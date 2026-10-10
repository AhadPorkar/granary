package dev.granary.format.docker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MediaTypesTest {

    @Test
    void parsesAcceptHeaders() {
        assertThat(MediaTypes.accepted(null)).isEmpty();
        assertThat(MediaTypes.accepted("*/*")).isEmpty();
        assertThat(MediaTypes.accepted(MediaTypes.OCI_MANIFEST + ";q=0.9, " + MediaTypes.DOCKER_MANIFEST + " , */*"))
                .containsExactly(MediaTypes.OCI_MANIFEST, MediaTypes.DOCKER_MANIFEST);
        assertThat(MediaTypes.accepted("Application/VND.OCI.Image.Index.V1+JSON")).containsExactly(MediaTypes.OCI_INDEX);
    }

    @Test
    void classifiesTypes() {
        assertThat(MediaTypes.isManifestType(MediaTypes.OCI_INDEX)).isTrue();
        assertThat(MediaTypes.isManifestType(MediaTypes.DOCKER_MANIFEST)).isTrue();
        assertThat(MediaTypes.isManifestType("application/json")).isFalse();
        assertThat(MediaTypes.isManifestType(null)).isFalse();
        assertThat(MediaTypes.isIndexType(MediaTypes.DOCKER_MANIFEST_LIST)).isTrue();
        assertThat(MediaTypes.isIndexType(MediaTypes.OCI_MANIFEST)).isFalse();
        assertThat(MediaTypes.acceptsIndex(MediaTypes.DEFAULT_ACCEPT)).isTrue();
        assertThat(MediaTypes.acceptsIndex(MediaTypes.OCI_MANIFEST)).isFalse();
        assertThat(MediaTypes.acceptsIndex(null)).isFalse();
    }

    @Test
    void upstreamAcceptKeepsTheClientsListOnlyWhenItNamesManifests() {
        assertThat(MediaTypes.upstreamAccept(null)).isEqualTo(MediaTypes.DEFAULT_ACCEPT);
        assertThat(MediaTypes.upstreamAccept("*/*")).isEqualTo(MediaTypes.DEFAULT_ACCEPT);
        assertThat(MediaTypes.upstreamAccept("application/json")).isEqualTo(MediaTypes.DEFAULT_ACCEPT);
        String docker = MediaTypes.DOCKER_MANIFEST + ", " + MediaTypes.DOCKER_MANIFEST_LIST;
        assertThat(MediaTypes.upstreamAccept(docker)).isEqualTo(docker);
    }

    @Test
    void variantKeyIsEmptyForIndexCapableClientsAndStableOtherwise() {
        assertThat(MediaTypes.variantKey(null)).isEmpty();
        assertThat(MediaTypes.variantKey("*/*")).isEmpty();
        assertThat(MediaTypes.variantKey(MediaTypes.DEFAULT_ACCEPT)).isEmpty();
        assertThat(MediaTypes.variantKey("text/plain")).isEmpty();
        String single = MediaTypes.variantKey(MediaTypes.DOCKER_MANIFEST);
        assertThat(single).startsWith("@").hasSize(9);
        assertThat(MediaTypes.variantKey(MediaTypes.DOCKER_MANIFEST + ";q=0.5, */*")).isEqualTo(single);
        String both = MediaTypes.variantKey(MediaTypes.DOCKER_MANIFEST + ", " + MediaTypes.OCI_MANIFEST);
        assertThat(MediaTypes.variantKey(MediaTypes.OCI_MANIFEST + ", " + MediaTypes.DOCKER_MANIFEST)).isEqualTo(both);
        assertThat(both).isNotEqualTo(single);
    }

    @Test
    void stripsParameters() {
        assertThat(MediaTypes.stripParameters("application/json; charset=utf-8")).isEqualTo("application/json");
        assertThat(MediaTypes.stripParameters(" application/json ")).isEqualTo("application/json");
        assertThat(MediaTypes.stripParameters(null)).isEmpty();
    }
}
