package dev.granary.format.docker;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RootRoute;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Fixtures shared by the docker unit tests: repositories, manifests the way the clients build them, requests. */
final class DockerTestSupport {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String SERVER = "http://granary.test";

    private DockerTestSupport() {
    }

    static Repository hosted(String name) {
        return repository(name, RepositoryType.HOSTED);
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

    static Repository repository(String name, RepositoryType type) {
        Repository r = new Repository();
        r.setId(UUID.randomUUID());
        r.setName(name);
        r.setFormat(DockerFormatHandler.FORMAT);
        r.setType(type);
        r.setBlobStore("default");
        return r;
    }

    static String baseUrl(Repository repo) {
        return SERVER + "/repository/" + repo.getName();
    }

    static byte[] layer(String seed) {
        return ("layer " + seed).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] config(String seed) {
        return ("{\"architecture\":\"amd64\",\"os\":\"linux\",\"seed\":\"" + seed + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /** An OCI image manifest referencing {@code config} and {@code layers}. */
    static byte[] imageManifest(String mediaType, byte[] config, byte[]... layers) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("schemaVersion", 2);
        m.put("mediaType", mediaType);
        ObjectNode c = m.putObject("config");
        c.put("mediaType", mediaType.equals(MediaTypes.DOCKER_MANIFEST)
                ? "application/vnd.docker.container.image.v1+json" : "application/vnd.oci.image.config.v1+json");
        c.put("digest", Digest.of(config).toString());
        c.put("size", config.length);
        ArrayNode ls = m.putArray("layers");
        for (byte[] layer : layers) {
            ObjectNode l = ls.addObject();
            l.put("mediaType", mediaType.equals(MediaTypes.DOCKER_MANIFEST)
                    ? "application/vnd.docker.image.rootfs.diff.tar.gzip" : "application/vnd.oci.image.layer.v1.tar+gzip");
            l.put("digest", Digest.of(layer).toString());
            l.put("size", layer.length);
        }
        return MAPPER.writeValueAsBytes(m);
    }

    static byte[] ociManifest(byte[] config, byte[]... layers) {
        return imageManifest(MediaTypes.OCI_MANIFEST, config, layers);
    }

    /** An OCI index listing {@code manifests}, each tagged with a platform. */
    static byte[] index(String mediaType, Map<String, byte[]> platformManifests) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("schemaVersion", 2);
        m.put("mediaType", mediaType);
        ArrayNode ms = m.putArray("manifests");
        platformManifests.forEach((arch, manifest) -> {
            ObjectNode d = ms.addObject();
            d.put("mediaType", mediaType.equals(MediaTypes.DOCKER_MANIFEST_LIST) ? MediaTypes.DOCKER_MANIFEST : MediaTypes.OCI_MANIFEST);
            d.put("digest", Digest.of(manifest).toString());
            d.put("size", manifest.length);
            ObjectNode p = d.putObject("platform");
            p.put("architecture", arch);
            p.put("os", "linux");
        });
        return MAPPER.writeValueAsBytes(m);
    }

    /** An OCI artifact manifest with a {@code subject} (a signature or SBOM attached to an image). */
    static byte[] referrer(byte[] subject, String artifactType, byte[] config, byte[] layer) {
        ObjectNode m = (ObjectNode) MAPPER.readTree(ociManifest(config, layer));
        m.put("artifactType", artifactType);
        ObjectNode s = m.putObject("subject");
        s.put("mediaType", MediaTypes.OCI_MANIFEST);
        s.put("digest", Digest.of(subject).toString());
        s.put("size", subject.length);
        m.putObject("annotations").put("org.example.kind", artifactType);
        return MAPPER.writeValueAsBytes(m);
    }

    static GranaryPrincipal anonymous() {
        return GranaryPrincipal.anonymous(Set.of());
    }

    static GranaryPrincipal anonymousReader() {
        return GranaryPrincipal.anonymous(Set.of("repository:read:*"));
    }

    static GranaryPrincipal user(String username) {
        return new GranaryPrincipal(UUID.randomUUID(), username, Set.of("developer"),
                Set.of("repository:read:*", "repository:write:*"), false, GranaryPrincipal.AuthMethod.API_TOKEN);
    }

    static RepositoryRequest get(Repository repo, String path) {
        return request(repo, "GET", path, null, user("dev"));
    }

    static RepositoryRequest request(Repository repo, String method, String path, byte[] body, GranaryPrincipal principal) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/" + repo.getName() + "/" + path);
        if (body != null) {
            http.setContent(body);
        }
        return new RepositoryRequest(repo, path, http, principal, baseUrl(repo));
    }

    /** A request that came through root routing ({@code /v2/...} at the server root). */
    static RepositoryRequest rootRequest(Repository repo, String method, String path, String rootPath, byte[] body) {
        RepositoryRequest request = request(repo, method, path, body, user("dev"));
        http(request).setAttribute(RootRoute.ATTRIBUTE, rootPath);
        return request;
    }

    static RepositoryRequest withHeader(RepositoryRequest request, String name, String value) {
        http(request).addHeader(name, value);
        return request;
    }

    static RepositoryRequest withQuery(RepositoryRequest request, String name, String value) {
        http(request).setParameter(name, value);
        return request;
    }

    static MockHttpServletRequest http(RepositoryRequest request) {
        return (MockHttpServletRequest) request.http();
    }

    static String header(ResponseEntity<?> response, String name) {
        return response.getHeaders().getFirst(name);
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
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    static JsonNode json(ResponseEntity<?> response) {
        return MAPPER.readTree(body(response));
    }

    static String errorCode(ResponseEntity<?> response) {
        return json(response).path("errors").path(0).path("code").stringValue();
    }
}
