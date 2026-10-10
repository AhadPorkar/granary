package dev.granary.format.pypi;

import dev.granary.core.proxy.UrlPaths;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON API of pypi.org ({@code pypi/<name>/json} and {@code pypi/<name>/<version>/json}): built from
 * the stored metadata of hosted projects, passed through with rewritten download links for proxies, and
 * merged release by release for groups.
 */
public final class PypiJsonApi {

    private static final DateTimeFormatter UPLOAD_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter UPLOAD_TIME_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper json;

    public PypiJsonApi(ObjectMapper json) {
        this.json = json;
    }

    // --- hosted ----------------------------------------------------------------------------------

    /** The project document of a hosted project; {@code info} describes the latest release. */
    public ObjectNode project(String normalisedName, List<PypiHosted.HostedFile> files, String baseUrl) {
        Map<String, List<PypiHosted.HostedFile>> byVersion = byVersion(files);
        String latest = PypiVersion.latest(byVersion.keySet()).orElseThrow();
        ObjectNode doc = json.createObjectNode();
        doc.set("info", info(normalisedName, latest, byVersion.get(latest), baseUrl));
        doc.put("last_serial", 0);
        ObjectNode releases = doc.putObject("releases");
        for (Map.Entry<String, List<PypiHosted.HostedFile>> e : byVersion.entrySet()) {
            ArrayNode array = releases.putArray(e.getKey());
            e.getValue().forEach(f -> array.add(fileNode(f)));
        }
        ArrayNode urls = doc.putArray("urls");
        byVersion.get(latest).forEach(f -> urls.add(fileNode(f)));
        doc.putArray("vulnerabilities");
        return doc;
    }

    /** The release document of one version of a hosted project, or empty when the version has no files. */
    public Optional<ObjectNode> release(String normalisedName, List<PypiHosted.HostedFile> files, String version,
                                        String baseUrl) {
        Map<String, List<PypiHosted.HostedFile>> byVersion = byVersion(files);
        List<PypiHosted.HostedFile> ofVersion = byVersion.get(version);
        if (ofVersion == null) {
            // the client may spell the version differently from the file name (1.0 vs 1.0.0)
            Optional<PypiVersion> wanted = PypiVersion.tryParse(version);
            if (wanted.isPresent()) {
                for (Map.Entry<String, List<PypiHosted.HostedFile>> e : byVersion.entrySet()) {
                    if (PypiVersion.tryParse(e.getKey()).filter(v -> v.compareTo(wanted.get()) == 0).isPresent()) {
                        ofVersion = e.getValue();
                        version = e.getKey();
                        break;
                    }
                }
            }
        }
        if (ofVersion == null) {
            return Optional.empty();
        }
        ObjectNode doc = json.createObjectNode();
        doc.set("info", info(normalisedName, version, ofVersion, baseUrl));
        doc.put("last_serial", 0);
        ArrayNode urls = doc.putArray("urls");
        ofVersion.forEach(f -> urls.add(fileNode(f)));
        doc.putArray("vulnerabilities");
        return Optional.of(doc);
    }

    private static Map<String, List<PypiHosted.HostedFile>> byVersion(List<PypiHosted.HostedFile> files) {
        Map<String, List<PypiHosted.HostedFile>> byVersion = new LinkedHashMap<>();
        for (PypiHosted.HostedFile f : files) {
            byVersion.computeIfAbsent(f.distribution().version(), k -> new ArrayList<>()).add(f);
        }
        return byVersion;
    }

