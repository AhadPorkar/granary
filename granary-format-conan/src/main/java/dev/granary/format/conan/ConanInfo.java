package dev.granary.format.conan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a {@code conaninfo.txt} into the document the package search returns for a binary: every
 * {@code [section]} becomes a property, holding an object of {@code key=value} lines or, for sections such as
 * {@code [requires]} whose lines are not assignments, an array of the lines. This is what {@code conan_server} and
 * Artifactory produce, so the client shows settings and options the same way for every remote.
 *
 * <p>The one exception is the {@code [recipe_hash]} of a Conan 1.x package, a single bare line that becomes a string:
 * Conan 1.x compares it with the recipe's manifest hash to tell whether the binary is outdated.
 *
 * <p>The parser is deliberately forgiving: a malformed header or a stray line is skipped rather than failing the
 * request, because a search must never break on one odd package.
 */
public final class ConanInfo {

    private static final String RECIPE_HASH = "recipe_hash";
    private static final Pattern SECTION = Pattern.compile("^\\[([a-zA-Z_][a-zA-Z0-9_]{0,49})]\\s*$");

    private ConanInfo() {
    }

    public static ObjectNode parse(String text) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        for (String raw : (text == null ? "" : text).split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            if (line.charAt(0) == '[') {
                Matcher m = SECTION.matcher(line);
                current = m.matches() ? sections.computeIfAbsent(m.group(1), k -> new ArrayList<>()) : null;
                continue;
            }
            if (current != null) {
                current.add(line);
            }
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            List<String> lines = section.getValue();
            if (section.getKey().equals(RECIPE_HASH) && lines.size() == 1 && lines.getFirst().indexOf('=') < 0) {
                result.put(RECIPE_HASH, lines.getFirst());
            } else {
                result.set(section.getKey(), sectionNode(lines));
            }
        }
        return result;
    }

    private static tools.jackson.databind.JsonNode sectionNode(List<String> lines) {
        boolean assignments = lines.stream().allMatch(l -> l.indexOf('=') >= 0);
        if (assignments) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            for (String line : lines) {
                int eq = line.indexOf('=');
                object.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
            return object;
        }
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        lines.forEach(array::add);
        return array;
    }
}
