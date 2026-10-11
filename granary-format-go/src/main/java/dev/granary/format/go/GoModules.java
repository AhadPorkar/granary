package dev.granary.format.go;

import dev.granary.core.format.ContentResolver;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The read side of the protocol for every repository type: hosted repositories answer from storage, proxies
 * from their cache and upstream, groups from their members (version lists merged, files from the first member
 * that has them). Checksum-database requests are passed through proxies the same way.
 */
@Component
public class GoModules {

    public static final String SUMDB_URL = "go.sumdbUrl";
    public static final String DEFAULT_SUMDB = "https://sum.golang.org";
    public static final String SUMDB_NAME = "go.sumdbName";
    public static final String DEFAULT_SUMDB_NAME = "sum.golang.org";

    private static final Logger log = LoggerFactory.getLogger(GoModules.class);

    private final StorageService storage;
    private final ProxyService proxy;
    private final GroupResolver groups;
    private final ContentResolver resolver;
    private final ObjectMapper mapper;

    public GoModules(StorageService storage, ProxyService proxy, GroupResolver groups, ContentResolver resolver,
                     ObjectMapper mapper) {
        this.storage = storage;
        this.proxy = proxy;
        this.groups = groups;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    // --- version lists -------------------------------------------------------------------------------

    /**
     * The versions {@code <module>/@v/list} shows: tagged semantic versions in precedence order, pseudo-versions
     * left out as proxy.golang.org leaves them out. Empty when no member knows the module at all (a 404 for
     * the go command, which then tries the next shorter path or the next proxy).
     */
    public Optional<List<String>> list(Repository repo, String escapedModule) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> {
                List<String> stored = storedVersions(repo, escapedModule);
                yield stored.isEmpty() ? Optional.empty() : Optional.of(GoSemver.sorted(listed(stored)));
            }
            case PROXY -> {
                Optional<StoredContent> upstream = proxy.fetch(repo, GoPath.listPath(escapedModule), FetchOptions.index());
                if (upstream.isEmpty()) {
                    yield Optional.empty();
                }
                yield Optional.of(GoSemver.sorted(parseList(upstream.get().readAllBytes())));
            }
            case GROUP -> {
                List<String> union = new ArrayList<>();
                boolean known = false;
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<List<String>> versions = list(member, escapedModule);
                    if (versions.isPresent()) {
                        known = true;
                        union.addAll(versions.get());
                    }
                }
                yield known ? Optional.of(GoSemver.sorted(union)) : Optional.empty();
            }
        };
    }

    /** The cached upstream list document of a proxy, served byte for byte. */
    public Optional<StoredContent> proxiedList(Repository proxyRepo, String escapedModule) {
        return proxy.fetch(proxyRepo, GoPath.listPath(escapedModule), FetchOptions.index());
    }

    /** Every version a hosted repository holds a zip for, pseudo-versions included, decoded. */
    public List<String> storedVersions(Repository hosted, String escapedModule) {
        String prefix = GoPath.versionsPrefix(escapedModule);
        List<String> versions = new ArrayList<>();
        for (Asset asset : storage.list(hosted, prefix)) {
            String file = asset.getPath().substring(prefix.length());
            if (file.indexOf('/') >= 0 || !file.endsWith(".zip")) {
                continue;
            }
            try {
                String version = GoModulePaths.unescape(file.substring(0, file.length() - 4));
                if (GoSemver.isValid(version)) {
                    versions.add(version);
                }
            } catch (GoError e) {
                log.warn("ignoring oddly named file {} in {}", asset.getPath(), hosted.getName());
            }
        }
        return versions;
    }

    /** Renders a list document: one version per line, newline-terminated. */
    public static byte[] renderList(List<String> versions) {
        StringBuilder sb = new StringBuilder();
        for (String v : versions) {
            sb.append(v).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Parses a list document the way the go command does: first field of each line, valid semver, no pseudo-versions. */
    public static List<String> parseList(byte[] document) {
        List<String> versions = new ArrayList<>();
        for (String line : new String(document, StandardCharsets.UTF_8).split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length == 0 || fields[0].isEmpty()) {
                continue;
            }
            String v = fields[0];
            if (GoSemver.isValid(v) && !GoSemver.isPseudoVersion(v)) {
                versions.add(v);
            }
        }
        return versions;
    }

    private static List<String> listed(List<String> versions) {
        return versions.stream().filter(v -> !GoSemver.isPseudoVersion(v)).toList();
    }

    // --- files ---------------------------------------------------------------------------------------

    /** A version's {@code .info}, {@code .mod} or {@code .zip} from wherever the repository type gets it. */
    public Optional<StoredContent> file(Repository repo, GoPath path) {
        String file = path.filePath(extension(path));
        return resolver.resolve(repo, file, path.isMetadata() ? FetchOptions.index() : FetchOptions.artifact());
    }

    private static String extension(GoPath path) {
        return switch (path.kind()) {
            case INFO -> "info";
            case MOD -> "mod";
            case ZIP -> "zip";
            default -> throw new IllegalArgumentException("not a module file: " + path.raw());
        };
    }

    // --- @latest -------------------------------------------------------------------------------------

    /** The {@code .info} document of the latest version: highest release, else pre-release, else pseudo-version. */
    public Optional<byte[]> latest(Repository repo, String escapedModule) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> {
                Optional<String> pick = GoSemver.latest(storedVersions(repo, escapedModule));
                if (pick.isEmpty()) {
                    yield Optional.empty();
                }
                yield Optional.of(storedInfo(repo, escapedModule, pick.get()));
            }
            case PROXY -> {
                Optional<StoredContent> upstream = proxy.fetch(repo, GoPath.latestPath(escapedModule), FetchOptions.index());
                yield upstream.isEmpty() ? Optional.empty() : Optional.of(upstream.get().readAllBytes());
            }
            case GROUP -> latestOfGroup(repo, escapedModule);
        };
    }

    private Optional<byte[]> latestOfGroup(Repository group, String escapedModule) throws IOException {
        List<Repository> members = groups.leafMembers(group);
        Map<String, Repository> owners = new LinkedHashMap<>();
        for (Repository member : members) {
            List<String> versions = member.isHosted()
                    ? storedVersions(member, escapedModule)
                    : list(member, escapedModule).orElse(List.of());
            for (String v : versions) {
                owners.putIfAbsent(v, member);
            }
        }
        Optional<String> pick = GoSemver.latest(owners.keySet());
        if (pick.isPresent()) {
            String version = pick.get();
            String infoPath = GoPath.filePath(escapedModule, GoModulePaths.escape(version), "info");
            List<Repository> order = new ArrayList<>();
            order.add(owners.get(version));
            for (Repository member : members) {
                if (!order.contains(member)) {
                    order.add(member);
                }
            }
            for (Repository member : order) {
                Optional<StoredContent> info = resolver.resolve(member, infoPath, FetchOptions.artifact());
                if (info.isPresent()) {
                    return Optional.of(info.get().readAllBytes());
                }
            }
            return Optional.of(new GoInfo(version, null).toJson(mapper));
        }
        for (Repository member : members) {
            if (member.isProxy()) {
                Optional<byte[]> upstream = latest(member, escapedModule);
                if (upstream.isPresent()) {
                    return upstream;
                }
            }
        }
        return Optional.empty();
    }

    private byte[] storedInfo(Repository hosted, String escapedModule, String version) throws IOException {
        Optional<StoredContent> info = storage.find(hosted, GoPath.filePath(escapedModule, GoModulePaths.escape(version), "info"));
        if (info.isPresent()) {
            return info.get().readAllBytes();
        }
        return new GoInfo(version, null).toJson(mapper);
    }

    // --- checksum database ---------------------------------------------------------------------------

    /**
     * The checksum database a proxy passes through: {@code go.sumdbName}, by default {@code sum.golang.org}. Requests
     * for any other database name are not forwarded, so the repository can never be used to fetch arbitrary hosts.
     */
    public static String sumdbName(Repository proxyRepo) {
        return proxyRepo.setting(SUMDB_NAME).map(String::trim).filter(s -> !s.isEmpty()).orElse(DEFAULT_SUMDB_NAME);
    }

    /**
     * Whether {@code sumdb/<name>/supported} should answer 200: only when a proxy (or a group with a proxy member)
     * passes that database through. Otherwise the 404 makes the go command contact the database directly.
     */
    public boolean sumdbSupported(Repository repo, String name) {
        return switch (repo.getType()) {
            case HOSTED -> false;
            case PROXY -> name.equals(sumdbName(repo));
            case GROUP -> groups.leafMembers(repo).stream().anyMatch(m -> m.isProxy() && name.equals(sumdbName(m)));
        };
    }

    /** A checksum-database response, cached: lookups and tiles forever, the {@code latest} tree head as metadata. */
    public Optional<StoredContent> sumdb(Repository repo, GoPath path) {
        return switch (repo.getType()) {
            case HOSTED -> Optional.empty();
            case PROXY -> {
                if (!path.sumdbName().equals(sumdbName(repo))) {
                    yield Optional.empty();
                }
                URI upstream = sumdbUri(repo, path);
                // UpstreamClient sends the proxy's credentials only if the database shares the remote URL's origin (or
                // its host is listed in upstream.credentialHosts), never to sum.golang.org or a mirror elsewhere
                yield proxy.fetch(repo, path.raw(), upstream,
                        path.isMetadata() ? FetchOptions.index() : FetchOptions.artifact(), Map.of());
            }
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    if (!member.isProxy()) {
                        continue;
                    }
                    Optional<StoredContent> hit = sumdb(member, path);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /** Where a proxy fetches the database from: the {@code go.sumdbUrl} setting, else {@code https://<name>}. */
    public static URI sumdbUri(Repository proxyRepo, GoPath path) {
        String base = proxyRepo.setting(SUMDB_URL).map(String::trim).filter(s -> !s.isEmpty())
                .orElse("https://" + path.sumdbName());
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(UrlPaths.join(base, path.sumdbRest()));
    }
}
