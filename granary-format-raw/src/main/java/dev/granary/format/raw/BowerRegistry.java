package dev.granary.format.raw;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Bower registry protocol: a package is a name and the git URL it is cloned from. The registry answers
 * {@code packages/<name>} with {@code {"name","url"}}, {@code packages} and {@code packages/search/<q>} with
 * lists of the same objects, and {@code POST packages} (form fields {@code name} and {@code url}) registers one.
 */
public final class BowerRegistry {

    public record Entry(String name, String url) {
    }

    public static final String PACKAGES = "packages";
    public static final String SEARCH_PREFIX = "packages/search/";

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");
    private static final Pattern URL = Pattern.compile(
            "^(?:(?:git|git\\+https|git\\+ssh|git\\+file|https?|ssh|file|svn\\+ssh)://\\S+|[\\w.-]+@[\\w.-]+:\\S+|/\\S+)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BowerRegistry() {
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** git, ssh and https clone URLs, scp-like {@code user@host:path} and absolute local paths. */
    public static boolean validUrl(String url) {
        return url != null && URL.matcher(url.trim()).matches();
    }

    public static String packagePath(String name) {
        return PACKAGES + "/" + name;
    }

    /** The package name of a {@code packages/<name>} path; empty for the list, search and anything deeper. */
    public static Optional<String> packageName(String path) {
        if (!path.startsWith(PACKAGES + "/") || path.startsWith(SEARCH_PREFIX)) {
            return Optional.empty();
        }
        String name = path.substring(PACKAGES.length() + 1);
        return name.isEmpty() || name.contains("/") ? Optional.empty() : Optional.of(name);
    }

    public static Optional<String> searchQuery(String path) {
        if (!path.startsWith(SEARCH_PREFIX) || path.length() == SEARCH_PREFIX.length()) {
            return Optional.empty();
        }
        return Optional.of(path.substring(SEARCH_PREFIX.length()));
    }

    public static byte[] json(Entry entry) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", entry.name());
        node.put("url", entry.url());
        return JSON.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] json(List<Entry> entries) {
        ArrayNode array = JSON.createArrayNode();
        for (Entry entry : entries) {
            ObjectNode node = array.addObject();
            node.put("name", entry.name());
            node.put("url", entry.url());
        }
        return JSON.writeValueAsString(array).getBytes(StandardCharsets.UTF_8);
    }

    /** Parses one registry entry; malformed documents yield empty. */
    public static Optional<Entry> parse(byte[] json) {
        try {
            JsonNode node = JSON.readTree(json);
            return entry(node);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Parses a registry list; malformed documents and elements are skipped. */
    public static List<Entry> parseList(byte[] json) {
        List<Entry> result = new ArrayList<>();
        try {
            JsonNode node = JSON.readTree(json);
            if (node.isArray()) {
                for (JsonNode element : node) {
                    entry(element).ifPresent(result::add);
                }
            } else {
                entry(node).ifPresent(result::add);
            }
        } catch (RuntimeException e) {
            return result;
        }
        return result;
    }

    private static Optional<Entry> entry(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode name = node.path("name");
        JsonNode url = node.path("url");
        if (!name.isString() || !url.isString() || name.asString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Entry(name.asString(), url.asString()));
    }

    /** Case-insensitive substring search on names, like the public registry. */
    public static List<Entry> search(List<Entry> entries, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.name().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(entry);
            }
        }
        return result;
    }

    /** Unions lists in order; the first list that has a name wins. */
    public static List<Entry> merge(List<List<Entry>> lists) {
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (List<Entry> list : lists) {
            for (Entry entry : list) {
                byName.putIfAbsent(entry.name(), entry);
            }
        }
        List<Entry> result = new ArrayList<>(byName.values());
        result.sort((a, b) -> a.name().compareTo(b.name()));
        return result;
    }
}
