package dev.granary.core.blob;

import dev.granary.core.concurrent.KeyedLocks;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * File-system blob store laid out as {@code <root>/content/ab/cd/<sha256>}.
 *
 * <p>Writes go to a temporary file first and are moved into place atomically, so a crash
 * mid-upload never leaves a half-written blob where a reader could find it. Because the
 * path is derived from the digest, storing the same bytes twice is idempotent; storing bytes
 * that are already present refreshes the file's modification time, which compaction uses to
 * leave blobs alone that an upload in progress is about to reference.
 */
public class FileBlobStore implements BlobStore {

    private static final HexFormat HEX = HexFormat.of();
    /** Moving into place, refreshing the time and deleting take microseconds; this only guards against bugs. */
    private static final Duration DIGEST_LOCK_TIMEOUT = Duration.ofMinutes(1);

    private final String name;
    private final Path root;
    private final Path content;
    private final Path tmp;
    /** See {@link #location()}. */
    private final Object location;
    /**
     * Serialises "move into place or refresh" against "delete if old" for one blob file. Shared by every instance and
     * keyed by {@link #location()}, because two configured stores may keep their files in the same directory.
     */
    private static final KeyedLocks<BlobKey> BLOB_LOCKS = new KeyedLocks<>();

    /**
     * Blobs stored by {@link #storePinned} whose writers have not released them yet, with the number of such
     * writers. Changed only under the blob's lock when pinning; an entry disappears with its last writer.
     */
    private static final Map<BlobKey, Integer> PINS = new ConcurrentHashMap<>();

    /** One blob file, whichever configured store reaches it. */
    private record BlobKey(Object location, String sha256) {
    }
    /** How often placing a blob is retried when compaction removes its fan-out directory at the same moment. */
    private static final int PLACE_ATTEMPTS = 10;

    public FileBlobStore(String name, Path root) throws IOException {
        this.name = name;
        this.root = root;
        this.content = root.resolve("content");
        this.tmp = root.resolve("tmp");
        Files.createDirectories(content);
        Files.createDirectories(tmp);
        this.location = identify(content);
    }

    @Override
    public String name() {
        return name;
    }

    public Path root() {
        return root;
    }

    /**
     * Identifies the directory this store keeps its blobs in, the same for every store configured at that directory
     * however its path is spelled: a relative path, a symbolic link or a bind mount to it. Compaction treats stores
     * with equal locations as one store, and their per-blob locks are shared.
     */
    public Object location() {
        return location;
    }

    /**
     * The file system's identity of {@code dir} (device and inode on Unix), which is the same for every path that
     * reaches it; where the file system has none, its real path with symbolic links resolved.
     */
    private static Object identify(Path dir) throws IOException {
        Object fileKey = Files.readAttributes(dir, BasicFileAttributes.class).fileKey();
        return fileKey != null ? fileKey : dir.toRealPath();
    }

    @Override
    public BlobRef store(InputStream in) throws IOException {
        return write(in, false);
    }

    @Override
    public PinnedBlob storePinned(InputStream in) throws IOException {
        BlobRef ref = write(in, true);
        BlobKey key = blobKey(ref.sha256());
        return new PinnedBlob(ref, () -> PINS.computeIfPresent(key, (k, writers) -> writers == 1 ? null : writers - 1));
    }

