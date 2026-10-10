package dev.granary.format.pub;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The package listing of the Hosted Pub Repository Specification v2 and the operations on it:
 *
 * <pre>
 * {"name": "foo", "isDiscontinued": false, "replacedBy": "bar", "advisoriesUpdated": "…",
 *  "latest": {version object},
 *  "versions": [{"version": "1.0.0", "retracted": false, "pubspec": {…}, "archive_url": "…",
 *                "archive_sha256": "…", "published": "…"}]}
 * </pre>
 *
 * Listings are kept as Jackson trees because upstream repositories may add members the pub client knows
 * and this server does not; everything survives a round trip. Rendering for a client points every
 * {@code archive_url} at the repository the client asked, sorts versions in ascending order and drops
 * entries whose version is not a pub version (the client could not use them and the repository could not
 * route their archive).
 */
public final class PubListing {

    public static final String NAME = "name";
    public static final String LATEST = "latest";
    public static final String VERSIONS = "versions";
    public static final String VERSION = "version";
    public static final String PUBSPEC = "pubspec";
    public static final String ARCHIVE_URL = "archive_url";
    public static final String ARCHIVE_SHA256 = "archive_sha256";
    public static final String PUBLISHED = "published";
    public static final String RETRACTED = "retracted";
    public static final String IS_DISCONTINUED = "isDiscontinued";
    public static final String REPLACED_BY = "replacedBy";
    public static final String ADVISORIES_UPDATED = "advisoriesUpdated";
    public static final String ADVISORIES = "advisories";

    /** The member order of a version object, as pub.dev writes it. */
    private static final List<String> VERSION_ORDER = List.of(VERSION, RETRACTED, PUBSPEC, ARCHIVE_URL, ARCHIVE_SHA256, PUBLISHED);

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final DateTimeFormatter PUB_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private PubListing() {
    }

    /** One version object of a listing with its parsed version. */
    public record Entry(PubVersion version, ObjectNode node) {

        public boolean retracted() {
            JsonNode r = node.get(RETRACTED);
            return r != null && r.isBoolean() && r.booleanValue();
        }

        public Optional<String> sha256() {
            return Optional.ofNullable(string(node, ARCHIVE_SHA256));
        }

        public Optional<String> archiveUrl() {
            return Optional.ofNullable(string(node, ARCHIVE_URL));
        }
    }

    /**
     * The usable version entries of a listing in document order: objects with a valid {@code version}; of two
     * entries for the same version only the first counts.
     */
    public static List<Entry> entries(JsonNode listing) {
        List<Entry> entries = new ArrayList<>();
        JsonNode versions = listing == null ? null : listing.get(VERSIONS);
        if (versions == null || !versions.isArray()) {
            return entries;
        }
        for (JsonNode node : versions) {
            if (!node.isObject()) {
                continue;
            }
            Optional<PubVersion> version = PubVersion.parse(string(node, VERSION));
            if (version.isEmpty() || entries.stream().anyMatch(e -> e.version().sameAs(version.get()))) {
                continue;
            }
            entries.add(new Entry(version.get(), (ObjectNode) node));
        }
        return entries;
    }

    /** The entry for {@code version}: the exact spelling first, else a version the client considers equal. */
    public static Optional<Entry> find(JsonNode listing, String version) {
        List<Entry> entries = entries(listing);
        for (Entry e : entries) {
            if (e.version().text().equals(version)) {
                return Optional.of(e);
            }
        }
        Optional<PubVersion> wanted = PubVersion.parse(version);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return entries.stream().filter(e -> e.version().sameAs(wanted.get())).findFirst();
    }

