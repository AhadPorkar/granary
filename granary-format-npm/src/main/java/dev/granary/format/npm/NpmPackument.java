package dev.granary.format.npm;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Operations on a packument, the JSON document that describes every version of an npm package.
 *
 * <p>Packuments are kept as Jackson trees rather than mapped to classes because clients and upstream
 * registries put arbitrary members in them (anything from {@code package.json} ends up in a version
 * object) and all of it must survive a round trip through this server untouched. Only the members the
 * registry protocol itself gives meaning to ({@code versions}, {@code dist-tags}, {@code time},
 * {@code dist.tarball}) are interpreted here.
 */
public final class NpmPackument {

    public static final String ID = "_id";
    public static final String REV = "_rev";
    public static final String NAME = "name";
    public static final String VERSIONS = "versions";
    public static final String DIST_TAGS = "dist-tags";
    public static final String TIME = "time";
    public static final String DIST = "dist";
    public static final String TARBALL = "tarball";
    public static final String LATEST = "latest";
    public static final String CREATED = "created";
    public static final String MODIFIED = "modified";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    /** The millisecond ISO-8601 shape npmjs.org uses, so {@code time} looks identical whichever registry wrote it. */
    private static final DateTimeFormatter NPM_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    /** Members that describe the document rather than the package; never copied from a client document. */
    private static final Set<String> STRUCTURAL = Set.of(
            ID, REV, "_revisions", "_attachments", NAME, VERSIONS, DIST_TAGS, TIME, "access");

    private NpmPackument() {
    }

    public static ObjectNode newDocument(String name) {
        ObjectNode doc = NODES.objectNode();
        doc.put(ID, name);
        doc.put(NAME, name);
        doc.putObject(DIST_TAGS);
        doc.putObject(VERSIONS);
        doc.putObject(TIME);
        return doc;
    }

