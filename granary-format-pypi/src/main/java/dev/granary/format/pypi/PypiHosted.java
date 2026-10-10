package dev.granary.format.pypi;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A hosted PyPI repository: distribution files under {@code packages/<name>/<version>/<file>} with a
 * PEP 658 {@code <file>.metadata} sidecar next to each, and index documents built from them on request
 * so nothing can get out of sync.
 */
@Component
public class PypiHosted {

    public static final String METADATA_SUFFIX = ".metadata";
    public static final String FILE_CONTENT_TYPE = "application/octet-stream";
    public static final String METADATA_CONTENT_TYPE = "application/octet-stream";

    private static final Logger log = LoggerFactory.getLogger(PypiHosted.class);

    /** Uploads to one project are serialised so two clients cannot both pass the "already exists" check. */
    private static final int LOCK_STRIPES = 64;

    private final StorageService storage;
    private final ReentrantLock[] projectLocks = new ReentrantLock[LOCK_STRIPES];

    public PypiHosted(StorageService storage) {
        this.storage = storage;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            projectLocks[i] = new ReentrantLock();
        }
    }

    private ReentrantLock lockFor(Repository repo, String normalisedName) {
        return projectLocks[Math.floorMod((repo.getId() + "/" + normalisedName).hashCode(), LOCK_STRIPES)];
    }

    /**
     * A stored distribution file with everything the index documents need to know about it.
     *
     * @param file         the simple-API view, URL already absolute
     * @param distribution what the file name says
     * @param metadata     the parsed sidecar, or {@code null} when there is none
     * @param uploaded     when the file was stored
     * @param path         the repository-relative path of the file
     */
    public record HostedFile(PypiFile file, PypiNames.Distribution distribution, PypiMetadata metadata, Instant uploaded,
                             String path) {
    }

    public static String filePath(String normalisedName, String version, String filename) {
        return PypiPath.PACKAGES + normalisedName + "/" + version + "/" + filename;
    }

    /** Every project with at least one distribution file, normalised and sorted. */
    public List<String> projectNames(Repository repo) {
        TreeSet<String> names = new TreeSet<>();
        for (Asset asset : storage.list(repo, PypiPath.PACKAGES)) {
            String[] s = asset.getPath().substring(PypiPath.PACKAGES.length()).split("/", -1);
            if (s.length == 3 && PypiNames.isDistributionFile(s[2])) {
                names.add(s[0]);
            }
        }
        return new ArrayList<>(names);
    }

    /** The project's files with their sidecars, oldest version first; empty when the project has no files. */
    public List<HostedFile> files(Repository repo, String normalisedName, String baseUrl) {
        String prefix = PypiPath.PACKAGES + normalisedName + "/";
        Map<String, Asset> sidecars = new LinkedHashMap<>();
        List<Asset> distributions = new ArrayList<>();
        for (Asset asset : storage.list(repo, prefix)) {
            String[] s = asset.getPath().substring(prefix.length()).split("/", -1);
            if (s.length != 2) {
                continue;
            }
            if (s[1].endsWith(METADATA_SUFFIX)) {
                sidecars.put(asset.getPath(), asset);
            } else if (PypiNames.isDistributionFile(s[1])) {
                distributions.add(asset);
            }
        }
        List<HostedFile> result = new ArrayList<>();
        for (Asset asset : distributions) {
            String filename = asset.getPath().substring(asset.getPath().lastIndexOf('/') + 1);
            PypiNames.Distribution distribution = PypiNames.parseFilename(filename).orElseThrow();
            Asset sidecar = sidecars.get(asset.getPath() + METADATA_SUFFIX);
            PypiMetadata metadata = sidecar == null ? null : readMetadata(repo, sidecar);
            Instant uploaded = asset.getCreatedAt() != null ? asset.getCreatedAt() : Instant.now();
            PypiFile file = PypiFile.of(filename, UrlPaths.join(baseUrl, asset.getPath()), asset.getBlobSha256())
                    .withSize(asset.getSize())
                    .withUploadTime(DateTimeFormatter.ISO_INSTANT.format(uploaded));
            if (metadata != null) {
                file = file.withRequiresPython(metadata.requiresPython().orElse(null));
            }
            if (sidecar != null) {
                file = file.withCoreMetadata(Map.of(PypiFile.SHA256, sidecar.getBlobSha256()));
            }
            result.add(new HostedFile(file, distribution, metadata, uploaded, asset.getPath()));
        }
        result.sort((a, b) -> {
            int c = PypiVersion.sort(List.of(a.distribution().version(), b.distribution().version()))
                    .getFirst().equals(a.distribution().version()) ? -1 : 1;
            return a.distribution().version().equals(b.distribution().version())
                    ? a.file().filename().compareTo(b.file().filename()) : c;
        });
        return result;
    }

    public Optional<PypiProject> project(Repository repo, String normalisedName, String baseUrl) {
        List<HostedFile> files = files(repo, normalisedName, baseUrl);
        if (files.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(PypiProject.of(normalisedName, files.stream().map(HostedFile::file).toList()));
    }

    private PypiMetadata readMetadata(Repository repo, Asset sidecar) {
        try {
            return PypiMetadata.parse(new StoredContent(sidecar, storage.blobStoreOf(repo)).readAllBytes());
        } catch (IOException e) {
            log.warn("metadata sidecar {} in {} is unreadable: {}", sidecar.getPath(), repo.getName(), e.getMessage());
            return null;
        }
    }

    public Optional<StoredContent> file(Repository repo, String path) {
        return storage.find(repo, path);
    }

    /**
     * Stores an upload and its metadata sidecar. The sidecar is the archive's own {@code METADATA} or
     * {@code PKG-INFO} when it has one, otherwise it is built from the form fields.
     *
     * <p>A file name is unique within a project, whatever version directory it was uploaded under, and a version
     * spelled differently from one already stored ({@code 1.0} next to {@code 1.0.0}) joins that release instead of
     * starting a second one, the way pypi.org compares versions.
     *
     * @return the repository-relative path of the stored file
     */
    public String store(Repository repo, PypiUpload upload, String baseUrl) throws IOException {
        String name = upload.normalisedName();
        ReentrantLock lock = lockFor(repo, name);
        lock.lock();
        try {
            String version = upload.version();
            String existing = null;
            String prefix = PypiPath.PACKAGES + name + "/";
            Optional<PypiVersion> wanted = PypiVersion.tryParse(version);
            for (Asset asset : storage.list(repo, prefix)) {
                String[] s = asset.getPath().substring(prefix.length()).split("/", -1);
                if (s.length != 2) {
                    continue;
                }
                if (s[1].equals(upload.filename())) {
                    existing = asset.getPath();
                    version = s[0];
                } else if (existing == null && !s[0].equals(version) && wanted.isPresent()
                        && PypiVersion.tryParse(s[0]).filter(v -> v.compareTo(wanted.get()) == 0).isPresent()) {
                    version = s[0];
                }
            }
            if (existing != null && !repo.isAllowRedeploy()) {
                throw PypiError.fileExists(upload.filename(), UrlPaths.join(baseUrl, PypiPath.SIMPLE + name + "/"));
            }
            String path = existing != null ? existing : filePath(name, version, upload.filename());
            byte[] metadata = extractMetadata(upload);
            checkArchiveMetadata(upload, metadata);
            StorageService.ComponentCoordinates coordinates = new StorageService.ComponentCoordinates("", name, version);
            try (InputStream in = Files.newInputStream(upload.file())) {
                storage.store(repo, path, in, FILE_CONTENT_TYPE, Asset.Origin.UPLOADED, false, coordinates);
            }
            storage.store(repo, path + METADATA_SUFFIX, new ByteArrayInputStream(metadata), METADATA_CONTENT_TYPE,
                    Asset.Origin.UPLOADED, false, coordinates);
            log.info("stored {} {} ({}) in {}", name, version, upload.filename(), repo.getName());
            return path;
        } finally {
            lock.unlock();
        }
    }

    private static byte[] extractMetadata(PypiUpload upload) throws IOException {
        Optional<byte[]> fromArchive;
        try {
            fromArchive = switch (upload.distribution().type()) {
                case WHEEL -> PypiMetadata.fromWheel(upload.file(), upload.name(), upload.distribution().version());
                case SDIST -> PypiMetadata.fromSdist(upload.file(), upload.filename());
            };
        } catch (IOException e) {
            if (upload.distribution().type() == PypiNames.DistributionType.WHEEL) {
                throw PypiError.badRequest(upload.filename() + " is not a valid wheel: " + e.getMessage());
            }
            log.info("cannot read the archive of {}; building its metadata from the form fields ({})", upload.filename(),
                    e.getMessage());
            fromArchive = Optional.empty();
        }
        return fromArchive.orElseGet(() -> PypiMetadata.fromUploadFields(upload.fields()).toBytes());
    }

    /**
     * The core metadata inside the archive must describe the project and version the form claims; otherwise a
     * mislabelled build would be served under the wrong name.
     */
    private static void checkArchiveMetadata(PypiUpload upload, byte[] metadata) {
        PypiMetadata parsed = PypiMetadata.parse(metadata);
        Optional<String> name = parsed.name().map(PypiNames::normalise);
        if (name.isPresent() && !name.get().equals(upload.normalisedName())) {
            throw PypiError.badRequest("The metadata inside " + upload.filename() + " names project '" + parsed.name().get()
                    + "', not '" + upload.name() + "'");
        }
        Optional<PypiVersion> version = parsed.version().flatMap(PypiVersion::tryParse);
        Optional<PypiVersion> claimed = PypiVersion.tryParse(upload.version());
        if (version.isPresent() && claimed.isPresent() && version.get().compareTo(claimed.get()) != 0) {
            throw PypiError.badRequest("The metadata inside " + upload.filename() + " is for version "
                    + parsed.version().get() + ", not " + upload.version());
        }
    }

    /** Removes one distribution file and its sidecar. Returns {@code false} when there was no such file. */
    public boolean deleteFile(Repository repo, String path) {
        boolean deleted = storage.delete(repo, path);
        if (deleted) {
            storage.delete(repo, path + METADATA_SUFFIX);
            log.info("deleted {} from {}", path, repo.getName());
        }
        return deleted;
    }

    /**
     * Removes every file of a version, also when it is spelled differently from the stored one ({@code 1.0} for
     * {@code 1.0.0}). Returns the number of distribution files removed.
     */
    public int deleteVersion(Repository repo, String normalisedName, String version) {
        String prefix = PypiPath.PACKAGES + normalisedName + "/";
        Optional<PypiVersion> wanted = PypiVersion.tryParse(version);
        int deleted = 0;
        for (Asset asset : storage.list(repo, prefix)) {
            String[] s = asset.getPath().substring(prefix.length()).split("/", -1);
            boolean sameVersion = s.length == 2 && (s[0].equals(version) || (wanted.isPresent()
                    && PypiVersion.tryParse(s[0]).filter(v -> v.compareTo(wanted.get()) == 0).isPresent()));
            if (sameVersion && storage.delete(repo, asset.getPath()) && !asset.getPath().endsWith(METADATA_SUFFIX)) {
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("deleted {} {} ({} files) from {}", normalisedName, version, deleted, repo.getName());
        }
        return deleted;
    }
}
