package dev.granary.format.terraform;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The upstream side of a proxy repository: discovers the remote registry's service endpoints through its
 * {@code .well-known/terraform.json}, fetches registry documents through the offline-first
 * {@link ProxyService} cache and resolves the absolute archive URLs those documents name.
 *
 * <p>Provider archives, checksum files and signatures live on other hosts (releases.hashicorp.com, GitHub
 * releases); the download document that names them is cached under its request path, and when a client
 * comes back for {@code files/<ns>/<type>/<version>/<file>} the original URL is looked up in those cached
 * documents and fetched into the cache under the {@code files/…} path. Module registries answer the
 * download endpoint with {@code 204} and an {@code X-Terraform-Get} header rather than a body, which the
 * generic cache cannot hold, so that one endpoint is fetched here and remembered as a small JSON document.
 */
final class UpstreamTerraform {

    /** Absolute base URLs (trailing slash) of the two registry services. */
    record Services(String providers, String modules) {
    }

    private static final Logger log = LoggerFactory.getLogger(UpstreamTerraform.class);
    private static final Map<String, String> ACCEPT_JSON = Map.of("Accept", "application/json");
    private static final String SOURCE_FIELD = "source";

    private final ProxyService proxy;
    private final UpstreamClient client;
    private final UpstreamHealth health;
    private final NegativeCache negativeCache;
    private final StorageService storage;
    private final ObjectMapper json;

    UpstreamTerraform(ProxyService proxy, UpstreamClient client, UpstreamHealth health, NegativeCache negativeCache,
                      StorageService storage, ObjectMapper json) {
        this.proxy = proxy;
        this.client = client;
        this.health = health;
        this.negativeCache = negativeCache;
        this.storage = storage;
        this.json = json;
    }

    // --- discovery ---------------------------------------------------------------------------

    /** The host name of the upstream registry, which is the host clients name in provider addresses. */
    static String upstreamHost(Repository repo) {
        try {
            URI uri = URI.create(repo.getRemoteUrl().trim());
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        } catch (IllegalArgumentException | NullPointerException e) {
            return "";
        }
    }

    /**
     * The service endpoints of the upstream registry, from its discovery document (cached as metadata so
     * a dead upstream still resolves to its last known endpoints); the conventional {@code /v1/…} paths
     * when the document is unavailable or does not name them.
     */
    Services services(Repository repo) {
        String remote = repo.getRemoteUrl() == null ? "" : repo.getRemoteUrl().trim();
        String providers = UrlPaths.join(remote, "v1/providers") + "/";
        String modules = UrlPaths.join(remote, "v1/modules") + "/";
        URI discovery;
        try {
            discovery = URI.create(UrlPaths.join(remote, TerraformPath.DISCOVERY_PATH));
        } catch (IllegalArgumentException e) {
            return new Services(providers, modules);
        }
        Optional<JsonNode> doc = proxy.fetch(repo, TerraformPath.DISCOVERY_PATH, discovery, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::readJson);
        if (doc.isEmpty()) {
            return new Services(providers, modules);
        }
        return new Services(
                resolve(remote, TerraformDocuments.text(doc.get(), "providers.v1")).orElse(providers),
                resolve(remote, TerraformDocuments.text(doc.get(), "modules.v1")).orElse(modules));
    }

