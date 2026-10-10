package dev.granary.format.helm;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The upstream side of a proxy repository. {@code index.yaml} is cached as metadata (re-validated after the
 * repository's metadata max age, served stale while upstream is unreachable); archives and provenance files are
 * cached forever under the repository path the rewritten index gives them, and fetched from wherever the upstream
 * index says the first time they are requested.
 */
@Component
public class HelmProxy {

    private static final Logger log = LoggerFactory.getLogger(HelmProxy.class);
    private static final java.util.regex.Pattern SHA256 = java.util.regex.Pattern.compile("[0-9a-fA-F]{64}");
    private static final Map<String, String> ACCEPT_YAML = Map.of("Accept", "application/x-yaml, application/yaml, text/yaml, */*");

    private final ProxyService proxy;
    private final StorageService storage;
    private final BoundedCache<String, IndexSnapshot> snapshots;

    @org.springframework.beans.factory.annotation.Autowired
    public HelmProxy(ProxyService proxy, StorageService storage) {
        this(proxy, storage, new BoundedCache<>(16, 200_000, IndexSnapshot::weight));
    }

    HelmProxy(ProxyService proxy, StorageService storage, BoundedCache<String, IndexSnapshot> snapshots) {
        this.proxy = proxy;
        this.storage = storage;
        this.snapshots = snapshots;
    }

    static Optional<URI> indexUri(Repository repo) {
        return UpstreamUrls.documentUrl(repo.getRemoteUrl(), HelmPath.INDEX_FILE);
    }

    /**
     * The upstream index with download paths rewritten to this repository; empty when neither upstream nor the
     * cache has one.
     *
     * @throws HelmError 502 when upstream serves something that is not a chart repository index
     */
    public Optional<IndexSnapshot> snapshot(Repository repo) {
        Optional<URI> indexUri = indexUri(repo);
        if (indexUri.isEmpty()) {
            log.warn("the remote URL of {} is not a usable chart repository URL: {}", repo.getName(), repo.getRemoteUrl());
            return Optional.empty();
        }
        Optional<StoredContent> content = proxy.fetch(repo, HelmPath.INDEX_FILE, indexUri.get(), FetchOptions.index(), ACCEPT_YAML);
        if (content.isEmpty()) {
            return Optional.empty();
        }
        StoredContent cached = content.get();
        String key = "proxy:" + repo.getId() + ":" + UpstreamUrls.repositoryUrl(repo.getRemoteUrl()) + ":" + cached.asset().getBlobSha256();
        IndexSnapshot snapshot = snapshots.getIfPresent(key);
        if (snapshot != null) {
            return Optional.of(snapshot);
        }
        HelmIndex upstream;
        try {
            upstream = HelmIndex.parse(cached.readAllBytes());
        } catch (HelmYaml.InvalidYamlException e) {
            log.warn("upstream index of {} is not a chart repository index: {}", repo.getName(), e.getMessage());
            throw new HelmError(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "the upstream of '" + repo.getName() + "' did not serve a valid index.yaml: " + e.getMessage());
        } catch (IOException e) {
            log.warn("cached index of {} is unreadable: {}", repo.getName(), e.getMessage());
            return Optional.empty();
        }
        UpstreamUrls.Mapping mapping = UpstreamUrls.map(upstream, UpstreamUrls.repositoryUrl(repo.getRemoteUrl()));
        snapshot = new IndexSnapshot(key, mapping.local(), Instant.now(), mapping);
        snapshots.put(key, snapshot);
        return Optional.of(snapshot);
    }

    /**
     * An archive or provenance file by its repository path: from the cache without any network access when it is
     * there, otherwise from the upstream location the index names (and then cached forever).
     */
    public Optional<StoredContent> file(Repository repo, String path) {
        Optional<StoredContent> cached = storage.find(repo, path);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<IndexSnapshot> snapshot;
        try {
            snapshot = snapshot(repo);
        } catch (HelmError e) {
            return Optional.empty();
        }
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        UpstreamUrls.Mapping mapping = snapshot.get().mapping();
        Optional<String> url = mapping.upstreamUrl(path);
        if (url.isEmpty()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(url.get());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Optional<StoredContent> fetched = proxy.fetch(repo, path, uri, FetchOptions.artifact(), Map.of());
        if (fetched.isPresent() && !path.endsWith(HelmPath.PROV_SUFFIX)) {
            verifyArchive(repo, path, fetched.get(), mapping.local());
        }
        return fetched.map(content -> linkComponent(repo, content, mapping.target(path)));
    }

    /**
     * Archives are cached forever, so a response that is not a gzip stream at all (an error or login page served
     * with status 200) is dropped again instead of being served. A digest that differs from the index is only
     * logged: helm does not check it either, and public repositories whose release assets were rebuilt after the
     * index was generated (helm/examples among them) would otherwise become unusable.
     */
    private void verifyArchive(Repository repo, String path, StoredContent content, HelmIndex index) {
        boolean gzip;
        try (InputStream in = content.open()) {
            gzip = in.read() == 0x1f && in.read() == 0x8b;
        } catch (IOException e) {
            gzip = false;
        }
        if (!gzip) {
            storage.delete(repo, path);
            log.warn("upstream of {} served {} as something that is not a chart archive; not cached", repo.getName(), path);
            throw new HelmError(org.springframework.http.HttpStatus.BAD_GATEWAY, "the upstream of '" + repo.getName()
                    + "' served " + path + " as something that is not a gzip-compressed chart archive");
        }
        String actual = content.asset().getBlobSha256();
        index.all().stream()
                .filter(entry -> entry.urls().contains(path))
                .map(ChartEntry::digest)
                .filter(digest -> digest != null && SHA256.matcher(digest).matches() && !digest.equalsIgnoreCase(actual))
                .findFirst()
                .ifPresent(digest -> log.warn("upstream of {} served {} with sha256 {}, but its index states {}",
                        repo.getName(), path, actual, digest));
    }

    /** The archive or provenance file of a chart version, under whatever path the index gives it. */
    public Optional<StoredContent> file(Repository repo, String name, String version, boolean provenance) {
        Optional<IndexSnapshot> snapshot;
        try {
            snapshot = snapshot(repo);
        } catch (HelmError e) {
            return Optional.empty();
        }
        return snapshot.flatMap(s -> s.mapping().archivePath(name, version))
                .flatMap(path -> file(repo, provenance ? path + HelmPath.PROV_SUFFIX : path));
    }

    public Optional<StoredContent> artifactHubMetadata(Repository repo) {
        return UpstreamUrls.documentUrl(repo.getRemoteUrl(), HelmPath.ARTIFACTHUB_FILE)
                .flatMap(uri -> proxy.fetch(repo, HelmPath.ARTIFACTHUB_FILE, uri, FetchOptions.index(), ACCEPT_YAML));
    }

    /** Cached archives show up as components, so the UI's browser and search list what was pulled through the proxy. */
    private StoredContent linkComponent(Repository repo, StoredContent content, Optional<UpstreamUrls.Target> target) {
        Asset asset = content.asset();
        if (asset.getComponentId() != null || target.isEmpty()) {
            return content;
        }
        StorageService.ComponentCoordinates coordinates =
                new StorageService.ComponentCoordinates("", target.get().name(), target.get().version());
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                asset.setComponentId(storage.ensureComponent(repo, coordinates).getId());
                return new StoredContent(storage.save(asset), content.blobStore());
            } catch (RuntimeException e) {
                // a concurrent download of the same version's other file created the component first
                asset.setComponentId(null);
                log.debug("linking {} in {} to its component failed: {}", asset.getPath(), repo.getName(), e.getMessage());
            }
        }
        return content;
    }
}
