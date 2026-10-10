package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KeyedLocksTest {

    @Test
    void oneKeyIsHeldByOneThreadAtATimeAndForgottenAfterwards() throws Exception {
        KeyedLocks locks = new KeyedLocks();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return locks.withLock("repo\u0000path\u0000a.txt", () -> {
                        maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                        Thread.onSpinWait();
                        inside.decrementAndGet();
                        return n;
                    });
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxInside.get()).isEqualTo(1);
        assertThat(locks.size()).isZero();
    }

    @Test
    void differentKeysDoNotBlockEachOtherAndTheLockIsReentrant() throws Exception {
        KeyedLocks locks = new KeyedLocks();
        String nested = locks.withLock("a", () -> locks.withLock("b", () -> locks.withLock("a", () -> "ok")));
        assertThat(nested).isEqualTo("ok");
        assertThat(locks.size()).isZero();
    }

    @Test
    void aFailingActionReleasesItsKey() {
        KeyedLocks locks = new KeyedLocks();
        assertThatThrownBy(() -> locks.withLock("a", () -> {
            throw new IOException("boom");
        })).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> locks.withLock("a", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(locks.size()).isZero();
    }
}
