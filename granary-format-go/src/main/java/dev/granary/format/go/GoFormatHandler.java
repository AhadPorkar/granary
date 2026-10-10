package dev.granary.format.go;

import dev.granary.core.GranaryException;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The GOPROXY protocol spoken by the go command, plus the checksum-database passthrough it uses when
 * {@code GOSUMDB} is set (the default): {@code <module>/@v/list}, {@code .info}, {@code .mod}, {@code .zip},
 * {@code @latest} and {@code sumdb/<name>/…}.
 *
 * <p>Hosted repositories take versions as a {@code PUT} of the module zip; proxies cache proxy.golang.org (or
 * any other GOPROXY) with offline fallback; groups merge version lists and serve files from the first member
 * that has them. Module paths and versions travel case-encoded ({@code !x} for {@code X}) and are stored that
 * way, exactly as the go command keeps them in its own module cache.
 */
@org.springframework.stereotype.Component
public class GoFormatHandler implements FormatHandler {

    public static final String FORMAT = "go";
    public static final String DEFAULT_REMOTE = "https://proxy.golang.org/";

    private final StorageService storage;
    private final GoModules modules;
    private final GoHostedWriter writer;
    private final GroupResolver groups;
    private final ObjectMapper mapper;

    public GoFormatHandler(StorageService storage, GoModules modules, GoHostedWriter writer, GroupResolver groups,
                           ObjectMapper mapper) {
        this.storage = storage;
        this.modules = modules;
        this.writer = writer;
        this.groups = groups;
        this.mapper = mapper;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Go modules";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("go-proxy", FORMAT, DEFAULT_REMOTE,
                        "Proxy of proxy.golang.org, checksum database included"),
                RepositoryTemplate.hosted("go-hosted", FORMAT, "Your own modules"),
                RepositoryTemplate.group("go-group", FORMAT, List.of("go-hosted", "go-proxy"),
                        "Single GOPROXY URL for public and private modules"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return List.of(
                SettingDescriptor.string(GoModules.SUMDB_NAME, "Checksum database name", GoModules.DEFAULT_SUMDB_NAME,
                        "The database (the name in GOSUMDB) whose sumdb/<name>/… requests are passed through; "
                                + "the go command contacts any other database directly")
                        .forTypes(RepositoryType.PROXY),
                SettingDescriptor.url(GoModules.SUMDB_URL, "Checksum database URL", GoModules.DEFAULT_SUMDB,
                        "Where those requests are forwarded to, e.g. a mirror such as https://sum.golang.google.cn; "
                                + "by default https://<checksum database name>")
                        .forTypes(RepositoryType.PROXY));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return GoSnippets.of(context, hostedMember(context.repository()).orElse(null));
    }

