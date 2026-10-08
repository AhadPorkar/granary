package dev.granary.core.proxy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Remembers upstream 404s for a while so a build that asks for a non-existent artifact a hundred times
 * (Maven does this for every missing {@code -sources.jar}) costs one upstream round trip, not a hundred.
 */
@Component
public class NegativeCache {

    private final Clock clock;
    private final Map<String, Instant> entries = new ConcurrentHashMap<>();

    @Autowired
    public NegativeCache() {
        this(Clock.systemUTC());
    }

    public NegativeCache(Clock clock) {
        this.clock = clock;
    }

    public boolean contains(UUID repositoryId, String path) {
        Instant expiry = entries.get(key(repositoryId, path));
        if (expiry == null) {
            return false;
        }
        if (expiry.isBefore(clock.instant())) {
            entries.remove(key(repositoryId, path));
            return false;
        }
        return true;
    }

    public void put(UUID repositoryId, String path, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        entries.put(key(repositoryId, path), clock.instant().plus(ttl));
    }

    public void remove(UUID repositoryId, String path) {
        entries.remove(key(repositoryId, path));
    }

    public void clear(UUID repositoryId) {
        String prefix = repositoryId + "|";
        entries.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public int size() {
        return entries.size();
    }

    @Scheduled(fixedDelayString = "PT5M")
    public void evictExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }

    private static String key(UUID repositoryId, String path) {
        return repositoryId + "|" + path;
    }
}