    /** The object member {@code field}, created when missing or of another type. */
    public static ObjectNode object(ObjectNode parent, String field) {
        JsonNode existing = parent.get(field);
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing;
        }
        return parent.putObject(field);
    }

    public static ObjectNode versions(ObjectNode doc) {
        return object(doc, VERSIONS);
    }

    public static ObjectNode distTags(ObjectNode doc) {
        return object(doc, DIST_TAGS);
    }

    public static ObjectNode time(ObjectNode doc) {
        return object(doc, TIME);
    }

    /** The version numbers in document order. */
    public static Set<String> versionNames(JsonNode doc) {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> e : entries(doc, VERSIONS)) {
            names.add(e.getKey());
        }
        return names;
    }

    /** Resolves a version number or a dist-tag such as {@code latest} to its version object. */
    public static Optional<ObjectNode> version(JsonNode doc, String versionOrTag) {
        JsonNode versions = doc.get(VERSIONS);
        if (versions == null || !versions.isObject()) {
            return Optional.empty();
        }
        JsonNode direct = versions.get(versionOrTag);
        if (direct != null && direct.isObject()) {
            return Optional.of((ObjectNode) direct);
        }
        String tagged = string(doc.path(DIST_TAGS), versionOrTag);
        if (tagged != null) {
            JsonNode node = versions.get(tagged);
            if (node != null && node.isObject()) {
                return Optional.of((ObjectNode) node);
            }
        }
        return Optional.empty();
    }

    public static Optional<String> latest(JsonNode doc) {
        return Optional.ofNullable(string(doc.path(DIST_TAGS), LATEST));
    }

    /** The string member {@code field}, or {@code null} when absent or not a string. */
    public static String string(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    // --- tarball URLs --------------------------------------------------------------------------

    /** The file name npm uses for a tarball: the unscoped name, a dash and the version. */
    public static String defaultFileName(String packageName, String version) {
        return NpmPath.unscopedNameOf(packageName) + "-" + version + ".tgz";
    }

    /** The last path segment of a tarball URL, or {@code null} when there is none. */
    public static String fileNameOf(String url) {
        if (url == null) {
            return null;
        }
        String s = url;
        int cut = s.indexOf('?');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        cut = s.indexOf('#');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        int slash = s.lastIndexOf('/');
        String file = slash >= 0 ? s.substring(slash + 1) : s;
        return file.isEmpty() ? null : file;
    }

    /** The file a version's tarball is stored as: taken from its {@code dist.tarball}, else the npm default. */
    public static String tarballFileName(String packageName, String version, JsonNode versionNode) {
        String file = versionNode == null ? null : fileNameOf(string(versionNode.path(DIST), TARBALL));
        return file == null ? defaultFileName(packageName, version) : file;
    }

    public static String tarballUrl(String baseUrl, String packageName, String fileName) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/" + NpmPath.tarballPath(packageName, fileName);
    }

    /**
     * Points every {@code dist.tarball} at this repository. Done on the way out, never when storing, so a
     * cached or published document stays valid when the server's external URL changes.
     */
    public static void rewriteTarballs(ObjectNode doc, String baseUrl, String packageName) {
        for (Map.Entry<String, JsonNode> e : entries(doc, VERSIONS)) {
            if (e.getValue().isObject()) {
                rewriteTarball((ObjectNode) e.getValue(), baseUrl, packageName, e.getKey());
            }
        }
    }

    public static void rewriteTarball(ObjectNode versionNode, String baseUrl, String packageName, String version) {
        String file = tarballFileName(packageName, version, versionNode);
        object(versionNode, DIST).put(TARBALL, tarballUrl(baseUrl, packageName, file));
    }

    // --- mutation ------------------------------------------------------------------------------

    public static void putVersion(ObjectNode doc, String version, ObjectNode versionNode, Instant now) {
        versions(doc).set(version, versionNode);
        ObjectNode time = time(doc);
        time.put(version, format(now));
        if (!time.has(CREATED)) {
            time.put(CREATED, format(now));
        }
        time.put(MODIFIED, format(now));
    }

    /**
     * Drops a version together with its {@code time} entry and every dist-tag pointing at it. When
     * {@code latest} went with it, the tag is re-pointed at the highest remaining release, which is what
     * npm itself computes before it sends an unpublish.
     */
    public static boolean removeVersion(ObjectNode doc, String version) {
        ObjectNode versions = versions(doc);
        if (versions.remove(version) == null) {
            return false;
        }
        time(doc).remove(version);
        ObjectNode tags = distTags(doc);
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : tags.properties()) {
            if (e.getValue().isString() && version.equals(e.getValue().stringValue())) {
                stale.add(e.getKey());
            }
        }
        stale.forEach(tags::remove);
        if (!tags.has(LATEST)) {
            NpmSemver.highestRelease(versionNames(doc)).ifPresent(v -> tags.put(LATEST, v));
        }
        return true;
    }

    public static void touch(ObjectNode doc, Instant now) {
        ObjectNode time = time(doc);
        if (!time.has(CREATED)) {
            time.put(CREATED, format(now));
        }
        time.put(MODIFIED, format(now));
    }

    /**
     * Advances the CouchDB-style revision {@code N-hash}. npm sends the revision back on unpublish; the
     * number is bumped on every write so clients holding an old document can tell it changed.
     */
    public static String bumpRevision(ObjectNode doc) {
        long number = 0;
        String current = string(doc, REV);
        if (current != null) {
            int dash = current.indexOf('-');
            try {
                number = Long.parseLong(dash > 0 ? current.substring(0, dash) : current);
            } catch (NumberFormatException ignored) {
                number = 0;
            }
        }
        String rev = (number + 1) + "-" + UUID.randomUUID().toString().replace("-", "");
        doc.put(REV, rev);
        return rev;
    }

    /** Copies every member that describes the package ({@code description}, {@code readme}, {@code author}, …). */
    public static void copyDescriptiveFields(JsonNode from, ObjectNode to) {
        if (from == null || !from.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> e : from.properties()) {
            if (!STRUCTURAL.contains(e.getKey())) {
                to.set(e.getKey(), e.getValue().deepCopy());
            }
        }
    }

    // --- group merge ---------------------------------------------------------------------------

    /**
     * Merges the documents of a group's members, in member order. Versions, tags and timestamps are a
     * union in which the first member wins a conflict, except {@code latest}, which points at the highest
     * version any member calls latest: a group that fronts a hosted repository and npmjs.org must resolve
     * {@code npm install foo} to the newest release, wherever it lives. Descriptive members come from the
     * first document.
     */
    public static ObjectNode merge(List<ObjectNode> docs) {
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("nothing to merge");
        }
        ObjectNode first = docs.getFirst();
        String name = string(first, NAME);
        ObjectNode result = NODES.objectNode();
        if (name != null) {
            result.put(ID, name);
            result.put(NAME, name);
        }
        String rev = string(first, REV);
        if (rev != null) {
            result.put(REV, rev);
        }
        copyDescriptiveFields(first, result);
        ObjectNode versions = result.putObject(VERSIONS);
        ObjectNode tags = result.putObject(DIST_TAGS);
        ObjectNode time = result.putObject(TIME);
        List<String> latestCandidates = new ArrayList<>();
        for (ObjectNode doc : docs) {
            for (Map.Entry<String, JsonNode> e : entries(doc, VERSIONS)) {
                if (!versions.has(e.getKey()) && e.getValue().isObject()) {
                    versions.set(e.getKey(), e.getValue().deepCopy());
                }
            }
            for (Map.Entry<String, JsonNode> e : entries(doc, DIST_TAGS)) {
                if (!e.getValue().isString()) {
                    continue;
                }
                if (LATEST.equals(e.getKey())) {
                    latestCandidates.add(e.getValue().stringValue());
                } else if (!tags.has(e.getKey())) {
                    tags.put(e.getKey(), e.getValue().stringValue());
                }
            }
            for (Map.Entry<String, JsonNode> e : entries(doc, TIME)) {
                if (!e.getValue().isString()) {
                    continue;
                }
                String value = e.getValue().stringValue();
                if (MODIFIED.equals(e.getKey())) {
                    String current = string(time, MODIFIED);
                    if (current == null || compareTimes(value, current) > 0) {
                        time.put(MODIFIED, value);
                    }
                } else if (!time.has(e.getKey())) {
                    time.put(e.getKey(), value);
                }
            }
        }
        Optional<String> latest = NpmSemver.highest(latestCandidates.stream().filter(versions::has).toList());
        if (latest.isEmpty()) {
            latest = NpmSemver.highestRelease(versionNames(result));
        }
        latest.ifPresent(v -> tags.put(LATEST, v));
        return result;
    }

    // --- misc ----------------------------------------------------------------------------------

    public static String format(Instant instant) {
        return NPM_TIME.format(instant);
    }

    /** The entries of the object member {@code field}, or nothing when it is absent or not an object. */
    public static Iterable<Map.Entry<String, JsonNode>> entries(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || !node.isObject()) {
            return List.of();
        }
        return node.properties();
    }

    private static int compareTimes(String a, String b) {
        try {
            return Instant.parse(a).compareTo(Instant.parse(b));
        } catch (DateTimeParseException e) {
            return a.compareTo(b);
        }
    }
}
