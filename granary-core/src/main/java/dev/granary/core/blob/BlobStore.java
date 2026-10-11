package dev.granary.core.blob;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Content-addressed binary storage. Implementations must be safe for concurrent use.
 */
public interface BlobStore {

    /** Unique name of this store, referenced by repositories. */
    String name();

    /**
     * Streams {@code in} into the store, computing its digest on the fly.
     * The caller remains responsible for closing {@code in}.
     */
    BlobRef store(InputStream in) throws IOException;

    /**
     * Like {@link #store}, but the blob is also pinned: {@link #deleteIfUnmodifiedSince} leaves it alone until the
     * returned handle is closed, whatever its age. Writers use it to cover the time between storing the bytes and
     * committing the row that references them. A store that supports compaction must pin atomically with placing or
     * refreshing the blob.
     */
    default PinnedBlob storePinned(InputStream in) throws IOException {
        return new PinnedBlob(store(in), () -> { });
    }

    /** Opens the blob with the given digest, or empty when it is not present. */
    Optional<InputStream> open(String sha256) throws IOException;

    boolean exists(String sha256);

    /** Removes a blob. Returns {@code true} when something was deleted. */
    boolean delete(String sha256) throws IOException;

    BlobStoreStats stats() throws IOException;

    /**
     * Whether {@link #blobs()} and {@link #deleteIfUnmodifiedSince} are implemented, i.e. whether the store can be
     * compacted.
     */
    default boolean supportsCompaction() {
        return false;
    }

    /**
     * Every blob in the store with its size and last-modified time, read lazily so that a store with millions of
     * blobs never has to be listed in memory. Close the stream when done. Blobs written or deleted while the stream
     * is consumed may or may not be included.
     *
     * <p>The last-modified time is when the blob was last written <em>or stored again</em>: storing bytes that are
     * already present refreshes it, so a blob a new upload is about to reference always looks recent.
     */
    default Stream<BlobInfo> blobs() throws IOException {
        throw new UnsupportedOperationException("blob store " + name() + " cannot list its blobs");
    }

    /**
     * Deletes the blob only when it is not pinned ({@link #storePinned}), its last-modified time is still before
     * {@code cutoff} and {@code unreferenced}, asked last, still answers {@code true}; all of it atomically with
     * respect to {@link #store} and {@link #storePinned} of the same bytes (which refresh that time and pin). Asking
     * for references at that point sees every reference committed by a write that has released its pin. Returns
     * {@code true} when the blob was deleted.
     */
    default boolean deleteIfUnmodifiedSince(String sha256, Instant cutoff, BooleanSupplier unreferenced)
            throws IOException {
        throw new UnsupportedOperationException("blob store " + name() + " cannot be compacted");
    }

    /** {@link #deleteIfUnmodifiedSince(String, Instant, BooleanSupplier)} without a reference check. */
    default boolean deleteIfUnmodifiedSince(String sha256, Instant cutoff) throws IOException {
        return deleteIfUnmodifiedSince(sha256, cutoff, () -> true);
    }

    record BlobStoreStats(long blobCount, long totalBytes, long availableBytes) {}

    /** One stored blob as listed by {@link #blobs()}. */
    record BlobInfo(String sha256, long size, Instant lastModified) {}
}
