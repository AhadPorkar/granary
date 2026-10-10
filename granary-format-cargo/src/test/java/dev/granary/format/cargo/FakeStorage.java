package dev.granary.format.cargo;

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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A Mockito {@link StorageService} backed by maps, so the code under test reads back what it stored without a
 * database. Components are recorded by coordinates and get stable random ids.
 */
final class FakeStorage {

    final MemoryBlobStore blobs = new MemoryBlobStore();
    final Map<String, Asset> assets = new LinkedHashMap<>();
    final Map<String, StorageService.ComponentCoordinates> coordinates = new LinkedHashMap<>();
    final Map<String, UUID> componentIds = new LinkedHashMap<>();
    final StorageService mock = mock(StorageService.class);

    FakeStorage() {
        when(mock.find(any(), anyString())).thenAnswer(inv -> locked(() -> find(inv.getArgument(0), inv.getArgument(1))));
        when(mock.exists(any(), anyString())).thenAnswer(inv -> locked(() -> find(inv.getArgument(0), inv.getArgument(1)).isPresent()));
        when(mock.store(any(), anyString(), any(), any(), any(), anyBoolean())).thenAnswer(inv -> locked(() ->
                putStream(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(5))));
        when(mock.store(any(), anyString(), any(), any(), any(), anyBoolean(), any())).thenAnswer(inv -> locked(() -> {
            Repository repo = inv.getArgument(0);
            String path = inv.getArgument(1);
            StorageService.ComponentCoordinates c = inv.getArgument(6);
            coordinates.put(key(repo, path), c);
            StoredContent stored = putStream(repo, path, inv.getArgument(2), inv.getArgument(3), inv.getArgument(5));
            stored.asset().setComponentId(componentId(repo, c));
            return stored;
        }));
        when(mock.delete(any(), anyString())).thenAnswer(inv -> locked(() -> {
            String key = key(inv.getArgument(0), inv.getArgument(1));
            coordinates.remove(key);
            return assets.remove(key) != null;
        }));
        when(mock.list(any(), anyString())).thenAnswer(inv -> locked(() -> list(inv.getArgument(0), inv.getArgument(1))));
        when(mock.ensureComponent(any(), any())).thenAnswer(inv -> {
            Repository repo = inv.getArgument(0);
            StorageService.ComponentCoordinates c = inv.getArgument(1);
            Component component = new Component();
            component.setRepositoryId(repo.getId());
            component.setName(c.name());
            component.setVersion(c.version());
            java.lang.reflect.Field id = Component.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(component, componentId(repo, c));
            return component;
        });
        when(mock.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** The fake is shared by threads in the concurrency tests; one lock keeps the maps consistent. */
    private synchronized <T> T locked(java.util.concurrent.Callable<T> body) throws Exception {
        return body.call();
    }

    StoredContent put(Repository repo, String path, byte[] bytes, String contentType, boolean metadata) {
        return putStream(repo, path, new ByteArrayInputStream(bytes), contentType, metadata);
    }

    StoredContent put(Repository repo, String path, String text, boolean metadata) {
        return put(repo, path, text.getBytes(StandardCharsets.UTF_8), "text/plain", metadata);
    }

    StoredContent putStream(Repository repo, String path, InputStream in, String contentType, boolean metadata) {
        try {
            var ref = blobs.store(in);
            Asset asset = new Asset();
            asset.setRepositoryId(repo.getId());
            asset.setPath(StorageService.normalise(path));
            asset.setBlobSha256(ref.sha256());
            asset.setSize(ref.size());
            asset.setContentType(contentType);
            asset.setMetadata(metadata);
            asset.setLastVerifiedAt(Instant.now());
            assets.put(key(repo, path), asset);
            return new StoredContent(asset, blobs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Optional<StoredContent> find(Repository repo, String path) {
        return Optional.ofNullable(assets.get(key(repo, path))).map(a -> new StoredContent(a, blobs));
    }

    byte[] bytes(Repository repo, String path) {
        try {
            return find(repo, path).orElseThrow(() -> new AssertionError("not stored: " + path)).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String text(Repository repo, String path) {
        return new String(bytes(repo, path), StandardCharsets.UTF_8);
    }

    Asset asset(Repository repo, String path) {
        Asset asset = assets.get(key(repo, path));
        if (asset == null) {
            throw new AssertionError("not stored: " + path);
        }
        return asset;
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

    UUID componentId(Repository repo, StorageService.ComponentCoordinates c) {
        return componentIds.computeIfAbsent(repo.getId() + "|" + c.namespace() + "|" + c.name() + "|" + c.version(),
                k -> UUID.randomUUID());
    }

    private List<Asset> list(Repository repo, String prefix) {
        return paths(repo).stream().filter(p -> p.startsWith(prefix)).map(p -> assets.get(key(repo, p))).toList();
    }

    private static String key(Repository repo, String path) {
        return repo.getId() + "|" + StorageService.normalise(path);
    }
}
