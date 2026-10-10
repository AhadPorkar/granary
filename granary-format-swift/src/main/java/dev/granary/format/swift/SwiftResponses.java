package dev.granary.format.swift;

import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StoredContent;
import java.util.function.Consumer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Responses in the shapes SwiftPM checks: every response carries {@code Content-Version: 1} (SwiftPM rejects
 * JSON documents, problem documents and the manifest list without it), JSON is {@code application/json}, errors
 * are RFC 7807 {@code application/problem+json} with a {@code detail} member, manifests are {@code text/x-swift}
 * and archives {@code application/zip}.
 */
final class SwiftResponses {

    static final String CONTENT_VERSION = "Content-Version";
    static final String API_VERSION = "1";
    static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");
    static final MediaType SWIFT = MediaType.parseMediaType(SwiftManifests.CONTENT_TYPE);
    static final MediaType ZIP = MediaType.parseMediaType("application/zip");
    static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final ObjectMapper json;

    SwiftResponses(ObjectMapper json) {
        this.json = json;
    }

    /** A generated JSON document; its SHA-256 is the ETag so polling clients get cheap 304s. */
    ResponseEntity<?> json(RepositoryRequest request, HttpStatus status, JsonNode document, Consumer<HttpHeaders> extra) {
        byte[] bytes = json.writeValueAsBytes(document);
        String etag = "\"" + Checksums.hex("SHA-256", bytes) + "\"";
        HttpHeaders headers = new HttpHeaders();
        headers.set(CONTENT_VERSION, API_VERSION);
        if (extra != null) {
            extra.accept(headers);
        }
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        if (status == HttpStatus.OK && ifNoneMatch != null && (ifNoneMatch.contains(etag) || ifNoneMatch.trim().equals("*"))) {
            headers.setETag(etag);
            return new ResponseEntity<>(headers, HttpStatus.NOT_MODIFIED);
        }
        headers.setETag(etag);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentLength(bytes.length);
        headers.setCacheControl(CacheControl.noCache());
        return new ResponseEntity<>(request.isHead() ? null : bytes, headers, status);
    }

    ResponseEntity<?> json(RepositoryRequest request, JsonNode document, Consumer<HttpHeaders> extra) {
        return json(request, HttpStatus.OK, document, extra);
    }

    /** A stored file with Granary's conditional-request handling plus the protocol's headers. */
    static ResponseEntity<?> file(RepositoryRequest request, StoredContent content, MediaType type, Consumer<HttpHeaders> extra) {
        ResponseEntity<?> base = ContentResponses.stream(request, content);
        HttpHeaders headers = HttpHeaders.copyOf(base.getHeaders());
        headers.set(CONTENT_VERSION, API_VERSION);
        if (base.getStatusCode().is2xxSuccessful()) {
            headers.setContentType(type);
            headers.set(HttpHeaders.CACHE_CONTROL, IMMUTABLE);
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            if (extra != null) {
                extra.accept(headers);
            }
        }
        return new ResponseEntity<>(base.getBody(), headers, base.getStatusCode());
    }

    /** An RFC 7807 problem document. */
    ResponseEntity<?> problem(RepositoryRequest request, HttpStatus status, String code, String detail,
                              Consumer<HttpHeaders> extra) {
        ObjectNode body = json.createObjectNode();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code);
        byte[] bytes = json.writeValueAsBytes(body);
        HttpHeaders headers = new HttpHeaders();
        headers.set(CONTENT_VERSION, API_VERSION);
        headers.set(HttpHeaders.CONTENT_LANGUAGE, "en");
        headers.setContentType(PROBLEM_JSON);
        headers.setContentLength(bytes.length);
        headers.setCacheControl(CacheControl.noStore());
        if (extra != null) {
            extra.accept(headers);
        }
        return new ResponseEntity<>(request != null && request.isHead() ? null : bytes, headers, status);
    }

    ResponseEntity<?> problem(RepositoryRequest request, SwiftProblem p) {
        return problem(request, p.status(), p.code(), p.detail(), null);
    }

    ResponseEntity<?> notFound(RepositoryRequest request, String detail) {
        return problem(request, HttpStatus.NOT_FOUND, "swift.notFound", detail, null);
    }

    /** A bodiless response that still names the API version (redirects, 204s). */
    static ResponseEntity<?> empty(HttpStatus status, Consumer<HttpHeaders> extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CONTENT_VERSION, API_VERSION);
        if (extra != null) {
            extra.accept(headers);
        }
        return new ResponseEntity<>(headers, status);
    }
}
