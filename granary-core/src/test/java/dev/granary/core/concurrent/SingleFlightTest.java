package dev.granary.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class SingleFlightTest {

    private final SingleFlight<String, Integer> flights = new SingleFlight<>();

    @RepeatedTest(5)
    void concurrentCallersShareOneLeader() throws Exception {
        AtomicInteger work = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allJoined = new CountDownLatch(16);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    SingleFlight<String, Integer>.Flight flight = flights.join("k");
                    allJoined.countDown();
                    if (flight.leader()) {
                        allJoined.await(10, TimeUnit.SECONDS); // keep the flight open until everybody arrived
                        flight.complete(work.incrementAndGet() * 42);
                    }
                    return flight.outcome().get(10, TimeUnit.SECONDS);
                }));
            }
            start.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(42);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(work.get()).isEqualTo(1);
        assertThat(flights.size()).isZero();
    }

    @Test
    void aCompletedFlightIsForgottenSoTheNextCallerLeadsAgain() {
        var first = flights.join("k");
        assertThat(first.leader()).isTrue();
        first.complete(1);

        var second = flights.join("k");
        assertThat(second.leader()).isTrue();
        second.complete(2);
        assertThat(flights.size()).isZero();
    }

    @Test
    void followersSeeTheLeadersFailureAndTheEntryIsRemoved() {
        var leader = flights.join("k");
        var follower = flights.join("k");
        assertThat(follower.leader()).isFalse();

        leader.fail(new IllegalStateException("boom"));

        assertThatThrownBy(() -> follower.outcome().get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(flights.size()).isZero();
    }

    @Test
    void waitingIsBoundedByTheCaller() {
        var leader = flights.join("k");
        var follower = flights.join("k");

        assertThatThrownBy(() -> follower.outcome().get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        leader.complete(7);
        assertThat(follower.outcome().join()).isEqualTo(7);
    }

    @Test
    void onlyTheLeaderEndsAFlight() {
        flights.join("k");
        var follower = flights.join("k");

        assertThatThrownBy(() -> follower.complete(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> follower.fail(new RuntimeException())).isInstanceOf(IllegalStateException.class);
        assertThat(flights.size()).isEqualTo(1);
    }

    @Test
    void keysAreIndependent() {
        assertThat(flights.join("a").leader()).isTrue();
        assertThat(flights.join("b").leader()).isTrue();
        assertThat(flights.size()).isEqualTo(2);
    }
}
