package dev.granary.format.conan;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.Checksums;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Fixtures shared by the unit tests: repositories, recipe files, requests and response readers. */
final class ConanTestSupport {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String SERVER = "https://granary.test";
    static final String RREV = "a9a8d3f6b8f0ba5c3a2e1cd04f0a1e3b";
    static final String RREV2 = "0d1c5d2b6e4f3a2918273645abcdef01";
    static final String PID = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
    static final String PREV = "5b4cf1ff2bfa3a7b9f1c4d2e6a8b0c1d";
    static final String PREV2 = "77aa66bb55cc44dd33ee22ff11009988";
    static final String CONANINFO = """
            [settings]
                arch=x86_64
                build_type=Release
                os=Linux

            [options]
                shared=False

            [requires]
                zlib/1.3.Z
            """;

    private ConanTestSupport() {
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
        r.setFormat(ConanFormatHandler.FORMAT);
        r.setType(type);
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    static ConanReference ref(String name, String version) {
        return new ConanReference(name, version, null, null);
    }

    /** gzip bytes standing in for {@code conan_export.tgz} / {@code conan_package.tgz}. */
    static byte[] tgz(String seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(("archive " + seed).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String sha1(byte[] bytes) {
        return Checksums.hex("SHA-1", bytes);
    }

    static GranaryPrincipal anonymous() {
        return GranaryPrincipal.anonymous(Set.of("repository:read:*"));
    }

    static GranaryPrincipal user(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("developer"), Set.of("repository:write:*"),
                false, GranaryPrincipal.AuthMethod.PASSWORD);
    }

    static RepositoryRequest request(Repository repo, String method, String path) {
        return request(repo, method, path, null);
    }

    static RepositoryRequest request(Repository repo, String method, String path, byte[] body) {
        String query = null;
        String bare = path;
        int q = path.indexOf('?');
        if (q >= 0) {
            bare = path.substring(0, q);
            query = path.substring(q + 1);
        }
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + bare);
        if (query != null) {
            http.setQueryString(query);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                http.addParameter(key, value);
            }
        }
        if (body != null) {
            http.setContent(body);
            http.setContentType("application/octet-stream");
        }
        return new RepositoryRequest(repo, bare, http, anonymous(), baseUrl(repo));
    }

    /** A PUT of one file the way the client sends it: the body plus its SHA-1. */
    static RepositoryRequest upload(Repository repo, String path, byte[] body, String username) {
        RepositoryRequest request = request(repo, "PUT", path, body);
        http(request).addHeader(ConanHostedWriter.CHECKSUM_SHA1, sha1(body));
        return as(request, username);
    }

    static RepositoryRequest as(RepositoryRequest request, String username) {
        return new RepositoryRequest(request.repository(), request.path(), request.http(), user(username), request.baseUrl());
    }

    static MockHttpServletRequest http(RepositoryRequest request) {
        return (MockHttpServletRequest) request.http();
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

    static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    static String str(JsonNode node, String... path) {
        JsonNode n = node;
        for (String p : path) {
            n = n.path(p);
        }
        return n.isString() ? n.stringValue() : null;
    }
}
