package dev.granary.core.concurrent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coalesces concurrent work on the same key: the first caller becomes the leader and does the work, everybody who
 * arrives while it is in flight joins and waits for the leader's outcome. An entry exists only while its flight is
 * in progress; the leader removes it when it {@link Flight#complete completes} or {@link Flight#fail fails}.
 *
 * <p>Waiting is up to the caller ({@code flight.outcome().get(timeout, unit)}), so it is always bounded and
 * interruptible. A leader must complete or fail its flight exactly once, also when it throws.
 *
 * @param <K> key type
 * @param <V> outcome type
 */
public final class SingleFlight<K, V> {

    /** A caller's ticket: the leader completes it, a follower waits for {@link #outcome()}. */
    public final class Flight {
        private final K key;
        private final CompletableFuture<V> outcome;
        private final boolean leader;

        private Flight(K key, CompletableFuture<V> outcome, boolean leader) {
            this.key = key;
            this.outcome = outcome;
            this.leader = leader;
        }

        public boolean leader() {
            return leader;
        }

        public CompletableFuture<V> outcome() {
            return outcome;
        }

        /** Leader only: ends the flight with {@code value}. New callers start a new flight from now on. */
        public void complete(V value) {
            requireLeader();
            inFlight.remove(key, outcome);
            outcome.complete(value);
        }

        /** Leader only: ends the flight with a failure that every follower sees. */
        public void fail(Throwable failure) {
            requireLeader();
            inFlight.remove(key, outcome);
            outcome.completeExceptionally(failure);
        }

        private void requireLeader() {
            if (!leader) {
                throw new IllegalStateException("only the leader of a flight completes it");
            }
        }
    }

    private final Map<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    /** Joins the flight for {@code key}, or starts one with the caller as leader when none is in progress. */
    public Flight join(K key) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
        return existing == null ? new Flight(key, mine, true) : new Flight(key, existing, false);
    }

    /** Number of flights in progress. */
    public int size() {
        return inFlight.size();
    }
}
