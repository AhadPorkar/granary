package dev.granary.format.swift;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Releases kept by a hosted repository: publishing, reading and deleting them, and the index that answers
 * {@code identifiers?url=} from the {@code repositoryURLs} of the published metadata.
 *
 * <p>A release becomes visible when its metadata document is stored, which happens last, after the archive and
 * the manifests; readers therefore never see a release whose files are missing.
 */
final class SwiftHosted {

    private static final Logger log = LoggerFactory.getLogger(SwiftHosted.class);

    /** What a publish stored. */
    record Published(ObjectNode document, boolean replaced) {
    }

    private final StorageService storage;
    private final ObjectMapper json;
    private final SwiftDocuments documents;
    private final KeyedLocks releaseLocks = new KeyedLocks(64);
    private final KeyedLocks indexLocks = new KeyedLocks(16);

    SwiftHosted(StorageService storage, SwiftDocuments documents) {
        this.storage = storage;
        this.documents = documents;
        this.json = documents.mapper();
    }

    // --- reads --------------------------------------------------------------------------------------

    /** The releases of a package, highest precedence first; empty map when there are none. */
    Map<String, SwiftDocuments.Release> releases(Repository repo, SwiftIdentity id) {
        String prefix = SwiftLayout.packagePrefix(id);
        List<SwiftDocuments.Release> releases = new ArrayList<>();
        for (Asset asset : storage.list(repo, prefix)) {
            SwiftLayout.versionOfReleaseDocument(prefix, asset.getPath())
                    .ifPresent(v -> releases.add(new SwiftDocuments.Release(v, null, repo)));
        }
        return SwiftDocuments.sorted(releases);
    }

    Optional<ObjectNode> releaseDocument(Repository repo, SwiftIdentity id, String version) {
        return storage.find(repo, SwiftLayout.releaseDocumentPath(id, version)).flatMap(this::readObject);
    }

    /** The version-specific manifests of a release, from the stored files. */
    List<SwiftManifests.Alternate> alternates(Repository repo, SwiftIdentity id, String version) {
        String prefix = SwiftLayout.releasePrefix(id, version);
        List<SwiftManifests.Alternate> result = new ArrayList<>();
        for (Asset asset : storage.list(repo, prefix)) {
            String filename = asset.getPath().substring(prefix.length());
            if (!SwiftManifests.isAlternateFileName(filename)) {
                continue;
            }
            storage.find(repo, asset.getPath()).ifPresent(content -> {
                try {
                    SwiftManifests.toolsVersion(content.readUtf8())
                            .ifPresent(tools -> result.add(new SwiftManifests.Alternate(filename, tools, null)));
                } catch (IOException e) {
                    log.warn("cannot read {} in {}: {}", asset.getPath(), repo.getName(), e.getMessage());
                }
            });
        }
        return result;
    }

    Optional<StoredContent> manifest(Repository repo, SwiftIdentity id, String version, String filename) {
        return storage.find(repo, SwiftLayout.manifestPath(id, version, filename));
    }

    Optional<StoredContent> archive(Repository repo, SwiftIdentity id, String version) {
        return storage.find(repo, SwiftLayout.archivePath(id, version));
    }

    /**
     * The package identifiers registered for a normalised repository URL. Packages without any release left are
     * skipped: releases can also disappear outside the protocol (a component or asset deleted in the web UI or the
     * management API), which does not update the index, and SwiftPM must not be sent to a package it cannot resolve.
     */
    List<String> identifiers(Repository repo, String normalisedUrl) {
        List<String> registered = storage.find(repo, SwiftLayout.identifiersPath(normalisedUrl)).flatMap(this::readObject)
                .map(SwiftDocuments::parseIdentifiers).orElseGet(List::of);
        return registered.stream()
                .filter(id -> SwiftIdentity.parse(id).map(identity -> !releases(repo, identity).isEmpty()).orElse(false))
                .toList();
    }

    // --- publish ------------------------------------------------------------------------------------

