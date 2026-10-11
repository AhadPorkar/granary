package dev.granary.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.CoreTestApplication;
import dev.granary.core.CoreServiceTest;
import dev.granary.core.GranaryException;
import dev.granary.core.blob.BlobStore;
import dev.granary.core.blob.BlobStoreRegistry;
import dev.granary.core.blob.FileBlobStore;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Compaction on a real file blob store (a store of its own, {@code secondary}, so the numbers are exact): what is
 * referenced or young stays, what is unreferenced and old goes, and the report says so.
 */
class BlobCompactionServiceTest extends CoreServiceTest {

    static final String STORE = "secondary";

    @Autowired
    BlobCompactionService compaction;

    @Autowired
    StorageService storage;

    @Autowired
    BlobStoreRegistry blobStores;

    @Autowired
    GranaryProperties properties;

    @Autowired
    StorageLocks locks;

    @Autowired
    PlatformTransactionManager transactionManager;

    FileBlobStore store;

    @BeforeEach
    void emptyTheStore() throws IOException {
        store = (FileBlobStore) blobStores.require(STORE);
        List<String> leftovers;
        try (Stream<BlobStore.BlobInfo> blobs = store.blobs()) {
            leftovers = blobs.map(BlobStore.BlobInfo::sha256).toList();
        }
        for (String sha : leftovers) {
            store.delete(sha);
        }
    }

    @AfterEach
    void restoreGracePeriod() {
        properties.getStorage().getCompaction().setGracePeriod(Duration.ofHours(24));
    }

    @Test
    void deletesOnlyUnreferencedBlobsOlderThanTheGracePeriodAndReportsIt() throws IOException {
        Repository repo = hostedIn("h", STORE);
        StoredContent referenced = storage.store(repo, "kept.txt", in("still referenced"), null, Asset.Origin.UPLOADED, false);
        StoredContent oldOrphan = storage.store(repo, "old.txt", in("old orphan!"), null, Asset.Origin.UPLOADED, false);
        StoredContent youngOrphan = storage.store(repo, "young.txt", in("young orphan"), null, Asset.Origin.UPLOADED, false);
        storage.delete(repo, "old.txt");
        storage.delete(repo, "young.txt");
        age(referenced, Duration.ofDays(10));
        age(oldOrphan, Duration.ofDays(2));
        age(youngOrphan, Duration.ofHours(23));

        BlobCompactionService.Report report = compaction.compact(STORE);

        assertThat(store.exists(referenced.asset().getBlobSha256())).isTrue();
        assertThat(store.exists(oldOrphan.asset().getBlobSha256())).isFalse();
        assertThat(store.exists(youngOrphan.asset().getBlobSha256())).isTrue();
        assertThat(report.blobStore()).isEqualTo(STORE);
        assertThat(report.blobsScanned()).isEqualTo(3);
        assertThat(report.blobsDeleted()).isEqualTo(1);
        assertThat(report.bytesReclaimed()).isEqualTo("old orphan!".length());
        assertThat(report.durationMillis()).isNotNegative();
        assertThat(report.startedAt()).isBefore(Instant.now().plusSeconds(1));
        assertThat(report.gracePeriod()).isEqualTo("PT24H");
        assertThat(storage.find(repo, "kept.txt").orElseThrow().readUtf8()).isEqualTo("still referenced");
    }

    @Test
    void aBlobSharedByTwoRepositoriesStaysWhileOneStillReferencesIt() throws IOException {
        Repository a = hostedIn("a", STORE);
        Repository b = hostedIn("b", STORE);
        StoredContent inA = storage.store(a, "shared.bin", in("same bytes"), null, Asset.Origin.UPLOADED, false);
        StoredContent inB = storage.store(b, "copy.bin", in("same bytes"), null, Asset.Origin.UPLOADED, false);
        assertThat(inA.asset().getBlobSha256()).isEqualTo(inB.asset().getBlobSha256());
        age(inA, Duration.ofDays(5));

        storage.delete(a, "shared.bin");
        assertThat(compaction.compact(STORE).blobsDeleted()).isZero();
        assertThat(storage.find(b, "copy.bin").orElseThrow().readUtf8()).isEqualTo("same bytes");

        storage.delete(b, "copy.bin");
        BlobCompactionService.Report report = compaction.compact(STORE);
        assertThat(report.blobsDeleted()).isEqualTo(1);
        assertThat(store.exists(inA.asset().getBlobSha256())).isFalse();
    }

