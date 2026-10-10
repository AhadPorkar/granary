package dev.granary.format.hex;

import dev.granary.core.format.RepositoryRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/**
 * Responses in the shapes Hex clients expect: API bodies as Erlang external terms
 * ({@code application/vnd.hex+erlang}, what {@code hex_core} asks for) or JSON for everything else, errors as
 * {@code {"status", "message", "errors"}}, and registry documents Granary builds itself with ETag and HEAD support.
 */
final class HexResponses {

    static final String ERLANG = "application/vnd.hex+erlang";
    static final MediaType ERLANG_TYPE = MediaType.parseMediaType(ERLANG + ";charset=utf-8");
    static final String REGISTRY_CONTENT_TYPE = "application/octet-stream";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HexResponses() {
    }

    /** Whether the client wants Erlang terms: hex_core always sends {@code Accept: application/vnd.hex+erlang}. */
    static boolean wantsErlang(RepositoryRequest request) {
        String accept = request.header(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains(ERLANG)) {
            return true;
        }
        if (accept != null && accept.contains("json")) {
            return false;
        }
        String contentType = request.header(HttpHeaders.CONTENT_TYPE);
        return contentType != null && contentType.contains(ERLANG);
    }

    static ResponseEntity<?> body(RepositoryRequest request, HttpStatus status, Object body) {
        byte[] bytes;
        MediaType type;
        if (wantsErlang(request)) {
            bytes = Etf.encode(body);
            type = ERLANG_TYPE;
        } else {
            bytes = JSON.writeValueAsBytes(body);
            type = MediaType.APPLICATION_JSON;
        }
        return ResponseEntity.status(status).contentType(type).contentLength(bytes.length).body(bytes);
    }

    static ResponseEntity<?> noContent() {
        return ResponseEntity.noContent().build();
    }

    static ResponseEntity<?> error(RepositoryRequest request, HexError error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", error.status().value());
        body.put("message", error.getMessage());
        if (!error.errors().isEmpty()) {
            body.put("errors", new LinkedHashMap<>(error.errors()));
        }
        ResponseEntity<?> response = body(request, error.status(), body);
        if (error.status() == HttpStatus.UNAUTHORIZED) {
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Granary\"")
                    .body(response.getBody());
        }
        return response;
    }

    /** A registry document Granary built (a group's merged view): ETag from the bytes, HEAD and 304 supported. */
    static ResponseEntity<?> document(RepositoryRequest request, byte[] bytes, String contentType) {
        String etag = "\"" + sha256(bytes) + "\"";
        if (matchesIfNoneMatch(request.header(HttpHeaders.IF_NONE_MATCH), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(bytes.length)
                .cacheControl(CacheControl.noCache());
        return request.isHead() ? builder.build() : builder.body(bytes);
    }

    /**
     * The weak comparison RFC 9110 prescribes for {@code If-None-Match}, the same rule stored content follows:
     * {@code *} matches any existing representation and {@code W/"x"} matches {@code "x"}.
     */
    static boolean matchesIfNoneMatch(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.trim();
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals("*") || tag.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    static ResponseEntity<?> text(RepositoryRequest request, String text, String contentType) {
        return document(request, text.getBytes(StandardCharsets.UTF_8), contentType);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
