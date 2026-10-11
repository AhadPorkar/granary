package dev.granary.core.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class KeyedLocksTest {

    private final KeyedLocks<String> locks = new KeyedLocks<>();

    @RepeatedTest(5)
    void holdersOfTheSameKeyNeverOverlapAndTheTableEmptiesAfterwards() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < 50; round++) {
                        try (KeyedLocks<String>.Lease lease = locks.tryAcquire("k", Duration.ofSeconds(10)).orElseThrow()) {
                            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                            Thread.onSpinWait();
                            inside.decrementAndGet();
                        }
                    }
                    done.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(done.get()).isEqualTo(16);
        assertThat(maxInside.get()).isEqualTo(1);
        assertThat(locks.size()).isZero();
    }

    @Test
    void differentKeysDoNotBlockEachOther() throws Exception {
        try (var a = locks.tryAcquire("a", Duration.ZERO).orElseThrow()) {
            ExecutorService other = Executors.newSingleThreadExecutor();
            try {
                Optional<KeyedLocks<String>.Lease> b = other.submit(() -> locks.tryAcquire("b", Duration.ZERO)).get();
                assertThat(b).isPresent();
                other.submit(() -> b.get().close()).get();
            } finally {
                other.shutdownNow();
            }
            assertThat(a.key()).isEqualTo("a");
        }
        assertThat(locks.size()).isZero();
    }

    @Test
    void timingOutLeavesNoEntryBehind() throws Exception {
        try (var held = locks.tryAcquire("k", Duration.ZERO).orElseThrow()) {
            ExecutorService other = Executors.newSingleThreadExecutor();
            try {
                long started = System.nanoTime();
                Optional<KeyedLocks<String>.Lease> lease = other.submit(() -> locks.tryAcquire("k", Duration.ofMillis(200))).get();
                assertThat(lease).isEmpty();
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(190));
            } finally {
                other.shutdownNow();
            }
            assertThat(locks.size()).isEqualTo(1);
        }
        assertThat(locks.size()).isZero();
    }

    @Test
    void anInterruptedWaiterGivesUpCleanly() throws Exception {
        try (var held = locks.tryAcquire("k", Duration.ZERO).orElseThrow()) {
            CountDownLatch waiting = new CountDownLatch(1);
            AtomicReference<Object> outcome = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                waiting.countDown();
                try {
                    outcome.set(locks.tryAcquire("k", Duration.ofMinutes(1)));
                } catch (InterruptedException e) {
                    outcome.set(e);
                }
            });
            waiter.start();
            waiting.await();
            Thread.sleep(100);
            waiter.interrupt();
            waiter.join(5_000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
            assertThat(locks.size()).isEqualTo(1);
        }
        assertThat(locks.size()).isZero();
    }

    @Test
    void locksAreReentrantForTheirThreadAndReleasedOncePerLease() throws Exception {
        var outer = locks.tryAcquire("k", Duration.ZERO).orElseThrow();
        var inner = locks.tryAcquire("k", Duration.ZERO).orElseThrow();
        inner.close();
        inner.close(); // a second close is a no-op
        assertThat(locks.size()).isEqualTo(1);

        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            assertThat(other.submit(() -> locks.tryAcquire("k", Duration.ZERO)).get()).isEmpty();
            outer.close();
            Optional<KeyedLocks<String>.Lease> now = other.submit(() -> locks.tryAcquire("k", Duration.ZERO)).get();
            assertThat(now).isPresent();
            other.submit(() -> now.get().close()).get();
        } finally {
            other.shutdownNow();
        }
        assertThat(locks.size()).isZero();
    }
}
