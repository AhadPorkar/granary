package dev.granary.format.helm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the metadata of an uploaded chart the way Helm's {@code Metadata.Validate} does, and checks that every
 * field has the type Helm's index reader expects. One entry of the wrong type in {@code index.yaml} makes the whole
 * index unreadable for every Helm client, so such charts are refused at upload instead.
 *
 * <p>Only the fields Helm knows end up in the index entry, exactly as {@code helm repo index} does.
 */
public final class ChartMetadata {

    static final Set<String> STRING_FIELDS = Set.of("name", "home", "version", "description", "icon", "apiVersion",
            "condition", "tags", "appVersion", "kubeVersion", "type");
    static final Set<String> STRING_LIST_FIELDS = Set.of("sources", "keywords");
    static final List<String> KNOWN_FIELDS = List.of("apiVersion", "name", "version", "appVersion", "kubeVersion",
            "description", "type", "home", "icon", "sources", "keywords", "maintainers", "condition", "tags",
            "deprecated", "annotations", "dependencies");

    private static final Pattern ALIAS = Pattern.compile("^[a-zA-Z0-9-_]+$");

    private ChartMetadata() {
    }

    /** The index entry fields of the chart (without urls, digest and created), validated. */
    public static Map<String, Object> of(ChartArchive.Contents contents) {
        Map<String, Object> chart = contents.chartYaml();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : KNOWN_FIELDS) {
            Object value = chart.get(field);
            if (value == null) {
                continue;
            }
            result.put(field, checked(field, value));
        }
        String apiVersion = (String) result.get("apiVersion");
        if (apiVersion == null || apiVersion.isBlank()) {
            // Helm 2 accepted charts without apiVersion and Helm 3 still loads them as v1 charts
            result.put("apiVersion", apiVersion = "v1");
        }
        if ("v1".equals(apiVersion) && !result.containsKey("dependencies") && contents.requirements() != null) {
            Object dependencies = contents.requirements().get("dependencies");
            if (dependencies != null) {
                result.put("dependencies", checked("dependencies", dependencies));
            }
        }
        String name = (String) result.get("name");
        if (name == null || name.isBlank()) {
            throw invalid("chart.metadata.name is required");
        }
        if (!HelmPath.isValidName(name)) {
            throw invalid("chart.metadata.name \"" + name + "\" is invalid: use letters, digits, '.', '_' and '-' only");
        }
        String version = (String) result.get("version");
        if (version == null || version.isBlank()) {
            throw invalid("chart.metadata.version is required");
        }
        if (!HelmVersion.isValid(version)) {
            throw invalid("chart.metadata.version \"" + version + "\" is invalid");
        }
        Object type = result.get("type");
        if (type != null && !type.equals("") && !type.equals("application") && !type.equals("library")) {
            throw invalid("chart.metadata.type must be application or library");
        }
        return result;
    }

    private static Object checked(String field, Object value) {
        if (STRING_FIELDS.contains(field)) {
            if (!(value instanceof String) && !(value instanceof Boolean)) {
                throw invalid("chart.metadata." + field + " must be a string");
            }
            return value.toString();
        }
        if (STRING_LIST_FIELDS.contains(field)) {
            return stringList(field, value);
        }
        return switch (field) {
            case "deprecated" -> {
                if (value instanceof Boolean) {
                    yield value;
                }
                throw invalid("chart.metadata.deprecated must be true or false");
            }
            case "annotations" -> stringMap("chart.metadata.annotations", value);
            case "maintainers" -> maintainers(value);
            case "dependencies" -> dependencies(value);
            default -> value;
        };
    }

    private static List<Object> stringList(String field, Object value) {
        if (!(value instanceof List<?> list)) {
            throw invalid("chart.metadata." + field + " must be a list of strings");
        }
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> || item instanceof List<?>) {
                throw invalid("chart.metadata." + field + " must be a list of strings");
            }
            result.add(item == null ? "" : item.toString());
        }
        return result;
    }

    private static Map<String, Object> stringMap(String what, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(what + " must be a map of strings");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> || v instanceof List<?>) {
                throw invalid(what + "." + e.getKey() + " must be a string");
            }
            result.put(String.valueOf(e.getKey()), v == null ? "" : v.toString());
        }
        return result;
    }

    private static List<Object> maintainers(Object value) {
        if (!(value instanceof List<?> list)) {
            throw invalid("chart.metadata.maintainers must be a list");
        }
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                throw invalid("maintainers must not contain empty or null nodes");
            }
            result.add(stringMap("chart.metadata.maintainers", item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> dependencies(Object value) {
        if (!(value instanceof List<?> list)) {
            throw invalid("chart.metadata.dependencies must be a list");
        }
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw invalid("dependencies must not contain empty or null nodes");
            }
            Map<String, Object> dependency = new LinkedHashMap<>((Map<String, Object>) raw);
            for (String key : List.of("name", "version", "repository", "condition", "alias")) {
                Object v = dependency.get(key);
                if (v instanceof Map<?, ?> || v instanceof List<?>) {
                    throw invalid("dependency " + key + " must be a string");
                }
                if (v != null) {
                    dependency.put(key, v.toString());
                }
            }
            Object tags = dependency.get("tags");
            if (tags != null) {
                dependency.put("tags", stringList("dependencies.tags", tags));
            }
            Object enabled = dependency.get("enabled");
            if (enabled != null && !(enabled instanceof Boolean)) {
                throw invalid("dependency enabled must be true or false");
            }
            Object alias = dependency.get("alias");
            if (alias != null && !alias.toString().isEmpty() && !ALIAS.matcher(alias.toString()).matches()) {
                throw invalid("dependency \"" + dependency.get("name") + "\" has disallowed characters in the alias");
            }
            result.add(dependency);
        }
        return result;
    }

    private static ChartArchive.InvalidChartException invalid(String message) {
        return new ChartArchive.InvalidChartException(message);
    }
}
