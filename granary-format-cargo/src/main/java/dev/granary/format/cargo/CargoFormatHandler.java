package dev.granary.format.cargo;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.AuthenticationService;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cargo registries: the sparse index protocol cargo reads ({@code index/config.json} and one file per crate) and
 * the registry web API it writes through ({@code cargo publish}, {@code yank}, {@code owner}, {@code search}).
 *
 * <p>Hosted repositories accept publishes; proxies cache a sparse upstream (index.crates.io by default) and
 * its crate files, serving the cache when the upstream is unreachable; groups merge their members' index files
 * under one URL. The {@code config.json} every repository serves names the repository itself (or the group the
 * client asked) as download and API host, so all traffic stays on Granary.
 */
@org.springframework.stereotype.Component
public class CargoFormatHandler implements FormatHandler {

    public static final String FORMAT = "cargo";
    public static final String DEFAULT_REMOTE = "https://index.crates.io/";

    private final StorageService storage;
    private final CargoIndexes indexes;
    private final CargoHosted hosted;
    private final CargoSearch search;
    private final GroupResolver groups;
    private final AuthenticationService authentication;
    private final Path tempDir;

    public CargoFormatHandler(StorageService storage, CargoIndexes indexes, CargoHosted hosted, CargoSearch search,
                              GroupResolver groups, AuthenticationService authentication, GranaryProperties properties) {
        this.storage = storage;
        this.indexes = indexes;
        this.hosted = hosted;
        this.search = search;
        this.groups = groups;
        this.authentication = authentication;
        this.tempDir = properties.getDataDir().resolve("tmp").resolve(FORMAT);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Cargo (crates.io)";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("cargo-proxy", FORMAT, DEFAULT_REMOTE, "Proxy of crates.io (sparse index)"),
                RepositoryTemplate.hosted("cargo-hosted", FORMAT, "Your own crates"),
                RepositoryTemplate.group("cargo-group", FORMAT, List.of("cargo-hosted", "cargo-proxy"),
                        "Single cargo registry for your crates and crates.io"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return CargoSettings.descriptors();
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return CargoSnippets.of(context, publishUrl(context));
    }

    /** Where a client of this repository publishes: itself when hosted, else the group's first hosted member. */
    private String publishUrl(ConnectContext context) {
        Repository repo = context.repository();
        if (repo.isHosted()) {
            return context.url();
        }
        if (repo.isGroup()) {
            return firstHostedMember(repo).map(name -> context.serverUrl() + "/repository/" + name).orElse(null);
        }
        return null;
    }

    @Override
    public void validate(Repository repository) {
        CargoSettings.validate(repository);
    }

    @Override
    public boolean isMetadataPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p.startsWith(CrateNames.INDEX_PREFIX) || p.startsWith(CrateNames.META_PREFIX)
                || CargoPath.parse(p).isMetadata();
    }

