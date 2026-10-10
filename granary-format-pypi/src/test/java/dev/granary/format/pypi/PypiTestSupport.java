package dev.granary.format.pypi;

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
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Fixtures shared by the unit tests: archives that look like real wheels and sdists, and a map-backed storage. */
final class PypiTestSupport {

    private PypiTestSupport() {
    }

    static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(PypiFormatHandler.FORMAT);
        r.setType(type);
        return r;
    }

    static String metadata(String name, String version, String requiresPython, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("Metadata-Version: 2.4\n");
        sb.append("Name: ").append(name).append('\n');
        sb.append("Version: ").append(version).append('\n');
        if (summary != null) {
            sb.append("Summary: ").append(summary).append('\n');
        }
        sb.append("Author-email: Acme <dev@acme.example>\n");
        sb.append("Project-URL: Homepage, https://example.com/").append(name).append('\n');
        sb.append("Classifier: Programming Language :: Python :: 3\n");
        if (requiresPython != null) {
            sb.append("Requires-Python: ").append(requiresPython).append('\n');
        }
        sb.append("Description-Content-Type: text/markdown\n");
        sb.append("Requires-Dist: six>=1.16\n");
        sb.append("\n# ").append(name).append("\n\nA demo package.\n");
        return sb.toString();
    }

    /** A wheel with the given METADATA member under {@code <name_>-<version>.dist-info/}. */
    static byte[] wheel(String name, String version, String metadata) {
        String distInfo = name.replace('-', '_') + "-" + version + ".dist-info/";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(name.replace('-', '_') + "/__init__.py", ("__version__ = \"" + version + "\"\n").getBytes(StandardCharsets.UTF_8));
        entries.put(distInfo + "METADATA", metadata.getBytes(StandardCharsets.UTF_8));
        entries.put(distInfo + "WHEEL", "Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n".getBytes(StandardCharsets.UTF_8));
        entries.put(distInfo + "RECORD", "".getBytes(StandardCharsets.UTF_8));
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

    /** An sdist: a gzip'ed ustar archive with {@code <name>-<version>/PKG-INFO} and a module. */
    static byte[] sdist(String name, String version, String pkgInfo) {
        String dir = name + "-" + version + "/";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(dir + "pyproject.toml", "[project]\n".getBytes(StandardCharsets.UTF_8));
        entries.put(dir + "PKG-INFO", pkgInfo.getBytes(StandardCharsets.UTF_8));
        entries.put(dir + name.replace('-', '_') + "/__init__.py", "".getBytes(StandardCharsets.UTF_8));
        return targz(entries);
    }

    static byte[] targz(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(tar(entries));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] tar(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            byte[] header = new byte[512];
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0, header, 0, Math.min(name.length, 100));
            octal(header, 100, 8, 0644);
            octal(header, 108, 8, 0);
            octal(header, 116, 8, 0);
            octal(header, 124, 12, e.getValue().length);
            octal(header, 136, 12, 0);
            for (int i = 148; i < 156; i++) {
                header[i] = ' ';
            }
            header[156] = '0';
            System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
            header[263] = '0';
            header[264] = '0';
            int sum = 0;
            for (byte b : header) {
                sum += b & 0xff;
            }
            octal(header, 148, 7, sum);
            header[155] = ' ';
            out.writeBytes(header);
            out.writeBytes(e.getValue());
            int padding = (512 - (e.getValue().length % 512)) % 512;
            out.writeBytes(new byte[padding]);
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    private static void octal(byte[] header, int offset, int length, long value) {
        String s = Long.toOctalString(value);
        while (s.length() < length - 1) {
            s = "0" + s;
        }
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
        header[offset + bytes.length] = 0;
    }

    static String sha256(byte[] data) {
        return Checksums.hex("SHA-256", data);
    }

    static String md5(byte[] data) {
        return Checksums.hex("MD5", data);
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

        byte[] bytes(Repository repo, String path) {
            try {
                return find(repo, path).orElseThrow().readAllBytes();
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
