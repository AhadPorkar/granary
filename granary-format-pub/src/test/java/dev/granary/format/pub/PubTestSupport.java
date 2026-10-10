package dev.granary.format.pub;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.granary.core.blob.BlobRef;
import dev.granary.core.blob.BlobStore;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.security.Privileges;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import org.springframework.mock.web.MockHttpServletRequest;

/** Fixtures shared by the unit tests: archives as dart pub publish builds them, a map-backed storage, requests. */
final class PubTestSupport {

    static final String SERVER = "http://granary.test";

    private PubTestSupport() {
    }

    static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(PubFormatHandler.FORMAT);
        r.setType(type);
        if (type == RepositoryType.PROXY) {
            r.setRemoteUrl("https://pub.dev/");
        }
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    // --- principals and requests -------------------------------------------------------------------

    static GranaryPrincipal admin() {
        return new GranaryPrincipal(UUID.randomUUID(), "admin", Set.of("admin"), Set.of(Privileges.SYSTEM_ADMIN), false,
                GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static GranaryPrincipal user(String name, String... privileges) {
        return new GranaryPrincipal(UUID.randomUUID(), name, Set.of("custom"), Set.of(privileges), false,
                GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static GranaryPrincipal anonymous(boolean canRead) {
        return GranaryPrincipal.anonymous(canRead ? Set.of(Privileges.REPOSITORY_READ_ALL) : Set.of());
    }

    static RepositoryRequest request(Repository repo, String method, String path, GranaryPrincipal principal) {
        return request(repo, new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path), path, principal);
    }

    static RepositoryRequest request(Repository repo, MockHttpServletRequest http, String path, GranaryPrincipal principal) {
        http.addHeader("Accept", PubResponses.PUB_V2);
        return new RepositoryRequest(repo, path, http, principal, baseUrl(repo));
    }

    // --- archives ----------------------------------------------------------------------------------

    static String pubspec(String name, String version) {
        return "name: " + name + "\n"
                + "version: " + version + "\n"
                + "description: A package used by the Granary tests, long enough to satisfy the client.\n"
                + "homepage: https://example.com/" + name + "\n"
                + "environment:\n"
                + "  sdk: ^3.4.0\n"
                + "dependencies:\n"
                + "  path: ^1.9.0\n";
    }

    /** The archive dart pub publish would build: sorted entries, pubspec.yaml at the root. */
    static byte[] archive(String name, String version) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("CHANGELOG.md", ("## " + version + "\n").getBytes(StandardCharsets.UTF_8));
        entries.put("LICENSE", "MIT".getBytes(StandardCharsets.UTF_8));
        entries.put("README.md", ("# " + name + "\n").getBytes(StandardCharsets.UTF_8));
        entries.put("lib/" + name + ".dart", "String hello() => 'hi';\n".getBytes(StandardCharsets.UTF_8));
        entries.put("pubspec.yaml", pubspec(name, version).getBytes(StandardCharsets.UTF_8));
        return targz(entries);
    }

    static byte[] targz(Map<String, byte[]> entries) {
        Tar tar = new Tar();
        entries.forEach(tar::file);
        return gzip(tar.finish());
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] data) {
        return Checksums.hex("SHA-256", data);
    }

    /** A tar writer with the corner cases real archivers produce. */
    static final class Tar {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Tar file(String name, byte[] data) {
            return entry(name, '0', data, "", null);
        }

        Tar directory(String name) {
            return entry(name, '5', new byte[0], "", null);
        }

        Tar symlink(String name, String target) {
            return entry(name, '2', new byte[0], target, null);
        }

        Tar hardlink(String name, String target) {
            return entry(name, '1', new byte[0], target, null);
        }

        /** A name longer than 100 bytes, written the GNU way (an {@code L} entry carrying the name). */
        Tar gnuLongName(String name, byte[] data) {
            byte[] longName = (name + "\0").getBytes(StandardCharsets.UTF_8);
            entry("././@LongLink", 'L', longName, "", null);
            return entry(name.substring(0, 100), '0', data, "", null);
        }

        /** A name carried in a pax extended header ({@code x} entry with a {@code path} record). */
        Tar paxName(String name, byte[] data) {
            String record = " path=" + name + "\n";
            int length = record.length() + 1;
            while (Integer.toString(length).length() + record.length() != length) {
                length = Integer.toString(length).length() + record.length();
            }
            entry("PaxHeaders/x", 'x', (length + record).getBytes(StandardCharsets.UTF_8), "", null);
            return entry("short-name", '0', data, "", null);
        }

        /**
         * A symbolic link whose target is longer than 100 bytes, written the GNU way: a {@code K} entry carries
         * the whole target, the link header only its first 100 bytes.
         */
        Tar gnuLongSymlink(String name, String target) {
            entry("././@LongLink", 'K', (target + "\0").getBytes(StandardCharsets.UTF_8), "", null);
            return entry(name, '2', new byte[0], target.substring(0, Math.min(100, target.length())), null);
        }

        /** A symbolic link whose real target is carried in a pax {@code linkpath} record. */
        Tar paxSymlink(String name, String target, String headerTarget) {
            String record = " linkpath=" + target + "\n";
            int length = record.length() + 1;
            while (Integer.toString(length).length() + record.length() != length) {
                length = Integer.toString(length).length() + record.length();
            }
            entry("PaxHeaders/x", 'x', (length + record).getBytes(StandardCharsets.UTF_8), "", null);
            return entry(name, '2', new byte[0], headerTarget, null);
        }

        /** A ustar entry whose name is split into prefix and name fields. */
        Tar ustarPrefix(String prefix, String name, byte[] data) {
            return entry(name, '0', data, "", prefix);
        }

        Tar entry(String name, char type, byte[] data, String link, String prefix) {
            out.writeBytes(header(name, type, data.length, link, prefix, true));
            out.writeBytes(data);
            out.writeBytes(new byte[(512 - data.length % 512) % 512]);
            return this;
        }

        Tar raw(byte[] bytes) {
            out.writeBytes(bytes);
            return this;
        }

        byte[] finish() {
            out.writeBytes(new byte[1024]);
            return out.toByteArray();
        }

        byte[] unterminated() {
            return out.toByteArray();
        }

        static byte[] header(String name, char type, long size, String link, String prefix, boolean validChecksum) {
            byte[] header = new byte[512];
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(n, 0, header, 0, Math.min(n.length, 100));
            octal(header, 100, 8, 0644);
            octal(header, 108, 8, 0);
            octal(header, 116, 8, 0);
            octal(header, 124, 12, size);
            octal(header, 136, 12, 0);
            header[156] = (byte) type;
            byte[] l = link.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(l, 0, header, 157, Math.min(l.length, 100));
            System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
            header[263] = '0';
            header[264] = '0';
            if (prefix != null) {
                byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(p, 0, header, 345, Math.min(p.length, 155));
            }
            for (int i = 148; i < 156; i++) {
                header[i] = ' ';
            }
            int sum = 0;
            for (byte b : header) {
                sum += b & 0xff;
            }
            octal(header, 148, 7, validChecksum ? sum : sum + 1);
            header[155] = ' ';
            return header;
        }

        static void octal(byte[] header, int offset, int length, long value) {
            String s = Long.toOctalString(value);
            while (s.length() < length - 1) {
                s = "0" + s;
            }
            byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(bytes, 0, header, offset, bytes.length);
            header[offset + bytes.length] = 0;
        }
    }

    // --- storage -----------------------------------------------------------------------------------

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
        final Map<String, Asset> assets = new ConcurrentHashMap<>();
        final Map<String, StorageService.ComponentCoordinates> coordinates = new ConcurrentHashMap<>();
        final List<Asset> downloads = new ArrayList<>();
        final Map<StorageService.ComponentCoordinates, Component> components = new ConcurrentHashMap<>();
        final StorageService mock = mock(StorageService.class);

        FakeStorage() {
            when(mock.ensureComponent(any(), any())).thenAnswer(inv -> components.computeIfAbsent(inv.getArgument(1), c -> {
                Component component = new Component();
                component.setName(c.name());
                component.setVersion(c.version());
                try {
                    var id = Component.class.getDeclaredField("id");
                    id.setAccessible(true);
                    id.set(component, UUID.randomUUID());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
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
                synchronized (downloads) {
                    downloads.add(inv.getArgument(0));
                }
                return null;
            }).when(mock).recordDownload(any());
        }

        StoredContent put(Repository repo, String path, byte[] bytes, String contentType, boolean metadata) {
            return putStream(repo, path, new ByteArrayInputStream(bytes), contentType, metadata);
        }

        StoredContent putStream(Repository repo, String path, InputStream in, String contentType, boolean metadata) {
            try {
                BlobRef ref = blobs.store(in);
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
                return find(repo, path).orElseThrow().readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String text(Repository repo, String path) {
            return new String(bytes(repo, path), StandardCharsets.UTF_8);
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
            return repo.getId() + "|" + StorageService.normalise(path);
        }
    }
}
