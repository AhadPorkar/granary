package dev.granary.core.proxy;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Repository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A small circuit breaker per proxy repository. After {@code failureThreshold} consecutive failures the
 * upstream is considered down and is not contacted again until a back-off (doubling up to a maximum) has
 * elapsed; then exactly one probe request is let through. While the circuit is open, proxies serve
 * cached content immediately instead of making every client wait for a timeout.
 */
@Component
public class UpstreamHealth {

    public record Status(boolean reachable, int consecutiveFailures, String lastError,
                         Instant lastSuccessAt, Instant lastFailureAt, Instant retryAt) {
    }

    private static final class State {
        int failures;
        Instant lastSuccess;
        Instant lastFailure;
        Instant openUntil;
        String lastError;
        boolean probing;
    }

    private final GranaryProperties.Proxy config;
    private final Clock clock;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    @Autowired
    public UpstreamHealth(GranaryProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public UpstreamHealth(GranaryProperties properties, Clock clock) {
        this.config = properties.getProxy();
        this.clock = clock;
    }

    /** Whether a request to upstream should be attempted now. */
    public boolean allowRequest(Repository repo) {
        State s = states.computeIfAbsent(repo.getId(), id -> new State());
        synchronized (s) {
            if (s.openUntil == null) {
                return true;
            }
            Instant now = clock.instant();
            if (now.isBefore(s.openUntil)) {
                return false;
            }
            if (s.probing) {
                return false; // one probe at a time
            }
            s.probing = true;
            return true;
        }
    }

    public void recordSuccess(Repository repo) {
        State s = states.computeIfAbsent(repo.getId(), id -> new State());
        synchronized (s) {
            s.failures = 0;
            s.openUntil = null;
            s.probing = false;
            s.lastError = null;
            s.lastSuccess = clock.instant();
        }
    }

    public void recordFailure(Repository repo, String error) {
        State s = states.computeIfAbsent(repo.getId(), id -> new State());
        synchronized (s) {
            s.failures++;
            s.probing = false;
            s.lastError = error;
            s.lastFailure = clock.instant();
            if (s.failures >= config.getFailureThreshold()) {
                int exponent = Math.min(s.failures - config.getFailureThreshold(), 16);
                long backoffMs = config.getCircuitOpenMin().toMillis() * (1L << exponent);
                Duration backoff = Duration.ofMillis(Math.min(backoffMs, config.getCircuitOpenMax().toMillis()));
                s.openUntil = s.lastFailure.plus(backoff);
            }
        }
    }

    public Status status(Repository repo) {
        State s = states.get(repo.getId());
        if (s == null) {
            return new Status(true, 0, null, null, null, null);
        }
        synchronized (s) {
            boolean reachable = s.openUntil == null || !clock.instant().isBefore(s.openUntil);
            return new Status(reachable, s.failures, s.lastError, s.lastSuccess, s.lastFailure, s.openUntil);
        }
    }

    public void reset(Repository repo) {
        states.remove(repo.getId());
    }
}