    /**
     * The listing as a client gets it from the repository at {@code baseUrl}.
     *
     * @param name         the package name (taken from the URL, not trusted from the document)
     * @param source       a stored or upstream listing
     * @param baseUrl      the repository the client asked, without a trailing slash
     * @param keepLatest   use the source's own {@code latest} when it names a listed version (proxies keep the
     *                     upstream's choice); otherwise {@code latest} is computed with pub's rules
     */
    public static ObjectNode render(String name, JsonNode source, String baseUrl, boolean keepLatest) {
        ObjectNode out = NODES.objectNode();
        out.put(NAME, name);
        copyStatus(source, out);
        String advisoriesUpdated = string(source, ADVISORIES_UPDATED);
        if (advisoriesUpdated != null) {
            out.put(ADVISORIES_UPDATED, advisoriesUpdated);
        }
        List<Entry> entries = new ArrayList<>(entries(source));
        entries.sort(Comparator.comparing(Entry::version));
        List<ObjectNode> rendered = new ArrayList<>();
        for (Entry entry : entries) {
            rendered.add(renderVersion(entry, name, baseUrl));
        }
        if (!rendered.isEmpty()) {
            ObjectNode latest = null;
            if (keepLatest) {
                String upstreamLatest = string(source.path(LATEST), VERSION);
                for (int i = 0; i < entries.size() && upstreamLatest != null; i++) {
                    if (entries.get(i).version().text().equals(upstreamLatest)) {
                        latest = rendered.get(i);
                    }
                }
            }
            if (latest == null) {
                latest = rendered.get(entries.indexOf(latestEntry(entries)));
            }
            out.set(LATEST, latest.deepCopy());
        }
        ArrayNode versions = out.putArray(VERSIONS);
        rendered.forEach(versions::add);
        return out;
    }

    /** A version object with its archive at {@code baseUrl}, members in pub.dev's order. */
    public static ObjectNode renderVersion(Entry entry, String name, String baseUrl) {
        ObjectNode source = entry.node();
        ObjectNode out = NODES.objectNode();
        out.put(VERSION, entry.version().text());
        JsonNode retracted = source.get(RETRACTED);
        if (retracted != null && retracted.isBoolean() && retracted.booleanValue()) {
            out.put(RETRACTED, true);
        }
        JsonNode pubspec = source.get(PUBSPEC);
        out.set(PUBSPEC, pubspec != null && pubspec.isObject() ? pubspec.deepCopy() : NODES.objectNode());
        out.put(ARCHIVE_URL, archiveUrl(baseUrl, name, entry.version().text()));
        String sha = string(source, ARCHIVE_SHA256);
        if (sha != null) {
            out.put(ARCHIVE_SHA256, sha);
        }
        String published = string(source, PUBLISHED);
        if (published != null) {
            out.put(PUBLISHED, published);
        }
        for (Map.Entry<String, JsonNode> member : source.properties()) {
            if (!VERSION_ORDER.contains(member.getKey()) && !member.getKey().startsWith("_")) {
                out.set(member.getKey(), member.getValue().deepCopy());
            }
        }
        return out;
    }

