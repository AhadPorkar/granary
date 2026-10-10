package dev.granary.format.raw;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Mutual exclusion per key (a repository path, a component, an index document) without a lock table that grows
 * forever: a key's lock exists only while somebody holds or waits for it. Used so that two concurrent uploads of
 * the same path cannot both pass the "already exists" check, two uploads that share a component cannot race to
 * create it, and index regenerations of one repository never overwrite each other with a stale view.
 */
final class KeyedLocks {

    /** Work done while holding a key. */
    @FunctionalInterface
    interface Action<T> {
        T run() throws IOException;
    }

    private static final class Holder {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    private final Map<String, Holder> holders = new ConcurrentHashMap<>();

    <T> T withLock(String key, Action<T> action) throws IOException {
        Holder holder = holders.compute(key, (k, existing) -> {
            Holder h = existing != null ? existing : new Holder();
            h.users++;
            return h;
        });
        holder.lock.lock();
        try {
            return action.run();
        } finally {
            holder.lock.unlock();
            holders.compute(key, (k, existing) -> {
                existing.users--;
                return existing.users == 0 ? null : existing;
            });
        }
    }

    /** Number of keys currently held or waited for (for tests). */
    int size() {
        return holders.size();
    }
}
