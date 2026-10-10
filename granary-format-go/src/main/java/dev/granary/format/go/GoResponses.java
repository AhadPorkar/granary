package dev.granary.format.go;

import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.Checksums;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP responses for documents the handler renders itself (version lists, {@code @latest}) and for errors. */
public final class GoResponses {

    public static final String LIST_CONTENT_TYPE = "text/plain; charset=utf-8";

    private GoResponses() {
    }

    public static ResponseEntity<?> error(GoError e) {
        return ContentResponses.error(e.status(), e.code(), e.getMessage());
    }

    /**
     * A document rendered for this request. Its ETag is the digest of the bytes, so a client that sends
     * {@code If-None-Match} gets a 304 when nothing changed; it is never cached by intermediaries because
     * version lists change with every publish.
     */
    public static ResponseEntity<?> document(RepositoryRequest request, byte[] body, String contentType) {
        String etag = "\"" + Checksums.hex("SHA-256", body) + "\"";
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(body.length);
        return request.isHead() ? builder.build() : builder.body(body);
    }

    /** {@code 200} with an empty body, what {@code sumdb/<name>/supported} answers. */
    public static ResponseEntity<?> empty() {
        return ResponseEntity.ok().contentLength(0).build();
    }
}
