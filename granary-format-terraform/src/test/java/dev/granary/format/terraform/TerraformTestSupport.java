package dev.granary.format.terraform;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Fixtures shared by the unit tests: repositories, archives, requests and response readers. */
final class TerraformTestSupport {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String SERVER = "https://granary.test";

    /** A real public key block is not needed: the handler only checks the armor header. */
    static final String ARMOR = "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nmQINBF…\n-----END PGP PUBLIC KEY BLOCK-----\n";

    private TerraformTestSupport() {
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
        r.setFormat(TerraformFormatHandler.FORMAT);
        r.setType(type);
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    /** A small but genuine zip archive whose single entry carries {@code seed}, so digests differ per seed. */
    static byte[] zip(String seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("terraform-provider-demo_v1.0.0_x5"));
            zip.write(("provider binary " + seed).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** gzip bytes: enough for the handler, which checks the magic number and nothing else. */
    static byte[] tgz(String seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(("module archive " + seed).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static String sha256(byte[] bytes) {
        return dev.granary.core.storage.Checksums.hex("SHA-256", bytes);
    }

    static byte[] signature() {
        return new byte[] {(byte) 0x89, 0x02, 0x33, 0x04, 0x00, 0x01, 0x08, 0x00};
    }

    static String gpgKeyJson(String keyId) {
        return "{\"key_id\":\"" + keyId + "\",\"ascii_armor\":" + MAPPER.writeValueAsString(ARMOR) + "}";
    }

    static GranaryPrincipal anonymous() {
        return GranaryPrincipal.anonymous(Set.of());
    }

    static GranaryPrincipal user(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("developer"), Set.of("repository:write:*"),
                false, GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static RepositoryRequest request(Repository repo, String method, String path) {
        return request(repo, method, path, null, null);
    }

    static RepositoryRequest request(Repository repo, String method, String path, byte[] body, String contentType) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        if (body != null) {
            http.setContent(body);
            http.setContentType(contentType == null ? "application/octet-stream" : contentType);
        }
        return new RepositoryRequest(repo, path, http, anonymous(), baseUrl(repo));
    }

    static MockHttpServletRequest http(RepositoryRequest request) {
        return (MockHttpServletRequest) request.http();
    }

    static StoredContent content(MemoryBlobStore blobs, String path, byte[] bytes, boolean metadata) {
        try {
            var ref = blobs.store(new java.io.ByteArrayInputStream(bytes));
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

    /** The response body as text, whatever Spring type the handler used. */
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
        if (body instanceof java.util.Map<?, ?> map) {
            return MAPPER.writeValueAsBytes(map);
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    static JsonNode json(ResponseEntity<?> response) {
        return MAPPER.readTree(body(response));
    }

    static String str(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            n = n.path(p);
        }
        return n.isString() ? n.stringValue() : null;
    }
}
