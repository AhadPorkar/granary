package dev.granary.format.helm;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

/**
 * Helm chart repositories, as the helm CLI (helm repo add/update, pull, install, dependency update), the
 * ChartMuseum API ({@code helm cm-push}, curl, CI scripts), Flux, Argo CD and Artifact Hub use them.
 *
 * <p>Hosted repositories accept chart uploads through the ChartMuseum API (and plain PUT) and generate
 * {@code index.yaml} from what is stored; proxies cache any chart repository (its index as metadata, archives
 * forever) and point every download at themselves; groups merge their members' indexes under one URL. OCI-based
 * charts ({@code oci://}) are served by Docker repositories, not by this format.
 */
@org.springframework.stereotype.Component
public class HelmFormatHandler implements FormatHandler {

    public static final String FORMAT = "helm";

    private final StorageService storage;
    private final HelmHosted hosted;
    private final HelmIndexes indexes;
    private final GroupResolver groups;
    private final Path tempDir;

    public HelmFormatHandler(StorageService storage, HelmHosted hosted, HelmIndexes indexes, GroupResolver groups,
                             GranaryProperties properties) {
        this.storage = storage;
        this.hosted = hosted;
        this.indexes = indexes;
        this.groups = groups;
        this.tempDir = properties.getDataDir().resolve("tmp").resolve(FORMAT);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Helm charts";
    }

    /** Proxies are per upstream chart repository, so only the hosted repository and its group are standard. */
    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.hosted("helm-hosted", FORMAT, "Your own Helm charts"),
                RepositoryTemplate.group("helm-group", FORMAT, List.of("helm-hosted"),
                        "Single chart repository URL; add proxies of public chart repositories as members"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return HelmSettings.descriptors();
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return HelmSnippets.of(context);
    }

    @Override
    public void validate(Repository repository) {
        HelmSettings.validate(repository);
    }

    @Override
    public boolean isMetadataPath(String path) {
        return HelmPath.parse(path).isMetadata() || (path != null && path.startsWith(HelmHosted.ENTRIES));
    }

