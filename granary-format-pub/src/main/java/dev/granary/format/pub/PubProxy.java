package dev.granary.format.pub;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proxy repositories in front of pub.dev or any other repository speaking the v2 API. Listings and advisories
 * are cached as metadata (re-validated after the repository's metadata max age, served stale while the
 * upstream is unreachable); archives are cached forever once fetched and checked against the
 * {@code archive_sha256} the listing promises, so a corrupt download never enters the cache.
 *
 * <p>Archives are fetched from the {@code archive_url} the upstream listing names (pub.dev hands out
 * {@code https://pub.dev/api/archives/<name>-<version>.tar.gz}). Proxies with upstream credentials only follow
 * URLs on the upstream's own host, because the credentials go with every upstream request; elsewhere they fall
 * back to the conventional {@code <remote>/packages/<name>/versions/<version>.tar.gz}.
 */
@Component
public class PubProxy {

    private static final Logger log = LoggerFactory.getLogger(PubProxy.class);
    static final Map<String, String> ACCEPT = Map.of("Accept", PubResponses.PUB_V2);

    private final ProxyService proxy;
    private final StorageService storage;
    private final UpstreamClient upstream;
    private final ObjectMapper mapper;

    public PubProxy(ProxyService proxy, StorageService storage, UpstreamClient upstream, ObjectMapper mapper) {
        this.proxy = proxy;
        this.storage = storage;
        this.upstream = upstream;
        this.mapper = mapper;
    }

    /** The upstream listing (raw: archive URLs still point upstream), from cache or upstream. */
    public Optional<ObjectNode> listing(Repository repo, String name) {
        return proxy.fetch(repo, PubPath.listingPath(name), FetchOptions.index(), ACCEPT).flatMap(this::parse);
    }

    /** The upstream advisories of a package; an empty document when the repository does not pass them on. */
    public Optional<ObjectNode> advisories(Repository repo, String name) {
        if (!PubSettings.advisories(repo)) {
            return Optional.of(PubListing.noAdvisories());
        }
        return proxy.fetch(repo, PubPath.advisoriesPath(name), FetchOptions.index(), ACCEPT).flatMap(this::parse);
    }

    /**
     * Whether a listing may announce {@code advisoriesUpdated}: the client then asks for the advisories, and
     * while the upstream is out of reach that only works if they are cached. Not announcing them spares the
     * client seven retries and a warning per package.
     */
    public boolean advisoriesAvailable(Repository repo, String name) {
        if (!PubSettings.advisories(repo)) {
            return false;
        }
        if (storage.exists(repo, PubPath.advisoriesPath(name))) {
            return true;
        }
        if (proxy.isOffline(repo)) {
            return false;
        }
        UpstreamHealth.Status status = proxy.status(repo);
        return status == null || status.reachable() && status.consecutiveFailures() == 0;
    }

    /** The archive of one version: from cache, else downloaded from upstream, verified and cached. */
    public Optional<StoredContent> archive(Repository repo, String name, String version) {
        String path = PubPath.archivePath(name, version);
        Optional<StoredContent> cached = storage.find(repo, path);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<PubListing.Entry> entry = storage.find(repo, PubPath.listingPath(name))
                .flatMap(this::parse)
                .flatMap(doc -> PubListing.find(doc, version));
        if (entry.isEmpty()) {
            entry = listing(repo, name).flatMap(doc -> PubListing.find(doc, version));
        }
        URI uri = upstreamArchiveUri(repo, name, version, entry.flatMap(PubListing.Entry::archiveUrl).orElse(null));
        Optional<StoredContent> fetched = proxy.fetch(repo, path, uri, FetchOptions.artifact(), Map.of());
        if (fetched.isEmpty()) {
            return fetched;
        }
        Optional<String> expected = entry.flatMap(PubListing.Entry::sha256);
        if (expected.isPresent() && !expected.get().equalsIgnoreCase(fetched.get().asset().getBlobSha256())) {
            storage.delete(repo, path);
            log.warn("upstream archive {} of {} has sha256 {} but the listing promises {}; not cached",
                    path, repo.getName(), fetched.get().asset().getBlobSha256(), expected.get());
            throw new PubError(HttpStatus.BAD_GATEWAY, PubError.UPSTREAM, "The archive of " + name + " " + version
                    + " downloaded from the upstream of '" + repo.getName() + "' does not match its archive_sha256");
        }
        return Optional.of(linkComponent(repo, fetched.get(), name, entry.map(e -> e.version().text()).orElse(version)));
    }

    /** Where upstream keeps an archive: the listing's {@code archive_url} when it is safe to follow. */
    URI upstreamArchiveUri(Repository repo, String name, String version, String archiveUrl) {
        URI conventional = upstream.upstreamUri(repo, PubPath.archivePath(name, version));
        if (archiveUrl == null || archiveUrl.isBlank()) {
            return conventional;
        }
        URI candidate;
        try {
            URI remote = new URI(repo.getRemoteUrl().trim());
            candidate = remote.resolve(new URI(archiveUrl.trim()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return conventional;
        }
        String scheme = candidate.getScheme() == null ? "" : candidate.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || candidate.getHost() == null) {
            return conventional;
        }
        if (sameHost(candidate, conventional) || !hasCredentials(repo)) {
            return candidate;
        }
        return conventional;
    }

    private static boolean sameHost(URI a, URI b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean hasCredentials(Repository repo) {
        return repo.getRemoteToken() != null && !repo.getRemoteToken().isBlank()
                || repo.getRemoteUsername() != null && !repo.getRemoteUsername().isBlank();
    }

    /** Cached archives belong to their component, so the UI lists cached package versions too. */
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
                // a concurrent download of the same version created the component first
                asset.setComponentId(null);
                log.debug("linking {} in {} to its component failed: {}", asset.getPath(), repo.getName(), e.getMessage());
            }
        }
        return content;
    }

    Optional<ObjectNode> parse(StoredContent content) {
        try {
            JsonNode node = mapper.readTree(content.readAllBytes());
            if (node != null && node.isObject()) {
                return Optional.of((ObjectNode) node);
            }
            log.warn("cached document {} is not a JSON object", content.asset().getPath());
        } catch (IOException | JacksonException e) {
            log.warn("cached document {} is unreadable: {}", content.asset().getPath(), e.getMessage());
        }
        return Optional.empty();
    }
}
