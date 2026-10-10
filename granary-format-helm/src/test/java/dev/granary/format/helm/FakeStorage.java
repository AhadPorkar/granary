package dev.granary.format.helm;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A Mockito {@link StorageService} backed by maps, so the code under test can read back what it stored (upload a
 * chart, then read the index generated from it) without a database.
 */
final class FakeStorage {

    final MemoryBlobStore blobs = new MemoryBlobStore();
    final Map<String, Asset> assets = new LinkedHashMap<>();
    final Map<String, StorageService.ComponentCoordinates> coordinates = new LinkedHashMap<>();
    final Map<String, Component> components = new LinkedHashMap<>();
    final List<Asset> downloads = new java.util.ArrayList<>();
    final StorageService mock = mock(StorageService.class);

    FakeStorage() {
        when(mock.find(any(), anyString())).thenAnswer(inv -> find(inv.getArgument(0), inv.getArgument(1)));
        when(mock.exists(any(), anyString())).thenAnswer(inv -> find(inv.getArgument(0), inv.getArgument(1)).isPresent());
        when(mock.store(any(), anyString(), any(), any(), any(), anyBoolean())).thenAnswer(inv ->
                putStream(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                        inv.getArgument(4), inv.getArgument(5), null));
        when(mock.store(any(), anyString(), any(), any(), any(), anyBoolean(), any())).thenAnswer(inv -> {
            Repository repo = inv.getArgument(0);
            String path = inv.getArgument(1);
            StorageService.ComponentCoordinates c = inv.getArgument(6);
            UUID componentId = null;
            if (c != null) {
                coordinates.put(key(repo, path), c);
                componentId = component(repo, c).getId();
            }
            return putStream(repo, path, inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5),
                    componentId);
        });
        when(mock.delete(any(), anyString())).thenAnswer(inv -> {
            String key = key(inv.getArgument(0), inv.getArgument(1));
            coordinates.remove(key);
            return assets.remove(key) != null;
        });
        when(mock.list(any(), anyString())).thenAnswer(inv -> list(inv.getArgument(0), inv.getArgument(1)));
        when(mock.ensureComponent(any(), any())).thenAnswer(inv -> component(inv.getArgument(0), inv.getArgument(1)));
        when(mock.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mock.blobStoreOf(any())).thenReturn(blobs);
        when(mock.touchVerified(any(), any(), any())).thenAnswer(inv -> {
            Asset asset = inv.getArgument(0);
            asset.setLastVerifiedAt(Instant.now());
            return asset;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            downloads.add(inv.getArgument(0));
            return null;
        }).when(mock).recordDownload(any());
    }

    StoredContent put(Repository repo, String path, byte[] bytes, String contentType, boolean metadata) {
        return putStream(repo, path, new java.io.ByteArrayInputStream(bytes), contentType, Asset.Origin.UPLOADED, metadata, null);
    }

    StoredContent putStream(Repository repo, String path, InputStream in, String contentType, Asset.Origin origin,
                            boolean metadata, UUID componentId) {
        try {
            var ref = blobs.store(in);
            Asset asset = assets.getOrDefault(key(repo, path), new Asset());
            asset.setRepositoryId(repo.getId());
            asset.setPath(path);
            asset.setBlobSha256(ref.sha256());
            asset.setSize(ref.size());
            asset.setContentType(contentType);
            asset.setOrigin(origin == null ? Asset.Origin.UPLOADED : origin);
            asset.setMetadata(metadata);
            asset.setLastVerifiedAt(Instant.now());
            if (componentId != null) {
                asset.setComponentId(componentId);
            }
            assets.put(key(repo, path), asset);
            return new StoredContent(asset, blobs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Component component(Repository repo, StorageService.ComponentCoordinates c) {
        String key = repo.getId() + "|" + c.namespace() + "|" + c.name() + "|" + c.version();
        return components.computeIfAbsent(key, k -> {
            Component component = new Component();
            component.setRepositoryId(repo.getId());
            component.setFormat(repo.getFormat());
            component.setNamespace(c.namespace());
            component.setName(c.name());
            component.setVersion(c.version());
            try {
                Field id = Component.class.getDeclaredField("id");
                id.setAccessible(true);
                id.set(component, UUID.randomUUID());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return component;
        });
    }

    Optional<StoredContent> find(Repository repo, String path) {
        return Optional.ofNullable(assets.get(key(repo, path))).map(a -> new StoredContent(a, blobs));
    }

    Asset asset(Repository repo, String path) {
        return find(repo, path).orElseThrow(() -> new AssertionError("not stored: " + path)).asset();
    }

    byte[] bytes(Repository repo, String path) {
        try {
            return find(repo, path).orElseThrow(() -> new AssertionError("not stored: " + path)).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String text(Repository repo, String path) {
        return new String(bytes(repo, path), java.nio.charset.StandardCharsets.UTF_8);
    }

    Set<String> paths(Repository repo) {
        Set<String> paths = new TreeSet<>();
        String prefix = repo.getId() + "|";
        for (String key : assets.keySet()) {
            if (key.startsWith(prefix)) {
                paths.add(key.substring(prefix.length()));
            }
        }
        return paths;
    }

    private List<Asset> list(Repository repo, String prefix) {
        return paths(repo).stream().filter(p -> p.startsWith(prefix)).map(p -> assets.get(key(repo, p))).toList();
    }

    private static String key(Repository repo, String path) {
        return repo.getId() + "|" + path;
    }
}