    /** Where clients download an archive from the repository at {@code baseUrl}. */
    public static String archiveUrl(String baseUrl, String name, String version) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/" + PubPath.archivePath(name, version);
    }

    /**
     * Joins the listings of a group's members, already rendered for the group. Members come in precedence
     * order: the first member that lists a version owns it; the discontinued flag comes from the first member
     * that knows the package; {@code advisoriesUpdated} is the most recent of all; {@code latest} is computed
     * over the union with pub's rules.
     */
    public static ObjectNode merge(String name, List<? extends JsonNode> listings) {
        ObjectNode out = NODES.objectNode();
        out.put(NAME, name);
        if (!listings.isEmpty()) {
            copyStatus(listings.getFirst(), out);
        }
        String advisoriesUpdated = null;
        for (JsonNode listing : listings) {
            advisoriesUpdated = later(advisoriesUpdated, string(listing, ADVISORIES_UPDATED));
        }
        if (advisoriesUpdated != null) {
            out.put(ADVISORIES_UPDATED, advisoriesUpdated);
        }
        List<Entry> union = new ArrayList<>();
        for (JsonNode listing : listings) {
            for (Entry entry : entries(listing)) {
                if (union.stream().noneMatch(e -> e.version().sameAs(entry.version()))) {
                    union.add(entry);
                }
            }
        }
        union.sort(Comparator.comparing(Entry::version));
        if (!union.isEmpty()) {
            out.set(LATEST, latestEntry(union).node().deepCopy());
        }
        ArrayNode versions = out.putArray(VERSIONS);
        union.forEach(e -> versions.add(e.node().deepCopy()));
        return out;
    }

    /** The entry pub.dev would call {@code latest}; {@code entries} must not be empty. */
    static Entry latestEntry(List<Entry> entries) {
        List<PubVersion> all = entries.stream().map(Entry::version).toList();
        List<PubVersion> retracted = entries.stream().filter(Entry::retracted).map(Entry::version).toList();
        PubVersion latest = PubVersion.latest(all, retracted).orElseThrow();
        return entries.stream().filter(e -> e.version().equals(latest)).findFirst().orElseThrow();
    }

    private static void copyStatus(JsonNode source, ObjectNode out) {
        JsonNode discontinued = source.get(IS_DISCONTINUED);
        if (discontinued != null && discontinued.isBoolean() && discontinued.booleanValue()) {
            out.put(IS_DISCONTINUED, true);
            String replacedBy = string(source, REPLACED_BY);
            if (replacedBy != null) {
                out.put(REPLACED_BY, replacedBy);
            }
        }
    }

    // --- advisories --------------------------------------------------------------------------------

    /** The advisories document of a package that has none (hosted packages). */
    public static ObjectNode noAdvisories() {
        ObjectNode doc = NODES.objectNode();
        doc.putArray(ADVISORIES);
        doc.putNull(ADVISORIES_UPDATED);
        return doc;
    }

    /** Joins advisories documents: every advisory once (by {@code id}), the latest {@code advisoriesUpdated}. */
    public static ObjectNode mergeAdvisories(List<? extends JsonNode> docs) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        List<JsonNode> withoutId = new ArrayList<>();
        String updated = null;
        for (JsonNode doc : docs) {
            updated = later(updated, string(doc, ADVISORIES_UPDATED));
            JsonNode list = doc.get(ADVISORIES);
            if (list == null || !list.isArray()) {
                continue;
            }
            for (JsonNode advisory : list) {
                String id = string(advisory, "id");
                if (id == null) {
                    withoutId.add(advisory);
                } else {
                    byId.putIfAbsent(id, advisory);
                }
            }
        }
        ObjectNode out = NODES.objectNode();
        ArrayNode advisories = out.putArray(ADVISORIES);
        byId.values().forEach(a -> advisories.add(a.deepCopy()));
        withoutId.forEach(a -> advisories.add(a.deepCopy()));
        if (updated == null) {
            out.putNull(ADVISORIES_UPDATED);
        } else {
            out.put(ADVISORIES_UPDATED, updated);
        }
        return out;
    }

    /** The later of two timestamps as pub repositories write them (with or without zone); null-tolerant. */
    static String later(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        Optional<Instant> ia = instant(a);
        Optional<Instant> ib = instant(b);
        if (ia.isPresent() && ib.isPresent()) {
            return ib.get().isAfter(ia.get()) ? b : a;
        }
        return b.compareTo(a) > 0 ? b : a;
    }

    static Optional<Instant> instant(String text) {
        try {
            return Optional.of(OffsetDateTime.parse(text).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(LocalDateTime.parse(text).toInstant(ZoneOffset.UTC));
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    /** A timestamp in the microsecond UTC shape pub.dev uses for {@code published}. */
    public static String timestamp(Instant instant) {
        return PUB_TIME.format(instant);
    }

    /** The string member {@code field}, or {@code null} when absent or not a string. */
    public static String string(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
