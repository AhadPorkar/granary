package dev.granary.core.concurrent;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One reentrant lock per key, without a lock table that grows forever: an entry exists only while somebody holds
 * or waits for its key, and it is removed when the last of them lets go. Every key has its own lock (no striping),
 * so two unrelated keys never block each other through a hash collision.
 *
 * <p>Acquisition is always bounded ({@link #tryAcquire}): a caller that cannot get a key within its time-out gives
 * up instead of waiting forever, which turns a potential deadlock between callers that take several keys in
 * different orders into a failed request. A {@link Lease} must be released by the thread that acquired it.
 *
 * @param <K> key type; needs proper {@code equals}/{@code hashCode} (records are ideal)
 */
public final class KeyedLocks<K> {

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        /** Holders plus waiters; only read and written inside {@code ConcurrentHashMap.compute}. */
        int users;
    }

    /** A held key. Closing it releases the key once; further calls do nothing. */
    public final class Lease implements AutoCloseable {
        private final K key;
        private final Entry entry;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(K key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        public K key() {
            return key;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                try {
                    entry.lock.unlock();
                } finally {
                    leave(key, entry);
                }
            }
        }
    }

    private final Map<K, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Waits at most {@code timeout} for {@code key}.
     *
     * @return the lease, or empty when the key stayed taken for the whole time-out
     * @throws InterruptedException when the waiting thread is interrupted; the key is not held then
     */
    public Optional<Lease> tryAcquire(K key, Duration timeout) throws InterruptedException {
        Entry entry = enter(key);
        boolean locked = false;
        try {
            locked = entry.lock.tryLock(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS);
        } finally {
            if (!locked) {
                leave(key, entry);
            }
        }
        return locked ? Optional.of(new Lease(key, entry)) : Optional.empty();
    }

    /** Number of keys currently held or waited for. */
    public int size() {
        return entries.size();
    }

    private Entry enter(K key) {
        return entries.compute(key, (k, existing) -> {
            Entry e = existing != null ? existing : new Entry();
            e.users++;
            return e;
        });
    }

    private void leave(K key, Entry entry) {
        entries.computeIfPresent(key, (k, existing) -> {
            if (existing != entry) {
                return existing;
            }
            existing.users--;
            return existing.users == 0 ? null : existing;
        });
    }
}
