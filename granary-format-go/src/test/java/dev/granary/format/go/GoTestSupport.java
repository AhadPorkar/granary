package dev.granary.format.go;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Fixtures shared by the Go unit tests: repositories, requests the go command would send, response helpers. */
final class GoTestSupport {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String SERVER = "http://granary.test";

    private GoTestSupport() {
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
        r.setFormat(GoFormatHandler.FORMAT);
        r.setType(type);
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    static GranaryPrincipal anonymous() {
        return GranaryPrincipal.anonymous(Set.of());
    }

    static GranaryPrincipal user(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("developer"), Set.of("repository:write:*"),
                false, GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static RepositoryRequest request(Repository repo, String method, String path) {
        return request(repo, method, path, null);
    }

    static RepositoryRequest request(Repository repo, String method, String path, byte[] body) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        if (body != null) {
            http.setContent(body);
            http.setContentType("application/octet-stream");
        }
        return new RepositoryRequest(repo, path, http, body == null ? anonymous() : user("alice"), baseUrl(repo));
    }

    static MockHttpServletRequest http(RepositoryRequest request) {
        return (MockHttpServletRequest) request.http();
    }

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
        if (body instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        return MAPPER.writeValueAsBytes(body);
    }

    static JsonNode json(ResponseEntity<?> response) {
        return MAPPER.readTree(bodyBytes(response));
    }

    static String str(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            n = n.path(p);
        }
        return n.isString() ? n.stringValue() : null;
    }
}
