package dev.granary.format.cocoapods;

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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

/**
 * CocoaPods spec repositories in the layout of {@code https://cdn.cocoapods.org/}, which {@code pod} 1.8 and later
 * read as a "CDN source" ({@code source 'https://…/'} in a Podfile, {@code pod repo add-cdn}).
 *
 * <p>Hosted repositories take podspecs ({@code PUT Specs/<Name>/<version>/<Name>.podspec.json}, or
 * {@code POST api/pods} with the source archive) and build the shard indexes from what is stored. Proxies cache
 * the CDN's index files as metadata and its podspecs forever, and point http source archives at themselves so
 * they are cached too. Groups merge the indexes of their members and answer podspecs and archives from the first
 * member that has them.
 */
@org.springframework.stereotype.Component
public class CocoaPodsFormatHandler implements FormatHandler {

    public static final String FORMAT = "cocoapods";
    public static final String DEFAULT_REMOTE = "https://cdn.cocoapods.org/";

    private final StorageService storage;
    private final CocoaPodsHosted hosted;
    private final CocoaPodsProxy proxy;
    private final CocoaPodsResolver resolver;
    private final GroupResolver groups;
    private final Path tempDir;

    public CocoaPodsFormatHandler(StorageService storage, CocoaPodsHosted hosted, CocoaPodsProxy proxy,
                                  CocoaPodsResolver resolver, GroupResolver groups, GranaryProperties properties) {
        this.storage = storage;
        this.hosted = hosted;
        this.proxy = proxy;
        this.resolver = resolver;
        this.groups = groups;
        this.tempDir = properties.getDataDir().resolve("tmp").resolve(FORMAT);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "CocoaPods";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("cocoapods-cdn", FORMAT, DEFAULT_REMOTE, "Proxy of the CocoaPods trunk CDN"),
                RepositoryTemplate.hosted("cocoapods-hosted", FORMAT, "Your own podspecs and source archives"),
                RepositoryTemplate.group("cocoapods-group", FORMAT, List.of("cocoapods-hosted", "cocoapods-cdn"),
                        "Single CDN source for Podfiles: your pods and trunk"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return List.of(SettingDescriptor.bool(CocoaPodsProxy.REWRITE_SETTING, "Cache http source archives", true,
                        "Point source.http of proxied podspecs at this repository, so zip and tar sources are downloaded "
                                + "through it and cached; git sources are never changed.")
                .forTypes(RepositoryType.PROXY, RepositoryType.GROUP));
    }

    @Override
    public void validate(Repository repository) {
        Optional<String> rewrite = repository.setting(CocoaPodsProxy.REWRITE_SETTING);
        if (rewrite.isPresent() && !List.of("true", "false", "1", "0", "yes", "no")
                .contains(rewrite.get().toLowerCase(Locale.ROOT))) {
            throw GranaryException.badRequest("repository.cocoapods.setting.invalid",
                    CocoaPodsProxy.REWRITE_SETTING + " must be true or false");
        }
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return CocoaPodsSnippets.of(context);
    }

    /** The index files change with every push; podspecs and source archives never do. */
    @Override
    public boolean isMetadataPath(String path) {
        return CocoaPodsPath.parse(path).isMetadata();
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        CocoaPodsPath path = hasEncodedSeparator(request) ? CocoaPodsPath.invalid() : CocoaPodsPath.parse(request.path());
        try {
            return dispatch(request, path);
        } catch (CocoaPodsError e) {
            return CocoaPodsResponses.error(e);
        }
    }

    /**
     * Whether the raw request path carries a percent-encoded slash or backslash. The path arrives decoded, so
     * {@code Specs/Foo%2F1.0/…} would otherwise be read as {@code Specs/Foo/1.0/…}. No pod name, version or archive
     * name contains either character ({@code pod} percent-encodes a {@code %} in a name as {@code %25}), so such a
     * request is never legitimate.
     */
    static boolean hasEncodedSeparator(RepositoryRequest request) {
        String raw = request.http() == null ? null : request.http().getRequestURI();
        if (raw == null) {
            return false;
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        return upper.contains("%2F") || upper.contains("%5C");
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, CocoaPodsPath path) throws IOException {
        String method = request.method();
        return switch (path.kind()) {
            case INVALID -> throw CocoaPodsError.invalidPath();
            case ROOT -> {
                requireRead(request);
                yield landingPage(request);
            }
            case VERSION_FILE -> {
                requireRead(request);
                yield versionFile(request);
            }
            case SHARD -> {
                requireRead(request);
                yield shard(request, path);
            }
            case ALL_PODS -> {
                requireRead(request);
                yield allPods(request);
            }
            case DEPRECATED -> {
                requireRead(request);
                yield deprecated(request);
            }
            case PODSPEC -> switch (method) {
                case "GET", "HEAD" -> podspec(request, path);
                case "PUT" -> push(request, path);
                case "DELETE" -> deleteVersion(request, path);
                default -> throw methodNotAllowed(method);
            };
            case VERSION -> switch (method) {
                case "DELETE" -> deleteVersion(request, path);
                case "GET", "HEAD" -> ContentResponses.notFound();
                default -> throw methodNotAllowed(method);
            };
            case SOURCE -> switch (method) {
                case "GET", "HEAD" -> source(request, path);
                case "PUT" -> putSource(request, path);
                case "DELETE" -> deleteSource(request, path);
                default -> throw methodNotAllowed(method);
            };
            case API_PODS -> {
                if (!"POST".equals(method)) {
                    throw CocoaPodsError.methodNotAllowed("POST a podspec (and optionally its source archive) to api/pods");
                }
                yield pushUpload(request);
            }
            case UNKNOWN -> {
                if (!request.isGet()) {
                    throw CocoaPodsError.notFound("Nothing to " + method + " at '" + request.path()
                            + "'; podspecs go to Specs/<Name>/<version>/<Name>.podspec.json");
                }
                yield ContentResponses.notFound();
            }
        };
    }

    // --- index documents --------------------------------------------------------------------

    private ResponseEntity<?> landingPage(RepositoryRequest request) {
        Repository repo = request.repository();
        String name = HtmlUtils.htmlEscape(repo.getName());
        String url = HtmlUtils.htmlEscape(request.baseUrl() + "/");
        String html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>" + name + "</title>\n</head>\n"
                + "<body>\n<h1>" + name + "</h1>\n"
                + "<p>CocoaPods spec repository (" + repo.getType().name().toLowerCase(Locale.ROOT) + "), CDN layout.</p>\n"
                + "<pre>source '" + url + "'</pre>\n"
                + "<pre>pod repo add-cdn granary " + url + "</pre>\n"
                + (repo.isHosted() ? "<p>Publish with <code>curl -X PUT --data-binary @MyPod.podspec.json " + url
                        + "Specs/MyPod/1.0.0/MyPod.podspec.json</code></p>\n" : "")
                + "<p><a href=\"" + VersionFile.PATH + "\">" + VersionFile.PATH + "</a></p>\n"
                + "</body>\n</html>\n";
        return CocoaPodsResponses.document(request, html.getBytes(StandardCharsets.UTF_8), "text/html;charset=UTF-8");
    }

    private ResponseEntity<?> versionFile(RepositoryRequest request) {
        Repository repo = request.repository();
        if (repo.isProxy()) {
            return cached(request, proxy.index(repo, VersionFile.PATH));
        }
        return CocoaPodsResponses.document(request, VersionFile.standard().toBytes(), VersionFile.CONTENT_TYPE);
    }

    private ResponseEntity<?> shard(RepositoryRequest request, CocoaPodsPath path) {
        Repository repo = request.repository();
        if (repo.isProxy()) {
            return cached(request, proxy.index(repo, request.path()));
        }
        if (!ShardLayout.STANDARD.isFragment(path.fragment())) {
            return ContentResponses.notFound("This repository shards pods by the first three hex digits of their MD5");
        }
        return resolver.shard(repo, String.join("", path.fragment()))
                .<ResponseEntity<?>>map(pods -> CocoaPodsResponses.document(request, PodIndex.renderShard(pods), PodIndex.CONTENT_TYPE))
                .orElseGet(ContentResponses::notFound);
    }

    private ResponseEntity<?> allPods(RepositoryRequest request) {
        Repository repo = request.repository();
        if (repo.isProxy()) {
            return cached(request, proxy.index(repo, CocoaPodsPath.ALL_PODS));
        }
        return resolver.podNames(repo)
                .<ResponseEntity<?>>map(names -> CocoaPodsResponses.document(request, PodIndex.renderLines(names), PodIndex.CONTENT_TYPE))
                .orElseGet(ContentResponses::notFound);
    }

    private ResponseEntity<?> deprecated(RepositoryRequest request) {
        Repository repo = request.repository();
        if (repo.isProxy()) {
            return cached(request, proxy.index(repo, CocoaPodsPath.DEPRECATED));
        }
        if (repo.isHosted()) {
            Optional<StoredContent> stored = hosted.deprecatedFile(repo);
            return stored.isPresent() ? ContentResponses.stream(request, stored.get())
                    : CocoaPodsResponses.document(request, new byte[0], PodIndex.CONTENT_TYPE);
        }
        return resolver.deprecated(repo)
                .<ResponseEntity<?>>map(lines -> CocoaPodsResponses.document(request, PodIndex.renderLines(lines), PodIndex.CONTENT_TYPE))
                .orElseGet(ContentResponses::notFound);
    }

    private static ResponseEntity<?> cached(RepositoryRequest request, Optional<StoredContent> content) {
        return content.<ResponseEntity<?>>map(c -> ContentResponses.stream(request, c)).orElseGet(ContentResponses::notFound);
    }

    // --- podspecs and sources ---------------------------------------------------------------

    private ResponseEntity<?> podspec(RepositoryRequest request, CocoaPodsPath path) throws IOException {
        Repository repo = request.repository();
        if (!path.fragment().isEmpty() && !path.fragment().equals(resolver.layout(repo).fragment(path.name()))) {
            return ContentResponses.notFound("Pod '" + path.name() + "' is not in this shard");
        }
        Optional<CocoaPodsResolver.Found> found = resolver.podspec(repo, path.name(), path.version());
        if (found.isEmpty()) {
            return ContentResponses.notFound("Pod '" + path.name() + "' " + path.version() + " not found");
        }
        byte[] body = resolver.render(repo, found.get(), path.name(), path.version(), request.baseUrl());
        ResponseEntity<?> response = CocoaPodsResponses.document(request, body, Podspec.CONTENT_TYPE);
        recordDownload(request, response, found.get().content());
        return response;
    }

    private ResponseEntity<?> source(RepositoryRequest request, CocoaPodsPath path) {
        Optional<StoredContent> content = resolver.source(request.repository(), path.name(), path.version(), path.filename());
        if (content.isEmpty()) {
            return ContentResponses.notFound("Source archive '" + path.filename() + "' of " + path.name() + " "
                    + path.version() + " not found");
        }
        ResponseEntity<?> response = CocoaPodsResponses.attachment(ContentResponses.stream(request, content.get()), path.filename());
        recordDownload(request, response, content.get());
        return response;
    }

    private void recordDownload(RepositoryRequest request, ResponseEntity<?> response, StoredContent content) {
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.asset());
        }
    }

