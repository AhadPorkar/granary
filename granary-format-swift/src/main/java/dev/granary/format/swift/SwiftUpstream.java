package dev.granary.format.swift;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamCredentials;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The upstream side of a proxy repository: another Swift package registry (another Granary, Artifactory, a
 * registry service). The protocol maps one to one, so every document is fetched from the same path below the
 * remote URL and cached through the offline-first {@link ProxyService}: release lists, release metadata and
 * identifier lookups as metadata (re-validated after the repository's metadata max age, served stale while the
 * upstream is unreachable), manifests and source archives as immutable artifacts.
 *
 * <p>The default {@code Package.swift} is fetched here rather than through the generic cache because its
 * {@code Link} header lists the version-specific manifests, and a cache that keeps bodies only would lose it.
 * The variants are remembered in a small document next to the manifest, so an offline Granary still
 * advertises them.
 */
final class SwiftUpstream {

    private static final Logger log = LoggerFactory.getLogger(SwiftUpstream.class);
    private static final Map<String, String> ACCEPT_JSON = Map.of("Accept", SwiftAccept.JSON);
    private static final Map<String, String> ACCEPT_ZIP = Map.of("Accept", SwiftAccept.ZIP);
    private static final Map<String, String> ACCEPT_SWIFT = Map.of("Accept", SwiftAccept.SWIFT);

    /** A cached default manifest and the variants the upstream advertised with it. */
    record Manifest(StoredContent content, List<SwiftManifests.Alternate> alternates) {
    }

    private final ProxyService proxy;
    private final UpstreamClient client;
    private final UpstreamHealth health;
    private final NegativeCache negativeCache;
    private final StorageService storage;
    private final ObjectMapper json;

    SwiftUpstream(ProxyService proxy, UpstreamClient client, UpstreamHealth health, NegativeCache negativeCache,
                  StorageService storage, ObjectMapper json) {
        this.proxy = proxy;
        this.client = client;
        this.health = health;
        this.negativeCache = negativeCache;
        this.storage = storage;
        this.json = json;
    }

    // --- documents ----------------------------------------------------------------------------------

    Optional<ObjectNode> releases(Repository repo, SwiftIdentity id) {
        URI uri = uri(repo, id.scope() + "/" + id.name(), null);
        return proxy.fetch(repo, SwiftLayout.releasesPath(id), uri, FetchOptions.index(), ACCEPT_JSON).flatMap(this::readObject);
    }

    Optional<ObjectNode> release(Repository repo, SwiftIdentity id, String version) {
        URI uri = uri(repo, id.scope() + "/" + id.name() + "/" + version, null);
        return proxy.fetch(repo, SwiftLayout.releaseDocumentPath(id, version), uri, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::readObject);
    }

    /** The identifiers the upstream registers for {@code url}; the cache key is the normalised URL. */
    Optional<List<String>> identifiers(Repository repo, String url, String normalisedUrl) {
        URI uri = uri(repo, "identifiers", "url=" + URLEncoder.encode(url, StandardCharsets.UTF_8));
        return proxy.fetch(repo, SwiftLayout.identifiersPath(normalisedUrl), uri, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::readObject).map(SwiftDocuments::parseIdentifiers);
    }

    // --- files --------------------------------------------------------------------------------------

    Optional<StoredContent> archive(Repository repo, SwiftIdentity id, String version) {
        URI uri = uri(repo, id.scope() + "/" + id.name() + "/" + version + SwiftPath.ZIP_SUFFIX, null);
        return withComponent(repo, proxy.fetch(repo, SwiftLayout.archivePath(id, version), uri, FetchOptions.artifact(), ACCEPT_ZIP),
                SwiftHosted.coordinates(id, version));
    }

