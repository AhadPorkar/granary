package dev.granary.format.npm;

import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.repository.GroupResolver;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code GET /-/v1/search}. Hosted repositories search their components, proxies forward the query
 * upstream without caching (results are personalised and short-lived), and groups show the union of
 * their members with the first member winning a name clash, the same rule groups use for packuments.
 */
@Component
public class NpmSearch {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 250;
    /** Upper bound on component rows examined for one hosted search; a registry this large wants a real index. */
    static final int HOSTED_SCAN_LIMIT = 1000;

    private static final Logger log = LoggerFactory.getLogger(NpmSearch.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Map<String, String> ACCEPT_JSON = Map.of("Accept", NpmPackuments.JSON);

    private final NpmPackuments packuments;
    private final ComponentDao components;
    private final UpstreamClient upstream;
    private final ProxyService proxy;
    private final GroupResolver groups;

    public NpmSearch(NpmPackuments packuments, ComponentDao components, UpstreamClient upstream, ProxyService proxy,
                     GroupResolver groups) {
        this.packuments = packuments;
        this.components = components;
        this.upstream = upstream;
        this.proxy = proxy;
        this.groups = groups;
    }

    /** The npm search response: {@code {objects: [{package: …}], total, time}}. */
    public ObjectNode search(Repository repo, String text, int from, int size, String baseUrl) {
        String query = text == null ? "" : text.trim();
        return switch (repo.getType()) {
            case HOSTED -> page(hosted(repo, query, baseUrl), from, size);
            case PROXY -> {
                ObjectNode upstreamResult = proxied(repo, query, from, size);
                yield upstreamResult != null ? upstreamResult : result(List.of(), 0);
            }
            case GROUP -> {
                Map<String, ObjectNode> byName = new LinkedHashMap<>();
                for (Repository member : groups.leafMembers(repo)) {
                    List<ObjectNode> found = member.isProxy()
                            ? objectsOf(proxied(member, query, 0, Math.min(MAX_SIZE, from + size)))
                            : hosted(member, query, baseUrl);
                    for (ObjectNode entry : found) {
                        String name = NpmPackument.string(entry.path("package"), NpmPackument.NAME);
                        if (name != null) {
                            byName.putIfAbsent(name, entry);
                        }
                    }
                }
                yield page(new ArrayList<>(byName.values()), from, size);
            }
        };
    }

    /** Every package of a hosted repository matching the query, as search entries. */
    List<ObjectNode> hosted(Repository repo, String query, String baseUrl) {
        List<String> words = new ArrayList<>();
        for (String word : query.split("\\s+")) {
            // qualifiers such as author:x or keywords:y are not supported; ignore them rather than match nothing
            if (!word.isEmpty() && !word.contains(":")) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        String term = words.isEmpty() ? "" : words.getFirst();
        Set<String> names = new LinkedHashSet<>();
        for (dev.granary.core.model.Component c : components.search(repo.getId(), term, PageRequest.of(0, HOSTED_SCAN_LIMIT))) {
            String name = c.getNamespace().isEmpty() ? c.getName() : "@" + c.getNamespace() + "/" + c.getName();
            if (words.stream().allMatch(w -> name.toLowerCase(Locale.ROOT).contains(w))) {
                names.add(name);
            }
        }
        List<ObjectNode> entries = new ArrayList<>();
        for (String name : names) {
            packuments.stored(repo, name).ifPresent(doc -> entries.add(entry(doc, baseUrl)));
        }
        return entries;
    }

    /** The upstream's own answer, or {@code null} when the proxy is offline or upstream failed. */
    ObjectNode proxied(Repository repo, String query, int from, int size) {
        if (proxy.isOffline(repo)) {
            return null;
        }
        URI uri = URI.create(UrlPaths.join(repo.getRemoteUrl().trim(), "-/v1/search")
                + "?text=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&size=" + size + "&from=" + from);
        try (UpstreamClient.Response response = upstream.getAbsolute(repo, uri, Map.of(), ACCEPT_JSON)) {
            if (response.status() != 200) {
                log.warn("search upstream of {} answered {}", repo.getName(), response.status());
                return null;
            }
            JsonNode node = packuments.mapper().readTree(response.body());
            return node != null && node.isObject() ? (ObjectNode) node : null;
        } catch (IOException | JacksonException e) {
            log.warn("search upstream of {} failed: {}", repo.getName(), e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static List<ObjectNode> objectsOf(ObjectNode result) {
        List<ObjectNode> list = new ArrayList<>();
        JsonNode objects = result == null ? null : result.get("objects");
        if (objects != null && objects.isArray()) {
            for (JsonNode o : objects) {
                if (o.isObject()) {
                    list.add((ObjectNode) o);
                }
            }
        }
        return list;
    }

    private static ObjectNode page(List<ObjectNode> all, int from, int size) {
        int start = Math.min(Math.max(from, 0), all.size());
        int end = Math.min(start + Math.max(size, 0), all.size());
        return result(all.subList(start, end), all.size());
    }

    static ObjectNode result(List<ObjectNode> objects, long total) {
        ObjectNode result = NODES.objectNode();
        ArrayNode array = result.putArray("objects");
        objects.forEach(array::add);
        result.put("total", total);
        result.put("time", NpmPackument.format(Instant.now()));
        return result;
    }

    /** One search hit built from a packument, with every member the npm CLI's formatter reads. */
    static ObjectNode entry(ObjectNode doc, String baseUrl) {
        String name = NpmPackument.string(doc, NpmPackument.NAME);
        ObjectNode entry = NODES.objectNode();
        ObjectNode pkg = entry.putObject("package");
        pkg.put(NpmPackument.NAME, name);
        String scope = NpmPath.scopeOf(name);
        pkg.put("scope", scope == null ? "unscoped" : scope);
        String latest = NpmPackument.latest(doc).orElse(null);
        JsonNode version = latest == null ? null : NpmPackument.versions(doc).get(latest);
        if (latest != null) {
            pkg.put("version", latest);
        }
        String description = NpmPackument.string(doc, "description");
        if (description == null && version != null) {
            description = NpmPackument.string(version, "description");
        }
        if (description != null) {
            pkg.put("description", description);
        }
        JsonNode keywords = version != null && version.has("keywords") ? version.get("keywords") : doc.get("keywords");
        if (keywords != null && keywords.isArray()) {
            pkg.set("keywords", keywords.deepCopy());
        }
        String date = NpmPackument.string(doc.path(NpmPackument.TIME), NpmPackument.MODIFIED);
        if (date == null && latest != null) {
            date = NpmPackument.string(doc.path(NpmPackument.TIME), latest);
        }
        if (date != null) {
            pkg.put("date", date);
        }
        pkg.putObject("links").put("npm", (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + name);
        if (version != null && version.has("author")) {
            pkg.set("author", version.get("author").deepCopy());
        }
        JsonNode npmUser = version == null ? null : version.get("_npmUser");
        if (npmUser != null && npmUser.isObject()) {
            pkg.set("publisher", npmUser.deepCopy());
        }
        JsonNode maintainers = doc.get("maintainers");
        if (maintainers != null && maintainers.isArray()) {
            pkg.set("maintainers", maintainers.deepCopy());
        } else {
            pkg.putArray("maintainers");
        }
        ObjectNode score = entry.putObject("score");
        score.put("final", 1.0);
        ObjectNode detail = score.putObject("detail");
        detail.put("quality", 1.0);
        detail.put("popularity", 1.0);
        detail.put("maintenance", 1.0);
        entry.put("searchScore", 1.0);
        return entry;
    }
}
