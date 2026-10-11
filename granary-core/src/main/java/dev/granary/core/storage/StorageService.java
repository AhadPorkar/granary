package dev.granary.core.storage;

import dev.granary.core.blob.BlobRef;
import dev.granary.core.blob.BlobStore;
import dev.granary.core.blob.BlobStoreRegistry;
import dev.granary.core.blob.PinnedBlob;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Format-agnostic persistence of files inside a repository: writes the bytes to the repository's blob
 * store and records an {@link Asset} row. Every format handler builds on this.
 *
 * <p>Concurrent writers are safe: every write of an asset path or a component takes the matching
 * {@link StorageLocks} key and keeps it until its transaction completes, so two requests storing the same new path
 * end up with one asset row (the last writer wins) and two requests storing different files of the same new
 * component end up linked to one component row. Keys are always taken path first, component second.
 *
 * <p>{@link #store} writes the bytes before it opens its transaction and takes the path's key before it opens its
 * transaction too, so neither a long upload nor waiting for a concurrent writer holds a database connection. Called
 * inside a caller's transaction, it joins that transaction and keeps the key until the caller commits. The blob it
 * wrote stays pinned against blob store compaction until the row referencing it has been committed (or the write has
 * failed), since until then no database row says that the blob is used.
 *
 * <p>Outside a caller's transaction every method follows the same order, which keeps a small connection pool from
 * deadlocking: a path key is taken before the connection, a component key after it. So a thread holding a
 * connection only ever waits for a component key, and the holder of a component key already has its connection and
 * waits for nothing else. (Were a path key taken after the connection by some writers and before it by others, the
 * former could hold every pooled connection while waiting for the key whose holder waits for a connection.)
 */
@Service
public class StorageService {

    private final AssetDao assets;
    private final ComponentDao components;
    private final BlobStoreRegistry blobStores;
    private final StorageLocks locks;
    private final TransactionTemplate transactions;

    public StorageService(AssetDao assets, ComponentDao components, BlobStoreRegistry blobStores, StorageLocks locks,
                          PlatformTransactionManager transactionManager) {
        this.assets = assets;
        this.components = components;
        this.blobStores = blobStores;
        this.locks = locks;
        this.transactions = new TransactionTemplate(transactionManager);
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

    /**
     * Stores or replaces the file at {@code path}. The caller closes {@code in}. When several requests store the same
     * path at the same time, each of them succeeds and the last one to commit wins. The new row is committed when
     * this method returns, unless a caller's transaction encloses the call.
     */
    public StoredContent store(Repository repo, String path, InputStream in, String contentType,
                               Asset.Origin origin, boolean metadata) {
        return store(repo, path, in, contentType, origin, metadata, null);
    }

    /** Variant of {@link #store} that also links the asset to a component (created on demand). */
    public StoredContent store(Repository repo, String path, InputStream in, String contentType,
                               Asset.Origin origin, boolean metadata, ComponentCoordinates coordinates) {
        String normalised = normalise(path);
        if (coordinates != null) {
            validate(coordinates);
        }
        // the bytes go to the content-addressed store first, outside any lock and transaction: identical concurrent
        // writes are safe there, and a slow upload must not hold a database connection. The blob stays pinned (out
        // of compaction's reach) until the row that references it is committed or the write has failed.
        PinnedBlob blob = writeBlob(repo, normalised, in);
        boolean releasedLater = false;
        try {
            // key first, transaction second: without an enclosing transaction the key is released after the commit
            StoredContent stored = locks.withKey(assetKey(repo, normalised), () -> transactions.execute(status -> {
                UUID componentId = coordinates == null ? null : ensureComponent(repo, coordinates).getId();
                return record(repo, normalised, blob.ref(), contentType, origin, metadata, componentId);
            }));
            releasedLater = releaseWhenTheTransactionCompletes(blob);
            return stored;
        } finally {
            if (!releasedLater) {
                blob.close();
            }
        }
    }

    private PinnedBlob writeBlob(Repository repo, String path, InputStream in) {
        BlobStore store = blobStores.require(repo.getBlobStore());
        try {
            return store.storePinned(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write blob for " + path, e);
        }
    }

    /**
     * Inside a caller's transaction the row is committed (or rolled back) with that transaction, so the blob stays
     * pinned until then. Returns whether the release was handed over to the transaction.
     */
    private static boolean releaseWhenTheTransactionCompletes(PinnedBlob blob) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                blob.close();
            }
        });
        return true;
    }

    /** Find-or-create of the asset row; the caller holds the path's lock. */
    private StoredContent record(Repository repo, String normalised, BlobRef ref, String contentType,
                                 Asset.Origin origin, boolean metadata, UUID componentId) {
        Asset asset = assets.findByRepositoryIdAndPath(repo.getId(), normalised).orElseGet(Asset::new);
        asset.setRepositoryId(repo.getId());
        asset.setPath(normalised);
        asset.setBlobSha256(ref.sha256());
        asset.setSize(ref.size());
        asset.setContentType(AssetSanitiser.contentType(contentType, normalised));
        asset.setOrigin(origin);
        asset.setMetadata(metadata);
        asset.setLastVerifiedAt(Instant.now());
        if (componentId != null) {
            asset.setComponentId(componentId);
        }
        asset = assets.save(asset);
        return new StoredContent(asset, blobStores.require(repo.getBlobStore()));
    }

    /**
     * The component with these coordinates, created when it does not exist yet. Concurrent callers with the same
     * coordinates all get the same row.
     */
    @Transactional
    public Component ensureComponent(Repository repo, ComponentCoordinates c) {
        validate(c);
        return locks.withKey(componentKey(repo.getId(), c.namespace(), c.name(), c.version()), () ->
                components.findByRepositoryIdAndNamespaceAndNameAndVersion(
                                repo.getId(), c.namespace(), c.name(), c.version())
                        .orElseGet(() -> {
                            Component component = new Component();
                            component.setRepositoryId(repo.getId());
                            component.setFormat(repo.getFormat());
                            component.setNamespace(c.namespace());
                            component.setName(c.name());
                            component.setVersion(c.version());
                            return components.save(component);
                        }));
    }

    /**
     * Links the asset at {@code path} to the component with these coordinates (created on demand), unless it already
     * belongs to a component. Used for files that were stored before their component was known, such as proxy caches
     * written by older versions. Concurrent callers for the same path or component end up with one component row; the
     * keys are taken in the same order as {@link #store}: the path's before the transaction, the component's inside.
     *
     * @return the asset as it is stored afterwards, or empty when nothing is stored at {@code path}
     * @throws InvalidStorageInputException for coordinates the components table cannot hold
     */
    public Optional<Asset> linkComponent(Repository repo, String path, ComponentCoordinates coordinates) {
        String normalised = normalise(path);
        validate(coordinates);
        return locks.withKey(assetKey(repo, normalised), () -> transactions.execute(status -> {
            Optional<Asset> found = assets.findByRepositoryIdAndPath(repo.getId(), normalised);
            if (found.isEmpty() || found.get().getComponentId() != null) {
                return found;
            }
            Asset asset = found.get();
            asset.setComponentId(ensureComponent(repo, coordinates).getId());
            return Optional.of(assets.save(asset));
        }));
    }

    /**
     * Whether a component with these coordinates can be stored: name and version present, no NUL character, and
     * within the column and index limits. {@link #store} and {@link #ensureComponent} refuse any other coordinates.
     */
    public static boolean isStorable(ComponentCoordinates coordinates) {
        if (coordinates == null) {
            return false;
        }
        try {
            validate(coordinates);
            return true;
        } catch (InvalidStorageInputException e) {
            return false;
        }
    }

    /**
     * Marks {@code asset} as just confirmed against upstream. A {@code null} validator leaves the stored one alone;
     * any other value replaces it, sanitised by {@link AssetSanitiser} (a value too long for its column is dropped).
     */
    public Asset touchVerified(Asset asset, String etag, String lastModified) {
        asset.setLastVerifiedAt(Instant.now());
        if (etag != null) {
            asset.setUpstreamEtag(AssetSanitiser.etag(etag));
        }
        if (lastModified != null) {
            asset.setUpstreamLastModified(AssetSanitiser.lastModified(lastModified));
        }
        return save(asset);
    }

    /**
     * Records the validators of a copy just downloaded from upstream: both values are replaced, so an absent or
     * unusable header clears what an older copy left behind and the next revalidation is unconditional.
     */
    public Asset recordUpstreamValidators(Asset asset, String etag, String lastModified) {
        asset.setLastVerifiedAt(Instant.now());
        asset.setUpstreamEtag(AssetSanitiser.etag(etag));
        asset.setUpstreamLastModified(AssetSanitiser.lastModified(lastModified));
        return save(asset);
    }

    /** Writes {@code asset} as it is (last writer wins), under its path's key taken before the transaction. */
    public Asset save(Asset asset) {
        asset.setContentType(AssetSanitiser.contentType(asset.getContentType(), asset.getPath()));
        asset.setUpstreamEtag(AssetSanitiser.etag(asset.getUpstreamEtag()));
        asset.setUpstreamLastModified(AssetSanitiser.lastModified(asset.getUpstreamLastModified()));
        if (asset.getRepositoryId() == null || asset.getPath() == null) {
            return transactions.execute(status -> assets.save(asset));
        }
        String normalised = normalise(asset.getPath());
        return locks.withKey(new StorageLocks.AssetKey(asset.getRepositoryId(), normalised),
                () -> transactions.execute(status -> assets.save(asset)));
    }

    /** Counted in its own transaction so a failed download response never rolls back the counter. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDownload(Asset asset) {
        assets.recordDownload(asset.getId(), Instant.now());
    }

    /** Deletes the file at {@code path}, and its component when that was its last file. */
    public boolean delete(Repository repo, String path) {
        String normalised = normalise(path);
        // path key first, transaction second, like store(); the component key is taken inside the transaction
        return Boolean.TRUE.equals(locks.withKey(assetKey(repo, normalised), () -> transactions.execute(status -> {
            Optional<Asset> asset = assets.findByRepositoryIdAndPath(repo.getId(), normalised);
            if (asset.isEmpty()) {
                return false;
            }
            Asset a = asset.get();
            assets.delete(a);
            if (a.getComponentId() != null) {
                // a concurrent store may be linking a new asset to this component right now: decide under its key
                components.findById(a.getComponentId()).ifPresent(component -> locks.withKey(componentKey(component), () -> {
                    if (assets.findByComponentId(component.getId()).isEmpty()) {
                        components.delete(component);
                    }
                    return null;
                }));
            }
            return true;
        })));
    }

    /** Removes a component and all of its assets. Returns the number of assets deleted. */
    @Transactional
    public int deleteComponent(UUID componentId) {
        Optional<Component> component = components.findById(componentId);
        if (component.isEmpty()) {
            return 0;
        }
        return locks.withKey(componentKey(component.get()), () -> {
            List<Asset> list = assets.findByComponentId(componentId);
            assets.deleteAll(list);
            components.delete(component.get());
            return list.size();
        });
    }

    private static StorageLocks.AssetKey assetKey(Repository repo, String normalisedPath) {
        return new StorageLocks.AssetKey(repo.getId(), normalisedPath);
    }

    private static StorageLocks.ComponentKey componentKey(UUID repositoryId, String namespace, String name, String version) {
        return new StorageLocks.ComponentKey(repositoryId, namespace == null ? "" : namespace, name, version);
    }

    private static StorageLocks.ComponentKey componentKey(Component c) {
        return componentKey(c.getRepositoryId(), c.getNamespace(), c.getName(), c.getVersion());
    }

    public BlobStore blobStoreOf(Repository repo) {
        return blobStores.require(repo.getBlobStore());
    }

    /**
     * Repository-relative paths never start with a slash, never contain {@code ..} segments or backslashes, fit the
     * {@code assets.path} column and, encoded as UTF-8, its unique index ({@link AssetSanitiser#MAX_INDEXED_BYTES}).
     *
     * @throws InvalidStorageInputException (an {@link IllegalArgumentException}) for any other path
     */
    public static String normalise(String path) {
        if (path == null) {
            return "";
        }
        String p = path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.length() > AssetSanitiser.MAX_PATH_LENGTH) {
            throw InvalidStorageInputException.path("path longer than " + AssetSanitiser.MAX_PATH_LENGTH
                    + " characters: " + p.substring(0, 100) + "...");
        }
        // a char takes at most three bytes, so only longer paths need counting
        if (p.length() > AssetSanitiser.MAX_INDEXED_BYTES / 3
                && AssetSanitiser.utf8Length(p) > AssetSanitiser.MAX_INDEXED_BYTES) {
            throw InvalidStorageInputException.path("path longer than " + AssetSanitiser.MAX_INDEXED_BYTES
                    + " bytes in UTF-8: " + p.substring(0, 100) + "...");
        }
        if (p.contains("\\") || p.contains("/../") || p.startsWith("../") || p.endsWith("/..") || p.equals("..")) {
            throw InvalidStorageInputException.path("illegal path: " + path);
        }
        if (p.indexOf('\0') >= 0) {
            // PostgreSQL cannot store or even compare a string with a zero character
            throw InvalidStorageInputException.path("illegal path: contains a NUL character");
        }
        return p;
    }

    private static void validate(ComponentCoordinates c) {
        if (c.name() == null || c.version() == null) {
            throw InvalidStorageInputException.component("component name and version are required");
        }
        if ((c.namespace() + c.name() + c.version()).indexOf('\0') >= 0) {
            throw InvalidStorageInputException.component("component coordinates contain a NUL character");
        }
        if (c.namespace().length() > AssetSanitiser.MAX_NAMESPACE_LENGTH
                || c.name().length() > AssetSanitiser.MAX_NAME_LENGTH
                || c.version().length() > AssetSanitiser.MAX_VERSION_LENGTH
                || AssetSanitiser.utf8Length(c.namespace()) + AssetSanitiser.utf8Length(c.name())
                        + AssetSanitiser.utf8Length(c.version()) > AssetSanitiser.MAX_INDEXED_BYTES) {
            throw InvalidStorageInputException.component("component coordinates too long (namespace "
                    + AssetSanitiser.MAX_NAMESPACE_LENGTH + ", name " + AssetSanitiser.MAX_NAME_LENGTH + ", version "
                    + AssetSanitiser.MAX_VERSION_LENGTH + " characters at most, " + AssetSanitiser.MAX_INDEXED_BYTES
                    + " bytes in UTF-8 together)");
        }
    }

    /** Coordinates used to attach an asset to a {@link Component}. */
    public record ComponentCoordinates(String namespace, String name, String version) {
        public ComponentCoordinates {
            namespace = namespace == null ? "" : namespace;
        }
    }
}