    /**
     * The default manifest with its variants. Cached forever once fetched (a release never changes); before
     * that the upstream is asked unless the repository is offline, the circuit breaker is open or the upstream
     * recently answered 404 for it.
     */
    Optional<Manifest> manifest(Repository repo, SwiftIdentity id, String version) {
        String path = SwiftLayout.manifestPath(id, version, SwiftManifests.MANIFEST);
        Optional<StoredContent> cached = storage.find(repo, path);
        if (cached.isPresent()) {
            return Optional.of(new Manifest(cached.get(), cachedAlternates(repo, id, version)));
        }
        if (proxy.isOffline(repo) || negativeCache.contains(repo.getId(), path) || !health.allowRequest(repo)) {
            return Optional.empty();
        }
        URI uri = uri(repo, id.scope() + "/" + id.name() + "/" + version + "/" + SwiftManifests.MANIFEST, null);
        try (UpstreamClient.Response response = client.getAbsolute(repo, uri, Map.of(), ACCEPT_SWIFT)) {
            int status = response.status();
            if (status == 200) {
                health.recordSuccess(repo);
                negativeCache.remove(repo.getId(), path);
                List<SwiftManifests.Alternate> alternates = SwiftManifests.parseLinkHeader(response.header("Link").orElse(null));
                // the variants first: a stored manifest without them would never be completed
                storeAlternates(repo, id, version, alternates);
                StoredContent stored;
                try (InputStream body = response.body()) {
                    stored = storage.store(repo, path, body, SwiftManifests.CONTENT_TYPE, Asset.Origin.CACHED, false,
                            SwiftHosted.coordinates(id, version));
                }
                storage.touchVerified(stored.asset(), response.header("ETag").orElse(null), response.header("Last-Modified").orElse(null));
                return Optional.of(new Manifest(stored, alternates));
            }
            if (status == 404 || status == 410) {
                health.recordSuccess(repo);
                negativeCache.put(repo.getId(), path, proxy.negativeTtl(repo));
                return Optional.empty();
            }
            health.recordFailure(repo, "HTTP " + status + " for " + path);
            log.warn("upstream of {} answered {} for {}", repo.getName(), status, path);
            return Optional.empty();
        } catch (IOException e) {
            health.recordFailure(repo, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("upstream of {} unreachable for {} ({})", repo.getName(), path, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** A version-specific manifest the upstream advertised, cached under its file name. */
    Optional<StoredContent> alternate(Repository repo, SwiftIdentity id, String version, SwiftManifests.Alternate alternate) {
        String path = SwiftLayout.manifestPath(id, version, alternate.filename());
        URI conventional = uri(repo, id.scope() + "/" + id.name() + "/" + version + "/" + SwiftManifests.MANIFEST,
                "swift-version=" + URLEncoder.encode(alternate.swiftVersion(), StandardCharsets.UTF_8));
        URI uri = trustedUrl(repo, alternate.url()).orElse(conventional);
        return proxy.fetch(repo, path, uri, FetchOptions.artifact(), ACCEPT_SWIFT);
    }

    private List<SwiftManifests.Alternate> cachedAlternates(Repository repo, SwiftIdentity id, String version) {
        List<SwiftManifests.Alternate> result = new ArrayList<>();
        storage.find(repo, SwiftLayout.manifestLinksPath(id, version)).flatMap(this::readObject).ifPresent(doc -> {
            for (JsonNode a : doc.path("alternates")) {
                String filename = SwiftDocuments.text(a, "filename");
                String tools = SwiftDocuments.text(a, "swiftToolsVersion");
                if (SwiftManifests.isAlternateFileName(filename) && tools != null) {
                    result.add(new SwiftManifests.Alternate(filename, tools, SwiftDocuments.text(a, "url")));
                }
            }
        });
        return result;
    }

    private void storeAlternates(Repository repo, SwiftIdentity id, String version, List<SwiftManifests.Alternate> alternates) {
        ObjectNode doc = json.createObjectNode();
        ArrayNode list = doc.putArray("alternates");
        for (SwiftManifests.Alternate a : alternates) {
            ObjectNode entry = list.addObject();
            entry.put("filename", a.filename());
            entry.put("swiftToolsVersion", a.toolsVersion());
            if (a.url() != null) {
                entry.put("url", a.url());
            }
        }
        storage.store(repo, SwiftLayout.manifestLinksPath(id, version), new ByteArrayInputStream(json.writeValueAsBytes(doc)),
                MediaType.APPLICATION_JSON_VALUE, Asset.Origin.CACHED, false);
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static URI uri(Repository repo, String path, String query) {
        String url = UrlPaths.join(repo.getRemoteUrl().trim(), path);
        return URI.create(query == null ? url : url + "?" + query);
    }

    /**
     * An absolute URL the upstream handed out, when the repository's upstream credentials may go there: the remote
     * URL's own origin, or a host listed in {@code upstream.credentialHosts}. Other URLs are not followed, the
     * conventional URL on the upstream is asked instead.
     */
    static Optional<URI> trustedUrl(Repository repo, String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI candidate = URI.create(url.trim());
            if (candidate.isAbsolute() && UpstreamCredentials.permitted(repo, candidate)) {
                return Optional.of(candidate);
            }
        } catch (IllegalArgumentException e) {
            log.debug("ignoring unusable upstream URL {}", url);
        }
        return Optional.empty();
    }

    /** Cached archives join the component browser the way uploads do; best effort, never failing the download. */
    private Optional<StoredContent> withComponent(Repository repo, Optional<StoredContent> content,
                                                  StorageService.ComponentCoordinates coordinates) {
        content.ifPresent(c -> {
            Asset asset = c.asset();
            if (asset.getComponentId() != null || asset.getId() == null) {
                return;
            }
            try {
                Component component = storage.ensureComponent(repo, coordinates);
                if (component != null) {
                    asset.setComponentId(component.getId());
                    storage.save(asset);
                }
            } catch (RuntimeException e) {
                log.warn("cannot link {} in {} to component {}: {}", asset.getPath(), repo.getName(), coordinates, e.getMessage());
            }
        });
        return content;
    }

    Optional<ObjectNode> readObject(StoredContent content) {
        try {
            JsonNode node = json.readTree(content.readAllBytes());
            return node != null && node.isObject() ? Optional.of((ObjectNode) node) : Optional.empty();
        } catch (IOException | JacksonException e) {
            log.warn("cannot read cached {}: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }
}
