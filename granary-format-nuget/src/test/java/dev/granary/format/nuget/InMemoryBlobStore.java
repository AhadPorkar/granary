package dev.granary.format.nuget;

import dev.granary.core.blob.BlobRef;
import dev.granary.core.blob.BlobStore;
import dev.granary.core.storage.Checksums;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A map-backed blob store, so handler tests can hand real bytes to {@code StoredContent} without mocking streams. */
final class InMemoryBlobStore implements BlobStore {

    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public BlobRef store(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        String sha = Checksums.hex("SHA-256", bytes);
        blobs.put(sha, bytes);
        return new BlobRef(sha, bytes.length);
    }

    /** Stores {@code bytes} and returns their digest. */
    String put(byte[] bytes) {
        String sha = Checksums.hex("SHA-256", bytes);
        blobs.put(sha, bytes);
        return sha;
    }

    @Override
    public Optional<InputStream> open(String sha256) {
        byte[] bytes = blobs.get(sha256);
        return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public boolean exists(String sha256) {
        return blobs.containsKey(sha256);
    }

    @Override
    public boolean delete(String sha256) {
        return blobs.remove(sha256) != null;
    }

    @Override
    public BlobStoreStats stats() {
        return new BlobStoreStats(blobs.size(), blobs.values().stream().mapToLong(b -> b.length).sum(), Long.MAX_VALUE);
    }
}
