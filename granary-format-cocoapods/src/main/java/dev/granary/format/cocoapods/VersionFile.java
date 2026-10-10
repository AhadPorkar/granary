package dev.granary.format.cocoapods;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code CocoaPods-version.yml}, the first file a CDN source is asked for: the supported CocoaPods range
 * ({@code min}, {@code max}), the latest release ({@code last}, which {@code pod} also uses to recognise a
 * CDN source at all) and the shard {@code prefix_lengths}.
 *
 * <p>The parser reads the block style the CDN writes, flow lists ({@code [1, 1, 1]}) and the JSON form, which
 * is enough for every spec repository in the wild; it is not a general YAML parser.
 *
 * @param min           minimum CocoaPods version, or {@code null}
 * @param max           maximum CocoaPods version, or {@code null}
 * @param last          latest CocoaPods release, or {@code null}
 * @param prefixLengths shard prefix lengths, or {@code null} when the file has none
 */
public record VersionFile(String min, String max, String last, List<Integer> prefixLengths) {

    public static final String PATH = "CocoaPods-version.yml";
    public static final String CONTENT_TYPE = "text/yaml;charset=UTF-8";
    public static final String DEFAULT_MIN = "1.0.0";
    public static final String DEFAULT_LAST = "1.16.2";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What hosted repositories and groups serve: CocoaPods 1.0 and later, standard sharding. */
    public static VersionFile standard() {
        return new VersionFile(DEFAULT_MIN, null, DEFAULT_LAST, ShardLayout.STANDARD.prefixLengths());
    }

    /** The shard layout the file declares; a missing or unusable {@code prefix_lengths} means no sharding at all. */
    public ShardLayout layout() {
        if (prefixLengths == null) {
            return new ShardLayout(List.of());
        }
        try {
            return new ShardLayout(prefixLengths);
        } catch (IllegalArgumentException e) {
            return ShardLayout.STANDARD;
        }
    }

    /** The document in the CDN's own formatting. */
    public String render() {
        StringBuilder sb = new StringBuilder("---\n");
        if (min != null) {
            sb.append("min: ").append(min).append('\n');
        }
        if (max != null) {
            sb.append("max: ").append(max).append('\n');
        }
        if (last != null) {
            sb.append("last: ").append(last).append('\n');
        }
        if (prefixLengths != null && !prefixLengths.isEmpty()) {
            sb.append("prefix_lengths:\n");
            for (int length : prefixLengths) {
                sb.append("- ").append(length).append('\n');
            }
        }
        return sb.toString();
    }

    public byte[] toBytes() {
        return render().getBytes(StandardCharsets.UTF_8);
    }

    /** Parses the document; empty when it is not a mapping at all. Unknown keys are ignored. */
    public static Optional<VersionFile> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        if (trimmed.startsWith("---")) {
            trimmed = trimmed.substring(3).strip();
        }
        if (trimmed.startsWith("{")) {
            return parseJson(trimmed);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> list = null;
        for (String raw : text.split("\r?\n")) {
            String line = stripComment(raw);
            if (line.isBlank() || line.strip().equals("---") || line.strip().equals("...")) {
                continue;
            }
            String stripped = line.strip();
            if (stripped.startsWith("- ") || stripped.equals("-")) {
                if (list == null) {
                    return Optional.empty();
                }
                list.add(unquote(stripped.substring(1).strip()));
                continue;
            }
            if (Character.isWhitespace(line.charAt(0))) {
                continue; // nested structure the format does not use
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return Optional.empty();
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.isEmpty()) {
                list = new ArrayList<>();
                values.put(key, list);
            } else if (value.startsWith("[") && value.endsWith("]")) {
                List<String> flow = new ArrayList<>();
                for (String item : value.substring(1, value.length() - 1).split(",")) {
                    if (!item.isBlank()) {
                        flow.add(unquote(item.strip()));
                    }
                }
                values.put(key, flow);
                list = null;
            } else {
                values.put(key, unquote(value));
                list = null;
            }
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new VersionFile(scalar(values.get("min")), scalar(values.get("max")), scalar(values.get("last")),
                lengths(values.get("prefix_lengths"))));
    }

    private static Optional<VersionFile> parseJson(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            List<Integer> lengths = null;
            JsonNode pl = node.get("prefix_lengths");
            if (pl != null && pl.isArray()) {
                List<String> items = new ArrayList<>();
                pl.forEach(n -> items.add(n.asString()));
                lengths = lengths(items);
            }
            return Optional.of(new VersionFile(text(node.get("min")), text(node.get("max")), text(node.get("last")), lengths));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isContainer() ? null : node.asString();
    }

    private static String scalar(Object value) {
        return value instanceof String s && !s.isEmpty() && !s.equals("~") && !s.equals("null") ? s : null;
    }

    /** The integer list, or {@code null} when the key is missing or any entry is not a positive integer. */
    private static List<Integer> lengths(Object value) {
        if (!(value instanceof List<?> items)) {
            return null;
        }
        List<Integer> result = new ArrayList<>();
        for (Object item : items) {
            try {
                int n = Integer.parseInt(String.valueOf(item).strip());
                if (n < 1 || n > ShardLayout.MAX_TOTAL_LENGTH) {
                    return null;
                }
                result.add(n);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return result;
    }

    private static String stripComment(String line) {
        if (line.stripLeading().startsWith("#")) {
            return "";
        }
        int hash = line.indexOf(" #");
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("\"") && value.endsWith("\"")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
