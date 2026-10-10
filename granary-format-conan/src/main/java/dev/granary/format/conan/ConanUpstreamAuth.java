package dev.granary.format.conan;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.storage.Checksums;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The token flow of private Conan upstreams ({@code conan_server}, Artifactory): username and password are
 * exchanged once at {@code v2/users/authenticate} for a bearer token, which every later request carries.
 *
 * <p>The proxy service authorises upstream requests from the repository definition it is given, so this class
 * hands it a copy of the definition that carries the exchanged token instead of the username and password.
 * Tokens are cached in memory for {@link #TOKEN_TTL}; a failed exchange falls back to Basic authentication (which
 * Artifactory accepts on every endpoint) and is retried after {@link #FAILURE_TTL}. ConanCenter is anonymous and
 * never goes through here; neither does a repository whose setting {@code conan.upstreamAuth} is {@code basic}.
 */
public class ConanUpstreamAuth {

    public static final String SETTING = "conan.upstreamAuth";
    public static final String TOKEN = "token";
    public static final String BASIC = "basic";

    static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    static final Duration FAILURE_TTL = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(ConanUpstreamAuth.class);

    private record Cached(String token, String credentials, Instant obtainedAt) {
    }

    private final UpstreamClient upstream;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public ConanUpstreamAuth(UpstreamClient upstream) {
        this(upstream, Clock.systemUTC());
    }

    ConanUpstreamAuth(UpstreamClient upstream, Clock clock) {
        this.upstream = upstream;
        this.clock = clock;
    }

    /** The repository to hand to the proxy service: {@code repo} itself, or a copy that authenticates with a token. */
    public Repository authorised(Repository repo) {
        if (!needsToken(repo)) {
            return repo;
        }
        String credentials = fingerprint(repo);
        Cached cached = cache.get(repo.getId());
        Instant now = clock.instant();
        if (cached != null && cached.credentials().equals(credentials)) {
            Duration ttl = cached.token() == null ? FAILURE_TTL : TOKEN_TTL;
            if (cached.obtainedAt().plus(ttl).isAfter(now)) {
                return cached.token() == null ? repo : withToken(repo, cached.token());
            }
        }
        String token = exchange(repo);
        cache.put(repo.getId(), new Cached(token, credentials, now));
        return token == null ? repo : withToken(repo, token);
    }

    /** Drops a cached token, for example after the upstream rejected it. */
    public void forget(Repository repo) {
        if (repo.getId() != null) {
            cache.remove(repo.getId());
        }
    }

    /**
     * The upstream answered 401 at {@code rejectedAt}: a token obtained before then has expired or been revoked
     * (a restarted {@code conan_server} with a new secret, a shorter-lived Artifactory token), so the next request
     * logs in again instead of failing until {@link #TOKEN_TTL} runs out. A token obtained afterwards is kept.
     */
    public void rejected(Repository repo, Instant rejectedAt) {
        if (repo.getId() == null || rejectedAt == null) {
            return;
        }
        cache.computeIfPresent(repo.getId(),
                (id, cached) -> cached.token() != null && !cached.obtainedAt().isAfter(rejectedAt) ? null : cached);
    }

    static boolean needsToken(Repository repo) {
        if (!repo.isProxy() || repo.getId() == null) {
            return false;
        }
        if (repo.getRemoteToken() != null && !repo.getRemoteToken().isBlank()) {
            return false;
        }
        if (repo.getRemoteUsername() == null || repo.getRemoteUsername().isBlank()
                || repo.getRemotePassword() == null || repo.getRemotePassword().isEmpty()) {
            return false;
        }
        return !BASIC.equalsIgnoreCase(repo.setting(SETTING, TOKEN));
    }

    private String exchange(Repository repo) {
        URI uri = upstream.upstreamUri(repo, "v2/users/authenticate");
        try (UpstreamClient.Response response = upstream.getAbsolute(repo, uri, Map.of(), Map.of("Accept", "text/plain"))) {
            if (response.status() != 200) {
                log.warn("token exchange with the upstream of {} answered {}; using Basic authentication",
                        repo.getName(), response.status());
                return null;
            }
            String token = new String(response.body().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (token.isEmpty() || token.length() > 4000 || token.chars().anyMatch(Character::isWhitespace)) {
                log.warn("token exchange with the upstream of {} returned no usable token; using Basic authentication",
                        repo.getName());
                return null;
            }
            return token;
        } catch (IOException e) {
            log.warn("token exchange with the upstream of {} failed ({}); using Basic authentication",
                    repo.getName(), e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String fingerprint(Repository repo) {
        String raw = repo.getRemoteUrl() + "\n" + repo.getRemoteUsername() + "\n" + repo.getRemotePassword();
        return Checksums.hex("SHA-256", raw.getBytes(StandardCharsets.UTF_8));
    }

    /** A copy of the definition the proxy service reads, authenticating with {@code token} instead of Basic. */
    static Repository withToken(Repository repo, String token) {
        Repository copy = new Repository();
        copy.setId(repo.getId());
        copy.setName(repo.getName());
        copy.setFormat(repo.getFormat());
        copy.setType(repo.getType());
        copy.setOnline(repo.isOnline());
        copy.setBlobStore(repo.getBlobStore());
        copy.setDescription(repo.getDescription());
        copy.setAllowRedeploy(repo.isAllowRedeploy());
        copy.setVersionPolicy(repo.getVersionPolicy());
        copy.setRemoteUrl(repo.getRemoteUrl());
        copy.setRemoteToken(token);
        copy.setConnectTimeoutSeconds(repo.getConnectTimeoutSeconds());
        copy.setReadTimeoutSeconds(repo.getReadTimeoutSeconds());
        copy.setMetadataMaxAgeMinutes(repo.getMetadataMaxAgeMinutes());
        copy.setNegativeCacheMinutes(repo.getNegativeCacheMinutes());
        copy.setOfflineMode(repo.isOfflineMode());
        copy.setMembers(repo.getMembers());
        copy.setFormatSettings(repo.getFormatSettings());
        return copy;
    }
}
