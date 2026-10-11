package dev.granary.format.go;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Every write a hosted Go repository accepts.
 *
 * <p>A version is published by uploading its zip; the {@code .mod} is taken from the zip's go.mod and the
 * {@code .info} is generated with the upload time. Both can be replaced afterwards with a {@code PUT} of the
 * file the go command produced ({@code go mod download -json} keeps them in {@code GOMODCACHE/cache/download}).
 * Files a client uploaded are never overwritten by a later zip upload; files the server derived follow the zip.
 * Directory entries are dropped from uploaded zips so that the stored zip hashes like the module it unpacks to.
 */
@Component
public class GoHostedWriter {

    public static final String ZIP_CONTENT_TYPE = "application/zip";

    /**
     * The outcome of a zip upload.
     *
     * @param module  decoded module path
     * @param version decoded version
     * @param files   repository paths written
     */
    public record Published(String module, String version, List<String> files) {
    }

    /** Lock stripes for the writes of one module version (repository, module and version hashed onto a stripe). */
    private static final int STRIPES = 64;

    private final StorageService storage;
    private final ObjectMapper mapper;
    private final Path tempDir;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public GoHostedWriter(StorageService storage, ObjectMapper mapper, GranaryProperties properties) {
        this.storage = storage;
        this.mapper = mapper;
        this.tempDir = properties.getDataDir().resolve("tmp").resolve("go");
        Arrays.setAll(locks, i -> new ReentrantLock());
    }

    /** {@code PUT <module>/@v/<version>.zip}. The caller closes {@code body}. */
    public Published publishZip(Repository repo, GoPath path, InputStream body) throws IOException {
        requireCanonical(path);
        String zipPath = path.filePath("zip");
        // fail fast, before reading the body
        requireNewOrRedeployable(repo, path, zipPath);
        Files.createDirectories(tempDir);
        Path temp = Files.createTempFile(tempDir, "upload-", ".zip");
        Path normalised = null;
        try {
            long size = copyBounded(body, temp);
            if (size == 0) {
                throw GoError.badRequest("the request body is empty; send the module zip as the body of the PUT");
            }
            GoModuleZip.Contents contents = GoModuleZip.check(temp, path.module(), path.version());
            byte[] goMod = contents.goMod();
            if (goMod == null) {
                if (!"+incompatible".equals(GoSemver.build(path.version()))) {
                    throw GoError.invalidZip("the zip has no " + path.module() + "@" + path.version()
                            + "/go.mod; a module without go.mod can only be published as a +incompatible version");
                }
                goMod = GoModFile.synthetic(path.module());
            } else {
                GoModFile.checkDeclaredPath(goMod, path.module());
            }
            Path stored = temp;
            if (contents.directories() > 0) {
                normalised = Files.createTempFile(tempDir, "upload-", ".zip");
                GoModuleZip.copyWithoutDirectories(temp, normalised);
                stored = normalised;
            }
            StorageService.ComponentCoordinates coordinates = coordinates(path);
            List<String> files = new ArrayList<>();
            ReentrantLock lock = lockFor(repo, path);
            lock.lock();
            try {
                // checked again now that this upload owns the version: another one may have finished meanwhile
                requireNewOrRedeployable(repo, path, zipPath);
                try (InputStream in = Files.newInputStream(stored)) {
                    storage.store(repo, zipPath, in, ZIP_CONTENT_TYPE, Asset.Origin.UPLOADED, false, coordinates);
                }
                files.add(zipPath);
                if (storeDerived(repo, path.filePath("mod"), goMod, GoModFile.CONTENT_TYPE, coordinates)) {
                    files.add(path.filePath("mod"));
                }
                if (storeDerived(repo, path.filePath("info"), GoInfo.now(path.version()).toJson(mapper), GoInfo.CONTENT_TYPE, coordinates)) {
                    files.add(path.filePath("info"));
                }
            } finally {
                lock.unlock();
            }
            return new Published(path.module(), path.version(), files);
        } finally {
            Files.deleteIfExists(temp);
            if (normalised != null) {
                Files.deleteIfExists(normalised);
            }
        }
    }

    /**
     * {@code PUT <module>/@v/<version>.info} of a published version: replaces the generated document, for example
     * with the real tag time. An {@code .info} a client uploaded before is kept unless the repository allows
     * redeploys; uploading the same document again is accepted. Returns whether anything was written.
     */
    public boolean putInfo(Repository repo, GoPath path, byte[] body) {
        requireCanonical(path);
        byte[] json = GoInfo.validateUpload(mapper, body, path.version());
        return putVersionFile(repo, path, "info", json, GoInfo.CONTENT_TYPE, true);
    }

