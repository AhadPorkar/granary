package dev.granary.format.raw;

import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.storage.StorageService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The documents of the Elm package site: {@code all-packages} (package to version list), {@code all-packages/since/<n>}
 * (releases newer than the client's count, newest first) and the per-release {@code endpoint.json} whose
 * {@code url} names the zip archive to download.
 */
public final class ElmPackages {

    /** One published version of {@code author/project}. */
    public record Release(String author, String project, String version) {

        public String packageName() {
            return author + "/" + project;
        }

        public String id() {
            return packageName() + "@" + version;
        }

        public String directory() {
            return "packages/" + author + "/" + project + "/" + version + "/";
        }

        public String zipRoute() {
            return "github/" + author + "/" + project + "/zip/" + version;
        }
    }

    public static final String ALL_PACKAGES = "all-packages";
    public static final String SINCE_PREFIX = "all-packages/since/";

    private static final Pattern RELEASE = Pattern.compile("^packages/([^/]+)/([^/]+)/(\\d+\\.\\d+\\.\\d+)/.+$");
    private static final Pattern ENDPOINT = Pattern.compile("^packages/([^/]+)/([^/]+)/(\\d+\\.\\d+\\.\\d+)/endpoint\\.json$");
    /**
     * {@code github/<author>/<project>/zip/<version>}: GitHub account and repository names and an Elm version, nothing
     * else, because the parts end up in an upstream URL.
     */
    private static final Pattern ZIP_ROUTE = Pattern.compile(
            "^github/([A-Za-z0-9][A-Za-z0-9_.-]{0,99})/([A-Za-z0-9][A-Za-z0-9_.-]{0,99})/zip/(\\d{1,9}\\.\\d{1,9}\\.\\d{1,9})$");
    private static final Pattern GITHUB_ZIPBALL = Pattern.compile("^https?://github\\.com/([^/]+)/([^/]+)/zipball/([^/]+?)/?$");
    private static final Pattern SINCE = Pattern.compile("^all-packages/since/(\\d{1,9})$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ElmPackages() {
    }

    public static Optional<Release> release(String path) {
        Matcher m = RELEASE.matcher(path);
        return m.matches() ? Optional.of(new Release(m.group(1), m.group(2), m.group(3))) : Optional.empty();
    }

    public static boolean isEndpoint(String path) {
        return ENDPOINT.matcher(path).matches();
    }

    public static Optional<Release> zipRoute(String path) {
        Matcher m = ZIP_ROUTE.matcher(path);
        return m.matches() ? Optional.of(new Release(m.group(1), m.group(2), m.group(3))) : Optional.empty();
    }

    public static Optional<Integer> sinceIndex(String path) {
        Matcher m = SINCE.matcher(path);
        return m.matches() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    public static URI githubZipball(Release release) {
        return URI.create("https://github.com/" + UrlPaths.encodeSegment(release.author()) + "/"
                + UrlPaths.encodeSegment(release.project()) + "/zipball/" + UrlPaths.encodeSegment(release.version()));
    }

    public static Optional<StorageService.ComponentCoordinates> coordinates(String path) {
        return release(path).or(() -> zipRoute(path))
                .map(r -> new StorageService.ComponentCoordinates(r.author(), r.project(), r.version()));
    }

    /**
     * Rewrites the {@code url} of an endpoint document: GitHub zipball URLs become the repository's own
     * {@code github/<author>/<project>/zip/<version>} route, relative URLs are made absolute under the repository.
     * The {@code hash} (SHA-1 of the zip) and any other field stay as they are.
     */
    public static byte[] rewriteEndpoint(byte[] json, String baseUrl) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (RuntimeException e) {
            return json;
        }
        if (!node.isObject()) {
            return json;
        }
        ObjectNode object = (ObjectNode) node;
        JsonNode url = object.path("url");
        if (url.isString()) {
            String value = url.asString();
            Matcher github = GITHUB_ZIPBALL.matcher(value);
            if (github.matches() && zipRoute("github/" + github.group(1) + "/" + github.group(2) + "/zip/" + github.group(3)).isPresent()) {
                object.put("url", baseUrl + "/github/" + github.group(1) + "/" + github.group(2) + "/zip/" + github.group(3));
            } else if (!value.contains("://")) {
                String relative = value;
                while (relative.startsWith("/")) {
                    relative = relative.substring(1);
                }
                object.put("url", baseUrl + "/" + relative);
            }
        }
        return JSON.writeValueAsString(object).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] endpoint(String url, String hash) {
        ObjectNode node = JSON.createObjectNode();
        node.put("url", url);
        node.put("hash", hash);
        return JSON.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
    }

    /** {@code {"author/project": ["1.0.0", "1.0.1"], ...}} with packages and versions in ascending order. */
    public static byte[] allPackages(Map<String, ? extends List<String>> versionsByPackage) {
        ObjectNode root = JSON.createObjectNode();
        for (String name : new TreeMap<>(versionsByPackage).keySet()) {
            List<String> versions = new ArrayList<>(new LinkedHashSet<>(versionsByPackage.get(name)));
            versions.sort(ElmPackages::compareVersions);
            ArrayNode array = root.putArray(name);
            versions.forEach(array::add);
        }
        return JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
    }

    public static Map<String, List<String>> parseAllPackages(byte[] json) {
        Map<String, List<String>> result = new TreeMap<>();
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (RuntimeException e) {
            return result;
        }
        if (!node.isObject()) {
            return result;
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            List<String> versions = new ArrayList<>();
            if (entry.getValue().isArray()) {
                for (JsonNode version : entry.getValue()) {
                    if (version.isString()) {
                        versions.add(version.asString());
                    }
                }
            }
            result.put(entry.getKey(), versions);
        }
        return result;
    }

    /** Union of several all-packages documents. */
    public static Map<String, List<String>> mergeAllPackages(List<Map<String, List<String>>> documents) {
        Map<String, Set<String>> merged = new TreeMap<>();
        for (Map<String, List<String>> document : documents) {
            for (Map.Entry<String, List<String>> entry : document.entrySet()) {
                merged.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).addAll(entry.getValue());
            }
        }
        Map<String, List<String>> result = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : merged.entrySet()) {
            List<String> versions = new ArrayList<>(entry.getValue());
            versions.sort(ElmPackages::compareVersions);
            result.put(entry.getKey(), versions);
        }
        return result;
    }

    /**
     * The {@code since/<n>} answer: given every release newest first, the ones a client that already knows
     * {@code n} releases has not seen.
     */
    public static byte[] since(List<String> releasesNewestFirst, int known) {
        ArrayNode array = JSON.createArrayNode();
        int unseen = Math.max(0, releasesNewestFirst.size() - known);
        for (int i = 0; i < unseen; i++) {
            array.add(releasesNewestFirst.get(i));
        }
        return JSON.writeValueAsString(array).getBytes(StandardCharsets.UTF_8);
    }

    public static List<String> parseSince(byte[] json) {
        List<String> result = new ArrayList<>();
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (RuntimeException e) {
            return result;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.isString()) {
                    result.add(element.asString());
                }
            }
        }
        return result;
    }

    /** Elm versions are {@code MAJOR.MINOR.PATCH}; anything else sorts by its numeric components too. */
    public static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int n = Math.max(left.length, right.length);
        for (int i = 0; i < n; i++) {
            long l = i < left.length ? parse(left[i]) : 0;
            long r = i < right.length ? parse(right[i]) : 0;
            if (l != r) {
                return Long.compare(l, r);
            }
        }
        return 0;
    }

    private static long parse(String component) {
        try {
            return Long.parseLong(component.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
