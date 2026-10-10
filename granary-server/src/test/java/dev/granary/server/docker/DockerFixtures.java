package dev.granary.server.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.granary.core.storage.Checksums;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Images the way docker builds and pushes them, plus the HTTP choreography of a push through MockMvc. */
final class DockerFixtures {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();
    static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
    static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
    static final String DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json";
    static final String DOCKER_MANIFEST_LIST = "application/vnd.docker.distribution.manifest.list.v2+json";
    static final String ALL_MANIFESTS = OCI_INDEX + ", " + DOCKER_MANIFEST_LIST + ", " + OCI_MANIFEST + ", " + DOCKER_MANIFEST;

    private DockerFixtures() {
    }

    /** A pushed image: the manifest bytes, their digest and the blobs it references. */
    record Image(byte[] manifest, String digest, byte[] config, byte[] layer) {
    }

    static String digest(byte[] bytes) {
        return "sha256:" + Checksums.hex("SHA-256", bytes);
    }

    static byte[] layer(String seed) {
        return ("layer-" + seed + "-" + "x".repeat(64)).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] config(String seed) {
        return ("{\"architecture\":\"amd64\",\"os\":\"linux\",\"rootfs\":{\"type\":\"layers\"},\"seed\":\"" + seed + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] manifest(String mediaType, byte[] config, byte[]... layers) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("schemaVersion", 2);
        m.put("mediaType", mediaType);
        ObjectNode c = m.putObject("config");
        c.put("mediaType", mediaType.equals(DOCKER_MANIFEST)
                ? "application/vnd.docker.container.image.v1+json" : "application/vnd.oci.image.config.v1+json");
        c.put("digest", digest(config));
        c.put("size", config.length);
        ArrayNode ls = m.putArray("layers");
        for (byte[] layer : layers) {
            ObjectNode l = ls.addObject();
            l.put("mediaType", mediaType.equals(DOCKER_MANIFEST)
                    ? "application/vnd.docker.image.rootfs.diff.tar.gzip" : "application/vnd.oci.image.layer.v1.tar+gzip");
            l.put("digest", digest(layer));
            l.put("size", layer.length);
        }
        return MAPPER.writeValueAsBytes(m);
    }

    static byte[] index(String mediaType, Map<String, byte[]> platformManifests) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("schemaVersion", 2);
        m.put("mediaType", mediaType);
        ArrayNode ms = m.putArray("manifests");
        platformManifests.forEach((arch, manifest) -> {
            ObjectNode d = ms.addObject();
            d.put("mediaType", mediaType.equals(DOCKER_MANIFEST_LIST) ? DOCKER_MANIFEST : OCI_MANIFEST);
            d.put("digest", digest(manifest));
            d.put("size", manifest.length);
            d.putObject("platform").put("architecture", arch).put("os", "linux");
        });
        return MAPPER.writeValueAsBytes(m);
    }

    static byte[] referrer(byte[] subject, String artifactType, byte[] config, byte[] layer) {
        ObjectNode m = (ObjectNode) MAPPER.readTree(manifest(OCI_MANIFEST, config, layer));
        m.put("artifactType", artifactType);
        ObjectNode s = m.putObject("subject");
        s.put("mediaType", OCI_MANIFEST);
        s.put("digest", digest(subject));
        s.put("size", subject.length);
        return MAPPER.writeValueAsBytes(m);
    }

    static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    static String errorCode(MvcResult result) throws Exception {
        return json(result).path("errors").path(0).path("code").stringValue();
    }

    /** POST to start an upload, then PUT the whole blob with its digest, the way a monolithic push goes. */
    static String uploadBlob(MockMvc mvc, String url, String name, byte[] data, String auth) throws Exception {
        MvcResult start = mvc.perform(post(url + "/v2/" + name + "/blobs/uploads/").header(HttpHeaders.AUTHORIZATION, auth))
                .andReturn();
        assertThat(start.getResponse().getStatus()).as("upload start").isEqualTo(202);
        String location = start.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isNotBlank();
        String digest = digest(data);
        MvcResult done = mvc.perform(put(URI.create(location)).queryParam("digest", digest)
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType("application/octet-stream").content(data))
                .andReturn();
        assertThat(done.getResponse().getStatus()).as("upload complete").isEqualTo(201);
        assertThat(done.getResponse().getHeader("Docker-Content-Digest")).isEqualTo(digest);
        return digest;
    }

    static MvcResult putManifest(MockMvc mvc, String url, String name, String reference, byte[] manifest, String mediaType,
                                 String auth) throws Exception {
        return mvc.perform(put(url + "/v2/" + name + "/manifests/" + reference)
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(mediaType).content(manifest))
                .andReturn();
    }

    /** Pushes a complete single-platform image: config, one layer, manifest tagged {@code tag}. */
    static Image pushImage(MockMvc mvc, String url, String name, String tag, String auth) throws Exception {
        byte[] config = config(name + ":" + tag);
        byte[] layer = layer(name + ":" + tag);
        uploadBlob(mvc, url, name, config, auth);
        uploadBlob(mvc, url, name, layer, auth);
        byte[] manifest = manifest(OCI_MANIFEST, config, layer);
        MvcResult result = putManifest(mvc, url, name, tag, manifest, OCI_MANIFEST, auth);
        assertThat(result.getResponse().getStatus()).as("manifest push").isEqualTo(201);
        return new Image(manifest, digest(manifest), config, layer);
    }
}
