package dev.granary.core.blob;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * File-system blob store laid out as {@code <root>/content/ab/cd/<sha256>}.
 *
 * <p>Writes go to a temporary file first and are moved into place atomically, so a crash
 * mid-upload never leaves a half-written blob where a reader could find it. Because the
 * path is derived from the digest, storing the same bytes twice is idempotent.
 */
public class FileBlobStore implements BlobStore {

    private static final HexFormat HEX = HexFormat.of();

    private final String name;
    private final Path root;
    private final Path content;
    private final Path tmp;

    public FileBlobStore(String name, Path root) throws IOException {
        this.name = name;
        this.root = root;
        this.content = root.resolve("content");
        this.tmp = root.resolve("tmp");
        Files.createDirectories(content);
        Files.createDirectories(tmp);
    }

    @Override
    public String name() {
        return name;
    }

    public Path root() {
        return root;
    }

    @Override
    public BlobRef store(InputStream in) throws IOException {
        Path temp = Files.createTempFile(tmp, "upload-", ".part");
        try {
            MessageDigest digest = sha256();
            long size;
            try (OutputStream out = Files.newOutputStream(temp);
                 DigestInputStream din = new DigestInputStream(in, digest)) {
                size = din.transferTo(out);
            }
            String hex = HEX.formatHex(digest.digest());
            Path target = pathFor(hex);
            if (Files.exists(target)) {
                Files.deleteIfExists(temp);
            } else {
                Files.createDirectories(target.getParent());
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    // A concurrent writer may have won the race; that is fine as long as the blob exists.
                    if (!Files.exists(target)) {
                        throw e;
                    }
                    Files.deleteIfExists(temp);
                }
            }
            return new BlobRef(hex, size);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public Optional<InputStream> open(String sha256) throws IOException {
        Path path = pathFor(sha256);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(Files.newInputStream(path));
    }

    @Override
    public boolean exists(String sha256) {
        return Files.isRegularFile(pathFor(sha256));
    }

    @Override
    public boolean delete(String sha256) throws IOException {
        return Files.deleteIfExists(pathFor(sha256));
    }

    @Override
    public BlobStoreStats stats() throws IOException {
        AtomicLong count = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        try (Stream<Path> files = Files.walk(content)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                count.incrementAndGet();
                try {
                    bytes.addAndGet(Files.size(p));
                } catch (IOException ignored) {
                    // the file disappeared between walk and size; nothing to count
                }
            });
        }
        FileStore fs = Files.getFileStore(root);
        return new BlobStoreStats(count.get(), bytes.get(), fs.getUsableSpace());
    }

    Path pathFor(String sha256) {
        if (sha256 == null || sha256.length() != 64 || !sha256.chars().allMatch(FileBlobStore::isHex)) {
            throw new IllegalArgumentException("invalid sha256 digest");
        }
        return content.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }
}
