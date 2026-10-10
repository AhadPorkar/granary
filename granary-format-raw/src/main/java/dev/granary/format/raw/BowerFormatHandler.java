package dev.granary.format.raw;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Bower registry: name-to-git-URL lookups. Proxies cache the public registry's answers (the git clones
 * themselves go to the URL the registry returns); hosted repositories are a registry of their own with
 * {@code POST packages} to register, {@code GET packages}, {@code GET packages/search/<q>} and
 * {@code DELETE packages/<name>}; groups merge the lists of their members.
 */
@org.springframework.stereotype.Component
public class BowerFormatHandler extends RawFormatHandler {

    public static final String FORMAT = "bower";

    static final List<String> DEFAULT_METADATA = List.of("packages", "packages/*", "packages/search/*", "packages/search/**");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public BowerFormatHandler(StorageService storage, ProxyService proxy, GroupResolver groups, GranaryProperties properties) {
        super(storage, proxy, groups, properties);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Bower";
    }

    @Override
    protected List<String> defaultMetadataPatterns() {
        return DEFAULT_METADATA;
    }

    /** Registry entries are metadata documents, not components. */
    @Override
    protected StorageService.ComponentCoordinates coordinates(String path) {
        return BowerRegistry.packageName(path).isPresent() ? null : RawComponents.generic(path);
    }

    @Override
    protected Optional<ResponseEntity<?>> route(RepositoryRequest request, String path) throws IOException {
        if (path.equals(BowerRegistry.PACKAGES)) {
            return Optional.of(switch (request.method()) {
                case "GET", "HEAD" -> list(request);
                case "POST" -> register(request);
                default -> RawResponses.methodNotAllowed(request.method(), RawResponses.ALLOW_READ_POST);
            });
        }
        Optional<String> query = BowerRegistry.searchQuery(path);
        if (query.isPresent()) {
            return Optional.of(request.isGet() ? search(request, query.get())
                    : RawResponses.methodNotAllowed(request.method(), RawResponses.ALLOW_READ));
        }
        return Optional.empty();
    }

    private ResponseEntity<?> list(RepositoryRequest request) throws IOException {
        Optional<List<BowerRegistry.Entry>> entries = entries(request.repository());
        return entries.isPresent()
                ? document(request, BowerRegistry.json(entries.get()), MediaType.APPLICATION_JSON_VALUE, true)
                : ContentResponses.notFound();
    }

    private ResponseEntity<?> search(RepositoryRequest request, String query) throws IOException {
        Optional<List<BowerRegistry.Entry>> entries = search(request.repository(), query);
        return entries.isPresent()
                ? document(request, BowerRegistry.json(entries.get()), MediaType.APPLICATION_JSON_VALUE, true)
                : ContentResponses.notFound();
    }

