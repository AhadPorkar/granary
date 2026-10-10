package dev.granary.format.cocoapods;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * A hosted spec repository. Podspecs live at their CDN path ({@code Specs/a/7/5/<Name>/<version>/<Name>.podspec.json},
 * standard sharding), uploaded source archives under {@code sources/<Name>/<version>/<file>}. The shard indexes,
 * {@code all_pods.txt} and {@code CocoaPods-version.yml} are built from the stored podspecs on every request, so
 * they can never disagree with what is stored, also after a version was deleted in the web UI. Only
 * {@code deprecated_podspecs.txt} is kept as a file, because deprecation is a property of the podspec's content;
 * it is rewritten with every push and delete.
 */
@Component
public class CocoaPodsHosted {

    private static final Logger log = LoggerFactory.getLogger(CocoaPodsHosted.class);
    private static final int LOCK_STRIPES = 64;

    private final StorageService storage;
    /** Pushes of one pod are serialised so two clients cannot both pass the "already exists" check. */
    private final ReentrantLock[] podLocks = new ReentrantLock[LOCK_STRIPES];
    /** The deprecated list of a repository is read, changed and written back under this lock. */
    private final ReentrantLock[] listLocks = new ReentrantLock[LOCK_STRIPES];

    public CocoaPodsHosted(StorageService storage) {
        this.storage = storage;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            podLocks[i] = new ReentrantLock();
            listLocks[i] = new ReentrantLock();
        }
    }

    private ReentrantLock podLock(Repository repo, String name) {
        return podLocks[Math.floorMod((repo.getId() + "/" + name).hashCode(), LOCK_STRIPES)];
    }

    private ReentrantLock listLock(Repository repo) {
        return listLocks[Math.floorMod(String.valueOf(repo.getId()).hashCode(), LOCK_STRIPES)];
    }

    /**
     * A source archive spooled to disk for a push.
     *
     * @param file     the spooled bytes
     * @param filename the client's file name, may be {@code null}
     */
    public record Archive(Path file, String filename) {
    }

    /**
     * What a push stored.
     *
     * @param name        pod name
     * @param version     version
     * @param podspecPath repository-relative path of the podspec
     * @param sourcePath  repository-relative path of the uploaded archive, or {@code null}
     */
    public record Pushed(String name, String version, String podspecPath, String sourcePath) {
    }

    // --- reading ---------------------------------------------------------------------------

    /** Pod name to versions for one shard of the standard layout. */
    public Map<String, List<String>> shard(Repository repo, List<String> fragment) {
        Map<String, List<String>> pods = new LinkedHashMap<>();
        for (Asset asset : storage.list(repo, ShardLayout.shardDirectory(fragment))) {
            CocoaPodsPath p = CocoaPodsPath.parse(asset.getPath());
            if (p.kind() == CocoaPodsPath.Kind.PODSPEC && p.fragment().equals(fragment)) {
                pods.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p.version());
            }
        }
        return pods;
    }

    /** Every pod with at least one stored podspec. */
    public List<String> podNames(Repository repo) {
        TreeSet<String> names = new TreeSet<>(PodNames.CODE_POINT_ORDER);
        for (Asset asset : storage.list(repo, ShardLayout.SPECS)) {
            CocoaPodsPath p = CocoaPodsPath.parse(asset.getPath());
            if (p.kind() == CocoaPodsPath.Kind.PODSPEC) {
                names.add(p.name());
            }
        }
        return new ArrayList<>(names);
    }

    /** The podspec paths of deprecated versions, as stored. */
    public List<String> deprecated(Repository repo) {
        return storage.find(repo, CocoaPodsPath.DEPRECATED).map(CocoaPodsHosted::lines).orElse(List.of());
    }

    public Optional<StoredContent> deprecatedFile(Repository repo) {
        return storage.find(repo, CocoaPodsPath.DEPRECATED);
    }

    public Optional<StoredContent> podspec(Repository repo, String name, String version) {
        return storage.find(repo, ShardLayout.STANDARD.podspecPath(name, version));
    }

    public Optional<StoredContent> source(Repository repo, String name, String version, String filename) {
        return storage.find(repo, CocoaPodsPath.sourcePath(name, version, filename));
    }

    /**
     * Whether a stored podspec's {@code source.http} is an archive stored in this repository (pushed with the podspec
     * or uploaded to {@code sources/…}); such URLs are rewritten to whichever URL the client used.
     */
    public boolean isLocalSource(Repository repo, String name, String version, String url) {
        Optional<String> path = SourceArchive.httpPath(url);
        if (path.isEmpty()) {
            return false;
        }
        String filename = path.get().substring(path.get().lastIndexOf('/') + 1);
        String suffix = "/" + CocoaPodsPath.sourcePath(name, version, filename);
        return path.get().endsWith(suffix) && storage.exists(repo, CocoaPodsPath.sourcePath(name, version, filename));
    }

    // --- writing ---------------------------------------------------------------------------

    /**
     * Validates and stores a pushed podspec, and the source archive pushed with it.
     *
     * @param pathName    the pod name the URL names, or {@code null} for {@code api/pods}
     * @param pathVersion the version the URL names, or {@code null}
     * @param archive     an uploaded source archive, or {@code null}
     * @param baseUrl     the repository URL, for the archive's {@code source.http}
     */
    public Pushed push(Repository repo, byte[] body, String pathName, String pathVersion, Archive archive, String baseUrl)
            throws IOException {
        Podspec spec = Podspec.parse(body);
        if (spec.node().has("version") && !spec.node().get("version").isString()) {
            throw CocoaPodsError.invalidPodspec("The podspec's version must be a string, e.g. \"1.0.0\"");
        }
        String name = spec.name().orElseThrow(() -> CocoaPodsError.invalidPodspec("The podspec has no name"));
        String version = spec.version().orElseThrow(() -> CocoaPodsError.invalidPodspec("The podspec has no version"));
        PodNames.pushProblem(name).ifPresent(problem -> {
            throw CocoaPodsError.invalidPodspec(problem);
        });
        PodNames.versionProblem(version).ifPresent(problem -> {
            throw CocoaPodsError.invalidPodspec(problem);
        });
        if (pathName != null && !pathName.equals(name)) {
            throw CocoaPodsError.invalidPodspec("The podspec is for '" + name + "' but the URL names '" + pathName + "'");
        }
        if (pathVersion != null && !pathVersion.equals(version)) {
            throw CocoaPodsError.invalidPodspec("The podspec is for version " + version + " but the URL names " + pathVersion);
        }
        if (archive == null && spec.source().isEmpty()) {
            throw CocoaPodsError.invalidPodspec("The podspec has no source; CocoaPods could not download " + name);
        }
        SourceArchive.Type type = archive == null ? null : archiveType(spec, archive);
        String podspecPath = ShardLayout.STANDARD.podspecPath(name, version);
        StorageService.ComponentCoordinates coordinates = new StorageService.ComponentCoordinates("", name, version);
        ReentrantLock lock = podLock(repo, name);
        lock.lock();
        try {
            if (!repo.isAllowRedeploy() && storage.exists(repo, podspecPath)) {
                throw CocoaPodsError.versionExists(name, version);
            }
            byte[] stored = body;
            String sourcePath = null;
            if (archive != null) {
                String filename = SourceArchive.sanitise(archive.filename(), type);
                sourcePath = CocoaPodsPath.sourcePath(name, version, filename);
                String sha256 = checkDigests(spec, archive.file());
                removeOtherSources(repo, name, version, filename);
                try (InputStream in = Files.newInputStream(archive.file())) {
                    storage.store(repo, sourcePath, in, contentType(type), Asset.Origin.UPLOADED, false, coordinates);
                }
                boolean implied = SourceArchive.Type.ofPath(filename).filter(t -> t == type).isPresent();
                stored = spec.withUploadedSource(UrlPaths.join(baseUrl, sourcePath), type, sha256, implied).toBytes();
            }
            storage.store(repo, podspecPath, new ByteArrayInputStream(stored), Podspec.CONTENT_TYPE, Asset.Origin.UPLOADED,
                    false, coordinates);
            updateDeprecated(repo, podspecPath, spec.isDeprecated());
            log.info("stored pod {} {} in {}{}", name, version, repo.getName(), sourcePath == null ? "" : " with its source archive");
            return new Pushed(name, version, podspecPath, sourcePath);
        } finally {
            lock.unlock();
        }
    }

    /** The archive's type: from its file name, else from the podspec's {@code source.type}; it must match the bytes. */
    private static SourceArchive.Type archiveType(Podspec spec, Archive archive) throws IOException {
        Optional<SourceArchive.Type> fromName = SourceArchive.Type.ofPath(archive.filename());
        Optional<SourceArchive.Type> fromSpec = spec.sourceType().flatMap(SourceArchive.Type::ofOption);
        if (spec.sourceType().isPresent() && fromSpec.isEmpty()) {
            throw CocoaPodsError.invalidPodspec("Unsupported source.type '" + spec.sourceType().get()
                    + "'; CocoaPods knows zip, tgz, tar, tbz, txz and dmg");
        }
        SourceArchive.Type type = fromName.or(() -> fromSpec).orElseThrow(() -> CocoaPodsError.badRequest(
                "Cannot tell the archive type of the source: name the file .zip, .tar.gz, .tgz, .tar, .tar.bz2, .tar.xz or "
                        + ".dmg, or set source.type in the podspec"));
        byte[] head;
        try (InputStream in = Files.newInputStream(archive.file())) {
            head = in.readNBytes(512);
        }
        if (head.length == 0) {
            throw CocoaPodsError.badRequest("The source archive is empty");
        }
        if (!SourceArchive.looksLike(type, head)) {
            throw CocoaPodsError.badRequest("The source archive is not a valid " + type.option() + " file");
        }
        return type;
    }

    /** SHA-256 of the archive; a {@code sha256} or {@code sha1} the podspec already states must match it. */
    private static String checkDigests(Podspec spec, Path file) throws IOException {
        String sha256;
        try (InputStream in = Files.newInputStream(file)) {
            sha256 = Checksums.hex("SHA-256", in);
        }
        Optional<String> claimed256 = spec.source().map(s -> s.get("sha256")).filter(n -> n.isString()).map(n -> n.asString());
        if (claimed256.isPresent() && !claimed256.get().equalsIgnoreCase(sha256)) {
            throw CocoaPodsError.badRequest("The podspec's source.sha256 " + claimed256.get()
                    + " does not match the uploaded archive (" + sha256 + ")");
        }
        Optional<String> claimed1 = spec.source().map(s -> s.get("sha1")).filter(n -> n.isString()).map(n -> n.asString());
        if (claimed1.isPresent()) {
            String sha1;
            try (InputStream in = Files.newInputStream(file)) {
                sha1 = Checksums.hex("SHA-1", in);
            }
            if (!claimed1.get().equalsIgnoreCase(sha1)) {
                throw CocoaPodsError.badRequest("The podspec's source.sha1 " + claimed1.get()
                        + " does not match the uploaded archive (" + sha1 + ")");
            }
        }
        return sha256;
    }

    private void removeOtherSources(Repository repo, String name, String version, String keep) {
        String dir = CocoaPodsPath.sourceDirectory(name, version);
        for (Asset asset : storage.list(repo, dir)) {
            if (!asset.getPath().equals(dir + keep)) {
                storage.delete(repo, asset.getPath());
            }
        }
    }

    /**
     * Stores a source archive uploaded on its own ({@code PUT sources/<Name>/<version>/<file>}); a podspec pushed
     * afterwards can point its {@code source.http} at it.
     */
    public String putSource(Repository repo, String name, String version, String filename, Path file) throws IOException {
        PodNames.pushProblem(name).ifPresent(problem -> {
            throw CocoaPodsError.badRequest(problem);
        });
        PodNames.versionProblem(version).ifPresent(problem -> {
            throw CocoaPodsError.badRequest(problem);
        });
        SourceArchive.Type type = SourceArchive.Type.ofPath(filename).orElseThrow(() -> CocoaPodsError.badRequest(
                "Name the archive .zip, .tar.gz, .tgz, .tar, .tar.bz2, .tar.xz or .dmg so CocoaPods knows how to unpack it"));
        if (!SourceArchive.sanitise(filename, type).equals(filename)) {
            throw CocoaPodsError.badRequest("Archive file names may only contain letters, digits and . _ + ~ -");
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(512);
        }
        if (head.length == 0 || !SourceArchive.looksLike(type, head)) {
            throw CocoaPodsError.badRequest("The upload is not a valid " + type.option() + " file");
        }
        String path = CocoaPodsPath.sourcePath(name, version, filename);
        ReentrantLock lock = podLock(repo, name);
        lock.lock();
        try {
            if (!repo.isAllowRedeploy() && storage.exists(repo, path)) {
                throw new CocoaPodsError(org.springframework.http.HttpStatus.CONFLICT, "repository.cocoapods.versionExists",
                        filename + " of " + name + " " + version + " already exists and redeployment is disabled for this repository");
            }
            try (InputStream in = Files.newInputStream(file)) {
                storage.store(repo, path, in, contentType(type), Asset.Origin.UPLOADED, false,
                        new StorageService.ComponentCoordinates("", name, version));
            }
            log.info("stored source archive {} in {}", path, repo.getName());
            return path;
        } finally {
            lock.unlock();
        }
    }

    /** Removes a version: its podspec and its source archives. Returns the number of files removed. */
    public int deleteVersion(Repository repo, String name, String version) {
        ReentrantLock lock = podLock(repo, name);
        lock.lock();
        try {
            int deleted = 0;
            String podspecPath = ShardLayout.STANDARD.podspecPath(name, version);
            if (storage.delete(repo, podspecPath)) {
                deleted++;
            }
            String dir = CocoaPodsPath.sourceDirectory(name, version);
            for (Asset asset : storage.list(repo, dir)) {
                if (storage.delete(repo, asset.getPath())) {
                    deleted++;
                }
            }
            if (deleted > 0) {
                updateDeprecated(repo, podspecPath, false);
                log.info("deleted pod {} {} ({} files) from {}", name, version, deleted, repo.getName());
            }
            return deleted;
        } finally {
            lock.unlock();
        }
    }

    public boolean deleteSource(Repository repo, String name, String version, String filename) {
        return storage.delete(repo, CocoaPodsPath.sourcePath(name, version, filename));
    }

    /** Adds the podspec to, or removes it from, {@code deprecated_podspecs.txt}; an empty list is removed. */
    private void updateDeprecated(Repository repo, String podspecPath, boolean deprecated) {
        ReentrantLock lock = listLock(repo);
        lock.lock();
        try {
            List<String> current = deprecated(repo);
            if (deprecated == current.contains(podspecPath)) {
                return;
            }
            List<String> next = new ArrayList<>(current);
            if (deprecated) {
                next.add(podspecPath);
            } else {
                next.remove(podspecPath);
            }
            if (next.isEmpty()) {
                storage.delete(repo, CocoaPodsPath.DEPRECATED);
            } else {
                storage.store(repo, CocoaPodsPath.DEPRECATED, new ByteArrayInputStream(PodIndex.renderLines(next)),
                        PodIndex.CONTENT_TYPE, Asset.Origin.UPLOADED, true);
            }
        } finally {
            lock.unlock();
        }
    }

    private static List<String> lines(StoredContent content) {
        try {
            return PodIndex.lines(content.readUtf8());
        } catch (IOException e) {
            log.warn("{} is unreadable: {}", content.asset().getPath(), e.getMessage());
            return List.of();
        }
    }

    static String contentType(SourceArchive.Type type) {
        return switch (type) {
            case ZIP -> "application/zip";
            case TGZ -> "application/gzip";
            case TAR -> "application/x-tar";
            case TBZ -> "application/x-bzip2";
            case TXZ -> "application/x-xz";
            case DMG -> "application/x-apple-diskimage";
        };
    }
}
