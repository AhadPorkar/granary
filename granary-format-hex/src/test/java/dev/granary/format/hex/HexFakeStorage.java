package dev.granary.format.hex;

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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A Mockito {@link StorageService} backed by maps, so the code under test reads back what it stored (publish
 * followed by revert, a registry document rebuilt from tarballs) without a database.
 */
final class HexFakeStorage {

    final HexMemoryBlobStore blobs = new HexMemoryBlobStore();
    final Map<String, Asset> assets = new LinkedHashMap<>();
    final Map<String, StorageService.ComponentCoordinates> coordinates = new LinkedHashMap<>();
    final StorageService mock = mock(StorageService.class);
    int stores;

    HexFakeStorage() {
        when(mock.find(any(), anyString())).thenAnswer(inv -> find(inv.getArgument(0), inv.getArgument(1)));
        when(mock.exists(any(), anyString())).thenAnswer(inv -> find(inv.getArgument(0), inv.getArgument(1)).isPresent());
        when(mock.store(any(), anyString(), any(), any(), any(), anyBoolean())).thenAnswer(inv ->
                putStream(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(5)));
        when(mock.store(any(), anyString(), any(), any(), any(), anyBoolean(), any())).thenAnswer(inv -> {
            Repository repo = inv.getArgument(0);
            String path = inv.getArgument(1);
            coordinates.put(key(repo, path), inv.getArgument(6));
            return putStream(repo, path, inv.getArgument(2), inv.getArgument(3), inv.getArgument(5));
        });
        when(mock.delete(any(), anyString())).thenAnswer(inv -> {
            String key = key(inv.getArgument(0), inv.getArgument(1));
            coordinates.remove(key);
            return assets.remove(key) != null;
        });
        when(mock.list(any(), anyString())).thenAnswer(inv -> list(inv.getArgument(0), inv.getArgument(1)));
        when(mock.ensureComponent(any(), any())).thenAnswer(inv -> {
            Component component = mock(Component.class);
            when(component.getId()).thenReturn(UUID.randomUUID());
            return component;
        });
        when(mock.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    StoredContent put(Repository repo, String path, byte[] bytes, String contentType, boolean metadata) {
        return putStream(repo, path, new ByteArrayInputStream(bytes), contentType, metadata);
    }

    StoredContent putStream(Repository repo, String path, InputStream in, String contentType, boolean metadata) {
        try {
            var ref = blobs.store(in);
            Asset asset = new Asset();
            asset.setRepositoryId(repo.getId());
            asset.setPath(path);
            asset.setBlobSha256(ref.sha256());
            asset.setSize(ref.size());
            asset.setContentType(contentType);
            asset.setMetadata(metadata);
            asset.setLastVerifiedAt(Instant.now());
            assets.put(key(repo, path), asset);
            stores++;
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

    boolean has(Repository repo, String path) {
        return assets.containsKey(key(repo, path));
    }

    void remove(Repository repo, String path) {
        assets.remove(key(repo, path));
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
