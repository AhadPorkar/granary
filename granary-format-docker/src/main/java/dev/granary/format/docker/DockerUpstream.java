package dev.granary.format.docker;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamCredentials;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Talks to the upstream registry of a proxy repository. Registries protect even public images with a token
 * exchange: the first request answers 401 with {@code WWW-Authenticate: Bearer realm=...,service=...}, the client
 * fetches a short-lived token from the realm (anonymously, or with the repository's remote credentials) and retries
 * with {@code Authorization: Bearer}. The challenge is probed once per repository and the tokens are cached per
 * image until shortly before they expire, and only for the remote URL, realm and credentials they were issued for.
 *
 * <p>The realm is usually on another host than the registry (auth.docker.io for registry-1.docker.io), so the
 * token request is the one place where the repository's credentials deliberately leave the remote URL's origin:
 * the registry itself names that host as the place to authenticate (a challenge answered by a host the registry's
 * {@code /v2/} redirected to is not the registry's and is ignored). Everything else follows the rule of
 * {@link UpstreamClient}: the probe goes without credentials, and a request with a bearer token carries that token
 * instead of the credentials, only on the registry's origin (blob redirects to a CDN or object store get neither).
 */
@Component
public class DockerUpstream {

    public static final String LIBRARY_PREFIX_SETTING = "docker.libraryPrefix";

    static final Duration CHALLENGE_TTL = Duration.ofHours(1);
    static final Duration CHALLENGE_RETRY = Duration.ofMinutes(1);
    static final Duration TOKEN_MARGIN = Duration.ofSeconds(30);
    static final Duration TOKEN_DEFAULT_TTL = Duration.ofSeconds(60);
    static final Duration TOKEN_MIN_TTL = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(DockerUpstream.class);
    private static final Pattern PARAM = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))");

    /** The authentication scheme the upstream asks for on {@code GET /v2/}. */
    public enum Scheme { NONE, BASIC, BEARER }

    /** A parsed {@code WWW-Authenticate} header. */
    public record Challenge(Scheme scheme, String realm, String service) {

        public static final Challenge NONE = new Challenge(Scheme.NONE, null, null);
    }

    /** A probed challenge, with the URL that answered it (after the probe's redirects) when it was a 401. */
    private record CachedChallenge(Challenge challenge, URI answeredBy, String remoteUrl, Instant expires) {
    }

    /**
     * A pull token and what it was issued for ({@link #issuer}): it is only ever sent back to the registry whose
     * realm issued it, for the credentials it was issued against.
     */
    private record CachedToken(String token, String issuer, Instant expires) {
    }

    private final UpstreamClient client;
    private final ProxyService proxy;
    private final StorageService storage;
    private final UpstreamHealth health;
    private final NegativeCache negativeCache;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<UUID, CachedChallenge> challenges = new ConcurrentHashMap<>();
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    @Autowired
    public DockerUpstream(UpstreamClient client, ProxyService proxy, StorageService storage, UpstreamHealth health,
                          NegativeCache negativeCache, ObjectMapper mapper) {
        this(client, proxy, storage, health, negativeCache, mapper, Clock.systemUTC());
    }

    DockerUpstream(UpstreamClient client, ProxyService proxy, StorageService storage, UpstreamHealth health,
                   NegativeCache negativeCache, ObjectMapper mapper, Clock clock) {
        this.client = client;
        this.proxy = proxy;
        this.storage = storage;
        this.health = health;
        this.negativeCache = negativeCache;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Whether single-segment names are rewritten to {@code library/<name>} for this repository. */
    public boolean libraryPrefix(Repository repo) {
        return repo.settingEnabled(LIBRARY_PREFIX_SETTING, isDockerHub(repo));
    }

    static boolean isDockerHub(Repository repo) {
        String remote = repo.getRemoteUrl();
        if (remote == null) {
            return false;
        }
        try {
            String host = URI.create(remote.trim()).getHost();
            return host != null && (host.equals("docker.io") || host.endsWith(".docker.io"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The name the image has upstream, i.e. with the {@code library/} prefix Docker Hub expects for official images. */
    public String upstreamName(Repository repo, String name) {
        return name.indexOf('/') < 0 && libraryPrefix(repo) ? "library/" + name : name;
    }

    /** The upstream's {@code /v2/} base, with a trailing slash. */
    public URI v2Base(Repository repo) {
        String remote = repo.getRemoteUrl() == null ? "" : repo.getRemoteUrl().trim();
        while (remote.endsWith("/")) {
            remote = remote.substring(0, remote.length() - 1);
        }
        if (remote.endsWith("/v2")) {
            remote = remote.substring(0, remote.length() - 3);
        }
        return URI.create(remote + "/v2/");
    }

    public URI uri(Repository repo, String upstreamPath) {
        return v2Base(repo).resolve(upstreamPath);
    }

    /**
     * Fetches {@code upstreamPath} (relative to the upstream's {@code /v2/}) through the proxy cache under
     * {@code cachePath}, adding a bearer token for {@code name} when the upstream asks for one.
     */
    public Optional<StoredContent> fetch(Repository repo, String cachePath, String upstreamPath, String name,
                                         FetchOptions options, String accept) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", accept == null || accept.isBlank() ? "*/*" : accept);
        URI target = uri(repo, upstreamPath);
        UpstreamHealth.Status before = health.status(repo);
        if (proxy.isOffline(repo) || answeredLocally(repo, cachePath, options, before)) {
            return proxyFetch(repo, cachePath, target, options, headers);
        }
        Challenge challenge = challenge(repo);
        boolean bearer = false;
        if (challenge.scheme() == Scheme.BEARER && !hasRemoteToken(repo)) {
            Optional<String> token = token(repo, challenge, name);
            if (token.isPresent()) {
                // replaces the repository's credentials in UpstreamClient, which keeps it on the registry's origin
                headers.put("Authorization", "Bearer " + token.get());
                bearer = true;
            }
        }
        Optional<StoredContent> result = proxyFetch(repo, cachePath, target, options, headers);
        if (result.isEmpty()) {
            denied(repo, cachePath, name, bearer, before.consecutiveFailures());
        }
        return result;
    }

    /**
     * {@link ProxyService#fetch}, repeated once when a concurrent request cached the same path first (two clients
     * pulling the same uncached layer): the second attempt finds that copy instead of failing the request.
     */
    private Optional<StoredContent> proxyFetch(Repository repo, String cachePath, URI target, FetchOptions options,
                                               Map<String, String> headers) {
        try {
            return proxy.fetch(repo, cachePath, target, options, headers);
        } catch (DataIntegrityViolationException e) {
            log.debug("concurrent caching of {} in {}; reading it back", cachePath, repo.getName());
            return proxy.fetch(repo, cachePath, target, options, headers);
        }
    }

    /**
     * Whether {@link ProxyService} will answer this path without contacting the upstream: cached immutable content,
     * metadata younger than the repository's max age, a remembered miss or an open circuit. The challenge probe and
     * the token exchange are skipped for those, so serving the cache never waits for the registry or its token
     * service (both of which can be unreachable while the cache is perfectly usable).
     */
    boolean answeredLocally(Repository repo, String cachePath, FetchOptions options, UpstreamHealth.Status health) {
        Optional<StoredContent> cached = storage.find(repo, cachePath);
        if (cached.isPresent()) {
            if (!options.metadata()) {
                return true;
            }
            Instant verified = cached.get().asset().getLastVerifiedAt();
            Duration maxAge = options.maxAge() != null ? options.maxAge() : proxy.metadataMaxAge(repo);
            if (verified != null && maxAge != null && verified.plus(maxAge).isAfter(clock.instant())) {
                return true;
            }
        } else if (negativeCache.contains(repo.getId(), cachePath)) {
            return true;
        }
        return !health.reachable();
    }

    /**
     * Registries answer 401 or 403, not 404, for images that do not exist or are private (Docker Hub does, so as
     * not to reveal which private repositories exist). {@link ProxyService} counts those as outages, which would
     * open the circuit for every image after a few typos. Here they are turned back into a plain miss: the
     * repository's health is restored, the remembered challenge and token are dropped so the next request
     * negotiates afresh and, when the registry rejected a token it issued itself, the miss is remembered like a 404.
     */
    private void denied(Repository repo, String cachePath, String name, boolean bearer, int failuresBefore) {
        UpstreamHealth.Status status = health.status(repo);
        String error = status.lastError();
        if (status.consecutiveFailures() != failuresBefore + 1 || error == null) {
            return;
        }
        if (!error.equals("HTTP 401 for " + cachePath) && !error.equals("HTTP 403 for " + cachePath)) {
            return;
        }
        log.info("upstream of {} denies {} ({}); treating it as missing", repo.getName(), name, error);
        health.recordSuccess(repo);
        challenges.remove(repo.getId());
        tokens.remove(repo.getId() + "|repository:" + name + ":pull");
        if (bearer) {
            negativeCache.put(repo.getId(), cachePath, proxy.negativeTtl(repo));
        }
    }

    /** The challenge the upstream of {@code repo} answers on {@code GET /v2/}, probed at most once an hour. */
    public Challenge challenge(Repository repo) {
        CachedChallenge cached = challenges.get(repo.getId());
        Instant now = clock.instant();
        if (cached != null && cached.expires().isAfter(now) && cached.remoteUrl().equals(repo.getRemoteUrl())) {
            return usable(repo, cached);
        }
        Challenge probed;
        URI answeredBy = null;
        Duration ttl;
        // anonymous on purpose: the answer to a request without credentials is the challenge this probe is after
        try (UpstreamClient.Response response = client.getAnonymous(repo, v2Base(repo), Map.of(), Map.of())) {
            if (response.status() == 401) {
                probed = parseChallenge(response.header("WWW-Authenticate").orElse(""));
                answeredBy = response.uri();
                ttl = CHALLENGE_TTL;
                if (probed.scheme() == Scheme.BEARER && !challengeTrusted(repo, answeredBy)) {
                    log.warn("upstream of {} redirected /v2/ to {}, which may not receive its credentials; ignoring the "
                            + "realm that host names", repo.getName(), answeredBy);
                }
            } else if (response.status() == 200) {
                probed = Challenge.NONE;
                ttl = CHALLENGE_TTL;
            } else {
                log.warn("upstream of {} answered {} on /v2/; assuming it wants no token", repo.getName(), response.status());
                probed = Challenge.NONE;
                ttl = CHALLENGE_RETRY;
            }
        } catch (IOException e) {
            log.warn("could not probe the upstream of {} ({}); assuming it wants no token", repo.getName(), e.getMessage());
            probed = Challenge.NONE;
            ttl = CHALLENGE_RETRY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Challenge.NONE;
        }
        CachedChallenge fresh = new CachedChallenge(probed, answeredBy, repo.getRemoteUrl(), now.plus(ttl));
        challenges.put(repo.getId(), fresh);
        return usable(repo, fresh);
    }

    /**
     * The cached challenge as the repository may use it now: a realm named by a host the credentials may not go to
     * is ignored. Decided on every use, not only when probing, because credentials or {@code upstream.credentialHosts}
     * may have changed since (a proxy that had no credentials when it was probed may have some now).
     */
    private static Challenge usable(Repository repo, CachedChallenge cached) {
        Challenge challenge = cached.challenge();
        return challenge.scheme() == Scheme.BEARER && !challengeTrusted(repo, cached.answeredBy()) ? Challenge.NONE : challenge;
    }

    /**
     * Whether the challenge in a probe's answer is the registry's own. The registry's {@code /v2/} may redirect, and
     * the probe follows; a host it was sent to that may not receive the repository's credentials (another origin,
     * not listed in {@code upstream.credentialHosts}) does not get to name the realm they are sent to either.
     */
    static boolean challengeTrusted(Repository repo, URI answeredBy) {
        return answeredBy == null || !UpstreamCredentials.present(repo) || UpstreamCredentials.permitted(repo, answeredBy);
    }

    /** Parses a {@code WWW-Authenticate} value such as {@code Bearer realm="https://auth.docker.io/token",service="registry.docker.io"}. */
    public static Challenge parseChallenge(String header) {
        if (header == null || header.isBlank()) {
            return Challenge.NONE;
        }
        String value = header.trim();
        int space = value.indexOf(' ');
        String scheme = (space < 0 ? value : value.substring(0, space)).toLowerCase(Locale.ROOT);
        String params = space < 0 ? "" : value.substring(space + 1);
        if (scheme.equals("basic")) {
            return new Challenge(Scheme.BASIC, null, null);
        }
        if (!scheme.equals("bearer")) {
            return Challenge.NONE;
        }
        String realm = null;
        String service = null;
        Matcher m = PARAM.matcher(params);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            String v = m.group(2) != null ? m.group(2) : m.group(3);
            if (key.equals("realm")) {
                realm = v;
            } else if (key.equals("service")) {
                service = v;
            }
        }
        if (realm == null || realm.isBlank()) {
            return Challenge.NONE;
        }
        return new Challenge(Scheme.BEARER, realm, service);
    }

    /** A pull token for {@code name}, from the cache or freshly exchanged at the challenge's realm. */
    public Optional<String> token(Repository repo, Challenge challenge, String name) {
        String scope = "repository:" + name + ":pull";
        String key = repo.getId() + "|" + scope;
        String issuer = issuer(repo, challenge);
        CachedToken cached = tokens.get(key);
        Instant now = clock.instant();
        // a token issued for another remote URL (the repository was pointed at another registry), another realm or
        // other credentials is never sent: it would hand one registry's token to another
        if (cached != null && cached.issuer().equals(issuer) && cached.expires().isAfter(now)) {
            return Optional.of(cached.token());
        }
        URI realm = tokenUri(challenge, scope);
        if (realm == null) {
            return Optional.empty();
        }
        // The registry's WWW-Authenticate challenge names this realm as its token service, usually on another host
        // (auth.docker.io for registry-1.docker.io, a GitLab instance for its registry): the protocol itself sends
        // the repository's credentials there, so they go to the realm's origin deliberately. A repository without
        // credentials asks for an anonymous token.
        try (UpstreamClient.Response response = client.getWithCredentials(repo, realm, Map.of(), Map.of("Accept", MediaTypes.JSON))) {
            if (response.status() != 200) {
                log.warn("token endpoint {} of {} answered {} for scope {}", challenge.realm(), repo.getName(),
                        response.status(), scope);
                return Optional.empty();
            }
            JsonNode body;
            try (InputStream in = response.body()) {
                body = mapper.readTree(in);
            }
            String token = text(body, "token");
            if (token == null) {
                token = text(body, "access_token");
            }
            if (token == null || token.isBlank()) {
                log.warn("token endpoint {} of {} returned no token", challenge.realm(), repo.getName());
                return Optional.empty();
            }
            Duration ttl = TOKEN_DEFAULT_TTL;
            JsonNode expiresIn = body.get("expires_in");
            if (expiresIn != null && expiresIn.isNumber() && expiresIn.longValue() > 0) {
                ttl = Duration.ofSeconds(expiresIn.longValue());
            }
            Duration keep = ttl.minus(TOKEN_MARGIN);
            if (keep.compareTo(TOKEN_MIN_TTL) < 0) {
                keep = TOKEN_MIN_TTL;
            }
            tokens.put(key, new CachedToken(token, issuer, now.plus(keep)));
            return Optional.of(token);
        } catch (IOException | RuntimeException e) {
            log.warn("could not obtain a token from {} for {}: {}", challenge.realm(), repo.getName(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * What a token is issued for: the registry (remote URL), the realm and service of its challenge and the
     * credentials it was asked with, as a digest so the cache holds no password.
     */
    static String issuer(Repository repo, Challenge challenge) {
        String raw = String.join("\n", String.valueOf(repo.getRemoteUrl()), String.valueOf(challenge.realm()),
                String.valueOf(challenge.service()), String.valueOf(repo.getRemoteUsername()),
                String.valueOf(repo.getRemotePassword()));
        return Checksums.hex("SHA-256", raw.getBytes(StandardCharsets.UTF_8));
    }

    static URI tokenUri(Challenge challenge, String scope) {
        if (challenge.realm() == null) {
            return null;
        }
        StringBuilder url = new StringBuilder(challenge.realm());
        url.append(challenge.realm().indexOf('?') >= 0 ? '&' : '?');
        if (challenge.service() != null && !challenge.service().isBlank()) {
            url.append("service=").append(URLEncoder.encode(challenge.service(), StandardCharsets.UTF_8)).append('&');
        }
        url.append("scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        try {
            return URI.create(url.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Drops the remembered challenge and tokens of a repository (after its settings changed, or in tests). */
    public void forget(Repository repo) {
        challenges.remove(repo.getId());
        tokens.keySet().removeIf(k -> k.startsWith(repo.getId() + "|"));
    }

    static boolean hasRemoteToken(Repository repo) {
        return repo.getRemoteToken() != null && !repo.getRemoteToken().isBlank();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isString() ? null : value.stringValue();
    }
}
