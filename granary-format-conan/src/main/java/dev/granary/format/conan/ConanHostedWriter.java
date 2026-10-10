package dev.granary.format.conan;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Uploads and deletions in a hosted repository.
 *
 * <p>The client PUTs each file of a revision with its SHA-1 in {@code X-Checksum-Sha1}; the body is spooled to the
 * data directory, verified against that header and only then stored. With the {@code checksum_deploy} capability
 * the client may first send an empty PUT with {@code X-Checksum-Deploy: true}: it is answered 201 when the very same
 * file is already stored (nothing to transfer) and 404 otherwise, which makes the client send the content.
 *
 * <p>Revisions are content hashes, so the same path normally only ever receives the same bytes: that is accepted
 * silently (200). Different bytes under an existing revision are a 409 unless the repository allows redeployment;
 * files below {@code metadata/} are the exception, because Conan lets them change without a new revision.
 */
public final class ConanHostedWriter {

    public static final String CHECKSUM_SHA1 = "X-Checksum-Sha1";
    public static final String CHECKSUM_DEPLOY = "X-Checksum-Deploy";

    private static final Logger log = LoggerFactory.getLogger(ConanHostedWriter.class);

    private final StorageService storage;
    private final Path spoolDir;

    public ConanHostedWriter(StorageService storage, Path spoolDir) {
        this.storage = storage;
        this.spoolDir = spoolDir;
    }

    public ResponseEntity<?> store(RepositoryRequest request, String path, String contentType,
                                   StorageService.ComponentCoordinates coordinates) throws IOException {
        Repository repo = request.repository();
        String claimedSha1 = blankToNull(request.header(CHECKSUM_SHA1));
        if ("true".equalsIgnoreCase(blankToNull(request.header(CHECKSUM_DEPLOY)))) {
            Optional<StoredContent> existing = storage.find(repo, path);
            if (existing.isPresent() && claimedSha1 != null
                    && claimedSha1.equalsIgnoreCase(Checksums.of(existing.get(), "SHA-1"))) {
                return ResponseEntity.status(HttpStatus.CREATED).build();
            }
            throw ConanError.notFound("No stored file matches the checksum of " + fileName(path) + "; upload its content");
        }
        Files.createDirectories(spoolDir);
        Path spool = Files.createTempFile(spoolDir, "upload-", ".part");
        try {
            Digests digests = spool(request, spool);
            if (claimedSha1 != null && !claimedSha1.equalsIgnoreCase(digests.sha1())) {
                throw ConanError.badRequest("repository.conan.checksumMismatch",
                        "The uploaded " + fileName(path) + " does not match its X-Checksum-Sha1 header");
            }
            Optional<StoredContent> existing = storage.find(repo, path);
            if (existing.isPresent()) {
                if (digests.sha256().equalsIgnoreCase(existing.get().asset().getBlobSha256())) {
                    return ResponseEntity.ok().build();
                }
                if (!repo.isAllowRedeploy() && !ConanLayout.isMutableFile(path)) {
                    throw ConanError.conflict("repository.conan.revisionExists",
                            fileName(path) + " already exists in this revision with different content and "
                                    + "redeployment is disabled");
                }
            }
            if (coordinates != null) {
                // created up front, so that parallel uploads of one reference cannot collide inside store()
                ConanComponents.ensure(storage, repo, coordinates);
            }
            try (InputStream in = Files.newInputStream(spool)) {
                // files below metadata/ may be replaced without a new revision: served with no-cache, not for a year
                storage.store(repo, path, in, contentType, Asset.Origin.UPLOADED, ConanLayout.isMutableFile(path),
                        coordinates);
            }
            log.info("stored {} in {}", path, repo.getName());
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /** Removes every stored file under {@code prefix}; returns how many there were. */
    public int deletePrefix(Repository repo, String prefix) {
        int deleted = 0;
        for (Asset asset : storage.list(repo, prefix)) {
            if (asset.getPath().startsWith(prefix) && storage.delete(repo, asset.getPath())) {
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("deleted {} files under {} from {}", deleted, prefix, repo.getName());
        }
        return deleted;
    }

    private record Digests(String sha1, String sha256) {
    }

    private static Digests spool(RepositoryRequest request, Path target) throws IOException {
        MessageDigest sha1;
        MessageDigest sha256;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = request.body(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                sha1.update(buffer, 0, read);
                sha256.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        HexFormat hex = HexFormat.of();
        return new Digests(hex.formatHex(sha1.digest()), hex.formatHex(sha256.digest()));
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
