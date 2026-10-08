package dev.granary.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Repository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpstreamHealthTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private UpstreamHealth health;
    private Repository repo;

    @BeforeEach
    void setUp() {
        GranaryProperties properties = new GranaryProperties();
        properties.getProxy().setFailureThreshold(3);
        properties.getProxy().setCircuitOpenMin(Duration.ofSeconds(30));
        properties.getProxy().setCircuitOpenMax(Duration.ofMinutes(10));
        health = new UpstreamHealth(properties, clock);
        repo = new Repository();
        repo.setId(UUID.randomUUID());
        repo.setName("central");
    }

    @Test
    void unknownRepositoryIsReachable() {
        assertThat(health.allowRequest(repo)).isTrue();
        assertThat(health.status(repo).reachable()).isTrue();
        assertThat(health.status(repo).consecutiveFailures()).isZero();
    }

    @Test
    void opensTheCircuitAfterThresholdFailures() {
        health.recordFailure(repo, "timeout");
        health.recordFailure(repo, "timeout");
        assertThat(health.allowRequest(repo)).isTrue();

        health.recordFailure(repo, "timeout");

        assertThat(health.allowRequest(repo)).isFalse();
        UpstreamHealth.Status status = health.status(repo);
        assertThat(status.reachable()).isFalse();
        assertThat(status.consecutiveFailures()).isEqualTo(3);
        assertThat(status.lastError()).isEqualTo("timeout");
        assertThat(status.retryAt()).isEqualTo(clock.instant().plusSeconds(30));
    }

    @Test
    void letsExactlyOneProbeThroughAfterTheBackoff() {
        for (int i = 0; i < 3; i++) {
            health.recordFailure(repo, "down");
        }
        clock.advance(Duration.ofSeconds(31));

        assertThat(health.allowRequest(repo)).isTrue();   // the probe
        assertThat(health.allowRequest(repo)).isFalse();  // everyone else waits for the probe's outcome
    }

    @Test
    void successClosesTheCircuit() {
        for (int i = 0; i < 3; i++) {
            health.recordFailure(repo, "down");
        }
        clock.advance(Duration.ofSeconds(31));
        health.allowRequest(repo);
        health.recordSuccess(repo);

        assertThat(health.allowRequest(repo)).isTrue();
        assertThat(health.allowRequest(repo)).isTrue();
        assertThat(health.status(repo).reachable()).isTrue();
        assertThat(health.status(repo).consecutiveFailures()).isZero();
        assertThat(health.status(repo).lastSuccessAt()).isEqualTo(clock.instant());
    }

    @Test
    void backoffDoublesUpToTheMaximum() {
        for (int i = 0; i < 3; i++) {
            health.recordFailure(repo, "down");
        }
        assertThat(health.status(repo).retryAt()).isEqualTo(clock.instant().plusSeconds(30));
        health.recordFailure(repo, "down");
        assertThat(health.status(repo).retryAt()).isEqualTo(clock.instant().plusSeconds(60));
        health.recordFailure(repo, "down");
        assertThat(health.status(repo).retryAt()).isEqualTo(clock.instant().plusSeconds(120));
        for (int i = 0; i < 20; i++) {
            health.recordFailure(repo, "down");
        }
        assertThat(health.status(repo).retryAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
    }

    @Test
    void resetForgetsEverything() {
        for (int i = 0; i < 3; i++) {
            health.recordFailure(repo, "down");
        }
        health.reset(repo);
        assertThat(health.allowRequest(repo)).isTrue();
        assertThat(health.status(repo).consecutiveFailures()).isZero();
    }

    /** A clock the test can move forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
