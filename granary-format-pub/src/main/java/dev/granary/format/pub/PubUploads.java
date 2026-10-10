package dev.granary.format.pub;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The three-step publishing protocol of the pub client.
 *
 * <ol>
 *   <li>{@code GET api/packages/versions/new} names the upload URL ({@link #newUpload}).</li>
 *   <li>The client POSTs the archive there as the multipart field {@code file}. The archive is validated and
 *       published right away; the outcome is written to {@code <dataDir>/tmp/pub/upload-<id>.json} and the
 *       answer is 204 with a {@code Location} pointing at step 3 ({@link #receive}).</li>
 *   <li>{@code GET api/packages/versions/newUploadFinish?id=<id>} reports the outcome as
 *       {@code {"success":{"message":…}}} or {@code {"error":{"code":…,"message":…}}} ({@link #finish}).</li>
 * </ol>
 *
 * Reporting the outcome in step 3 matters: the client shows the server's message only for that request and
 * prints a bare "Failed to upload the package." for any error answer to the upload itself. Outcomes are kept
 * for an hour, so a client that retries step 3 gets the same answer; only the caller who uploaded can read
 * one.
 */
@Component
public class PubUploads {

    private static final Logger log = LoggerFactory.getLogger(PubUploads.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    static final Duration RETENTION = Duration.ofHours(1);
    static final String FILE_FIELD = "file";

    private final PubHosted hosted;
    private final ObjectMapper mapper;
    private final Path dir;

    public PubUploads(PubHosted hosted, ObjectMapper mapper, GranaryProperties properties) {
        this.hosted = hosted;
        this.mapper = mapper;
        this.dir = properties.getDataDir().resolve("tmp").resolve(PubFormatHandler.FORMAT);
    }

    /** Step 1: where to upload, with no extra form fields. */
    public ObjectNode newUpload(String baseUrl) {
        ObjectNode body = NODES.objectNode();
        body.put("url", baseUrl + "/" + PubPath.NEW_UPLOAD);
        body.putObject("fields");
        return body;
    }

    /** Step 2: publishes the uploaded archive and answers with the URL of its outcome. */
    public String receive(RepositoryRequest request) throws IOException {
        Repository repo = request.repository();
        Files.createDirectories(dir);
        cleanup();
        String id = UUID.randomUUID().toString();
        Path spool = dir.resolve("upload-" + id + ".tar.gz");
        ObjectNode outcome = NODES.objectNode();
        outcome.put("repository", String.valueOf(repo.getId()));
        outcome.put("user", request.principal().username());
        try {
            copyUpload(request, spool, PubSettings.maxArchiveBytes(repo));
            PubHosted.Published published = hosted.publish(repo, spool);
            outcome.put("status", HttpStatus.OK.value());
            outcome.put("message", "Successfully uploaded " + published.name() + " version " + published.version()
                    + " to " + request.baseUrl() + (published.replaced() ? " (replacing the previous upload)" : "") + ".");
        } catch (PubError e) {
            outcome.put("status", e.status().value());
            outcome.put("code", e.code());
            outcome.put("message", e.getMessage());
            log.info("rejected upload to {}: {}", repo.getName(), e.getMessage());
        } finally {
            Files.deleteIfExists(spool);
        }
        Files.write(dir.resolve("upload-" + id + ".json"), mapper.writeValueAsBytes(outcome));
        return request.baseUrl() + "/" + PubPath.NEW_UPLOAD_FINISH + "?id=" + id;
    }

    /** Step 3: the outcome of an upload, for the caller who made it. */
    public ResponseEntity<?> finish(RepositoryRequest request) throws IOException {
        String id = request.query("id");
        if (id == null || !ID.matcher(id).matches()) {
            throw PubError.invalid("The upload id is missing or malformed; run dart pub publish again");
        }
        Path file = dir.resolve("upload-" + id + ".json");
        JsonNode outcome = null;
        if (Files.isRegularFile(file)) {
            try {
                outcome = mapper.readTree(Files.readAllBytes(file));
            } catch (JacksonException e) {
                log.warn("unreadable upload outcome {}: {}", file, e.getMessage());
            }
        }
        if (outcome == null || !outcome.isObject()
                || !Objects.equals(PubListing.string(outcome, "repository"), String.valueOf(request.repository().getId()))
                || !Objects.equals(PubListing.string(outcome, "user"), request.principal().username())) {
            throw PubError.notFound("Upload " + id + " is unknown or has expired; run dart pub publish again");
        }
        int status = outcome.path("status").asInt(500);
        String message = PubListing.string(outcome, "message");
        if (status == HttpStatus.OK.value()) {
            return PubResponses.success(message);
        }
        HttpStatus httpStatus = HttpStatus.resolve(status);
        return PubResponses.error(httpStatus == null ? HttpStatus.BAD_REQUEST : httpStatus,
                Objects.requireNonNullElse(PubListing.string(outcome, "code"), PubError.PACKAGE_REJECTED), message);
    }

    /**
     * Copies the archive to {@code target}: the multipart field {@code file} (any file part will do), or the
     * raw request body for clients that PUT/POST the bytes directly. Refuses more than {@code maxBytes}.
     */
    static void copyUpload(RepositoryRequest request, Path target, long maxBytes) throws IOException {
        HttpServletRequest http = request.http();
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(http, MultipartHttpServletRequest.class);
        if (multipart != null) {
            MultipartFile file = multipart.getFile(FILE_FIELD);
            if (file == null && !multipart.getFileMap().isEmpty()) {
                file = multipart.getFileMap().values().iterator().next();
            }
            if (file == null) {
                throw PubError.invalid("The upload has no \"" + FILE_FIELD + "\" field with the package archive");
            }
            if (file.getSize() > maxBytes) {
                throw tooLarge(file.getSize(), maxBytes);
            }
            try (InputStream in = file.getInputStream()) {
                copyBounded(in, target, maxBytes);
            }
            return;
        }
        String contentType = http.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            Collection<Part> parts;
            try {
                parts = http.getParts();
            } catch (ServletException | IllegalStateException e) {
                throw PubError.invalid("The multipart upload cannot be read: " + e.getMessage());
            }
            Part chosen = null;
            for (Part part : parts) {
                if (FILE_FIELD.equals(part.getName())) {
                    chosen = part;
                    break;
                }
                if (chosen == null && part.getSubmittedFileName() != null) {
                    chosen = part;
                }
            }
            if (chosen == null) {
                throw PubError.invalid("The upload has no \"" + FILE_FIELD + "\" field with the package archive");
            }
            try (InputStream in = chosen.getInputStream()) {
                copyBounded(in, target, maxBytes);
            }
            return;
        }
        try (InputStream in = request.body()) {
            copyBounded(in, target, maxBytes);
        }
    }

    private static void copyBounded(InputStream in, Path target, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw tooLarge(total, maxBytes);
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private static PubError tooLarge(long size, long maxBytes) {
        return PubError.rejected("The package archive is larger than the " + (maxBytes / (1024 * 1024))
                + " MiB this repository accepts");
    }

    /** Deletes outcomes and spool files older than {@link #RETENTION}; best effort. */
    void cleanup() {
        Instant cutoff = Instant.now().minus(RETENTION);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "upload-*")) {
            for (Path file : files) {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    log.debug("could not clean up {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("could not list {}: {}", dir, e.getMessage());
        }
    }

    Path directory() {
        return dir;
    }
}
