package dev.granary.format.npm;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.Checksums;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * HTTP responses in the shape the npm client expects. Errors are {@code {"error": "…"}} because that is
 * the text npm prints after the status line; anything else would show up as an unhelpful blank.
 */
public final class NpmResponses {

    private NpmResponses() {
    }

    public static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"" + escape(message) + "\"}");
    }

    public static ResponseEntity<?> error(NpmError error) {
        return error(error.status(), error.getMessage());
    }

    public static ResponseEntity<?> ok(HttpStatus status) {
        return json(status, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    }

    public static ResponseEntity<?> json(HttpStatus status, byte[] body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .body(body);
    }

    /**
     * A JSON document that was rendered for this request (a packument with rewritten tarball URLs, a merged
     * group document). The ETag is the digest of the rendered bytes so npm's cache can re-validate it with
     * {@code If-None-Match} and get a cheap 304 when nothing changed.
     */
    public static ResponseEntity<?> document(RepositoryRequest request, byte[] body) {
        String etag = "\"" + Checksums.hex("SHA-256", body) + "\"";
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length);
        return request.isHead() ? builder.build() : builder.body(body);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
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
