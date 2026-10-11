package dev.granary.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.CoreServiceTest;
import dev.granary.core.config.GranaryProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionTemplate;

class StorageLocksTest extends CoreServiceTest {

    @Autowired
    StorageLocks locks;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    GranaryProperties properties;

    Duration originalTimeout;
    final StorageLocks.AssetKey key = new StorageLocks.AssetKey(UUID.randomUUID(), "a/b");

    @BeforeEach
    void shortTimeout() {
        originalTimeout = properties.getStorage().getWriteLockTimeout();
        properties.getStorage().setWriteLockTimeout(Duration.ofMillis(200));
    }

    @AfterEach
    void restoreTimeout() {
        properties.getStorage().setWriteLockTimeout(originalTimeout);
    }

    @Test
    void aKeyTakenInATransactionIsHeldUntilItCommits() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            locks.withKey(key, () -> "work done");
            // the work is over, but the transaction is still open: the key must stay taken
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> CompletableFuture.supplyAsync(() -> locks.withKey(key, () -> "second")).get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CannotAcquireLockException.class);

        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        assertThat(CompletableFuture.supplyAsync(() -> locks.withKey(key, () -> "third")).get()).isEqualTo("third");
        assertThat(locks.heldKeys()).isZero();
    }

    @Test
    void aRollbackReleasesTheKeyToo() throws Exception {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            locks.withKey(key, () -> null);
            throw new IllegalStateException("roll back");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(locks.heldKeys()).isZero();
        assertThat(CompletableFuture.supplyAsync(() -> locks.withKey(key, () -> "next")).get()).isEqualTo("next");
    }

    @Test
    void outsideATransactionTheKeyIsHeldOnlyForTheWork() {
        assertThat(locks.withKey(key, () -> locks.heldKeys())).isEqualTo(1);
        assertThat(locks.heldKeys()).isZero();
    }

    @Test
    void oneTransactionMayTakeTheSameKeyTwice() {
        String result = transactions.execute(status -> locks.withKey(key, () -> locks.withKey(key, () -> "nested")));

        assertThat(result).isEqualTo("nested");
        assertThat(locks.heldKeys()).isZero();
    }

    @Test
    void anInterruptedWriterFailsWithoutHoldingTheKey() throws Exception {
        properties.getStorage().setWriteLockTimeout(Duration.ofMinutes(1));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            locks.withKey(key, () -> null);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        Thread writer = new Thread(() -> {
            try {
                locks.withKey(key, () -> null);
                failure.complete(null);
            } catch (RuntimeException e) {
                failure.complete(e);
            }
        });
        writer.start();
        Thread.sleep(100);
        writer.interrupt();

        assertThat(failure.get(5, TimeUnit.SECONDS)).isInstanceOf(CannotAcquireLockException.class);
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        assertThat(locks.heldKeys()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
