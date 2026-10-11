package dev.granary.format.composer;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.ContentTypes;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proxy repositories: a cache of another Composer v2 repository (Packagist by default).
 *
 * <p>The upstream's {@code packages.json} is cached and tells where its {@code p2} documents, search, package list,
 * providers and security-advisory APIs are. {@code p2} documents are cached as upstream sent them and rewritten on
 * the way out; dist archives are fetched from wherever the metadata says (GitHub, GitLab, …) the first time a client
 * asks for them through the rewritten URL, and kept forever. An upstream 404 for a {@code p2} document is remembered
 * on disk, so that an offline proxy can still say "no such package" instead of "cannot tell".
 */
@Component
public class ComposerProxy {

    private static final Logger log = LoggerFactory.getLogger(ComposerProxy.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    public static final String DIST_REWRITE_SETTING = "composer.distRewrite";
    static final String ROOT_CACHE = "packages.json";
    static final String ABSENT_PREFIX = "absent/";
    static final String ADVISORIES_PREFIX = "security-advisories/";
    static final Map<String, String> ACCEPT_JSON = Map.of("Accept", "application/json");
    /** Package names per upstream advisory request; Packagist accepts long lists, URLs do not. */
    static final int ADVISORY_BATCH = 50;

    private final ProxyService proxy;
    private final StorageService storage;
    private final UpstreamClient upstream;
    private final UpstreamHealth health;
    private final NegativeCache negativeCache;
    private final ObjectMapper mapper;
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public ComposerProxy(ProxyService proxy, StorageService storage, UpstreamClient upstream, UpstreamHealth health,
                         NegativeCache negativeCache, ObjectMapper mapper) {
        this.proxy = proxy;
        this.storage = storage;
        this.upstream = upstream;
        this.health = health;
        this.negativeCache = negativeCache;
        this.mapper = mapper;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** Whether dist URLs are rewritten to the repository ({@code composer.distRewrite}, default true). */
    public static boolean rewritesDist(Repository repo) {
        return repo.settingEnabled(DIST_REWRITE_SETTING, true);
    }

    // --- the upstream root document ----------------------------------------------------------------

    /** The upstream's {@code packages.json}, from the cache when fresh or the upstream is unreachable. */
    public Optional<ObjectNode> upstreamRoot(Repository repo) {
        URI uri = rootUri(repo);
        return proxy.fetch(repo, ROOT_CACHE, uri, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::object);
    }

    /** {@code <remote>/packages.json}, or the remote URL itself when it already names a {@code .json} file. */
    static URI rootUri(Repository repo) {
        String remote = repo.getRemoteUrl().trim();
        URI uri = URI.create(remote);
        if (uri.getPath() != null && uri.getPath().contains(".json")) {
            return uri;
        }
        return URI.create(stripTrailingSlash(remote) + "/packages.json");
    }

    /**
     * Resolves a URL from the upstream root the way Composer's {@code canonicalizeUrl} does: a leading slash means
     * the root's origin, an absolute URL stays, anything else is relative to the root's directory.
     */
    static Optional<String> canonicalize(URI root, String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String value = url.trim();
        if (value.startsWith("//")) {
            return Optional.of(root.getScheme() + ":" + value);
        }
        if (value.startsWith("/")) {
            String origin = UpstreamScope.origin(root);
            return origin == null ? Optional.empty() : Optional.of(origin + value);
        }
        if (value.contains("://")) {
            return Optional.of(value);
        }
        String rootString = root.toString();
        int slash = rootString.lastIndexOf('/');
        return Optional.of(rootString.substring(0, slash + 1) + value);
    }

    /** A URL template from the upstream root ({@code search}, {@code list}, …), canonicalised. */
    Optional<String> rootUrl(Repository repo, ObjectNode root, String field) {
        return canonicalize(rootUri(repo), P2Document.string(root, field));
    }

    /** The upstream's security-advisory API, when its root declares one. */
    Optional<String> advisoryApi(Repository repo, ObjectNode root) {
        JsonNode config = root.get("security-advisories");
        if (!(config instanceof ObjectNode object)) {
            return Optional.empty();
        }
        return canonicalize(rootUri(repo), P2Document.string(object, "api-url"));
    }

    /** Where upstream keeps {@code p2/<file>.json}: its declared {@code metadata-url}, else {@code <remote>/p2/…}. */
    URI metadataUri(Repository repo, String fileName) {
        URI root = rootUri(repo);
        Optional<String> template = Optional.empty();
        Optional<StoredContent> cachedRoot = storage.find(repo, ROOT_CACHE);
        Optional<ObjectNode> rootDoc = cachedRoot.isPresent() ? cachedRoot.flatMap(this::object) : upstreamRoot(repo);
        if (rootDoc.isPresent()) {
            template = canonicalize(root, P2Document.string(rootDoc.get(), "metadata-url"));
        }
        String url = template.orElseGet(() -> canonicalize(root, "p2/%package%.json").orElseThrow());
        try {
            return URI.create(url.replace("%package%", fileName));
        } catch (IllegalArgumentException e) {
            return URI.create(canonicalize(root, "p2/" + fileName + ".json").orElseThrow());
        }
    }

    // --- package metadata --------------------------------------------------------------------------

    /** The upstream's document for a package, cached; see {@link Lookup} for what a miss means. */
    public Lookup metadata(Repository repo, String name, boolean dev) {
        String path = ComposerPath.metadataPath(name, dev);
        URI uri = metadataUri(repo, name + (dev ? ComposerPath.DEV_SUFFIX : ""));
        Optional<StoredContent> content = proxy.fetch(repo, path, uri,
                FetchOptions.index(), ACCEPT_JSON);
        if (content.isPresent()) {
            Optional<P2Document> doc = parseDocument(content.get(), name);
            if (doc.isPresent()) {
                return Lookup.found(doc.get());
            }
            log.warn("{}: upstream metadata {} is not a Composer document", repo.getName(), path);
            return Lookup.unknown();
        }
        if (upstreamAnswered(repo)) {
            rememberAbsent(repo, path);
            return Lookup.missing();
        }
        return knownAbsent(repo, name, dev) ? Lookup.missingOffline() : Lookup.unknown();
    }

    /**
     * Whether upstream said it has no such document the last time it could be asked. A Composer 2 repository serves
     * the {@code ~dev} document of every package it has, so when it denied knowing the package's releases its
     * branches are absent too: {@code composer show -a} or a dev-stability resolution of a private package then
     * still works offline, although only the release document was ever asked for.
     */
    boolean knownAbsent(Repository repo, String name, boolean dev) {
        return storage.exists(repo, ABSENT_PREFIX + ComposerPath.metadataPath(name, dev))
                || (dev && storage.exists(repo, ABSENT_PREFIX + ComposerPath.metadataPath(name, false)));
    }

    /** The cached document only, without asking upstream. */
    Optional<P2Document> cachedMetadata(Repository repo, String name, boolean dev) {
        return storage.find(repo, ComposerPath.metadataPath(name, dev)).flatMap(c -> parseDocument(c, name));
    }

    /** True when upstream could be asked and answered: not offline, circuit closed, no failure since the last success. */
    boolean upstreamAnswered(Repository repo) {
        if (proxy.isOffline(repo)) {
            return false;
        }
        UpstreamHealth.Status status = proxy.status(repo);
        return status.reachable() && status.consecutiveFailures() == 0;
    }

    private void rememberAbsent(Repository repo, String path) {
        String marker = ABSENT_PREFIX + path;
        try {
            if (!storage.exists(repo, marker)) {
                byte[] body = "{\"absent\":true}".getBytes(StandardCharsets.UTF_8);
                storage.store(repo, marker, new ByteArrayInputStream(body), ComposerHosted.JSON, Asset.Origin.CACHED, true);
            }
        } catch (RuntimeException e) {
            // a concurrent request wrote the same marker; either way it is there
            log.debug("{}: could not record {} as absent: {}", repo.getName(), path, e.getMessage());
        }
    }

    private Optional<P2Document> parseDocument(StoredContent content, String name) {
        try {
            return P2Document.parse(mapper, content.readAllBytes(), name, lastModified(content.asset()));
        } catch (IOException e) {
            log.warn("cached metadata {} is unreadable: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * When Granary's copy of a cached document last changed (or was re-validated), not the upstream's own
     * {@code Last-Modified}. Composer sends the {@code Last-Modified} it kept back as {@code If-Modified-Since}, and the
     * served value is the latest of this, the repository's configuration and, in a group, the other members'
     * documents. The upstream's time of a change can be older than a value already served (a private release or a
     * configuration change that happened while the cached copy was still fresh), which would answer 304 to new
     * content until the package changed again; the local time is always later than anything served before the copy
     * changed. A re-validation that found nothing new costs one full response instead of a 304.
     */
    static Instant lastModified(Asset asset) {
        return asset.getUpdatedAt();
    }

    // --- dist archives -----------------------------------------------------------------------------

    /** The result of resolving a dist archive. */
    public record Dist(StoredContent content, String unavailable) {
        static final Dist MISSING = new Dist(null, null);

        static Dist found(StoredContent content) {
            return new Dist(content, null);
        }

        static Dist unavailable(String reason) {
            return new Dist(null, reason);
        }

        public boolean isFound() {
            return content != null;
        }
    }

    /**
     * A dist archive: from the cache, else from the URL the package metadata names for that version and file, which
     * is then cached forever and linked to the package version for the component browser.
     */
    public Dist distribution(Repository repo, ComposerPath path) {
        String storagePath = ComposerPath.distPath(path.name(), path.version(), path.file());
        Optional<StoredContent> cached = storage.find(repo, storagePath);
        if (cached.isPresent()) {
            return Dist.found(cached.get());
        }
        Optional<ObjectNode> entry = distEntry(repo, path, false);
        if (entry.isEmpty() && !proxy.isOffline(repo)) {
            entry = distEntry(repo, path, true);
        }
        if (entry.isEmpty()) {
            return Dist.MISSING;
        }
        String url = P2Document.string(entry.get().get(P2Document.DIST), "url");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dist.MISSING;
        }
        String version = P2Document.version(entry.get());
        StorageService.ComponentCoordinates coordinates = new StorageService.ComponentCoordinates(
                ComposerNames.vendor(path.name()), ComposerNames.project(path.name()), version == null ? path.version() : version);
        if (UpstreamScope.sameOrigin(repo, uri)) {
            return proxy.fetch(repo, storagePath, uri, FetchOptions.artifact(), Map.of())
                    .map(content -> Dist.found(linkComponent(repo, content, coordinates)))
                    .orElse(Dist.MISSING);
        }
        return fetchForeign(repo, storagePath, uri, coordinates);
    }

    /** The version entry whose rewritten dist URL is {@code path}, from the cached metadata or, if asked, upstream's. */
    private Optional<ObjectNode> distEntry(Repository repo, ComposerPath path, boolean refresh) {
        for (boolean dev : new boolean[] {path.dev(), !path.dev()}) {
            Optional<P2Document> doc = refresh
                    ? Optional.of(metadata(repo, path.name(), dev)).filter(Lookup::isFound).map(Lookup::document)
                    : cachedMetadata(repo, path.name(), dev);
            if (doc.isEmpty()) {
                continue;
            }
            for (ObjectNode entry : doc.get().versions()) {
                if (!path.file().equals(ComposerDist.fileName(entry).orElse(null))) {
                    continue;
                }
                String version = P2Document.version(entry);
                if (path.version().equals(version)
                        || ComposerVersions.tryNormalize(path.version()).map(v -> v.equals(P2Document.normalized(entry))).orElse(false)) {
                    return Optional.of(entry);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Downloads an archive from a host other than the upstream's: without the repository's upstream credentials
     * (unless that host is listed in {@code upstream.credentialHosts}) and behind a circuit breaker of its own, so a
     * rate-limited GitHub cannot take the metadata upstream down with it.
     */
    private Dist fetchForeign(Repository repo, String path, URI uri, StorageService.ComponentCoordinates coordinates) {
        if (proxy.isOffline(repo) || negativeCache.contains(repo.getId(), path)) {
            return Dist.MISSING;
        }
        Repository scope = UpstreamScope.hostScope(repo, uri);
        ReentrantLock lock = locks[Math.floorMod((repo.getId() + "|" + path).hashCode(), locks.length)];
        lock.lock();
        try {
            Optional<StoredContent> cached = storage.find(repo, path);
            if (cached.isPresent()) {
                return Dist.found(cached.get());
            }
            if (!health.allowRequest(scope)) {
                UpstreamHealth.Status status = health.status(scope);
                // the circuit breaker of that host is open after recent failures; say what the last one was
                return Dist.unavailable(UpstreamScope.origin(uri) + " cannot be used right now"
                        + (status.lastError() == null ? "" : " (last failure: " + status.lastError() + ")"));
            }
            try (UpstreamClient.Response response = upstream.getAbsolute(scope, uri, Map.of(), Map.of())) {
                int status = response.status();
                if (status == 200) {
                    // a forge's Content-Type is stored and served again later, so it must be a valid media type
                    String contentType = response.header("Content-Type").map(ComposerProxy::stripParameters)
                            .filter(ComposerProxy::isMediaType).orElseGet(() -> ContentTypes.guess(path));
                    StoredContent stored;
                    try (InputStream body = response.body()) {
                        stored = storage.store(repo, path, body, contentType, Asset.Origin.CACHED, false, coordinates);
                    }
                    health.recordSuccess(scope);
                    return Dist.found(stored);
                }
                if (status == 404 || status == 410) {
                    health.recordSuccess(scope);
                    negativeCache.put(repo.getId(), path, proxy.negativeTtl(repo));
                    return Dist.MISSING;
                }
                health.recordFailure(scope, "HTTP " + status + " for " + uri);
                log.warn("{}: {} answered {} for {}", repo.getName(), UpstreamScope.origin(uri), status, path);
                return Dist.unavailable(UpstreamScope.origin(uri) + " answered HTTP " + status);
            } catch (IOException e) {
                health.recordFailure(scope, e.getClass().getSimpleName() + ": " + e.getMessage());
                log.warn("{}: {} unreachable for {}: {}", repo.getName(), UpstreamScope.origin(uri), path, e.getMessage());
                return Dist.unavailable(UpstreamScope.origin(uri) + " is unreachable: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                health.recordFailure(scope, "interrupted");
                return Dist.unavailable("interrupted");
            }
        } finally {
            lock.unlock();
        }
    }

    private StoredContent linkComponent(Repository repo, StoredContent content, StorageService.ComponentCoordinates coordinates) {
        Asset asset = content.asset();
        if (asset.getComponentId() != null) {
            return content;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                asset.setComponentId(storage.ensureComponent(repo, coordinates).getId());
                return new StoredContent(storage.save(asset), content.blobStore());
            } catch (RuntimeException e) {
                // a concurrent download of the same version created the component first
                asset.setComponentId(null);
                log.debug("linking {} in {} to its component failed: {}", asset.getPath(), repo.getName(), e.getMessage());
            }
        }
        return content;
    }

    // --- search, list, providers -------------------------------------------------------------------

    /** Upstream search results, cached per query; empty when upstream has no search or cannot be asked. */
    public Optional<ObjectNode> search(Repository repo, String query, String type, String page, String perPage) {
        Optional<ObjectNode> root = upstreamRoot(repo);
        Optional<String> template = root.flatMap(r -> rootUrl(repo, r, "search"));
        if (template.isEmpty()) {
            return Optional.empty();
        }
        String url = template.get()
                .replace("%query%", encode(query))
                .replace("%type%", encode(type));
        url = appendQuery(url, "page", page);
        url = appendQuery(url, "per_page", perPage);
        String key = Checksums.hex("SHA-256", (query + "\n" + type + "\n" + page + "\n" + perPage).getBytes(StandardCharsets.UTF_8));
        return passThrough(repo, "search/" + key.substring(0, 40) + ".json", url);
    }

    /** The upstream package list, cached per query. */
    public Optional<ObjectNode> list(Repository repo, Map<String, String> query) {
        Optional<String> base = upstreamRoot(repo).flatMap(r -> rootUrl(repo, r, "list"));
        if (base.isEmpty()) {
            return Optional.empty();
        }
        String url = base.get();
        StringBuilder key = new StringBuilder();
        for (Map.Entry<String, String> e : query.entrySet()) {
            url = appendQuery(url, e.getKey(), e.getValue());
            key.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        String cachePath = key.isEmpty() ? "list.json"
                : "list/" + Checksums.hex("SHA-256", key.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 40) + ".json";
        return passThrough(repo, cachePath, url);
    }

    /** Whether the upstream root declares a package list, so the proxy can offer one. */
    public boolean hasList(ObjectNode root) {
        return P2Document.string(root, "list") != null;
    }

    /** Packages providing {@code name}, from upstream's providers API. */
    public Optional<ObjectNode> providers(Repository repo, String name) {
        Optional<String> template = upstreamRoot(repo).flatMap(r -> rootUrl(repo, r, "providers-api"));
        if (template.isEmpty()) {
            return Optional.empty();
        }
        return passThrough(repo, "providers/" + name + ".json", template.get().replace("%package%", name));
    }

    private Optional<ObjectNode> passThrough(Repository repo, String cachePath, String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("{}: upstream URL {} is invalid", repo.getName(), url);
            return Optional.empty();
        }
        return proxy.fetch(repo, cachePath, uri, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::object);
    }

    // --- security advisories -----------------------------------------------------------------------

    /**
     * Security advisories for some packages, by package name. Answers come from a per-package cache that is renewed
     * from the upstream API after the repository's metadata max age, asking upstream once for all stale packages;
     * when upstream cannot be asked the cached answers are used however old. Packages nothing is known about are
     * left out of the map.
     *
     * @return empty when upstream declares no advisory API
     */
    public Optional<Map<String, JsonNode>> advisories(Repository repo, List<String> names) {
        Optional<ObjectNode> root = upstreamRoot(repo);
        Optional<String> api = root.flatMap(r -> advisoryApi(repo, r));
        if (api.isEmpty()) {
            return Optional.empty();
        }
        Map<String, JsonNode> result = new LinkedHashMap<>();
        Map<String, StoredContent> stale = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        Instant freshAfter = Instant.now().minus(proxy.metadataMaxAge(repo));
        for (String name : names) {
            Optional<StoredContent> cached = storage.find(repo, ADVISORIES_PREFIX + name + ".json");
            Optional<JsonNode> value = cached.flatMap(this::node);
            if (value.isPresent() && cached.get().asset().getLastVerifiedAt() != null
                    && cached.get().asset().getLastVerifiedAt().isAfter(freshAfter)) {
                result.put(name, value.get());
            } else if (value.isPresent()) {
                stale.put(name, cached.get());
            } else {
                unknown.add(name);
            }
        }
        List<String> toFetch = new ArrayList<>(stale.keySet());
        toFetch.addAll(unknown);
        if (!toFetch.isEmpty() && !proxy.isOffline(repo)) {
            for (int i = 0; i < toFetch.size(); i += ADVISORY_BATCH) {
                List<String> batch = toFetch.subList(i, Math.min(toFetch.size(), i + ADVISORY_BATCH));
                fetchAdvisories(repo, api.get(), batch).ifPresent(result::putAll);
            }
        }
        for (Map.Entry<String, StoredContent> e : stale.entrySet()) {
            if (!result.containsKey(e.getKey())) {
                node(e.getValue()).ifPresent(v -> result.put(e.getKey(), v));
            }
        }
        return Optional.of(result);
    }

    private Optional<Map<String, JsonNode>> fetchAdvisories(Repository repo, String api, List<String> batch) {
        StringBuilder url = new StringBuilder(api);
        for (String name : batch) {
            url.append(url.indexOf("?") < 0 ? '?' : '&').append("packages%5B%5D=").append(encode(name));
        }
        URI uri;
        try {
            uri = URI.create(url.toString());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Repository scope = UpstreamScope.hostScope(repo, uri);
        if (!health.allowRequest(scope)) {
            return Optional.empty();
        }
        try (UpstreamClient.Response response = upstream.getAbsolute(scope, uri, Map.of(), ACCEPT_JSON)) {
            if (response.status() != 200) {
                health.recordFailure(scope, "HTTP " + response.status() + " for " + uri);
                return Optional.empty();
            }
            JsonNode body;
            try (InputStream in = response.body()) {
                body = mapper.readTree(in);
            }
            health.recordSuccess(scope);
            JsonNode advisories = body == null ? null : body.get("advisories");
            Map<String, JsonNode> fetched = new LinkedHashMap<>();
            for (String name : batch) {
                JsonNode list = advisories instanceof ObjectNode o && o.get(name) instanceof ArrayNode a ? a : NODES.arrayNode();
                byte[] bytes = mapper.writeValueAsBytes(list);
                storage.store(repo, ADVISORIES_PREFIX + name + ".json", new ByteArrayInputStream(bytes),
                        ComposerHosted.JSON, Asset.Origin.CACHED, true);
                fetched.put(name, list);
            }
            return Optional.of(fetched);
        } catch (IOException | JacksonException e) {
            health.recordFailure(scope, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("{}: security advisories unavailable: {}", repo.getName(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            health.recordFailure(scope, "interrupted");
            return Optional.empty();
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private Optional<ObjectNode> object(StoredContent content) {
        return node(content).filter(ObjectNode.class::isInstance).map(ObjectNode.class::cast);
    }

    private Optional<JsonNode> node(StoredContent content) {
        try {
            return Optional.ofNullable(mapper.readTree(content.readAllBytes()));
        } catch (IOException | JacksonException e) {
            log.warn("cached document {} is unreadable: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String appendQuery(String url, String name, String value) {
        if (value == null || value.isBlank()) {
            return url;
        }
        return url + (url.indexOf('?') < 0 ? '?' : '&') + encode(name) + "=" + encode(value);
    }

    private static String stripTrailingSlash(String s) {
        String r = s;
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private static String stripParameters(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    }

    /** Whether a Content-Type value is a concrete {@code type/subtype}. */
    static boolean isMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            return MediaType.parseMediaType(contentType).isConcrete();
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}