    /**
     * {@code me} and the landing page are open to everyone (they only explain how to log in); owners can be read
     * by anyone who may read; everything else follows the method.
     */
    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        CargoPath path = CargoPath.parse(request.path());
        return switch (path.kind()) {
            case ROOT, ME -> AccessLevel.NONE;
            case CONFIG, INDEX, DOWNLOAD, SEARCH -> AccessLevel.READ;
            default -> request.isGet() ? AccessLevel.READ : AccessLevel.WRITE;
        };
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        if (hasEncodedSeparator(request)) {
            return CargoResponses.error(HttpStatus.BAD_REQUEST, "invalid crate name, version or path");
        }
        CargoPath path = CargoPath.parse(request.path());
        ResponseEntity<?> response;
        try {
            response = dispatch(request, path);
        } catch (CargoError e) {
            response = CargoResponses.error(e);
        }
        // the headers of a GET, without its body, whichever container serves the response
        return request.isHead() && response.hasBody()
                ? ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).build()
                : response;
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, CargoPath path) throws IOException {
        String method = request.method();
        return switch (path.kind()) {
            case ROOT -> request.isGet() ? landingPage(request) : methodNotAllowed(method);
            case ME -> request.isGet() ? mePage(request) : methodNotAllowed(method);
            case CONFIG -> request.isGet() ? config(request) : methodNotAllowed(method);
            case INDEX -> request.isGet() ? indexFile(request, path) : methodNotAllowed(method);
            case SEARCH -> request.isGet() ? search(request) : methodNotAllowed(method);
            case PUBLISH -> "PUT".equals(method) ? publish(request) : methodNotAllowed(method);
            case DOWNLOAD -> request.isGet() ? download(request, path) : methodNotAllowed(method);
            case YANK -> "DELETE".equals(method) ? yank(request, path, true) : methodNotAllowed(method);
            case UNYANK -> "PUT".equals(method) ? yank(request, path, false) : methodNotAllowed(method);
            case OWNERS -> switch (method) {
                case "GET", "HEAD" -> listOwners(request, path);
                case "PUT" -> changeOwners(request, path, true);
                case "DELETE" -> changeOwners(request, path, false);
                default -> methodNotAllowed(method);
            };
            case INVALID -> CargoResponses.error(HttpStatus.BAD_REQUEST, "invalid crate name, version or path");
            case UNKNOWN -> CargoResponses.error(HttpStatus.NOT_FOUND, "not found");
        };
    }

    // --- sparse index --------------------------------------------------------------------------

    private ResponseEntity<?> config(RepositoryRequest request) {
        ObjectNode config = CargoJson.object();
        config.put("dl", request.baseUrl() + "/api/v1/crates");
        config.put("api", request.baseUrl());
        config.put("auth-required", authRequired(request.repository()));
        return CargoResponses.document(request, CargoJson.bytes(config), CargoResponses.JSON_CONTENT_TYPE);
    }

    /** Tokens are needed on every request when the repository says so, or when anonymous users may not read it. */
    private boolean authRequired(Repository repo) {
        return CargoSettings.authRequired(repo) || !authentication.anonymous().canRead(repo.getName());
    }

    private ResponseEntity<?> indexFile(RepositoryRequest request, CargoPath path) {
        List<IndexEntry.Sourced<Repository>> entries = indexes.entries(request.repository(), path.name());
        if (entries.isEmpty()) {
            return CargoResponses.error(HttpStatus.NOT_FOUND, "crate `" + path.name() + "` not found");
        }
        String text = IndexEntry.render(entries.stream().map(IndexEntry.Sourced::entry).toList());
        return CargoResponses.document(request, text.getBytes(StandardCharsets.UTF_8), CargoResponses.INDEX_CONTENT_TYPE);
    }

    private ResponseEntity<?> download(RepositoryRequest request, CargoPath path) {
        Optional<StoredContent> content = indexes.crate(request.repository(), path.name(), path.version());
        if (content.isEmpty()) {
            return CargoResponses.error(HttpStatus.NOT_FOUND,
                    "crate `" + path.name() + "` version `" + path.version() + "` not found");
        }
        ResponseEntity<?> response = ContentResponses.stream(request, content.get());
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.get().asset());
        }
        return response;
    }

    // --- web API -------------------------------------------------------------------------------

    private ResponseEntity<?> search(RepositoryRequest request) {
        int perPage = intParam(request, "per_page", CargoSearch.DEFAULT_PER_PAGE, 1, CargoSearch.MAX_PER_PAGE);
        int page = intParam(request, "page", 1, 1, 10_000);
        CargoSearch.Result result = search.search(request.repository(), request.query("q"), perPage, page);
        return CargoResponses.json(HttpStatus.OK, CargoSearch.render(result));
    }

    private ResponseEntity<?> publish(RepositoryRequest request) throws IOException {
        requireHosted(request.repository(), "publish");
        try (InputStream body = request.body(); PublishRequest publish = PublishRequest.read(body, tempDir)) {
            PublishMetadata metadata = PublishMetadata.of(publish.metadata());
            ObjectNode result = hosted.publish(request.repository(), publish, metadata, request.principal().username());
            return CargoResponses.json(HttpStatus.OK, result);
        }
    }

    private ResponseEntity<?> yank(RepositoryRequest request, CargoPath path, boolean yanked) throws IOException {
        requireHosted(request.repository(), yanked ? "yank" : "unyank");
        hosted.setYanked(request.repository(), path.name(), path.version(), yanked);
        return CargoResponses.ok(null);
    }

    private ResponseEntity<?> listOwners(RepositoryRequest request, CargoPath path) {
        Repository repo = request.repository();
        Repository owner = repo;
        if (repo.isGroup()) {
            owner = indexes.members(repo).stream()
                    .filter(Repository::isHosted)
                    .filter(member -> !hosted.liveEntries(member, path.name()).isEmpty())
                    .findFirst()
                    .orElseThrow(() -> CargoError.notFound("crate `" + path.name() + "` is not published to a hosted member of "
                            + repo.getName()));
        } else if (repo.isProxy()) {
            throw CargoError.notFound("owners are only recorded for crates published to a hosted repository");
        }
        ObjectNode body = CargoJson.object();
        ArrayNode users = body.putArray("users");
        int id = 1;
        for (String login : hosted.owners(owner, path.name())) {
            ObjectNode user = users.addObject();
            user.put("id", id++);
            user.put("login", login);
            user.put("name", login);
        }
        return CargoResponses.json(HttpStatus.OK, body);
    }

    private ResponseEntity<?> changeOwners(RepositoryRequest request, CargoPath path, boolean add) throws IOException {
        requireHosted(request.repository(), "change the owners of");
        List<String> logins = ownersFromBody(request);
        String message = add
                ? hosted.addOwners(request.repository(), path.name(), logins)
                : hosted.removeOwners(request.repository(), path.name(), logins);
        return CargoResponses.ok(message);
    }

    /** {@code {"users":["login", …]}}, the body of {@code cargo owner --add/--remove}. */
    private static List<String> ownersFromBody(RepositoryRequest request) throws IOException {
        byte[] bytes;
        try (InputStream in = request.body()) {
            bytes = in.readNBytes(1024 * 1024);
        }
        ObjectNode body = CargoJson.parseObject(bytes)
                .orElseThrow(() -> CargoError.badRequest("expected a JSON object {\"users\": [\"login\"]}"));
        JsonNode users = body.get("users");
        if (users == null || !users.isArray() || users.isEmpty()) {
            throw CargoError.badRequest("expected a non-empty list of logins in `users`");
        }
        List<String> logins = new ArrayList<>();
        for (JsonNode u : users) {
            if (!u.isString() || u.stringValue().isBlank()) {
                throw CargoError.badRequest("every entry of `users` must be a login");
            }
            logins.add(u.stringValue().trim());
        }
        return logins;
    }

    // --- pages ---------------------------------------------------------------------------------

    private ResponseEntity<?> mePage(RepositoryRequest request) {
        String name = CargoResponses.escapeHtml(request.repository().getName());
        String account = CargoResponses.escapeHtml(request.serverUrl() + "/account");
        String html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<title>Cargo token for " + name + "</title>\n</head>\n<body>\n"
                + "<h1>Log in to " + name + " with your Granary API token</h1>\n"
                + "<p>Cargo authenticates with a Granary API token. Create one on your "
                + "<a href=\"" + account + "\">account page</a> and paste it into</p>\n"
                + "<pre>cargo login --registry " + CargoSnippets.REGISTRY + "</pre>\n"
                + "<p>or set <code>CARGO_REGISTRIES_" + CargoSnippets.REGISTRY.toUpperCase(Locale.ROOT)
                + "_TOKEN</code> in the environment.</p>\n"
                + "</body>\n</html>\n";
        return CargoResponses.html(request, html);
    }

    private ResponseEntity<?> landingPage(RepositoryRequest request) {
        Repository repo = request.repository();
        String name = CargoResponses.escapeHtml(repo.getName());
        String index = CargoResponses.escapeHtml("sparse+" + request.baseUrl() + "/index/");
        String html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<title>" + name + "</title>\n</head>\n<body>\n"
                + "<h1>" + name + "</h1>\n"
                + "<p>Cargo registry (" + repo.getType().name().toLowerCase(Locale.ROOT) + "). Add it to "
                + "<code>.cargo/config.toml</code>:</p>\n"
                + "<pre>[registries." + CargoSnippets.REGISTRY + "]\nindex = \"" + index + "\"</pre>\n"
                + "</body>\n</html>\n";
        return CargoResponses.html(request, html);
    }

    // --- helpers -------------------------------------------------------------------------------

    private void requireHosted(Repository repo, String action) {
        if (repo.isHosted()) {
            return;
        }
        String type = repo.getType().name().toLowerCase(Locale.ROOT);
        String hint = repo.isGroup()
                ? firstHostedMember(repo).map(m -> "; use its hosted member `" + m + "` instead")
                        .orElse("; use a hosted repository instead")
                : "; use a hosted repository instead";
        throw CargoError.methodNotAllowed("cannot " + action + " crates in `" + repo.getName() + "`: it is a " + type
                + " repository" + hint);
    }

    private Optional<String> firstHostedMember(Repository group) {
        return groups.leafMembers(group).stream().filter(Repository::isHosted).map(Repository::getName).findFirst();
    }

    /**
     * {@code %2F} or {@code %5C} in the request URL. cargo never sends one, and the path the handler sees is already
     * decoded, so {@code ac%2Fme/acme} would otherwise be read as {@code ac/me/acme}.
     */
    static boolean hasEncodedSeparator(RepositoryRequest request) {
        String uri = request.http() == null ? null : request.http().getRequestURI();
        if (uri == null) {
            return false;
        }
        String lower = uri.toLowerCase(Locale.ROOT);
        return lower.contains("%2f") || lower.contains("%5c");
    }

    private static int intParam(RepositoryRequest request, String name, int defaultValue, int min, int max) {
        String raw = request.query(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static ResponseEntity<?> methodNotAllowed(String method) {
        return CargoResponses.error(HttpStatus.METHOD_NOT_ALLOWED, method + " is not supported here");
    }
}
