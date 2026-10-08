package dev.granary.format.nuget;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds {@code v3/query} responses: from stored packages for hosted repositories, by rewriting an
 * upstream result for proxies, and by union for groups.
 *
 * <p>Search is deliberately simple: a case-insensitive substring match over id, title, description,
 * tags and authors, plus the {@code packageid:}/{@code id:} prefixes the {@code dotnet} CLI sends. A
 * private feed has hundreds of packages, not millions, and a predictable match beats a clever one when
 * the person searching knows what they pushed.
 */
public final class SearchResponse {

    /** The query parameters of {@code v3/query}, with the client defaults applied. */
    public record Query(String q, int skip, int take, boolean prerelease, String semVerLevel) {

        public static final int DEFAULT_TAKE = 20;
        public static final int MAX_TAKE = 1000;

        public static Query of(String q, String skip, String take, String prerelease, String semVerLevel) {
            return new Query(q == null ? "" : q.trim(),
                    Math.max(0, parseInt(skip, 0)),
                    Math.max(1, Math.min(MAX_TAKE, parseInt(take, DEFAULT_TAKE))),
                    Boolean.parseBoolean(prerelease),
                    semVerLevel == null || semVerLevel.isBlank() ? null : semVerLevel.trim());
        }

        /** The same search without paging, so a group can page over the union of its members' results. */
        public Query widened() {
            return new Query(q, 0, Math.min(MAX_TAKE, skip + take), prerelease, semVerLevel);
        }

        /** The query string to forward to an upstream search service. */
        public String toQueryString() {
            StringBuilder sb = new StringBuilder();
            sb.append("q=").append(URLEncoder.encode(q, StandardCharsets.UTF_8));
            sb.append("&skip=").append(skip);
            sb.append("&take=").append(take);
            sb.append("&prerelease=").append(prerelease);
            sb.append("&semVerLevel=").append(semVerLevel == null ? "2.0.0" : URLEncoder.encode(semVerLevel, StandardCharsets.UTF_8));
            return sb.toString();
        }

