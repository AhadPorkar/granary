package dev.granary.format.conan;

import dev.granary.core.GranaryException;
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
import dev.granary.core.model.RepositoryType;
import dev.granary.core.model.User;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.security.UserService;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Conan remote protocol: the v1 ping that announces the server's capabilities and the v2 API with revisions
 * that Conan 2.x (and Conan 1.x with revisions enabled) speaks to {@code conan_server}, Artifactory and ConanCenter.
 *
 * <p>Hosted repositories accept {@code conan upload}; proxies cache ConanCenter (or a private server) so that once
 * a recipe and its binaries have been installed through Granary they stay available offline; groups merge the
 * revision lists of their members under one remote URL. Login tokens are Granary API tokens, so
 * {@code conan remote login} works with a password or with an existing token.
 */
@Component
public class ConanFormatHandler implements FormatHandler {

    public static final String FORMAT = "conan";
    public static final String CONANCENTER = "https://center2.conan.io";
    public static final String CAPABILITIES_HEADER = "X-Conan-Server-Capabilities";
    /**
     * What {@code v1/ping} announces. {@code revisions} is what Conan 2 requires; {@code only_v2} makes Conan 1.x
     * without revisions stop with "only works with revisions enabled" instead of trying the v1 API.
     */
    public static final String CAPABILITIES = "complex_search,checksum_deploy,revisions,only_v2";
    public static final String TOKEN_TTL_SETTING = "conan.tokenTtlHours";
    public static final int DEFAULT_TOKEN_TTL_HOURS = 24;
    static final String TOKEN_NAME = "conan";
    static final String CHALLENGE = "Basic realm=\"Granary\", charset=\"UTF-8\"";

    private final StorageService storage;
    private final ApiTokenService tokens;
    private final UserService users;
    private final ObjectMapper json;
    private final ConanResolver resolver;
    private final ConanHostedWriter writer;

