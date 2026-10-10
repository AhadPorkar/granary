package dev.granary.format.rubygems;

import dev.granary.core.model.Repository;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The read side of the rubygems.org JSON API: {@code api/v1/gems/<name>.json} (the latest version with its
 * dependencies), {@code api/v1/versions/<name>.json} (every version) and
 * {@code api/v1/versions/<name>/latest.json}. Hosted repositories answer from their records, proxies pass upstream's
 * documents through with {@code gem_uri} pointing at Granary, groups combine their members'.
 */
@Component
public class RubygemsApi {

    private static final Logger log = LoggerFactory.getLogger(RubygemsApi.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final List<String> URI_KEYS = List.of("homepage_uri", "wiki_uri", "documentation_uri",
            "mailing_list_uri", "source_code_uri", "bug_tracker_uri", "changelog_uri", "funding_uri");

    private final RubygemsHosted hosted;
    private final RubygemsProxy proxy;
    private final GroupResolver groups;

    public RubygemsApi(RubygemsHosted hosted, RubygemsProxy proxy, GroupResolver groups) {
        this.hosted = hosted;
        this.proxy = proxy;
        this.groups = groups;
    }

    public static String gemPath(String name) {
        return "api/v1/gems/" + name + ".json";
    }

    public static String versionsPath(String name) {
        return "api/v1/versions/" + name + ".json";
    }

    public static String latestPath(String name) {
        return "api/v1/versions/" + name + "/latest.json";
    }

    // --- api/v1/gems/<name>.json --------------------------------------------------------------------

    public Optional<ObjectNode> gem(Repository repo, String name, String baseUrl) {
        return switch (repo.getType()) {
            case HOSTED -> {
                List<GemRecord> live = live(hosted.records(repo, name));
                yield live.isEmpty() ? Optional.empty()
                        : Optional.of(gemDocument(latest(live), live, hosted.downloads(repo, name), baseUrl));
            }
            case PROXY -> readObject(repo, gemPath(name)).map(doc -> rewriteGemUri(doc, baseUrl));
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<ObjectNode> hit = gem(member, name, baseUrl);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    static ObjectNode gemDocument(GemRecord latest, List<GemRecord> all, Map<String, Long> downloads, String baseUrl) {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("name", latest.name());
        doc.put("downloads", downloads.values().stream().mapToLong(Long::longValue).sum());
        doc.put("version", latest.version());
        doc.put("version_created_at", timestamp(latest.createdAt()));
        doc.put("version_downloads", downloads.getOrDefault(latest.fullName(), 0L));
        doc.put("platform", latest.platform());
        doc.put("authors", String.join(", ", latest.authors()));
        doc.put("info", latest.description() != null && !latest.description().isBlank() ? latest.description() : latest.summary());
        ArrayNode licenses = doc.putArray("licenses");
        latest.licenses().forEach(licenses::add);
        ObjectNode metadata = doc.putObject("metadata");
        latest.metadata().forEach(metadata::put);
        doc.put("yanked", false);
        doc.put("sha", latest.sha256());
        doc.put("project_uri", UrlPaths.join(baseUrl, gemPath(latest.name())));
        doc.put("gem_uri", UrlPaths.join(baseUrl, GemNames.gemPath(latest.fullName())));
        doc.put("homepage_uri", latest.homepage());
        for (String key : URI_KEYS) {
            if (!key.equals("homepage_uri")) {
                doc.put(key, latest.metadata().get(key));
            }
        }
        ObjectNode deps = doc.putObject("dependencies");
        ArrayNode development = deps.putArray("development");
        ArrayNode runtime = deps.putArray("runtime");
        for (GemRecord.Dependency d : latest.dependencies()) {
            (d.runtime() ? runtime : development).addObject().put("name", d.name()).put("requirements", d.requirement());
        }
        doc.put("versions_count", all.size());
        return doc;
    }

    /** Points {@code gem_uri} of an upstream document at this repository. */
    static ObjectNode rewriteGemUri(ObjectNode doc, String baseUrl) {
        String name = text(doc, "name");
        String version = text(doc, "version");
        if (name != null && version != null && GemNames.isValidName(name)) {
            String platform = text(doc, "platform");
            String fullName = GemNames.fullName(name, version, platform);
            if (GemNames.isSafeFullName(fullName)) {
                doc.put("gem_uri", UrlPaths.join(baseUrl, GemNames.gemPath(fullName)));
            }
        }
        return doc;
    }

    // --- api/v1/versions/<name>.json ----------------------------------------------------------------

    public Optional<ArrayNode> versions(Repository repo, String name) {
        return switch (repo.getType()) {
            case HOSTED -> {
                List<GemRecord> live = live(hosted.records(repo, name));
                yield live.isEmpty() ? Optional.empty()
                        : Optional.of(versionsDocument(live, hosted.downloads(repo, name)));
            }
            case PROXY -> read(repo, versionsPath(name)).filter(JsonNode::isArray).map(n -> (ArrayNode) n);
            case GROUP -> {
                ArrayNode merged = JSON.createArrayNode();
                Set<String> seen = new HashSet<>();
                boolean any = false;
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<ArrayNode> list = versions(member, name);
                    if (list.isEmpty()) {
                        continue;
                    }
                    any = true;
                    for (JsonNode v : list.get()) {
                        String key = text(v, "number") + " " + Optional.ofNullable(text(v, "platform")).orElse(GemPlatform.RUBY);
                        if (seen.add(key)) {
                            merged.add(v);
                        }
                    }
                }
                yield any ? Optional.of(merged) : Optional.empty();
            }
        };
    }

    static ArrayNode versionsDocument(List<GemRecord> live, Map<String, Long> downloads) {
        List<GemRecord> newestFirst = new ArrayList<>(live);
        newestFirst.sort(Comparator.comparing(GemRecord::tuple).reversed());
        ArrayNode list = JSON.createArrayNode();
        for (GemRecord r : newestFirst) {
            ObjectNode v = list.addObject();
            v.put("authors", String.join(", ", r.authors()));
            v.put("built_at", timestamp(r.createdAt()));
            v.put("created_at", timestamp(r.createdAt()));
            v.put("description", r.description());
            v.put("downloads_count", downloads.getOrDefault(r.fullName(), 0L));
            ObjectNode metadata = v.putObject("metadata");
            r.metadata().forEach(metadata::put);
            v.put("number", r.version());
            v.put("summary", r.summary());
            v.put("platform", r.platform());
            v.put("rubygems_version", r.requiredRubygemsVersion());
            v.put("ruby_version", r.requiredRubyVersion());
            v.put("prerelease", r.prerelease());
            ArrayNode licenses = v.putArray("licenses");
            r.licenses().forEach(licenses::add);
            v.putArray("requirements");
            v.put("sha", r.sha256());
        }
        return list;
    }

    // --- api/v1/versions/<name>/latest.json ---------------------------------------------------------

    public Optional<String> latestVersion(Repository repo, String name) {
        return switch (repo.getType()) {
            case HOSTED -> {
                List<GemRecord> live = live(hosted.records(repo, name));
                yield live.isEmpty() ? Optional.empty() : Optional.of(latest(live).version());
            }
            case PROXY -> readObject(repo, latestPath(name)).map(doc -> text(doc, "version"))
                    .filter(v -> GemVersion.isValid(v));
            case GROUP -> {
                String best = null;
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<String> v = latestVersion(member, name);
                    if (v.isPresent() && (best == null || GemVersion.compareStrings(v.get(), best) > 0)) {
                        best = v.get();
                    }
                }
                yield Optional.ofNullable(best);
            }
        };
    }

    public static byte[] latestDocument(String version) {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("version", version);
        return JSON.writeValueAsBytes(doc);
    }

    public static byte[] bytes(JsonNode node) {
        return JSON.writeValueAsBytes(node);
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static List<GemRecord> live(List<GemRecord> records) {
        return records.stream().filter(r -> !r.yanked()).toList();
    }

    /** The newest release (or, when there is none, the newest prerelease), preferring the {@code ruby} platform. */
    static GemRecord latest(List<GemRecord> live) {
        Comparator<GemRecord> order = Comparator
                .comparing((GemRecord r) -> !r.prerelease())
                .thenComparing((a, b) -> GemVersion.compareStrings(a.version(), b.version()))
                .thenComparing(r -> GemPlatform.isRuby(r.platform()));
        return live.stream().max(order).orElseThrow();
    }

    private Optional<JsonNode> read(Repository repo, String path) {
        Optional<StoredContent> content = proxy.document(repo, path);
        if (content.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JSON.readTree(content.get().readAllBytes()));
        } catch (IOException | JacksonException e) {
            log.warn("ignoring unreadable {} of {}: {}", path, repo.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ObjectNode> readObject(Repository repo, String path) {
        return read(repo, path).filter(JsonNode::isObject).map(n -> (ObjectNode) n);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asString();
    }

    private static String timestamp(Instant instant) {
        return instant == null ? null : TIMESTAMP.format(instant);
    }
}
