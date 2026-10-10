package dev.granary.format.cocoapods;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.blob.BlobRef;
import dev.granary.core.blob.BlobStore;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Fixtures shared by the unit tests: repositories, podspecs, archives and a map-backed storage. */
final class CocoaPodsTestSupport {

    private CocoaPodsTestSupport() {
    }

    static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(CocoaPodsFormatHandler.FORMAT);
        r.setType(type);
        if (type == RepositoryType.PROXY) {
            r.setRemoteUrl("https://cdn.cocoapods.org/");
        }
        return r;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String podspec(String name, String version) {
        return "{\n  \"name\": \"" + name + "\",\n  \"version\": \"" + version + "\",\n  \"summary\": \"The " + name + " pod.\",\n"
                + "  \"license\": \"MIT\",\n  \"authors\": {\"Acme\": \"dev@acme.example\"},\n"
                + "  \"homepage\": \"https://example.com/" + name + "\",\n"
                + "  \"source\": {\"git\": \"https://example.com/" + name + ".git\", \"tag\": \"" + version + "\"},\n"
                + "  \"platforms\": {\"ios\": \"12.0\"},\n  \"source_files\": \"Sources/**/*.swift\"\n}\n";
    }

    static String httpPodspec(String name, String version, String url) {
        return "{\"name\": \"" + name + "\", \"version\": \"" + version + "\", \"summary\": \"Binary pod\","
                + " \"source\": {\"http\": \"" + url + "\", \"flatten\": true}, \"vendored_frameworks\": \"" + name + ".xcframework\"}";
    }

    static byte[] zip(String entry, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(utf8(content));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static StoredContent content(MemoryBlobStore blobs, String path, byte[] bytes, String contentType) {
        Asset asset = new Asset();
        asset.setPath(path);
        asset.setBlobSha256(blobs.put(bytes));
        asset.setSize(bytes.length);
        asset.setContentType(contentType);
        asset.setLastVerifiedAt(Instant.now());
        return new StoredContent(asset, blobs);
    }

    /** A map-backed blob store, so tests can hand real bytes to {@link StoredContent}. */
    static final class MemoryBlobStore implements BlobStore {
        private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

        @Override
        public String name() {
            return "memory";
        }

        @Override
        public BlobRef store(InputStream in) throws IOException {
            byte[] bytes = in.readAllBytes();
            String sha = Checksums.hex("SHA-256", bytes);
            blobs.put(sha, bytes);
            return new BlobRef(sha, bytes.length);
        }

        String put(byte[] bytes) {
            String sha = Checksums.hex("SHA-256", bytes);
            blobs.put(sha, bytes);
            return sha;
        }

        @Override
        public Optional<InputStream> open(String sha256) {
            byte[] bytes = blobs.get(sha256);
            return bytes == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(bytes));
        }

        @Override
        public boolean exists(String sha256) {
            return blobs.containsKey(sha256);
        }

        @Override
        public boolean delete(String sha256) {
            return blobs.remove(sha256) != null;
        }

        @Override
        public BlobStoreStats stats() {
            return new BlobStoreStats(blobs.size(), blobs.values().stream().mapToLong(b -> b.length).sum(), Long.MAX_VALUE);
        }
    }

    /** A Mockito {@link StorageService} backed by a map, so code under test can read back what it stored. */
    static final class FakeStorage {
        final MemoryBlobStore blobs = new MemoryBlobStore();
        final Map<String, Asset> assets = new LinkedHashMap<>();
        final Map<String, StorageService.ComponentCoordinates> coordinates = new LinkedHashMap<>();
        final List<Asset> downloads = new ArrayList<>();
        final Map<StorageService.ComponentCoordinates, Component> components = new LinkedHashMap<>();
        final StorageService mock = mock(StorageService.class);

        FakeStorage() {
            when(mock.ensureComponent(any(), any())).thenAnswer(inv -> components.computeIfAbsent(inv.getArgument(1), c -> {
                Component component = mock(Component.class);
                UUID id = UUID.randomUUID();
                when(component.getId()).thenReturn(id);
                return component;
            }));
            when(mock.save(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));
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
            when(mock.touchVerified(any(), any(), any())).thenAnswer(inv -> {
                Asset asset = inv.getArgument(0);
                asset.setLastVerifiedAt(Instant.now());
                if (inv.getArgument(1) != null) {
                    asset.setUpstreamEtag(inv.getArgument(1));
                }
                if (inv.getArgument(2) != null) {
                    asset.setUpstreamLastModified(inv.getArgument(2));
                }
                return asset;
            });
            when(mock.blobStoreOf(any())).thenReturn(blobs);
            org.mockito.Mockito.doAnswer(inv -> {
                downloads.add(inv.getArgument(0));
                return null;
            }).when(mock).recordDownload(any());
        }

        StoredContent put(Repository repo, String path, byte[] bytes, String contentType, boolean metadata) {
            return putStream(repo, path, new ByteArrayInputStream(bytes), contentType, metadata);
        }

        StoredContent put(Repository repo, String path, String text) {
            return put(repo, path, utf8(text), path.endsWith(".json") ? "application/json" : "text/plain", !path.startsWith("Specs/"));
        }

        StoredContent putStream(Repository repo, String path, InputStream in, String contentType, boolean metadata) {
            try {
                var ref = blobs.store(in);
                Asset asset = assets.getOrDefault(key(repo, path), new Asset());
                asset.setRepositoryId(repo.getId());
                asset.setPath(path);
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

        String text(Repository repo, String path) {
            try {
                return find(repo, path).orElseThrow().readUtf8();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        List<Asset> list(Repository repo, String prefix) {
            String keyPrefix = repo.getId() + "|" + prefix;
            List<Asset> result = new ArrayList<>();
            for (Map.Entry<String, Asset> e : assets.entrySet()) {
                if (e.getKey().startsWith(keyPrefix)) {
                    result.add(e.getValue());
                }
            }
            result.sort((a, b) -> a.getPath().compareTo(b.getPath()));
            return result;
        }

        List<String> paths(Repository repo) {
            return list(repo, "").stream().map(Asset::getPath).toList();
        }

        private static String key(Repository repo, String path) {
            return repo.getId() + "|" + path;
        }
    }
}