    /**
     * All entries of a repository: stored ones for hosted, the upstream list for proxies, the union for groups.
     * Empty when no source could answer (a proxy whose upstream is unreachable and nothing is cached), so the
     * request ends as a 404, which the dispatcher turns into a 503 while the upstream is down.
     */
    Optional<List<BowerRegistry.Entry>> entries(Repository repo) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> {
                List<BowerRegistry.Entry> result = new ArrayList<>();
                for (Asset asset : storage.list(repo, BowerRegistry.PACKAGES + "/")) {
                    if (BowerRegistry.packageName(asset.getPath()).isEmpty()) {
                        continue;
                    }
                    Optional<StoredContent> content = storage.find(repo, asset.getPath());
                    if (content.isPresent()) {
                        BowerRegistry.parse(content.get().readAllBytes()).ifPresent(result::add);
                    }
                }
                yield Optional.of(BowerRegistry.merge(List.of(result)));
            }
            case PROXY -> {
                Optional<StoredContent> content = fetch(repo, BowerRegistry.PACKAGES);
                yield content.isPresent() ? Optional.of(BowerRegistry.parseList(content.get().readAllBytes())) : Optional.empty();
            }
            case GROUP -> merged(repo, this::entries);
        };
    }

    Optional<List<BowerRegistry.Entry>> search(Repository repo, String query) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> Optional.of(BowerRegistry.search(entries(repo).orElse(List.of()), query));
            case PROXY -> {
                Optional<StoredContent> content = fetch(repo, BowerRegistry.SEARCH_PREFIX + query);
                yield content.isPresent() ? Optional.of(BowerRegistry.parseList(content.get().readAllBytes())) : Optional.empty();
            }
            case GROUP -> merged(repo, member -> search(member, query));
        };
    }

    /** One registry answer per member; a group answers as long as one member could. */
    private Optional<List<BowerRegistry.Entry>> merged(Repository group, MemberQuery query) throws IOException {
        List<List<BowerRegistry.Entry>> lists = new ArrayList<>();
        for (Repository member : groups.leafMembers(group)) {
            query.apply(member).ifPresent(lists::add);
        }
        return lists.isEmpty() ? Optional.empty() : Optional.of(BowerRegistry.merge(lists));
    }

    @FunctionalInterface
    private interface MemberQuery {
        Optional<List<BowerRegistry.Entry>> apply(Repository member) throws IOException;
    }

    /** {@code bower register <name> <url>}: a form post, or JSON for scripted use. */
    private ResponseEntity<?> register(RepositoryRequest request) throws IOException {
        Repository repo = request.repository();
        if (!repo.isHosted()) {
            return readOnly(repo);
        }
        String name = request.query("name");
        String url = request.query("url");
        String contentType = request.header("Content-Type");
        if ((name == null || url == null) && contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            try (InputStream in = request.body()) {
                JsonNode body = JSON.readTree(in.readAllBytes());
                name = name != null ? name : body.path("name").asString(null);
                url = url != null ? url : body.path("url").asString(null);
            } catch (RuntimeException e) {
                return RawResponses.badRequest("bower.malformed", "The request body is not valid JSON");
            }
        }
        if (!BowerRegistry.validName(name)) {
            return RawResponses.badRequest("bower.invalidName", "A package name is required: letters, digits, '.', '_' and '-'");
        }
        if (!BowerRegistry.validUrl(url)) {
            return RawResponses.badRequest("bower.invalidUrl", "A git clone URL is required (git://, https://…git, git+ssh://, user@host:path)");
        }
        BowerRegistry.Entry entry = new BowerRegistry.Entry(name, url.trim());
        String path = BowerRegistry.packagePath(name);
        // the check and the write happen under one lock so two registrations of a name cannot both win
        return locks.withLock(pathKey(repo, path), () -> {
            Optional<StoredContent> existing = storage.find(repo, path);
            if (existing.isPresent() && !repo.isAllowRedeploy()) {
                Optional<BowerRegistry.Entry> stored = BowerRegistry.parse(existing.get().readAllBytes());
                if (stored.isEmpty() || !stored.get().equals(entry)) {
                    return ContentResponses.error(HttpStatus.CONFLICT, "bower.nameTaken",
                            "Package '" + entry.name() + "' is already registered with a different URL");
                }
            } else {
                storage.store(repo, path, new ByteArrayInputStream(BowerRegistry.json(entry)), MediaType.APPLICATION_JSON_VALUE,
                        Asset.Origin.UPLOADED, true);
            }
            return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(BowerRegistry.json(entry));
        });
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(RepositoryTemplate.proxy("bower-registry", FORMAT, "https://registry.bower.io/",
                "Proxy of the public Bower registry (name to git URL lookups)"));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext ctx) {
        String url = ctx.url();
        String auth = ctx.username() + ":" + ctx.token();
        return List.of(
                ConnectSnippet.file("bowerrc", "Bower: .bowerrc", "json", ".bowerrc",
                        "{\n"
                                + "  \"registry\": \"" + url + "\",\n"
                                + "  \"storage\": { \"packages\": \".bower-cache/packages\", \"registry\": \".bower-cache/registry\" }\n"
                                + "}\n"
                                + "// the registry only maps names to git URLs; bower then clones that URL itself,\n"
                                + "// so git access to GitHub (or your own git server) is still required"),
                ConnectSnippet.shell("install", "Bower: install through this registry",
                        "npm install -g bower\n"
                                + "bower install jquery --config.registry=" + url),
                ConnectSnippet.shell("register", "Bower: register your own package (hosted repositories)",
                        "bower register my-widget https://git.example.com/ui/my-widget.git --config.registry=" + url + "\n"
                                + "# or without the client:\n"
                                + "curl -fsS -u " + auth + " -X POST " + url + "/packages -d name=my-widget "
                                + "-d url=https://git.example.com/ui/my-widget.git"),
                ConnectSnippet.shell("lookup", "Lookup, search and unregister",
                        "curl -fsS " + url + "/packages/jquery\n"
                                + "curl -fsS " + url + "/packages/search/jquery\n"
                                + "curl -fsS -u " + auth + " -X DELETE " + url + "/packages/my-widget"));
    }
}
