package dev.granary.format.pub;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.Checksums;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP responses in the shapes the pub client understands. */
public final class PubResponses {

    /** The media type of the v2 API; the client asks for it in {@code Accept}. */
    public static final String PUB_V2 = "application/vnd.pub.v2+json";
    /** Served with an explicit charset: the Dart HTTP client decodes undeclared text as Latin-1. */
    public static final MediaType PUB_V2_UTF8 = MediaType.parseMediaType(PUB_V2 + "; charset=utf-8");
    public static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json; charset=utf-8");
    /** Package archives: gzip-compressed tar files. */
    public static final MediaType ARCHIVE = MediaType.parseMediaType("application/gzip");

    private PubResponses() {
    }

    /**
     * A JSON document rendered for this request (a listing with archive URLs pointing at the repository that
     * was asked, a merged group listing). Its ETag is the digest of the rendered bytes, so the client's
     * {@code If-None-Match} revalidation gets a cheap 304 when nothing changed.
     */
    public static ResponseEntity<?> document(RepositoryRequest request, byte[] body) {
        String etag = "\"" + Checksums.hex("SHA-256", body) + "\"";
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && (ifNoneMatch.contains(etag) || ifNoneMatch.trim().equals("*"))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(CacheControl.noCache()).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .contentType(PUB_V2_UTF8)
                .contentLength(body.length);
        return request.isHead() ? builder.build() : builder.body(body);
    }

    public static ResponseEntity<?> json(HttpStatus status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).contentType(JSON_UTF8).contentLength(bytes.length).body(bytes);
    }

    public static ResponseEntity<?> error(PubError error) {
        ResponseEntity<?> response = error(error.status(), error.code(), error.getMessage());
        if (error.status() == HttpStatus.METHOD_NOT_ALLOWED) {
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                    .allow(HttpMethod.GET, HttpMethod.HEAD).body(response.getBody());
        }
        return response;
    }

    public static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return json(status, "{\"error\":{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}}");
    }

    /** {@code {"success":{"message":"…"}}}, which the client prints as "Message from server: …". */
    public static ResponseEntity<?> success(String message) {
        return json(HttpStatus.OK, successBody(message));
    }

    static String successBody(String message) {
        return "{\"success\":{\"message\":\"" + escape(message) + "\"}}";
    }

    /** The answer to the multipart upload: no content, and where to ask for the outcome. */
    public static ResponseEntity<?> uploaded(String finishUrl) {
        return ResponseEntity.noContent().location(URI.create(finishUrl)).build();
    }

    /**
     * 401 or 403 with the challenge the pub client looks for: {@code WWW-Authenticate: Bearer realm="pub",
     * message="…"}. The client prints the message under its own hint to run {@code dart pub token add}.
     */
    public static ResponseEntity<?> challenge(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"pub\", message=\"" + quoted(message) + "\"")
                .contentType(JSON_UTF8)
                .body("{\"error\":{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}}");
    }

    /** A value for an RFC 7230 quoted-string: printable ASCII only, quotes and backslashes escaped. */
    static String quoted(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c >= 0x20 && c < 0x7f) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    /** A JSON string body (without the quotes). */
    static String escape(String s) {
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