    private ObjectNode info(String name, String version, List<PypiHosted.HostedFile> files, String baseUrl) {
        PypiMetadata metadata = files.stream().map(PypiHosted.HostedFile::metadata).filter(m -> m != null).findFirst().orElse(null);
        ObjectNode info = json.createObjectNode();
        info.put("name", metadata == null ? name : metadata.name().orElse(name));
        info.put("version", version);
        info.put("summary", metadata == null ? null : metadata.first("Summary").orElse(null));
        info.put("description", metadata == null ? "" : metadata.description());
        info.put("description_content_type", metadata == null ? null : metadata.first("Description-Content-Type").orElse(null));
        info.put("author", metadata == null ? null : metadata.first("Author").orElse(null));
        info.put("author_email", metadata == null ? null : metadata.first("Author-email").orElse(null));
        info.put("maintainer", metadata == null ? null : metadata.first("Maintainer").orElse(null));
        info.put("maintainer_email", metadata == null ? null : metadata.first("Maintainer-email").orElse(null));
        info.put("license", metadata == null ? null : metadata.first("License").orElse(null));
        info.put("license_expression", metadata == null ? null : metadata.first("License-Expression").orElse(null));
        info.put("keywords", metadata == null ? null : metadata.first("Keywords").orElse(null));
        info.put("home_page", metadata == null ? null : metadata.first("Home-page").orElse(null));
        info.put("download_url", metadata == null ? null : metadata.first("Download-URL").orElse(null));
        info.put("platform", metadata == null ? null : metadata.first("Platform").orElse(null));
        info.put("requires_python", metadata == null ? null : metadata.requiresPython().orElse(null));
        ArrayNode classifiers = info.putArray("classifiers");
        ArrayNode requiresDist = info.putArray("requires_dist");
        ArrayNode providesExtra = info.putArray("provides_extra");
        ObjectNode projectUrls = info.putObject("project_urls");
        if (metadata != null) {
            metadata.all("Classifier").forEach(classifiers::add);
            metadata.all("Requires-Dist").forEach(requiresDist::add);
            metadata.all("Provides-Extra").forEach(providesExtra::add);
            for (String entry : metadata.all("Project-URL")) {
                int comma = entry.indexOf(',');
                if (comma > 0) {
                    projectUrls.put(entry.substring(0, comma).trim(), entry.substring(comma + 1).trim());
                }
            }
            metadata.first("Home-page").ifPresent(url -> projectUrls.putIfAbsent("Homepage", json.getNodeFactory().stringNode(url)));
        }
        String projectUrl = UrlPaths.join(baseUrl, PypiPath.SIMPLE + name + "/");
        info.put("package_url", projectUrl);
        info.put("project_url", projectUrl);
        info.put("release_url", UrlPaths.join(baseUrl, PypiPath.PYPI + name + "/" + version + "/json"));
        info.putNull("docs_url");
        info.putNull("bugtrack_url");
        info.put("yanked", false);
        info.putNull("yanked_reason");
        ObjectNode downloads = info.putObject("downloads");
        downloads.put("last_day", -1);
        downloads.put("last_month", -1);
        downloads.put("last_week", -1);
        return info;
    }

    private ObjectNode fileNode(PypiHosted.HostedFile f) {
        ObjectNode node = json.createObjectNode();
        node.put("comment_text", "");
        ObjectNode digests = node.putObject("digests");
        f.file().sha256().ifPresent(sha -> digests.put("sha256", sha));
        node.put("downloads", -1);
        node.put("filename", f.file().filename());
        node.put("has_sig", false);
        node.put("packagetype", f.distribution().type().uploadName());
        node.put("python_version", f.distribution().pythonTag());
        node.put("requires_python", f.file().requiresPython());
        node.put("size", f.file().size() == null ? 0 : f.file().size());
        node.put("upload_time", UPLOAD_TIME.format(f.uploaded()));
        node.put("upload_time_iso_8601", UPLOAD_TIME_ISO.format(f.uploaded()));
        node.put("url", f.file().url());
        node.put("yanked", false);
        node.putNull("yanked_reason");
        return node;
    }

    // --- proxy -----------------------------------------------------------------------------------

