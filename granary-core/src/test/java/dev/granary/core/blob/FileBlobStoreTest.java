package dev.granary.core.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.storage.Checksums;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBlobStoreTest {

    private static final String HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @TempDir
    Path dir;

    FileBlobStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new FileBlobStore("default", dir);
    }

    @Test
    void storesContentAddressedBySha256() throws IOException {
        BlobRef ref = store.store(bytes("hello"));

        assertThat(ref.sha256()).isEqualTo(HELLO_SHA256);
        assertThat(ref.size()).isEqualTo(5);
        assertThat(store.exists(HELLO_SHA256)).isTrue();
        assertThat(store.pathFor(HELLO_SHA256)).isEqualTo(dir.resolve("content/2c/f2/" + HELLO_SHA256));
        try (InputStream in = store.open(HELLO_SHA256).orElseThrow()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
    }

    @Test
    void storingTheSameBytesTwiceIsIdempotent() throws IOException {
        BlobRef first = store.store(bytes("hello"));
        BlobRef second = store.store(bytes("hello"));

        assertThat(second).isEqualTo(first);
        assertThat(store.stats().blobCount()).isEqualTo(1);
    }

    @Test
    void openOfUnknownDigestIsEmpty() throws IOException {
        assertThat(store.open("0".repeat(64))).isEmpty();
        assertThat(store.exists("0".repeat(64))).isFalse();
    }

    @Test
    void rejectsMalformedDigests() {
        assertThatThrownBy(() -> store.open("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open("ZZ".repeat(32))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRemovesBlobAndReportsWhetherItExisted() throws IOException {
        store.store(bytes("hello"));

        assertThat(store.delete(HELLO_SHA256)).isTrue();
        assertThat(store.delete(HELLO_SHA256)).isFalse();
        assertThat(store.exists(HELLO_SHA256)).isFalse();
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws IOException {
        store.store(bytes("one"));
        store.store(bytes("two"));

        try (var files = Files.list(dir.resolve("tmp"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void statsCountBlobsAndBytes() throws IOException {
        store.store(bytes("hello"));
        store.store(bytes("granary"));

        BlobStore.BlobStoreStats stats = store.stats();
        assertThat(stats.blobCount()).isEqualTo(2);
        assertThat(stats.totalBytes()).isEqualTo(5 + 7);
        assertThat(stats.availableBytes()).isPositive();
    }

    @Test
    void concurrentWritersOfTheSameContentAllSucceed() throws Exception {
        byte[] payload = new byte[256 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<BlobRef>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> store.store(new ByteArrayInputStream(payload)));
            }
            List<Future<BlobRef>> results = pool.invokeAll(tasks);
            BlobRef expected = results.get(0).get();
            for (Future<BlobRef> f : results) {
                assertThat(f.get()).isEqualTo(expected);
            }
            assertThat(store.stats().blobCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void blobRefValidatesItsArguments() {
        assertThatThrownBy(() -> new BlobRef("short", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlobRef(HELLO_SHA256, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsEveryBlobWithSizeAndModificationTimeLazily() throws IOException {
        BlobRef hello = store.store(bytes("hello"));
        BlobRef other = store.store(bytes("granary"));
        // things that are not blobs are ignored
        Files.writeString(dir.resolve("content/2c/f2/not-a-digest.txt"), "x");
        Files.createDirectories(dir.resolve("content/zz/yy"));
        Files.writeString(dir.resolve("content/stray.txt"), "x");

        Map<String, BlobStore.BlobInfo> listed;
        try (Stream<BlobStore.BlobInfo> blobs = store.blobs()) {
            listed = blobs.collect(Collectors.toMap(BlobStore.BlobInfo::sha256, b -> b));
        }

        assertThat(listed).containsOnlyKeys(hello.sha256(), other.sha256());
        assertThat(listed.get(hello.sha256()).size()).isEqualTo(5);
        assertThat(listed.get(other.sha256()).size()).isEqualTo(7);
        assertThat(listed.get(hello.sha256()).lastModified()).isAfter(Instant.now().minusSeconds(60));
        assertThat(store.supportsCompaction()).isTrue();
    }

    /**
     * Only files where the store itself puts blobs are blobs. A directory the store does not create (a file system's
     * {@code lost+found} when {@code content/} is a mount point, a backup copy) is not even entered, since it may not
     * be readable at all, and a digest-named file in the wrong fan-out directory is not the blob of that digest.
     */
    @Test
    void listsOnlyFilesInTheStoresOwnLayout() throws IOException {
        BlobRef hello = store.store(bytes("hello"));
        String elsewhere = "ff" + "0".repeat(62);
        Files.createDirectories(dir.resolve("content/lost+found/2c"));
        Files.writeString(dir.resolve("content/lost+found/2c/" + "2c" + "1".repeat(62)), "foreign");
        Files.createDirectories(dir.resolve("content/2C/F2"));
        Files.writeString(dir.resolve("content/2C/F2/" + "2cf2" + "1".repeat(60)), "upper case directories");
        Files.writeString(dir.resolve("content/2c/f2/" + elsewhere), "misplaced");
        Files.createDirectories(dir.resolve("content/2c/f2/" + "2cf2" + "2".repeat(60)));

        List<String> listed;
        try (Stream<BlobStore.BlobInfo> blobs = store.blobs()) {
            listed = blobs.map(BlobStore.BlobInfo::sha256).toList();
        }

        assertThat(listed).containsExactly(hello.sha256());
    }

    @Test
    void anEmptyStoreListsNothing() throws IOException {
        try (Stream<BlobStore.BlobInfo> blobs = store.blobs()) {
            assertThat(blobs).isEmpty();
        }
    }

    @Test
    void storingExistingBytesAgainRefreshesTheirModificationTime() throws IOException {
        store.store(bytes("hello"));
        Path file = store.pathFor(HELLO_SHA256);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(3))));

        store.store(bytes("hello"));

        assertThat(Files.getLastModifiedTime(file).toInstant()).isAfter(Instant.now().minusSeconds(60));
    }

    @Test
    void deleteIfUnmodifiedSinceKeepsRecentBlobsAndDeletesOldOnes() throws IOException {
        store.store(bytes("hello"));
        Path file = store.pathFor(HELLO_SHA256);
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));

        assertThat(store.deleteIfUnmodifiedSince(HELLO_SHA256, cutoff)).isFalse();
        assertThat(store.exists(HELLO_SHA256)).isTrue();

        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(25))));
        assertThat(store.deleteIfUnmodifiedSince(HELLO_SHA256, cutoff)).isTrue();
        assertThat(store.exists(HELLO_SHA256)).isFalse();
        assertThat(store.deleteIfUnmodifiedSince(HELLO_SHA256, cutoff)).isFalse();
    }

    @Test
    void aPinnedBlobIsKeptWhateverItsAgeUntilEveryWriterReleasedIt() throws IOException {
        Instant future = Instant.now().plus(Duration.ofDays(1));
        PinnedBlob first = store.storePinned(bytes("hello"));
        PinnedBlob second = store.storePinned(bytes("hello"));
        assertThat(first.ref().sha256()).isEqualTo(HELLO_SHA256);

        assertThat(store.deleteIfUnmodifiedSince(HELLO_SHA256, future)).isFalse();
        first.close();
        first.close(); // only the first close counts
        assertThat(store.deleteIfUnmodifiedSince(HELLO_SHA256, future)).isFalse();
        second.close();

        assertThat(store.deleteIfUnmodifiedSince(HELLO_SHA256, future)).isTrue();
        assertThat(store.exists(HELLO_SHA256)).isFalse();
    }

    @Test
    void aPinThroughOneStoreProtectsTheBlobFromAnotherStoreOfTheSameDirectory() throws IOException {
        Path link = Files.createSymbolicLink(dir.getParent().resolve(dir.getFileName() + "-pin-link"), dir);
        try {
            FileBlobStore throughLink = new FileBlobStore("link", link);
            Instant future = Instant.now().plus(Duration.ofDays(1));
            try (PinnedBlob pinned = throughLink.storePinned(bytes("hello"))) {
                assertThat(store.deleteIfUnmodifiedSince(pinned.ref().sha256(), future)).isFalse();
            }
            assertThat(store.deleteIfUnmodifiedSince(HELLO_SHA256, future)).isTrue();
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void theReferenceCheckIsAskedLastAndCanKeepTheBlob() throws IOException {
        store.store(bytes("hello"));
        Instant future = Instant.now().plus(Duration.ofDays(1));
        Instant past = Instant.now().minus(Duration.ofDays(1));
        int[] asked = new int[1];

        assertThat(store.deleteIfUnmodifiedSince(HELLO_SHA256, past, () -> ++asked[0] > 0)).isFalse();
        assertThat(asked[0]).as("not asked for a blob that is too young").isZero();
        assertThat(store.deleteIfUnmodifiedSince(HELLO_SHA256, future, () -> ++asked[0] < 0)).isFalse();
        assertThat(asked[0]).isEqualTo(1);
        assertThat(store.exists(HELLO_SHA256)).isTrue();
        assertThat(store.deleteIfUnmodifiedSince(HELLO_SHA256, future, () -> true)).isTrue();
    }

    @Test
    void deletingTheLastBlobOfAFanOutDirectoryRemovesTheEmptyDirectories() throws IOException {
        store.store(bytes("hello"));
        Path file = store.pathFor(HELLO_SHA256);

        assertThat(store.delete(HELLO_SHA256)).isTrue();

        assertThat(file.getParent()).doesNotExist();
        assertThat(file.getParent().getParent()).doesNotExist();
        assertThat(dir.resolve("content")).isDirectory();
        // and the next write recreates them
        assertThat(store.store(bytes("hello")).sha256()).isEqualTo(HELLO_SHA256);
        assertThat(store.exists(HELLO_SHA256)).isTrue();
    }

    @Test
    void fanOutDirectoriesStayWhileTheyHoldOtherBlobs() throws IOException {
        store.store(bytes("hello"));
        Path sibling = store.pathFor(HELLO_SHA256).resolveSibling("2cf2" + "0".repeat(60));
        Files.writeString(sibling, "other");

        store.delete(HELLO_SHA256);

        assertThat(sibling).exists();
    }

    @Test
    void storesStayCorrectWhileOldCopiesAreDeletedConcurrently() throws Exception {
        byte[] payload = "contended".getBytes(StandardCharsets.UTF_8);
        String sha = store.store(new ByteArrayInputStream(payload)).sha256();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 50; round++) {
                Files.setLastModifiedTime(store.pathFor(sha), FileTime.from(Instant.now().minus(Duration.ofDays(2))));
                Instant cutoff = Instant.now().minus(Duration.ofDays(1));
                List<Callable<Object>> tasks = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    tasks.add(() -> store.store(new ByteArrayInputStream(payload)));
                    tasks.add(() -> store.deleteIfUnmodifiedSince(sha, cutoff));
                }
                for (Future<Object> f : pool.invokeAll(tasks)) {
                    f.get();
                }
                // whatever the interleaving: a store that returned left its bytes in place, either because it ran
                // after the delete or because it refreshed the time before the delete looked at it
                assertThat(store.exists(sha)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Blobs whose digests start alike share a fan-out directory. Compaction deleting the last of them removes the
     * directory, possibly while a store of another blob is creating it; the store must create it again, not fail.
     */
    @Test
    void storesIntoAFanOutDirectoryThatCompactionIsRemovingSucceed() throws Exception {
        List<byte[]> sharing = payloadsSharingAFanOutDirectory(6);
        List<String> digests = new ArrayList<>();
        for (byte[] payload : sharing) {
            digests.add(Checksums.hex("SHA-256", payload));
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int round = 0; round < 300; round++) {
                for (int i = 0; i < 6; i++) {
                    store.delete(digests.get(i));
                }
                for (int i = 0; i < 3; i++) {
                    store.store(new ByteArrayInputStream(sharing.get(i)));
                    Files.setLastModifiedTime(store.pathFor(digests.get(i)), FileTime.from(Instant.now().minus(Duration.ofDays(2))));
                }
                Instant cutoff = Instant.now().minus(Duration.ofDays(1));
                List<Callable<Object>> tasks = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    String old = digests.get(i);
                    byte[] fresh = sharing.get(i + 3);
                    tasks.add(() -> store.deleteIfUnmodifiedSince(old, cutoff));
                    tasks.add(() -> store.store(new ByteArrayInputStream(fresh)));
                }
                for (Future<Object> f : pool.invokeAll(tasks)) {
                    f.get();
                }
                for (int i = 3; i < 6; i++) {
                    assertThat(store.exists(digests.get(i))).as("round %d", round).isTrue();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** {@code count} different payloads whose SHA-256 digests share the first four hex digits. */
    private static List<byte[]> payloadsSharingAFanOutDirectory(int count) {
        Map<String, List<byte[]>> byDirectory = new java.util.HashMap<>();
        for (int i = 0; ; i++) {
            byte[] payload = ("fan-out " + i).getBytes(StandardCharsets.UTF_8);
            List<byte[]> bucket = byDirectory.computeIfAbsent(
                    Checksums.hex("SHA-256", payload).substring(0, 4), k -> new ArrayList<>());
            bucket.add(payload);
            if (bucket.size() == count) {
                return bucket;
            }
        }
    }

    /**
     * Two configured stores may share one directory. A store through one of them and compaction through the other
     * must still exclude each other per blob, or compaction could delete bytes a new upload just re-used.
     */
    @Test
    void storesThroughAnAliasStayCorrectWhileOldCopiesAreDeletedConcurrently() throws Exception {
        FileBlobStore alias = new FileBlobStore("alias", dir);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 1000; round++) {
                byte[] payload = ("contended " + round).getBytes(StandardCharsets.UTF_8);
                String sha = store.store(new ByteArrayInputStream(payload)).sha256();
                Files.setLastModifiedTime(store.pathFor(sha), FileTime.from(Instant.now().minus(Duration.ofDays(2))));
                Instant cutoff = Instant.now().minus(Duration.ofDays(1));
                List<Callable<Object>> tasks = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    tasks.add(() -> alias.store(new ByteArrayInputStream(payload)));
                    tasks.add(() -> store.deleteIfUnmodifiedSince(sha, cutoff));
                }
                for (Future<Object> f : pool.invokeAll(tasks)) {
                    f.get();
                }
                assertThat(alias.exists(sha)).as("round %d", round).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A store configured at a symlink to another store's directory (or at a bind mount of it) is the same location,
     * although the two paths differ: compaction must treat them as one store and their per-blob locks must be shared.
     */
    @Test
    void storesReachedThroughASymlinkShareTheirLocation() throws IOException {
        Path link = Files.createSymbolicLink(dir.getParent().resolve(dir.getFileName() + "-link"), dir);
        try {
            FileBlobStore throughLink = new FileBlobStore("link", link);
            FileBlobStore elsewhere = new FileBlobStore("elsewhere", dir.resolve("nested-store"));

            assertThat(throughLink.root()).isNotEqualTo(store.root());
            assertThat(throughLink.location()).isEqualTo(store.location());
            assertThat(elsewhere.location()).isNotEqualTo(store.location());

            // and both see the same blobs
            BlobRef ref = throughLink.store(bytes("hello"));
            assertThat(store.exists(ref.sha256())).isTrue();
        } finally {
            Files.delete(link);
        }
    }

    /**
     * Compaction removes fan-out directories that became empty. The dashboard's statistics walk the same tree at the
     * same time and must skip what disappears under them instead of failing.
     */
    @Test
    void statsStayAvailableWhileCompactionRemovesDirectories() throws Exception {
        List<String> digests = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            digests.add(store.store(bytes("blob " + i)).sha256());
        }
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofDays(2)));
        for (String sha : digests) {
            Files.setLastModifiedTime(store.pathFor(sha), old);
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> compaction = pool.submit(() -> {
                for (String sha : digests) {
                    store.deleteIfUnmodifiedSince(sha, cutoff);
                }
                return null;
            });
            int walks = 0;
            while (!compaction.isDone() || walks == 0) {
                BlobStore.BlobStoreStats stats = store.stats();
                assertThat(stats.blobCount()).isBetween(0L, 2000L);
                walks++;
            }
            compaction.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.stats().blobCount()).isZero();
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
