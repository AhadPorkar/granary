package dev.granary.format.pypi;

import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.Checksums;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP responses in the shapes pip, uv, poetry and twine expect. */
public final class PypiResponses {

    private PypiResponses() {
    }

    /**
     * An index document rendered for this request. The ETag is the digest of the rendered bytes, so a client
     * that sends it back in {@code If-None-Match} gets a cheap 304 when nothing changed.
     */
    public static ResponseEntity<?> document(RepositoryRequest request, byte[] body, String contentType) {
        return document(request, body, contentType, false);
    }

    public static ResponseEntity<?> document(RepositoryRequest request, String body, String contentType) {
        return document(request, body.getBytes(StandardCharsets.UTF_8), contentType, false);
    }

    /**
     * A simple-API page whose representation (HTML or JSON) was chosen from the {@code Accept} header; it says so
     * with {@code Vary: Accept}, so no HTTP cache between client and server hands one client's flavour to another.
     */
    public static ResponseEntity<?> negotiated(RepositoryRequest request, byte[] body, String contentType) {
        return document(request, body, contentType, true);
    }

    public static ResponseEntity<?> negotiated(RepositoryRequest request, String body, String contentType) {
        return document(request, body.getBytes(StandardCharsets.UTF_8), contentType, true);
    }

    private static ResponseEntity<?> document(RepositoryRequest request, byte[] body, String contentType, boolean varies) {
        String etag = "\"" + Checksums.hex("SHA-256", body) + "\"";
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            ResponseEntity.HeadersBuilder<?> notModified = ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag);
            if (varies) {
                notModified.varyBy(HttpHeaders.ACCEPT);
            }
            return notModified.build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(body.length);
        if (varies) {
            builder.varyBy(HttpHeaders.ACCEPT);
        }
        return request.isHead() ? builder.build() : builder.body(body);
    }

    /** A permanent redirect to the canonical spelling of a simple-API URL. */
    public static ResponseEntity<?> redirect(String location) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, location)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Moved to " + location + "\n");
    }

    /** A plain-text answer, the kind upload clients print verbatim. */
    public static ResponseEntity<?> text(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(message.endsWith("\n") ? message : message + "\n");
    }

    public static ResponseEntity<?> text(PypiError error) {
        return text(error.status(), error.getMessage());
    }

    public static ResponseEntity<?> json(PypiError error) {
        return ContentResponses.error(error.status(), error.code(), error.getMessage());
    }
}