    @Test
    void referencesFromRepositoriesOfAnotherStoreDoNotKeepABlob() throws IOException {
        Repository here = hostedIn("here", STORE);
        Repository elsewhere = hostedIn("elsewhere", "default");
        StoredContent mine = storage.store(here, "f", in("identical"), null, Asset.Origin.UPLOADED, false);
        storage.store(elsewhere, "f", in("identical"), null, Asset.Origin.UPLOADED, false);
        storage.delete(here, "f");
        age(mine, Duration.ofDays(3));

        assertThat(compaction.compact(STORE).blobsDeleted()).isEqualTo(1);
        assertThat(store.exists(mine.asset().getBlobSha256())).isFalse();
        // the other store keeps its own copy for its own repository
        assertThat(storage.find(elsewhere, "f").orElseThrow().readUtf8()).isEqualTo("identical");
    }

    @Test
    void deletingARepositoryAndCompactingReclaimsItsBlobs() throws IOException {
        Repository repo = hostedIn("doomed", STORE);
        List<StoredContent> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            files.add(storage.store(repo, "f" + i, in("file number " + i), null, Asset.Origin.UPLOADED, false,
                    new StorageService.ComponentCoordinates("", "pkg", "1." + i)));
        }
        for (StoredContent f : files) {
            age(f, Duration.ofDays(2));
        }
        assertThat(compaction.compact(STORE).blobsDeleted()).isZero();

        repositories.delete("doomed");
        BlobCompactionService.Report report = compaction.compact(STORE);

