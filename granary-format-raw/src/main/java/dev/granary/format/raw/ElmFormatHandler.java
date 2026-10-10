package dev.granary.format.raw;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The Elm package site. Proxies cache {@code package.elm-lang.org}: the registry documents are re-validated,
 * each release's {@code endpoint.json} is rewritten so its {@code url} points at the repository's own
 * {@code github/<author>/<project>/zip/<version>} route, which proxies GitHub's immutable zipball. Hosted
 * repositories take the files of a release under {@code packages/<author>/<project>/<version>/} and generate
 * {@code all-packages}, {@code all-packages/since/<n>} and, from an uploaded zip, {@code endpoint.json}.
 *
 * <p>The Elm compiler hard-codes the package site's host name and POSTs {@code all-packages}; both GET and
 * POST are accepted here and the registry documents are fetched upstream with GET.
 */
@org.springframework.stereotype.Component
public class ElmFormatHandler extends RawFormatHandler {

    public static final String FORMAT = "elm";

    static final List<String> DEFAULT_METADATA = List.of(
            "all-packages", "all-packages/since/*", "search.json", "packages/*/*/releases.json", "packages/*/*/*/endpoint.json");

    /**
     * The releases of a group in the order the group first listed them, oldest first, for one membership. The
     * compiler keeps a count of the releases it knows and asks for {@code all-packages/since/<count>}, so the list
     * may only ever grow at its end: a release one member gains later is appended, whichever member it is in.
     */
    private record GroupLog(String members, List<String> releases, Set<String> known) {
    }

    private final Map<String, GroupLog> groupLogs = new ConcurrentHashMap<>();

    public ElmFormatHandler(StorageService storage, ProxyService proxy, GroupResolver groups, GranaryProperties properties) {
        super(storage, proxy, groups, properties);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Elm packages";
    }

    @Override
    protected List<String> defaultMetadataPatterns() {
        return DEFAULT_METADATA;
    }

