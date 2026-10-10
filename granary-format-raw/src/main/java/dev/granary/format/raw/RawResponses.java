package dev.granary.format.raw;

import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** HTTP responses of raw repositories: streamed files with conditional-request support, generated documents, errors. */
final class RawResponses {

    private RawResponses() {
    }

    /**
     * Streams a stored file with {@code ETag}, {@code Last-Modified}, {@code If-None-Match} and {@code HEAD}
     * handling, like {@link ContentResponses#stream}, plus an optional {@code Content-Disposition: attachment}.
     * Media types that upstream servers send in malformed form fall back to {@code application/octet-stream}.
     */
    static ResponseEntity<?> stream(RepositoryRequest request, StoredContent content, boolean attachment) {
        String etag = "\"" + content.asset().getBlobSha256() + "\"";
        if (notModified(request, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        Instant lastModified = content.asset().getUpdatedAt() == null ? Instant.now() : content.asset().getUpdatedAt();
        MediaType type = mediaType(content.contentType());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .lastModified(lastModified)
                .contentType(type)
                .contentLength(content.size())
                .header("X-Checksum-Sha256", content.asset().getBlobSha256())
                .cacheControl(content.asset().isMetadata()
                        ? CacheControl.noCache()
                        : CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
        // always named: without it Spring's download protection labels every .zip, .tar.gz or .bin "f.txt"
        String fileName = RawPaths.fileName(content.asset().getPath());
        builder.header(HttpHeaders.CONTENT_DISPOSITION, attachment ? attachmentDisposition(fileName) : inlineDisposition(fileName));
        sandbox(builder, type);
        if (request.isHead()) {
            return builder.build();
        }
        return builder.body(new StoredResource(content));
    }

    /**
     * {@code Content-Security-Policy} for documents a browser renders as pages (HTML, XHTML, SVG, XML). Raw
     * repositories hold whatever was uploaded or proxied (an npm package's HTML through unpkg, a build output);
     * served from the Granary origin, its scripts could read the web UI's session from local storage. The
     * sandbox gives such a page an opaque origin while scripts, forms and popups keep working.
     */
    static final String SANDBOX = "sandbox allow-downloads allow-forms allow-modals allow-popups allow-presentation allow-scripts";

    static boolean isActiveDocument(MediaType type) {
        if (type == null) {
            return false;
        }
        String subtype = type.getSubtype().toLowerCase(java.util.Locale.ROOT);
        return subtype.equals("html") || subtype.equals("xhtml+xml") || subtype.equals("xml") || subtype.endsWith("+xml")
                || subtype.equals("xsl") || subtype.equals("xslt");
    }

    private static void sandbox(ResponseEntity.BodyBuilder builder, MediaType type) {
        if (isActiveDocument(type)) {
            builder.header("Content-Security-Policy", SANDBOX);
        }
    }

    /** {@code If-None-Match} with the current ETag, a weak form of it, or {@code *}. */
    static boolean notModified(RepositoryRequest request, String etag) {
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        return ifNoneMatch != null && (ifNoneMatch.contains(etag) || ifNoneMatch.trim().equals("*"));
    }

    /** A document generated or rewritten on the fly; its ETag is the digest of the bytes. */
    static ResponseEntity<?> document(RepositoryRequest request, byte[] body, String contentType, boolean mutable) {
        String etag = "\"" + Checksums.hex("SHA-256", body) + "\"";
        if (notModified(request, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        MediaType type = mediaType(contentType);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .contentType(type)
                .contentLength(body.length)
                .cacheControl(mutable ? CacheControl.noCache() : CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
        sandbox(builder, type);
        String fileName = RawPaths.fileName(request.path());
        if (!fileName.isEmpty()) {
            builder.header(HttpHeaders.CONTENT_DISPOSITION, inlineDisposition(fileName));
        }
        return request.isHead() ? builder.build() : builder.body(body);
    }

    static ResponseEntity<?> created(String path, StoredContent content) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("path", path, "size", content.size(), "sha256", content.asset().getBlobSha256()));
    }

    static ResponseEntity<?> conflict(String path) {
        return ContentResponses.error(HttpStatus.CONFLICT, "repository.raw.exists",
                "'" + path + "' already exists and redeployment is disabled for this repository");
    }

    static ResponseEntity<?> badRequest(String code, String message) {
        return ContentResponses.error(HttpStatus.BAD_REQUEST, code, message);
    }

    /** Methods a raw repository path accepts, for the {@code Allow} header of a 405. */
    static final String ALLOW_ALL = "GET, HEAD, PUT, POST, DELETE";
    static final String ALLOW_READ = "GET, HEAD";
    static final String ALLOW_READ_POST = "GET, HEAD, POST";

    static ResponseEntity<?> methodNotAllowed(String method) {
        return methodNotAllowed(method, ALLOW_ALL);
    }

    static ResponseEntity<?> methodNotAllowed(String method, String allow) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, allow)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "method.notAllowed", "message", method + " is not supported by this repository"));
    }

    static MediaType mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    static String attachmentDisposition(String fileName) {
        return disposition("attachment", fileName);
    }

    static String inlineDisposition(String fileName) {
        return disposition("inline", fileName);
    }

    /** RFC 6266: a quoted ASCII fallback plus the UTF-8 form when the name needs it. */
    static String disposition(String type, String fileName) {
        StringBuilder ascii = new StringBuilder();
        boolean plain = true;
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                ascii.append('_');
                plain = false;
            } else if (c == '"' || c == '\\') {
                ascii.append('_');
            } else {
                ascii.append(c);
            }
        }
        String header = type + "; filename=\"" + ascii + "\"";
        if (!plain) {
            header += "; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        }
        return header;
    }

    /** A Spring resource over a blob; the stream is opened only when the response body is written. */
    static final class StoredResource extends AbstractResource {
        private final StoredContent content;

        StoredResource(StoredContent content) {
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
            return RawPaths.fileName(content.asset().getPath());
        }
    }

    /** Whether Spring can parse the media type at all. */
    static boolean isValidMediaType(String contentType) {
        try {
            MediaType.parseMediaType(contentType);
            return true;
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}