    // --- writes -----------------------------------------------------------------------------

    private ResponseEntity<?> push(RepositoryRequest request, CocoaPodsPath path) throws IOException {
        Repository repo = requireHosted(request.repository(), "push to");
        requireStandardFragment(path);
        byte[] body;
        try (InputStream in = request.body()) {
            body = PodUpload.readPodspec(in);
        }
        CocoaPodsHosted.Pushed pushed = hosted.push(repo, body, path.name(), path.version(), null, request.baseUrl());
        return created(request, pushed);
    }

    private ResponseEntity<?> pushUpload(RepositoryRequest request) throws IOException {
        Repository repo = requireHosted(request.repository(), "push to");
        PodUpload upload = PodUpload.read(request, tempDir);
        try {
            CocoaPodsHosted.Archive archive = upload.source() == null ? null
                    : new CocoaPodsHosted.Archive(upload.source(), upload.sourceFilename());
            CocoaPodsHosted.Pushed pushed = hosted.push(repo, upload.podspec(), null, null, archive, request.baseUrl());
            return created(request, pushed);
        } finally {
            upload.cleanUp();
        }
    }

    private static ResponseEntity<?> created(RepositoryRequest request, CocoaPodsHosted.Pushed pushed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", pushed.name());
        body.put("version", pushed.version());
        body.put("podspec", UrlPaths.join(request.baseUrl(), pushed.podspecPath()));
        if (pushed.sourcePath() != null) {
            body.put("source", UrlPaths.join(request.baseUrl(), pushed.sourcePath()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private ResponseEntity<?> putSource(RepositoryRequest request, CocoaPodsPath path) throws IOException {
        Repository repo = requireHosted(request.repository(), "upload to");
        Path spooled = PodUpload.spool(tempDir);
        try {
            PodUpload.copyBody(request, spooled);
            String stored = hosted.putSource(repo, path.name(), path.version(), path.filename(), spooled);
            return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("source", UrlPaths.join(request.baseUrl(), stored)));
        } finally {
            Files.deleteIfExists(spooled);
        }
    }

    private ResponseEntity<?> deleteVersion(RepositoryRequest request, CocoaPodsPath path) {
        Repository repo = requireHosted(request.repository(), "delete from");
        requireStandardFragment(path);
        if (hosted.deleteVersion(repo, path.name(), path.version()) == 0) {
            throw CocoaPodsError.notFound("Pod '" + path.name() + "' " + path.version() + " not found");
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> deleteSource(RepositoryRequest request, CocoaPodsPath path) {
        Repository repo = requireHosted(request.repository(), "delete from");
        if (!hosted.deleteSource(repo, path.name(), path.version(), path.filename())) {
            throw CocoaPodsError.notFound("Source archive '" + path.filename() + "' not found");
        }
        return ResponseEntity.noContent().build();
    }

    /** Hosted repositories store pods under the standard shard; the short form without shard directories is accepted too. */
    private static void requireStandardFragment(CocoaPodsPath path) {
        List<String> expected = ShardLayout.STANDARD.fragment(path.name());
        if (!path.fragment().isEmpty() && !path.fragment().equals(expected)) {
            throw CocoaPodsError.badRequest("'" + path.name() + "' belongs in " + ShardLayout.STANDARD.podDirectory(path.name())
                    + "/; use that path or Specs/" + path.name() + "/" + path.version() + "/…");
        }
    }

    /** Writes only make sense on hosted repositories; a group names the member to use instead. */
    private Repository requireHosted(Repository repo, String verb) {
        if (repo.isHosted()) {
            return repo;
        }
        String hint = "";
        if (repo.isGroup()) {
            hint = groups.leafMembers(repo).stream().filter(Repository::isHosted).findFirst()
                    .map(member -> "; use its hosted member '" + member.getName() + "'")
                    .orElse("; it has no hosted member");
        }
        throw CocoaPodsError.readOnly("'" + repo.getName() + "' is a " + repo.getType().name().toLowerCase(Locale.ROOT)
                + " repository, you cannot " + verb + " it" + hint);
    }

    private static void requireRead(RepositoryRequest request) {
        if (!request.isGet()) {
            throw methodNotAllowed(request.method());
        }
    }

    private static CocoaPodsError methodNotAllowed(String method) {
        return CocoaPodsError.methodNotAllowed(method + " is not supported here");
    }
}
