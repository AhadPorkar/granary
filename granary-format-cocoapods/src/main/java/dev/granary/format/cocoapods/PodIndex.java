package dev.granary.format.cocoapods;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The plain-text index documents of a CDN-style spec repository and how they are merged.
 *
 * <ul>
 *   <li>a shard index ({@code all_pods_versions_a_7_5.txt}) has one line per pod: {@code Name/1.0.0/1.1.0};</li>
 *   <li>{@code all_pods.txt} has one pod name per line;</li>
 *   <li>{@code deprecated_podspecs.txt} has one podspec path per line
 *       ({@code Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json}).</li>
 * </ul>
 * Rendered documents are sorted by code point (the CDN's order) and end every line with a newline, so the same
 * content always produces the same bytes and the same ETag.
 */
public final class PodIndex {

    public static final String CONTENT_TYPE = "text/plain;charset=UTF-8";

    private PodIndex() {
    }

    /** Pod name to versions, in file order; blank lines are skipped, a line without versions is a pod with none. */
    public static Map<String, List<String>> parseShard(String text) {
        Map<String, List<String>> pods = new LinkedHashMap<>();
        for (String line : lines(text)) {
            String[] parts = line.split("/");
            String name = parts[0];
            if (name.isEmpty()) {
                continue;
            }
            List<String> versions = pods.computeIfAbsent(name, k -> new ArrayList<>());
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isEmpty() && !versions.contains(parts[i])) {
                    versions.add(parts[i]);
                }
            }
        }
        return pods;
    }

    /** One line per pod with at least one version, names by code point, versions lowest first. */
    public static byte[] renderShard(Map<String, ? extends Collection<String>> pods) {
        TreeMap<String, Collection<String>> sorted = new TreeMap<>(PodNames.CODE_POINT_ORDER);
        pods.forEach((name, versions) -> {
            if (!versions.isEmpty()) {
                sorted.put(name, versions);
            }
        });
        StringBuilder sb = new StringBuilder();
        sorted.forEach((name, versions) -> {
            sb.append(name);
            TreeSet<String> ordered = new TreeSet<>(PodVersion.ORDER);
            ordered.addAll(versions);
            for (String version : ordered) {
                sb.append('/').append(version);
            }
            sb.append('\n');
        });
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The union of several shard indexes: a pod lists every version any of them has. Member order only matters
     * for which member answers the podspec of a version; the merged index itself is sorted.
     */
    public static Map<String, List<String>> mergeShards(List<Map<String, List<String>>> shards) {
        Map<String, LinkedHashSet<String>> union = new LinkedHashMap<>();
        for (Map<String, List<String>> shard : shards) {
            shard.forEach((name, versions) -> union.computeIfAbsent(name, k -> new LinkedHashSet<>()).addAll(versions));
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        union.forEach((name, versions) -> result.put(name, new ArrayList<>(versions)));
        return result;
    }

    /** Only the pods whose MD5 starts with {@code hexPrefix}; used when a member shards more coarsely than the group. */
    public static Map<String, List<String>> filterByHashPrefix(Map<String, List<String>> pods, String hexPrefix) {
        if (hexPrefix.isEmpty()) {
            return pods;
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        pods.forEach((name, versions) -> {
            if (PodNames.md5Hex(name).startsWith(hexPrefix)) {
                result.put(name, versions);
            }
        });
        return result;
    }

    /** Non-blank lines without surrounding whitespace or carriage returns. */
    public static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** Distinct lines sorted by code point, each terminated by a newline. */
    public static byte[] renderLines(Collection<String> lines) {
        TreeSet<String> sorted = new TreeSet<>(PodNames.CODE_POINT_ORDER);
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                sorted.add(line.strip());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : sorted) {
            sb.append(line).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Re-expresses a podspec path of another layout ({@code Specs/<prefixes>/<Name>/<v>/<Name>.podspec.json}) in
     * {@code layout}; empty when the line is not a podspec path.
     */
    public static Optional<String> translatePodspecPath(String line, ShardLayout layout) {
        CocoaPodsPath parsed = CocoaPodsPath.parse(line);
        if (parsed.kind() != CocoaPodsPath.Kind.PODSPEC) {
            return Optional.empty();
        }
        return Optional.of(layout.podspecPath(parsed.name(), parsed.version()));
    }
}
