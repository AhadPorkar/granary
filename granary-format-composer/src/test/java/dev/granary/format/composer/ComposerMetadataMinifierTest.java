package dev.granary.format.composer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code psr-log.json} is Packagist's p2 document for psr/log; {@code psr-log.expanded.json} is what Composer's
 * own {@code MetadataMinifier::expand} makes of it, and Composer's {@code minify} of that gives the original back.
 */
class ComposerMetadataMinifierTest {

    @Test
    void expandsPackagistMetadataExactlyLikeComposer() {
        for (String fixture : List.of("psr-log", "psr-log-dev")) {
            JsonNode minified = ComposerTestSupport.resourceJson(fixture + ".json").get("packages").get("psr/log");
            JsonNode expected = ComposerTestSupport.resourceJson(fixture + ".expanded.json");

            List<ObjectNode> expanded = MetadataMinifier.expand(list(minified));

            assertThat(expanded).hasSize(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertThat(expanded.get(i)).as(fixture + " entry " + i).isEqualTo(expected.get(i));
            }
        }
    }

    @Test
    void minifiesBackToWhatPackagistServes() {
        for (String fixture : List.of("psr-log", "psr-log-dev")) {
            JsonNode original = ComposerTestSupport.resourceJson(fixture + ".json").get("packages").get("psr/log");
            List<ObjectNode> expanded = MetadataMinifier.expand(list(original));

            List<ObjectNode> minified = MetadataMinifier.minify(expanded);

            assertThat(minified).hasSize(original.size());
            for (int i = 0; i < original.size(); i++) {
                assertThat(minified.get(i)).as(fixture + " entry " + i).isEqualTo(original.get(i));
            }
        }
    }

    @Test
    void unsetMarkerRemovesInheritedKeys() {
        List<ObjectNode> minified = List.of(
                ComposerTestSupport.object("{\"name\":\"a/b\",\"version\":\"2.0.0\",\"require\":{\"php\":\">=8\"},\"suggest\":{\"x/y\":\"why\"}}"),
                ComposerTestSupport.object("{\"version\":\"1.0.0\",\"suggest\":\"__unset\"}"));

        List<ObjectNode> expanded = MetadataMinifier.expand(minified);

        assertThat(expanded.get(1)).isEqualTo(ComposerTestSupport.object(
                "{\"name\":\"a/b\",\"version\":\"1.0.0\",\"require\":{\"php\":\">=8\"}}"));
        assertThat(MetadataMinifier.minify(expanded)).containsExactlyElementsOf(minified);
    }

    @Test
    void doesNotModifyItsInput() {
        ObjectNode first = ComposerTestSupport.object("{\"name\":\"a/b\",\"version\":\"2.0.0\",\"type\":\"library\"}");
        ObjectNode second = ComposerTestSupport.object("{\"version\":\"1.0.0\"}");
        MetadataMinifier.expand(List.of(first, second)).get(1).put("type", "changed");
        assertThat(first.get("type").stringValue()).isEqualTo("library");
        assertThat(second.has("type")).isFalse();
    }

    @Test
    void emptyListsStayEmpty() {
        assertThat(MetadataMinifier.expand(List.of())).isEmpty();
        assertThat(MetadataMinifier.minify(List.of())).isEmpty();
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }
}
