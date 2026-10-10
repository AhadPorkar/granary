package dev.granary.format.pypi;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The PyPI protocol as pip, uv, poetry, pipenv, pdm, twine, hatch and flit speak it: the simple API
 * (PEP 503 HTML and PEP 691 JSON, with PEP 658 metadata files, PEP 592 yanked flags and PEP 629 version
 * tags), the JSON API of pypi.org, and the legacy upload API.
 *
 * <p>Hosted repositories accept {@code twine upload} and friends and generate their index pages from the
 * stored files; proxies cache pypi.org (or any other index) and rewrite every download link to themselves
 * so that, once a file has been installed through them, it is available without internet; groups merge
 * their members under one URL.
 */
@org.springframework.stereotype.Component
public class PypiFormatHandler implements FormatHandler {

    public static final String FORMAT = "pypi";
    public static final String DEFAULT_REMOTE = "https://pypi.org/";

    private final StorageService storage;
    private final PypiHosted hosted;
    private final PypiProxy proxy;
    private final GroupResolver groups;
    private final SimpleIndex index;
    private final PypiJsonApi jsonApi;
    private final Path tempDir;

    public PypiFormatHandler(StorageService storage, PypiHosted hosted, PypiProxy proxy, GroupResolver groups,
                             SimpleIndex index, ObjectMapper json, GranaryProperties properties) {
        this.storage = storage;
        this.hosted = hosted;
        this.proxy = proxy;
        this.groups = groups;
        this.index = index;
        this.jsonApi = new PypiJsonApi(json);
        this.tempDir = properties.getDataDir().resolve("tmp").resolve(FORMAT);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "PyPI";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("pypi-proxy", FORMAT, DEFAULT_REMOTE, "Proxy of pypi.org"),
                RepositoryTemplate.hosted("pypi-hosted", FORMAT, "Your own Python packages"),
                RepositoryTemplate.group("pypi-group", FORMAT, List.of("pypi-hosted", "pypi-proxy"),
                        "Single index URL for pip, uv, poetry, pipenv and pdm"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return List.of(
                SettingDescriptor.url(PypiProxy.FILES_URL_SETTING, "Files host", PypiProxy.DEFAULT_FILES_URL,
                        "Base URL of the host the upstream index links files from; a cached file under packages/… is "
                                + "fetched from <files host>/packages/… when it is first requested")
                        .forTypes(RepositoryType.PROXY));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return PypiSnippets.of(context);
    }

    @Override
    public void validate(Repository repository) {
        Optional<String> filesUrl = repository.setting(PypiProxy.FILES_URL_SETTING);
        if (filesUrl.isEmpty()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(filesUrl.get());
        } catch (IllegalArgumentException e) {
            throw GranaryException.badRequest("repository.pypi.filesUrl.invalid", "pypi.filesUrl is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
            throw GranaryException.badRequest("repository.pypi.filesUrl.invalid", "pypi.filesUrl must be an absolute http(s) URL");
        }
    }

    /** Index pages and JSON API documents change with every upload; files under {@code packages/} never do. */
    @Override
    public boolean isMetadataPath(String path) {
        return PypiPath.parse(path).isMetadata();
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        PypiPath path = PypiPath.parse(request.path());
        try {
            return dispatch(request, path);
        } catch (PypiError e) {
            return isUpload(request, path) ? PypiResponses.text(e) : PypiResponses.json(e);
        }
    }

    private static boolean isUpload(RepositoryRequest request, PypiPath path) {
        return path.kind() == PypiPath.Kind.UPLOAD || (path.kind() == PypiPath.Kind.ROOT && "POST".equals(request.method()));
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, PypiPath path) throws IOException {
        String method = request.method();
        return switch (path.kind()) {
            case INVALID -> ContentResponses.error(HttpStatus.BAD_REQUEST, "path.invalid", "Illegal path");
            case ROOT -> switch (method) {
                case "GET", "HEAD" -> landingPage(request);
                case "POST" -> upload(request);
                default -> methodNotAllowed(method);
            };
            case UPLOAD -> "POST".equals(method) ? upload(request)
                    : PypiResponses.text(HttpStatus.METHOD_NOT_ALLOWED, "Upload files with a multipart POST to this URL");
            case SIMPLE_INDEX -> request.isGet() ? (path.isCanonical() ? projectList(request) : redirect(request, path))
                    : methodNotAllowed(method);
            case SIMPLE_PROJECT -> request.isGet() ? (path.isCanonical() ? projectPage(request, path) : redirect(request, path))
                    : methodNotAllowed(method);
            case PACKAGE_FILE -> switch (method) {
                case "GET", "HEAD" -> file(request, path);
                case "DELETE" -> deleteFile(request, path);
                default -> methodNotAllowed(method);
            };
            case PACKAGE_VERSION -> "DELETE".equals(method) ? deleteVersion(request, path) : ContentResponses.notFound();
            case JSON_PROJECT, JSON_RELEASE -> request.isGet() ? jsonApi(request, path) : methodNotAllowed(method);
            case UNKNOWN -> ContentResponses.notFound();
        };
    }

    // --- index pages -----------------------------------------------------------------------------

    private ResponseEntity<?> landingPage(RepositoryRequest request) {
        String name = SimpleHtml.escape(request.repository().getName());
        String simple = SimpleHtml.escape(request.baseUrl() + "/simple");
        String html = "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>" + name + "</title>\n</head>\n<body>\n"
                + "<h1>" + name + "</h1>\n"
                + "<p>PyPI repository (" + request.repository().getType().name().toLowerCase(Locale.ROOT) + "). "
                + "Simple index: <a href=\"simple/\">simple/</a></p>\n"
                + "<pre>pip install --index-url " + simple + " &lt;package&gt;</pre>\n"
                + (request.repository().isHosted() ? "<p>Upload with <code>twine upload --repository-url "
                        + SimpleHtml.escape(request.baseUrl() + "/") + " dist/*</code></p>\n" : "")
                + "</body>\n</html>\n";
        return PypiResponses.document(request, html, PypiAccept.TEXT_HTML_UTF8);
    }

    private ResponseEntity<?> redirect(RepositoryRequest request, PypiPath path) {
        return PypiResponses.redirect(UrlPaths.join(request.baseUrl(), path.canonicalPath()));
    }

    private PypiAccept accept(RepositoryRequest request) {
        return PypiAccept.negotiate(request.header("Accept"), request.query("format"));
    }

    private ResponseEntity<?> projectList(RepositoryRequest request) {
        PypiAccept accept = accept(request);
        Optional<List<String>> names = projectNames(request.repository(), accept);
        if (names.isEmpty()) {
            return ContentResponses.notFound("The project list is not available");
        }
        return accept.json()
                ? PypiResponses.negotiated(request, index.renderProjectListJson(names.get()), accept.contentType())
                : PypiResponses.negotiated(request, index.renderProjectListHtml(names.get()), accept.contentType());
    }

    /** Every project name the repository can serve; empty when a proxy has nothing (neither upstream nor cache). */
    Optional<List<String>> projectNames(Repository repo, PypiAccept accept) {
        return switch (repo.getType()) {
            case HOSTED -> Optional.of(hosted.projectNames(repo));
            case PROXY -> proxy.projectNames(repo, accept);
            case GROUP -> {
                List<List<String>> lists = new ArrayList<>();
                boolean anyMember = false;
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<List<String>> names = projectNames(member, accept);
                    if (names.isPresent()) {
                        anyMember = true;
                        lists.add(names.get());
                    }
                }
                yield anyMember ? Optional.of(PypiMerger.mergeNames(lists)) : Optional.empty();
            }
        };
    }

    private ResponseEntity<?> projectPage(RepositoryRequest request, PypiPath path) {
        PypiAccept accept = accept(request);
        Optional<PypiProject> project = project(request.repository(), path.project(), accept, request.baseUrl());
        if (project.isEmpty()) {
            return ContentResponses.notFound("Project '" + path.project() + "' not found");
        }
        return accept.json()
                ? PypiResponses.negotiated(request, index.renderProjectJson(project.get()), accept.contentType())
                : PypiResponses.negotiated(request, index.renderProjectHtml(project.get()), accept.contentType());
    }

    /** The project page with download links pointing at {@code baseUrl}, which for a group is the group's own URL. */
    Optional<PypiProject> project(Repository repo, String name, PypiAccept accept, String baseUrl) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.project(repo, name, baseUrl);
            case PROXY -> proxy.project(repo, name, accept)
                    .map(page -> PypiProxy.rewrite(page, baseUrl, PypiProxy.filesUrl(repo)).withName(name));
            case GROUP -> {
                List<PypiProject> pages = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    project(member, name, accept, baseUrl).ifPresent(pages::add);
                }
                yield pages.isEmpty() ? Optional.empty() : Optional.of(PypiMerger.mergeProjects(name, pages));
            }
        };
    }

    // --- files -----------------------------------------------------------------------------------

    private ResponseEntity<?> file(RepositoryRequest request, PypiPath path) {
        Optional<StoredContent> content = file(request.repository(), path.path());
        if (content.isEmpty()) {
            return ContentResponses.notFound("File '" + path.filename() + "' not found");
        }
        ResponseEntity<?> response = withFilename(ContentResponses.stream(request, content.get()), path.filename());
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.get().asset());
        }
        return response;
    }

    /**
     * Names the download explicitly. Spring otherwise adds {@code Content-Disposition: inline;filename=f.txt} to
     * responses for unknown extensions such as {@code .whl}, and pip takes the file name from that header.
     */
    static ResponseEntity<?> withFilename(ResponseEntity<?> response, String filename) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replace("\"", "") + "\"")
                .body(response.getBody());
    }

    /** A file under {@code packages/…} from wherever the repository type keeps it; a group asks its members in order. */
    Optional<StoredContent> file(Repository repo, String path) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.file(repo, path);
            case PROXY -> proxy.file(repo, path);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = file(member, path);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    // --- JSON API --------------------------------------------------------------------------------

    private ResponseEntity<?> jsonApi(RepositoryRequest request, PypiPath path) {
        Optional<ObjectNode> doc = path.kind() == PypiPath.Kind.JSON_PROJECT
                ? projectJson(request.repository(), path.project(), request.baseUrl())
                : releaseJson(request.repository(), path.project(), path.version(), request.baseUrl());
        if (doc.isEmpty()) {
            return ContentResponses.notFound("Project '" + path.project() + "' not found");
        }
        return PypiResponses.document(request, index.toBytes(doc.get()), MediaType.APPLICATION_JSON_VALUE);
    }

    Optional<ObjectNode> projectJson(Repository repo, String name, String baseUrl) {
        return switch (repo.getType()) {
            case HOSTED -> {
                List<PypiHosted.HostedFile> files = hosted.files(repo, name, baseUrl);
                yield files.isEmpty() ? Optional.empty() : Optional.of(jsonApi.project(name, files, baseUrl));
            }
            case PROXY -> proxy.jsonApi(repo, PypiPath.PYPI + name + "/json")
                    .map(doc -> jsonApi.rewrite(doc, name, baseUrl, PypiProxy.filesUrl(repo)));
            case GROUP -> {
                List<ObjectNode> docs = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    projectJson(member, name, baseUrl).ifPresent(docs::add);
                }
                yield docs.isEmpty() ? Optional.empty() : Optional.of(jsonApi.merge(docs));
            }
        };
    }

    Optional<ObjectNode> releaseJson(Repository repo, String name, String version, String baseUrl) {
        return switch (repo.getType()) {
            case HOSTED -> jsonApi.release(name, hosted.files(repo, name, baseUrl), version, baseUrl);
            case PROXY -> proxy.jsonApi(repo, PypiPath.PYPI + name + "/" + version + "/json")
                    .map(doc -> jsonApi.rewrite(doc, name, baseUrl, PypiProxy.filesUrl(repo)));
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<ObjectNode> hit = releaseJson(member, name, version, baseUrl);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    // --- writes ----------------------------------------------------------------------------------

    private ResponseEntity<?> upload(RepositoryRequest request) throws IOException {
        Repository repo = request.repository();
        requireHosted(repo, "upload to");
        PypiUpload upload = PypiUpload.read(request, tempDir);
        try {
            hosted.store(repo, upload, request.baseUrl());
        } finally {
            Files.deleteIfExists(upload.file());
        }
        return PypiResponses.text(HttpStatus.OK, "OK: " + upload.filename() + " stored in " + repo.getName());
    }

    private ResponseEntity<?> deleteFile(RepositoryRequest request, PypiPath path) {
        requireHosted(request.repository(), "delete from");
        if (!hosted.deleteFile(request.repository(), path.path())) {
            throw PypiError.notFound("File '" + path.filename() + "' not found");
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> deleteVersion(RepositoryRequest request, PypiPath path) {
        requireHosted(request.repository(), "delete from");
        if (hosted.deleteVersion(request.repository(), path.project(), path.version()) == 0) {
            throw PypiError.notFound("Version " + path.version() + " of '" + path.project() + "' not found");
        }
        return ResponseEntity.noContent().build();
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
        throw PypiError.readOnly("'" + repo.getName() + "' is a " + repo.getType().name().toLowerCase(Locale.ROOT)
                + " repository, you cannot " + verb + " it" + hint);
    }

    private static ResponseEntity<?> methodNotAllowed(String method) {
        return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                method + " is not supported by PyPI repositories here");
    }

    /** For tests and the JSON API: the upload temp directory this handler spools into. */
    Path tempDir() {
        return tempDir;
    }
}
