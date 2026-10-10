package dev.granary.format.hex;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.ApiToken;
import dev.granary.core.model.Repository;
import dev.granary.core.model.User;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.ApiTokenService;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.security.UserService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The part of the Hex HTTP API ({@code HEX_API_URL=<repository>/api}) a private repository needs: publishing,
 * reverting, retiring, documentation, package and release lookups for {@code mix hex.info} and
 * {@code mix hex.package search}, the current user and API keys. Answers in Erlang terms when the client asks
 * for {@code application/vnd.hex+erlang} (every {@code hex_core} client does) and JSON otherwise.
 */
@Component
public class HexApi {

    static final int MAX_BODY = 1024 * 1024;
    static final int PAGE_SIZE = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HexHosted hosted;
    private final GroupResolver groups;
    private final ApiTokenService tokens;
    private final UserService users;

    public HexApi(HexHosted hosted, GroupResolver groups, ApiTokenService tokens, UserService users) {
        this.hosted = hosted;
        this.groups = groups;
        this.tokens = tokens;
        this.users = users;
    }

    public ResponseEntity<?> handle(RepositoryRequest request, HexPath path) throws IOException {
        String method = request.method();
        return switch (path.kind()) {
            case API_ROOT -> request.isGet() ? HexResponses.body(request, HttpStatus.OK, root(request)) : notAllowed(method);
            case API_PUBLISH -> "POST".equals(method) ? publish(request, null) : notAllowed(method);
            case API_RELEASES -> switch (method) {
                case "POST" -> publish(request, path.name());
                case "GET", "HEAD" -> releases(request, path.name());
                default -> notAllowed(method);
            };
            case API_PACKAGES -> request.isGet() ? packages(request) : notAllowed(method);
            case API_PACKAGE -> request.isGet() ? pkg(request, path.name()) : notAllowed(method);
            case API_RELEASE -> switch (method) {
                case "GET", "HEAD" -> release(request, path.name(), path.version());
                case "DELETE" -> revert(request, path.name(), path.version());
                default -> notAllowed(method);
            };
            case API_RETIRE -> switch (method) {
                case "POST", "PUT" -> retire(request, path.name(), path.version());
                case "DELETE" -> unretire(request, path.name(), path.version());
                default -> notAllowed(method);
            };
            case API_RELEASE_DOCS -> switch (method) {
                case "POST", "PUT" -> publishDocs(request, path.name(), path.version());
                case "GET", "HEAD" -> docsRedirect(request, path.name(), path.version());
                case "DELETE" -> deleteDocs(request, path.name(), path.version());
                default -> notAllowed(method);
            };
            case API_USERS_ME -> request.isGet() ? me(request) : notAllowed(method);
            case API_AUTH -> request.isGet() ? auth(request) : notAllowed(method);
            case API_KEYS -> switch (method) {
                case "GET", "HEAD" -> listKeys(request);
                case "POST" -> createKey(request);
                case "DELETE" -> revokeAllKeys(request);
                default -> notAllowed(method);
            };
            case API_KEY -> switch (method) {
                case "GET", "HEAD" -> key(request, path.name());
                case "DELETE" -> revokeKey(request, path.name());
                default -> notAllowed(method);
            };
            default -> throw HexError.notFound("Page not found");
        };
    }

    // --- publishing ------------------------------------------------------------------------------

    private ResponseEntity<?> publish(RepositoryRequest request, String expectedName) throws IOException {
        Repository repo = requireHosted(request);
        byte[] body = readBody(request, HexHosted.MAX_TARBALL_SIZE);
        boolean replace = "true".equalsIgnoreCase(request.query("replace"));
        HexHosted.Published published = hosted.publish(repo, body, expectedName, replace, request.principal());
        return HexResponses.body(request, published.replaced() ? HttpStatus.OK : HttpStatus.CREATED,
                releaseJson(request, repo, published.state(), published.release()));
    }

    private ResponseEntity<?> revert(RepositoryRequest request, String name, String version) {
        Repository repo = requireHosted(request);
        hosted.revert(repo, name, version, request.principal());
        return HexResponses.noContent();
    }

    private ResponseEntity<?> retire(RepositoryRequest request, String name, String version) throws IOException {
        Repository repo = requireHosted(request);
        Map<String, Object> body = readMap(request);
        hosted.retire(repo, name, version, HexMetadata.text(body.get("reason")), HexMetadata.text(body.get("message")));
        return HexResponses.noContent();
    }

