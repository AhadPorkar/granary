package dev.granary.format.helm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProvenanceTest {

    @Test
    void readsAProvenanceFileSignedByHelm() {
        byte[] archive = TarFixtures.resource("charts/demo-app-0.2.0.tgz");
        Provenance prov = Provenance.parse(TarFixtures.resource("charts/demo-app-0.2.0.tgz.prov"));
        assertThat(prov.name()).isEqualTo("demo-app");
        assertThat(prov.version()).isEqualTo("0.2.0");
        assertThat(prov.sha256Of("demo-app-0.2.0.tgz")).contains(HelmTestSupport.sha256(archive));
        assertThat(prov.sha256Of("other.tgz")).isEmpty();
    }

    @Test
    void undoesDashEscapingAndCrLf() {
        String text = HelmTestSupport.provenance("demo", "1.0.0", "ab".repeat(32))
                .replace("type: application\n", "type: application\nkeywords:\n- - web\n")
                .replace("\n", "\r\n");
        Provenance prov = Provenance.parse(text.getBytes(StandardCharsets.UTF_8));
        assertThat(prov.name()).isEqualTo("demo");
        assertThat(prov.sha256Of("demo-1.0.0.tgz")).contains("ab".repeat(32));
    }

    @Test
    void digestWithoutPrefixIsTakenAsIs() {
        String text = HelmTestSupport.provenance("demo", "1.0.0", "x").replace("sha256:x", "cafe");
        assertThat(Provenance.parse(text.getBytes(StandardCharsets.UTF_8)).sha256Of("demo-1.0.0.tgz")).contains("cafe");
    }

    @Test
    void refusesWhatIsNotAProvenanceFile() {
        assertThatThrownBy(() -> Provenance.parse(new byte[0])).hasMessageContaining("empty");
        assertThatThrownBy(() -> Provenance.parse("name: demo\nversion: 1.0.0\n".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("clear-signed");
        String noName = HelmTestSupport.provenance("demo", "1.0.0", "ab").replace("name: demo\n", "");
        assertThatThrownBy(() -> Provenance.parse(noName.getBytes(StandardCharsets.UTF_8))).hasMessageContaining("no valid chart");
        String badVersion = HelmTestSupport.provenance("demo", "one", "ab");
        assertThatThrownBy(() -> Provenance.parse(badVersion.getBytes(StandardCharsets.UTF_8))).hasMessageContaining("version");
        String badYaml = HelmTestSupport.provenance("demo", "1.0.0", "ab").replace("description:", "description: [");
        assertThatThrownBy(() -> Provenance.parse(badYaml.getBytes(StandardCharsets.UTF_8))).hasMessageContaining("not valid YAML");
        String traversal = HelmTestSupport.provenance("../demo", "1.0.0", "ab");
        assertThatThrownBy(() -> Provenance.parse(traversal.getBytes(StandardCharsets.UTF_8))).hasMessageContaining("no valid chart");
        byte[] huge = new byte[HelmYaml.SMALL_DOCUMENT_LIMIT + 1];
        assertThatThrownBy(() -> Provenance.parse(huge)).hasMessageContaining("too large");
    }
}
