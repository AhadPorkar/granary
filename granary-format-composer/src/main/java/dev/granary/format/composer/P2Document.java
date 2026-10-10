package dev.granary.format.composer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code p2/{vendor}/{name}.json} (or {@code ~dev}) document: the package's version entries, always held
 * expanded, plus whatever other top-level keys the document carried ({@code security-advisories} on
 * Packagist). {@link #lastModified} is when the content last changed, for {@code Last-Modified}.
 *
 * @param name         the lower-case package name
 * @param versions     complete version entries, in document order
 * @param extras       top-level keys other than {@code packages} and {@code minified}
 * @param lastModified when the document last changed, or {@code null} when unknown
 */
public record P2Document(String name, List<ObjectNode> versions, ObjectNode extras, Instant lastModified) {

    public static final String PACKAGES = "packages";
    public static final String MINIFIED = "minified";
    public static final String VERSION = "version";
    public static final String VERSION_NORMALIZED = "version_normalized";
    public static final String NAME = "name";
    public static final String DIST = "dist";
    /** {@code VersionParser::DEFAULT_BRANCH_ALIAS}; Composer re-normalises entries that carry it. */
    public static final String DEFAULT_BRANCH_ALIAS = "9999999-dev";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    public P2Document {
        versions = List.copyOf(versions);
        extras = extras == null ? NODES.objectNode() : extras;
    }

    public static P2Document empty(String name) {
        return new P2Document(name, List.of(), NODES.objectNode(), null);
    }

    public P2Document withVersions(List<ObjectNode> newVersions) {
        return new P2Document(name, newVersions, extras, lastModified);
    }

    public P2Document withLastModified(Instant instant) {
        return new P2Document(name, versions, extras, instant);
    }

    public boolean isEmpty() {
        return versions.isEmpty();
    }

    /**
     * Reads a p2 document. The entries under {@code packages.<name>} may be minified ({@code "minified":
     * "composer/2.0"}), a plain list or, from old repositories, an object keyed by version; all three come back
     * expanded. A document whose {@code packages} does not mention the package yields an empty version list.
     *
     * @return empty when the bytes are not a JSON object
     */
    public static Optional<P2Document> parse(ObjectMapper mapper, byte[] bytes, String name, Instant lastModified) {
        JsonNode root;
        try {
            root = mapper.readTree(bytes);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (!(root instanceof ObjectNode object)) {
            return Optional.empty();
        }
        ObjectNode extras = NODES.objectNode();
        for (Map.Entry<String, JsonNode> e : object.properties()) {
            if (!e.getKey().equals(PACKAGES) && !e.getKey().equals(MINIFIED) && !e.getKey().equals("last-modified")) {
                extras.set(e.getKey(), e.getValue());
            }
        }
        List<JsonNode> raw = new ArrayList<>();
        JsonNode packages = object.get(PACKAGES);
        if (packages instanceof ObjectNode byName) {
            JsonNode entries = entriesFor(byName, name);
            if (entries instanceof ArrayNode array) {
                array.forEach(raw::add);
            } else if (entries instanceof ObjectNode byVersion) {
                byVersion.forEach(raw::add);
            }
        }
        JsonNode minified = object.get(MINIFIED);
        boolean isMinified = minified != null && minified.isString() && MetadataMinifier.FORMAT.equals(minified.stringValue());
        List<ObjectNode> versions = isMinified ? MetadataMinifier.expand(raw) : copies(raw);
        return Optional.of(new P2Document(name, versions, extras, lastModified));
    }

    private static JsonNode entriesFor(ObjectNode byName, String name) {
        JsonNode exact = byName.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, JsonNode> e : byName.properties()) {
            if (e.getKey().toLowerCase(Locale.ROOT).equals(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static List<ObjectNode> copies(Collection<JsonNode> raw) {
        List<ObjectNode> list = new ArrayList<>(raw.size());
        for (JsonNode node : raw) {
            if (node instanceof ObjectNode o) {
                list.add(o.deepCopy());
            }
        }
        return list;
    }

    /** The minified wire form: {@code {"minified":"composer/2.0","packages":{"<name>":[…]}, …extras}}. */
    public ObjectNode toJson() {
        ObjectNode root = NODES.objectNode();
        root.put(MINIFIED, MetadataMinifier.FORMAT);
        ObjectNode packages = root.putObject(PACKAGES);
        ArrayNode list = packages.putArray(name);
        MetadataMinifier.minify(versions).forEach(list::add);
        for (Map.Entry<String, JsonNode> e : extras.properties()) {
            if (!root.has(e.getKey())) {
                root.set(e.getKey(), e.getValue());
            }
        }
        return root;
    }

    // --- version entries -----------------------------------------------------------------------------

    public static String string(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    /** The pretty version of an entry ({@code v1.2.0}, {@code dev-main}). */
    public static String version(ObjectNode entry) {
        JsonNode v = entry.get(VERSION);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isString() ? v.stringValue() : v.asString();
    }

    /**
     * The normalised version that identifies an entry: {@code version_normalized} when present (except the default
     * branch alias, which Composer recomputes too), else the normalised {@code version}, else the raw version.
     */
    public static String normalized(ObjectNode entry) {
        String normalized = string(entry, VERSION_NORMALIZED);
        if (normalized != null && !normalized.isEmpty() && !DEFAULT_BRANCH_ALIAS.equals(normalized)) {
            return normalized;
        }
        String version = version(entry);
        if (version == null) {
            return null;
        }
        return ComposerVersions.tryNormalize(version).orElse(version);
    }

    /** The entry whose pretty version is {@code version}, or whose normalised version equals its normalised form. */
    public Optional<ObjectNode> find(String version) {
        for (ObjectNode entry : versions) {
            if (version.equals(version(entry))) {
                return Optional.of(entry);
            }
        }
        Optional<String> wanted = ComposerVersions.tryNormalize(version);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        for (ObjectNode entry : versions) {
            if (wanted.get().equals(normalized(entry))) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }
}