    /**
     * Stores a release. The upload and archive have been validated; this checks for an existing release under
     * the release lock and writes the files, the metadata document last.
     */
    Published publish(Repository repo, SwiftIdentity id, String version, SwiftUpload upload, SourceArchive archive,
                      ObjectNode metadata, String signatureFormat) throws IOException {
        ReentrantLock lock = releaseLocks.lock(repo.getId(), id.key() + "@" + version);
        lock.lock();
        try {
            Optional<ObjectNode> existing = releaseDocument(repo, id, version);
            boolean exists = existing.isPresent() || storage.exists(repo, SwiftLayout.archivePath(id, version));
            if (exists && !repo.isAllowRedeploy()) {
                throw new SwiftProblem(HttpStatus.CONFLICT, "swift.release.exists",
                        "a release with version " + version + " already exists");
            }
            List<String> previousUrls = existing.map(doc -> SwiftMetadata.repositoryUrls(doc.path("metadata"))).orElse(List.of());
            if (exists) {
                deleteFiles(repo, id, version);
            }
            StorageService.ComponentCoordinates coordinates = coordinates(id, version);
            try (InputStream in = Files.newInputStream(upload.archive())) {
                storage.store(repo, SwiftLayout.archivePath(id, version), in, "application/zip", Asset.Origin.UPLOADED, false,
                        coordinates);
            }
            for (SourceArchive.Manifest m : archive.manifests().values()) {
                storage.store(repo, SwiftLayout.manifestPath(id, version, m.filename()), new ByteArrayInputStream(m.content()),
                        SwiftManifests.CONTENT_TYPE, Asset.Origin.UPLOADED, false, coordinates);
            }
            String signature = null;
            if (upload.signature() != null) {
                storage.store(repo, SwiftLayout.signaturePath(id, version), new ByteArrayInputStream(upload.signature()),
                        MediaType.APPLICATION_OCTET_STREAM_VALUE, Asset.Origin.UPLOADED, false, coordinates);
                signature = Base64.getEncoder().encodeToString(upload.signature());
            }
            if (upload.metadataSignature() != null) {
                storage.store(repo, SwiftLayout.metadataSignaturePath(id, version), new ByteArrayInputStream(upload.metadataSignature()),
                        MediaType.APPLICATION_OCTET_STREAM_VALUE, Asset.Origin.UPLOADED, false, coordinates);
            }
            ObjectNode document = documents.releaseDocument(id, version, upload.sha256(), signature,
                    signature == null ? null : signatureFormat, metadata, Instant.now());
            storage.store(repo, SwiftLayout.releaseDocumentPath(id, version), new ByteArrayInputStream(json.writeValueAsBytes(document)),
                    MediaType.APPLICATION_JSON_VALUE, Asset.Origin.UPLOADED, true, coordinates);
            updateIdentifiers(repo, id, previousUrls, SwiftMetadata.repositoryUrls(metadata));
            log.info("published {} {} to {} ({} bytes, sha256 {})", id.id(), version, repo.getName(), upload.size(), upload.sha256());
            return new Published(document, exists);
        } finally {
            lock.unlock();
        }
    }

    static StorageService.ComponentCoordinates coordinates(SwiftIdentity id, String version) {
        return new StorageService.ComponentCoordinates(id.lowerScope(), id.lowerName(), version);
    }

    // --- delete -------------------------------------------------------------------------------------

