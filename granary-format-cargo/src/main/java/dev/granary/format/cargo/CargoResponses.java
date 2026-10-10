package dev.granary.format.cargo;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.Checksums;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** HTTP responses in the shapes cargo understands. */
final class CargoResponses {

    static final String INDEX_CONTENT_TYPE = "text/plain;charset=utf-8";
    static final String JSON_CONTENT_TYPE = "application/json";
    static final String HTML_CONTENT_TYPE = "text/html;charset=utf-8";

    private CargoResponses() {
    }

    /** {@code {"errors":[{"detail":"…"}]}}, the error document of the registry web API. */
    static ResponseEntity<?> error(HttpStatus status, String detail) {
        return json(status, errorBody(detail));
    }

    static ResponseEntity<?> error(CargoError error) {
        return error(error.status(), error.getMessage());
    }

    static ObjectNode errorBody(String detail) {
        ObjectNode body = CargoJson.object();
        ArrayNode errors = body.putArray("errors");
        errors.addObject().put("detail", detail == null ? "" : detail);
        return body;
    }

    static ResponseEntity<?> json(HttpStatus status, JsonNode body) {
        byte[] bytes = CargoJson.bytes(body);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(bytes.length)
                .cacheControl(CacheControl.noStore())
                .body(bytes);
    }

    static ResponseEntity<?> ok(String message) {
        ObjectNode body = CargoJson.object();
        body.put("ok", true);
        if (message != null) {
            body.put("msg", message);
        }
        return json(HttpStatus.OK, body);
    }

    /**
     * A document rendered for this request (an index file, {@code config.json}). The ETag is the digest of the
     * bytes, so cargo's {@code If-None-Match} re-validation gets a cheap 304 when nothing changed.
     */
    static ResponseEntity<?> document(RepositoryRequest request, byte[] body, String contentType) {
        String etag = "\"" + Checksums.hex("SHA-256", body) + "\"";
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && (ifNoneMatch.contains(etag) || ifNoneMatch.trim().equals("*"))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(CacheControl.noCache()).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(body.length);
        return request.isHead() ? builder.build() : builder.body(body);
    }

    static ResponseEntity<?> html(RepositoryRequest request, String html) {
        return document(request, html.getBytes(StandardCharsets.UTF_8), HTML_CONTENT_TYPE);
    }

    static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
