package dev.granary.format.nuget;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The upstream side of a proxy repository: discovers the remote feed's endpoints through its service
 * index and fetches documents and packages through the offline-first {@link ProxyService} cache.
 *
 * <p>A V3 feed hands out absolute resource URLs that may live on other hosts (nuget.org's packages come
 * from a CDN), so every upstream URL is resolved from the cached service index and cached under a local
 * path of this class's choosing rather than under the request path. Only search bypasses the cache: a
 * search result is a transient view that is worthless once stale, and an unreachable upstream simply
 * yields no hits.
 */
final class UpstreamNuGet {

    private static final Logger log = LoggerFactory.getLogger(UpstreamNuGet.class);
    private static final Map<String, String> ACCEPT_JSON = Map.of("Accept", "application/json");

    private final ProxyService proxy;
    private final UpstreamClient client;
    private final ObjectMapper json;

    UpstreamNuGet(ProxyService proxy, UpstreamClient client, ObjectMapper json) {
        this.proxy = proxy;
        this.client = client;
        this.json = json;
    }

    /** The upstream service index, cached as metadata so a dead upstream still resolves to its last known endpoints. */
    Optional<JsonNode> serviceIndex(Repository repo) {
        URI uri;
        try {
            uri = URI.create(repo.getRemoteUrl().trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        return proxy.fetch(repo, NuGetLayout.SERVICE_INDEX_CACHE_PATH, uri, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::readJson);
    }

    /** The URL of the preferred resource of the given types, with a trailing slash for base addresses. */
    Optional<String> resource(Repository repo, List<String> types) {
        return serviceIndex(repo).flatMap(index -> ServiceIndex.resource(index, types));
    }

    private Optional<String> baseAddress(Repository repo, List<String> types) {
        return resource(repo, types).map(url -> url.endsWith("/") ? url : url + "/");
    }

    /** All versions upstream lists for a package, sorted; empty when unknown both upstream and in the cache. */
    Optional<List<NuGetVersion>> flatVersions(Repository repo, String id) {
        Optional<String> base = baseAddress(repo, List.of(ServiceIndex.PACKAGE_BASE_ADDRESS));
        if (base.isEmpty()) {
            return Optional.empty();
        }
        String lower = NuGetLayout.lower(id);
        URI uri = URI.create(base.get() + lower + "/index.json");
        return proxy.fetch(repo, NuGetLayout.flatIndexCachePath(lower), uri, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::readJson)
                .map(doc -> {
                    TreeSet<NuGetVersion> versions = new TreeSet<>();
                    for (JsonNode v : doc.path("versions")) {
                        if (v.isString()) {
                            NuGetVersion.tryParse(v.stringValue()).ifPresent(versions::add);
                        }
                    }
                    return new ArrayList<>(versions);
                });
    }

    /** A package, manifest or symbol file from the upstream flat container, cached forever once fetched. */
    Optional<StoredContent> flatFile(Repository repo, String id, String version, NuGetPath.Kind kind) {
        Optional<String> base = baseAddress(repo, List.of(ServiceIndex.PACKAGE_BASE_ADDRESS));
        if (base.isEmpty()) {
            return Optional.empty();
        }
        String lower = NuGetLayout.lower(id);
        String v = NuGetLayout.lower(version);
        String cachePath;
        String fileName;
        switch (kind) {
            case PACKAGE -> {
                cachePath = NuGetLayout.nupkgPath(lower, v);
                fileName = lower + "." + v + ".nupkg";
            }
            case NUSPEC -> {
                cachePath = NuGetLayout.nuspecPath(lower, v);
                fileName = lower + ".nuspec";
            }
            case SYMBOLS -> {
                cachePath = NuGetLayout.snupkgPath(lower, v);
                fileName = lower + "." + v + ".snupkg";
            }
            default -> {
                return Optional.empty();
            }
        }
        URI uri = URI.create(base.get() + lower + "/" + v + "/" + fileName);
        return proxy.fetch(repo, cachePath, uri, FetchOptions.artifact(), Map.of());
    }

    /**
     * The upstream registration index with every page inlined. Pages that upstream only references are
     * fetched and cached one by one; a page that is neither reachable nor cached is left out, so an
     * offline server still answers with the versions it knows.
     */
    Optional<ObjectNode> registrationIndex(Repository repo, String id) {
        Optional<String> base = baseAddress(repo, ServiceIndex.REGISTRATION_TYPES);
        if (base.isEmpty()) {
            return Optional.empty();
        }
        String lower = NuGetLayout.lower(id);
        URI uri = URI.create(base.get() + lower + "/index.json");
        Optional<JsonNode> fetched = proxy.fetch(repo, NuGetLayout.registrationCachePath(lower), uri,
                FetchOptions.index(), ACCEPT_JSON).flatMap(this::readJson);
        if (fetched.isEmpty() || !fetched.get().isObject()) {
            return Optional.empty();
        }
        ObjectNode index = (ObjectNode) fetched.get().deepCopy();
        JsonNode pages = index.path("items");
        if (!pages.isArray()) {
            return Optional.of(index);
        }
        ArrayNode inlined = json.createArrayNode();
        int n = 0;
        for (JsonNode page : pages) {
            n++;
            if (!page.isObject()) {
                continue;
            }
            if (page.has("items") || !page.path("@id").isString()) {
                inlined.add(page);
                continue;
            }
            URI pageUri;
            try {
                pageUri = URI.create(page.path("@id").stringValue());
            } catch (IllegalArgumentException e) {
                continue;
            }
            Optional<JsonNode> fetchedPage = proxy.fetch(repo, NuGetLayout.registrationPageCachePath(lower, n), pageUri,
                    FetchOptions.index(), ACCEPT_JSON).flatMap(this::readJson);
            if (fetchedPage.isPresent() && fetchedPage.get().path("items").isArray()) {
                ObjectNode copy = (ObjectNode) page.deepCopy();
                copy.set("items", fetchedPage.get().path("items"));
                inlined.add(copy);
            } else {
                log.warn("registration page {} of {} is unavailable upstream and not cached; omitting it", pageUri, lower);
            }
        }
        index.set("items", inlined);
        return Optional.of(index);
    }

    /** Forwards a search to the upstream search service, un-cached; empty when offline or unreachable. */
    Optional<JsonNode> search(Repository repo, SearchResponse.Query query) {
        if (proxy.isOffline(repo) || !proxy.status(repo).reachable()) {
            return Optional.empty();
        }
        Optional<String> service = resource(repo, ServiceIndex.SEARCH_TYPES);
        if (service.isEmpty()) {
            return Optional.empty();
        }
        String url = service.get();
        URI uri;
        try {
            uri = URI.create(url + (url.contains("?") ? "&" : "?") + query.toQueryString());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        try (UpstreamClient.Response response = client.getAbsolute(repo, uri, Map.of(), ACCEPT_JSON)) {
            if (response.status() != 200) {
                log.warn("upstream search of {} answered {} for {}", repo.getName(), response.status(), uri);
                return Optional.empty();
            }
            boolean gzip = response.header("Content-Encoding").map(v -> v.toLowerCase().contains("gzip")).orElse(false);
            try (InputStream body = response.body()) {
                byte[] bytes = body.readAllBytes();
                return parse(gzip ? gunzip(bytes) : inflateIfGzip(bytes));
            }
        } catch (IOException e) {
            log.warn("upstream search of {} failed for {}: {}", repo.getName(), uri, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Parses cached JSON, transparently inflating a body that upstream delivered gzip-encoded. */
    Optional<JsonNode> readJson(StoredContent content) {
        try {
            return parse(inflateIfGzip(content.readAllBytes()));
        } catch (IOException e) {
            log.warn("cannot read cached {}: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JsonNode> parse(byte[] bytes) {
        try {
            return Optional.of(json.readTree(bytes));
        } catch (JacksonException e) {
            log.warn("upstream document is not JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The JDK client never asks for gzip, but a CDN may send it anyway; the magic number tells. */
    static byte[] inflateIfGzip(byte[] bytes) throws IOException {
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            return gunzip(bytes);
        }
        return bytes;
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        }
    }
}
