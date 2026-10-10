package dev.granary.format.pypi;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the two representations of the simple API: PEP 503 HTML and PEP 691 JSON, both for
 * the project list ({@code simple/}) and for a project's file list ({@code simple/<name>/}). Whatever came
 * in, the model is the same, so a page fetched as HTML from an old index can be answered as JSON to uv.
 */
@org.springframework.stereotype.Component
public final class SimpleIndex {

    public static final String REPOSITORY_VERSION_META = "pypi:repository-version";

    private static final Logger log = LoggerFactory.getLogger(SimpleIndex.class);
    private static final Set<String> FILE_MEMBERS = Set.of("filename", "url", "hashes", "requires-python", "yanked",
            "core-metadata", "dist-info-metadata", "gpg-sig", "size", "upload-time");
    private static final Set<String> PROJECT_MEMBERS = Set.of("meta", "name", "files", "versions");

    private final ObjectMapper json;

    public SimpleIndex(ObjectMapper json) {
        this.json = json;
    }

    // --- parsing -----------------------------------------------------------------------------

    /** Whether the body is the JSON representation; decided by content type, then by the first character. */
    public static boolean looksLikeJson(byte[] body, String contentType) {
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.contains("json")) {
                return true;
            }
            if (ct.contains("html")) {
                return false;
            }
        }
        for (byte b : body) {
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '{';
        }
        return false;
    }

    /**
     * Parses a project page. {@code pageUrl} resolves relative links; {@code null} keeps them as they are.
     * Returns empty when the body is neither representation.
     */
    public Optional<PypiProject> parseProject(byte[] body, String contentType, String name, URI pageUrl) {
        return looksLikeJson(body, contentType) ? parseProjectJson(body, name, pageUrl)
                : Optional.of(parseProjectHtml(new String(body, StandardCharsets.UTF_8), name, pageUrl));
    }

    public PypiProject parseProjectHtml(String html, String name, URI pageUrl) {
        List<PypiFile> files = new ArrayList<>();
        for (SimpleHtml.Tag a : SimpleHtml.anchors(html)) {
            String href = a.attribute("href");
            if (href == null || href.isBlank()) {
                continue;
            }
            Map<String, String> hashes = new LinkedHashMap<>();
            String url = href.trim();
            int hash = url.indexOf('#');
            if (hash >= 0) {
                String fragment = url.substring(hash + 1);
                url = url.substring(0, hash);
                int eq = fragment.indexOf('=');
                if (eq > 0) {
                    hashes.put(fragment.substring(0, eq).toLowerCase(Locale.ROOT), fragment.substring(eq + 1));
                }
            }
            url = resolve(url, pageUrl);
            String filename = a.text().isEmpty() ? lastSegment(url) : a.text();
            if (filename.isEmpty()) {
                continue;
            }
            String yanked = null;
            Map<String, String> coreMetadata = null;
            Boolean gpgSig = null;
            Map<String, String> attributes = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : a.attributes().entrySet()) {
                switch (e.getKey()) {
                    case "href", "data-requires-python" -> {
                        // handled separately
                    }
                    case "data-yanked" -> yanked = e.getValue();
                    case "data-core-metadata", "data-dist-info-metadata" -> {
                        Map<String, String> parsed = parseMetadataAttribute(e.getValue());
                        if (coreMetadata == null || (coreMetadata.isEmpty() && !parsed.isEmpty())) {
                            coreMetadata = parsed;
                        }
                    }
                    case "data-gpg-sig" -> gpgSig = "true".equalsIgnoreCase(e.getValue());
                    default -> {
                        if (e.getKey().startsWith("data-")) {
                            attributes.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }
            files.add(new PypiFile(filename, url, hashes, a.attribute("data-requires-python"), yanked, coreMetadata,
                    gpgSig, null, null, Map.of(), attributes));
        }
        String apiVersion = SimpleHtml.meta(html, REPOSITORY_VERSION_META).orElse("1.0");
        return new PypiProject(name, apiVersion, files, null, Map.of());
    }

    public Optional<PypiProject> parseProjectJson(byte[] body, String name, URI pageUrl) {
        Optional<JsonNode> root = readObject(body);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        JsonNode doc = root.get();
        List<PypiFile> files = new ArrayList<>();
        for (JsonNode f : doc.path("files")) {
            if (!f.isObject()) {
                continue;
            }
            String filename = text(f, "filename");
            String url = text(f, "url");
            if (filename == null || url == null) {
                continue;
            }
            Map<String, String> hashes = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> h : f.path("hashes").properties()) {
                if (h.getValue().isString()) {
                    hashes.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue().stringValue());
                }
            }
            String yanked = null;
            JsonNode y = f.get("yanked");
            if (y != null) {
                if (y.isBoolean() && y.booleanValue()) {
                    yanked = "";
                } else if (y.isString()) {
                    yanked = y.stringValue();
                }
            }
            Map<String, String> coreMetadata = parseMetadataNode(f.get("core-metadata"));
            if (coreMetadata == null || coreMetadata.isEmpty()) {
                Map<String, String> legacy = parseMetadataNode(f.get("dist-info-metadata"));
                if (legacy != null && (coreMetadata == null || !legacy.isEmpty())) {
                    coreMetadata = legacy;
                }
            }
            JsonNode gpg = f.get("gpg-sig");
            Boolean gpgSig = gpg != null && gpg.isBoolean() ? gpg.booleanValue() : null;
            JsonNode size = f.get("size");
            Long sizeValue = size != null && size.isNumber() ? size.longValue() : null;
            Map<String, JsonNode> extra = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : f.properties()) {
                if (!FILE_MEMBERS.contains(e.getKey())) {
                    extra.put(e.getKey(), e.getValue());
                }
            }
            files.add(new PypiFile(filename, resolve(url, pageUrl), hashes, text(f, "requires-python"), yanked, coreMetadata,
                    gpgSig, sizeValue, text(f, "upload-time"), extra, Map.of()));
        }
        List<String> versions = null;
        if (doc.path("versions").isArray()) {
            versions = new ArrayList<>();
            for (JsonNode v : doc.path("versions")) {
                if (v.isString()) {
                    versions.add(v.stringValue());
                }
            }
        }
        Map<String, JsonNode> extra = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : doc.properties()) {
            if (!PROJECT_MEMBERS.contains(e.getKey())) {
                extra.put(e.getKey(), e.getValue());
            }
        }
        String pageName = text(doc, "name");
        return Optional.of(new PypiProject(pageName != null ? pageName : name, text(doc.path("meta"), "api-version"),
                files, versions, extra));
    }

    /** Parses a project list; names are returned as the page spells them, duplicates (after normalisation) dropped. */
    public Optional<List<String>> parseProjectList(byte[] body, String contentType) {
        if (looksLikeJson(body, contentType)) {
            Optional<JsonNode> root = readObject(body);
            if (root.isEmpty()) {
                return Optional.empty();
            }
            Set<String> seen = new LinkedHashSet<>();
            List<String> names = new ArrayList<>();
            for (JsonNode p : root.get().path("projects")) {
                String name = p.isObject() ? text(p, "name") : (p.isString() ? p.stringValue() : null);
                if (name != null && !name.isBlank() && seen.add(PypiNames.normalise(name))) {
                    names.add(name.trim());
                }
            }
            return Optional.of(names);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (SimpleHtml.Tag a : SimpleHtml.anchors(new String(body, StandardCharsets.UTF_8))) {
            String name = a.text();
            if (name.isEmpty()) {
                String href = a.attribute("href");
                name = href == null ? "" : lastSegment(href);
            }
            if (!name.isEmpty() && seen.add(PypiNames.normalise(name))) {
                names.add(name);
            }
        }
        return Optional.of(names);
    }

    private Optional<JsonNode> readObject(byte[] body) {
        try {
            JsonNode node = json.readTree(body);
            if (node != null && node.isObject()) {
                return Optional.of(node);
            }
            log.warn("simple API document is not a JSON object");
            return Optional.empty();
        } catch (JacksonException e) {
            log.warn("simple API document is not valid JSON: {}", e.getOriginalMessage());
            return Optional.empty();
        }
    }

    private static Map<String, String> parseMetadataAttribute(String value) {
        Map<String, String> digests = new LinkedHashMap<>();
        if (value == null) {
            return digests;
        }
        int eq = value.indexOf('=');
        if (eq > 0) {
            digests.put(value.substring(0, eq).trim().toLowerCase(Locale.ROOT), value.substring(eq + 1).trim());
        }
        return digests;
    }

    private static Map<String, String> parseMetadataNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? Map.of() : null;
        }
        if (node.isObject()) {
            Map<String, String> digests = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (e.getValue().isString()) {
                    digests.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().stringValue());
                }
            }
            return digests;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static String resolve(String url, URI pageUrl) {
        if (pageUrl == null || url.isEmpty()) {
            return url;
        }
        try {
            return pageUrl.resolve(new URI(url)).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return url;
        }
    }

    private static String lastSegment(String url) {
        String u = url;
        int hash = u.indexOf('#');
        if (hash >= 0) {
            u = u.substring(0, hash);
        }
        int query = u.indexOf('?');
        if (query >= 0) {
            u = u.substring(0, query);
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u.substring(u.lastIndexOf('/') + 1);
    }

    // --- rendering ---------------------------------------------------------------------------

    public String renderProjectHtml(PypiProject project) {
        StringBuilder sb = new StringBuilder(512 + project.files().size() * 256);
        String title = "Links for " + SimpleHtml.escape(project.name());
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta name=\"").append(REPOSITORY_VERSION_META)
                .append("\" content=\"").append(SimpleHtml.escape(project.apiVersion())).append("\">\n")
                .append("<meta charset=\"utf-8\">\n<title>").append(title).append("</title>\n</head>\n<body>\n<h1>")
                .append(title).append("</h1>\n");
        for (PypiFile f : project.files()) {
            sb.append("<a href=\"").append(SimpleHtml.escape(f.url()));
            f.fragment().ifPresent(fragment -> sb.append('#').append(SimpleHtml.escape(fragment)));
            sb.append('"');
            if (f.requiresPython() != null) {
                attribute(sb, "data-requires-python", f.requiresPython());
            }
            if (f.isYanked()) {
                attribute(sb, "data-yanked", f.yanked());
            }
            if (f.coreMetadata() != null) {
                String value = metadataAttribute(f.coreMetadata());
                attribute(sb, "data-core-metadata", value);
                attribute(sb, "data-dist-info-metadata", value);
            }
            if (f.gpgSig() != null) {
                attribute(sb, "data-gpg-sig", f.gpgSig().toString());
            }
            for (Map.Entry<String, String> e : f.attributes().entrySet()) {
                attribute(sb, e.getKey(), e.getValue());
            }
            sb.append('>').append(SimpleHtml.escape(f.filename())).append("</a><br />\n");
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    public byte[] renderProjectJson(PypiProject project) {
        ObjectNode root = json.createObjectNode();
        root.putObject("meta").put("api-version", project.apiVersion());
        root.put("name", project.name());
        ArrayNode files = root.putArray("files");
        for (PypiFile f : project.files()) {
            ObjectNode node = files.addObject();
            node.put("filename", f.filename());
            node.put("url", f.url());
            ObjectNode hashes = node.putObject("hashes");
            f.hashes().forEach(hashes::put);
            if (f.requiresPython() != null) {
                node.put("requires-python", f.requiresPython());
            }
            if (f.yanked() == null) {
                node.put("yanked", false);
            } else if (f.yanked().isEmpty()) {
                node.put("yanked", true);
            } else {
                node.put("yanked", f.yanked());
            }
            if (f.coreMetadata() != null) {
                if (f.coreMetadata().isEmpty()) {
                    node.put("core-metadata", true);
                    node.put("dist-info-metadata", true);
                } else {
                    ObjectNode digests = node.putObject("core-metadata");
                    f.coreMetadata().forEach(digests::put);
                    node.set("dist-info-metadata", digests.deepCopy());
                }
            }
            if (f.gpgSig() != null) {
                node.put("gpg-sig", f.gpgSig());
            }
            if (f.size() != null) {
                node.put("size", f.size());
            }
            if (f.uploadTime() != null) {
                node.put("upload-time", f.uploadTime());
            }
            f.extra().forEach(node::set);
        }
        ArrayNode versions = root.putArray("versions");
        project.effectiveVersions().forEach(versions::add);
        project.extra().forEach(root::set);
        return json.writeValueAsBytes(root);
    }

    public String renderProjectListHtml(List<String> names) {
        StringBuilder sb = new StringBuilder(256 + names.size() * 48);
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta name=\"").append(REPOSITORY_VERSION_META)
                .append("\" content=\"").append(PypiProject.API_VERSION).append("\">\n")
                .append("<meta charset=\"utf-8\">\n<title>Simple index</title>\n</head>\n<body>\n");
        for (String name : names) {
            sb.append("<a href=\"").append(SimpleHtml.escape(PypiNames.normalise(name))).append("/\">")
                    .append(SimpleHtml.escape(name)).append("</a><br />\n");
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    public byte[] renderProjectListJson(List<String> names) {
        ObjectNode root = json.createObjectNode();
        root.putObject("meta").put("api-version", PypiProject.API_VERSION);
        ArrayNode projects = root.putArray("projects");
        for (String name : names) {
            projects.addObject().put("name", name);
        }
        return json.writeValueAsBytes(root);
    }

    /** Serialises a JSON API document. */
    public byte[] toBytes(JsonNode node) {
        return json.writeValueAsBytes(node);
    }

    private static void attribute(StringBuilder sb, String name, String value) {
        sb.append(' ').append(name).append("=\"").append(SimpleHtml.escape(value)).append('"');
    }

    private static String metadataAttribute(Map<String, String> digests) {
        if (digests.isEmpty()) {
            return "true";
        }
        if (digests.containsKey(PypiFile.SHA256)) {
            return PypiFile.SHA256 + "=" + digests.get(PypiFile.SHA256);
        }
        Map.Entry<String, String> first = digests.entrySet().iterator().next();
        return first.getKey() + "=" + first.getValue();
    }
}