    /**
     * {@code PUT <module>/@v/<version>.mod} of a published version. Consumers record the hash of the go.mod in their
     * go.sum files, so the go.mod already served (taken from the zip, or uploaded) can only be uploaded again as it
     * is, which is what publishing the files of {@code GOMODCACHE/cache/download} does; a different one replaces it
     * only when the repository allows redeploys. Returns whether anything was written.
     */
    public boolean putMod(Repository repo, GoPath path, byte[] body) {
        requireCanonical(path);
        if (body.length == 0) {
            throw GoError.badRequest("the request body is empty; send the go.mod as the body of the PUT");
        }
        GoModFile.checkDeclaredPath(body, path.module());
        return putVersionFile(repo, path, "mod", body, GoModFile.CONTENT_TYPE, false);
    }

    /**
     * Stores an {@code .info} or {@code .mod} next to an existing zip. An identical file is left alone; a different
     * one replaces the stored file only when the repository allows redeploys, or when {@code derivedReplaceable} is
     * set and the stored file is one the server derived itself.
     */
    private boolean putVersionFile(Repository repo, GoPath path, String extension, byte[] bytes, String contentType,
                                   boolean derivedReplaceable) {
        String file = path.filePath(extension);
        ReentrantLock lock = lockFor(repo, path);
        lock.lock();
        try {
            if (!storage.exists(repo, path.filePath("zip"))) {
                throw GoError.versionMissing(path.module() + "@" + path.version() + " has not been published; upload "
                        + path.filePath("zip") + " first, then its ." + extension);
            }
            Optional<StoredContent> existing = storage.find(repo, file);
            if (existing.isPresent()) {
                byte[] current = read(existing.get());
                if (Arrays.equals(current, bytes)) {
                    return false;
                }
                boolean derived = existing.get().asset().getOrigin() == Asset.Origin.GENERATED;
                if (!(derivedReplaceable && derived) && !repo.isAllowRedeploy()) {
                    throw GoError.conflict(file + " already exists with different content and "
                            + (extension.equals("mod") ? "its hash is in consumers' go.sum files; " : "")
                            + "the repository does not allow redeploys");
                }
            }
            storage.store(repo, file, new ByteArrayInputStream(bytes), contentType, Asset.Origin.UPLOADED, false, coordinates(path));
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** {@code DELETE <module>/@v/<version>}: removes the version's three files. Returns what was removed. */
    public List<String> delete(Repository repo, GoPath path) {
        requireCanonical(path);
        List<String> removed = new ArrayList<>();
        ReentrantLock lock = lockFor(repo, path);
        lock.lock();
        try {
            for (String extension : new String[] {"zip", "mod", "info"}) {
                String file = path.filePath(extension);
                if (storage.delete(repo, file)) {
                    removed.add(file);
                }
            }
        } finally {
            lock.unlock();
        }
        if (removed.isEmpty()) {
            throw GoError.notFound(path.module() + "@" + path.version() + " does not exist");
        }
        return removed;
    }

    static StorageService.ComponentCoordinates coordinates(GoPath path) {
        return coordinates(path.module(), path.version());
    }

    /** The component of one version of a module: the module path without its last element, that element, the version. */
    static StorageService.ComponentCoordinates coordinates(String module, String version) {
        return new StorageService.ComponentCoordinates(GoModulePaths.namespace(module), GoModulePaths.name(module), version);
    }

    private static void requireCanonical(GoPath path) {
        GoModulePaths.checkPathMajor(path.module(), path.version());
    }

    private void requireNewOrRedeployable(Repository repo, GoPath path, String zipPath) {
        if (!repo.isAllowRedeploy() && storage.exists(repo, zipPath)) {
            throw GoError.conflict(path.module() + "@" + path.version()
                    + " already exists; delete it first or allow redeploy on the repository");
        }
    }

    /** The lock that serialises writes to one version of one module in one repository. */
    private ReentrantLock lockFor(Repository repo, GoPath path) {
        int hash = (repo.getId() + "|" + path.escapedModule() + "@" + path.escapedVersion()).hashCode();
        return locks[Math.floorMod(hash, STRIPES)];
    }

    private static byte[] read(StoredContent content) {
        try {
            return content.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("could not read " + content.asset().getPath(), e);
        }
    }

    /** Writes a server-derived file unless a client uploaded that file itself. */
    private boolean storeDerived(Repository repo, String file, byte[] bytes, String contentType,
                                 StorageService.ComponentCoordinates coordinates) {
        Optional<StoredContent> existing = storage.find(repo, file);
        if (existing.isPresent() && existing.get().asset().getOrigin() == Asset.Origin.UPLOADED) {
            return false;
        }
        storage.store(repo, file, new ByteArrayInputStream(bytes), contentType, Asset.Origin.GENERATED, false, coordinates);
        return true;
    }

    private static long copyBounded(InputStream in, Path target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > GoModulePaths.MAX_ZIP_FILE) {
                    throw GoError.tooLarge("module zip file is too large (max size is " + GoModulePaths.MAX_ZIP_FILE + " bytes)");
                }
                out.write(buffer, 0, n);
            }
        }
        return total;
    }
}
