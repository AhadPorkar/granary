package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class ComposerP2DocumentTest {

    private static final Instant WHEN = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void parsesMinifiedPackagistMetadata() {
        P2Document doc = P2Document.parse(ComposerTestSupport.JSON, ComposerTestSupport.resource("psr-log.json"), "psr/log", WHEN)
                .orElseThrow();

        assertThat(doc.name()).isEqualTo("psr/log");
        assertThat(doc.versions()).hasSize(12);
        assertThat(P2Document.version(doc.versions().getFirst())).isEqualTo("3.0.2");
        // expanded: later entries inherit fields the minified form only states once
        assertThat(doc.versions().get(1).get("name").stringValue()).isEqualTo("psr/log");
        assertThat(doc.extras().has("security-advisories")).isTrue();
        assertThat(doc.extras().has("minified")).isFalse();
        assertThat(doc.lastModified()).isEqualTo(WHEN);
    }

    @Test
    void writesTheMinifiedWireFormat() {
        byte[] original = ComposerTestSupport.resource("psr-log.json");
        P2Document doc = P2Document.parse(ComposerTestSupport.JSON, original, "psr/log", WHEN).orElseThrow();

        ObjectNode json = doc.toJson();

        assertThat(json.get("minified").stringValue()).isEqualTo("composer/2.0");
        assertThat(json.get("packages").get("psr/log")).isEqualTo(ComposerTestSupport.parse(original).get("packages").get("psr/log"));
        assertThat(json.get("security-advisories")).isNotNull();
    }

    @Test
    void parsesUnminifiedListsAndVersionMaps() {
        String list = "{\"packages\":{\"acme/lib\":[{\"name\":\"acme/lib\",\"version\":\"1.0.0\"},{\"name\":\"acme/lib\",\"version\":\"2.0.0\"}]}}";
        String map = "{\"packages\":{\"acme/lib\":{\"1.0.0\":{\"name\":\"acme/lib\",\"version\":\"1.0.0\"}}}}";

        assertThat(parse(list, "acme/lib").orElseThrow().versions()).hasSize(2);
        assertThat(parse(map, "acme/lib").orElseThrow().versions()).hasSize(1);
    }

    @Test
    void findsThePackageKeyCaseInsensitively() {
        String json = "{\"packages\":{\"Acme/Lib\":[{\"name\":\"Acme/Lib\",\"version\":\"1.0.0\"}]}}";
        assertThat(parse(json, "acme/lib").orElseThrow().versions()).hasSize(1);
    }

    @Test
    void documentsWithoutThePackageAreEmpty() {
        assertThat(parse("{\"packages\":{}}", "acme/lib").orElseThrow().isEmpty()).isTrue();
        assertThat(parse("{\"packages\":[]}", "acme/lib").orElseThrow().isEmpty()).isTrue();
        assertThat(parse("{}", "acme/lib").orElseThrow().isEmpty()).isTrue();
    }

    @Test
    void rejectsWhatIsNotAJsonObject() {
        assertThat(parse("[]", "acme/lib")).isEmpty();
        assertThat(parse("<html>", "acme/lib")).isEmpty();
        assertThat(parse("", "acme/lib")).isEmpty();
    }

    @Test
    void findsVersionsByPrettyOrNormalisedForm() {
        P2Document doc = P2Document.parse(ComposerTestSupport.JSON, ComposerTestSupport.resource("psr-log.json"), "psr/log", WHEN)
                .orElseThrow();
        assertThat(doc.find("3.0.2")).isPresent();
        assertThat(doc.find("3.0.2.0")).isPresent();
        assertThat(doc.find("v3.0.2")).isPresent();
        assertThat(doc.find("9.9.9")).isEmpty();
        assertThat(doc.find("not a version")).isEmpty();
    }

    @Test
    void normalisedVersionPrefersTheEntrysOwnButRecomputesTheDefaultBranchAlias() {
        assertThat(P2Document.normalized(ComposerTestSupport.object("{\"version\":\"v1.2\",\"version_normalized\":\"1.2.0.0\"}")))
                .isEqualTo("1.2.0.0");
        assertThat(P2Document.normalized(ComposerTestSupport.object("{\"version\":\"v1.2\"}"))).isEqualTo("1.2.0.0");
        assertThat(P2Document.normalized(ComposerTestSupport.object("{\"version\":\"dev-main\",\"version_normalized\":\"9999999-dev\"}")))
                .isEqualTo("dev-main");
        assertThat(P2Document.normalized(ComposerTestSupport.object("{\"version\":\"weird version\"}"))).isEqualTo("weird version");
        assertThat(P2Document.normalized(ComposerTestSupport.object("{}"))).isNull();
    }

    @Test
    void emptyDocumentsRoundTrip() {
        P2Document empty = P2Document.empty("acme/lib");
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.toJson().get("packages").get("acme/lib").isEmpty()).isTrue();
        assertThat(empty.withVersions(List.of(ComposerTestSupport.object("{\"version\":\"1.0.0\"}"))).isEmpty()).isFalse();
    }

    private static Optional<P2Document> parse(String json, String name) {
        return P2Document.parse(ComposerTestSupport.JSON, json.getBytes(StandardCharsets.UTF_8), name, WHEN);
    }
}
