package dev.granary.format.cocoapods;

import dev.granary.core.format.RepositoryRequest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

/**
 * The body of a push: a podspec, and for {@code POST api/pods} optionally the source archive next to it.
 *
 * <p>{@code api/pods} takes {@code multipart/form-data} with a {@code podspec} part (file or field) and an optional
 * {@code source} file, or the podspec JSON alone as the request body.
 *
 * @param podspec        the podspec JSON
 * @param source         the spooled source archive, or {@code null}
 * @param sourceFilename the archive's file name as the client sent it, or {@code null}
 */
public record PodUpload(byte[] podspec, Path source, String sourceFilename) {

    /** Far above any real podspec (the largest on the CDN are a few hundred kilobytes). */
    public static final int MAX_PODSPEC_BYTES = 10 * 1024 * 1024;

    /** Reads a raw request body that must be a podspec. */
    public static byte[] readPodspec(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_PODSPEC_BYTES + 1);
        if (body.length > MAX_PODSPEC_BYTES) {
            throw CocoaPodsError.tooLarge("A podspec may not be larger than " + MAX_PODSPEC_BYTES + " bytes");
        }
        if (body.length == 0) {
            throw CocoaPodsError.invalidPodspec("The request has no body; send the podspec JSON");
        }
        return body;
    }

    /** Spools a raw request body (a source archive) to {@code target}. */
    public static void copyBody(RepositoryRequest request, Path target) throws IOException {
        try (InputStream in = request.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads a push to {@code api/pods}. The multipart request is looked up through the wrapper chain because the
     * security filters wrap the request before it reaches the handler.
     */
    public static PodUpload read(RepositoryRequest request, Path tempDir) throws IOException {
        HttpServletRequest http = request.http();
        String contentType = http.getContentType() == null ? "" : http.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("multipart/")) {
            try (InputStream in = request.body()) {
                return new PodUpload(readPodspec(in), null, null);
            }
        }
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(http, MultipartHttpServletRequest.class);
        if (multipart != null) {
            byte[] podspec = null;
            MultipartFile podspecFile = multipart.getFile("podspec");
            if (podspecFile != null) {
                try (InputStream in = podspecFile.getInputStream()) {
                    podspec = readPodspec(in);
                }
            } else if (multipart.getParameter("podspec") != null) {
                podspec = field(multipart.getParameter("podspec"));
            }
            if (podspec == null) {
                throw CocoaPodsError.badRequest("The upload has no 'podspec' part");
            }
            MultipartFile source = multipart.getFile("source");
            if (source == null || source.isEmpty()) {
                return new PodUpload(podspec, null, null);
            }
            Path spooled = spool(tempDir);
            try (InputStream in = source.getInputStream()) {
                Files.copy(in, spooled, StandardCopyOption.REPLACE_EXISTING);
            }
            return new PodUpload(podspec, spooled, source.getOriginalFilename());
        }
        // multipart support was not applied by the framework; fall back to the Servlet API
        try {
            Part podspecPart = http.getPart("podspec");
            if (podspecPart == null) {
                throw CocoaPodsError.badRequest("The upload has no 'podspec' part");
            }
            byte[] podspec;
            try (InputStream in = podspecPart.getInputStream()) {
                podspec = readPodspec(in);
            }
            Part sourcePart = http.getPart("source");
            if (sourcePart == null || sourcePart.getSize() == 0) {
                return new PodUpload(podspec, null, null);
            }
            Path spooled = spool(tempDir);
            try (InputStream in = sourcePart.getInputStream()) {
                Files.copy(in, spooled, StandardCopyOption.REPLACE_EXISTING);
            }
            return new PodUpload(podspec, spooled, sourcePart.getSubmittedFileName());
        } catch (ServletException | IllegalStateException e) {
            throw CocoaPodsError.badRequest("The multipart upload cannot be read: " + e.getMessage());
        }
    }

    private static byte[] field(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PODSPEC_BYTES) {
            throw CocoaPodsError.tooLarge("A podspec may not be larger than " + MAX_PODSPEC_BYTES + " bytes");
        }
        return bytes;
    }

    /** A new temp file under {@code <dataDir>/tmp/cocoapods/}. */
    public static Path spool(Path tempDir) throws IOException {
        Files.createDirectories(tempDir);
        return Files.createTempFile(tempDir, "upload-", ".bin");
    }

    /** Deletes the spooled archive, if any. */
    public void cleanUp() throws IOException {
        if (source != null) {
            Files.deleteIfExists(source);
        }
    }
}
