package dev.granary.core.storage;

import dev.granary.core.concurrent.KeyedLocks;
import dev.granary.core.config.GranaryProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Serialises writers of the same asset path or the same component inside this JVM, so the find-or-create in
 * {@link StorageService} cannot insert the same row twice.
 *
 * <p>A key taken inside a transaction stays locked until that transaction has committed or rolled back: the row a
 * writer inserted is only visible to the next writer after the commit, so releasing any earlier would let the next
 * writer miss it and insert a duplicate. Acquisition waits at most {@code granary.storage.write-lock-timeout}; a
 * writer that times out (only possible when one transaction writes several keys in an order that conflicts with
 * another one, or when a transaction stays open that long) fails with {@link CannotAcquireLockException}, which
 * the server reports as {@code 409 storage.conflict} so the client retries.
 *
 * <p>Granary runs as a single node: the locks coordinate the threads of one JVM. The database unique constraints
 * stay in place as the last line of defence.
 */
@Component
public class StorageLocks {

    /** Lock key of one asset path. */
    public record AssetKey(UUID repositoryId, String path) {
    }

    /** Lock key of one component. */
    public record ComponentKey(UUID repositoryId, String namespace, String name, String version) {
    }

    private final KeyedLocks<Object> locks = new KeyedLocks<>();
    private final GranaryProperties.Storage config;

    public StorageLocks(GranaryProperties properties) {
        this.config = properties.getStorage();
    }

    /**
     * Locks {@code key} until the current transaction completes; outside a transaction, only for {@code work}.
     */
    public <T> T withKey(Object key, Supplier<T> work) {
        KeyedLocks<Object>.Lease lease = acquire(key);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        lease.close();
                    }
                });
            } catch (RuntimeException e) {
                lease.close();
                throw e;
            }
            return work.get();
        }
        try {
            return work.get();
        } finally {
            lease.close();
        }
    }

    /** Number of keys held or waited for right now (for tests and diagnostics). */
    public int heldKeys() {
        return locks.size();
    }

    private KeyedLocks<Object>.Lease acquire(Object key) {
        Duration timeout = config.getWriteLockTimeout();
        try {
            return locks.tryAcquire(key, timeout).orElseThrow(() -> new CannotAcquireLockException(
                    "Timed out after " + timeout + " waiting for a concurrent write of " + key));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CannotAcquireLockException("Interrupted while waiting for a concurrent write of " + key, e);
        }
    }
}