    /**
     * A copy of an upstream document whose file URLs point at {@code baseUrl}, mapped the same way as the links of
     * the simple index (see {@link PypiProxy#localPath(String, String, String, String)}).
     */
    public ObjectNode rewrite(JsonNode upstreamDoc, String normalisedName, String baseUrl, String filesUrl) {
        ObjectNode doc = upstreamDoc.isObject() ? (ObjectNode) upstreamDoc.deepCopy() : json.createObjectNode();
        rewriteFiles(doc.get("urls"), normalisedName, baseUrl, filesUrl);
        JsonNode releases = doc.get("releases");
        if (releases != null && releases.isObject()) {
            for (Map.Entry<String, JsonNode> e : releases.properties()) {
                rewriteFiles(e.getValue(), normalisedName, baseUrl, filesUrl);
            }
        }
        return doc;
    }

    private static void rewriteFiles(JsonNode files, String normalisedName, String baseUrl, String filesUrl) {
        if (files == null || !files.isArray()) {
            return;
        }
        for (JsonNode file : files) {
            if (file.isObject() && file.path("url").isString()) {
                String url = file.path("url").stringValue();
                String filename = file.path("filename").isString() ? file.path("filename").stringValue() : null;
                ((ObjectNode) file).put("url", UrlPaths.join(baseUrl, PypiProxy.localPath(url, filename, normalisedName, filesUrl)));
            }
        }
    }

    // --- group -----------------------------------------------------------------------------------

    /**
     * Merges the project documents of a group's members: releases are unioned (for one version the earlier
     * member's files win by file name), {@code info} comes from the member that owns the highest release.
     */
    public ObjectNode merge(List<ObjectNode> docs) {
        Map<String, List<JsonNode>> releases = new LinkedHashMap<>();
        Map<String, ObjectNode> infoByLatest = new LinkedHashMap<>();
        for (ObjectNode doc : docs) {
            JsonNode docReleases = doc.get("releases");
            if (docReleases != null && docReleases.isObject()) {
                for (Map.Entry<String, JsonNode> e : docReleases.properties()) {
                    merge(releases.computeIfAbsent(e.getKey(), k -> new ArrayList<>()), e.getValue());
                }
            }
            JsonNode info = doc.get("info");
            if (info != null && info.isObject() && info.path("version").isString()) {
                String version = info.path("version").stringValue();
                if (docReleases == null || !docReleases.has(version)) {
                    merge(releases.computeIfAbsent(version, k -> new ArrayList<>()), doc.get("urls"));
                }
                infoByLatest.putIfAbsent(version, (ObjectNode) info);
            }
        }
        if (releases.isEmpty() && docs.size() == 1) {
            return docs.getFirst();
        }
        String latest = PypiVersion.latest(releases.keySet()).orElse(null);
        ObjectNode result = json.createObjectNode();
        ObjectNode info;
        if (latest != null && infoByLatest.containsKey(latest)) {
            info = infoByLatest.get(latest).deepCopy();
        } else if (!docs.isEmpty() && docs.getFirst().get("info") != null && docs.getFirst().get("info").isObject()) {
            info = (ObjectNode) docs.getFirst().get("info").deepCopy();
        } else {
            info = json.createObjectNode();
        }
        if (latest != null) {
            info.put("version", latest);
        }
        result.set("info", info);
        result.put("last_serial", 0);
        ObjectNode merged = result.putObject("releases");
        for (String version : PypiVersion.sort(releases.keySet())) {
            ArrayNode array = merged.putArray(version);
            releases.get(version).forEach(array::add);
        }
        ArrayNode urls = result.putArray("urls");
        if (latest != null) {
            releases.get(latest).forEach(urls::add);
        }
        result.putArray("vulnerabilities");
        return result;
    }

    private static void merge(List<JsonNode> into, JsonNode files) {
        if (files == null || !files.isArray()) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode existing : into) {
            names.add(existing.path("filename").asString());
        }
        for (JsonNode file : files) {
            if (file.isObject() && names.add(file.path("filename").asString())) {
                into.add(file);
            }
        }
    }
}
