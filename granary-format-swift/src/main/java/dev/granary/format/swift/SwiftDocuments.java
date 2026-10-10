package dev.granary.format.swift;

import dev.granary.core.model.Repository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON documents and {@code Link} headers of the protocol: the release list of a package, the metadata
 * document of a release, the identifiers lookup result, and the merging of release lists across group members.
 */
final class SwiftDocuments {

    static final String SOURCE_ARCHIVE = "source-archive";

    /**
     * One entry of a release list.
     *
     * @param version the version as listed
     * @param problem the problem details object marking the release unavailable, or {@code null}
     * @param owner   the (leaf) repository the entry came from
     */
    record Release(String version, JsonNode problem, Repository owner) {

        boolean available() {
            return problem == null;
        }
    }

    private final ObjectMapper json;

    SwiftDocuments(ObjectMapper json) {
        this.json = json;
    }

    ObjectMapper mapper() {
        return json;
    }

    // --- release lists ------------------------------------------------------------------------------

    /** Releases keyed by version, highest precedence first. */
    static Map<String, Release> sorted(Collection<Release> releases) {
        List<Release> list = new ArrayList<>(releases);
        list.sort((a, b) -> SwiftVersion.ORDER.compare(b.version(), a.version()));
        Map<String, Release> map = new LinkedHashMap<>();
        for (Release r : list) {
            map.putIfAbsent(r.version(), r);
        }
        return map;
    }

    /** Group merge: the union of the members' releases, the first member listing a version owning it. */
    static Map<String, Release> merge(List<Map<String, Release>> members) {
        Map<String, Release> merged = new LinkedHashMap<>();
        for (Map<String, Release> member : members) {
            for (Release r : member.values()) {
                merged.putIfAbsent(r.version(), r);
            }
        }
        return sorted(merged.values());
    }

    /** The versions that are not marked with a problem, highest precedence first. */
    static List<String> available(Map<String, Release> releases) {
        return releases.values().stream().filter(Release::available).map(Release::version).toList();
    }

    /** Parses an upstream release list ({@code {"releases":{"1.0.0":{"url":...,"problem":...}}}}); invalid versions are skipped. */
    static Map<String, Release> parseReleases(JsonNode document, Repository owner) {
        List<Release> releases = new ArrayList<>();
        JsonNode map = document == null ? null : document.path("releases");
        if (map != null && map.isObject()) {
            for (Map.Entry<String, JsonNode> e : map.properties()) {
                if (!SwiftVersion.isValid(e.getKey())) {
                    continue;
                }
                JsonNode problem = e.getValue().path("problem");
                releases.add(new Release(e.getKey(), problem.isObject() ? problem : null, owner));
            }
        }
        return sorted(releases);
    }

    ObjectNode releasesDocument(String baseUrl, SwiftIdentity id, Collection<Release> releases) {
        ObjectNode doc = json.createObjectNode();
        ObjectNode map = doc.putObject("releases");
        for (Release r : releases) {
            ObjectNode entry = map.putObject(r.version());
            entry.put("url", releaseUrl(baseUrl, id, r.version()));
            if (r.problem() != null) {
                entry.set("problem", r.problem().deepCopy());
            }
        }
        return doc;
    }

    // --- release metadata ---------------------------------------------------------------------------

    /** The metadata document of a newly published release, stored as it is served. */
    ObjectNode releaseDocument(SwiftIdentity id, String version, String checksum, String signatureBase64,
                               String signatureFormat, ObjectNode metadata, Instant publishedAt) {
        ObjectNode doc = json.createObjectNode();
        doc.put("id", id.id());
        doc.put("version", version);
        ArrayNode resources = doc.putArray("resources");
        ObjectNode archive = resources.addObject();
        archive.put("name", SOURCE_ARCHIVE);
        archive.put("type", "application/zip");
        archive.put("checksum", checksum);
        if (signatureBase64 != null) {
            ObjectNode signing = archive.putObject("signing");
            signing.put("signatureBase64Encoded", signatureBase64);
            signing.put("signatureFormat", signatureFormat);
        }
        doc.set("metadata", metadata == null ? json.createObjectNode() : metadata);
        doc.put("publishedAt", SwiftMetadata.timestamp(publishedAt));
        return doc;
    }

    /** The {@code source-archive} resource of a release document. */
    static Optional<JsonNode> sourceArchive(JsonNode releaseDocument) {
        if (releaseDocument == null) {
            return Optional.empty();
        }
        for (JsonNode resource : releaseDocument.path("resources")) {
            if (SOURCE_ARCHIVE.equals(text(resource, "name"))) {
                return Optional.of(resource);
            }
        }
        return Optional.empty();
    }