    public ConanFormatHandler(StorageService storage, ProxyService proxy, UpstreamClient upstreamClient,
                              GroupResolver groups, ApiTokenService tokens, UserService users,
                              GranaryProperties properties, ObjectMapper json) {
        this.storage = storage;
        this.tokens = tokens;
        this.users = users;
        this.json = json;
        this.resolver = new ConanResolver(storage, proxy, upstreamClient, groups, new ConanUpstreamAuth(upstreamClient), json);
        this.writer = new ConanHostedWriter(storage, properties.getDataDir().resolve("tmp").resolve(FORMAT));
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Conan (C/C++)";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("conancenter", FORMAT, CONANCENTER, "Proxy of ConanCenter"),
                RepositoryTemplate.hosted("conan-hosted", FORMAT, "Your own Conan packages"),
                RepositoryTemplate.group("conan-group", FORMAT, List.of("conan-hosted", "conancenter"),
                        "Single remote for conan install, upload and search"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return List.of(
                SettingDescriptor.integer(TOKEN_TTL_SETTING, "Login token lifetime (hours)", DEFAULT_TOKEN_TTL_HOURS,
                        "How long the API tokens issued by 'conan remote login' stay valid; 0 means they never expire."),
                SettingDescriptor.string(ConanUpstreamAuth.SETTING, "Upstream authentication", ConanUpstreamAuth.TOKEN,
                        "With a remote username and password: 'token' exchanges them once for a bearer token at "
                                + "v2/users/authenticate (conan_server, Artifactory), 'basic' sends them with every request.")
                        .forTypes(RepositoryType.PROXY));
    }

    @Override
    public void validate(Repository repository) {
        Optional<String> ttl = repository.setting(TOKEN_TTL_SETTING);
        if (ttl.isPresent()) {
            int hours;
            try {
                hours = Integer.parseInt(ttl.get());
            } catch (NumberFormatException e) {
                hours = -1;
            }
            if (hours < 0) {
                throw GranaryException.badRequest("repository.conan.tokenTtl",
                        TOKEN_TTL_SETTING + " must be a whole number of hours (0 = never expires)");
            }
        }
        Optional<String> auth = repository.setting(ConanUpstreamAuth.SETTING);
        if (auth.isPresent() && !auth.get().equalsIgnoreCase(ConanUpstreamAuth.TOKEN)
                && !auth.get().equalsIgnoreCase(ConanUpstreamAuth.BASIC)) {
            throw GranaryException.badRequest("repository.conan.upstreamAuth",
                    ConanUpstreamAuth.SETTING + " must be 'token' or 'basic'");
        }
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        Repository repo = context.repository();
        String hosted = switch (repo.getType()) {
            case HOSTED -> repo.getName();
            case GROUP -> resolver.hostedMember(repo).orElse(null);
            case PROXY -> null;
        };
        return ConanSnippets.build(context, hosted);
    }

    /**
     * The derived documents ({@code revisions}, {@code latest}, {@code files}, {@code search}) and the files below a
     * revision's {@code metadata/} folder are mutable; every other file belongs to a content-addressed revision.
     */
    @Override
    public boolean isMetadataPath(String path) {
        ConanPath parsed = ConanPath.parse(path);
        if (parsed.isMetadata()) {
            return true;
        }
        if (parsed.isFile()) {
            return parsed.file().startsWith(ConanLayout.METADATA_FOLDER);
        }
        return ConanLayout.isMutableFile(path);
    }

    /** The ping and the login endpoints answer for themselves; everything else is a read or a write. */
    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        return switch (ConanPath.parse(request.path()).kind()) {
            case PING, AUTHENTICATE, CHECK_CREDENTIALS -> AccessLevel.NONE;
            default -> request.isGet() ? AccessLevel.READ : AccessLevel.WRITE;
        };
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        ConanPath path = ConanPath.parse(request.path());
        if (hasEncodedSeparator(request)) {
            // the dispatcher decodes %2F into a real separator, which would turn one segment into two
            return ConanResponses.error(HttpStatus.BAD_REQUEST, "path.invalid",
                    "Encoded '/' or '\\' in a Conan API path: " + path.path());
        }
        try {
            return dispatch(request, path);
        } catch (ConanError e) {
            return ConanResponses.error(e);
        }
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, ConanPath path) throws IOException {
        String method = request.method();
        boolean read = request.isGet();
        return switch (path.kind()) {
            case ROOT -> read ? info(request) : methodNotAllowed(method, path.kind());
            case PING -> read ? ping() : methodNotAllowed(method, path.kind());
            case AUTHENTICATE -> read || "POST".equals(method) ? authenticate(request) : methodNotAllowed(method, path.kind());
            case CHECK_CREDENTIALS -> read ? checkCredentials(request) : methodNotAllowed(method, path.kind());
            case SEARCH -> read ? search(request) : methodNotAllowed(method, path.kind());
            case RECIPE -> "DELETE".equals(method) ? deleteRecipe(request, path)
                    : read ? notFound("Use /latest, /revisions or /search below " + path.reference()) : methodNotAllowed(method, path.kind());
            case RECIPE_LATEST -> read ? recipeLatest(request, path) : methodNotAllowed(method, path.kind());
            case RECIPE_REVISIONS -> switch (method) {
                case "GET", "HEAD" -> recipeRevisions(request, path);
                case "DELETE" -> deleteRecipe(request, path);
                default -> methodNotAllowed(method, path.kind());
            };
            case RECIPE_REVISION -> "DELETE".equals(method) ? deleteRevision(request, path)
                    : read ? notFound("Use /files below the revision") : methodNotAllowed(method, path.kind());
            case RECIPE_FILES -> read ? recipeFiles(request, path) : methodNotAllowed(method, path.kind());
            case RECIPE_FILE -> switch (method) {
                case "GET", "HEAD" -> recipeFile(request, path);
                case "PUT" -> upload(request, path);
                default -> methodNotAllowed(method, path.kind());
            };
            case PACKAGE_SEARCH -> read ? packageSearch(request, path) : methodNotAllowed(method, path.kind());
            case PACKAGES -> "DELETE".equals(method) ? deletePackages(request, path)
                    : read ? notFound("Use /search below the revision to list binaries") : methodNotAllowed(method, path.kind());
            case PACKAGE -> "DELETE".equals(method) ? deletePackage(request, path)
                    : read ? notFound("Use /latest or /revisions below the package") : methodNotAllowed(method, path.kind());
            case PACKAGE_LATEST -> read ? packageLatest(request, path) : methodNotAllowed(method, path.kind());
            case PACKAGE_REVISIONS -> switch (method) {
                case "GET", "HEAD" -> packageRevisions(request, path);
                case "DELETE" -> deletePackage(request, path);
                default -> methodNotAllowed(method, path.kind());
            };
            case PACKAGE_REVISION -> "DELETE".equals(method) ? deletePackageRevision(request, path)
                    : read ? notFound("Use /files below the package revision") : methodNotAllowed(method, path.kind());
            case PACKAGE_FILES -> read ? packageFiles(request, path) : methodNotAllowed(method, path.kind());
            case PACKAGE_FILE -> switch (method) {
                case "GET", "HEAD" -> packageFile(request, path);
                case "PUT" -> upload(request, path);
                default -> methodNotAllowed(method, path.kind());
            };
            case V1 -> notFound("The Conan v1 API is not supported; use Conan 2.x or Conan 1.x with revisions enabled");
            case UNKNOWN -> notFound("Unknown Conan API path: " + path.path());
            case INVALID -> ConanResponses.error(HttpStatus.BAD_REQUEST, "path.invalid",
                    "Illegal character or segment in Conan API path: " + path.path());
        };
    }

