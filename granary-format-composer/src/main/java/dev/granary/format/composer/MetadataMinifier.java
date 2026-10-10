package dev.granary.format.composer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code composer/2.0} minification of {@code p2} documents, a port of {@code composer/metadata-minifier}.
 * The first version entry is complete; every following entry lists only the keys whose value differs from the
 * entry before it, and {@code "__unset"} removes a key the previous entry had.
 */
public final class MetadataMinifier {

    /** The value of the top-level {@code minified} key of a minified document. */
    public static final String FORMAT = "composer/2.0";
    public static final String UNSET = "__unset";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private MetadataMinifier() {
    }

    /** {@code MetadataMinifier::expand}: complete entries, each an independent copy. Non-object entries are skipped. */
    public static List<ObjectNode> expand(List<? extends JsonNode> versions) {
        List<ObjectNode> expanded = new ArrayList<>(versions.size());
        ObjectNode current = null;
        for (JsonNode node : versions) {
            if (!(node instanceof ObjectNode data)) {
                continue;
            }
            if (current == null || current.isEmpty()) {
                // like PHP, an empty entry is falsy and the next one starts afresh
                current = data.deepCopy();
                expanded.add(current.deepCopy());
                continue;
            }
            for (Map.Entry<String, JsonNode> e : data.properties()) {
                if (e.getValue().isString() && UNSET.equals(e.getValue().stringValue())) {
                    current.remove(e.getKey());
                } else {
                    current.set(e.getKey(), e.getValue().deepCopy());
                }
            }
            expanded.add(current.deepCopy());
        }
        return expanded;
    }

    /** {@code MetadataMinifier::minify}: the inverse of {@link #expand}. */
    public static List<ObjectNode> minify(List<? extends JsonNode> versions) {
        List<ObjectNode> minified = new ArrayList<>(versions.size());
        ObjectNode last = null;
        for (JsonNode node : versions) {
            if (!(node instanceof ObjectNode version)) {
                continue;
            }
            if (last == null || last.isEmpty()) {
                last = version.deepCopy();
                minified.add(version.deepCopy());
                continue;
            }
            ObjectNode diff = NODES.objectNode();
            for (Map.Entry<String, JsonNode> e : version.properties()) {
                JsonNode previous = last.get(e.getKey());
                if (previous == null || !previous.equals(e.getValue())) {
                    diff.set(e.getKey(), e.getValue().deepCopy());
                    last.set(e.getKey(), e.getValue().deepCopy());
                }
            }
            List<String> removed = new ArrayList<>();
            for (String key : last.propertyNames()) {
                if (!version.has(key)) {
                    removed.add(key);
                }
            }
            for (String key : removed) {
                diff.put(key, UNSET);
                last.remove(key);
            }
            minified.add(diff);
        }
        return minified;
    }
}