    private ResponseEntity<?> unretire(RepositoryRequest request, String name, String version) {
        Repository repo = requireHosted(request);
        hosted.unretire(repo, name, version);
        return HexResponses.noContent();
    }

    private ResponseEntity<?> publishDocs(RepositoryRequest request, String name, String version) throws IOException {
        Repository repo = requireHosted(request);
        byte[] body = readBody(request, HexHosted.MAX_DOCS_SIZE);
        hosted.publishDocs(repo, name, version, body);
        String location = request.baseUrl() + "/" + HexHosted.docsPath(name, version);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", location);
        ResponseEntity<?> response = HexResponses.body(request, HttpStatus.CREATED, result);
        return ResponseEntity.status(HttpStatus.CREATED)
                .headers(h -> {
                    h.addAll(response.getHeaders());
                    h.setLocation(URI.create(location));
                })
                .body(response.getBody());
    }

    private ResponseEntity<?> deleteDocs(RepositoryRequest request, String name, String version) {
        Repository repo = requireHosted(request);
        hosted.deleteDocs(repo, name, version);
        return HexResponses.noContent();
    }

    private ResponseEntity<?> docsRedirect(RepositoryRequest request, String name, String version) {
        Located located = locate(request, name);
        HexPackageState.Release release = located.state().release(version)
                .orElseThrow(() -> HexError.notFound("release " + name + " " + version + " does not exist"));
        if (!release.hasDocs() || hosted.docs(located.repo(), name, version).isEmpty()) {
            throw HexError.notFound("release " + name + " " + version + " has no documentation");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(request.baseUrl() + "/" + HexHosted.docsPath(name, version))).build();
    }

    // --- lookups ---------------------------------------------------------------------------------

    private Map<String, Object> root(RepositoryRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repository", repositoryName(request));
        body.put("package_url", request.baseUrl() + "/api/packages/{name}");
        body.put("repository_url", request.baseUrl());
        body.put("public_key_url", request.baseUrl() + "/public_key");
        return body;
    }

    private ResponseEntity<?> pkg(RepositoryRequest request, String name) {
        Located located = locate(request, name);
        return HexResponses.body(request, HttpStatus.OK, packageJson(request, located.repo(), located.state(), true));
    }

    private ResponseEntity<?> releases(RepositoryRequest request, String name) {
        Located located = locate(request, name);
        List<Object> list = new ArrayList<>();
        for (HexPackageState.Release r : descending(located.state())) {
            list.add(releaseJson(request, located.repo(), located.state(), r));
        }
        return HexResponses.body(request, HttpStatus.OK, list);
    }

    private ResponseEntity<?> release(RepositoryRequest request, String name, String version) {
        Located located = locate(request, name);
        HexPackageState.Release release = located.state().release(version)
                .orElseThrow(() -> HexError.notFound("release " + name + " " + version + " does not exist"));
        return HexResponses.body(request, HttpStatus.OK, releaseJson(request, located.repo(), located.state(), release));
    }

    /** {@code GET api/packages?search=…&sort=…&page=…}, what {@code mix hex.package search} asks. */
    private ResponseEntity<?> packages(RepositoryRequest request) {
        if (request.repository().isProxy()) {
            throw notProxied(request);
        }
        String search = Optional.ofNullable(request.query("search")).map(s -> s.trim().toLowerCase(Locale.ROOT)).orElse("");
        Map<String, Located> byName = new LinkedHashMap<>();
        for (Repository member : hostedMembers(request.repository())) {
            for (HexPackageState state : hosted.states(member)) {
                if (!state.releases().isEmpty() && matches(state, search)) {
                    byName.putIfAbsent(state.name(), new Located(member, state));
                }
            }
        }
        List<Located> sorted = new ArrayList<>(byName.values());
        String sort = Optional.ofNullable(request.query("sort")).orElse("name");
        if (sort.equals("inserted_at") || sort.equals("updated_at")) {
            Comparator<Located> order = Comparator.comparing(l -> sort.equals("inserted_at")
                    ? HexPackageState.parseInstant(l.state().insertedAt()) : HexPackageState.parseInstant(l.state().updatedAt()));
            sorted.sort(order.reversed());
        } else {
            sorted.sort(Comparator.comparing(l -> l.state().name()));
        }
        int page = 1;
        try {
            page = Math.max(1, Integer.parseInt(Optional.ofNullable(request.query("page")).orElse("1").trim()));
        } catch (NumberFormatException ignored) {
            // hex.pm treats a malformed page as the first one
        }
        List<Object> result = new ArrayList<>();
        int from = (page - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(sorted.size(), from + PAGE_SIZE); i++) {
            result.add(packageJson(request, sorted.get(i).repo(), sorted.get(i).state(), false));
        }
        return HexResponses.body(request, HttpStatus.OK, result);
    }

