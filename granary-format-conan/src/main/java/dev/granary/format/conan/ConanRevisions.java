package dev.granary.format.conan;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The revision documents of the v2 API and how they are merged across group members.
 *
 * <p>{@code GET …/revisions} answers {@code {"revisions":[{"revision":"…","time":"…"}]}} newest first,
 * {@code …/latest} the first of those entries and {@code …/files} {@code {"files":{"conanfile.py":{},…}}}.
 * Times are rendered the way Artifactory does ({@code 2026-01-31T12:00:00.000+0000}); the parser accepts that,
 * {@code conan_server}'s {@code +00:00} form and plain {@code Z} timestamps.
 */
public final class ConanRevisions {

    /** One revision with its upload time; {@code time} is the text the document shows, {@code instant} orders it. */
    public record Entry(String revision, String time, Instant instant) {
    }

    static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final Pattern OFFSET_WITHOUT_COLON = Pattern.compile("([+-]\\d{2})(\\d{2})$");

    /** Newest first; ties broken by revision so the order is stable between requests. */
    static final Comparator<Entry> NEWEST_FIRST = Comparator.comparing(Entry::instant, Comparator.reverseOrder())
            .thenComparing(Entry::revision);

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ConanRevisions() {
    }

    public static String format(Instant instant) {
        return FORMAT.format(instant == null ? Instant.EPOCH : instant);
    }

    /** Parses an ISO-8601 time; empty when the text is not one. */
    public static Optional<Instant> parseTime(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = OFFSET_WITHOUT_COLON.matcher(text.trim()).replaceFirst("$1:$2");
        try {
            return Optional.of(OffsetDateTime.parse(t).toInstant());
        } catch (DateTimeParseException ignored) {
            // not an offset date-time; try the other shapes
        }
        try {
            return Optional.of(LocalDateTime.parse(t).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException ignored) {
            // not a local date-time either
        }
        try {
            return Optional.of(Instant.parse(t));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    public static Entry entry(String revision, Instant time) {
        Instant instant = time == null ? Instant.EPOCH : time;
        return new Entry(revision, format(instant), instant);
    }

    public static ObjectNode revisionsDocument(List<Entry> entries) {
        ObjectNode doc = NODES.objectNode();
        ArrayNode array = doc.putArray("revisions");
        for (Entry entry : entries) {
            array.add(entryNode(entry));
        }
        return doc;
    }

    public static ObjectNode latestDocument(Entry entry) {
        return entryNode(entry);
    }

    private static ObjectNode entryNode(Entry entry) {
        ObjectNode node = NODES.objectNode();
        node.put("revision", entry.revision());
        node.put("time", entry.time());
        return node;
    }

    public static ObjectNode filesDocument(Collection<String> files) {
        ObjectNode doc = NODES.objectNode();
        ObjectNode map = doc.putObject("files");
        for (String file : files) {
            map.putObject(file);
        }
        return doc;
    }

    /** The entries of a {@code revisions} document; unparsable items are skipped, missing times sort last. */
    public static List<Entry> parseRevisions(JsonNode doc) {
        List<Entry> entries = new ArrayList<>();
        JsonNode array = doc == null ? null : doc.path("revisions");
        if (array == null || !array.isArray()) {
            return entries;
        }
        for (JsonNode item : array) {
            parseEntry(item).ifPresent(entries::add);
        }
        return entries;
    }

    public static Optional<Entry> parseEntry(JsonNode item) {
        if (item == null || !item.isObject()) {
            return Optional.empty();
        }
        JsonNode revision = item.path("revision");
        if (!revision.isString() || revision.stringValue().isBlank()) {
            return Optional.empty();
        }
        JsonNode time = item.path("time");
        String text = time.isString() ? time.stringValue() : null;
        Instant instant = parseTime(text).orElse(Instant.EPOCH);
        return Optional.of(new Entry(revision.stringValue(), text == null ? format(instant) : text, instant));
    }

    /** The file names of a {@code files} document, in document order. */
    public static List<String> parseFiles(JsonNode doc) {
        List<String> files = new ArrayList<>();
        JsonNode map = doc == null ? null : doc.path("files");
        if (map != null && map.isObject()) {
            map.propertyNames().forEach(files::add);
        }
        return files;
    }

    /**
     * The union of several revision lists: a revision that several sources know keeps the entry of the first source
     * (so a hosted member's time wins over a proxy's), and the result is ordered newest first.
     */
    public static List<Entry> merge(List<List<Entry>> sources) {
        Map<String, Entry> byRevision = new LinkedHashMap<>();
        for (List<Entry> source : sources) {
            for (Entry entry : source) {
                byRevision.putIfAbsent(entry.revision(), entry);
            }
        }
        List<Entry> merged = new ArrayList<>(byRevision.values());
        merged.sort(NEWEST_FIRST);
        return merged;
    }

    public static List<Entry> sorted(Collection<Entry> entries) {
        List<Entry> list = new ArrayList<>(entries);
        list.sort(NEWEST_FIRST);
        return list;
    }
}
