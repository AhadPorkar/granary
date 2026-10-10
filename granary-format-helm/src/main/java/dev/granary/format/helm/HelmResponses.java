package dev.granary.format.helm;

import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.StoredContent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Response shapes of the Helm format: generated YAML documents, downloads, and ChartMuseum's JSON bodies. */
final class HelmResponses {

    private HelmResponses() {
    }

    /** ChartMuseum's error body, {@code {"error": "..."}}, which {@code helm cm-push} prints as is. */
    static ResponseEntity<?> error(HelmError e) {
        return error(e.status(), e.getMessage());
    }

    static ResponseEntity<?> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    static ResponseEntity<?> json(RepositoryRequest request, HttpStatus status, Object body) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noCache());
        return request != null && request.isHead() ? builder.build() : builder.body(body);
    }

    /**
     * A generated document (an index) with a strong ETag, answering {@code If-None-Match} with 304 and HEAD without
     * a body, so {@code helm repo update} and caches do not download an unchanged index again.
     */
    static ResponseEntity<?> document(RepositoryRequest request, byte[] body, String etag, String contentType, String filename) {
        String quoted = "\"" + etag + "\"";
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && (ifNoneMatch.contains(quoted) || ifNoneMatch.trim().equals("*"))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(quoted).cacheControl(CacheControl.noCache()).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(quoted)
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(body.length)
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(filename).build().toString());
        return request.isHead() ? builder.build() : builder.body(body);
    }

    /** A stored file with ETag and conditional handling, named for download and typed by what it is. */
    static ResponseEntity<?> download(RepositoryRequest request, StoredContent content, String contentType, String filename) {
        ResponseEntity<?> response = ContentResponses.stream(request, content);
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString());
        if (contentType != null) {
            builder.contentType(MediaType.parseMediaType(contentType));
        }
        return builder.body(response.getBody());
    }
}