    private static boolean matches(HexPackageState state, String search) {
        if (search.isEmpty()) {
            return true;
        }
        String query = search.startsWith("name:") ? search.substring(5) : search;
        query = query.replace("*", "");
        if (state.name().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        String description = state.latest().map(r -> r.meta().description()).orElse(null);
        return !search.startsWith("name:") && description != null && description.toLowerCase(Locale.ROOT).contains(query);
    }

    private record Located(Repository repo, HexPackageState state) {
    }

    /** The hosted repository (the request's own, or a group's first hosted member) that has the package. */
    private Located locate(RepositoryRequest request, String name) {
        if (request.repository().isProxy()) {
            throw notProxied(request);
        }
        for (Repository member : hostedMembers(request.repository())) {
            Optional<HexPackageState> state = hosted.state(member, name).filter(s -> !s.releases().isEmpty());
            if (state.isPresent()) {
                return new Located(member, state.get());
            }
        }
        throw HexError.notFound("package " + name + " does not exist");
    }

    private List<Repository> hostedMembers(Repository repo) {
        if (repo.isHosted()) {
            return List.of(repo);
        }
        if (repo.isGroup()) {
            return groups.leafMembers(repo).stream().filter(Repository::isHosted).toList();
        }
        return List.of();
    }

    // --- JSON shapes (as hex.pm answers them) ----------------------------------------------------

    Map<String, Object> packageJson(RepositoryRequest request, Repository repo, HexPackageState state, boolean full) {
        String name = state.name();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("repository", repositoryName(request));
        body.put("url", request.baseUrl() + "/api/packages/" + name);
        body.put("html_url", null);
        body.put("docs_html_url", null);
        HexPackageState.Release latest = state.latest().orElse(null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("description", latest == null ? null : latest.meta().description());
        meta.put("licenses", latest == null ? List.of() : latest.meta().licenses());
        meta.put("links", latest == null ? Map.of() : new LinkedHashMap<>(latest.meta().links()));
        meta.put("maintainers", List.of());
        body.put("meta", meta);
        List<Object> releases = new ArrayList<>();
        long downloads = 0;
        Map<String, Object> retirements = new LinkedHashMap<>();
        for (HexPackageState.Release r : descending(state)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", r.version());
            entry.put("url", releaseUrl(request, name, r.version()));
            entry.put("has_docs", r.hasDocs());
            entry.put("inserted_at", r.insertedAt());
            releases.add(entry);
            downloads += downloads(repo, name, r.version());
            if (r.retirement() != null) {
                retirements.put(r.version(), retirementJson(r.retirement()));
            }
        }
        body.put("releases", releases);
        body.put("retirements", retirements);
        body.put("inserted_at", state.insertedAt());
        body.put("updated_at", state.updatedAt());
        body.put("latest_version", HexVersion.latest(state.versions()).orElse(null));
        body.put("latest_stable_version", HexVersion.latestStable(state.versions()).orElse(null));
        body.put("downloads", Map.of("all", downloads));
        if (full) {
            body.put("owners", List.of());
        }
        body.put("configs", configs(request, name, latest == null ? null : latest.version()));
        return body;
    }

    Map<String, Object> releaseJson(RepositoryRequest request, Repository repo, HexPackageState state,
                                    HexPackageState.Release release) {
        String name = state.name();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", release.version());
        body.put("url", releaseUrl(request, name, release.version()));
        body.put("package_url", request.baseUrl() + "/api/packages/" + name);
        body.put("html_url", null);
        body.put("docs_html_url", null);
        body.put("checksum", release.outerChecksum());
        body.put("inner_checksum", release.innerChecksum());
        body.put("has_docs", release.hasDocs());
        body.put("inserted_at", release.insertedAt());
        body.put("updated_at", release.updatedAt());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("app", release.meta().app());
        meta.put("build_tools", release.meta().buildTools());
        meta.put("elixir", release.meta().elixir());
        body.put("meta", meta);
        Map<String, Object> requirements = new LinkedHashMap<>();
        for (HexPackageState.Dependency d : release.dependencies()) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("app", d.app() == null ? d.name() : d.app());
            req.put("optional", d.optional());
            req.put("requirement", d.requirement());
            if (d.repository() != null) {
                req.put("repository", d.repository());
            }
            requirements.put(d.name(), req);
        }
        body.put("requirements", requirements);
        body.put("retirement", release.retirement() == null ? null : retirementJson(release.retirement()));
        body.put("publisher", release.publisher() == null ? null : Map.of("username", release.publisher()));
        body.put("downloads", downloads(repo, name, release.version()));
        body.put("configs", configs(request, name, release.version()));
        return body;
    }

    private static Map<String, Object> retirementJson(HexPackageState.Retirement retirement) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reason", retirement.reasonCode().key());
        map.put("message", retirement.message());
        return map;
    }

