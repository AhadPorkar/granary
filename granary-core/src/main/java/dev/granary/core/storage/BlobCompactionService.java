package dev.granary.core.storage;

import dev.granary.core.GranaryException;
import dev.granary.core.blob.BlobStore;
import dev.granary.core.blob.BlobStoreRegistry;
import dev.granary.core.blob.FileBlobStore;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.dao.AssetDao;
import dev.granary.core.dao.RepositoryDao;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reclaims disk space: deletes the blobs of a store that no asset references any more (what other repository
 * managers call "compact blob store"). Deleting assets, components or whole repositories only removes rows; their
 * blobs stay until compaction, because one blob may be shared by many assets and repositories.
 *
 * <p>A blob is deleted only when
 * <ol>
 *   <li>no asset of a repository that keeps its blobs in this store (or in another store configured at the same
 *       directory, or in a store that is not configured any more) references it,</li>
 *   <li>its file was last written (or stored again) longer ago than the grace period
 *       ({@code granary.storage.compaction.grace-period}, 24 hours by default),</li>
 * </ol>
 * and it is not pinned by a write in progress. A write stores its blob before it commits the asset row that
 * references it, so {@link StorageService} keeps the blob pinned ({@link BlobStore#storePinned}) until that row is
 * committed, however long the write waits for a lock or a connection and whatever the grace period. The pin and
 * age checks, a last reference query and the delete happen under the blob's lock, atomically with respect to a write
 * of the same bytes ({@link BlobStore#deleteIfUnmodifiedSince}). The grace period is the safety margin for references
 * this process does not create through {@code StorageService.store} (an asset row saved again with an old blob's
 * digest, another process using the same directory against the rules): storing bytes that already exist refreshes
 * their modification time.
 *
 * <p>Runs on a schedule ({@code granary.storage.compaction.schedule}, daily by default; switched off with
 * {@code granary.storage.compaction.enabled=false}) and on demand through the API. At most one compaction per store
 * runs at a time; a second request is refused rather than queued.
 */
@Service
public class BlobCompactionService {

    private static final Logger log = LoggerFactory.getLogger(BlobCompactionService.class);
    /** Digests checked against the database per query. */
    static final int BATCH_SIZE = 500;

    /**
     * Outcome of one compaction.
     *
     * @param blobStore      the compacted store
     * @param startedAt      when it started
     * @param durationMillis how long it took
     * @param gracePeriod    blobs modified more recently than this were kept regardless of references (ISO-8601)
     * @param blobsScanned   blobs found in the store
     * @param blobsDeleted   unreferenced blobs older than the grace period that were deleted
     * @param bytesReclaimed size of the deleted blobs
     */
    public record Report(String blobStore, Instant startedAt, long durationMillis, String gracePeriod,
                         long blobsScanned, long blobsDeleted, long bytesReclaimed) {
    }

    private final BlobStoreRegistry blobStores;
    private final AssetDao assets;
    private final RepositoryDao repositories;
    private final GranaryProperties.Compaction config;
    private final Map<Object, ReentrantLock> running = new ConcurrentHashMap<>();

    public BlobCompactionService(BlobStoreRegistry blobStores, AssetDao assets, RepositoryDao repositories,
                                 GranaryProperties properties) {
        this.blobStores = blobStores;
        this.assets = assets;
        this.repositories = repositories;
        this.config = properties.getStorage().getCompaction();
    }

    /**
     * Compacts the named store now.
     *
     * @throws GranaryException 404 for an unknown store, 409 while the store is already being compacted, 400 for a
     *                          store type that cannot be compacted
     */
    public Report compact(String storeName) {
        BlobStore store = blobStores.find(storeName).orElseThrow(() ->
                GranaryException.notFound("blobStore.notFound", "Blob store '" + storeName + "' does not exist"));
        if (!store.supportsCompaction()) {
            throw GranaryException.badRequest("blobStore.compaction.unsupported",
                    "Blob store '" + storeName + "' cannot be compacted");
        }
        ReentrantLock lock = runningLock(store);
        if (!lock.tryLock()) {
            throw GranaryException.conflict("blobStore.compaction.running",
                    "Blob store '" + storeName + "' is already being compacted");
        }
        try {
            return run(store);
        } finally {
            lock.unlock();
        }
    }

    /** The lock a compaction of the named store holds while it runs. */
    ReentrantLock runningLock(String storeName) {
        return runningLock(blobStores.require(storeName));
    }

    /**
     * One lock per directory, shared by every store configured at it: the map never holds more entries than there
     * are stores.
     */
    private ReentrantLock runningLock(BlobStore store) {
        return running.computeIfAbsent(identity(store), n -> new ReentrantLock());
    }

    /** The scheduled run over every store, unless switched off. */
    @Scheduled(cron = "${granary.storage.compaction.schedule:0 30 3 * * *}")
    public void compactAllOnSchedule() {
        if (!config.isEnabled()) {
            return;
        }
        compactAll();
    }

    /**
     * Compacts every store that can be compacted, each directory once: stores configured at the same directory are
     * one store to compaction. A store that is already being compacted or fails is skipped and logged.
     */
    List<Report> compactAll() {
        List<Report> reports = new ArrayList<>();
        Set<Object> done = new HashSet<>();
        for (BlobStore store : blobStores.all()) {
            if (!store.supportsCompaction() || !done.add(identity(store))) {
                continue;
            }
            try {
                reports.add(compact(store.name()));
            } catch (GranaryException e) {
                log.info("scheduled compaction of blob store {} skipped: {}", store.name(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("scheduled compaction of blob store {} failed", store.name(), e);
            }
        }
        return reports;
    }

    private Report run(BlobStore store) {
        Instant startedAt = Instant.now();
        Duration grace = config.getGracePeriod();
        Instant cutoff = startedAt.minus(grace);
        Set<String> scope = storesSharingLocationWith(store);
        long scanned = 0;
        long deleted = 0;
        long reclaimed = 0;
        try (Stream<BlobStore.BlobInfo> blobs = store.blobs()) {
            Iterator<BlobStore.BlobInfo> it = blobs.iterator();
            List<BlobStore.BlobInfo> candidates = new ArrayList<>(BATCH_SIZE);
            while (it.hasNext()) {
                BlobStore.BlobInfo blob = it.next();
                scanned++;
                if (blob.lastModified().isBefore(cutoff)) {
                    candidates.add(blob);
                }
                if (candidates.size() == BATCH_SIZE) {
                    long[] result = deleteUnreferenced(store, scope, candidates, cutoff);
                    deleted += result[0];
                    reclaimed += result[1];
                    candidates.clear();
                }
            }
            if (!candidates.isEmpty()) {
                long[] result = deleteUnreferenced(store, scope, candidates, cutoff);
                deleted += result[0];
                reclaimed += result[1];
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not list the blobs of " + store.name(), e);
        }
        long millis = Duration.between(startedAt, Instant.now()).toMillis();
        Report report = new Report(store.name(), startedAt, millis, grace.toString(), scanned, deleted, reclaimed);
        log.info("compacted blob store {}: {} blobs scanned, {} deleted, {} bytes reclaimed in {} ms",
                store.name(), scanned, deleted, reclaimed, millis);
        return report;
    }

    /** Returns {deleted, bytes}. */
    private long[] deleteUnreferenced(BlobStore store, Set<String> scope, List<BlobStore.BlobInfo> candidates,
                                      Instant cutoff) {
        List<String> digests = candidates.stream().map(BlobStore.BlobInfo::sha256).toList();
        Set<String> referenced = new HashSet<>(assets.findReferencedBlobs(digests, scope));
        long deleted = 0;
        long bytes = 0;
        for (BlobStore.BlobInfo blob : candidates) {
            if (referenced.contains(blob.sha256())) {
                continue;
            }
            try {
                // asked again under the blob's lock, after the pin and age checks: this sees every reference committed
                // since the batch query, including one whose write re-used these bytes and has just released its pin
                if (store.deleteIfUnmodifiedSince(blob.sha256(), cutoff,
                        () -> !assets.existsByBlobSha256InBlobStores(blob.sha256(), scope))) {
                    deleted++;
                    bytes += blob.size();
                }
            } catch (IOException e) {
                log.warn("could not delete blob {} from {}: {}", blob.sha256(), store.name(), e.getMessage());
            }
        }
        return new long[] {deleted, bytes};
    }

    /**
     * The blob store names whose repositories' references keep a blob of {@code store}: every configured store that
     * keeps its files where {@code store} does (normally just its own name), and every store name a repository uses
     * that is not configured at all. The files of such a repository (its store renamed or removed from the
     * configuration) are still somewhere on disk, maybe in this directory, and must survive until the configuration
     * is fixed.
     */
    private Set<String> storesSharingLocationWith(BlobStore store) {
        Set<String> names = new LinkedHashSet<>();
        names.add(store.name());
        Object identity = identity(store);
        for (BlobStore other : blobStores.all()) {
            if (identity.equals(identity(other))) {
                names.add(other.name());
            }
        }
        for (String used : repositories.findBlobStoreNames()) {
            if (used != null && blobStores.find(used).isEmpty() && names.add(used)) {
                log.warn("repositories use blob store '{}', which is not configured; compaction of {} keeps every blob "
                        + "they reference", used, store.name());
            }
        }
        return names;
    }

    /**
     * What makes two stores the same to compaction: the directory of a file store, however its path is spelled
     * (through a symbolic link, a bind mount), otherwise the store's name.
     */
    private static Object identity(BlobStore store) {
        return store instanceof FileBlobStore file ? file.location() : store.name();
    }
}
