package dev.granary.format.cocoapods;

import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.Checksums;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP responses in the shapes the CocoaPods CDN source and {@code curl} expect. */
public final class CocoaPodsResponses {

    private CocoaPodsResponses() {
    }

    /**
     * A document built for this request (a generated index, a merged index, a podspec with rewritten source). The
     * ETag is the digest of the bytes, so {@code pod repo update}, which sends the ETag it saved back in
     * {@code If-None-Match}, gets a cheap 304 when nothing changed.
     */
    public static ResponseEntity<?> document(RepositoryRequest request, byte[] body, String contentType) {
        String etag = "\"" + Checksums.hex("SHA-256", body) + "\"";
        if (matchesIfNoneMatch(request.header(HttpHeaders.IF_NONE_MATCH), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(body.length);
        return request.isHead() ? builder.build() : builder.body(body);
    }

    /**
     * The weak comparison RFC 9110 prescribes for {@code If-None-Match}: a comma-separated list of entity tags where
     * {@code W/"x"} matches {@code "x"} and {@code *} matches any existing document.
     */
    static boolean matchesIfNoneMatch(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.strip();
            if (tag.equals("*")) {
                return true;
            }
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    /** Names a downloaded archive explicitly, so no client guesses a file name from the URL or the content type. */
    public static ResponseEntity<?> attachment(ResponseEntity<?> response, String filename) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replace("\"", "") + "\"")
                .body(response.getBody());
    }

    public static ResponseEntity<?> error(CocoaPodsError error) {
        return ContentResponses.error(error.status(), error.code(), error.getMessage());
    }
}