    @Override
    public void validate(Repository repository) {
        Optional<String> name = repository.setting(GoModules.SUMDB_NAME).map(String::trim).filter(s -> !s.isEmpty());
        if (name.isPresent() && !GoPath.isValidSumdbName(name.get())) {
            throw GranaryException.badRequest("repository.go.sumdbName.invalid",
                    "'" + name.get() + "' is not a checksum database name; " + GoModules.SUMDB_NAME
                            + " must be a lower-case host name such as " + GoModules.DEFAULT_SUMDB_NAME);
        }
        Optional<String> sumdb = repository.setting(GoModules.SUMDB_URL).map(String::trim).filter(s -> !s.isEmpty());
        if (sumdb.isEmpty()) {
            return;
        }
        try {
            URI uri = new URI(sumdb.get());
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalidSetting(sumdb.get());
            }
        } catch (URISyntaxException e) {
            throw invalidSetting(sumdb.get());
        }
    }

    private static GranaryException invalidSetting(String value) {
        return GranaryException.badRequest("repository.go.sumdbUrl.invalid",
                "'" + value + "' is not an http(s) URL; " + GoModules.SUMDB_URL + " must look like " + GoModules.DEFAULT_SUMDB);
    }

    @Override
    public boolean isMetadataPath(String path) {
        try {
            return GoPath.parse(path).isMetadata();
        } catch (GoError e) {
            return false;
        }
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        try {
            return dispatch(request, GoPath.parse(request.path()));
        } catch (GoError e) {
            return GoResponses.error(e);
        }
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, GoPath path) throws IOException {
        String method = request.method();
        return switch (path.kind()) {
            case ROOT -> request.isGet() ? info(request) : methodNotAllowed(method);
            case LIST -> request.isGet() ? list(request, path) : methodNotAllowed(method);
            case LATEST -> request.isGet() ? latest(request, path) : methodNotAllowed(method);
            case INFO, MOD, ZIP -> switch (method) {
                case "GET", "HEAD" -> file(request, path);
                case "PUT" -> put(request, path);
                default -> methodNotAllowed(method);
            };
            case VERSION -> "DELETE".equals(method) ? delete(request, path)
                    : request.isGet() ? notFound("there is no such file; ask for " + path.raw() + ".info, .mod or .zip")
                    : methodNotAllowed(method);
            case SUMDB_SUPPORTED -> request.isGet() ? sumdbSupported(request, path) : methodNotAllowed(method);
            case SUMDB -> request.isGet() ? sumdb(request, path) : methodNotAllowed(method);
            case UNKNOWN -> request.isGet() ? notFound("not found") : methodNotAllowed(method);
        };
    }

    // --- reads ---------------------------------------------------------------------------------

    private ResponseEntity<?> info(RepositoryRequest request) {
        ObjectNode node = mapper.createObjectNode();
        node.put("format", FORMAT);
        node.put("repository", request.repository().getName());
        node.put("type", request.repository().getType().name().toLowerCase(Locale.ROOT));
        node.put("goproxy", request.baseUrl());
        return GoResponses.document(request, mapper.writeValueAsBytes(node), GoInfo.CONTENT_TYPE);
    }

    private ResponseEntity<?> list(RepositoryRequest request, GoPath path) throws IOException {
        Repository repo = request.repository();
        if (repo.isProxy()) {
            Optional<StoredContent> cached = modules.proxiedList(repo, path.escapedModule());
            return cached.isPresent() ? ContentResponses.stream(request, cached.get()) : notFound(unknownModule(path));
        }
        Optional<List<String>> versions = modules.list(repo, path.escapedModule());
        if (versions.isEmpty()) {
            return notFound(unknownModule(path));
        }
        return GoResponses.document(request, GoModules.renderList(versions.get()), GoResponses.LIST_CONTENT_TYPE);
    }

    private ResponseEntity<?> latest(RepositoryRequest request, GoPath path) throws IOException {
        Optional<byte[]> info = modules.latest(request.repository(), path.escapedModule());
        if (info.isEmpty()) {
            return notFound(unknownModule(path));
        }
        return GoResponses.document(request, info.get(), GoInfo.CONTENT_TYPE);
    }

    private ResponseEntity<?> file(RepositoryRequest request, GoPath path) {
        if (path.kind() != GoPath.Kind.INFO && !path.hasCanonicalVersion() && request.repository().isHosted()) {
            return notFound("version " + path.version() + " of " + path.module() + " does not exist");
        }
        Optional<StoredContent> content = modules.file(request.repository(), path);
        if (content.isEmpty()) {
            return notFound(path.module() + "@" + path.version() + " does not exist");
        }
        ResponseEntity<?> response = ContentResponses.stream(request, content.get());
        if (path.kind() == GoPath.Kind.ZIP && !request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.get().asset());
        }
        return response;
    }

    private ResponseEntity<?> sumdbSupported(RepositoryRequest request, GoPath path) {
        if (!modules.sumdbSupported(request.repository(), path.sumdbName())) {
            return notFound("checksum database " + path.sumdbName() + " is not passed through this repository");
        }
        return GoResponses.empty();
    }

    private ResponseEntity<?> sumdb(RepositoryRequest request, GoPath path) {
        Optional<StoredContent> content = modules.sumdb(request.repository(), path);
        if (content.isEmpty()) {
            return notFound("checksum database entry " + path.sumdbRest() + " is not available");
        }
        return ContentResponses.stream(request, content.get());
    }

    // --- writes --------------------------------------------------------------------------------

    private ResponseEntity<?> put(RepositoryRequest request, GoPath path) throws IOException {
        Repository repo = requireHosted(request.repository());
        return switch (path.kind()) {
            case ZIP -> {
                GoHostedWriter.Published published;
                try (InputStream body = request.body()) {
                    published = writer.publishZip(repo, path, body);
                }
                yield created(published.module(), published.version(), published.files());
            }
            case MOD -> {
                boolean written = writer.putMod(repo, path, readBody(request));
                yield written ? created(path.module(), path.version(), List.of(path.filePath("mod")))
                        : unchanged(path.module(), path.version());
            }
            case INFO -> {
                boolean written = writer.putInfo(repo, path, readBody(request));
                yield written ? created(path.module(), path.version(), List.of(path.filePath("info")))
                        : unchanged(path.module(), path.version());
            }
            default -> throw new IllegalStateException(path.kind().name());
        };
    }

    private ResponseEntity<?> delete(RepositoryRequest request, GoPath path) {
        Repository repo = requireHosted(request.repository());
        List<String> removed = writer.delete(repo, path);
        ObjectNode node = mapper.createObjectNode();
        node.put("module", path.module());
        node.put("version", path.version());
        ArrayNode files = node.putArray("deleted");
        removed.forEach(files::add);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsBytes(node));
    }

    private Repository requireHosted(Repository repo) {
        if (repo.isHosted()) {
            return repo;
        }
        if (repo.isGroup()) {
            String member = hostedMember(repo).map(m -> "its hosted member '" + m + "'").orElse("a hosted repository");
            throw GoError.readOnly("'" + repo.getName() + "' is a group repository; publish to " + member + " instead");
        }
        throw GoError.readOnly("'" + repo.getName() + "' is a proxy repository; publish to a hosted repository instead");
    }

    private Optional<String> hostedMember(Repository repo) {
        if (!repo.isGroup()) {
            return Optional.empty();
        }
        return groups.leafMembers(repo).stream().filter(Repository::isHosted).map(Repository::getName).findFirst();
    }

    private ResponseEntity<?> created(String module, String version, List<String> files) {
        return written(HttpStatus.CREATED, module, version, files);
    }

    /** {@code 200} for an upload of exactly the file already stored: nothing was written. */
    private ResponseEntity<?> unchanged(String module, String version) {
        return written(HttpStatus.OK, module, version, List.of());
    }

    private ResponseEntity<?> written(HttpStatus status, String module, String version, List<String> files) {
        ObjectNode node = mapper.createObjectNode();
        node.put("module", module);
        node.put("version", version);
        ArrayNode array = node.putArray("files");
        files.forEach(array::add);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsBytes(node));
    }

    // --- helpers -------------------------------------------------------------------------------

    private static byte[] readBody(RepositoryRequest request) throws IOException {
        try (InputStream in = request.body()) {
            return in.readAllBytes();
        }
    }

    private static String unknownModule(GoPath path) {
        return "module " + path.module() + " is not known to this repository";
    }

    private static ResponseEntity<?> notFound(String message) {
        return ContentResponses.notFound(message);
    }

    private static ResponseEntity<?> methodNotAllowed(String method) {
        return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed", method + " is not supported here");
    }
}