    /** The compiler POSTs the registry documents; those posts are reads. */
    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        if (request.isGet() || (request.method().equals("POST") && isRegistryDocument(request.path()))) {
            return AccessLevel.READ;
        }
        return AccessLevel.WRITE;
    }

    private static boolean isRegistryDocument(String path) {
        return path.equals(ElmPackages.ALL_PACKAGES) || ElmPackages.sinceIndex(path).isPresent() || path.equals("search.json");
    }

    @Override
    protected StorageService.ComponentCoordinates coordinates(String path) {
        return ElmPackages.coordinates(path).orElseGet(() -> RawComponents.generic(path));
    }

    @Override
    protected URI upstreamUri(Repository proxyRepository, String path) {
        return ElmPackages.zipRoute(path).map(ElmPackages::githubZipball).orElse(null);
    }

    @Override
    protected Optional<byte[]> transform(RepositoryRequest request, String path, StoredContent content) throws IOException {
        if (ElmPackages.isEndpoint(path)) {
            return Optional.of(ElmPackages.rewriteEndpoint(content.readAllBytes(), request.baseUrl()));
        }
        return Optional.empty();
    }

    /** A hosted release whose zip was uploaded but no endpoint.json: generated with the zip's SHA-1. */
    @Override
    protected Optional<StoredContent> generated(Repository hosted, String path) throws IOException {
        if (!ElmPackages.isEndpoint(path)) {
            return Optional.empty();
        }
        String directory = RawPaths.directory(path);
        for (Asset asset : storage.list(hosted, directory)) {
            if (asset.getPath().endsWith(".zip") && RawPaths.directory(asset.getPath()).equals(directory)) {
                Optional<StoredContent> zip = storage.find(hosted, asset.getPath());
                if (zip.isPresent()) {
                    byte[] endpoint = ElmPackages.endpoint(asset.getPath(), Checksums.of(zip.get(), "SHA-1"));
                    return Optional.of(storeGenerated(hosted, path, endpoint, MediaType.APPLICATION_JSON_VALUE));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    protected Optional<ResponseEntity<?>> route(RepositoryRequest request, String path) throws IOException {
        boolean read = request.isGet() || request.method().equals("POST");
        if (path.equals(ElmPackages.ALL_PACKAGES)) {
            return Optional.of(read ? allPackages(request) : RawResponses.methodNotAllowed(request.method(), RawResponses.ALLOW_READ_POST));
        }
        Optional<Integer> since = ElmPackages.sinceIndex(path);
        if (since.isPresent()) {
            return Optional.of(read ? since(request, since.get()) : RawResponses.methodNotAllowed(request.method(), RawResponses.ALLOW_READ_POST));
        }
        if (path.equals("search.json") && request.method().equals("POST")) {
            Optional<StoredContent> content = resolve(request.repository(), path);
            return Optional.of(content.isPresent() ? respond(request, path, content.get()) : ContentResponses.notFound());
        }
        return Optional.empty();
    }

    private ResponseEntity<?> allPackages(RepositoryRequest request) throws IOException {
        Optional<Map<String, List<String>>> packages = allPackagesOf(request.repository());
        return packages.isPresent()
                ? document(request, ElmPackages.allPackages(packages.get()), MediaType.APPLICATION_JSON_VALUE, true)
                : ContentResponses.notFound();
    }

    private ResponseEntity<?> since(RepositoryRequest request, int known) throws IOException {
        Repository repo = request.repository();
        if (repo.isProxy()) {
            Optional<StoredContent> content = fetch(repo, ElmPackages.SINCE_PREFIX + known);
            return content.isPresent() ? respond(request, ElmPackages.SINCE_PREFIX + known, content.get()) : ContentResponses.notFound();
        }
        Optional<List<String>> releases = releasesNewestFirst(repo);
        return releases.isPresent()
                ? document(request, ElmPackages.since(releases.get(), known), MediaType.APPLICATION_JSON_VALUE, true)
                : ContentResponses.notFound();
    }

    /**
     * The package list of a repository; empty when no source could answer (a proxy whose upstream is unreachable
     * with nothing cached), so the request ends as a 404 that the dispatcher turns into a 503.
     */
    Optional<Map<String, List<String>>> allPackagesOf(Repository repo) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> {
                Map<String, List<String>> result = new TreeMap<>();
                for (ElmPackages.Release release : hostedReleases(repo).keySet()) {
                    result.computeIfAbsent(release.packageName(), k -> new ArrayList<>()).add(release.version());
                }
                yield Optional.of(ElmPackages.mergeAllPackages(List.of(result)));
            }
            case PROXY -> {
                Optional<StoredContent> content = fetch(repo, ElmPackages.ALL_PACKAGES);
                yield content.isPresent() ? Optional.of(ElmPackages.parseAllPackages(content.get().readAllBytes())) : Optional.empty();
            }
            case GROUP -> {
                // from the same release list as since/<n>, so the count a client derives from it matches
                Optional<List<String>> releases = groupReleasesOldestFirst(repo);
                if (releases.isEmpty()) {
                    yield Optional.empty();
                }
                Map<String, List<String>> result = new TreeMap<>();
                for (String id : releases.get()) {
                    int at = id.lastIndexOf('@');
                    if (at > 0) {
                        result.computeIfAbsent(id.substring(0, at), k -> new ArrayList<>()).add(id.substring(at + 1));
                    }
                }
                yield Optional.of(ElmPackages.mergeAllPackages(List.of(result)));
            }
        };
    }

    /** Every release of a hosted repository or group, newest first ({@code author/project@version}). */
    Optional<List<String>> releasesNewestFirst(Repository repo) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> {
                List<Map.Entry<ElmPackages.Release, Instant>> releases = new ArrayList<>(hostedReleases(repo).entrySet());
                releases.sort(Map.Entry.<ElmPackages.Release, Instant>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(e -> e.getKey().id(), Comparator.reverseOrder()));
                List<String> ids = new ArrayList<>(releases.size());
                for (Map.Entry<ElmPackages.Release, Instant> entry : releases) {
                    ids.add(entry.getKey().id());
                }
                yield Optional.of(ids);
            }
            case PROXY -> {
                Optional<StoredContent> content = fetch(repo, ElmPackages.SINCE_PREFIX + 0);
                yield content.isPresent() ? Optional.of(ElmPackages.parseSince(content.get().readAllBytes())) : Optional.empty();
            }
            case GROUP -> groupReleasesOldestFirst(repo).map(oldestFirst -> {
                List<String> newestFirst = new ArrayList<>(oldestFirst);
                Collections.reverse(newestFirst);
                return newestFirst;
            });
        };
    }

    /**
     * The group's release log, oldest first: every release of every member that answers, the ones not listed
     * before appended at the end (each member's in its own order, members in group order). Empty when no member
     * could answer. A membership change starts a new log.
     */
    Optional<List<String>> groupReleasesOldestFirst(Repository group) throws IOException {
        List<String> current = new ArrayList<>();
        StringBuilder membership = new StringBuilder();
        boolean answered = false;
        for (Repository member : groups.leafMembers(group)) {
            membership.append(member.getName()).append('\u0000');
            Optional<List<String>> releases = releasesNewestFirst(member);
            if (releases.isPresent()) {
                answered = true;
                List<String> oldestFirst = new ArrayList<>(releases.get());
                Collections.reverse(oldestFirst);
                current.addAll(oldestFirst);
            }
        }
        if (!answered) {
            return Optional.empty();
        }
        String members = membership.toString();
        return Optional.of(locks.withLock(group.getName() + "\u0000elm-releases", () -> {
            GroupLog log = groupLogs.get(group.getName());
            if (log == null || !log.members().equals(members)) {
                log = new GroupLog(members, new ArrayList<>(), new HashSet<>());
                groupLogs.put(group.getName(), log);
            }
            for (String id : current) {
                if (log.known().add(id)) {
                    log.releases().add(id);
                }
            }
            return List.copyOf(log.releases());
        }));
    }

    /** Releases with the time their first file was stored. */
    private Map<ElmPackages.Release, Instant> hostedReleases(Repository repo) {
        Map<ElmPackages.Release, Instant> releases = new LinkedHashMap<>();
        for (Asset asset : storage.list(repo, "packages/")) {
            Optional<ElmPackages.Release> release = ElmPackages.release(asset.getPath());
            if (release.isEmpty()) {
                continue;
            }
            Instant created = asset.getCreatedAt() != null ? asset.getCreatedAt() : Instant.EPOCH;
            releases.merge(release.get(), created, (a, b) -> a.isBefore(b) ? a : b);
        }
        return releases;
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(RepositoryTemplate.proxy("elm-proxy", FORMAT, "https://package.elm-lang.org/",
                "Proxy of the Elm package site (registry documents and GitHub release zips)"));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext ctx) {
        String url = ctx.url();
        String auth = ctx.username() + ":" + ctx.token();
        return List.of(
                ConnectSnippet.file("note", "How the Elm compiler reaches this repository", "text", null,
                        "The Elm compiler has no registry setting: it hard-codes https://package.elm-lang.org.\n"
                                + "To use this repository, resolve that host name to a TLS reverse proxy in front of Granary\n"
                                + "(hosts file, internal DNS or a tool such as elm-package-proxy), or build with a wrapper that\n"
                                + "fetches the registry documents and zips from the URLs below."),
                ConnectSnippet.file("nginx", "nginx: reverse proxy impersonating package.elm-lang.org", "text", "/etc/nginx/conf.d/elm.conf",
                        "server {\n"
                                + "    listen 443 ssl;\n"
                                + "    server_name package.elm-lang.org;\n"
                                + "    ssl_certificate     /etc/ssl/elm/fullchain.pem;   # issued by your internal CA\n"
                                + "    ssl_certificate_key /etc/ssl/elm/privkey.pem;\n"
                                + "    location / {\n"
                                + "        # keep Granary's own Host: endpoint.json then points the zip downloads straight at Granary\n"
                                + "        proxy_pass " + url + "/;\n"
                                + "    }\n"
                                + "}"),
                ConnectSnippet.shell("hosts", "Point the host name at the reverse proxy",
                        "echo \"10.0.0.5 package.elm-lang.org\" | sudo tee -a /etc/hosts\n"
                                + "# the machine running elm must also trust the CA that issued the reverse proxy's certificate"),
                ConnectSnippet.shell("curl", "What the compiler asks for (works without any override)",
                        "curl -fsS -X POST " + url + "/all-packages/since/0 | head -c 300\n"
                                + "curl -fsS " + url + "/packages/elm/core/1.0.5/endpoint.json\n"
                                + "# the endpoint's url points back at this repository, which proxies GitHub's zip:\n"
                                + "curl -fsSL -o core.zip " + url + "/github/elm/core/zip/1.0.5"),
                ConnectSnippet.shell("publish", "Hosted: publish a release",
                        "# elm.json, docs.json (elm make --docs=docs.json), README.md and the source zip of the tag\n"
                                + "for f in elm.json docs.json README.md; do\n"
                                + "  curl -fsS -u " + auth + " -T $f " + url + "/packages/acme/widgets/1.0.0/$f\n"
                                + "done\n"
                                + "curl -fsS -u " + auth + " -T widgets-1.0.0.zip " + url + "/packages/acme/widgets/1.0.0/widgets-1.0.0.zip\n"
                                + "# endpoint.json (url + sha1 hash) and all-packages are generated"));
    }
}