        assertThat(report.blobsDeleted()).isEqualTo(5);
        assertThat(report.bytesReclaimed()).isEqualTo(files.stream().mapToLong(StoredContent::size).sum());
        assertThat(files).allSatisfy(f -> assertThat(store.exists(f.asset().getBlobSha256())).isFalse());
        assertThat(store.stats().blobCount()).isZero();
    }

    @Test
    void reUploadingTheBytesOfAnOldOrphanRescuesIt() throws IOException {
        Repository repo = hostedIn("h", STORE);
        StoredContent first = storage.store(repo, "v1", in("recycled bytes"), null, Asset.Origin.UPLOADED, false);
        storage.delete(repo, "v1");
        age(first, Duration.ofDays(30));

        // a new upload of the same bytes refreshes the blob before its asset row exists
        storage.store(repo, "v2", in("recycled bytes"), null, Asset.Origin.UPLOADED, false);
        storage.delete(repo, "v2");

        assertThat(compaction.compact(STORE).blobsDeleted()).isZero();
        assertThat(store.exists(first.asset().getBlobSha256())).isTrue();
    }

    @Test
    void worksInBatchesOverManyBlobs() throws IOException {
        Repository repo = hostedIn("many", STORE);
        int total = BlobCompactionService.BATCH_SIZE * 2 + 17;
        List<StoredContent> kept = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            StoredContent c = storage.store(repo, "file-" + i, in("blob " + i), null, Asset.Origin.UPLOADED, false);
            age(c, Duration.ofDays(2));
            if (i % 3 == 0) {
                kept.add(c);
            } else {
                storage.delete(repo, "file-" + i);
            }
        }

        BlobCompactionService.Report report = compaction.compact(STORE);

        assertThat(report.blobsScanned()).isEqualTo(total);
        assertThat(report.blobsDeleted()).isEqualTo(total - kept.size());
        assertThat(store.stats().blobCount()).isEqualTo(kept.size());
        assertThat(kept).allSatisfy(c -> assertThat(store.exists(c.asset().getBlobSha256())).isTrue());
    }

    @Test
    void unknownStoreIsNotFound() {
        assertThatThrownBy(() -> compaction.compact("nope"))
                .isInstanceOf(GranaryException.class)
                .satisfies(e -> assertThat(((GranaryException) e).status()).isEqualTo(404))
                .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("blobStore.notFound"));
    }

    @Test
    void onlyOneCompactionPerStoreRunsAtATime() throws Exception {
        Repository repo = hostedIn("busy", STORE);
        // enough blobs that the first compaction is still running while the second one is asked for
        for (int i = 0; i < 300; i++) {
            StoredContent c = storage.store(repo, "x" + i, in("busy " + i), null, Asset.Origin.UPLOADED, false);
            age(c, Duration.ofDays(2));
            storage.delete(repo, "x" + i);
        }
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Object>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            runs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    go.await(5, TimeUnit.SECONDS);
                    return compaction.compact(STORE);
                } catch (GranaryException e) {
                    return e;
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        go.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (CompletableFuture<Object> run : runs) {
            outcomes.add(run.get(60, TimeUnit.SECONDS));
        }

        long deleted = outcomes.stream().filter(o -> o instanceof BlobCompactionService.Report)
                .mapToLong(o -> ((BlobCompactionService.Report) o).blobsDeleted()).sum();
        assertThat(deleted).isEqualTo(300);
        assertThat(outcomes).filteredOn(o -> o instanceof GranaryException)
                .allSatisfy(o -> assertThat(((GranaryException) o).code()).isEqualTo("blobStore.compaction.running"))
                .allSatisfy(o -> assertThat(((GranaryException) o).status()).isEqualTo(409));
        assertThat(store.stats().blobCount()).isZero();
    }

    @Test
    void aCompactionIsRefusedWhileTheStoreIsBeingCompacted() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            var lock = compaction.runningLock(STORE);
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

        assertThatThrownBy(() -> compaction.compact(STORE))
                .isInstanceOf(GranaryException.class)
                .satisfies(e -> assertThat(((GranaryException) e).status()).isEqualTo(409))
                .satisfies(e -> assertThat(((GranaryException) e).code()).isEqualTo("blobStore.compaction.running"));
        // other stores are independent
        assertThat(compaction.compact("default").blobStore()).isEqualTo("default");

        release.countDown();
        running.get(5, TimeUnit.SECONDS);
        assertThat(compaction.compact(STORE).blobStore()).isEqualTo(STORE);
    }

    @Test
    void theScheduledRunCompactsEveryStoreWhenEnabled() throws IOException {
        Repository repo = hostedIn("h", STORE);
        StoredContent orphan = storage.store(repo, "o", in("scheduled orphan"), null, Asset.Origin.UPLOADED, false);
        storage.delete(repo, "o");
        age(orphan, Duration.ofDays(2));

        compaction.compactAllOnSchedule(); // disabled in the test profile
        assertThat(store.exists(orphan.asset().getBlobSha256())).isTrue();

        properties.getStorage().getCompaction().setEnabled(true);
        try {
            compaction.compactAllOnSchedule();
        } finally {
            properties.getStorage().getCompaction().setEnabled(false);
        }
        assertThat(store.exists(orphan.asset().getBlobSha256())).isFalse();
    }

    @Test
    void theGracePeriodIsConfigurable() throws IOException {
        Repository repo = hostedIn("h", STORE);
        StoredContent orphan = storage.store(repo, "o", in("orphan"), null, Asset.Origin.UPLOADED, false);
        storage.delete(repo, "o");
        age(orphan, Duration.ofHours(2));

        assertThat(compaction.compact(STORE).blobsDeleted()).isZero();
        properties.getStorage().getCompaction().setGracePeriod(Duration.ofHours(1));
        BlobCompactionService.Report report = compaction.compact(STORE);
        assertThat(report.blobsDeleted()).isEqualTo(1);
        assertThat(report.gracePeriod()).isEqualTo("PT1H");
    }

    /**
     * A write stores its blob first and commits the asset row that references it afterwards, possibly much later: it
     * may wait up to {@code granary.storage.write-lock-timeout} for a concurrent writer of the same path. Whatever the
     * grace period, compaction must not delete the blob meanwhile, or the row would be committed pointing at nothing.
     */
    @Test
    void aWriteWaitingToCommitKeepsItsBlobWhateverTheGracePeriod() throws Exception {
        properties.getStorage().getCompaction().setGracePeriod(Duration.ZERO);
        Repository repo = hostedIn("pending", STORE);
        String payload = "bytes of a write that has not committed yet";
        String sha = Checksums.hex("SHA-256", payload.getBytes(StandardCharsets.UTF_8));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // a concurrent writer of the same path holds its key
        CompletableFuture<Object> other = CompletableFuture.supplyAsync(() ->
                locks.withKey(new StorageLocks.AssetKey(repo.getId(), "pending.bin"), () -> {
                    held.countDown();
                    try {
                        return release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<StoredContent> write = CompletableFuture.supplyAsync(() ->
                storage.store(repo, "pending.bin", in(payload), null, Asset.Origin.UPLOADED, false));
        waitUntil(() -> store.exists(sha));
        Thread.sleep(50); // the blob is now older than a grace period of zero

        BlobCompactionService.Report report = compaction.compact(STORE);
        release.countDown();
        other.get(10, TimeUnit.SECONDS);
        write.get(10, TimeUnit.SECONDS);

        assertThat(report.blobsDeleted()).isZero();
        assertThat(store.exists(sha)).isTrue();
        assertThat(storage.find(repo, "pending.bin").orElseThrow().readUtf8()).isEqualTo(payload);
        // once the reference is committed nothing protects the blob but the reference itself
        storage.delete(repo, "pending.bin");
        Thread.sleep(50);
        assertThat(compaction.compact(STORE).blobsDeleted()).isEqualTo(1);
    }

    /** A write inside a caller's transaction keeps its blob out of compaction until that transaction commits. */
    @Test
    void aWriteInsideACallersTransactionKeepsItsBlobUntilTheCommit() throws Exception {
        properties.getStorage().getCompaction().setGracePeriod(Duration.ZERO);
        Repository repo = hostedIn("in-tx", STORE);
        String payload = "written inside a longer transaction";
        BlobCompactionService.Report report = new TransactionTemplate(transactionManager).execute(status -> {
            storage.store(repo, "in-tx.bin", in(payload), null, Asset.Origin.UPLOADED, false);
            try {
                Thread.sleep(50);
                // compaction runs on another thread and cannot see the uncommitted row
                return CompletableFuture.supplyAsync(() -> compaction.compact(STORE)).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(report.blobsDeleted()).isZero();
        assertThat(storage.find(repo, "in-tx.bin").orElseThrow().readUtf8()).isEqualTo(payload);
    }

    /** A write that fails or is rolled back after storing its blob leaves an orphan that compaction may reclaim. */
    @Test
    void failedAndRolledBackWritesReleaseTheirBlobs() throws Exception {
        properties.getStorage().getCompaction().setGracePeriod(Duration.ZERO);
        Duration lockTimeout = properties.getStorage().getWriteLockTimeout();
        Repository repo = hostedIn("failing", STORE);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Object> other = CompletableFuture.supplyAsync(() ->
                locks.withKey(new StorageLocks.AssetKey(repo.getId(), "busy.bin"), () -> {
                    held.countDown();
                    try {
                        return release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        properties.getStorage().setWriteLockTimeout(Duration.ofMillis(100));
        try {
            assertThatThrownBy(() -> storage.store(repo, "busy.bin", in("timed out"), null, Asset.Origin.UPLOADED, false))
                    .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
        } finally {
            properties.getStorage().setWriteLockTimeout(lockTimeout);
            release.countDown();
            other.get(10, TimeUnit.SECONDS);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            storage.store(repo, "rolled-back.bin", in("rolled back"), null, Asset.Origin.UPLOADED, false);
            status.setRollbackOnly();
        });
        Thread.sleep(50);

        BlobCompactionService.Report report = compaction.compact(STORE);

        assertThat(report.blobsDeleted()).isEqualTo(2);
        assertThat(storage.find(repo, "busy.bin")).isEmpty();
        assertThat(storage.find(repo, "rolled-back.bin")).isEmpty();
    }

    /**
     * A repository can name a blob store that is no longer configured, for example after the store was renamed in
     * the configuration. Its files are still on disk, possibly in this store's directory, and must survive until the
     * configuration is fixed: compaction cannot know where they are, so their references count for every store.
     */
    @Test
    void blobsOfARepositoryWhoseStoreIsNoLongerConfiguredAreKept() throws IOException {
        Repository repo = hostedIn("renamed", STORE);
        StoredContent file = storage.store(repo, "precious.bin", in("still wanted"), null, Asset.Origin.UPLOADED, false);
        age(file, Duration.ofDays(30));
        // the configuration called this store "archive" when the repository was created
        Repository row = repositoryDao.findById(repo.getId()).orElseThrow();
        row.setBlobStore("archive");
        repositoryDao.save(row);

        BlobCompactionService.Report report = compaction.compact(STORE);

        assertThat(report.blobsDeleted()).isZero();
        assertThat(store.exists(file.asset().getBlobSha256())).isTrue();
    }

    @Test
    void aNegativeGracePeriodIsRefused() {
        GranaryProperties.Compaction config = new GranaryProperties.Compaction();
        assertThatThrownBy(() -> config.setGracePeriod(Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grace-period");
        assertThatThrownBy(() -> config.setGracePeriod(null)).isInstanceOf(IllegalArgumentException.class);
        config.setGracePeriod(Duration.ZERO);
        assertThat(config.getGracePeriod()).isZero();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(5);
        }
    }

    private Repository hostedIn(String name, String blobStore) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat(CoreTestApplication.FAKE_FORMAT);
        r.setType(RepositoryType.HOSTED);
        r.setBlobStore(blobStore);
        return repositories.create(r);
    }

    private void age(StoredContent content, Duration by) throws IOException {
        Path root = ((FileBlobStore) content.blobStore()).root();
        String sha = content.asset().getBlobSha256();
        Path file = root.resolve("content").resolve(sha.substring(0, 2)).resolve(sha.substring(2, 4)).resolve(sha);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(by)));
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