    private BlobRef write(InputStream in, boolean pin) throws IOException {
        Path temp = Files.createTempFile(tmp, "upload-", ".part");
        try {
            MessageDigest digest = sha256();
            long size;
            try (OutputStream out = Files.newOutputStream(temp);
                 DigestInputStream din = new DigestInputStream(in, digest)) {
                size = din.transferTo(out);
            }
            String hex = HEX.formatHex(digest.digest());
            try (KeyedLocks<BlobKey>.Lease ignored = lockDigest(hex)) {
                placeOrRefresh(temp, pathFor(hex));
                if (pin) {
                    PINS.merge(blobKey(hex), 1, Integer::sum);
                }
            }
            return new BlobRef(hex, size);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void placeOrRefresh(Path temp, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            if (Files.exists(target)) {
                // the bytes are already there; make them look recent so compaction keeps them for the new reference
                Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
                return;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (NoSuchFileException | FileAlreadyExistsException e) {
                // compaction deleted the last other blob of this fan-out directory and removed the directory while it
                // was being created (createDirectories then reports it as existing but not a directory) or before the
                // move: create it again
                if (attempt >= PLACE_ATTEMPTS) {
                    throw e;
                }
            } catch (IOException e) {
                // A concurrent writer may have won the race; that is fine as long as the blob exists.
                if (!Files.exists(target)) {
                    throw e;
                }
            }
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
        Path path = pathFor(sha256);
        try (KeyedLocks<BlobKey>.Lease ignored = lockDigest(sha256)) {
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                removeEmptyFanOutDirectories(path);
            }
            return deleted;
        }
    }

    @Override
    public boolean supportsCompaction() {
        return true;
    }

    @Override
    public boolean deleteIfUnmodifiedSince(String sha256, Instant cutoff, BooleanSupplier unreferenced)
            throws IOException {
        Path path = pathFor(sha256);
        try (KeyedLocks<BlobKey>.Lease ignored = lockDigest(sha256)) {
            if (PINS.containsKey(blobKey(sha256))) {
                return false; // a write stored these bytes and has not committed its reference yet
            }
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class);
            } catch (NoSuchFileException e) {
                return false;
            }
            if (!attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                return false;
            }
            // last, so that a write which released its pin before this point has its reference counted
            if (!unreferenced.getAsBoolean()) {
                return false;
            }
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                removeEmptyFanOutDirectories(path);
            }
            return deleted;
        }
    }

    /**
     * Streams the blobs of {@code content/ab/cd/}. The two fan-out levels are listed one directory at a time and
     * the files of a directory are read lazily, so memory use does not depend on the number of blobs. Entries that
     * disappear while the stream is consumed are skipped. Only the store's own layout is read: directories whose
     * names are not two lower-case hex digits (a file system's {@code lost+found}, a backup copy) are not entered,
     * and a file is a blob only when its name is a digest that belongs in the directory it is in.
     */
    @Override
    public Stream<BlobInfo> blobs() throws IOException {
        return subdirectories(content).stream()
                .flatMap(first -> subdirectories(first).stream())
                .flatMap(FileBlobStore::blobsIn);
    }

    /** The fan-out directories ({@code ab}) directly inside {@code dir}, sorted. */
    private static List<Path> subdirectories(Path dir) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir,
                entry -> isFanOutName(entry.getFileName().toString()) && Files.isDirectory(entry))) {
            for (Path entry : entries) {
                result.add(entry);
            }
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + dir, e);
        }
        result.sort(null);
        return result;
    }

    private static Stream<BlobInfo> blobsIn(Path dir) {
        Stream<Path> files;
        try {
            files = Files.list(dir);
        } catch (NoSuchFileException e) {
            return Stream.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + dir, e);
        }
        // content/ab/cd/ holds the digests that start with abcd
        String prefix = dir.getParent().getFileName().toString() + dir.getFileName();
        return files.map(file -> info(file, prefix)).filter(Objects::nonNull);
    }

    private static BlobInfo info(Path file, String prefix) {
        String fileName = file.getFileName().toString();
        if (!isDigest(fileName) || !fileName.startsWith(prefix)) {
            return null;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                return null;
            }
            return new BlobInfo(fileName, attributes.size(), attributes.lastModifiedTime().toInstant());
        } catch (IOException e) {
            return null; // deleted while listing
        }
    }

    /** Removes {@code content/ab/cd} and {@code content/ab} when the blob just deleted was their last entry. */
    private void removeEmptyFanOutDirectories(Path blob) {
        Path dir = blob.getParent();
        for (int level = 0; level < 2 && dir != null && !dir.equals(content); level++) {
            try {
                Files.deleteIfExists(dir);
            } catch (DirectoryNotEmptyException e) {
                return;
            } catch (IOException e) {
                return; // cosmetic only; an empty directory does no harm
            }
            dir = dir.getParent();
        }
    }

    /**
     * Counts the files under {@code content/}. Compaction may delete blobs and remove their fan-out directories while
     * this walks the tree; whatever disappears on the way is simply not counted.
     */
    @Override
    public BlobStoreStats stats() throws IOException {
        long[] totals = new long[2];
        Files.walkFileTree(content, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    totals[0]++;
                    totals[1] += attributes.size();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                if (e instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw e;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null && !(e instanceof NoSuchFileException)) {
                    throw e;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        FileStore fs = Files.getFileStore(root);
        return new BlobStoreStats(totals[0], totals[1], fs.getUsableSpace());
    }

    Path pathFor(String sha256) {
        if (!isDigest(sha256)) {
            throw new IllegalArgumentException("invalid sha256 digest");
        }
        return content.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    private KeyedLocks<BlobKey>.Lease lockDigest(String sha256) throws IOException {
        try {
            return BLOB_LOCKS.tryAcquire(blobKey(sha256), DIGEST_LOCK_TIMEOUT)
                    .orElseThrow(() -> new IOException("timed out waiting for blob " + sha256));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for blob " + sha256, e);
        }
    }

    private BlobKey blobKey(String sha256) {
        if (!isDigest(sha256)) {
            throw new IllegalArgumentException("invalid sha256 digest");
        }
        return new BlobKey(location, sha256);
    }

    private static boolean isDigest(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(FileBlobStore::isHex);
    }

    private static boolean isFanOutName(String name) {
        return name.length() == 2 && isHex(name.charAt(0)) && isHex(name.charAt(1));
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
