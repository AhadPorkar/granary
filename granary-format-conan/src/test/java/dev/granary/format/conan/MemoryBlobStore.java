package dev.granary.format.conan;

import dev.granary.core.blob.BlobRef;
import dev.granary.core.blob.BlobStore;
import dev.granary.core.storage.Checksums;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** An in-memory blob store so unit tests can hand real {@code StoredContent} to the code under test. */
final class MemoryBlobStore implements BlobStore {

    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public BlobRef store(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        String sha256 = Checksums.hex("SHA-256", bytes);
        blobs.put(sha256, bytes);
        return new BlobRef(sha256, bytes.length);
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
        return new BlobStoreStats(blobs.size(), blobs.values().stream().mapToLong(b -> b.length).sum(), 0);
    }
}
