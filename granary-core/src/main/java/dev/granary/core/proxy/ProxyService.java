package dev.granary.core.proxy;

import dev.granary.core.concurrent.SingleFlight;
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
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *
 * <p>Concurrent requests for the same file that all need the upstream are coalesced (single flight): the first one
 * fetches and caches it, the others wait for that and then serve what it cached, or its outcome when nothing could
 * be cached. A waiter gives up after {@code granary.proxy.single-flight-timeout} and fetches on its own.
 * Coordination is per JVM, which matches Granary's single-node deployment. A fetch inside a caller's transaction
 * does not take part: it could not publish what it cached before that transaction commits, and as a waiter it would
 * hold the transaction's connection, and maybe write keys the leader needs, for as long as it waits.
 */
@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    /** One upstream fetch in progress, identified by the repository and the local cache path. */
    private record FlightKey(UUID repositoryId, String path) {
    }

    private final StorageService storage;
    private final UpstreamClient upstream;
    private final UpstreamHealth health;
    private final NegativeCache negativeCache;
    private final SettingsService settings;
    private final GranaryProperties.Proxy defaults;
    private final SingleFlight<FlightKey, Optional<StoredContent>> flights = new SingleFlight<>();

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
        StorageService.ComponentCoordinates component = storableComponent(repo, cachePath, options.component());
        FetchOptions effective = component == options.component() ? options : options.withComponent(component);
        return linked(repo, lookup(repo, cachePath, upstreamUri, effective, extraHeaders), component);
    }

    private Optional<StoredContent> lookup(Repository repo, String path, URI upstreamUri, FetchOptions options,
                                           Map<String, String> extraHeaders) {
        Optional<StoredContent> cached = storage.find(repo, path);
        if (servable(cached, repo, options)) {
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
        return fetchOnce(repo, path, upstreamUri, options, cached, extraHeaders);
    }

    /**
     * The coordinates to link a cached file to, or {@code null}. Coordinates the components table cannot hold (an
     * upstream may name a package anything) are dropped: the file is still cached and served, just not listed.
     */
    private static StorageService.ComponentCoordinates storableComponent(Repository repo, String path,
                                                                         StorageService.ComponentCoordinates component) {
        if (component == null || StorageService.isStorable(component)) {
            return component;
        }
        log.debug("{} in {} is cached without a component: its coordinates {} cannot be stored", path, repo.getName(),
                component);
        return null;
    }

    /**
     * Links a file served from the cache to its component when it has none yet: copies cached before the proxy
     * recorded components (or by an older version of Granary) are linked the next time a client asks for them. A
     * failure to link never fails the download; the next request tries again.
     */
    private Optional<StoredContent> linked(Repository repo, Optional<StoredContent> content,
                                           StorageService.ComponentCoordinates component) {
        if (component == null || content.isEmpty() || content.get().asset().getComponentId() != null) {
            return content;
        }
        StoredContent found = content.get();
        try {
            return Optional.of(storage.linkComponent(repo, found.asset().getPath(), component)
                    .map(asset -> new StoredContent(asset, found.blobStore()))
                    .orElse(found));
        } catch (RuntimeException e) {
            log.debug("could not link {} in {} to component {}: {}", found.asset().getPath(), repo.getName(),
                    component, e.toString());
            return content;
        }
    }

    /** Number of upstream fetches currently coalescing concurrent requests (for tests and diagnostics). */
    public int inFlightFetches() {
        return flights.size();
    }

    /**
     * Makes sure only one of the concurrent requests for {@code path} talks to the upstream. The others wait for it
     * (bounded) and then read what it left in the cache.
     */
    private Optional<StoredContent> fetchOnce(Repository repo, String path, URI upstreamUri, FetchOptions options,
                                              Optional<StoredContent> cached, Map<String, String> extraHeaders) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // inside a caller's transaction: what this fetch caches is invisible to others until that commits, and a
            // second fetch of the same file in the same transaction would wait for its own flight
            return fetchFromUpstream(repo, path, upstreamUri, options, cached, extraHeaders);
        }
        FlightKey key = new FlightKey(repo.getId(), StorageService.normalise(path));
        Duration wait = defaults.getSingleFlightTimeout();
        for (int round = 0; round < 2; round++) {
            SingleFlight<FlightKey, Optional<StoredContent>>.Flight flight = flights.join(key);
            if (flight.leader()) {
                return lead(flight, repo, path, upstreamUri, options, extraHeaders);
            }
            try {
                flight.outcome().get(wait.toNanos(), TimeUnit.NANOSECONDS);
                // the leader's cache write is committed by now: serve it, or the leader's empty outcome
                return storage.find(repo, path);
            } catch (TimeoutException e) {
                log.info("waited {} for the concurrent upstream request for {} in {}; fetching it directly",
                        wait, path, repo.getName());
                break;
            } catch (ExecutionException e) {
                // the leader failed: try once more as (or behind) a new leader, then on our own
                log.debug("concurrent upstream request for {} in {} failed: {}", path, repo.getName(), e.getCause().toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return cached;
            }
        }
        Optional<StoredContent> current = storage.find(repo, path);
        if (servable(current, repo, options)) {
            return current;
        }
        return fetchFromUpstream(repo, path, upstreamUri, options, current, extraHeaders);
    }

    private Optional<StoredContent> lead(SingleFlight<FlightKey, Optional<StoredContent>>.Flight flight, Repository repo,
                                         String path, URI upstreamUri, FetchOptions options,
                                         Map<String, String> extraHeaders) {
        Optional<StoredContent> result;
        try {
            // a flight that ended just before this one began may have done the work already
            Optional<StoredContent> current = storage.find(repo, path);
            if (servable(current, repo, options)) {
                result = current;
            } else if (current.isEmpty() && negativeCache.contains(repo.getId(), path)) {
                result = Optional.empty();
            } else {
                result = fetchFromUpstream(repo, path, upstreamUri, options, current, extraHeaders);
            }
        } catch (RuntimeException | Error e) {
            endFlight(flight, null, e);
            throw e;
        }
        endFlight(flight, result, null);
        return result;
    }

    /**
     * Releases the waiters. A leader never runs inside a caller's transaction ({@link #fetchOnce}), so whatever it
     * cached is committed by now and visible to them.
     */
    private static void endFlight(SingleFlight<FlightKey, Optional<StoredContent>>.Flight flight,
                                  Optional<StoredContent> result, Throwable failure) {
        if (failure == null) {
            flight.complete(result);
        } else {
            flight.fail(failure);
        }
    }

    private boolean servable(Optional<StoredContent> cached, Repository repo, FetchOptions options) {
        return cached.isPresent() && (!options.metadata() || isFresh(cached.get().asset(), repo, options));
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
                    stored = storage.store(repo, path, body, contentType, Asset.Origin.CACHED, options.metadata(),
                            options.component());
                }
                // StorageService sanitises the header values: oversized validators are dropped, never truncated
                Asset asset = storage.recordUpstreamValidators(stored.asset(),
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
