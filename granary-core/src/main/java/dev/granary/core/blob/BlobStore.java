package dev.granary.core.blob;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

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

    /** Opens the blob with the given digest, or empty when it is not present. */
    Optional<InputStream> open(String sha256) throws IOException;

    boolean exists(String sha256);

    /** Removes a blob. Returns {@code true} when something was deleted. */
    boolean delete(String sha256) throws IOException;

    BlobStoreStats stats() throws IOException;

    record BlobStoreStats(long blobCount, long totalBytes, long availableBytes) {}
}
