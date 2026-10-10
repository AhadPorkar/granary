package dev.granary.format.composer;

import dev.granary.core.model.Repository;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers every read of the Composer protocol for all three repository types: hosted repositories from storage,
 * proxies from their cache and upstream, groups by asking their members.
 *
 * <p>Group members are consulted hosted first, then in their configured order (nested groups flattened), and the
 * first member that has a version supplies it: a public package can never shadow a version of the same name that
 * was published privately, and the archive is always served by the member whose metadata the client saw.
 */
@Component
public class ComposerMetadata {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final int DEFAULT_PAGE_SIZE = 100;
    static final int MAX_PAGE_SIZE = 100;

    private final StorageService storage;
    private final ComposerHosted hosted;
    private final ComposerProxy proxy;
    private final GroupResolver groups;

    public ComposerMetadata(StorageService storage, ComposerHosted hosted, ComposerProxy proxy, GroupResolver groups) {
        this.storage = storage;
        this.hosted = hosted;
        this.proxy = proxy;
        this.groups = groups;
    }

    /** The leaf members of a group, hosted ones first, otherwise in configured order. */
    public List<Repository> members(Repository group) {
        List<Repository> leaves = groups.leafMembers(group);
        List<Repository> ordered = new ArrayList<>(leaves.size());
        leaves.stream().filter(Repository::isHosted).forEach(ordered::add);
        leaves.stream().filter(r -> !r.isHosted()).forEach(ordered::add);
        return ordered;
    }

    // --- root document -----------------------------------------------------------------------------

    /** {@code packages.json} for a repository reached at {@code baseUrl}. */
    public ObjectNode root(Repository repo, String baseUrl) {
        ObjectNode root = NODES.objectNode();
        root.putArray("packages");
        root.put("metadata-url", baseUrl + "/p2/%package%.json");
        root.put("notify-batch", baseUrl + "/downloads");
        root.put("search", baseUrl + "/search.json?q=%query%&type=%type%");
        switch (repo.getType()) {
            case HOSTED -> {
                root.put("list", baseUrl + "/list.json");
                root.put("providers-api", baseUrl + "/providers/%package%.json");
                List<String> names = hosted.packageNames(repo);
                if (!names.isEmpty()) {
                    ArrayNode available = root.putArray("available-packages");
                    names.forEach(available::add);
                }
            }
            case PROXY -> {
                Optional<ObjectNode> upstream = proxy.upstreamRoot(repo);
                if (upstream.isPresent()) {
                    ObjectNode u = upstream.get();
                    if (proxy.hasList(u)) {
                        root.put("list", baseUrl + "/list.json");
                    }
                    if (P2Document.string(u, "providers-api") != null) {
                        root.put("providers-api", baseUrl + "/providers/%package%.json");
                    }
                    if (proxy.advisoryApi(repo, u).isPresent()) {
                        advisories(root, baseUrl);
                    }
                    for (String key : List.of("warning", "warning-versions", "info", "info-versions")) {
                        if (u.get(key) != null && u.get(key).isString()) {
                            root.put(key, u.get(key).stringValue());
                        }
                    }
                }
            }
            case GROUP -> {
                root.put("list", baseUrl + "/list.json");
                root.put("providers-api", baseUrl + "/providers/%package%.json");
                for (Repository member : members(repo)) {
                    if (member.isProxy() && proxy.upstreamRoot(member).flatMap(u -> proxy.advisoryApi(member, u)).isPresent()) {
                        advisories(root, baseUrl);
                        break;
                    }
                }
            }
        }
        return root;
    }

    private static void advisories(ObjectNode root, String baseUrl) {
        ObjectNode config = root.putObject("security-advisories");
        config.put("metadata", false);
        config.put("api-url", baseUrl + "/api/security-advisories");
    }

    // --- package metadata --------------------------------------------------------------------------

