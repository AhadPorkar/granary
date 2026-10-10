package dev.granary.format.docker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.storage.StorageService;
import org.junit.jupiter.api.Test;

class DockerLayoutTest {

    private static final String HEX = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Digest DIGEST = Digest.sha256(HEX);

    @Test
    void paths() {
        assertThat(DockerLayout.blobPath(DIGEST)).isEqualTo("blobs/sha256/" + HEX);
        assertThat(DockerLayout.manifestPath("library/alpine", DIGEST)).isEqualTo("manifests/library/alpine/sha256/" + HEX);
        assertThat(DockerLayout.manifestPrefix("app")).isEqualTo("manifests/app/sha256/");
        assertThat(DockerLayout.tagPath("acme/app", "1.0")).isEqualTo("tags/acme/app/1.0");
        assertThat(DockerLayout.tagPath("acme/app", "1.0", "@abcd1234")).isEqualTo("tags/acme/app/1.0@abcd1234");
        assertThat(DockerLayout.tagPath("acme/app", "1.0", null)).isEqualTo("tags/acme/app/1.0");
        assertThat(DockerLayout.tagsPrefix("acme/app")).isEqualTo("tags/acme/app/");
        assertThat(DockerLayout.tagListCachePath("acme/app")).isEqualTo("tags/acme/app/.list");
    }

    @Test
    void onlyTagsAreMetadata() {
        assertThat(DockerLayout.isMetadata("tags/acme/app/1.0")).isTrue();
        assertThat(DockerLayout.isMetadata("tags/acme/app/.list")).isTrue();
        assertThat(DockerLayout.isMetadata("manifests/acme/app/sha256/" + HEX)).isFalse();
        assertThat(DockerLayout.isMetadata("blobs/sha256/" + HEX)).isFalse();
        assertThat(DockerLayout.isMetadata(null)).isFalse();
    }

    @Test
    void tagOfRecognisesOwnTagsOnly() {
        assertThat(DockerLayout.tagOf("acme/app", "tags/acme/app/1.0")).contains("1.0");
        assertThat(DockerLayout.tagOf("acme/app", "tags/acme/app/1.0@abcd1234")).contains("1.0");
        assertThat(DockerLayout.tagOf("acme/app", "tags/acme/app/.list")).isEmpty();
        assertThat(DockerLayout.tagOf("acme", "tags/acme/app/1.0")).isEmpty();
        assertThat(DockerLayout.tagOf("acme/app", "tags/acme/app/")).isEmpty();
        assertThat(DockerLayout.tagOf("acme/app", "tags/other/1.0")).isEmpty();
        assertThat(DockerLayout.tagOf("acme/app", null)).isEmpty();
    }

    @Test
    void manifestPathsCanBeReadBack() {
        String path = DockerLayout.manifestPath("acme/team/app", DIGEST);
        assertThat(DockerLayout.nameOfManifestPath(path)).contains("acme/team/app");
        assertThat(DockerLayout.digestOfManifestPath(path)).contains(DIGEST);
        assertThat(DockerLayout.nameOfManifestPath("manifests/Bad/sha256/" + HEX)).isEmpty();
        assertThat(DockerLayout.nameOfManifestPath("manifests/sha256/" + HEX)).isEmpty();
        assertThat(DockerLayout.nameOfManifestPath("manifests/app/sha256/" + HEX + "/x")).isEmpty();
        assertThat(DockerLayout.nameOfManifestPath("blobs/sha256/" + HEX)).isEmpty();
        assertThat(DockerLayout.nameOfManifestPath(null)).isEmpty();
        assertThat(DockerLayout.digestOfManifestPath("manifests/app/sha256/zz")).isEmpty();
        assertThat(DockerLayout.digestOfManifestPath(null)).isEmpty();
    }

    @Test
    void coordinatesSplitNamespaceFromName() {
        StorageService.ComponentCoordinates c = DockerLayout.coordinates("acme/team/app", "1.0");
        assertThat(c.namespace()).isEqualTo("acme/team");
        assertThat(c.name()).isEqualTo("app");
        assertThat(c.version()).isEqualTo("1.0");
        StorageService.ComponentCoordinates single = DockerLayout.coordinates("alpine", "sha256:" + HEX);
        assertThat(single.namespace()).isEmpty();
        assertThat(single.name()).isEqualTo("alpine");
    }
}
