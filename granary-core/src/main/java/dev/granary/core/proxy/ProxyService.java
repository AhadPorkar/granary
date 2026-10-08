package dev.granary.core.proxy;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.settings.SettingsService;
import dev.granary.core.storage.ContentTypes;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The offline-first proxy cache.
 *
 * <p>Decision order for every request:
 * <ol>
 *   <li>Immutable artifacts already in the cache are served without touching the network.</li>
 *   <li>Fresh metadata (younger than {@code maxAge}) is served from the cache too.</li>
 *   <li>If the repository, the whole server or the circuit breaker says "offline", whatever is cached is served
 *       (even stale metadata); nothing cached means 404.</li>
 *   <li>Otherwise upstream is asked, conditionally when a cached copy exists. A 200 replaces the cache, a 304 renews
 *       it, a 404 is remembered in the negative cache, and any failure falls back to the cached copy.</li>
 * </ol>
 * The result is that once an artifact has been fetched, an internet outage never breaks a build.
 */
@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    private final StorageService storage;
    private final UpstreamClient upstream;
    private final UpstreamHealth health;
    private final NegativeCache negativeCache;
    private final SettingsService settings;
    private final GranaryProperties.Proxy defaults;

    public ProxyService(StorageService storage, UpstreamClient upstream, UpstreamHealth health,
                        NegativeCache negativeCache, SettingsService settings, GranaryProperties properties) {
        this.storage = storage;
        this.upstream = upstream;
        this.health = health;
        this.negativeCache = negativeCache;
        this.settings = settings;
        this.defaults = properties.getProxy();
    }

    public Optional<StoredContent> fetch(Repository repo, String path, FetchOptions options) {
        return fetch(repo, path, options, Map.of());
    }

    /**
     * @param extraHeaders headers to add to the upstream request (formats use this for Accept negotiation)
     */
    public Optional<StoredContent> fetch(Repository repo, String path, FetchOptions options,
                                         Map<String, String> extraHeaders) {
        return fetch(repo, path, upstream.upstreamUri(repo, path), options, extraHeaders);
    }

    /**
     * Like {@link #fetch(Repository, String, FetchOptions, Map)} but with an explicit upstream URL. Formats whose
     * upstream hands out absolute resource URLs (NuGet service indexes) cache those documents under a local
     * {@code cachePath} of their choosing.
     */
    public Optional<StoredContent> fetch(Repository repo, String cachePath, URI upstreamUri, FetchOptions options,
                                         Map<String, String> extraHeaders) {
        String path = cachePath;
        Optional<StoredContent> cached = storage.find(repo, path);
        if (cached.isPresent() && !options.metadata()) {
            return cached;
        }
        if (cached.isPresent() && isFresh(cached.get().asset(), repo, options)) {
            return cached;
        }
        if (isOffline(repo)) {
            return cached;
        }
        if (cached.isEmpty() && negativeCache.contains(repo.getId(), path)) {
            return Optional.empty();
        }
        if (!health.allowRequest(repo)) {
            return cached;
        }
        return fetchFromUpstream(repo, path, upstreamUri, options, cached, extraHeaders);
    }

    public boolean isOffline(Repository repo) {
        return repo.isOfflineMode() || !repo.isOnline() || settings.isGlobalOffline();
    }

    private Optional<StoredContent> fetchFromUpstream(Repository repo, String path, URI upstreamUri,
                                                      FetchOptions options, Optional<StoredContent> cached,
                                                      Map<String, String> extraHeaders) {
        Map<String, String> conditional = new HashMap<>();
        cached.ifPresent(c -> {
            if (c.asset().getUpstreamEtag() != null) {
                conditional.put("If-None-Match", c.asset().getUpstreamEtag());
            }
            if (c.asset().getUpstreamLastModified() != null) {
                conditional.put("If-Modified-Since", c.asset().getUpstreamLastModified());
            }
        });
        try (UpstreamClient.Response response = upstream.getAbsolute(repo, upstreamUri, conditional, extraHeaders)) {
            int status = response.status();
            if (status == 200) {
                health.recordSuccess(repo);
                negativeCache.remove(repo.getId(), path);
                String contentType = response.header("Content-Type")
                        .map(ProxyService::stripParameters)
                        .filter(ct -> !ct.isBlank())
                        .orElseGet(() -> ContentTypes.guess(path));
                StoredContent stored;
                try (InputStream body = response.body()) {
                    stored = storage.store(repo, path, body, contentType, Asset.Origin.CACHED, options.metadata());
                }
                Asset asset = storage.touchVerified(stored.asset(),
                        response.header("ETag").orElse(null),
                        response.header("Last-Modified").orElse(null));
                return Optional.of(new StoredContent(asset, stored.blobStore()));
            }
            if (status == 304 && cached.isPresent()) {
                health.recordSuccess(repo);
                Asset asset = storage.touchVerified(cached.get().asset(), null, null);
                return Optional.of(new StoredContent(asset, cached.get().blobStore()));
            }
            if (status == 404 || status == 410) {
                health.recordSuccess(repo); // upstream answered; it just does not have the file
                if (cached.isEmpty()) {
                    negativeCache.put(repo.getId(), path, negativeTtl(repo));
                }
                return cached;
            }
            health.recordFailure(repo, "HTTP " + status + " for " + path);
            log.warn("upstream of {} answered {} for {}; serving cache={}", repo.getName(), status, path, cached.isPresent());
            return cached;
        } catch (IOException e) {
            health.recordFailure(repo, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("upstream of {} unreachable for {} ({}); serving cache={}", repo.getName(), path,
                    e.getMessage(), cached.isPresent());
            return cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cached;
        }
    }

    private boolean isFresh(Asset asset, Repository repo, FetchOptions options) {
        Duration maxAge = options.maxAge() != null ? options.maxAge() : metadataMaxAge(repo);
        return asset.getLastVerifiedAt() != null && asset.getLastVerifiedAt().plus(maxAge).isAfter(Instant.now());
    }

    public Duration metadataMaxAge(Repository repo) {
        return repo.getMetadataMaxAgeMinutes() != null
                ? Duration.ofMinutes(repo.getMetadataMaxAgeMinutes())
                : defaults.getMetadataMaxAge();
    }

    public Duration negativeTtl(Repository repo) {
        return repo.getNegativeCacheMinutes() != null
                ? Duration.ofMinutes(repo.getNegativeCacheMinutes())
                : defaults.getNegativeCacheTtl();
    }

    public UpstreamHealth.Status status(Repository repo) {
        return health.status(repo);
    }

    /** Drops the remembered 404s and circuit-breaker state of a repository, e.g. after the network came back. */
    public void invalidate(Repository repo) {
        negativeCache.clear(repo.getId());
        health.reset(repo);
    }

    private static String stripParameters(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    }
}
