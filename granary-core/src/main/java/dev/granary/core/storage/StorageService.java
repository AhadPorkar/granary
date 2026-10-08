package dev.granary.core.storage;

import dev.granary.core.blob.BlobRef;
import dev.granary.core.blob.BlobStore;
import dev.granary.core.blob.BlobStoreRegistry;
import dev.granary.core.dao.AssetDao;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Format-agnostic persistence of files inside a repository: writes the bytes to the repository's blob
 * store and records an {@link Asset} row. Every format handler builds on this.
 */
@Service
public class StorageService {

    private final AssetDao assets;
    private final ComponentDao components;
    private final BlobStoreRegistry blobStores;

    public StorageService(AssetDao assets, ComponentDao components, BlobStoreRegistry blobStores) {
        this.assets = assets;
        this.components = components;
        this.blobStores = blobStores;
    }

    @Transactional(readOnly = true)
    public Optional<StoredContent> find(Repository repo, String path) {
        return assets.findByRepositoryIdAndPath(repo.getId(), normalise(path))
                .map(asset -> new StoredContent(asset, blobStores.require(repo.getBlobStore())));
    }

    @Transactional(readOnly = true)
    public boolean exists(Repository repo, String path) {
        return assets.findByRepositoryIdAndPath(repo.getId(), normalise(path)).isPresent();
    }

    @Transactional(readOnly = true)
    public List<Asset> list(Repository repo, String prefix) {
        return assets.findByRepositoryIdAndPathStartingWithOrderByPathAsc(repo.getId(), normalise(prefix));
    }

    /** Stores or replaces the file at {@code path}. The caller closes {@code in}. */
    @Transactional
    public StoredContent store(Repository repo, String path, InputStream in, String contentType,
                               Asset.Origin origin, boolean metadata) {
        BlobStore store = blobStores.require(repo.getBlobStore());
        BlobRef ref;
        try {
            ref = store.store(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write blob for " + path, e);
        }
        return record(repo, path, ref, contentType, origin, metadata, null);
    }

    /** Variant of {@link #store} that also links the asset to a component (created on demand). */
    @Transactional
    public StoredContent store(Repository repo, String path, InputStream in, String contentType,
                               Asset.Origin origin, boolean metadata, ComponentCoordinates coordinates) {
        BlobStore store = blobStores.require(repo.getBlobStore());
        BlobRef ref;
        try {
            ref = store.store(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write blob for " + path, e);
        }
        UUID componentId = coordinates == null ? null : ensureComponent(repo, coordinates).getId();
        return record(repo, path, ref, contentType, origin, metadata, componentId);
    }

    private StoredContent record(Repository repo, String path, BlobRef ref, String contentType,
                                 Asset.Origin origin, boolean metadata, UUID componentId) {
        String normalised = normalise(path);
        Asset asset = assets.findByRepositoryIdAndPath(repo.getId(), normalised).orElseGet(Asset::new);
        asset.setRepositoryId(repo.getId());
        asset.setPath(normalised);
        asset.setBlobSha256(ref.sha256());
        asset.setSize(ref.size());
        asset.setContentType(contentType == null ? ContentTypes.guess(normalised) : contentType);
        asset.setOrigin(origin);
        asset.setMetadata(metadata);
        asset.setLastVerifiedAt(Instant.now());
        if (componentId != null) {
            asset.setComponentId(componentId);
        }
        asset = assets.save(asset);
        return new StoredContent(asset, blobStores.require(repo.getBlobStore()));
    }

    @Transactional
    public Component ensureComponent(Repository repo, ComponentCoordinates c) {
        return components.findByRepositoryIdAndNamespaceAndNameAndVersion(
                        repo.getId(), c.namespace(), c.name(), c.version())
                .orElseGet(() -> {
                    Component component = new Component();
                    component.setRepositoryId(repo.getId());
                    component.setFormat(repo.getFormat());
                    component.setNamespace(c.namespace());
                    component.setName(c.name());
                    component.setVersion(c.version());
                    return components.save(component);
                });
    }

    @Transactional
    public Asset touchVerified(Asset asset, String etag, String lastModified) {
        asset.setLastVerifiedAt(Instant.now());
        if (etag != null) {
            asset.setUpstreamEtag(etag);
        }
        if (lastModified != null) {
            asset.setUpstreamLastModified(lastModified);
        }
        return assets.save(asset);
    }

    @Transactional
    public Asset save(Asset asset) {
        return assets.save(asset);
    }

    /** Counted in its own transaction so a failed download response never rolls back the counter. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDownload(Asset asset) {
        assets.recordDownload(asset.getId(), Instant.now());
    }

    @Transactional
    public boolean delete(Repository repo, String path) {
        Optional<Asset> asset = assets.findByRepositoryIdAndPath(repo.getId(), normalise(path));
        if (asset.isEmpty()) {
            return false;
        }
        Asset a = asset.get();
        assets.delete(a);
        if (a.getComponentId() != null && assets.findByComponentId(a.getComponentId()).isEmpty()) {
            components.deleteById(a.getComponentId());
        }
        return true;
    }

    /** Removes a component and all of its assets. Returns the number of assets deleted. */
    @Transactional
    public int deleteComponent(UUID componentId) {
        List<Asset> list = assets.findByComponentId(componentId);
        assets.deleteAll(list);
        components.deleteById(componentId);
        return list.size();
    }

    public BlobStore blobStoreOf(Repository repo) {
        return blobStores.require(repo.getBlobStore());
    }

    /** Repository-relative paths never start with a slash and never contain {@code ..} segments. */
    public static String normalise(String path) {
        if (path == null) {
            return "";
        }
        String p = path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.contains("\\") || p.contains("/../") || p.startsWith("../") || p.endsWith("/..") || p.equals("..")) {
            throw new IllegalArgumentException("illegal path: " + path);
        }
        return p;
    }

    /** Coordinates used to attach an asset to a {@link Component}. */
    public record ComponentCoordinates(String namespace, String name, String version) {
        public ComponentCoordinates {
            namespace = namespace == null ? "" : namespace;
        }
    }
}
