package dev.granary.format.rubygems;

import dev.granary.core.GranaryException;
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
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.security.UserService;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The RubyGems protocol as {@code gem} (RubyGems 3.x) and Bundler speak it: the compact index ({@code versions},
 * {@code info/<gem>}, {@code names}), the legacy Marshal indexes ({@code specs.4.8.gz}, {@code latest_specs.4.8.gz},
 * {@code prerelease_specs.4.8.gz}, {@code quick/Marshal.4.8/*.gemspec.rz}), the gems themselves, the push, yank and
 * sign-in API, and the read side of the JSON API.
 *
 * <p>Hosted repositories accept {@code gem push} and generate every index from what was pushed; proxies cache
 * rubygems.org so that, once installed through them, gems stay installable without internet; groups merge their
 * members' indexes under one source URL.
 */
@org.springframework.stereotype.Component
public class RubygemsFormatHandler implements FormatHandler {

    public static final String FORMAT = "rubygems";
    public static final String DEFAULT_REMOTE = "https://rubygems.org/";

    /** Whether {@code gem signin} may exchange a username and password for a new API token. */
    public static final String SIGNIN_SETTING = "rubygems.signin";
    /** The largest {@code .gem} {@code gem push} may upload, in MiB. */
    public static final String MAX_GEM_SIZE_SETTING = "rubygems.maxGemSizeMb";
    static final int DEFAULT_MAX_GEM_SIZE_MB = 1024;

    private final StorageService storage;
    private final RubygemsHosted hosted;
    private final RubygemsIndexes indexes;
    private final RubygemsApi api;
    private final GroupResolver groups;
    private final UserService users;
    private final ApiTokenService tokens;
    private final Path tempDir;

    public RubygemsFormatHandler(StorageService storage, RubygemsHosted hosted, RubygemsIndexes indexes, RubygemsApi api,
                                 GroupResolver groups, UserService users, ApiTokenService tokens,
                                 DerivedDocuments derived) {
        this.storage = storage;
        this.hosted = hosted;
        this.indexes = indexes;
        this.api = api;
        this.groups = groups;
        this.users = users;
        this.tokens = tokens;
        this.tempDir = derived.tempDir();
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "RubyGems";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("rubygems-proxy", FORMAT, DEFAULT_REMOTE, "Proxy of rubygems.org"),
                RepositoryTemplate.hosted("rubygems-hosted", FORMAT, "Your own gems, published with gem push"),
                RepositoryTemplate.group("rubygems-group", FORMAT, List.of("rubygems-hosted", "rubygems-proxy"),
                        "Single source URL for gem and Bundler"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return List.of(
                SettingDescriptor.bool(SIGNIN_SETTING, "Allow gem signin", true,
                        "Let gem signin exchange a username and password sent to this repository for a new API token"),
                SettingDescriptor.integer(MAX_GEM_SIZE_SETTING, "Largest gem (MiB)", DEFAULT_MAX_GEM_SIZE_MB,
                                "The largest .gem file gem push may upload to this repository")
                        .forTypes(RepositoryType.HOSTED));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return RubygemsSnippets.of(context);
    }

    @Override
    public void validate(Repository repository) {
        Optional<String> signin = repository.setting(SIGNIN_SETTING);
        if (signin.isPresent() && !signin.get().equalsIgnoreCase("true") && !signin.get().equalsIgnoreCase("false")) {
            throw GranaryException.badRequest("repository.rubygems.signin.invalid", SIGNIN_SETTING + " must be true or false");
        }
        Optional<String> maxSize = repository.setting(MAX_GEM_SIZE_SETTING);
        if (maxSize.isPresent()) {
            try {
                if (Integer.parseInt(maxSize.get().trim()) <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw GranaryException.badRequest("repository.rubygems.maxGemSize.invalid",
                        MAX_GEM_SIZE_SETTING + " must be a positive number of MiB");
            }
        }
    }

    /** {@code gem signin} authenticates against {@code api/v1/api_key} itself; everything else is a normal read or write. */
    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        if (GemPath.parse(request.path()).kind() == GemPath.Kind.API_KEY) {
            return AccessLevel.NONE;
        }
        return FormatHandler.super.requiredAccess(request);
    }

    @Override
    public boolean isMetadataPath(String path) {
        return GemPath.parse(path).isMetadata();
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        GemPath path = GemPath.parse(request.path());
        try {
            return dispatch(request, path);
        } catch (GemError e) {
            return isApi(path) ? GemResponses.text(e) : GemResponses.json(e);
        }
    }

    private static boolean isApi(GemPath path) {
        return switch (path.kind()) {
            case API_GEMS, API_YANK, API_UNYANK, API_KEY, API_DEPENDENCIES -> true;
            default -> false;
        };
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, GemPath path) throws IOException {
        String method = request.method();
        Repository repo = request.repository();
        return switch (path.kind()) {
            case INVALID -> ContentResponses.error(HttpStatus.BAD_REQUEST, "path.invalid", "Illegal path");
            case UNKNOWN -> ContentResponses.notFound();
            case ROOT -> request.isGet() ? landingPage(request) : methodNotAllowed(method);
            case VERSIONS -> request.isGet() ? compact(request, indexes.versions(repo), "The versions index") : methodNotAllowed(method);
            case NAMES -> request.isGet() ? compact(request, indexes.names(repo), "The names index") : methodNotAllowed(method);
            case INFO -> request.isGet() ? info(request, path.name()) : methodNotAllowed(method);
            case SPECS, LATEST_SPECS, PRERELEASE_SPECS -> request.isGet()
                    ? stored(request, indexes.specs(repo, path.path()), GemResponses.OCTET_STREAM, false, path.path())
                    : methodNotAllowed(method);
            case QUICK_SPEC -> request.isGet()
                    ? stored(request, indexes.file(repo, path.path()), GemResponses.OCTET_STREAM, true, path.path())
                    : methodNotAllowed(method);
            case GEM -> request.isGet() ? gem(request, path) : switch (method) {
                case "DELETE", "PUT" -> GemResponses.text(HttpStatus.METHOD_NOT_ALLOWED,
                        "Gems are immutable here; remove a version from the indexes with gem yank, or delete the "
                                + "component in the web UI.\n");
                default -> methodNotAllowed(method);
            };
            case API_GEMS -> "POST".equals(method) ? push(request) : methodNotAllowed(method);
            case API_YANK -> "DELETE".equals(method) ? yank(request, true) : methodNotAllowed(method);
            case API_UNYANK -> "PUT".equals(method) ? yank(request, false) : methodNotAllowed(method);
            case API_KEY -> apiKey(request);
            case API_GEM -> {
                if (!request.isGet()) {
                    yield methodNotAllowed(method);
                }
                yield api.gem(repo, path.name(), request.baseUrl())
                        .<ResponseEntity<?>>map(doc -> GemResponses.json(request, RubygemsApi.bytes(doc)))
                        .orElseGet(() -> ContentResponses.notFound("This rubygem could not be found."));
            }
            case API_VERSIONS -> {
                if (!request.isGet()) {
                    yield methodNotAllowed(method);
                }
                yield api.versions(repo, path.name())
                        .<ResponseEntity<?>>map(doc -> GemResponses.json(request, RubygemsApi.bytes(doc)))
                        .orElseGet(() -> ContentResponses.notFound("This rubygem could not be found."));
            }
            case API_LATEST -> {
                if (!request.isGet()) {
                    yield methodNotAllowed(method);
                }
                yield api.latestVersion(repo, path.name())
                        .<ResponseEntity<?>>map(v -> GemResponses.json(request, RubygemsApi.latestDocument(v)))
                        .orElseGet(() -> ContentResponses.notFound("This rubygem could not be found."));
            }
            case API_DEPENDENCIES -> GemResponses.text(HttpStatus.NOT_FOUND,
                    "The dependency API is not provided; RubyGems 3 and Bundler 2 use the compact index (versions, "
                            + "info/<gem>) instead.\n");
        };
    }

    // --- reads --------------------------------------------------------------------------------------

    private ResponseEntity<?> landingPage(RepositoryRequest request) {
        Repository repo = request.repository();
        String source = request.baseUrl() + "/";
        StringBuilder text = new StringBuilder()
                .append("RubyGems repository '").append(repo.getName()).append("' (")
                .append(repo.getType().name().toLowerCase(Locale.ROOT)).append(")\n\n")
                .append("gem sources --add ").append(source).append('\n')
                .append("gem install --clear-sources --source ").append(source).append(" <gem>\n")
                .append("Gemfile: source \"").append(source).append("\"\n");
        if (repo.isHosted()) {
            text.append("gem push --host ").append(request.baseUrl()).append(" <gem>-<version>.gem\n");
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(GemResponses.TEXT_UTF8))
                .contentLength(body.length);
        return request.isHead() ? builder.build() : builder.body(body);
    }

    private static ResponseEntity<?> compact(RepositoryRequest request, Optional<StoredContent> content, String what) {
        return content.<ResponseEntity<?>>map(c -> GemResponses.compactIndex(request, c))
                .orElseGet(() -> ContentResponses.notFound(what + " is not available"));
    }

    private ResponseEntity<?> info(RepositoryRequest request, String name) throws IOException {
        Optional<RubygemsIndexes.Info> info = indexes.info(request.repository(), name);
        if (info.isEmpty()) {
            return ContentResponses.notFound("This rubygem could not be found.");
        }
        RubygemsIndexes.Info i = info.get();
        return i.stored() != null ? GemResponses.compactIndex(request, i.stored()) : GemResponses.compactIndex(request, i.text());
    }

    private static ResponseEntity<?> stored(RepositoryRequest request, Optional<StoredContent> content, String contentType,
                                            boolean immutable, String path) {
        return content.<ResponseEntity<?>>map(c -> GemResponses.file(request, c, contentType, immutable))
                .orElseGet(() -> ContentResponses.notFound("'" + path + "' not found"));
    }

    private ResponseEntity<?> gem(RepositoryRequest request, GemPath path) {
        Optional<StoredContent> content = indexes.file(request.repository(), path.path());
        if (content.isEmpty()) {
            return ContentResponses.notFound("Gem '" + path.fullName() + "' not found");
        }
        ResponseEntity<?> response = GemResponses.file(request, content.get(), GemResponses.OCTET_STREAM, true);
        if (!request.isHead() && response.getStatusCode() == HttpStatus.OK) {
            storage.recordDownload(content.get().asset());
        }
        return response;
    }

    // --- writes -------------------------------------------------------------------------------------

    private ResponseEntity<?> push(RepositoryRequest request) throws IOException {
        Repository repo = request.repository();
        requireHosted(repo, "push to", "gem push");
        long limit = maxGemBytes(repo);
        Files.createDirectories(tempDir);
        Path upload = Files.createTempFile(tempDir, "push-", ".gem");
        try {
            long size = spool(request.body(), upload, limit);
            if (size == 0) {
                throw GemError.badRequest("The request has no body; gem push sends the .gem file as the request body.");
            }
            RubygemsHosted.Pushed pushed = hosted.push(repo, upload, request.principal().username());
            GemRecord r = pushed.record();
            return GemResponses.text(HttpStatus.OK, "Successfully registered gem: " + r.name() + " (" + r.versionKey() + ")");
        } finally {
            Files.deleteIfExists(upload);
        }
    }

    private static long spool(InputStream body, Path target, long limit) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = body; OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > limit) {
                    throw GemError.tooLarge("The gem is larger than the " + (limit / (1024 * 1024))
                            + " MiB this repository accepts (setting " + MAX_GEM_SIZE_SETTING + ").");
                }
                out.write(buffer, 0, n);
            }
        }
        return total;
    }

    private static long maxGemBytes(Repository repo) {
        int mb = DEFAULT_MAX_GEM_SIZE_MB;
        try {
            mb = Integer.parseInt(repo.setting(MAX_GEM_SIZE_SETTING, Integer.toString(DEFAULT_MAX_GEM_SIZE_MB)).trim());
        } catch (NumberFormatException e) {
            // validate() refuses such values; fall back to the default for rows written before it existed
        }
        return Math.max(1, mb) * 1024L * 1024L;
    }

    private ResponseEntity<?> yank(RepositoryRequest request, boolean yank) throws IOException {
        Repository repo = request.repository();
        requireHosted(repo, yank ? "yank from" : "unyank in", "gem yank");
        Map<String, String> form = form(request);
        GemRecord r = hosted.yank(repo, form.get("gem_name"), form.get("version"), form.get("platform"), yank);
        return GemResponses.text(HttpStatus.OK, (yank ? "Successfully deleted gem: " : "Successfully reinstated gem: ")
                + r.name() + " (" + r.versionKey() + ")");
    }

    /** Uploads, yanks and sign-ins only make sense on hosted repositories; a group names the member to use. */
    private void requireHosted(Repository repo, String verb, String command) {
        if (repo.isHosted()) {
            return;
        }
        String hint = "";
        if (repo.isGroup()) {
            hint = groups.leafMembers(repo).stream().filter(Repository::isHosted).findFirst()
                    .map(member -> "; use its hosted member '" + member.getName() + "' (" + command + " --host "
                            + "<server>/repository/" + member.getName() + ")")
                    .orElse("; it has no hosted member");
        }
        throw GemError.readOnly("'" + repo.getName() + "' is a " + repo.getType().name().toLowerCase(Locale.ROOT)
                + " repository, you cannot " + verb + " it" + hint + ".");
    }

    /**
     * {@code gem signin} posts the user's credentials (HTTP Basic) and expects an API key as the plain-text body; the
     * key is a new Granary API token. {@code PUT} (changing a key's scopes) is accepted as a no-op, because Granary
     * tokens carry the user's privileges rather than scopes.
     */
    private ResponseEntity<?> apiKey(RepositoryRequest request) throws IOException {
        String method = request.method();
        if (!List.of("GET", "HEAD", "POST", "PUT").contains(method)) {
            return methodNotAllowed(method);
        }
        GranaryPrincipal principal = request.principal();
        if (principal.anonymous()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Granary\"")
                    .contentType(org.springframework.http.MediaType.parseMediaType(GemResponses.TEXT_UTF8))
                    .body("HTTP Basic: Access denied. Sign in with your Granary username and password.\n");
        }
        if (!request.repository().settingEnabled(SIGNIN_SETTING, true)) {
            throw GemError.forbidden("gem signin is disabled for this repository; create an API token in the Granary "
                    + "web UI and put it into ~/.gem/credentials.");
        }
        Map<String, String> form = form(request);
        if ("PUT".equals(method)) {
            String key = form.get("api_key");
            if (key == null || key.isBlank()) {
                throw GemError.badRequest("The api_key parameter is missing.");
            }
            return GemResponses.text(HttpStatus.OK, key.strip());
        }
        if (request.isHead()) {
            return ResponseEntity.ok().build();
        }
        User user = users.find(principal.username())
                .orElseThrow(() -> GemError.unauthorized("User '" + principal.username() + "' does not exist."));
        String name = form.getOrDefault("name", "").strip();
        if (name.isEmpty()) {
            name = "gem signin";
        }
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        ApiTokenService.IssuedToken issued = tokens.issue(user, name, null);
        return GemResponses.text(HttpStatus.OK, issued.secret());
    }

    /**
     * The form fields of a request: the query string, plus an {@code application/x-www-form-urlencoded} body, which
     * the servlet container only parses for {@code POST} ({@code gem yank} sends its fields in a {@code DELETE}).
     */
    static Map<String, String> form(RepositoryRequest request) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        String contentType = request.header(HttpHeaders.CONTENT_TYPE);
        boolean formBody = contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
        if (formBody && !"POST".equals(request.method())) {
            byte[] body = request.body().readNBytes(64 * 1024);
            parseInto(new String(body, StandardCharsets.UTF_8), fields);
        }
        request.http().getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0) {
                fields.putIfAbsent(k, v[0]);
            }
        });
        return fields;
    }

    static void parseInto(String encoded, Map<String, String> fields) {
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            try {
                String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                fields.putIfAbsent(key, value);
            } catch (IllegalArgumentException e) {
                // a malformed escape: ignore the field, the caller reports what is missing
            }
        }
    }

    private static ResponseEntity<?> methodNotAllowed(String method) {
        return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                method + " is not supported here by RubyGems repositories");
    }
}