    /** The source archive's checksum in a release document, lower-case hex. */
    static Optional<String> checksum(JsonNode releaseDocument) {
        return sourceArchive(releaseDocument).map(r -> text(r, "checksum")).filter(c -> c != null && c.matches("[0-9a-fA-F]{64}"))
                .map(c -> c.toLowerCase(java.util.Locale.ROOT));
    }

    /** The source archive's signature ({@code [base64, format]}) in a release document. */
    static Optional<String[]> signing(JsonNode releaseDocument) {
        return sourceArchive(releaseDocument).map(r -> r.path("signing")).filter(JsonNode::isObject).flatMap(s -> {
            String signature = text(s, "signatureBase64Encoded");
            String format = text(s, "signatureFormat");
            return signature == null || format == null ? Optional.empty() : Optional.of(new String[] {signature, format});
        });
    }

    // --- identifiers --------------------------------------------------------------------------------

    ObjectNode identifiersDocument(Collection<String> identifiers) {
        ObjectNode doc = json.createObjectNode();
        ArrayNode list = doc.putArray("identifiers");
        identifiers.forEach(list::add);
        return doc;
    }

    /** The identifiers of a lookup result or index document; invalid entries are skipped. */
    static List<String> parseIdentifiers(JsonNode document) {
        List<String> result = new ArrayList<>();
        if (document == null) {
            return result;
        }
        for (JsonNode id : document.path("identifiers")) {
            if (id.isString() && SwiftIdentity.parse(id.stringValue()).isPresent()) {
                result.add(id.stringValue().trim());
            }
        }
        return result;
    }

    /** Adds identifiers not yet present (identities compare case-insensitively), keeping the order. */
    static void addAll(List<String> target, Collection<String> identifiers) {
        for (String id : identifiers) {
            boolean present = target.stream().anyMatch(existing -> existing.equalsIgnoreCase(id));
            if (!present) {
                target.add(id);
            }
        }
    }

    // --- links --------------------------------------------------------------------------------------

    static String packageUrl(String baseUrl, SwiftIdentity id) {
        return baseUrl + "/" + id.scope() + "/" + id.name();
    }

    static String releaseUrl(String baseUrl, SwiftIdentity id, String version) {
        return packageUrl(baseUrl, id) + "/" + version;
    }

    static String manifestUrl(String baseUrl, SwiftIdentity id, String version) {
        return releaseUrl(baseUrl, id, version) + "/" + SwiftManifests.MANIFEST;
    }

    /**
     * {@code Link} of a release list: {@code latest-version}, plus the source repositories of the latest release as
     * {@code canonical} (the first) and {@code alternate} (the others), each in the two-field shape SwiftPM parses.
     */
    static Optional<String> releasesLinks(String baseUrl, SwiftIdentity id, List<String> availableDescending,
                                          List<String> repositoryUrls) {
        List<String> links = new ArrayList<>();
        if (!availableDescending.isEmpty()) {
            links.add(link(releaseUrl(baseUrl, id, availableDescending.getFirst()), "latest-version"));
        }
        boolean first = true;
        for (String url : repositoryUrls) {
            if (!linkSafe(url)) {
                continue;
            }
            links.add(link(url, first ? "canonical" : "alternate"));
            first = false;
        }
        return links.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", links));
    }

    /** {@code Link} of a release document: {@code latest-version}, {@code successor-version}, {@code predecessor-version}. */
    static Optional<String> releaseLinks(String baseUrl, SwiftIdentity id, List<String> availableDescending, String version) {
        List<String> links = new ArrayList<>();
        if (!availableDescending.isEmpty()) {
            links.add(link(releaseUrl(baseUrl, id, availableDescending.getFirst()), "latest-version"));
        }
        Optional<SwiftVersion> current = SwiftVersion.parse(version);
        if (current.isPresent()) {
            String successor = null;
            String predecessor = null;
            for (String v : availableDescending) {
                Optional<SwiftVersion> parsed = SwiftVersion.parse(v);
                if (parsed.isEmpty()) {
                    continue;
                }
                int c = parsed.get().compareTo(current.get());
                if (c > 0) {
                    successor = v; // descending order: the last one above is the closest
                } else if (c < 0 && predecessor == null) {
                    predecessor = v;
                }
            }
            if (successor != null) {
                links.add(link(releaseUrl(baseUrl, id, successor), "successor-version"));
            }
            if (predecessor != null) {
                links.add(link(releaseUrl(baseUrl, id, predecessor), "predecessor-version"));
            }
        }
        return links.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", links));
    }

    private static String link(String url, String rel) {
        return "<" + url + ">; rel=\"" + rel + "\"";
    }

    /** SwiftPM splits {@code Link} at commas and semicolons, so URLs containing them (or angle brackets) are left out. */
    static boolean linkSafe(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == ',' || c == ';' || c == '<' || c == '>' || c == '"' || Character.isWhitespace(c) || c < 0x20) {
                return false;
            }
        }
        return true;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isString() ? v.stringValue() : null;
    }
}
