package dev.granary.format.rubygems;

import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * HTTP responses in the shapes {@code gem} and Bundler expect.
 *
 * <p>Compact index documents carry the headers Bundler's incremental updater relies on: an {@code ETag} that is
 * the MD5 of the body (as on rubygems.org), {@code Repr-Digest: sha-256=:…:} over the complete representation so
 * an appended tail can be verified, and {@code Accept-Ranges: bytes}. Bodies are Spring {@link Resource}s, so a
 * {@code Range} request is answered with {@code 206 Partial Content} (or {@code 416}, after which Bundler simply
 * fetches the whole file).
 */
public final class GemResponses {

    public static final String TEXT_UTF8 = "text/plain;charset=utf-8";
    public static final String OCTET_STREAM = "application/octet-stream";
    public static final String JSON_UTF8 = "application/json;charset=utf-8";

    private static final int MD5_MEMO_SIZE = 4096;

    /** MD5 by blob SHA-256: the MD5 of a stored document never changes, and the big ones are expensive to hash. */
    private static final Map<String, String> MD5_BY_SHA256 = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MD5_MEMO_SIZE;
        }
    };

    private GemResponses() {
    }

    /** Remembers the MD5 of a blob computed while it was written. */
    public static void rememberMd5(String sha256, String md5) {
        synchronized (MD5_BY_SHA256) {
            MD5_BY_SHA256.put(sha256, md5);
        }
    }

    /** The MD5 (hex) of a stored document, hashed once per blob. */
    public static String md5(StoredContent content) {
        String sha256 = content.asset().getBlobSha256();
        synchronized (MD5_BY_SHA256) {
            String known = MD5_BY_SHA256.get(sha256);
            if (known != null) {
                return known;
            }
        }
        MessageDigest md5 = GemPackage.digest("MD5");
        try (InputStream in = new DigestInputStream(content.open(), md5)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String hex = HexFormat.of().formatHex(md5.digest());
        rememberMd5(sha256, hex);
        return hex;
    }

    /** A stored compact index document ({@code versions}, {@code names}, {@code info/…}). */
    public static ResponseEntity<?> compactIndex(RepositoryRequest request, StoredContent content) {
        Instant modified = content.asset().getUpdatedAt();
        return compactIndex(request, md5(content), content.asset().getBlobSha256(), content.size(), modified,
                new BlobResource(content));
    }

    /** A compact index document built for this request (a group's merged {@code info/…}). */
    public static ResponseEntity<?> compactIndex(RepositoryRequest request, String document) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(GemPackage.digest("SHA-256").digest(bytes));
        return compactIndex(request, CompactIndex.md5(bytes), sha256, bytes.length, null, new ByteArrayResource(bytes));
    }

    private static ResponseEntity<?> compactIndex(RepositoryRequest request, String md5, String sha256Hex, long length,
                                                  Instant modified, Resource body) {
        String etag = "\"" + md5 + "\"";
        String reprDigest = "sha-256=:" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex)) + ":";
        if (matches(request.header(HttpHeaders.IF_NONE_MATCH), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).header("Repr-Digest", reprDigest).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .header("Repr-Digest", reprDigest)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType(TEXT_UTF8));
        if (modified != null) {
            builder.lastModified(modified);
        }
        if (request.isHead()) {
            return builder.contentLength(length).build();
        }
        // no Content-Length here: for a Range request Spring answers 206 with the length of the part
        return builder.body(body);
    }

    /** A stored index or file other than the compact index (specs, quick specs, gems). */
    public static ResponseEntity<?> file(RepositoryRequest request, StoredContent content, String contentType,
                                         boolean immutable) {
        String etag = "\"" + content.asset().getBlobSha256() + "\"";
        if (matches(request.header(HttpHeaders.IF_NONE_MATCH), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .header("X-Checksum-Sha256", content.asset().getBlobSha256())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(immutable
                        ? CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic()
                        : CacheControl.noCache());
        if (content.asset().getUpdatedAt() != null) {
            builder.lastModified(content.asset().getUpdatedAt());
        }
        if (request.isHead()) {
            return builder.contentLength(content.size()).build();
        }
        return builder.body(new BlobResource(content));
    }

    /** A JSON document rendered for this request. */
    public static ResponseEntity<?> json(RepositoryRequest request, byte[] body) {
        String etag = "\"" + HexFormat.of().formatHex(GemPackage.digest("SHA-256").digest(body)) + "\"";
        if (matches(request.header(HttpHeaders.IF_NONE_MATCH), etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType(JSON_UTF8))
                .contentLength(body.length);
        return request.isHead() ? builder.build() : builder.body(body);
    }

    /** A plain-text answer, the kind {@code gem push}, {@code gem yank} and {@code gem signin} print verbatim. */
    public static ResponseEntity<?> text(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(TEXT_UTF8))
                .body(message);
    }

    public static ResponseEntity<?> text(GemError error) {
        return text(error.status(), error.getMessage());
    }

    /** The JSON error shape the rest of the server uses, for read requests. */
    public static ResponseEntity<?> json(GemError error) {
        return ContentResponses.error(error.status(), error.code(), error.getMessage());
    }

    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if (c.equals("*") || c.equals(etag) || c.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }

    /** A Spring {@link Resource} over a stored blob, opened lazily when the response is written. */
    static final class BlobResource extends AbstractResource {
        private final StoredContent content;

        BlobResource(StoredContent content) {
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
            return path.substring(path.lastIndexOf('/') + 1);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BlobResource b && b.content.asset().getBlobSha256().equals(content.asset().getBlobSha256());
        }

        @Override
        public int hashCode() {
            return content.asset().getBlobSha256().hashCode();
        }
    }
}
