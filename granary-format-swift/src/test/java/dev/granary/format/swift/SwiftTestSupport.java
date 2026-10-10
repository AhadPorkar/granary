package dev.granary.format.swift;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockPart;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Fixtures shared by the unit tests: repositories, source archives, requests and response readers. */
final class SwiftTestSupport {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String SERVER = "https://granary.test";

    static final String MANIFEST = """
            // swift-tools-version:5.9
            import PackageDescription

            let package = Package(
                name: "Demo",
                products: [.library(name: "Demo", targets: ["Demo"])],
                targets: [.target(name: "Demo")]
            )
            """;

    static final String MANIFEST_58 = """
            // swift-tools-version:5.8
            import PackageDescription

            let package = Package(name: "Demo", targets: [.target(name: "Demo")])
            """;

    static final String METADATA = """
            {
              "description": "A demo package",
              "author": {"name": "Jane Doe", "organization": {"name": "Acme"}},
              "licenseURL": "https://example.com/acme/demo/LICENSE",
              "repositoryURLs": ["https://github.com/acme/demo", "git@github.com:acme/demo.git"]
            }
            """;

    private SwiftTestSupport() {
    }

    static Repository hosted(String name) {
        Repository r = repository(name, RepositoryType.HOSTED);
        r.setAllowRedeploy(false);
        return r;
    }

    static Repository proxy(String name, String remoteUrl) {
        Repository r = repository(name, RepositoryType.PROXY);
        r.setRemoteUrl(remoteUrl);
        return r;
    }

    static Repository group(String name, String... members) {
        Repository r = repository(name, RepositoryType.GROUP);
        r.setMembers(List.of(members));
        return r;
    }

    private static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(SwiftFormatHandler.FORMAT);
        r.setType(type);
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    // --- archives -----------------------------------------------------------------------------------

    private static final long FIXED_TIME = java.time.Instant.parse("2024-01-31T12:00:00Z").toEpochMilli();

    /** A zip with the given entries in order; a name ending in {@code /} becomes a directory entry. */
    static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(FIXED_TIME); // the same entries always make the same bytes (and checksum)
                zip.putNextEntry(entry);
                if (!e.getKey().endsWith("/")) {
                    zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A source archive as {@code swift package archive-source} lays it out: everything below {@code root/}. */
    static byte[] sourceArchive(String root, String seed, boolean withVariant) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(root + "/", "");
        entries.put(root + "/Package.swift", MANIFEST);
        if (withVariant) {
            entries.put(root + "/Package@swift-5.8.swift", MANIFEST_58);
        }
        entries.put(root + "/Sources/Demo/Demo.swift", "public let seed = \"" + seed + "\"\n");
        return zip(entries);
    }

    static byte[] sourceArchive(String seed) {
        return sourceArchive("demo", seed, false);
    }

    static String sha256(byte[] bytes) {
        return Checksums.hex("SHA-256", bytes);
    }

    // --- principals and requests --------------------------------------------------------------------

    /** An anonymous caller on a server that allows anonymous reads (the default). */
    static GranaryPrincipal anonymous() {
        return GranaryPrincipal.anonymous(Set.of("repository:read:*"));
    }

    /** An anonymous caller on a server with anonymous reads switched off. */
    static GranaryPrincipal anonymousWithoutRead() {
        return GranaryPrincipal.anonymous(Set.of());
    }

    static GranaryPrincipal user(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("developer"), Set.of("repository:write:*"),
                false, GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static RepositoryRequest request(Repository repo, String method, String path) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        return new RepositoryRequest(repo, path, http, anonymous(), baseUrl(repo));
    }

    static RepositoryRequest get(Repository repo, String path) {
        return request(repo, "GET", path);
    }

    static RepositoryRequest withPrincipal(RepositoryRequest request, GranaryPrincipal principal) {
        return new RepositoryRequest(request.repository(), request.path(), request.http(), principal, request.baseUrl());
    }

    /** A publish request the way SwiftPM sends it: multipart parts without file names. */
    static RepositoryRequest publish(Repository repo, String path, byte[] archive, String metadata) {
        MockHttpServletRequest http = new MockHttpServletRequest("PUT", "/repository/" + repo.getName() + "/" + path);
        http.setContentType("multipart/form-data; boundary=granary");
        http.addHeader("Accept", SwiftAccept.JSON);
        if (archive != null) {
            http.addPart(part(SwiftUpload.SOURCE_ARCHIVE, archive, "application/zip", "binary"));
        }
        if (metadata != null) {
            http.addPart(part(SwiftUpload.METADATA, metadata.getBytes(StandardCharsets.UTF_8), "application/json", "quoted-printable"));
        }
        return new RepositoryRequest(repo, path, http, user("publisher"), baseUrl(repo));
    }

    static MockPart part(String name, byte[] content, String contentType, String transferEncoding) {
        MockPart part = new MockPart(name, content);
        part.getHeaders().set("Content-Type", contentType);
        if (transferEncoding != null) {
            part.getHeaders().set("Content-Transfer-Encoding", transferEncoding);
        }
        return part;
    }

    static MockHttpServletRequest http(RepositoryRequest request) {
        return (MockHttpServletRequest) request.http();
    }

    static StoredContent content(MemoryBlobStore blobs, String path, byte[] bytes, boolean metadata) {
        try {
            var ref = blobs.store(new ByteArrayInputStream(bytes));
            Asset asset = new Asset();
            asset.setPath(path);
            asset.setBlobSha256(ref.sha256());
            asset.setSize(ref.size());
            asset.setMetadata(metadata);
            asset.setContentType(metadata ? "application/json" : "application/octet-stream");
            asset.setLastVerifiedAt(Instant.now());
            return new StoredContent(asset, blobs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- responses ----------------------------------------------------------------------------------

    static String body(ResponseEntity<?> response) {
        return new String(bodyBytes(response), StandardCharsets.UTF_8);
    }

    static byte[] bodyBytes(ResponseEntity<?> response) {
        Object body = response.getBody();
        if (body == null) {
            return new byte[0];
        }
        if (body instanceof byte[] bytes) {
            return bytes;
        }
        if (body instanceof Resource resource) {
            try (var in = resource.getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    static JsonNode json(ResponseEntity<?> response) {
        return MAPPER.readTree(body(response));
    }

    static String detail(ResponseEntity<?> response) {
        return json(response).path("detail").stringValue();
    }
}
