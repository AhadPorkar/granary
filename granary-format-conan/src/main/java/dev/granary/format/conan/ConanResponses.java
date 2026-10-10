package dev.granary.format.conan;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.Checksums;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * HTTP responses in the shapes the Conan client checks for.
 *
 * <p>JSON documents carry exactly {@code Content-Type: application/json} (the client refuses anything else) and an
 * ETag of their bytes so a conditional request gets a cheap 304. Errors use the document Artifactory produces,
 * {@code {"errors":[{"status":404,"message":"…"}]}}, which the client prints as {@code 404: message}; the usual
 * {@code code}/{@code message} pair is included for the UI and other tooling.
 */
public final class ConanResponses {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private ConanResponses() {
    }

    public static ResponseEntity<?> json(RepositoryRequest request, byte[] body) {
        String etag = "\"" + Checksums.hex("SHA-256", body) + "\"";
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && (ifNoneMatch.contains(etag) || ifNoneMatch.trim().equals("*"))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .contentType(JSON)
                .contentLength(body.length);
        return request.isHead() ? builder.build() : builder.body(body);
    }

    public static ResponseEntity<?> text(HttpStatus status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(bytes.length)
                .body(bytes);
    }

    public static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        String body = "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message)
                + "\",\"errors\":[{\"status\":" + status.value() + ",\"message\":\"" + escape(message) + "\"}]}";
        return ResponseEntity.status(status)
                .contentType(JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    public static ResponseEntity<?> error(ConanError error) {
        return error(error.status(), error.code(), error.getMessage());
    }

    public static ResponseEntity<?> notFound(String message) {
        return error(HttpStatus.NOT_FOUND, "content.notFound", message);
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
