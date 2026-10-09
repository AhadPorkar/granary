package dev.granary.format.npm;

import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.User;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.AuthenticationService;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.security.UserService;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The npm registry protocol, as spoken by the npm CLI (and yarn, pnpm): packuments, tarballs,
 * publish/unpublish, dist-tags, legacy login, whoami, ping and search.
 *
 * <p>Hosted repositories accept {@code npm publish}; proxies cache a remote registry (npmjs.org by
 * default) with offline fallback; groups merge their members so one URL serves both. Every tarball URL
 * a client sees points back at the repository it asked, which is what makes the proxy cache and the
 * group work at all: npm downloads whatever URL the packument names.
 */
@org.springframework.stereotype.Component
public class NpmFormatHandler implements FormatHandler {

    public static final String FORMAT = "npm";
    public static final String DEFAULT_REMOTE = "https://registry.npmjs.org/";
    static final String TOKEN_NAME = "npm login";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final StorageService storage;
    private final NpmPackuments packuments;
    private final NpmHostedWriter writer;
    private final NpmSearch search;
    private final AuthenticationService authentication;
    private final ApiTokenService tokens;
    private final UserService users;

    public NpmFormatHandler(StorageService storage, NpmPackuments packuments, NpmHostedWriter writer, NpmSearch search,
                            AuthenticationService authentication, ApiTokenService tokens, UserService users) {
        this.storage = storage;
        this.packuments = packuments;
        this.writer = writer;
        this.search = search;
        this.authentication = authentication;
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "npm";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("npm-proxy", FORMAT, "https://registry.npmjs.org/", "Proxy of the public npm registry"),
                RepositoryTemplate.hosted("npm-hosted", FORMAT, "Your own packages"),
                RepositoryTemplate.group("npm-group", FORMAT, List.of("npm-hosted", "npm-proxy"),
                        "Single registry URL for npm, pnpm, yarn and bun"));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return NpmConnectSnippets.of(context);
    }

    @Override
    public boolean isMetadataPath(String path) {
        return NpmPath.parse(path).isMetadata();
    }

    /**
     * Login must be reachable without credentials (it is how credentials are obtained), ping and whoami are
     * registry-level and answer for themselves, and the audit/search endpoints are reads even though npm
     * POSTs to the former.
     */
    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        NpmPath path = NpmPath.parse(request.path());
        return switch (path.kind()) {
            case LOGIN, WEB_LOGIN, PING, WHOAMI -> AccessLevel.NONE;
            case SEARCH, AUDIT -> AccessLevel.READ;
            default -> request.isGet() ? AccessLevel.READ : AccessLevel.WRITE;
        };
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        NpmPath path = NpmPath.parse(request.path());
        try {
            return dispatch(request, path);
        } catch (NpmError e) {
            return NpmResponses.error(e);
        }
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, NpmPath path) throws IOException {
        String method = request.method();
        return switch (path.kind()) {
            case ROOT -> request.isGet() ? registryInfo(request) : methodNotAllowed(method);
            case PACKUMENT -> switch (method) {
                case "GET", "HEAD" -> packument(request, path);
                case "PUT" -> publish(request, path);
                case "DELETE" -> unpublishPackage(request, path);
                default -> methodNotAllowed(method);
            };
            case VERSION -> request.isGet() ? version(request, path) : methodNotAllowed(method);
            case TARBALL -> request.isGet() ? tarball(request, path) : methodNotAllowed(method);
            case PACKAGE_REVISION -> switch (method) {
                case "PUT" -> unpublishVersions(request, path);
                case "DELETE" -> unpublishPackage(request, path);
                default -> methodNotAllowed(method);
            };
            case TARBALL_REVISION -> "DELETE".equals(method) ? deleteTarball(request, path) : methodNotAllowed(method);
            case DIST_TAGS -> request.isGet() ? distTags(request, path) : methodNotAllowed(method);
            case DIST_TAG -> switch (method) {
                case "PUT", "POST" -> setDistTag(request, path);
                case "DELETE" -> removeDistTag(request, path);
                default -> methodNotAllowed(method);
            };
            case LOGIN -> "PUT".equals(method) || "POST".equals(method) ? login(request, path) : notFound();
            case WEB_LOGIN -> NpmResponses.error(HttpStatus.NOT_FOUND,
                    "web login is not supported; run npm login --auth-type=legacy");
            case WHOAMI -> request.isGet() ? whoami(request) : methodNotAllowed(method);
            case PING -> request.isGet() ? json(request, NODES.objectNode()) : methodNotAllowed(method);
            case SEARCH -> request.isGet() ? search(request) : methodNotAllowed(method);
            case AUDIT -> NpmResponses.error(HttpStatus.NOT_FOUND, "security audits are not supported by this registry");
            case UNKNOWN -> notFound();
        };
    }

    // --- reads ---------------------------------------------------------------------------------

    private ResponseEntity<?> registryInfo(RepositoryRequest request) {
        ObjectNode info = NODES.objectNode();
        info.put("db_name", request.repository().getName());
        info.put("format", FORMAT);
        info.put("type", request.repository().getType().name().toLowerCase());
        return json(request, info);
    }

    private ResponseEntity<?> packument(RepositoryRequest request, NpmPath path) {
        ObjectNode doc = requirePackument(request, path);
        NpmPackument.rewriteTarballs(doc, request.baseUrl(), path.packageName());
        return json(request, doc);
    }

    private ResponseEntity<?> version(RepositoryRequest request, NpmPath path) {
        ObjectNode doc = requirePackument(request, path);
        ObjectNode version = NpmPackument.version(doc, path.version())
                .orElseThrow(() -> NpmError.notFound("version not found: " + path.version()));
        String number = NpmPackument.string(version, "version");
        NpmPackument.rewriteTarball(version, request.baseUrl(), path.packageName(), number != null ? number : path.version());
        return json(request, version);
    }

    private ResponseEntity<?> distTags(RepositoryRequest request, NpmPath path) {
        ObjectNode doc = requirePackument(request, path);
        return json(request, NpmPackument.distTags(doc));
    }

    private ResponseEntity<?> tarball(RepositoryRequest request, NpmPath path) {
        Optional<StoredContent> content = packuments.tarball(request.repository(), path);
        if (content.isEmpty()) {
            throw NpmError.notFound("tarball not found: " + path.fileName());
        }
        ResponseEntity<?> response = ContentResponses.stream(request, content.get());
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.get().asset());
        }
        return response;
    }

    private ObjectNode requirePackument(RepositoryRequest request, NpmPath path) {
        return packuments.load(request.repository(), path.packageName())
                .orElseThrow(() -> NpmError.notFound("package not found: " + path.packageName()));
    }

    private ResponseEntity<?> search(RepositoryRequest request) {
        int size = intParam(request, "size", NpmSearch.DEFAULT_SIZE, 1, NpmSearch.MAX_SIZE);
        int from = intParam(request, "from", 0, 0, Integer.MAX_VALUE);
        ObjectNode result = search.search(request.repository(), request.query("text"), from, size, request.baseUrl());
        return json(request, result);
    }

    // --- writes --------------------------------------------------------------------------------

    private ResponseEntity<?> publish(RepositoryRequest request, NpmPath path) throws IOException {
        NpmHostedWriter.requireHosted(request.repository());
        NpmPublishRequest publish = NpmPublishRequest.parse(packuments.mapper(), readBody(request));
        if (!publish.name().equals(path.packageName())) {
            throw NpmError.badRequest("package name '" + publish.name() + "' does not match the URL");
        }
        boolean isPublish = publish.hasAttachments();
        ObjectNode doc = writer.publish(request.repository(), request.baseUrl(), publish);
        return okWithRevision(isPublish ? HttpStatus.CREATED : HttpStatus.OK, doc);
    }

    private ResponseEntity<?> unpublishVersions(RepositoryRequest request, NpmPath path) throws IOException {
        NpmHostedWriter.requireHosted(request.repository());
        ObjectNode incoming = packuments.read(readBody(request));
        ObjectNode doc = writer.unpublishVersions(request.repository(), path.packageName(), incoming);
        return okWithRevision(HttpStatus.CREATED, doc);
    }

    private ResponseEntity<?> unpublishPackage(RepositoryRequest request, NpmPath path) {
        writer.unpublishPackage(request.repository(), path.packageName());
        return NpmResponses.ok(HttpStatus.OK);
    }

    private ResponseEntity<?> deleteTarball(RepositoryRequest request, NpmPath path) {
        writer.deleteTarball(request.repository(), path);
        return NpmResponses.ok(HttpStatus.OK);
    }

    private ResponseEntity<?> setDistTag(RepositoryRequest request, NpmPath path) throws IOException {
        NpmHostedWriter.requireHosted(request.repository());
        String version = versionFromBody(readBody(request));
        ObjectNode doc = writer.setDistTag(request.repository(), path.packageName(), path.tag(), version);
        return okWithRevision(HttpStatus.CREATED, doc);
    }

    private ResponseEntity<?> removeDistTag(RepositoryRequest request, NpmPath path) {
        ObjectNode doc = writer.removeDistTag(request.repository(), path.packageName(), path.tag());
        return okWithRevision(HttpStatus.OK, doc);
    }

    /** npm sends the version as a JSON string ({@code "1.2.3"}); be lenient and take a bare one as well. */
    private String versionFromBody(byte[] body) {
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (text.startsWith("\"")) {
            JsonNode node;
            try {
                node = packuments.mapper().readTree(body);
            } catch (tools.jackson.core.JacksonException e) {
                throw NpmError.badRequest("malformed JSON: " + e.getOriginalMessage());
            }
            text = node != null && node.isString() ? node.stringValue().trim() : "";
        }
        if (text.isEmpty()) {
            throw NpmError.badRequest("request body must be the version to tag");
        }
        return text;
    }

    // --- registry-level endpoints --------------------------------------------------------------

    private ResponseEntity<?> login(RepositoryRequest request, NpmPath path) throws IOException {
        ObjectNode body = packuments.read(readBody(request));
        String username = NpmPackument.string(body, "name");
        if (username == null || username.isBlank()) {
            username = path.username();
        }
        String password = NpmPackument.string(body, "password");
        if (username == null || password == null) {
            throw NpmError.badRequest("name and password are required");
        }
        GranaryPrincipal principal = authentication.authenticate(username, password)
                .orElseThrow(() -> new NpmError(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        User user = users.find(principal.username())
                .orElseThrow(() -> new NpmError(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        ApiTokenService.IssuedToken issued = tokens.issue(user, TOKEN_NAME, null);
        ObjectNode response = NODES.objectNode();
        response.put("ok", true);
        response.put("id", "org.couchdb.user:" + user.getUsername());
        response.put("rev", "1-" + (issued.token().getId() != null ? issued.token().getId() : UUID.randomUUID()));
        response.put("token", issued.secret());
        return NpmResponses.json(HttpStatus.CREATED, packuments.toBytes(response));
    }

    private ResponseEntity<?> whoami(RepositoryRequest request) {
        if (request.principal().anonymous()) {
            throw new NpmError(HttpStatus.UNAUTHORIZED, "You must be logged in to use npm whoami");
        }
        ObjectNode body = NODES.objectNode();
        body.put("username", request.principal().username());
        return json(request, body);
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResponseEntity<?> json(RepositoryRequest request, JsonNode node) {
        return NpmResponses.document(request, packuments.toBytes(node));
    }

    private ResponseEntity<?> okWithRevision(HttpStatus status, ObjectNode doc) {
        ObjectNode body = NODES.objectNode();
        body.put("ok", true);
        String id = NpmPackument.string(doc, NpmPackument.ID);
        if (id != null) {
            body.put("id", id);
        }
        String rev = NpmPackument.string(doc, NpmPackument.REV);
        if (rev != null) {
            body.put("rev", rev);
        }
        return NpmResponses.json(status, packuments.toBytes(body));
    }

    private static byte[] readBody(RepositoryRequest request) throws IOException {
        try (InputStream in = request.body()) {
            return in.readAllBytes();
        }
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
        return NpmResponses.error(HttpStatus.METHOD_NOT_ALLOWED, method + " is not supported here");
    }

    private static ResponseEntity<?> notFound() {
        return NpmResponses.error(HttpStatus.NOT_FOUND, "not found");
    }

    /** The format needs nothing beyond the generic rules; a proxy without a remote URL is already refused. */
    @Override
    public void validate(Repository repository) {
        // intentionally empty: see Javadoc
    }
}
