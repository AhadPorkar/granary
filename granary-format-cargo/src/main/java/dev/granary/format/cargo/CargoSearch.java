package dev.granary.format.cargo;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code GET /api/v1/crates?q=…&per_page=…}, what {@code cargo search} calls.
 *
 * <p>Hosted repositories search the crates they have (name, description and keywords); proxies ask the
 * upstream web API and fall back to the crates in their cache while it is unreachable or the server is
 * offline; groups merge their members, hosted first, one hit per crate name.
 */
@Component
public class CargoSearch {

    private static final Logger log = LoggerFactory.getLogger(CargoSearch.class);

    static final int DEFAULT_PER_PAGE = 10;
    static final int MAX_PER_PAGE = 100;

    private final StorageService storage;
    private final ProxyService proxy;
    private final UpstreamClient upstream;
    private final CargoIndexes indexes;
    private final CargoHosted hosted;

    public CargoSearch(StorageService storage, ProxyService proxy, UpstreamClient upstream, CargoIndexes indexes,
                       CargoHosted hosted) {
        this.storage = storage;
        this.proxy = proxy;
        this.upstream = upstream;
        this.indexes = indexes;
        this.hosted = hosted;
    }

    /** One crate in a search result. {@code description} may be {@code null}. */
    record Hit(String name, String maxVersion, String description) {
    }

    /** One page of results and how many there are in all. */
    record Result(List<Hit> hits, long total) {
    }

    Result search(Repository repo, String query, int perPage, int page) {
        String q = query == null ? "" : query.trim();
        if (repo.isProxy()) {
            Optional<Result> remote = remote(repo, q, perPage, page);
            if (remote.isPresent()) {
                return remote.get();
            }
        }
        if (!repo.isGroup()) {
            return slice(local(repo, q), perPage, page);
        }
        int wanted = (int) Math.min(MAX_PER_PAGE, (long) perPage * page);
        List<Hit> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (Repository member : indexes.members(repo)) {
            Result result = member.isProxy()
                    ? remote(member, q, wanted, 1).orElseGet(() -> slice(local(member, q), wanted, 1))
                    : slice(local(member, q), wanted, 1);
            total += result.total();
            for (Hit hit : result.hits()) {
                if (seen.add(CrateNames.canonical(hit.name()))) {
                    merged.add(hit);
                } else {
                    total--;
                }
            }
        }
        int from = (int) Math.min(merged.size(), (long) perPage * (page - 1));
        int to = (int) Math.min(merged.size(), (long) from + perPage);
        return new Result(List.copyOf(merged.subList(from, to)), Math.max(total, merged.size()));
    }

    /** The crates this repository itself stores (or has cached) that match, best matches first. */
    List<Hit> local(Repository repo, String query) {
        if (!repo.isOnline()) {
            return List.of();
        }
        List<String> terms = terms(query);
        List<Hit> hits = new ArrayList<>();
        for (Asset asset : storage.list(repo, CrateNames.INDEX_PREFIX)) {
            String name = CrateNames.nameOfIndexPath(asset.getPath());
            if (name == null) {
                continue;
            }
            ObjectNode meta = repo.isHosted() ? hosted.meta(repo, name).orElse(null) : null;
            String description = meta == null ? null : CargoJson.string(meta, "description");
            String haystack = CrateNames.canonical(name) + " " + lower(description) + " "
                    + lower(CargoHosted.text(meta, "keywords")).replace('_', '-');
            if (!terms.stream().allMatch(haystack::contains)) {
                continue;
            }
            List<IndexEntry> entries = repo.isHosted()
                    ? hosted.liveEntries(repo, name)
                    : storage.find(repo, asset.getPath()).map(CargoIndexes::read).orElseGet(List::of);
            if (entries.isEmpty()) {
                continue;
            }
            hits.add(hit(entries, description));
        }
        String exact = String.join("-", terms);
        hits.sort(Comparator.comparing((Hit h) -> !CrateNames.canonical(h.name()).equals(exact))
                .thenComparing(h -> !CrateNames.canonical(h.name()).startsWith(exact))
                .thenComparing(h -> CrateNames.lower(h.name())));
        return hits;
    }

    /** The newest usable version: the highest that is not yanked, or the highest at all when everything is yanked. */
    static Hit hit(List<IndexEntry> entries, String description) {
        List<String> live = entries.stream().filter(e -> !e.yanked()).map(IndexEntry::vers).toList();
        List<String> all = entries.stream().map(IndexEntry::vers).toList();
        String max = CargoVersion.highest(live).or(() -> CargoVersion.highest(all)).orElse(entries.getLast().vers());
        String name = entries.stream().filter(e -> e.vers().equals(max)).map(IndexEntry::name).findFirst()
                .orElse(entries.getLast().name());
        return new Hit(name, max, description);
    }

    /** The upstream web API's answer, or empty when it should not or could not be asked. */
    Optional<Result> remote(Repository repo, String query, int perPage, int page) {
        Optional<String> api = CargoSettings.apiUrl(repo);
        if (api.isEmpty() || proxy.isOffline(repo) || !proxy.status(repo).reachable()) {
            return Optional.empty();
        }
        URI uri = URI.create(api.get() + "/api/v1/crates?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&per_page=" + perPage + "&page=" + page);
        try (UpstreamClient.Response response = upstream.getAbsolute(repo, uri, Map.of(), Map.of("Accept", "application/json"))) {
            if (response.status() != 200) {
                log.info("search on the upstream of {} answered {}; searching the cache instead", repo.getName(),
                        response.status());
                return Optional.empty();
            }
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readAllBytes();
            }
            return CargoJson.parseObject(body).map(CargoSearch::parseResult);
        } catch (IOException | IllegalArgumentException e) {
            log.info("search on the upstream of {} failed ({}); searching the cache instead", repo.getName(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Reads a crates.io search document. */
    static Result parseResult(ObjectNode doc) {
        List<Hit> hits = new ArrayList<>();
        JsonNode crates = doc.get("crates");
        if (crates != null && crates.isArray()) {
            for (JsonNode c : crates) {
                String name = CargoJson.string(c, "name");
                if (name == null) {
                    continue;
                }
                String max = CargoJson.string(c, "max_version");
                if (max == null) {
                    max = CargoJson.string(c, "newest_version");
                }
                hits.add(new Hit(name, max == null ? "0.0.0" : max, CargoJson.string(c, "description")));
            }
        }
        JsonNode total = doc.path("meta").get("total");
        long count = total != null && total.isNumber() ? total.longValue() : hits.size();
        return new Result(hits, Math.max(count, hits.size()));
    }

    /** The response body: {@code {"crates":[{"name","max_version","description"}],"meta":{"total":n}}}. */
    static ObjectNode render(Result result) {
        ObjectNode body = CargoJson.object();
        ArrayNode crates = body.putArray("crates");
        for (Hit hit : result.hits()) {
            ObjectNode c = crates.addObject();
            c.put("name", hit.name());
            c.put("max_version", hit.maxVersion());
            if (hit.description() == null) {
                c.putNull("description");
            } else {
                c.put("description", hit.description());
            }
        }
        body.putObject("meta").put("total", result.total());
        return body;
    }

    static Result slice(List<Hit> hits, int perPage, int page) {
        int from = (int) Math.min(hits.size(), (long) perPage * (page - 1));
        int to = (int) Math.min(hits.size(), (long) from + perPage);
        return new Result(List.copyOf(hits.subList(from, to)), hits.size());
    }

    static List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        for (String t : lower(query).replace('_', '-').split("\\s+")) {
            if (!t.isEmpty()) {
                terms.add(t);
            }
        }
        return terms;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