    /** Deletes one release; false when it does not exist. */
    boolean delete(Repository repo, SwiftIdentity id, String version) {
        ReentrantLock lock = releaseLocks.lock(repo.getId(), id.key() + "@" + version);
        lock.lock();
        try {
            Optional<ObjectNode> existing = releaseDocument(repo, id, version);
            boolean exists = existing.isPresent() || storage.exists(repo, SwiftLayout.archivePath(id, version));
            if (!exists) {
                return false;
            }
            deleteFiles(repo, id, version);
            existing.ifPresent(doc -> updateIdentifiers(repo, id, SwiftMetadata.repositoryUrls(doc.path("metadata")), List.of()));
            log.info("deleted {} {} from {}", id.id(), version, repo.getName());
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Deletes every release of a package; the number of releases deleted. */
    int deletePackage(Repository repo, SwiftIdentity id) {
        Set<String> versions = new LinkedHashSet<>(releases(repo, id).keySet());
        String prefix = SwiftLayout.packagePrefix(id);
        for (Asset asset : storage.list(repo, prefix)) {
            String rest = asset.getPath().substring(prefix.length());
            if (rest.endsWith(SwiftPath.ZIP_SUFFIX) && !rest.contains("/")) {
                String v = rest.substring(0, rest.length() - SwiftPath.ZIP_SUFFIX.length());
                if (SwiftVersion.isValid(v)) {
                    versions.add(v);
                }
            }
        }
        int deleted = 0;
        for (String v : versions) {
            if (delete(repo, id, v)) {
                deleted++;
            }
        }
        return deleted;
    }

    private void deleteFiles(Repository repo, SwiftIdentity id, String version) {
        // the metadata document first, so the release disappears before its files do
        storage.delete(repo, SwiftLayout.releaseDocumentPath(id, version));
        String prefix = SwiftLayout.releasePrefix(id, version);
        for (Asset asset : storage.list(repo, prefix)) {
            if (asset.getPath().startsWith(prefix)) {
                storage.delete(repo, asset.getPath());
            }
        }
        storage.delete(repo, SwiftLayout.archivePath(id, version));
    }

    // --- identifiers index --------------------------------------------------------------------------

    /**
     * Keeps {@code -/identifiers/<url>.json} in step after a release of {@code id} stopped listing
     * {@code removed} and started listing {@code added}: the package is added for every added URL and dropped from
     * a removed URL unless another release of the package still lists it.
     */
    private void updateIdentifiers(Repository repo, SwiftIdentity id, List<String> removed, List<String> added) {
        Set<String> addedKeys = normalised(added);
        Set<String> removedKeys = normalised(removed);
        removedKeys.removeAll(addedKeys);
        if (addedKeys.isEmpty() && removedKeys.isEmpty()) {
            return;
        }
        ReentrantLock lock = indexLocks.lock(repo.getId(), "identifiers");
        lock.lock();
        try {
            for (String url : addedKeys) {
                List<String> ids = new ArrayList<>(identifiers(repo, url));
                SwiftDocuments.addAll(ids, List.of(id.id()));
                writeIdentifiers(repo, url, ids);
            }
            if (!removedKeys.isEmpty()) {
                Set<String> stillListed = new LinkedHashSet<>();
                for (String version : releases(repo, id).keySet()) {
                    releaseDocument(repo, id, version).ifPresent(doc ->
                            stillListed.addAll(normalised(SwiftMetadata.repositoryUrls(doc.path("metadata")))));
                }
                for (String url : removedKeys) {
                    if (stillListed.contains(url)) {
                        continue;
                    }
                    List<String> ids = new ArrayList<>(identifiers(repo, url));
                    ids.removeIf(existing -> SwiftIdentity.parse(existing).map(id::sameAs).orElse(false));
                    writeIdentifiers(repo, url, ids);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void writeIdentifiers(Repository repo, String normalisedUrl, List<String> ids) {
        String path = SwiftLayout.identifiersPath(normalisedUrl);
        if (ids.isEmpty()) {
            storage.delete(repo, path);
            return;
        }
        ObjectNode doc = documents.identifiersDocument(ids);
        doc.put("url", normalisedUrl);
        storage.store(repo, path, new ByteArrayInputStream(json.writeValueAsBytes(doc)), MediaType.APPLICATION_JSON_VALUE,
                Asset.Origin.UPLOADED, true);
    }

    private static Set<String> normalised(List<String> urls) {
        Set<String> keys = new LinkedHashSet<>();
        for (String url : urls) {
            RepositoryUrls.normalise(url).ifPresent(keys::add);
        }
        return keys;
    }

    // --- helpers ------------------------------------------------------------------------------------

    Optional<ObjectNode> readObject(StoredContent content) {
        try {
            JsonNode node = json.readTree(content.readAllBytes());
            return node != null && node.isObject() ? Optional.of((ObjectNode) node) : Optional.empty();
        } catch (IOException | JacksonException e) {
            log.warn("cannot read {}: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }
}
