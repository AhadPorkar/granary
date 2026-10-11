package dev.granary.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.CoreTestApplication;
import dev.granary.core.CoreServiceTest;
import dev.granary.core.GranaryException;
import dev.granary.core.blob.FileBlobStore;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Two configured blob stores that keep their files in one directory, here {@code real} and {@code link}, a symbolic
 * link to it: a blob that a repository of either store references must survive the compaction of the other, the two
 * must never be compacted at the same time, and the scheduled run compacts the directory once.
 */
class BlobCompactionSharedDirectoryTest extends CoreServiceTest {

    private static final Path REAL;
    private static final Path LINK;

    static {
        try {
            Path base = Files.createTempDirectory("granary-shared-blob-dir");
            REAL = Files.createDirectories(base.resolve("real"));
            LINK = Files.createSymbolicLink(base.resolve("link"), REAL);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void sharedDirectory(DynamicPropertyRegistry registry) {
        registry.add("granary.storage.blob-stores.real.path", REAL::toString);
        registry.add("granary.storage.blob-stores.link.path", LINK::toString);
    }

    @Autowired
    BlobCompactionService compaction;

    @Autowired
    StorageService storage;

    @Test
    void aBlobReferencedThroughTheOtherStoreSurvivesCompaction() throws IOException {
        Repository viaLink = hostedIn("via-link", "link");
        Repository viaReal = hostedIn("via-real", "real");
        StoredContent linked = storage.store(viaLink, "a.bin", in("referenced through the link"), null,
                Asset.Origin.UPLOADED, false);
        StoredContent real = storage.store(viaReal, "b.bin", in("referenced through the real path"), null,
                Asset.Origin.UPLOADED, false);
        age(linked, Duration.ofDays(3));
        age(real, Duration.ofDays(3));

        assertThat(compaction.compact("real").blobsDeleted()).isZero();
        assertThat(compaction.compact("link").blobsDeleted()).isZero();

        assertThat(storage.find(viaLink, "a.bin").orElseThrow().readUtf8()).isEqualTo("referenced through the link");
        assertThat(storage.find(viaReal, "b.bin").orElseThrow().readUtf8())
                .isEqualTo("referenced through the real path");

        storage.delete(viaLink, "a.bin");
        BlobCompactionService.Report report = compaction.compact("real");
        assertThat(report.blobsDeleted()).isEqualTo(1);
        assertThat(linked.blobStore().exists(linked.asset().getBlobSha256())).isFalse();
        assertThat(storage.find(viaReal, "b.bin").orElseThrow().readUtf8())
                .isEqualTo("referenced through the real path");
    }

    @Test
    void theSharedDirectoryIsCompactedByOneRunAtATime() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            ReentrantLock lock = compaction.runningLock("real");
            lock.lock();
            try {
                held.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> compaction.compact("link"))
                .isInstanceOf(GranaryException.class)
                .satisfies(e -> assertThat(((GranaryException) e).status()).isEqualTo(409))
                .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("blobStore.compaction.running"));

        release.countDown();
        running.get(5, TimeUnit.SECONDS);
        assertThat(compaction.compact("link").blobStore()).isEqualTo("link");
    }

    @Test
    void theScheduledRunCompactsEachDirectoryOnce() {
        List<String> compacted = compaction.compactAll().stream().map(BlobCompactionService.Report::blobStore).toList();

        assertThat(compacted).contains("default", "secondary").doesNotHaveDuplicates();
        assertThat(compacted).filteredOn(name -> name.equals("real") || name.equals("link")).hasSize(1);
        assertThat(compacted).hasSize(3);
    }

    private Repository hostedIn(String name, String blobStore) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat(CoreTestApplication.FAKE_FORMAT);
        r.setType(RepositoryType.HOSTED);
        r.setBlobStore(blobStore);
        return repositories.create(r);
    }

    private static void age(StoredContent content, Duration by) throws IOException {
        Path root = ((FileBlobStore) content.blobStore()).root();
        String sha = content.asset().getBlobSha256();
        Path file = root.resolve("content").resolve(sha.substring(0, 2)).resolve(sha.substring(2, 4)).resolve(sha);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(by)));
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
