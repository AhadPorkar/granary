package dev.granary.format.npm;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fixtures shared by the npm unit tests: repositories, publish documents the way the npm CLI builds them, requests. */
final class NpmTestSupport {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String SERVER = "http://granary.test";

    private NpmTestSupport() {
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
        r.setFormat(NpmFormatHandler.FORMAT);
        r.setType(type);
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    static ObjectNode json(String text) {
        return (ObjectNode) MAPPER.readTree(text);
    }

    static byte[] bytes(JsonNode node) {
        return MAPPER.writeValueAsBytes(node);
    }

    /** Navigates {@code path} and returns the string there, or {@code null}. */
    static String str(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            n = n.path(p);
        }
        return n.isString() ? n.stringValue() : null;
    }

    /** Any bytes will do: the registry never looks inside a tarball. */
    static byte[] tarball(String seed) {
        return ("\u001f\u008b tarball " + seed).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The document {@code npm publish} sends: one version, its dist digests, the dist-tag and the tarball as
     * a base64 attachment named {@code {name}-{version}.tgz} (scope included, as libnpmpublish does).
     */
    static ObjectNode publishDocument(String name, String version, byte[] tarball, String tag) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("_id", name);
        doc.put("name", name);
        doc.put("description", "description of " + name + "@" + version);
        doc.put("readme", "# " + name + " " + version);
        doc.putObject("dist-tags").put(tag, version);
        ObjectNode v = doc.putObject("versions").putObject(version);
        v.put("name", name);
        v.put("version", version);
        v.put("description", "description of " + name + "@" + version);
        v.put("_id", name + "@" + version);
        v.putObject("dependencies");
        ObjectNode dist = v.putObject("dist");
        dist.put("integrity", NpmIntegrity.integrity(tarball));
        dist.put("shasum", NpmIntegrity.shasum(tarball));
        String file = name + "-" + version + ".tgz";
        dist.put("tarball", "http://client.test/repository/whatever/" + name + "/-/" + file);
        doc.put("access", "public");
        ObjectNode attachment = doc.putObject("_attachments").putObject(file);
        attachment.put("content_type", "application/octet-stream");
        attachment.put("data", Base64.getEncoder().encodeToString(tarball));
        attachment.put("length", tarball.length);
        return doc;
    }

    static NpmPublishRequest publishRequest(String name, String version, byte[] tarball, String tag) {
        return NpmPublishRequest.parse(MAPPER, bytes(publishDocument(name, version, tarball, tag)));
    }

    static GranaryPrincipal anonymous() {
        return GranaryPrincipal.anonymous(Set.of());
    }

    static GranaryPrincipal user(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("developer"), Set.of("repository:write:*"),
                false, GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static RepositoryRequest request(Repository repo, String method, String path) {
        return request(repo, method, path, null, anonymous());
    }

    static RepositoryRequest request(Repository repo, String method, String path, byte[] body, GranaryPrincipal principal) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        if (body != null) {
            http.setContent(body);
            http.setContentType("application/json");
        }
        return new RepositoryRequest(repo, path, http, principal, baseUrl(repo));
    }

    static MockHttpServletRequest http(RepositoryRequest request) {
        return (MockHttpServletRequest) request.http();
    }

    /** The response body as text, whatever Spring type the handler used. */
    static String body(ResponseEntity<?> response) {
        Object body = response.getBody();
        if (body == null) {
            return "";
        }
        if (body instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (body instanceof Resource resource) {
            try (var in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        return body.toString();
    }

    static byte[] bodyBytes(ResponseEntity<?> response) {
        Object body = response.getBody();
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
        return body(response).getBytes(StandardCharsets.UTF_8);
    }

    static JsonNode jsonBody(ResponseEntity<?> response) {
        return MAPPER.readTree(body(response));
    }
}
