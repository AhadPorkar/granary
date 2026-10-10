package dev.granary.format.raw;

import dev.granary.core.format.RepositoryRequest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

/** Reads what a client uploads: the raw request body, or the file parts of a multipart form. */
final class RawUpload {

    /** Opens the bytes of one uploaded file. */
    @FunctionalInterface
    interface Opener {
        InputStream open() throws IOException;
    }

    /** One file of a multipart upload: the form field name, the client's file name and its bytes. */
    record Part(String field, String fileName, Opener opener) {
    }

    private RawUpload() {
    }

    /**
     * The file parts of a multipart request, or empty when the request is not multipart. The multipart wrapper
     * is looked up through the request wrapper chain (the security filters wrap the request), with the Servlet
     * API as fallback when the framework did not apply multipart support.
     */
    static Optional<List<Part>> multipart(RepositoryRequest request) throws IOException {
        HttpServletRequest http = request.http();
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(http, MultipartHttpServletRequest.class);
        if (multipart != null) {
            List<Part> parts = new ArrayList<>();
            // every file of every field: curl -F file=@a -F file=@b sends two parts with the same name
            for (Map.Entry<String, List<MultipartFile>> entry : multipart.getMultiFileMap().entrySet()) {
                for (MultipartFile file : entry.getValue()) {
                    parts.add(new Part(entry.getKey(), file.getOriginalFilename(), file::getInputStream));
                }
            }
            return Optional.of(parts);
        }
        String contentType = http.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            return Optional.empty();
        }
        List<Part> parts = new ArrayList<>();
        try {
            for (jakarta.servlet.http.Part part : http.getParts()) {
                if (part.getSubmittedFileName() != null) {
                    parts.add(new Part(part.getName(), part.getSubmittedFileName(), part::getInputStream));
                }
            }
        } catch (ServletException | IllegalStateException e) {
            throw new IOException("the multipart upload cannot be read: " + e.getMessage(), e);
        }
        return Optional.of(parts);
    }

    /** Copies the stream into a fresh temporary file inside {@code directory}; the caller deletes it. */
    static Path spool(InputStream in, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "upload-", ".tmp");
        Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    /** The client's file name reduced to its last path element, so a browser never dictates a directory. */
    static String safeFileName(String submitted) {
        if (submitted == null) {
            return "";
        }
        String name = submitted.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