    /** The ChartMuseum health check is open to everyone; everything else follows the read/write split. */
    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        if (HelmPath.parse(request.path()).kind() == HelmPath.Kind.HEALTH && request.isGet()) {
            return AccessLevel.NONE;
        }
        return request.isGet() ? AccessLevel.READ : AccessLevel.WRITE;
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        HelmPath path = HelmPath.parse(request.path());
        try {
            return dispatch(request, path);
        } catch (HelmError e) {
            ResponseEntity<?> error = HelmResponses.error(e);
            // only hosted repositories take writes; on a proxy or group every path is read-only
            return e.status() == HttpStatus.METHOD_NOT_ALLOWED
                    ? ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders()).header(HttpHeaders.ALLOW, "GET, HEAD")
                            .body(error.getBody())
                    : error;
        } catch (ChartArchive.InvalidChartException e) {
            return HelmResponses.error(HttpStatus.BAD_REQUEST, "invalid chart: " + e.getMessage());
        }
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, HelmPath path) throws IOException {
        String method = request.method();
        return switch (path.kind()) {
            case INVALID -> HelmResponses.error(HttpStatus.BAD_REQUEST, "invalid path: " + path.path());
            case UNKNOWN -> HelmResponses.error(HttpStatus.NOT_FOUND, "not found: " + path.path());
            case ROOT -> request.isGet() ? landingPage(request) : methodNotAllowed(method, path);
            case HEALTH -> request.isGet() ? HelmResponses.json(request, HttpStatus.OK, Map.of("healthy", true))
                    : methodNotAllowed(method, path);
            case INDEX -> request.isGet() ? index(request) : methodNotAllowed(method, path);
            case CHART, PROVENANCE, ROOT_FILE -> switch (method) {
                case "GET", "HEAD" -> download(request, chartsPath(path), path.filename());
                case "PUT" -> upload(request, path.isProvenancePath() ? HelmUpload.Body.PROV : HelmUpload.Body.CHART);
                case "DELETE" -> deleteFile(request, path);
                default -> methodNotAllowed(method, path);
            };
            case CHART_ALIAS, PROVENANCE_ALIAS -> request.isGet() ? aliasDownload(request, path) : methodNotAllowed(method, path);
            case ARTIFACTHUB -> switch (method) {
                case "GET", "HEAD" -> artifactHub(request);
                case "PUT" -> putArtifactHub(request);
                case "DELETE" -> deleteArtifactHub(request);
                default -> methodNotAllowed(method, path);
            };
            case API_CHARTS -> switch (method) {
                case "GET", "HEAD" -> apiCharts(request);
                case "POST" -> upload(request, HelmUpload.Body.CHART);
                default -> methodNotAllowed(method, path);
            };
            case API_CHART -> request.isGet() ? apiChart(request, path) : methodNotAllowed(method, path);
            case API_CHART_VERSION -> switch (method) {
                case "GET", "HEAD" -> apiChartVersion(request, path);
                case "DELETE" -> deleteVersion(request, path);
                default -> methodNotAllowed(method, path);
            };
            case API_PROV -> "POST".equals(method) ? upload(request, HelmUpload.Body.PROV) : methodNotAllowed(method, path);
        };
    }

    // --- reads -----------------------------------------------------------------------------------

    private ResponseEntity<?> landingPage(RepositoryRequest request) {
        Repository repo = request.repository();
        String name = HtmlUtils.htmlEscape(repo.getName());
        String url = HtmlUtils.htmlEscape(request.baseUrl());
        String html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>" + name + "</title>\n</head>\n<body>\n"
                + "<h1>" + name + "</h1>\n"
                + "<p>Helm chart repository (" + repo.getType().name().toLowerCase(Locale.ROOT) + "). "
                + "Index: <a href=\"index.yaml\">index.yaml</a></p>\n"
                + "<pre>helm repo add " + HelmSnippets.REPO_NAME + " " + url + "</pre>\n"
                + (repo.isHosted() ? "<p>Upload charts with <code>curl --data-binary @chart-1.0.0.tgz " + url
                        + "/api/charts</code> or <code>helm cm-push</code>.</p>\n" : "")
                + "</body>\n</html>\n";
        return HelmResponses.document(request, html.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Integer.toHexString(html.hashCode()), "text/html;charset=UTF-8", "index.html");
    }

    private ResponseEntity<?> index(RepositoryRequest request) {
        IndexSnapshot snapshot = requireSnapshot(request.repository());
        HelmIndexes.Rendered rendered = indexes.render(request.repository(), snapshot, request.baseUrl());
        return HelmResponses.document(request, rendered.bytes(), rendered.etag(), HelmIndex.CONTENT_TYPE, HelmPath.INDEX_FILE);
    }

    private IndexSnapshot requireSnapshot(Repository repo) {
        return indexes.snapshot(repo).orElseThrow(() ->
                HelmError.notFound("the index of '" + repo.getName() + "' is not available"));
    }

    /** {@code charts/<file>}; a root-level {@code <file>.tgz} (the Nexus layout) is the same file. */
    private static String chartsPath(HelmPath path) {
        return path.kind() == HelmPath.Kind.ROOT_FILE ? HelmPath.CHARTS + path.filename() : path.path();
    }

    private ResponseEntity<?> download(RepositoryRequest request, String path, String filename) {
        Optional<StoredContent> content = indexes.file(request.repository(), path);
        if (content.isEmpty()) {
            throw HelmError.notFound((HelmPath.isProvenance(filename) ? "provenance file" : "chart") + " not found: " + filename);
        }
        return send(request, content.get(), filename);
    }

    private ResponseEntity<?> aliasDownload(RepositoryRequest request, HelmPath path) {
        boolean provenance = path.kind() == HelmPath.Kind.PROVENANCE_ALIAS;
        Optional<StoredContent> content = indexes.file(request.repository(), path.name(), path.version(), provenance);
        if (content.isEmpty()) {
            throw HelmError.notFound((provenance ? "provenance file" : "chart") + " not found: " + path.name() + " " + path.version());
        }
        return send(request, content.get(), path.filename());
    }

    private ResponseEntity<?> send(RepositoryRequest request, StoredContent content, String filename) {
        String contentType = HelmPath.isProvenance(filename) ? Provenance.CONTENT_TYPE : HelmHosted.ARCHIVE_CONTENT_TYPE;
        ResponseEntity<?> response = HelmResponses.download(request, content, contentType, filename);
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.asset());
        }
        return response;
    }

    private ResponseEntity<?> artifactHub(RepositoryRequest request) {
        Optional<StoredContent> content = indexes.artifactHubMetadata(request.repository());
        if (content.isEmpty()) {
            throw HelmError.notFound("not found: " + HelmPath.ARTIFACTHUB_FILE);
        }
        return HelmResponses.download(request, content.get(), HelmHosted.ARTIFACTHUB_CONTENT_TYPE, HelmPath.ARTIFACTHUB_FILE);
    }

    // --- ChartMuseum API reads -------------------------------------------------------------------

    /** The index entries with the download URLs a client of this repository uses. */
    private HelmIndex clientIndex(RepositoryRequest request) {
        IndexSnapshot snapshot = requireSnapshot(request.repository());
        return HelmIndexes.withUrls(snapshot.index(), request.baseUrl(), HelmSettings.relativeUrls(request.repository()));
    }

    private ResponseEntity<?> apiCharts(RepositoryRequest request) {
        return HelmResponses.json(request, HttpStatus.OK, clientIndex(request).toMap());
    }

    private ResponseEntity<?> apiChart(RepositoryRequest request, HelmPath path) {
        List<ChartEntry> versions = clientIndex(request).versions(path.name());
        if (versions.isEmpty()) {
            throw HelmError.notFound("chart not found: " + path.name());
        }
        return HelmResponses.json(request, HttpStatus.OK, versions.stream().map(e -> (Object) e.sortedCopy()).toList());
    }

    private ResponseEntity<?> apiChartVersion(RepositoryRequest request, HelmPath path) {
        HelmIndex index = clientIndex(request);
        Optional<ChartEntry> entry = "latest".equals(path.version())
                ? index.latest(path.name())
                : index.find(path.name(), path.version());
        if (entry.isEmpty()) {
            throw HelmError.notFound("chart version not found: " + path.name() + " " + path.version());
        }
        return HelmResponses.json(request, HttpStatus.OK, entry.get().sortedCopy());
    }

    // --- writes ----------------------------------------------------------------------------------

    private ResponseEntity<?> upload(RepositoryRequest request, HelmUpload.Body rawBody) throws IOException {
        Repository repo = request.repository();
        requireHosted(repo, "upload to");
        try (HelmUpload upload = HelmUpload.read(request, tempDir, rawBody, HelmSettings.maxChartBytes(repo))) {
            if (rawBody == HelmUpload.Body.PROV && upload.prov() == null) {
                throw HelmError.badRequest("the request contains no provenance file (form field 'prov')");
            }
            if (upload.chart() != null && rawBody == HelmUpload.Body.CHART) {
                hosted.storeChart(repo, upload.chart(), upload.prov());
            } else {
                hosted.storeProvenance(repo, upload.prov());
            }
        }
        return HelmResponses.json(null, HttpStatus.CREATED, Map.of("saved", true));
    }

    private ResponseEntity<?> deleteVersion(RepositoryRequest request, HelmPath path) {
        Repository repo = request.repository();
        requireHosted(repo, "delete from");
        if (!hosted.deleteVersion(repo, path.name(), path.version())) {
            throw HelmError.notFound("chart version not found: " + path.name() + " " + path.version());
        }
        return HelmResponses.json(null, HttpStatus.OK, Map.of("deleted", true));
    }

    /** {@code DELETE charts/<name>-<version>.tgz} removes the version; {@code DELETE ….tgz.prov} only the provenance file. */
    private ResponseEntity<?> deleteFile(RepositoryRequest request, HelmPath path) {
        Repository repo = request.repository();
        requireHosted(repo, "delete from");
        String file = chartsPath(path);
        boolean deleted;
        if (path.isProvenancePath()) {
            deleted = storage.delete(repo, file);
        } else {
            Optional<String[]> version = hosted.versionOfArchive(repo, path.filename());
            deleted = version.isPresent() ? hosted.deleteVersion(repo, version.get()[0], version.get()[1]) : storage.delete(repo, file);
        }
        if (!deleted) {
            throw HelmError.notFound("not found: " + path.filename());
        }
        return HelmResponses.json(null, HttpStatus.OK, Map.of("deleted", true));
    }

    private ResponseEntity<?> putArtifactHub(RepositoryRequest request) throws IOException {
        Repository repo = request.repository();
        requireHosted(repo, "upload to");
        try (HelmUpload upload = HelmUpload.read(request, tempDir, HelmUpload.Body.CHART, 1024 * 1024)) {
            Path file = upload.chart() != null ? upload.chart() : upload.prov();
            hosted.storeArtifactHubMetadata(repo, file);
        }
        return HelmResponses.json(null, HttpStatus.CREATED, Map.of("saved", true));
    }

    private ResponseEntity<?> deleteArtifactHub(RepositoryRequest request) {
        Repository repo = request.repository();
        requireHosted(repo, "delete from");
        if (!storage.delete(repo, HelmPath.ARTIFACTHUB_FILE)) {
            throw HelmError.notFound("not found: " + HelmPath.ARTIFACTHUB_FILE);
        }
        return HelmResponses.json(null, HttpStatus.OK, Map.of("deleted", true));
    }

    /** Uploads and deletes only make sense on hosted repositories; a group names the member to use instead. */
    private void requireHosted(Repository repo, String verb) {
        if (repo.isHosted()) {
            return;
        }
        String hint = "";
        if (repo.isGroup()) {
            hint = groups.leafMembers(repo).stream().filter(Repository::isHosted).findFirst()
                    .map(member -> "; use its hosted member '" + member.getName() + "'")
                    .orElse("; it has no hosted member");
        }
        throw HelmError.methodNotAllowed("'" + repo.getName() + "' is a " + repo.getType().name().toLowerCase(Locale.ROOT)
                + " repository, you cannot " + verb + " it" + hint);
    }

    private static ResponseEntity<?> methodNotAllowed(String method, HelmPath path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", method + " is not supported for " + (path.path().isEmpty() ? "/" : path.path()));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, allowedMethods(path.kind()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
    }

    /** The {@code Allow} header of a 405: the methods the path supports (writes only on hosted repositories). */
    static String allowedMethods(HelmPath.Kind kind) {
        return switch (kind) {
            case CHART, PROVENANCE, ROOT_FILE, ARTIFACTHUB -> "GET, HEAD, PUT, DELETE";
            case API_CHARTS -> "GET, HEAD, POST";
            case API_CHART_VERSION -> "GET, HEAD, DELETE";
            case API_PROV -> "POST";
            default -> "GET, HEAD";
        };
    }

    /** For tests: where uploads are spooled. */
    Path tempDir() {
        return tempDir;
    }
}
