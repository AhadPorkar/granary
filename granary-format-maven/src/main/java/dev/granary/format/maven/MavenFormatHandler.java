package dev.granary.format.maven;

import dev.granary.core.format.ContentResolver;
import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.DirectoryListing;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.VersionPolicy;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Maven 2 layout. Hosted repositories accept {@code mvn deploy}, proxies cache a remote such as Maven
 * Central with offline fallback, and groups merge members including their {@code maven-metadata.xml}.
 */
@org.springframework.stereotype.Component
public class MavenFormatHandler implements FormatHandler {

    public static final String FORMAT = "maven2";

    private final StorageService storage;
    private final ContentResolver resolver;
    private final ProxyService proxy;
    private final GroupResolver groups;
    private final ComponentDao components;

    public MavenFormatHandler(StorageService storage, ContentResolver resolver, ProxyService proxy,
                              GroupResolver groups, ComponentDao components) {
        this.storage = storage;
        this.resolver = resolver;
        this.proxy = proxy;
        this.groups = groups;
        this.components = components;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Maven";
    }

    @Override
    public boolean isMetadataPath(String path) {
        return MavenPath.parse(path).isMutable();
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        MavenPath path = MavenPath.parse(request.path());
        return switch (request.method()) {
            case "GET", "HEAD" -> get(request, path);
            case "PUT" -> put(request, path);
            case "DELETE" -> delete(request, path);
            default -> ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                    request.method() + " is not supported by Maven repositories");
        };
    }

    // --- GET ---------------------------------------------------------------------------------

    private ResponseEntity<?> get(RepositoryRequest request, MavenPath path) throws IOException {
        Repository repo = request.repository();
        if (path.kind() == MavenPath.Kind.DIRECTORY) {
            return DirectoryListing.html(request, listChildren(repo, path.path()));
        }
        Optional<StoredContent> content = switch (repo.getType()) {
            case HOSTED -> storage.find(repo, path.path());
            case PROXY -> proxy.fetch(repo, path.path(), options(path));
            case GROUP -> path.kind() == MavenPath.Kind.METADATA
                    ? Optional.empty()
                    : resolver.resolve(repo, path.path(), options(path));
        };
        if (content.isPresent()) {
            return respond(request, content.get());
        }
        if (path.kind() == MavenPath.Kind.METADATA) {
            Optional<byte[]> generated = metadata(repo, path);
            return generated.<ResponseEntity<?>>map(xml -> ContentResponses.bytes(xml, "application/xml"))
                    .orElseGet(ContentResponses::notFound);
        }
        if (path.kind() == MavenPath.Kind.CHECKSUM) {
            return checksum(request, path);
        }
        return ContentResponses.notFound();
    }

    private ResponseEntity<?> respond(RepositoryRequest request, StoredContent content) {
        ResponseEntity<?> response = ContentResponses.stream(request, content);
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.asset());
        }
        return response;
    }

    /** Computes a missing checksum sidecar from its subject, so consumers never fail on a repository that stores none. */
    private ResponseEntity<?> checksum(RepositoryRequest request, MavenPath path) throws IOException {
        MavenPath subject = MavenPath.parse(path.checksumOf());
        String algorithm = Checksums.algorithmFor(path.checksumAlgorithm()).orElse(null);
        if (algorithm == null || subject.kind() == MavenPath.Kind.CHECKSUM) {
            return ContentResponses.notFound(); // no checksums of checksums
        }
        if (subject.kind() == MavenPath.Kind.METADATA) {
            Optional<byte[]> xml = metadataBytes(request.repository(), subject);
            return xml.<ResponseEntity<?>>map(bytes -> ContentResponses.utf8(Checksums.hex(algorithm, bytes), "text/plain"))
                    .orElseGet(ContentResponses::notFound);
        }
        Optional<StoredContent> content = resolver.resolve(request.repository(), subject.path(), options(subject));
        if (content.isEmpty()) {
            return ContentResponses.notFound();
        }
        return ContentResponses.utf8(Checksums.of(content.get(), algorithm), "text/plain");
    }

    /** The bytes a client gets for a metadata path: stored, generated from components, or merged over a group. */
    private Optional<byte[]> metadataBytes(Repository repo, MavenPath path) throws IOException {
        Optional<StoredContent> stored = switch (repo.getType()) {
            case HOSTED -> storage.find(repo, path.path());
            case PROXY -> proxy.fetch(repo, path.path(), FetchOptions.index());
            case GROUP -> Optional.empty();
        };
        if (stored.isPresent()) {
            return Optional.of(stored.get().readAllBytes());
        }
        return metadata(repo, path);
    }

    private Optional<byte[]> metadata(Repository repo, MavenPath path) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> generated(repo, path).map(MavenMetadata::toBytes);
            case PROXY -> Optional.empty();
            case GROUP -> {
                List<MavenMetadata> sources = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<byte[]> bytes = metadataBytes(member, path);
                    if (bytes.isPresent()) {
                        try {
                            sources.add(MavenMetadata.parse(bytes.get()));
                        } catch (IOException ignored) {
                            // a corrupt member document must not break the whole group
                        }
                    }
                }
                yield sources.isEmpty() ? Optional.empty() : Optional.of(MavenMetadataMerger.merge(sources).toBytes());
            }
        };
    }

    /** Artifact-level metadata derived from the components of a hosted repository (used when nothing was uploaded). */
    private Optional<MavenMetadata> generated(Repository repo, MavenPath path) {
        if (!path.isArtifactLevelMetadata() || path.artifactId() == null) {
            return Optional.empty();
        }
        List<dev.granary.core.model.Component> found = components.findByRepositoryIdAndNamespaceAndNameOrderByVersionAsc(
                repo.getId(), path.groupId(), path.artifactId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MavenMetadata md = new MavenMetadata();
        md.setGroupId(path.groupId());
        md.setArtifactId(path.artifactId());
        List<String> versions = found.stream().map(dev.granary.core.model.Component::getVersion).sorted(MavenVersions.COMPARATOR).toList();
        md.getVersions().addAll(versions);
        md.setLatest(versions.get(versions.size() - 1));
        versions.stream().filter(v -> !MavenPath.isSnapshot(v)).reduce((a, b) -> b).ifPresent(md::setRelease);
        // derived from the stored components, so the document (and its checksums) are stable between requests
        found.stream().map(dev.granary.core.model.Component::getUpdatedAt).filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder())
                .ifPresentOrElse(t -> md.setLastUpdated(MavenMetadata.LAST_UPDATED.format(t)),
                        () -> md.setLastUpdated(MavenMetadata.now()));
        return Optional.of(md);
    }

    private static FetchOptions options(MavenPath path) {
        return path.isMutable() ? FetchOptions.index() : FetchOptions.artifact();
    }

    // --- PUT ---------------------------------------------------------------------------------

    private ResponseEntity<?> put(RepositoryRequest request, MavenPath path) throws IOException {
        Repository repo = request.repository();
        if (!repo.isHosted()) {
            return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly",
                    "Artifacts can only be deployed to hosted repositories");
        }
        if (path.kind() == MavenPath.Kind.DIRECTORY) {
            return ContentResponses.error(HttpStatus.BAD_REQUEST, "path.invalid", "Cannot PUT a directory");
        }
        if (path.version() != null) {
            boolean snapshot = path.isSnapshot();
            if (snapshot && repo.getVersionPolicy() == VersionPolicy.RELEASE) {
                return ContentResponses.error(HttpStatus.BAD_REQUEST, "repository.maven.policy.release",
                        "This repository accepts release versions only");
            }
            if (!snapshot && repo.getVersionPolicy() == VersionPolicy.SNAPSHOT) {
                return ContentResponses.error(HttpStatus.BAD_REQUEST, "repository.maven.policy.snapshot",
                        "This repository accepts SNAPSHOT versions only");
            }
        }
        if (path.kind() == MavenPath.Kind.ARTIFACT && !path.isSnapshot() && !repo.isAllowRedeploy()
                && storage.exists(repo, path.path())) {
            return ContentResponses.error(HttpStatus.BAD_REQUEST, "repository.maven.redeploy",
                    "Release artifact already exists and redeployment is disabled");
        }
        if (path.kind() == MavenPath.Kind.CHECKSUM) {
            return putChecksum(request, path);
        }
        StorageService.ComponentCoordinates coordinates = path.kind() == MavenPath.Kind.ARTIFACT
                ? new StorageService.ComponentCoordinates(path.groupId(), path.artifactId(), path.version())
                : null;
        try (InputStream body = request.body()) {
            storage.store(repo, path.path(), body, null, Asset.Origin.UPLOADED,
                    path.kind() == MavenPath.Kind.METADATA, coordinates);
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** Accepts a checksum sidecar, verifying it against the subject when the subject is already stored. */
    private ResponseEntity<?> putChecksum(RepositoryRequest request, MavenPath path) throws IOException {
        byte[] body = request.body().readAllBytes();
        String uploaded = new String(body, StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase();
        Optional<StoredContent> subject = storage.find(request.repository(), path.checksumOf());
        String algorithm = Checksums.algorithmFor(path.checksumAlgorithm()).orElse(null);
        if (subject.isPresent() && algorithm != null) {
            String expected = Checksums.of(subject.get(), algorithm);
            if (!expected.equalsIgnoreCase(uploaded)) {
                return ContentResponses.error(HttpStatus.BAD_REQUEST, "repository.maven.checksumMismatch",
                        "Uploaded " + path.checksumAlgorithm() + " does not match the stored artifact");
            }
        }
        storage.store(request.repository(), path.path(), new ByteArrayInputStream(body), "text/plain",
                Asset.Origin.UPLOADED, MavenPath.parse(path.checksumOf()).isMutable());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // --- DELETE ------------------------------------------------------------------------------

    private ResponseEntity<?> delete(RepositoryRequest request, MavenPath path) {
        Repository repo = request.repository();
        if (repo.isGroup()) {
            return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly",
                    "Delete from a member repository instead of the group");
        }
        if (path.kind() == MavenPath.Kind.DIRECTORY) {
            int deleted = 0;
            for (Asset asset : storage.list(repo, path.path())) {
                if (storage.delete(repo, asset.getPath())) {
                    deleted++;
                }
            }
            return deleted == 0 ? ContentResponses.notFound() : ResponseEntity.noContent().build();
        }
        return storage.delete(repo, path.path())
                ? ResponseEntity.noContent().build()
                : ContentResponses.notFound();
    }

    // --- browsing ----------------------------------------------------------------------------

    private Set<String> listChildren(Repository repo, String prefix) {
        Set<String> children = new TreeSet<>();
        List<Repository> sources = repo.isGroup() ? groups.leafMembers(repo) : List.of(repo);
        for (Repository source : sources) {
            for (Asset asset : storage.list(source, prefix)) {
                String rest = asset.getPath().substring(prefix.length());
                int slash = rest.indexOf('/');
                children.add(slash < 0 ? rest : rest.substring(0, slash + 1));
            }
        }
        return new LinkedHashSet<>(children);
    }
}
