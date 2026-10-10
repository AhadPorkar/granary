package dev.granary.format.composer;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixtures shared by the unit tests: package archives, fixture documents and a map-backed storage. */
final class ComposerTestSupport {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    private ComposerTestSupport() {
    }

    static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(ComposerFormatHandler.FORMAT);
        r.setType(type);
        return r;
    }

    /** A composer.json for a library, with the given extra fields merged in. */
    static String composerJson(String name, String version, String extraFields) {
        StringBuilder sb = new StringBuilder("{\n");
        if (name != null) {
            sb.append("  \"name\": \"").append(name).append("\",\n");
        }
        if (version != null) {
            sb.append("  \"version\": \"").append(version).append("\",\n");
        }
        sb.append("  \"description\": \"Demo library ").append(name).append("\",\n");
        sb.append("  \"type\": \"library\",\n");
        sb.append("  \"keywords\": [\"demo\", \"logging\"],\n");
        sb.append("  \"license\": \"MIT\",\n");
        sb.append("  \"require\": {\"php\": \">=8.1\"},\n");
        if (extraFields != null && !extraFields.isBlank()) {
            sb.append("  ").append(extraFields).append(",\n");
        }
        sb.append("  \"autoload\": {\"psr-4\": {\"Acme\\\\Lib\\\\\": \"src/\"}}\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** A package archive the way GitHub builds it: everything under one top-level directory. */
    static byte[] packageZip(String composerJson) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("acme-lib-1a2b3c/", new byte[0]);
        entries.put("acme-lib-1a2b3c/composer.json", composerJson.getBytes(StandardCharsets.UTF_8));
        entries.put("acme-lib-1a2b3c/src/Lib.php", "<?php\nnamespace Acme\\Lib;\nfinal class Lib {}\n".getBytes(StandardCharsets.UTF_8));
        return zip(entries);
    }

    /** A package archive with composer.json at the root, as {@code composer archive} builds it. */
    static byte[] rootZip(String composerJson) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("composer.json", composerJson.getBytes(StandardCharsets.UTF_8));
        entries.put("src/Lib.php", "<?php\n".getBytes(StandardCharsets.UTF_8));
        return zip(entries);
    }

    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String sha1(byte[] data) {
        return Checksums.hex("SHA-1", data);
    }

    static byte[] resource(String name) {
        try (InputStream in = ComposerTestSupport.class.getResourceAsStream("/composer/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode resourceJson(String name) {
        return JSON.readTree(resource(name));
    }

    static ObjectNode object(String json) {
        return (ObjectNode) JSON.readTree(json);
    }

    static ObjectNode parse(byte[] json) {
        return (ObjectNode) JSON.readTree(json);
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
        final Map<String, Asset.Origin> origins = new LinkedHashMap<>();
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
            when(mock.store(any(), anyString(), any(), any(), any(), anyBoolean())).thenAnswer(inv -> {
                Repository repo = inv.getArgument(0);
                String path = inv.getArgument(1);
                origins.put(key(repo, path), inv.getArgument(4));
                return putStream(repo, path, inv.getArgument(2), inv.getArgument(3), inv.getArgument(5));
            });
            when(mock.store(any(), anyString(), any(), any(), any(), anyBoolean(), any())).thenAnswer(inv -> {
                Repository repo = inv.getArgument(0);
                String path = inv.getArgument(1);
                coordinates.put(key(repo, path), inv.getArgument(6));
                origins.put(key(repo, path), inv.getArgument(4));
                return putStream(repo, path, inv.getArgument(2), inv.getArgument(3), inv.getArgument(5));
            });
            when(mock.delete(any(), anyString())).thenAnswer(inv -> {
                String key = key(inv.getArgument(0), inv.getArgument(1));
                coordinates.remove(key);
                return assets.remove(key) != null;
            });
            when(mock.list(any(), anyString())).thenAnswer(inv -> list(inv.getArgument(0), inv.getArgument(1)));
            when(mock.blobStoreOf(any())).thenReturn(blobs);
            org.mockito.Mockito.doAnswer(inv -> {
                downloads.add(inv.getArgument(0));
                return null;
            }).when(mock).recordDownload(any());
        }

        StoredContent put(Repository repo, String path, byte[] bytes, String contentType, boolean metadata) {
            return putStream(repo, path, new ByteArrayInputStream(bytes), contentType, metadata);
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

        boolean has(Repository repo, String path) {
            return assets.containsKey(key(repo, path));
        }

        byte[] bytes(Repository repo, String path) {
            try {
                return find(repo, path).orElseThrow(() -> new AssertionError("no " + path)).readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        ObjectNode json(Repository repo, String path) {
            return parse(bytes(repo, path));
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

        void remove(Repository repo, String path) {
            assets.remove(key(repo, path));
        }

        StorageService.ComponentCoordinates coordinatesOf(Repository repo, String path) {
            return coordinates.get(key(repo, path));
        }

        Asset.Origin originOf(Repository repo, String path) {
            return origins.get(key(repo, path));
        }

        private static String key(Repository repo, String path) {
            return repo.getId() + "|" + path;
        }
    }
}
