package dev.granary.core.format;

import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Builds HTTP responses from stored content, with the conditional-request headers package managers rely on. */
public final class ContentResponses {

    private ContentResponses() {
    }

    /** Streams the asset, honouring {@code If-None-Match}. The caller records the download separately. */
    public static ResponseEntity<?> stream(RepositoryRequest request, StoredContent content) {
        String etag = "\"" + content.asset().getBlobSha256() + "\"";
        if (matchesIfNoneMatch(request.header(HttpHeaders.IF_NONE_MATCH), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        Instant lastModified = content.asset().getUpdatedAt() == null ? Instant.now() : content.asset().getUpdatedAt();
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .lastModified(lastModified)
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.size())
                .header("X-Checksum-Sha256", content.asset().getBlobSha256())
                .cacheControl(content.asset().isMetadata()
                        ? CacheControl.noCache()
                        : CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic());
        if (request.isHead()) {
            return builder.build();
        }
        return builder.body(new StoredContentResource(content));
    }

    /**
     * Whether an {@code If-None-Match} header matches the strong entity tag {@code etag} (quoted). Implements the
     * weak comparison RFC 9110 prescribes for this header: {@code *} matches any existing representation, and
     * {@code W/"x"} matches {@code "x"}.
     */
    public static boolean matchesIfNoneMatch(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.trim();
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

    public static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "content.notFound", "message", "Not found"));
    }

    public static ResponseEntity<?> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "content.notFound", "message", message));
    }

    public static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "message", message));
    }

    public static ResponseEntity<?> text(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(body);
    }

    public static ResponseEntity<?> bytes(byte[] body, String contentType) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(body.length)
                .body(body);
    }

    public static ResponseEntity<?> utf8(String body, String contentType) {
        return bytes(body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    /** A Spring {@link Resource} over a blob; the stream is opened lazily when the response is written. */
    static final class StoredContentResource extends AbstractResource {
        private final StoredContent content;

        StoredContentResource(StoredContent content) {
            this.content = content;
        }

        @Override
        public String getDescription() {
            return "asset " + content.asset().getPath();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return content.open();
        }

        @Override
        public long contentLength() {
            return content.size();
        }

        @Override
        public String getFilename() {
            String path = content.asset().getPath();
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }
}
