package dev.granary.format.docker;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.storage.StoredContent;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.AbstractResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * HTTP responses in the shape the distribution protocol prescribes: every answer carries the API version
 * header, content answers carry {@code Docker-Content-Digest}, an ETag equal to the digest, an exact
 * {@code Content-Length} (also on HEAD) and honour {@code If-None-Match} and, for blobs, {@code Range}.
 */
public final class DockerResponses {

    public static final String API_VERSION_HEADER = "Docker-Distribution-API-Version";
    public static final String API_VERSION = "registry/2.0";
    public static final String DIGEST_HEADER = "Docker-Content-Digest";
    public static final String UPLOAD_UUID_HEADER = "Docker-Upload-UUID";

    private DockerResponses() {
    }

    public static ResponseEntity.BodyBuilder status(HttpStatus status) {
        return ResponseEntity.status(status).header(API_VERSION_HEADER, API_VERSION);
    }

    /**
     * Every value of a list-valued header such as {@code Accept} or {@code If-None-Match}, joined with commas as
     * HTTP allows. containers/image (podman, skopeo, buildah) sends each manifest type as a header line of its own,
     * single-platform manifests first, so reading only the first line would hide the index types it accepts.
     */
    public static String listHeader(RepositoryRequest request, String name) {
        Enumeration<String> values = request.http().getHeaders(name);
        if (values == null) {
            return request.header(name);
        }
        List<String> all = Collections.list(values);
        return all.isEmpty() ? null : String.join(", ", all);
    }

    public static ResponseEntity<?> empty(HttpStatus status) {
        return status(status).contentLength(0).build();
    }

    public static ResponseEntity<?> json(byte[] body, String contentType) {
        return status(HttpStatus.OK)
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CacheControl.noCache())
                .contentLength(body.length)
                .body(body);
    }

    /**
     * Serves stored bytes as a manifest or blob. A matching {@code If-None-Match} is a 304, HEAD carries the
     * headers only, and a satisfiable single {@code Range} on a blob is a 206.
     */
    public static ResponseEntity<?> content(RepositoryRequest request, StoredContent content, Digest digest,
                                            String contentType, boolean rangeable) {
        String etag = "\"" + digest + "\"";
        String ifNoneMatch = listHeader(request, HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && (ifNoneMatch.trim().equals("*") || ifNoneMatch.contains(etag)
                || ifNoneMatch.contains("\"" + digest.hex() + "\""))) {
            return status(HttpStatus.NOT_MODIFIED).eTag(etag).header(DIGEST_HEADER, digest.toString()).build();
        }
        long size = content.size();
        Instant lastModified = content.asset().getUpdatedAt() == null ? Instant.now() : content.asset().getUpdatedAt();
        Optional<long[]> range = rangeable && !request.isHead() ? parseRange(request.header(HttpHeaders.RANGE), size) : Optional.empty();
        if (range.isPresent() && range.get()[0] < 0) {
            return status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                    .contentLength(0)
                    .build();
        }
        ResponseEntity.BodyBuilder builder = status(range.isPresent() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .eTag(etag)
                .lastModified(lastModified)
                .header(DIGEST_HEADER, digest.toString())
                .header("X-Checksum-Sha256", content.asset().getBlobSha256())
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(content.asset().isMetadata()
                        ? CacheControl.noCache()
                        : CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
        if (rangeable) {
            builder.header(HttpHeaders.ACCEPT_RANGES, "bytes");
        }
        if (range.isPresent()) {
            long start = range.get()[0];
            long end = range.get()[1];
            return builder.header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size)
                    .contentLength(end - start + 1)
                    .body(new StoredRange(content, start, end - start + 1));
        }
        builder.contentLength(size);
        if (request.isHead()) {
            return builder.build();
        }
        return builder.body(new StoredRange(content, 0, size));
    }

    /**
     * Parses a single-range {@code Range} header into {start, end} (inclusive), {@code {-1, -1}} when the
     * range cannot be satisfied, or empty when there is no usable range (the full content is served then).
     */
    static Optional<long[]> parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",") || size <= 0) {
            return Optional.empty();
        }
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return Optional.empty();
        }
        try {
            String first = spec.substring(0, dash).trim();
            String second = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (first.isEmpty()) {
                if (second.isEmpty()) {
                    return Optional.empty();
                }
                long suffix = Long.parseLong(second);
                if (suffix <= 0) {
                    return Optional.of(new long[] {-1, -1});
                }
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(first);
                end = second.isEmpty() ? size - 1 : Math.min(Long.parseLong(second), size - 1);
            }
            if (start < 0 || start >= size || end < start) {
                return Optional.of(new long[] {-1, -1});
            }
            if (start == 0 && end == size - 1) {
                return Optional.empty();
            }
            return Optional.of(new long[] {start, end});
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A Spring resource over a slice of a stored blob; the stream is opened when the response is written. */
    static final class StoredRange extends AbstractResource {
        private final StoredContent content;
        private final long start;
        private final long length;

        StoredRange(StoredContent content, long start, long length) {
            this.content = content;
            this.start = start;
            this.length = length;
        }

        @Override
        public String getDescription() {
            return "asset " + content.asset().getPath() + " [" + start + "+" + length + "]";
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream in = content.open();
            try {
                if (start > 0) {
                    in.skipNBytes(start);
                }
            } catch (IOException | RuntimeException e) {
                in.close();
                throw e;
            }
            return new Limited(in, length);
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public String getFilename() {
            String path = content.asset().getPath();
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }

    /** Reads at most {@code remaining} bytes from the wrapped stream. */
    static final class Limited extends FilterInputStream {
        private long remaining;

        Limited(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = in.read(buffer, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), remaining);
        }
    }
}
