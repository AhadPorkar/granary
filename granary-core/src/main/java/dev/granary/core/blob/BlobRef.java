package dev.granary.core.blob;

/**
 * Identifies a stored blob by its SHA-256 digest (lower-case hex) and size in bytes.
 * Blobs are content-addressed, so two identical uploads share one {@code BlobRef}.
 */
public record BlobRef(String sha256, long size) {

    public BlobRef {
        if (sha256 == null || sha256.length() != 64) {
            throw new IllegalArgumentException("sha256 must be a 64 character hex digest");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }
}