    /**
     * Whether the raw request path carries a percent-encoded slash or backslash. Conan never sends one (names cannot
     * contain them and metadata sub-folders are sent with plain slashes), and decoded they would shift every segment
     * after them, so {@code v2/conans/a%2Fb/1.0/_/…} would address a different reference than it spells.
     */
    static boolean hasEncodedSeparator(RepositoryRequest request) {
        String uri = request.http() == null ? null : request.http().getRequestURI();
        if (uri == null) {
            return false;
        }
        String lower = uri.toLowerCase(Locale.ROOT);
        return lower.contains("%2f") || lower.contains("%5c");
    }

    // --- server-level endpoints ------------------------------------------------------------------

    private ResponseEntity<?> info(RepositoryRequest request) {
        ObjectNode info = json.createObjectNode();
        info.put("name", request.repository().getName());
        info.put("format", FORMAT);
        info.put("type", request.repository().getType().name().toLowerCase(Locale.ROOT));
        info.put("api", "v2");
        info.put("capabilities", CAPABILITIES);
        return document(request, info);
    }

    private static ResponseEntity<?> ping() {
        return ResponseEntity.ok().header(CAPABILITIES_HEADER, CAPABILITIES).build();
    }

    /** Basic credentials in, a token out: the client stores it and sends it as {@code Authorization: Bearer}. */
    private ResponseEntity<?> authenticate(RepositoryRequest request) {
        GranaryPrincipal principal = request.principal();
        if (principal.anonymous()) {
            return unauthorized("Send your Granary username and password (or API token) as Basic credentials");
        }
        if (request.isHead()) {
            // a HEAD has no body to carry a token, so it must not leave a new one behind either
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).build();
        }
        Optional<String> presented = presentedToken(request.header(HttpHeaders.AUTHORIZATION));
        if (presented.isPresent()) {
            // already a valid API token (the credentials filter checked it): hand it back instead of minting another
            return ConanResponses.text(HttpStatus.OK, presented.get());
        }
        User user = users.find(principal.username())
                .orElseThrow(() -> ConanError.unauthorized("User '" + principal.username() + "' does not exist"));
        int ttlHours = tokenTtlHours(request.repository());
        Instant expiresAt = ttlHours > 0 ? Instant.now().plus(Duration.ofHours(ttlHours)) : null;
        ApiTokenService.IssuedToken issued = tokens.issue(user, TOKEN_NAME, expiresAt);
        return ConanResponses.text(HttpStatus.OK, issued.secret());
    }

    /** The API token sent as the Basic password ({@code conan remote login granary alice -p gry_…}), if any. */
    static Optional<String> presentedToken(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        String[] parts = authorization.trim().split("\\s+", 2);
        if (parts.length != 2 || !parts[0].equalsIgnoreCase("Basic")) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(parts[1].trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            String password = colon < 0 ? "" : decoded.substring(colon + 1);
            return ApiTokenService.looksLikeToken(password) ? Optional.of(password) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private ResponseEntity<?> checkCredentials(RepositoryRequest request) {
        GranaryPrincipal principal = request.principal();
        if (principal.anonymous()) {
            return unauthorized("Authentication required");
        }
        return ConanResponses.text(HttpStatus.OK, principal.username());
    }

    static int tokenTtlHours(Repository repo) {
        try {
            return Math.max(0, Integer.parseInt(repo.setting(TOKEN_TTL_SETTING, Integer.toString(DEFAULT_TOKEN_TTL_HOURS))));
        } catch (NumberFormatException e) {
            return DEFAULT_TOKEN_TTL_HOURS;
        }
    }

    private ResponseEntity<?> search(RepositoryRequest request) {
        String pattern = request.query("q");
        if (pattern != null && pattern.length() > ConanSearch.MAX_PATTERN_LENGTH) {
            throw ConanError.badRequest("search.patternTooLong",
                    "Search patterns are limited to " + ConanSearch.MAX_PATTERN_LENGTH + " characters");
        }
        String ignoreCase = request.query("ignorecase");
        boolean ignore = ignoreCase == null || !(ignoreCase.equalsIgnoreCase("false") || ignoreCase.equals("0"));
        List<String> results = resolver.search(request.repository(), pattern, ignore)
                .orElseThrow(() -> ConanError.notFound("The search is not cached and the upstream of '"
                        + request.repository().getName() + "' cannot be asked"));
        return document(request, ConanSearch.resultsDocument(results));
    }

    // --- recipe reads ----------------------------------------------------------------------------

    private ResponseEntity<?> recipeRevisions(RepositoryRequest request, ConanPath path) {
        List<ConanRevisions.Entry> entries = resolver.recipeRevisions(request.repository(), path.reference())
                .orElseThrow(() -> recipeNotFound(path));
        return document(request, ConanRevisions.revisionsDocument(entries));
    }

    private ResponseEntity<?> recipeLatest(RepositoryRequest request, ConanPath path) {
        ConanRevisions.Entry latest = resolver.recipeLatest(request.repository(), path.reference())
                .orElseThrow(() -> recipeNotFound(path));
        return document(request, ConanRevisions.latestDocument(latest));
    }

    private ResponseEntity<?> recipeFiles(RepositoryRequest request, ConanPath path) {
        List<String> files = resolver.recipeFiles(request.repository(), path.reference(), path.recipeRevision())
                .orElseThrow(() -> revisionNotFound(path));
        return document(request, ConanRevisions.filesDocument(files));
    }

    private ResponseEntity<?> recipeFile(RepositoryRequest request, ConanPath path) {
        StoredContent content = resolver.recipeFile(request.repository(), path.reference(), path.recipeRevision(), path.file())
                .orElseThrow(() -> ConanError.notFound("File not found: " + path.file() + " of " + revisionName(path)));
        return respond(request, content);
    }

    // --- package reads ---------------------------------------------------------------------------

    private ResponseEntity<?> packageSearch(RepositoryRequest request, ConanPath path) {
        Repository repo = request.repository();
        String rrev = path.recipeRevision();
        if (rrev == null) {
            rrev = resolver.recipeLatest(repo, path.reference()).orElseThrow(() -> recipeNotFound(path)).revision();
        }
        String revision = rrev;
        ObjectNode packages = resolver.packageSearch(repo, path.reference(), revision)
                .orElseThrow(() -> ConanError.notFound("Recipe revision not found: '" + path.reference() + "#" + revision + "'"));
        return document(request, packages);
    }

    private ResponseEntity<?> packageRevisions(RepositoryRequest request, ConanPath path) {
        List<ConanRevisions.Entry> entries = resolver.packageRevisions(request.repository(), path.reference(),
                path.recipeRevision(), path.packageId()).orElseThrow(() -> packageNotFound(path));
        return document(request, ConanRevisions.revisionsDocument(entries));
    }

    private ResponseEntity<?> packageLatest(RepositoryRequest request, ConanPath path) {
        ConanRevisions.Entry latest = resolver.packageLatest(request.repository(), path.reference(),
                path.recipeRevision(), path.packageId()).orElseThrow(() -> packageNotFound(path));
        return document(request, ConanRevisions.latestDocument(latest));
    }

    private ResponseEntity<?> packageFiles(RepositoryRequest request, ConanPath path) {
        List<String> files = resolver.packageFiles(request.repository(), path.reference(), path.recipeRevision(),
                path.packageId(), path.packageRevision()).orElseThrow(() -> packageRevisionNotFound(path));
        return document(request, ConanRevisions.filesDocument(files));
    }

    private ResponseEntity<?> packageFile(RepositoryRequest request, ConanPath path) {
        StoredContent content = resolver.packageFile(request.repository(), path.reference(), path.recipeRevision(),
                        path.packageId(), path.packageRevision(), path.file())
                .orElseThrow(() -> ConanError.notFound("File not found: " + path.file() + " of " + packageRevisionName(path)));
        return respond(request, content);
    }

    private ResponseEntity<?> respond(RepositoryRequest request, StoredContent content) {
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && ifNoneMatch.trim().equals("*")) {
            // "*" matches any current representation, and the file exists
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag("\"" + content.asset().getBlobSha256() + "\"").build();
        }
        ResponseEntity<?> response = ContentResponses.stream(request, content);
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.asset());
        }
        return response;
    }

    // --- writes ----------------------------------------------------------------------------------

    private ResponseEntity<?> upload(RepositoryRequest request, ConanPath path) throws IOException {
        Repository repo = requireHosted(request.repository(), "upload to");
        ConanReference ref = path.reference();
        String stored = path.isPackageRoute()
                ? ConanLayout.packagePath(ref, path.recipeRevision(), path.packageId(), path.packageRevision(), path.file())
                : ConanLayout.exportPath(ref, path.recipeRevision(), path.file());
        return writer.store(request, stored, ConanLayout.contentType(path.file()), ConanComponents.coordinates(ref));
    }

    private ResponseEntity<?> deleteRecipe(RepositoryRequest request, ConanPath path) {
        return deletePrefix(request, ConanLayout.recipePrefix(path.reference()), () -> recipeNotFound(path));
    }

    private ResponseEntity<?> deleteRevision(RepositoryRequest request, ConanPath path) {
        return deletePrefix(request, ConanLayout.revisionPrefix(path.reference(), path.recipeRevision()),
                () -> revisionNotFound(path));
    }

    private ResponseEntity<?> deletePackages(RepositoryRequest request, ConanPath path) {
        return deletePrefix(request, ConanLayout.packagesPrefix(path.reference(), path.recipeRevision()),
                () -> ConanError.notFound("No binaries stored for " + revisionName(path)));
    }

    private ResponseEntity<?> deletePackage(RepositoryRequest request, ConanPath path) {
        return deletePrefix(request, ConanLayout.packagePrefix(path.reference(), path.recipeRevision(), path.packageId()),
                () -> packageNotFound(path));
    }

    private ResponseEntity<?> deletePackageRevision(RepositoryRequest request, ConanPath path) {
        return deletePrefix(request, ConanLayout.packageRevisionPrefix(path.reference(), path.recipeRevision(),
                path.packageId(), path.packageRevision()), () -> packageRevisionNotFound(path));
    }

    private ResponseEntity<?> deletePrefix(RepositoryRequest request, String prefix,
                                           java.util.function.Supplier<ConanError> notFound) {
        Repository repo = requireHosted(request.repository(), "remove packages from");
        if (writer.deletePrefix(repo, prefix) == 0) {
            throw notFound.get();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Groups and proxies refuse writes with 403 and the error document: the Conan client prints the message of a 403
     * ("Permission denied for user: …: 403: …, upload to its hosted member '…' instead") and stops, whereas for a 405
     * it only reports "405 Client Error" for every file and retries each one.
     */
    private Repository requireHosted(Repository repo, String action) {
        return switch (repo.getType()) {
            case HOSTED -> repo;
            case GROUP -> throw ConanError.forbidden("repository.readOnly",
                    "Repository '" + repo.getName() + "' is a group and read-only; " + action
                            + resolver.hostedMember(repo).map(m -> " its hosted member '" + m + "' instead")
                            .orElse(" a hosted repository instead"));
            case PROXY -> throw ConanError.forbidden("repository.readOnly",
                    "Repository '" + repo.getName() + "' is a proxy of " + repo.getRemoteUrl() + " and read-only; "
                            + action + " a hosted repository instead");
        };
    }

    // --- helpers ---------------------------------------------------------------------------------

    private ResponseEntity<?> document(RepositoryRequest request, JsonNode node) {
        return ConanResponses.json(request, json.writeValueAsBytes(node));
    }

    private static ResponseEntity<?> unauthorized(String message) {
        ResponseEntity<?> error = ConanResponses.error(HttpStatus.UNAUTHORIZED, "auth.required", message);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error.getBody());
    }

    private static ResponseEntity<?> notFound(String message) {
        return ConanResponses.notFound(message);
    }

    /** A 405 that names, in {@code Allow}, the methods the route does support. */
    private static ResponseEntity<?> methodNotAllowed(String method, ConanPath.Kind kind) {
        ResponseEntity<?> error = ConanResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                method + " is not supported on this Conan API path");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, allowedMethods(kind))
                .contentType(MediaType.APPLICATION_JSON)
                .body(error.getBody());
    }

    static String allowedMethods(ConanPath.Kind kind) {
        return switch (kind) {
            case AUTHENTICATE -> "GET, HEAD, POST";
            case RECIPE_FILE, PACKAGE_FILE -> "GET, HEAD, PUT";
            case RECIPE, RECIPE_REVISIONS, RECIPE_REVISION, PACKAGES, PACKAGE, PACKAGE_REVISIONS, PACKAGE_REVISION ->
                    "GET, HEAD, DELETE";
            default -> "GET, HEAD";
        };
    }

    private static ConanError recipeNotFound(ConanPath path) {
        return ConanError.notFound("Recipe not found: '" + path.reference() + "'");
    }

    private static ConanError revisionNotFound(ConanPath path) {
        return ConanError.notFound("Recipe revision not found: '" + revisionName(path) + "'");
    }

    private static ConanError packageNotFound(ConanPath path) {
        return ConanError.notFound("Binary package not found: '" + revisionName(path) + ":" + path.packageId() + "'");
    }

    private static ConanError packageRevisionNotFound(ConanPath path) {
        return ConanError.notFound("Package revision not found: '" + packageRevisionName(path) + "'");
    }

    private static String revisionName(ConanPath path) {
        return path.reference() + "#" + path.recipeRevision();
    }

    private static String packageRevisionName(ConanPath path) {
        return revisionName(path) + ":" + path.packageId() + "#" + path.packageRevision();
    }
}
