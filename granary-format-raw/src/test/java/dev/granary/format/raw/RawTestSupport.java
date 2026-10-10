package dev.granary.format.raw;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixtures shared by the raw unit tests: repositories, requests the way clients send them, response helpers. */
final class RawTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SERVER = "http://granary.test";

    private RawTestSupport() {
    }

    static Repository hosted(String name, String format) {
        return repository(name, format, RepositoryType.HOSTED);
    }

    static Repository proxy(String name, String format, String remoteUrl) {
        Repository r = repository(name, format, RepositoryType.PROXY);
        r.setRemoteUrl(remoteUrl);
        return r;
    }

    static Repository group(String name, String format, String... members) {
        Repository r = repository(name, format, RepositoryType.GROUP);
        r.setMembers(List.of(members));
        return r;
    }

    private static Repository repository(String name, String format, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(format);
        r.setType(type);
        r.setAllowRedeploy(false);
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    static GranaryProperties properties(Path dataDir) {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        return properties;
    }

    static GranaryPrincipal anonymous() {
        return GranaryPrincipal.anonymous(Set.of());
    }

    static GranaryPrincipal user(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("developer"), Set.of("repository:write:*"),
                false, GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static RepositoryRequest request(Repository repo, String method, String path) {
        return request(repo, method, path, null, null, Map.of());
    }

    static RepositoryRequest request(Repository repo, String method, String path, byte[] body, String contentType) {
        return request(repo, method, path, body, contentType, Map.of());
    }

    static RepositoryRequest request(Repository repo, String method, String path, byte[] body, String contentType,
                                     Map<String, String> headers) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        if (body != null) {
            http.setContent(body);
        }
        if (contentType != null) {
            http.setContentType(contentType);
            http.addHeader("Content-Type", contentType);
        }
        headers.forEach(http::addHeader);
        return new RepositoryRequest(repo, path, http, anonymous(), baseUrl(repo));
    }

    static RepositoryRequest request(Repository repo, String method, String path, String body) {
        return request(repo, method, path, body.getBytes(StandardCharsets.UTF_8), "application/octet-stream");
    }

    /** A multipart request with one file part per (field, file name, content) triple. */
    static RepositoryRequest multipart(Repository repo, String method, String path, MockMultipartFile... files) {
        MockMultipartHttpServletRequest http = new MockMultipartHttpServletRequest();
        http.setMethod(method);
        http.setRequestURI("/repository/" + repo.getName() + "/" + path);
        http.setContentType("multipart/form-data; boundary=xyz");
        for (MockMultipartFile file : files) {
            http.addFile(file);
        }
        return new RepositoryRequest(repo, path, http, anonymous(), baseUrl(repo));
    }

    static ConnectContext context(Repository repo, boolean anonymousRead) {
        return new ConnectContext(repo, baseUrl(repo), SERVER, ConnectContext.USERNAME_PLACEHOLDER,
                ConnectContext.TOKEN_PLACEHOLDER, anonymousRead);
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
        if (body instanceof Map<?, ?> map) {
            return JSON.writeValueAsString(map).getBytes(StandardCharsets.UTF_8);
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    static JsonNode json(ResponseEntity<?> response) {
        return JSON.readTree(body(response));
    }

    static String header(ResponseEntity<?> response, String name) {
        return response.getHeaders().getFirst(name);
    }
}
