package dev.granary.format.docker;

import static dev.granary.format.docker.DockerTestSupport.MAPPER;
import static dev.granary.format.docker.DockerTestSupport.config;
import static dev.granary.format.docker.DockerTestSupport.imageManifest;
import static dev.granary.format.docker.DockerTestSupport.index;
import static dev.granary.format.docker.DockerTestSupport.layer;
import static dev.granary.format.docker.DockerTestSupport.ociManifest;
import static dev.granary.format.docker.DockerTestSupport.referrer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ManifestTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesAnOciImageManifest() {
        byte[] cfg = config("a");
        byte[] l1 = layer("1");
        byte[] l2 = layer("2");
        Manifest m = Manifest.parse(MAPPER, ociManifest(cfg, l1, l2), null);
        assertThat(m.mediaType()).isEqualTo(MediaTypes.OCI_MANIFEST);
        assertThat(m.index()).isFalse();
        assertThat(m.config().digest()).isEqualTo(Digest.of(cfg));
        assertThat(m.config().size()).isEqualTo(cfg.length);
        assertThat(m.layers()).hasSize(2);
        assertThat(m.referencedBlobs()).containsExactly(Digest.of(cfg), Digest.of(l1), Digest.of(l2));
        assertThat(m.referencedManifests()).isEmpty();
        assertThat(m.subjectDigest()).isEmpty();
    }

    @Test
    void parsesADockerManifestListAsIndex() {
        byte[] amd = imageManifest(MediaTypes.DOCKER_MANIFEST, config("amd"), layer("amd"));
        byte[] arm = imageManifest(MediaTypes.DOCKER_MANIFEST, config("arm"), layer("arm"));
        Manifest m = Manifest.parse(MAPPER, index(MediaTypes.DOCKER_MANIFEST_LIST, Map.of("amd64", amd, "arm64", arm)), null);
        assertThat(m.index()).isTrue();
        assertThat(m.mediaType()).isEqualTo(MediaTypes.DOCKER_MANIFEST_LIST);
        assertThat(m.referencedManifests()).containsExactlyInAnyOrder(Digest.of(amd), Digest.of(arm));
        assertThat(m.referencedBlobs()).isEmpty();
    }

    @Test
    void mediaTypeFallsBackToContentTypeThenToShape() {
        byte[] noType = utf8("{\"schemaVersion\":2,\"config\":{\"digest\":\"" + Digest.of(config("x")) + "\",\"size\":1},\"layers\":[]}");
        assertThat(Manifest.parse(MAPPER, noType, MediaTypes.DOCKER_MANIFEST + "; charset=utf-8").mediaType())
                .isEqualTo(MediaTypes.DOCKER_MANIFEST);
        assertThat(Manifest.parse(MAPPER, noType, "application/json").mediaType()).isEqualTo(MediaTypes.OCI_MANIFEST);
        byte[] noTypeIndex = utf8("{\"schemaVersion\":2,\"manifests\":[]}");
        assertThat(Manifest.parse(MAPPER, noTypeIndex, null).mediaType()).isEqualTo(MediaTypes.OCI_INDEX);
        assertThat(Manifest.declaredMediaType(MAPPER, noType)).isEmpty();
        assertThat(Manifest.declaredMediaType(MAPPER, ociManifest(config("y")))).contains(MediaTypes.OCI_MANIFEST);
        assertThat(Manifest.declaredMediaType(MAPPER, utf8("not json"))).isEmpty();
    }

    @Test
    void foreignLayersAreNotRequiredLocally() {
        byte[] cfg = config("w");
        String foreign = "{\"schemaVersion\":2,\"mediaType\":\"" + MediaTypes.DOCKER_MANIFEST + "\","
                + "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",\"digest\":\"" + Digest.of(cfg) + "\",\"size\":" + cfg.length + "},"
                + "\"layers\":[{\"mediaType\":\"application/vnd.docker.image.rootfs.foreign.diff.tar.gzip\",\"digest\":\""
                + Digest.of(layer("win")) + "\",\"size\":5,\"urls\":[\"https://example.test/layer\"]}]}";
        Manifest m = Manifest.parse(MAPPER, utf8(foreign), null);
        assertThat(m.layers().get(0).isForeign()).isTrue();
        assertThat(m.referencedBlobs()).containsExactly(Digest.of(cfg));
    }

    @Test
    void subjectAndArtifactType() {
        byte[] subject = ociManifest(config("s"), layer("s"));
        Manifest m = Manifest.parse(MAPPER, referrer(subject, "application/vnd.example.sbom", config("r"), layer("r")), null);
        assertThat(m.subjectDigest()).contains(Digest.of(subject));
        assertThat(m.artifactType()).isEqualTo("application/vnd.example.sbom");
        assertThat(m.annotations()).containsEntry("org.example.kind", "application/vnd.example.sbom");
    }

    @Test
    void rejectsBrokenManifests() {
        assertInvalid(utf8("not json"), "not valid JSON");
        assertInvalid(utf8("[]"), "JSON object");
        assertInvalid(utf8("{\"schemaVersion\":1,\"fsLayers\":[]}"), "schema version 2");
        assertInvalid(utf8("{\"schemaVersion\":3}"), "schema version 2");
        assertInvalid(utf8("{\"schemaVersion\":2,\"config\":{\"digest\":\"sha256:zz\"},\"layers\":[]}"), "invalid digest");
        assertInvalid(utf8("{\"schemaVersion\":2,\"layers\":[{\"digest\":\"nope\"}]}"), "invalid digest");
        assertInvalid(utf8("{\"schemaVersion\":2,\"layers\":[\"x\"]}"), "descriptor");
    }

    private static void assertInvalid(byte[] body, String message) {
        assertThatThrownBy(() -> Manifest.parse(MAPPER, body, null))
                .isInstanceOf(DockerError.class)
                .hasMessageContaining(message)
                .satisfies(e -> {
                    assertThat(((DockerError) e).code()).isEqualTo(OciErrorCode.MANIFEST_INVALID);
                    assertThat(((DockerError) e).status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }
}
