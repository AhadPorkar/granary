package dev.granary.format.helm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HelmYamlTest {

    private static Map<String, Object> load(String yaml) {
        return HelmYaml.loadMap(yaml.getBytes(StandardCharsets.UTF_8), HelmYaml.SMALL_DOCUMENT_LIMIT);
    }

    @Test
    void scalarsStayAsWritten() {
        Map<String, Object> doc = load("""
                version: 1.10
                appVersion: 010
                created: 2021-03-02T18:36:45.469691322Z
                answer: yes
                port: 8080
                ratio: .5
                hex: 0x1F
                deprecated: true
                legacy: False
                nothing: null
                tilde: ~
                empty:
                """);
        assertThat(doc.get("version")).isEqualTo("1.10");
        assertThat(doc.get("appVersion")).isEqualTo("010");
        assertThat(doc.get("created")).isEqualTo("2021-03-02T18:36:45.469691322Z");
        assertThat(doc.get("answer")).isEqualTo("yes");
        assertThat(doc.get("port")).isEqualTo("8080");
        assertThat(doc.get("ratio")).isEqualTo(".5");
        assertThat(doc.get("hex")).isEqualTo("0x1F");
        assertThat(doc.get("deprecated")).isEqualTo(true);
        assertThat(doc.get("legacy")).isEqualTo(false);
        assertThat(doc).containsEntry("nothing", null).containsEntry("tilde", null).containsEntry("empty", null);
    }

    @Test
    void explicitTagsAreNormalisedToStrings() {
        Map<String, Object> doc = load("""
                a: !!int 5
                b: !!float 1.5
                c: !!timestamp 2020-01-01T00:00:00Z
                d: !!binary aGVsbG8=
                e: !!set {x, y}
                """);
        assertThat(doc.get("a")).isEqualTo("5");
        assertThat(doc.get("b")).isEqualTo("1.5");
        assertThat(doc.get("c")).isEqualTo("2020-01-01T00:00:00Z");
        assertThat(doc.get("d")).isEqualTo("aGVsbG8=");
        assertThat(doc.get("e")).isInstanceOf(List.class);
    }

    @Test
    void keysAreStrings() {
        Map<String, Object> doc = load("true: a\n1: b\n~: c\n");
        assertThat(doc.keySet()).containsExactly("true", "1", "");
    }

    @Test
    void unicodeAndBomAreRead() {
        byte[] withBom = ("\uFEFFdescription: Charts for Kubernetes – ±∑ 𝄞\n").getBytes(StandardCharsets.UTF_8);
        assertThat(HelmYaml.loadMap(withBom, 1000).get("description")).isEqualTo("Charts for Kubernetes – ±∑ 𝄞");
    }

    @Test
    void emptyDocumentIsAnEmptyMap() {
        assertThat(load("")).isEmpty();
        assertThat(load("# only a comment\n")).isEmpty();
    }

    @Test
    void malformedInputIsRefusedCleanly() {
        assertThatThrownBy(() -> load("a: [1, 2")).isInstanceOf(HelmYaml.InvalidYamlException.class);
        assertThatThrownBy(() -> load("- a\n- b\n")).isInstanceOf(HelmYaml.InvalidYamlException.class)
                .hasMessageContaining("mapping");
        assertThatThrownBy(() -> load("just text")).isInstanceOf(HelmYaml.InvalidYamlException.class);
        assertThatThrownBy(() -> load("a: 1\n---\nb: 2\n")).isInstanceOf(HelmYaml.InvalidYamlException.class);
        assertThatThrownBy(() -> load("a: !!python/object:os.system x")).isInstanceOf(HelmYaml.InvalidYamlException.class);
        assertThatThrownBy(() -> load("a: !<tag:yaml.org,2002:java.net.URL> [http://x]")).isInstanceOf(HelmYaml.InvalidYamlException.class);
    }

    @Test
    void aliasBombsAreRefused() {
        StringBuilder bomb = new StringBuilder("a0: &a0 [x, x, x, x, x, x, x, x, x, x]\n");
        for (int i = 1; i < 12; i++) {
            bomb.append("a").append(i).append(": &a").append(i).append(" [");
            for (int j = 0; j < 10; j++) {
                bomb.append(j == 0 ? "" : ", ").append("*a").append(i - 1);
            }
            bomb.append("]\n");
        }
        assertThatThrownBy(() -> load(bomb.toString())).isInstanceOf(HelmYaml.InvalidYamlException.class);
    }

    @Test
    void deepNestingIsRefused() {
        String deep = "a: " + "[".repeat(5000) + "]".repeat(5000);
        assertThatThrownBy(() -> load(deep)).isInstanceOf(HelmYaml.InvalidYamlException.class);
    }

    @Test
    void codePointLimitIsEnforced() {
        String big = "a: " + "x".repeat(2000) + "\n";
        assertThatThrownBy(() -> HelmYaml.loadMap(big.getBytes(StandardCharsets.UTF_8), 100))
                .isInstanceOf(HelmYaml.InvalidYamlException.class);
    }

    @Test
    void dumpQuotesWhatWouldReadBackAsAnotherType() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", "1.10");
        doc.put("appVersion", "1.0");
        doc.put("created", "2021-03-02T18:36:45Z");
        doc.put("flag", "true");
        doc.put("word", "yes");
        doc.put("real", true);
        doc.put("multi", "line one\nline two");
        doc.put("control", "bell\u0007");
        doc.put("unicode", "∑∞✓");
        doc.put("list", List.of("a", "1"));
        String yaml = new String(HelmYaml.dump(doc), StandardCharsets.UTF_8);
        assertThat(yaml).contains("version: '1.10'").contains("appVersion: '1.0'").contains("flag: 'true'")
                .contains("real: true").contains("unicode: ∑∞✓").contains("list:\n- a\n- '1'\n")
                .doesNotContain("!!binary").doesNotContain("&id");
        Map<String, Object> back = HelmYaml.loadMap(HelmYaml.dump(doc), HelmYaml.SMALL_DOCUMENT_LIMIT);
        assertThat(back).isEqualTo(doc);
    }

    @Test
    void sharedObjectsAreDumpedWithoutAnchors() {
        List<String> shared = List.of("x");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("a", shared);
        doc.put("b", shared);
        assertThat(new String(HelmYaml.dump(doc), StandardCharsets.UTF_8)).doesNotContain("&").doesNotContain("*");
    }
}
