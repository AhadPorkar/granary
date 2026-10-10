package dev.granary.format.cocoapods;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The upstream side of a proxy of a CDN-style spec repository ({@code https://cdn.cocoapods.org/} or any other).
 *
 * <p>The index documents are cached as metadata under their own names and re-validated after the repository's
 * metadata max age; podspecs are cached forever as the upstream served them (the rewrite of {@code source.http}
 * happens on the way out); source archives are fetched from the URL in the cached podspec and cached under
 * {@code sources/<Name>/<version>/<file>}. Podspecs listed in the upstream's {@code deprecated_podspecs.txt} are
 * the exception to "forever": {@code pod trunk deprecate} changes them, so they are re-validated like metadata.
 */
@Component
public class CocoaPodsProxy {

    public static final String REWRITE_SETTING = "cocoapods.rewriteHttpSources";

    private static final Logger log = LoggerFactory.getLogger(CocoaPodsProxy.class);
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    /** Headers the JDK client refuses or that Granary sets itself; never forwarded from a podspec. */
    private static final Set<String> NOT_FORWARDED = Set.of("host", "connection", "content-length", "expect", "upgrade",
            "te", "trailer", "transfer-encoding", "keep-alive", "user-agent", "if-none-match", "if-modified-since",
            "proxy-authorization", "proxy-connection", "http2-settings", "via", "warning", "date", "range");

    private final ProxyService proxy;
    private final StorageService storage;
    private final UpstreamClient client;
    private final NegativeCache negativeCache;
    private final Map<UUID, Cached<ShardLayout>> layouts = new ConcurrentHashMap<>();
    private final Map<UUID, Cached<Set<String>>> deprecated = new ConcurrentHashMap<>();

    private record Cached<T>(String sha256, T value) {
    }

    public CocoaPodsProxy(ProxyService proxy, StorageService storage, UpstreamClient client, NegativeCache negativeCache) {
        this.proxy = proxy;
        this.storage = storage;
        this.client = client;
        this.negativeCache = negativeCache;
    }

    /** Whether podspecs served through {@code repo} point their http sources at Granary (on unless switched off). */
    public static boolean rewritesSources(Repository repo) {
        return repo.settingEnabled(REWRITE_SETTING, true);
    }

    /** An index document ({@code CocoaPods-version.yml}, a shard, {@code all_pods.txt}, the deprecated list). */
    public Optional<StoredContent> index(Repository repo, String path) {
        return proxy.fetch(repo, path, FetchOptions.index());
    }

    /** The upstream's shard layout from its {@code CocoaPods-version.yml}; the standard one when that is unavailable. */
    public ShardLayout layout(Repository repo) {
        Optional<StoredContent> file = index(repo, VersionFile.PATH);
        if (file.isEmpty()) {
            return ShardLayout.STANDARD;
        }
        String sha = file.get().asset().getBlobSha256();
        Cached<ShardLayout> cached = layouts.get(repo.getId());
        if (cached != null && cached.sha256().equals(sha)) {
            return cached.value();
        }
        ShardLayout layout;
        try {
            layout = VersionFile.parse(file.get().readUtf8()).map(VersionFile::layout).orElse(ShardLayout.STANDARD);
        } catch (IOException e) {
            log.warn("cached {} of {} is unreadable: {}", VersionFile.PATH, repo.getName(), e.getMessage());
            layout = ShardLayout.STANDARD;
        }
        layouts.put(repo.getId(), new Cached<>(sha, layout));
        return layout;
    }

    /** The podspec as the upstream serves it, linked to its component once cached. */
    public Optional<StoredContent> podspec(Repository repo, String name, String version) {
        String path = layout(repo).podspecPath(name, version);
        FetchOptions options = isDeprecated(repo, path) ? FetchOptions.index() : FetchOptions.artifact();
        return proxy.fetch(repo, path, options).map(content -> linkComponent(repo, content, name, version));
    }

    /**
     * A source archive under {@code sources/<Name>/<version>/<file>}: from the cache, else downloaded from the
     * {@code source.http} of the cached podspec (with the podspec's {@code source.headers}) and cached forever.
     * Empty when the podspec has no http source of that file name, or the archive's host answers 404.
     *
     * <p>Source archives live on any host the pod's author chose (GitHub releases, S3, a vendor's site). Only an
     * archive on the proxy's own upstream (same scheme, host and port as the remote URL) goes through
     * {@link ProxyService} with the repository's upstream credentials; every other host is asked without them, and
     * its failures do not count against the health of the proxy's upstream, so a dead archive link cannot open the
     * circuit breaker for the whole spec repository. Either way, an archive that does not match the podspec's
     * {@code sha256} is not cached.
     *
     * @throws CocoaPodsError {@code 502} when the archive's host fails or serves an archive with the wrong digest
     */
    public Optional<StoredContent> source(Repository repo, String name, String version, String filename) {
        String path = CocoaPodsPath.sourcePath(name, version, filename);
        Optional<StoredContent> cached = storage.find(repo, path);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<Podspec> spec = podspec(repo, name, version).flatMap(CocoaPodsProxy::parse);
        if (spec.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> url = spec.get().httpSource().filter(SourceArchive::isHttpUrl);
        if (url.isEmpty() || !SourceArchive.filename(url.get(), spec.get().sourceType().orElse(null)).equals(filename)) {
            return Optional.empty();
        }
        URI upstream;
        try {
            upstream = new URI(url.get().strip());
        } catch (URISyntaxException e) {
            log.warn("source of {} {} in {} is not a usable URL: {}", name, version, repo.getName(), url.get());
            return Optional.empty();
        }
        Map<String, String> headers = forwardedHeaders(spec.get().sourceHeaders());
        StorageService.ComponentCoordinates coordinates = new StorageService.ComponentCoordinates("", name, version);
        Optional<StoredContent> fetched = sameOrigin(repo.getRemoteUrl(), upstream)
                ? proxy.fetch(repo, path, upstream, FetchOptions.artifact(), headers)
                : download(repo, path, upstream, headers, coordinates);
        if (fetched.isEmpty()) {
            return Optional.empty();
        }
        verifySha256(repo, fetched.get(), spec.get(), upstream);
        return Optional.of(linkComponent(repo, fetched.get(), name, version));
    }

    /** Whether {@code uri} is on the origin (scheme, host, port) of the repository's remote URL. */
    static boolean sameOrigin(String remoteUrl, URI uri) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return false;
        }
        URI remote;
        try {
            remote = new URI(remoteUrl.strip());
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String remoteScheme = remote.getScheme() == null ? "" : remote.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals(remoteScheme)
                && uri.getHost() != null && uri.getHost().equalsIgnoreCase(remote.getHost())
                && effectivePort(uri) == effectivePort(remote);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    /**
     * Downloads an archive from another host than the proxy's upstream: no repository credentials, no circuit
     * breaker, the shared negative cache for 404s, offline mode honoured.
     */
    private Optional<StoredContent> download(Repository repo, String path, URI uri, Map<String, String> headers,
                                             StorageService.ComponentCoordinates coordinates) {
        if (proxy.isOffline(repo) || negativeCache.contains(repo.getId(), path)) {
            return Optional.empty();
        }
        try (UpstreamClient.Response response = client.getAbsolute(withoutCredentials(repo), uri, Map.of(), headers)) {
            int status = response.status();
            if (status == 200) {
                String contentType = response.header("Content-Type")
                        .map(ct -> ct.contains(";") ? ct.substring(0, ct.indexOf(';')) : ct)
                        .map(String::strip)
                        .filter(ct -> !ct.isEmpty())
                        .orElse(null);
                StoredContent stored;
                try (InputStream body = response.body()) {
                    stored = storage.store(repo, path, body, contentType, Asset.Origin.CACHED, false, coordinates);
                }
                Asset asset = storage.touchVerified(stored.asset(), response.header("ETag").orElse(null),
                        response.header("Last-Modified").orElse(null));
                log.info("cached source archive {} of {} from {}", path, repo.getName(), uri);
                return Optional.of(new StoredContent(asset, stored.blobStore()));
            }
            if (status == 404 || status == 410) {
                negativeCache.put(repo.getId(), path, proxy.negativeTtl(repo));
                log.info("source archive {} of {} is gone: {} answered {}", path, repo.getName(), uri, status);
                return Optional.empty();
            }
            throw sourceUnavailable(uri, "HTTP " + status);
        } catch (IOException | UncheckedIOException e) {
            throw sourceUnavailable(uri, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw sourceUnavailable(uri, "interrupted");
        }
    }

    /** The repository's timeouts without its upstream credentials, which belong to the upstream alone. */
    private static Repository withoutCredentials(Repository repo) {
        Repository anonymous = new Repository();
        anonymous.setId(repo.getId());
        anonymous.setName(repo.getName());
        anonymous.setConnectTimeoutSeconds(repo.getConnectTimeoutSeconds());
        anonymous.setReadTimeoutSeconds(repo.getReadTimeoutSeconds());
        return anonymous;
    }

    /** An archive whose bytes are not what the podspec's {@code sha256} promises is dropped, not cached forever. */
    private void verifySha256(Repository repo, StoredContent content, Podspec spec, URI uri) {
        Optional<String> expected = spec.source().map(s -> s.get("sha256")).filter(n -> n != null && n.isString())
                .map(n -> n.asString().strip());
        String actual = content.asset().getBlobSha256();
        if (expected.isEmpty() || actual == null || expected.get().equalsIgnoreCase(actual)) {
            return;
        }
        storage.delete(repo, content.asset().getPath());
        log.warn("source archive {} of {} from {} has SHA-256 {}, the podspec says {}; not cached",
                content.asset().getPath(), repo.getName(), uri, actual, expected.get());
        throw new CocoaPodsError(HttpStatus.BAD_GATEWAY, "upstream.checksumMismatch", "The archive downloaded from " + uri
                + " has SHA-256 " + actual + ", but the podspec's source.sha256 is " + expected.get());
    }

    private static CocoaPodsError sourceUnavailable(URI uri, String reason) {
        return new CocoaPodsError(HttpStatus.BAD_GATEWAY, "upstream.unavailable",
                "The source archive is not cached and downloading it from " + uri + " failed: " + reason);
    }

    /** {@code source.headers} entries that can safely be sent upstream, as a header map. */
    static Map<String, String> forwardedHeaders(List<String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String header : headers) {
            int colon = header.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = header.substring(0, colon).strip();
            String value = header.substring(colon + 1).strip();
            if (!TOKEN.matcher(name).matches() || NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT))
                    || value.chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
                continue;
            }
            result.putIfAbsent(name, value);
        }
        return result;
    }

    private boolean isDeprecated(Repository repo, String podspecPath) {
        Optional<StoredContent> list = storage.find(repo, CocoaPodsPath.DEPRECATED);
        if (list.isEmpty()) {
            return false;
        }
        String sha = list.get().asset().getBlobSha256();
        Cached<Set<String>> cached = deprecated.get(repo.getId());
        if (cached == null || !cached.sha256().equals(sha)) {
            Set<String> paths;
            try {
                paths = new HashSet<>(PodIndex.lines(list.get().readUtf8()));
            } catch (IOException e) {
                log.warn("cached {} of {} is unreadable: {}", CocoaPodsPath.DEPRECATED, repo.getName(), e.getMessage());
                paths = Set.of();
            }
            cached = new Cached<>(sha, paths);
            deprecated.put(repo.getId(), cached);
        }
        return cached.value().contains(podspecPath);
    }

    static Optional<Podspec> parse(StoredContent content) {
        try {
            return Podspec.tryParse(content.readAllBytes());
        } catch (IOException e) {
            log.warn("cached podspec {} is unreadable: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Links a cached podspec or archive to its pod version, so the component browser and search show it. */
    private StoredContent linkComponent(Repository repo, StoredContent content, String name, String version) {
        Asset asset = content.asset();
        if (asset.getComponentId() != null) {
            return content;
        }
        StorageService.ComponentCoordinates coordinates = new StorageService.ComponentCoordinates("", name, version);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                asset.setComponentId(storage.ensureComponent(repo, coordinates).getId());
                return new StoredContent(storage.save(asset), content.blobStore());
            } catch (RuntimeException e) {
                // a concurrent download of another file of the same version created the component first
                asset.setComponentId(null);
                log.debug("linking {} in {} to its component failed: {}", asset.getPath(), repo.getName(), e.getMessage());
            }
        }
        return content;
    }
}
