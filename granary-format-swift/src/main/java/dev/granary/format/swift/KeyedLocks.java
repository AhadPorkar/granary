package dev.granary.format.swift;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A fixed set of striped locks. Publishing checks what is stored and then stores; holding the lock of the
 * release makes that one step, so two concurrent publishes of the same version cannot both pass the
 * redeploy check. Different keys may share a stripe, which only costs a little parallelism. Callers that nest
 * two locks take them from two separate instances, always in the same order, so stripes cannot deadlock.
 */
final class KeyedLocks {

    private final ReentrantLock[] locks;

    KeyedLocks(int stripes) {
        locks = new ReentrantLock[stripes];
        for (int i = 0; i < stripes; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    ReentrantLock lock(UUID repositoryId, String key) {
        int hash = 31 * String.valueOf(repositoryId).hashCode() + key.hashCode();
        return locks[Math.floorMod(hash, locks.length)];
    }
}