    private Map<String, Object> configs(RepositoryRequest request, String name, String version) {
        Map<String, Object> configs = new LinkedHashMap<>();
        String repository = repositoryName(request);
        String requirement = version == null ? ">= 0.0.0" : "~> " + HexVersion.parse(version)
                .map(v -> v.major() == 0 ? v.major() + "." + v.minor() + "." + v.patch() : v.major() + "." + v.minor())
                .orElse(version);
        configs.put("mix.exs", "{:" + name + ", \"" + requirement + "\", repo: \"" + repository + "\"}");
        configs.put("rebar.config", "{" + name + ", \"" + (version == null ? "" : version) + "\"}");
        configs.put("erlang.mk", "dep_" + name + " = hex " + (version == null ? "" : version));
        return configs;
    }

    private long downloads(Repository repo, String name, String version) {
        return hosted.tarball(repo, name, version).map(c -> c.asset().getDownloadCount()).orElse(0L);
    }

    private static List<HexPackageState.Release> descending(HexPackageState state) {
        List<HexPackageState.Release> list = new ArrayList<>(state.releases());
        list.sort(Comparator.comparing(HexPackageState.Release::version, HexVersion.ORDER).reversed());
        return list;
    }

    private static String releaseUrl(RepositoryRequest request, String name, String version) {
        return request.baseUrl() + "/api/packages/" + name + "/releases/" + version;
    }

    private static String repositoryName(RepositoryRequest request) {
        return HexSettings.repositoryName(request.repository());
    }

    // --- users and keys --------------------------------------------------------------------------