        private static int parseInt(String value, int fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    private final ObjectMapper json;

    public SearchResponse(ObjectMapper json) {
        this.json = json;
    }

    public ObjectNode empty() {
        ObjectNode root = json.createObjectNode();
        root.put("totalHits", 0);
        root.putArray("data");
        return root;
    }

    // --- hosted ------------------------------------------------------------------------------

    /**
     * Searches stored packages. {@code packages} maps a lower-cased id to all of its versions; the entry
     * with the highest version that the {@code prerelease} flag allows represents the package.
     */
    public ObjectNode fromPackages(String baseUrl, Map<String, List<PackageEntry>> packages, Query query) {
        List<ObjectNode> hits = new ArrayList<>();
        for (List<PackageEntry> versions : packages.values()) {
            List<PackageEntry> candidates = versions.stream()
                    .filter(PackageEntry::listed)
                    .filter(e -> query.prerelease() || !e.version().isPrerelease())
                    .sorted(PackageEntry.BY_VERSION)
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            PackageEntry latest = candidates.get(candidates.size() - 1);
            if (matches(query.q(), latest)) {
                hits.add(hit(baseUrl, latest, candidates));
            }
        }
        hits.sort((a, b) -> a.path("id").stringValue().compareToIgnoreCase(b.path("id").stringValue()));
        return page(hits, query);
    }

    private ObjectNode hit(String baseUrl, PackageEntry latest, List<PackageEntry> versions) {
        Nuspec nuspec = latest.nuspec();
        String id = latest.lowerCaseId();
        String registration = NuGetLayout.registrationIndexUrl(baseUrl, id);
        ObjectNode hit = json.createObjectNode();
        hit.put("@id", registration);
        hit.put("@type", "Package");
        hit.put("registration", registration);
        hit.put("id", nuspec.id());
        hit.put("version", latest.version().toNormalizedString());
        hit.put("description", nuspec.description() == null ? "" : nuspec.description());
        hit.put("summary", nuspec.summary() == null ? "" : nuspec.summary());
        hit.put("title", nuspec.title() == null ? nuspec.id() : nuspec.title());
        putIfPresent(hit, "iconUrl", nuspec.iconUrl());
        putIfPresent(hit, "licenseUrl", nuspec.licenseUrl());
        putIfPresent(hit, "projectUrl", nuspec.projectUrl());
        ArrayNode tags = hit.putArray("tags");
        nuspec.tags().forEach(tags::add);
        ArrayNode authors = hit.putArray("authors");
        nuspec.authorList().forEach(authors::add);
        ArrayNode owners = hit.putArray("owners");
        nuspec.ownerList().forEach(owners::add);
        long total = versions.stream().mapToLong(PackageEntry::downloads).sum();
        hit.put("totalDownloads", total);
        hit.put("verified", false);
        ArrayNode packageTypes = hit.putArray("packageTypes");
        List<String> types = nuspec.packageTypes().isEmpty() ? List.of("Dependency") : nuspec.packageTypes();
        for (String type : types) {
            packageTypes.addObject().put("name", type);
        }
        ArrayNode versionList = hit.putArray("versions");
        for (PackageEntry entry : versions) {
            ObjectNode v = versionList.addObject();
            v.put("version", entry.version().toNormalizedString());
            v.put("downloads", entry.downloads());
            v.put("@id", NuGetLayout.registrationLeafUrl(baseUrl, id, entry.version().toLowerCaseString()));
        }
        return hit;
    }

    /** Every whitespace-separated term must match; {@code packageid:} wants the exact id, {@code id:} a part of it. */
    static boolean matches(String q, PackageEntry entry) {
        if (q == null || q.isBlank()) {
            return true;
        }
        Nuspec nuspec = entry.nuspec();
        String id = nuspec.id().toLowerCase(Locale.ROOT);
        for (String term : q.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (term.startsWith("packageid:")) {
                if (!id.equals(term.substring("packageid:".length()))) {
                    return false;
                }
            } else if (term.startsWith("id:")) {
                if (!id.contains(term.substring("id:".length()))) {
                    return false;
                }
            } else if (!(id.contains(term)
                    || contains(nuspec.title(), term)
                    || contains(nuspec.description(), term)
                    || contains(nuspec.summary(), term)
                    || contains(nuspec.authors(), term)
                    || nuspec.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(term)))) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    // --- proxy -------------------------------------------------------------------------------

    /** Points the registration URLs of an upstream search result at this repository. */
    public ObjectNode rewrite(JsonNode upstream, String baseUrl) {
        ObjectNode root = json.createObjectNode();
        root.put("totalHits", upstream.path("totalHits").asLong(0));
        ArrayNode data = root.putArray("data");
        for (JsonNode item : upstream.path("data")) {
            if (!item.isObject() || !item.path("id").isString()) {
                continue;
            }
            ObjectNode hit = (ObjectNode) item.deepCopy();
            String id = item.path("id").stringValue().toLowerCase(Locale.ROOT);
            String registration = NuGetLayout.registrationIndexUrl(baseUrl, id);
            hit.put("@id", registration);
            hit.put("registration", registration);
            for (JsonNode version : hit.path("versions")) {
                if (version.isObject() && version.path("version").isString()) {
                    String v = NuGetVersion.normalise(version.path("version").stringValue()).toLowerCase(Locale.ROOT);
                    ((ObjectNode) version).put("@id", NuGetLayout.registrationLeafUrl(baseUrl, id, v));
                }
            }
            data.add(hit);
        }
        return root;
    }

    // --- group -------------------------------------------------------------------------------

    /** Unions members' results by id (first member wins) and applies the caller's paging to the union. */
    public ObjectNode merge(List<? extends JsonNode> results, Query query) {
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        for (JsonNode result : results) {
            for (JsonNode item : result.path("data")) {
                if (item.isObject() && item.path("id").isString()) {
                    byId.putIfAbsent(item.path("id").stringValue().toLowerCase(Locale.ROOT), (ObjectNode) item.deepCopy());
                }
            }
        }
        return page(new ArrayList<>(byId.values()), query);
    }

    private ObjectNode page(List<ObjectNode> hits, Query query) {
        ObjectNode root = json.createObjectNode();
        root.put("totalHits", hits.size());
        ArrayNode data = root.putArray("data");
        hits.stream().skip(query.skip()).limit(query.take()).forEach(data::add);
        return root;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
