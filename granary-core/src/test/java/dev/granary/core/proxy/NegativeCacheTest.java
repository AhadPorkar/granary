package dev.granary.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NegativeCacheTest {

    private final UpstreamHealthTest.MutableClock clock = new UpstreamHealthTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final NegativeCache cache = new NegativeCache(clock);
    private final UUID repo = UUID.randomUUID();

    @Test
    void remembersAMissUntilItExpires() {
        cache.put(repo, "a/b.jar", Duration.ofMinutes(10));

        assertThat(cache.contains(repo, "a/b.jar")).isTrue();
        clock.advance(Duration.ofMinutes(11));
        assertThat(cache.contains(repo, "a/b.jar")).isFalse();
    }

    @Test
    void isScopedPerRepository() {
        cache.put(repo, "a/b.jar", Duration.ofMinutes(10));
        assertThat(cache.contains(UUID.randomUUID(), "a/b.jar")).isFalse();
    }

    @Test
    void zeroTtlDisablesNegativeCaching() {
        cache.put(repo, "a/b.jar", Duration.ZERO);
        cache.put(repo, "c.jar", null);
        assertThat(cache.contains(repo, "a/b.jar")).isFalse();
        assertThat(cache.size()).isZero();
    }

    @Test
    void removeAndClearForgetEntries() {
        cache.put(repo, "a", Duration.ofMinutes(1));
        cache.put(repo, "b", Duration.ofMinutes(1));
        cache.remove(repo, "a");
        assertThat(cache.contains(repo, "a")).isFalse();
        assertThat(cache.contains(repo, "b")).isTrue();
        cache.clear(repo);
        assertThat(cache.contains(repo, "b")).isFalse();
    }

    @Test
    void evictionDropsExpiredEntriesOnly() {
        cache.put(repo, "old", Duration.ofMinutes(1));
        cache.put(repo, "new", Duration.ofMinutes(30));
        clock.advance(Duration.ofMinutes(5));
        cache.evictExpired();
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.contains(repo, "new")).isTrue();
    }
}
