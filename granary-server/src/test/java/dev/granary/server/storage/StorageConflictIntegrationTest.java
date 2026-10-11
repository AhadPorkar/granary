package dev.granary.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageLocks;
import dev.granary.server.IntegrationTest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The last line of defence end to end: a write that cannot get its path because another transaction holds it for
 * longer than the write-lock time-out is answered with {@code 409 storage.conflict}, never a 500, and the same
 * request succeeds once the other transaction is done.
 */
class StorageConflictIntegrationTest extends IntegrationTest {

    @Autowired
    StorageLocks locks;

    @Autowired
    GranaryProperties properties;

    @Autowired
    TransactionTemplate transactions;

    Duration original;

    @BeforeEach
    void shortLockTimeout() {
        original = properties.getStorage().getWriteLockTimeout();
        properties.getStorage().setWriteLockTimeout(Duration.ofMillis(300));
    }

    @AfterEach
    void restore() {
        properties.getStorage().setWriteLockTimeout(original);
    }

    @Test
    void aWriteBlockedByAnotherTransactionIsAConflictNotAServerError() throws Exception {
        Repository repo = hosted("files", "raw");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> other = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            locks.withKey(new StorageLocks.AssetKey(repo.getId(), "docs/readme.txt"), () -> null);
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

        mvc.perform(put("/repository/files/docs/readme.txt").header("Authorization", adminBasic()).content("hello"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("storage.conflict"));

        release.countDown();
        other.get(5, TimeUnit.SECONDS);
        mvc.perform(put("/repository/files/docs/readme.txt").header("Authorization", adminBasic()).content("hello"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/repository/files/docs/readme.txt")).andExpect(status().isOk()).andExpect(content().string("hello"));
        assertThat(locks.heldKeys()).isZero();
    }
}