    private static Optional<String> resolve(String remote, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            String resolved = URI.create(remote).resolve(value.trim()).toString();
            return Optional.of(resolved.endsWith("/") ? resolved : resolved + "/");
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // --- providers ---------------------------------------------------------------------------

    Optional<JsonNode> providerVersions(Repository repo, String ns, String type) {
        URI uri = uri(services(repo).providers(), ns + "/" + type + "/versions");
        return proxy.fetch(repo, TerraformLayout.providerVersionsPath(ns, type), uri, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::readJson);
    }

    /** The raw upstream download document for one platform; URLs still point at upstream. */
    Optional<ObjectNode> providerDownload(Repository repo, String ns, String type, String version, String os, String arch) {
        URI uri = uri(services(repo).providers(), ns + "/" + type + "/" + version + "/download/" + os + "/" + arch);
        return proxy.fetch(repo, TerraformLayout.providerDownloadPath(ns, type, version, os, arch), uri,
                        FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::readJson)
                .filter(JsonNode::isObject)
                .map(n -> (ObjectNode) n);
    }

    Optional<JsonNode> providerInfo(Repository repo, String ns, String type, String version) {
        URI uri = uri(services(repo).providers(), ns + "/" + type + (version == null ? "" : "/" + version));
        return proxy.fetch(repo, TerraformLayout.providerInfoPath(ns, type, version), uri, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::readJson);
    }

    /**
     * A provider archive, checksum file or signature by the file name the rewritten download document gave
     * the client. Cached forever once fetched; before that the original URL comes from a cached download
     * document of that version, fetched on demand when none mentions the file yet.
     */
    Optional<StoredContent> providerFile(Repository repo, String ns, String type, String version, String filename) {
        String cachePath = TerraformLayout.providerFilePath(ns, type, version, filename);
        Optional<StoredContent> cached = storage.find(repo, cachePath);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<String> url = upstreamUrl(repo, ns, type, version, filename);
        if (url.isEmpty()) {
            Optional<ProviderFilename> conventional = ProviderFilename.parse(filename);
            if (conventional.isPresent()) {
                providerDownload(repo, ns, type, version, conventional.get().os(), conventional.get().arch());
            } else if (ProviderFilename.isShaSums(filename) || ProviderFilename.isSignature(filename)) {
                // any platform's document names the checksum and signature files
                providerVersions(repo, ns, type).ifPresent(doc -> firstPlatform(doc, version)
                        .ifPresent(p -> providerDownload(repo, ns, type, version, p.os(), p.arch())));
            }
            url = upstreamUrl(repo, ns, type, version, filename);
        }
        if (url.isEmpty()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(url.get());
        } catch (IllegalArgumentException e) {
            log.warn("download document of {} {} {} names an invalid URL {}", ns, type, version, url.get());
            return Optional.empty();
        }
        return withComponent(repo, proxy.fetch(repo, cachePath, uri, FetchOptions.artifact(), Map.of()),
                new StorageService.ComponentCoordinates(ns, type, version));
    }

    private Optional<String> upstreamUrl(Repository repo, String ns, String type, String version, String filename) {
        for (Asset asset : storage.list(repo, TerraformLayout.providerDownloadPrefix(ns, type, version))) {
            Optional<String> url = storage.find(repo, asset.getPath())
                    .flatMap(this::readJson)
                    .flatMap(doc -> TerraformDocuments.upstreamUrlFor(doc, filename));
            if (url.isPresent()) {
                return url;
            }
        }
        return Optional.empty();
    }

    private Optional<TerraformDocuments.Platform> firstPlatform(JsonNode versions, String version) {
        for (JsonNode entry : versions.path("versions")) {
            if (version.equals(TerraformDocuments.text(entry, "version"))) {
                for (JsonNode p : entry.path("platforms")) {
                    String os = TerraformDocuments.text(p, "os");
                    String arch = TerraformDocuments.text(p, "arch");
                    if (os != null && arch != null) {
                        return Optional.of(new TerraformDocuments.Platform(os, arch));
                    }
                }
            }
        }
        return Optional.empty();
    }

    // --- modules -----------------------------------------------------------------------------

    Optional<JsonNode> moduleVersions(Repository repo, String ns, String name, String system) {
        URI uri = uri(services(repo).modules(), ns + "/" + name + "/" + system + "/versions");
        return proxy.fetch(repo, TerraformLayout.moduleVersionsPath(ns, name, system), uri, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::readJson);
    }

    Optional<JsonNode> moduleInfo(Repository repo, String ns, String name, String system, String version) {
        URI uri = uri(services(repo).modules(), ns + "/" + name + "/" + system + (version == null ? "" : "/" + version));
        return proxy.fetch(repo, TerraformLayout.moduleInfoPath(ns, name, system, version), uri, FetchOptions.index(), ACCEPT_JSON)
                .flatMap(this::readJson);
    }

    /**
     * The upstream {@code X-Terraform-Get} source of a module version. The registry answers {@code 204}
     * with the header only, so the value is remembered as a small cached document following the same
     * rules as other metadata: fresh copies are served, stale ones re-validated, and whatever is cached is
     * served while upstream is unreachable or the repository is offline.
     */
    Optional<String> moduleDownload(Repository repo, String ns, String name, String system, String version) {
        String path = TerraformLayout.moduleDownloadPath(ns, name, system, version);
        Optional<StoredContent> cached = storage.find(repo, path);
        Optional<String> cachedSource = cached.flatMap(this::readJson).map(d -> TerraformDocuments.text(d, SOURCE_FIELD));
        if (cached.isPresent() && isFresh(cached.get().asset(), repo)) {
            return cachedSource;
        }
        if (proxy.isOffline(repo)) {
            return cachedSource;
        }
        if (cached.isEmpty() && negativeCache.contains(repo.getId(), path)) {
            return Optional.empty();
        }
        if (!health.allowRequest(repo)) {
            return cachedSource;
        }
        URI uri = uri(services(repo).modules(), ns + "/" + name + "/" + system + "/" + version + "/download");
        try (UpstreamClient.Response response = client.getAbsolute(repo, uri, Map.of(), ACCEPT_JSON)) {
            int status = response.status();
            Optional<String> source = response.header("X-Terraform-Get").map(String::strip).filter(s -> !s.isEmpty())
                    .map(s -> resolveRelativeSource(uri, s));
            if ((status == 204 || status == 200) && source.isPresent()) {
                health.recordSuccess(repo);
                negativeCache.remove(repo.getId(), path);
                ObjectNode doc = json.createObjectNode();
                doc.put(SOURCE_FIELD, source.get());
                byte[] bytes = json.writeValueAsBytes(doc);
                StoredContent stored = storage.store(repo, path, new ByteArrayInputStream(bytes),
                        MediaType.APPLICATION_JSON_VALUE, Asset.Origin.CACHED, true);
                storage.touchVerified(stored.asset(), null, null);
                return source;
            }
            if (status == 404 || status == 410) {
                health.recordSuccess(repo);
                if (cached.isEmpty()) {
                    negativeCache.put(repo.getId(), path, proxy.negativeTtl(repo));
                }
                return cachedSource;
            }
            health.recordFailure(repo, "HTTP " + status + " for " + path);
            log.warn("upstream of {} answered {} without X-Terraform-Get for {}; serving cache={}", repo.getName(),
                    status, path, cachedSource.isPresent());
            return cachedSource;
        } catch (IOException e) {
            health.recordFailure(repo, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("upstream of {} unreachable for {} ({}); serving cache={}", repo.getName(), path, e.getMessage(),
                    cachedSource.isPresent());
            return cachedSource;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cachedSource;
        }
    }

    /**
     * A registry may answer with a source relative to its download endpoint ({@code /archives/x.tgz},
     * {@code ./x.tgz}, {@code ../x.tgz}); the CLIs resolve those against the endpoint URL, so the proxy does
     * the same before remembering it, otherwise the relative path would later be resolved against Granary.
     * Anything else is a go-getter address and is kept verbatim.
     */
    static String resolveRelativeSource(URI downloadEndpoint, String source) {
        if (!(source.startsWith("/") || source.startsWith("./") || source.startsWith("../"))) {
            return source;
        }
        try {
            return downloadEndpoint.resolve(source).toString();
        } catch (IllegalArgumentException e) {
            return source;
        }
    }

    private boolean isFresh(Asset asset, Repository repo) {
        Duration maxAge = proxy.metadataMaxAge(repo);
        return asset.getLastVerifiedAt() != null && maxAge != null && asset.getLastVerifiedAt().plus(maxAge).isAfter(Instant.now());
    }

    /** The module archive the upstream source names, cached forever under the {@code files/modules/…} path. */
    Optional<StoredContent> moduleFile(Repository repo, String ns, String name, String system, String version, String extension) {
        String cachePath = TerraformLayout.moduleFilePath(ns, name, system, version, extension);
        Optional<StoredContent> cached = storage.find(repo, cachePath);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ModuleSource.Archive> archive = moduleDownload(repo, ns, name, system, version).flatMap(ModuleSource::archive);
        if (archive.isEmpty() || !archive.get().extension().equals(extension)) {
            return Optional.empty();
        }
        return withComponent(repo, proxy.fetch(repo, cachePath, archive.get().url(), FetchOptions.artifact(), Map.of()),
                new StorageService.ComponentCoordinates(ns, name + "/" + system, version));
    }

    /**
     * Files a proxy caches join the component browser and search the way uploads do: provider files as
     * {@code (<ns>, <type>, <version>)}, module archives as {@code (<ns>, <name>/<system>, <version>)}. Linking is
     * best effort and never fails the download it accompanies.
     */
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

    // --- helpers -----------------------------------------------------------------------------

    private static URI uri(String base, String relative) {
        return URI.create(UrlPaths.join(base, relative));
    }

    Optional<JsonNode> readJson(StoredContent content) {
        try {
            return parse(content.readAllBytes());
        } catch (IOException e) {
            log.warn("cannot read cached {}: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JsonNode> parse(byte[] bytes) {
        try {
            JsonNode node = json.readTree(bytes);
            return node == null || node.isMissingNode() ? Optional.empty() : Optional.of(node);
        } catch (JacksonException e) {
            log.warn("upstream document is not JSON: {}", e.getOriginalMessage());
            return Optional.empty();
        }
    }


}