    private ResponseEntity<?> me(RepositoryRequest request) {
        User user = requireUser(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", user.getUsername());
        // what a user has not set is left out rather than sent as nil: rebar3_hex prints email and full_name
        // with a "private" default for absent keys, and fails on nil
        putIfPresent(body, "email", user.getEmail());
        putIfPresent(body, "full_name", user.getDisplayName());
        body.put("url", request.baseUrl() + "/api/users/" + user.getUsername());
        putIfPresent(body, "inserted_at", user.getCreatedAt() == null ? null : user.getCreatedAt().toString());
        putIfPresent(body, "updated_at", user.getUpdatedAt() == null ? null : user.getUpdatedAt().toString());
        body.put("organizations", List.of());
        body.put("packages", List.of());
        return HexResponses.body(request, HttpStatus.OK, body);
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    /** {@code GET api/auth?domain=…&resource=…}: 204 when the caller's credentials allow it, the check {@code hex_core} makes. */
    private ResponseEntity<?> auth(RepositoryRequest request) {
        GranaryPrincipal principal = request.principal();
        if (principal.anonymous()) {
            throw HexError.unauthorized("missing authentication information");
        }
        String domain = Optional.ofNullable(request.query("domain")).orElse("api");
        String resource = Optional.ofNullable(request.query("resource")).orElse("read");
        String repo = request.repository().getName();
        boolean allowed = switch (domain) {
            case "api" -> resource.equals("write") ? principal.canWrite(repo) : principal.canRead(repo);
            case "repository", "repositories", "docs" -> principal.canRead(repo);
            default -> false;
        };
        if (!allowed) {
            throw HexError.forbidden("key does not have the required permissions");
        }
        return HexResponses.noContent();
    }

    private ResponseEntity<?> listKeys(RepositoryRequest request) {
        User user = requireUser(request);
        List<Object> list = new ArrayList<>();
        for (ApiToken token : tokens.list(user)) {
            list.add(keyJson(request, token, null));
        }
        return HexResponses.body(request, HttpStatus.OK, list);
    }

    private ResponseEntity<?> key(RepositoryRequest request, String name) {
        User user = requireUser(request);
        ApiToken token = tokens.list(user).stream().filter(t -> t.getName().equals(name)).findFirst()
                .orElseThrow(() -> HexError.notFound("key " + name + " does not exist"));
        return HexResponses.body(request, HttpStatus.OK, keyJson(request, token, null));
    }

    /** {@code POST api/keys}: mints a Granary API token for the caller, the secret is only in this answer. */
    private ResponseEntity<?> createKey(RepositoryRequest request) throws IOException {
        User user = requireUser(request);
        Map<String, Object> body = readMap(request);
        String name = HexMetadata.text(body.get("name"));
        if (name == null || name.isBlank()) {
            throw HexError.validation("name", "can't be blank");
        }
        if (name.length() > 200) {
            throw HexError.validation("name", "should be at most 200 character(s)");
        }
        ApiTokenService.IssuedToken issued = tokens.issue(user, name.trim(), null);
        return HexResponses.body(request, HttpStatus.CREATED, keyJson(request, issued.token(), issued.secret()));
    }

    private ResponseEntity<?> revokeKey(RepositoryRequest request, String name) {
        User user = requireUser(request);
        List<ApiToken> matching = tokens.list(user).stream().filter(t -> t.getName().equals(name)).toList();
        if (matching.isEmpty()) {
            throw HexError.notFound("key " + name + " does not exist");
        }
        matching.forEach(t -> tokens.revoke(user, t.getId()));
        return HexResponses.noContent();
    }

    private ResponseEntity<?> revokeAllKeys(RepositoryRequest request) {
        User user = requireUser(request);
        tokens.list(user).forEach(t -> tokens.revoke(user, t.getId()));
        return HexResponses.noContent();
    }

    private Map<String, Object> keyJson(RepositoryRequest request, ApiToken token, String secret) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", token.getName());
        if (secret != null) {
            body.put("secret", secret);
        }
        body.put("authing_key", false);
        body.put("permissions", List.of(Map.of("domain", "api", "resource", "write")));
        body.put("revoked_at", null);
        body.put("inserted_at", token.getCreatedAt() == null ? Instant.now().toString() : token.getCreatedAt().toString());
        body.put("updated_at", token.getCreatedAt() == null ? Instant.now().toString() : token.getCreatedAt().toString());
        body.put("url", request.baseUrl() + "/api/keys/" + token.getName());
        return body;
    }

    private User requireUser(RepositoryRequest request) {
        if (request.principal().anonymous()) {
            throw HexError.unauthorized("missing authentication information");
        }
        return users.find(request.principal().username())
                .orElseThrow(() -> HexError.unauthorized("user " + request.principal().username() + " does not exist"));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private Repository requireHosted(RepositoryRequest request) {
        Repository repo = request.repository();
        if (repo.isHosted()) {
            return repo;
        }
        Optional<String> member = repo.isGroup()
                ? groups.leafMembers(repo).stream().filter(Repository::isHosted).map(Repository::getName).findFirst()
                : Optional.empty();
        throw HexHosted.notHosted(repo, member);
    }

    private static HexError notProxied(RepositoryRequest request) {
        return HexError.notFound("'" + request.repository().getName() + "' is a proxy repository; the Hex HTTP API is not "
                + "proxied, only the repository documents and tarballs are");
    }

    private static byte[] readBody(RepositoryRequest request, int limit) throws IOException {
        try (InputStream in = request.body()) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new HexError(HttpStatus.CONTENT_TOO_LARGE, "request body exceeds " + (limit / (1024 * 1024)) + " MiB");
            }
            return bytes;
        }
    }

    /** A request body as a map: Erlang terms when the client sent {@code application/vnd.hex+erlang}, else JSON. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readMap(RepositoryRequest request) throws IOException {
        byte[] body = readBody(request, MAX_BODY);
        if (body.length == 0) {
            return Map.of();
        }
        String contentType = Optional.ofNullable(request.header(HttpHeaders.CONTENT_TYPE)).orElse("");
        if (contentType.contains(HexResponses.ERLANG) || (body[0] & 0xFF) == Etf.VERSION) {
            Map<String, Object> map;
            try {
                map = Etf.asMap(Etf.decode(body));
            } catch (Etf.DecodeException e) {
                throw HexError.badRequest("malformed Erlang term body: " + e.getMessage());
            }
            if (map == null) {
                throw HexError.badRequest("request body must be a map");
            }
            return map;
        }
        try {
            Object value = JSON.readValue(body, Object.class);
            if (!(value instanceof Map<?, ?> map)) {
                throw HexError.badRequest("request body must be a JSON object");
            }
            return (Map<String, Object>) map;
        } catch (JacksonException e) {
            throw HexError.badRequest("malformed JSON body: " + e.getOriginalMessage());
        }
    }

    private static ResponseEntity<?> notAllowed(String method) {
        throw HexError.methodNotAllowed(method + " is not supported here");
    }

}