    /**
     * A package's {@code p2} document as the client at {@code baseUrl} must see it: dist URLs pointing at
     * {@code baseUrl}, {@code lastModified} covering the repository's configuration too.
     */
    public Lookup metadata(Repository repo, String name, boolean dev, String baseUrl) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.document(repo, name, dev)
                    .map(doc -> ComposerRewriter.hosted(doc, baseUrl))
                    .map(doc -> Lookup.found(doc.withLastModified(ComposerRewriter.lastModified(doc, repo))))
                    .orElse(Lookup.missing());
            case PROXY -> {
                Lookup lookup = proxy.metadata(repo, name, dev);
                if (!lookup.isFound()) {
                    yield lookup;
                }
                P2Document doc = ComposerRewriter.proxied(lookup.document(), baseUrl, ComposerProxy.rewritesDist(repo));
                yield Lookup.found(doc.withLastModified(ComposerRewriter.lastModified(doc, repo)));
            }
            case GROUP -> {
                List<P2Document> found = new ArrayList<>();
                boolean unknown = false;
                boolean offlineMiss = false;
                for (Repository member : members(repo)) {
                    Lookup lookup = metadata(member, name, dev, baseUrl);
                    switch (lookup.state()) {
                        case FOUND -> found.add(lookup.document());
                        case UNKNOWN -> unknown = true;
                        case MISSING_OFFLINE -> offlineMiss = true;
                        case MISSING -> {
                            // nothing to add
                        }
                    }
                }
                if (!found.isEmpty()) {
                    P2Document merged = ComposerRewriter.merge(name, found);
                    yield Lookup.found(merged.withLastModified(ComposerRewriter.lastModified(merged, repo)));
                }
                if (unknown) {
                    yield Lookup.unknown();
                }
                yield offlineMiss ? Lookup.missingOffline() : Lookup.missing();
            }
        };
    }

    // --- dist archives -----------------------------------------------------------------------------

    /**
     * A dist archive from whichever repository serves it. In a group, a member that already has the file wins;
     * otherwise each proxy member is asked to fetch it, which only succeeds for the member whose metadata names it.
     */
    public ComposerProxy.Dist distribution(Repository repo, ComposerPath path) {
        String storagePath = ComposerPath.distPath(path.name(), path.version(), path.file());
        return switch (repo.getType()) {
            case HOSTED -> storage.find(repo, storagePath).map(ComposerProxy.Dist::found).orElse(ComposerProxy.Dist.MISSING);
            case PROXY -> proxy.distribution(repo, path);
            case GROUP -> {
                List<Repository> members = members(repo);
                for (Repository member : members) {
                    if (!member.isGroup()) {
                        Optional<StoredContent> local = storage.find(member, storagePath);
                        if (local.isPresent()) {
                            yield ComposerProxy.Dist.found(local.get());
                        }
                    }
                }
                ComposerProxy.Dist unavailable = null;
                for (Repository member : members) {
                    if (member.isProxy()) {
                        ComposerProxy.Dist dist = proxy.distribution(member, path);
                        if (dist.isFound()) {
                            yield dist;
                        }
                        if (dist.unavailable() != null && unavailable == null) {
                            unavailable = dist;
                        }
                    }
                }
                yield unavailable != null ? unavailable : ComposerProxy.Dist.MISSING;
            }
        };
    }

    // --- search ------------------------------------------------------------------------------------

    /** Search parameters as the client sent them. */
    public record SearchQuery(String query, String type, int page, int perPage) {
        public SearchQuery {
            query = query == null ? "" : query.trim();
            type = type == null ? "" : type.trim();
        }
    }

    /**
     * Full-text search in Packagist's shape: {@code {"results":[{name, description, url, repository, downloads,
     * favers}], "total": n, "next"?: url}}. Hosted members contribute their matches, proxies what upstream answers;
     * the first source that names a package wins.
     *
     * @return empty when a proxy (alone, or every source of a group) could not answer
     */
    public Optional<ObjectNode> search(Repository repo, SearchQuery query, String baseUrl) {
        List<ObjectNode> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        long total = 0;
        boolean answered = false;
        boolean more = false;
        List<Repository> sources = repo.isGroup() ? members(repo) : List.of(repo);
        for (Repository source : sources) {
            if (source.isHosted()) {
                List<ObjectNode> matches = searchHosted(source, query, baseUrl);
                answered = true;
                total += matches.size();
                int from = Math.min(matches.size(), (query.page() - 1) * query.perPage());
                int to = Math.min(matches.size(), from + query.perPage());
                more |= to < matches.size();
                for (ObjectNode match : matches.subList(from, to)) {
                    if (seen.add(P2Document.string(match, "name"))) {
                        results.add(match);
                    }
                }
            } else if (source.isProxy()) {
                Optional<ObjectNode> upstream = proxy.search(source, query.query(), query.type(),
                        query.page() > 1 ? Integer.toString(query.page()) : null,
                        query.perPage() != DEFAULT_PAGE_SIZE ? Integer.toString(query.perPage()) : null);
                if (upstream.isEmpty()) {
                    continue;
                }
                answered = true;
                JsonNode list = upstream.get().get("results");
                if (list instanceof ArrayNode array) {
                    for (JsonNode item : array) {
                        String name = P2Document.string(item, "name");
                        if (item instanceof ObjectNode o && name != null && seen.add(name)) {
                            results.add(o);
                        }
                    }
                }
                JsonNode t = upstream.get().get("total");
                total += t != null && t.isNumber() ? t.longValue() : (list == null ? 0 : list.size());
                more |= P2Document.string(upstream.get(), "next") != null;
            }
        }
        if (!answered) {
            return Optional.empty();
        }
        ObjectNode response = NODES.objectNode();
        ArrayNode array = response.putArray("results");
        results.forEach(array::add);
        response.put("total", total);
        if (more) {
            response.put("next", baseUrl + "/search.json?q=" + ComposerProxy.encode(query.query())
                    + "&type=" + ComposerProxy.encode(query.type())
                    + "&page=" + (query.page() + 1) + "&per_page=" + query.perPage());
        }
        return Optional.of(response);
    }

    /** Every package of a hosted repository that matches: each word in the name, description or keywords. */
    List<ObjectNode> searchHosted(Repository repo, SearchQuery query, String baseUrl) {
        String[] words = query.query().toLowerCase(Locale.ROOT).split("\\s+");
        List<ObjectNode> exact = new ArrayList<>();
        List<ObjectNode> matches = new ArrayList<>();
        for (String name : hosted.packageNames(repo)) {
            Optional<ObjectNode> latest = latest(repo, name);
            if (latest.isEmpty()) {
                continue;
            }
            ObjectNode entry = latest.get();
            String type = Optional.ofNullable(P2Document.string(entry, "type")).orElse("library");
            if (!query.type().isEmpty() && !query.type().equalsIgnoreCase(type)) {
                continue;
            }
            String description = Optional.ofNullable(P2Document.string(entry, "description")).orElse("");
            StringBuilder haystack = new StringBuilder(name).append(' ').append(description.toLowerCase(Locale.ROOT));
            if (entry.get("keywords") instanceof ArrayNode keywords) {
                keywords.forEach(k -> haystack.append(' ').append(k.asString().toLowerCase(Locale.ROOT)));
            }
            boolean all = true;
            for (String word : words) {
                if (!word.isEmpty() && haystack.indexOf(word) < 0) {
                    all = false;
                    break;
                }
            }
            if (!all) {
                continue;
            }
            ObjectNode result = NODES.objectNode();
            result.put("name", name);
            result.put("description", description);
            result.put("url", baseUrl + "/p2/" + name + ".json");
            String repository = Optional.ofNullable(P2Document.string(entry.get("support"), "source"))
                    .orElse(Optional.ofNullable(P2Document.string(entry, "homepage")).orElse(""));
            result.put("repository", repository);
            result.put("downloads", hosted.downloads(repo, name));
            result.put("favers", 0);
            JsonNode abandoned = entry.get("abandoned");
            if (abandoned != null && !abandoned.isNull() && !(abandoned.isBoolean() && !abandoned.booleanValue())) {
                result.set("abandoned", abandoned);
            }
            (name.equals(query.query().toLowerCase(Locale.ROOT)) ? exact : matches).add(result);
        }
        matches.sort(Comparator.comparingLong((ObjectNode r) -> -r.get("downloads").longValue())
                .thenComparing(r -> r.get("name").stringValue()));
        exact.addAll(matches);
        return exact;
    }

    /** The newest release of a hosted package, else its newest branch. */
    private Optional<ObjectNode> latest(Repository repo, String name) {
        for (boolean dev : new boolean[] {false, true}) {
            Optional<P2Document> doc = hosted.document(repo, name, dev);
            if (doc.isPresent() && !doc.get().isEmpty()) {
                return Optional.of(doc.get().versions().getFirst());
            }
        }
        return Optional.empty();
    }

    // --- package list ------------------------------------------------------------------------------

    /**
     * {@code {"packageNames": [...]}}, filtered like Packagist's list API: {@code filter} is a glob with {@code *},
     * {@code vendor} an exact vendor, {@code type} a package type (hosted packages only).
     *
     * @return empty when no source could answer
     */
    public Optional<ObjectNode> list(Repository repo, Map<String, String> query) {
        Set<String> names = new TreeSet<>();
        boolean answered = false;
        List<Repository> sources = repo.isGroup() ? members(repo) : List.of(repo);
        for (Repository source : sources) {
            if (source.isHosted()) {
                answered = true;
                names.addAll(listHosted(source, query));
            } else if (source.isProxy()) {
                Optional<ObjectNode> upstream = proxy.list(source, query);
                if (upstream.isPresent() && upstream.get().get("packageNames") instanceof ArrayNode array) {
                    answered = true;
                    array.forEach(n -> {
                        if (n.isString()) {
                            names.add(n.stringValue());
                        }
                    });
                }
            }
        }
        if (!answered) {
            return Optional.empty();
        }
        ObjectNode response = NODES.objectNode();
        ArrayNode array = response.putArray("packageNames");
        names.forEach(array::add);
        return Optional.of(response);
    }

    List<String> listHosted(Repository repo, Map<String, String> query) {
        Pattern filter = query.containsKey("filter") ? glob(query.get("filter")) : null;
        String vendor = query.get("vendor");
        String type = query.get("type");
        List<String> result = new ArrayList<>();
        for (String name : hosted.packageNames(repo)) {
            if (filter != null && !filter.matcher(name).matches()) {
                continue;
            }
            if (vendor != null && !vendor.isBlank() && !ComposerNames.vendor(name).equalsIgnoreCase(vendor.trim())) {
                continue;
            }
            if (type != null && !type.isBlank()) {
                String actual = latest(repo, name).map(e -> P2Document.string(e, "type")).orElse(null);
                if (!type.trim().equalsIgnoreCase(actual == null ? "library" : actual)) {
                    continue;
                }
            }
            result.add(name);
        }
        return result;
    }

    /** A Packagist list filter: {@code *} matches anything, everything else literally, case-insensitively. */
    static Pattern glob(String filter) {
        StringBuilder regex = new StringBuilder();
        String[] parts = filter.trim().split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    // --- providers ---------------------------------------------------------------------------------

    /**
     * {@code {"providers":[{name, description, type}]}}: packages that {@code provide} {@code name}.
     *
     * @return empty when no source could answer
     */
    public Optional<ObjectNode> providers(Repository repo, String name) {
        Map<String, ObjectNode> providers = new LinkedHashMap<>();
        boolean answered = false;
        List<Repository> sources = repo.isGroup() ? members(repo) : List.of(repo);
        for (Repository source : sources) {
            if (source.isHosted()) {
                answered = true;
                for (ObjectNode provider : providersHosted(source, name)) {
                    providers.putIfAbsent(P2Document.string(provider, "name"), provider);
                }
            } else if (source.isProxy()) {
                Optional<ObjectNode> upstream = proxy.providers(source, name);
                if (upstream.isPresent()) {
                    answered = true;
                    if (upstream.get().get("providers") instanceof ArrayNode array) {
                        for (JsonNode item : array) {
                            String providerName = P2Document.string(item, "name");
                            if (providerName != null && item instanceof ObjectNode o) {
                                providers.putIfAbsent(providerName, o);
                            }
                        }
                    }
                }
            }
        }
        if (!answered) {
            return Optional.empty();
        }
        ObjectNode response = NODES.objectNode();
        ArrayNode array = response.putArray("providers");
        providers.values().forEach(array::add);
        return Optional.of(response);
    }

    private List<ObjectNode> providersHosted(Repository repo, String virtual) {
        List<ObjectNode> result = new ArrayList<>();
        for (String name : hosted.packageNames(repo)) {
            for (boolean dev : new boolean[] {false, true}) {
                Optional<P2Document> doc = hosted.document(repo, name, dev);
                if (doc.isEmpty()) {
                    continue;
                }
                Optional<ObjectNode> providing = doc.get().versions().stream().filter(e -> provides(e, virtual)).findFirst();
                if (providing.isPresent()) {
                    ObjectNode provider = NODES.objectNode();
                    provider.put("name", name);
                    provider.put("description", Optional.ofNullable(P2Document.string(providing.get(), "description")).orElse(""));
                    provider.put("type", Optional.ofNullable(P2Document.string(providing.get(), "type")).orElse("library"));
                    result.add(provider);
                    break;
                }
            }
        }
        return result;
    }

    private static boolean provides(ObjectNode entry, String virtual) {
        if (!(entry.get("provide") instanceof ObjectNode provide)) {
            return false;
        }
        for (String key : provide.propertyNames()) {
            if (key.equalsIgnoreCase(virtual)) {
                return true;
            }
        }
        return false;
    }

    // --- security advisories -----------------------------------------------------------------------

    /**
     * {@code {"advisories": {"<name>": [...]}}} for the requested packages, from every proxy (hosted packages have
     * none on record).
     *
     * @return empty when advisories were asked for but no source could say anything
     */
    public Optional<ObjectNode> advisories(Repository repo, List<String> names) {
        Map<String, List<JsonNode>> merged = new LinkedHashMap<>();
        boolean anySource = false;
        boolean answered = names.isEmpty();
        List<Repository> sources = repo.isGroup() ? members(repo) : List.of(repo);
        for (Repository source : sources) {
            if (!source.isProxy()) {
                continue;
            }
            Optional<Map<String, JsonNode>> found = proxy.advisories(source, names);
            if (found.isEmpty()) {
                continue;
            }
            anySource = true;
            if (!found.get().isEmpty()) {
                answered = true;
            }
            for (Map.Entry<String, JsonNode> e : found.get().entrySet()) {
                List<JsonNode> list = merged.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                if (e.getValue() instanceof ArrayNode array) {
                    array.forEach(list::add);
                }
            }
        }
        if (anySource && !answered) {
            return Optional.empty();
        }
        ObjectNode response = NODES.objectNode();
        ObjectNode advisories = response.putObject("advisories");
        for (Map.Entry<String, List<JsonNode>> e : merged.entrySet()) {
            advisories.set(e.getKey(), ComposerRewriter.dedupeAdvisories(e.getValue()));
        }
        return Optional.of(response);
    }
}
