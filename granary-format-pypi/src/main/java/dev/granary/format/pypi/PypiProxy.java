package dev.granary.format.pypi;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UrlPaths;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The upstream side of a proxy repository. Index pages are cached as metadata under
 * {@code simple/<name>/index.html} or {@code index.json} (one per representation, since pypi.org serves
 * both), the JSON API under {@code pypi/<name>/json}, and files from the files host
 * ({@code pypi.filesUrl}) under their own {@code packages/…} path so the cache looks like that host. A file
 * link anywhere else (another Granary, Nexus, devpi, a bandersnatch mirror) is cached under
 * {@code packages/-/<project>/<file>} and its upstream URL is looked up again in the cached project page when
 * the file is requested.
 *
 * <p>{@code remoteUrl} may be the root of the index ({@code https://pypi.org/}) or, the way pip users write
 * it, the simple index itself ({@code https://pypi.org/simple/}, devpi's {@code …/+simple/}).
 */
@Component
public class PypiProxy {

    public static final String FILES_URL_SETTING = "pypi.filesUrl";
    public static final String DEFAULT_FILES_URL = "https://files.pythonhosted.org/";
    /** First segment under {@code packages/} for files whose upstream URL is not on the files host. */
    public static final String FOREIGN_MARKER = "-";

    private static final Logger log = LoggerFactory.getLogger(PypiProxy.class);
    private static final Map<String, String> ACCEPT_JSON = Map.of("Accept", "application/json");

    private final ProxyService proxy;
    private final StorageService storage;
    private final SimpleIndex index;
    private final ObjectMapper json;

    public PypiProxy(ProxyService proxy, StorageService storage, SimpleIndex index, ObjectMapper json) {
        this.proxy = proxy;
        this.storage = storage;
        this.index = index;
        this.json = json;
    }

    // --- upstream URLs ---------------------------------------------------------------------------

    /** The simple index of the upstream, with a trailing slash: {@code remoteUrl} itself when it names one, else {@code <remoteUrl>simple/}. */
    static String simpleRoot(Repository repo) {
        String remote = remoteUrl(repo);
        return namesSimpleIndex(remote) ? remote : remote + PypiPath.SIMPLE;
    }

    /** Where the upstream's JSON API ({@code pypi/<name>/json}) lives: the root of the index, with a trailing slash. */
    static String apiRoot(Repository repo) {
        String remote = remoteUrl(repo);
        if (!namesSimpleIndex(remote)) {
            return remote;
        }
        String withoutSlash = remote.substring(0, remote.length() - 1);
        return withoutSlash.substring(0, withoutSlash.lastIndexOf('/') + 1);
    }

    private static String remoteUrl(Repository repo) {
        String remote = repo.getRemoteUrl() == null ? "" : repo.getRemoteUrl().trim();
        return remote.endsWith("/") ? remote : remote + "/";
    }

    private static boolean namesSimpleIndex(String remoteWithSlash) {
        try {
            String path = new URI(remoteWithSlash).getPath();
            return path != null && (path.endsWith("/simple/") || path.endsWith("/+simple/"));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static URI projectPageUri(Repository repo, String normalisedName) {
        return URI.create(UrlPaths.join(simpleRoot(repo), normalisedName + "/"));
    }

    // --- index documents -----------------------------------------------------------------------

    /** The upstream project page, links still pointing upstream; empty when neither upstream nor the cache has it. */
    public Optional<PypiProject> project(Repository repo, String normalisedName, PypiAccept accept) {
        URI pageUrl = projectPageUri(repo, normalisedName);
        String cachePath = projectCachePath(normalisedName, accept.json());
        return proxy.fetch(repo, cachePath, pageUrl, FetchOptions.index(), Map.of("Accept", accept.upstreamAccept()))
                .flatMap(content -> parseProject(content, normalisedName, pageUrl));
    }

    /** The project page in whichever representation is cached, without contacting upstream unless nothing is cached. */
    Optional<PypiProject> cachedProject(Repository repo, String normalisedName) {
        URI pageUrl = projectPageUri(repo, normalisedName);
        for (boolean asJson : new boolean[] {true, false}) {
            Optional<StoredContent> cached = proxy.fetch(repo, projectCachePath(normalisedName, asJson), pageUrl,
                    FetchOptions.index(), Map.of("Accept", asJson ? PypiAccept.UPSTREAM_ACCEPT_JSON : PypiAccept.UPSTREAM_ACCEPT_HTML));
            if (cached.isPresent()) {
                return parseProject(cached.get(), normalisedName, pageUrl);
            }
        }
        return Optional.empty();
    }

    /** The upstream project list (pypi.org's is about 25 MB, so it is only fetched when a client asks for it). */
    public Optional<List<String>> projectNames(Repository repo, PypiAccept accept) {
        URI pageUrl = URI.create(simpleRoot(repo));
        String cachePath = PypiPath.SIMPLE + (accept.json() ? "index.json" : "index.html");
        return proxy.fetch(repo, cachePath, pageUrl, FetchOptions.index(), Map.of("Accept", accept.upstreamAccept()))
                .flatMap(content -> {
                    try {
                        return index.parseProjectList(content.readAllBytes(), content.contentType());
                    } catch (IOException e) {
                        log.warn("cached project list of {} is unreadable: {}", repo.getName(), e.getMessage());
                        return Optional.empty();
                    }
                });
    }

    /** A JSON API document ({@code pypi/<name>/json} or {@code pypi/<name>/<version>/json}), as upstream serves it. */
    public Optional<JsonNode> jsonApi(Repository repo, String path) {
        URI uri = URI.create(UrlPaths.join(apiRoot(repo), path));
        return proxy.fetch(repo, path, uri, FetchOptions.index(), ACCEPT_JSON).flatMap(content -> {
            try {
                JsonNode node = json.readTree(content.readAllBytes());
                return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
            } catch (IOException | JacksonException e) {
                log.warn("cached JSON API document {} of {} is unreadable: {}", path, repo.getName(), e.getMessage());
                return Optional.empty();
            }
        });
    }

    private Optional<PypiProject> parseProject(StoredContent content, String name, URI pageUrl) {
        try {
            return index.parseProject(content.readAllBytes(), content.contentType(), name, pageUrl);
        } catch (IOException e) {
            log.warn("cached project page {} is unreadable: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    static String projectCachePath(String normalisedName, boolean json) {
        return PypiPath.SIMPLE + normalisedName + "/" + (json ? "index.json" : "index.html");
    }

    // --- files ---------------------------------------------------------------------------------

    /**
     * A file under {@code packages/…}: from the cache, else fetched from upstream and cached forever. A cached
     * distribution (and its {@code .metadata} sidecar) is linked to its project and version, so the component
     * browser and search show what was installed through the proxy.
     */
    public Optional<StoredContent> file(Repository repo, String path) {
        Optional<URI> uri = upstreamFileUri(repo, path);
        if (uri.isEmpty()) {
            return Optional.empty();
        }
        return proxy.fetch(repo, path, uri.get(), FetchOptions.artifact(), Map.of()).map(content -> linkComponent(repo, content));
    }

    private StoredContent linkComponent(Repository repo, StoredContent content) {
        Asset asset = content.asset();
        if (asset.getComponentId() != null) {
            return content;
        }
        String filename = asset.getPath().substring(asset.getPath().lastIndexOf('/') + 1);
        String distribution = PypiNames.distributionOfSidecar(filename).orElse(filename);
        Optional<PypiNames.Distribution> parsed = PypiNames.parseFilename(distribution);
        if (parsed.isEmpty()) {
            return content;
        }
        StorageService.ComponentCoordinates coordinates =
                new StorageService.ComponentCoordinates("", parsed.get().normalisedName(), parsed.get().version());
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                asset.setComponentId(storage.ensureComponent(repo, coordinates).getId());
                return new StoredContent(storage.save(asset), content.blobStore());
            } catch (RuntimeException e) {
                // a concurrent download of another file of the same release created the component first
                asset.setComponentId(null);
                log.debug("linking {} in {} to its component failed: {}", asset.getPath(), repo.getName(), e.getMessage());
            }
        }
        return content;
    }

    /** Where upstream keeps the file cached at {@code path}; empty when that cannot be determined. */
    public Optional<URI> upstreamFileUri(Repository repo, String path) {
        String rest = path.substring(PypiPath.PACKAGES.length());
        if (!rest.startsWith(FOREIGN_MARKER + "/")) {
            try {
                return Optional.of(URI.create(UrlPaths.join(filesUrl(repo), path)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        String[] s = rest.split("/", -1);
        if (s.length != 3) {
            return Optional.empty();
        }
        String project = s[1];
        String filename = s[2];
        boolean sidecar = filename.endsWith(PypiHosted.METADATA_SUFFIX);
        String distribution = sidecar ? filename.substring(0, filename.length() - PypiHosted.METADATA_SUFFIX.length()) : filename;
        return cachedProject(repo, project).flatMap(page -> page.files().stream()
                .filter(f -> f.filename().equals(distribution))
                .findFirst()
                .flatMap(f -> {
                    try {
                        return Optional.of(new URI(sidecar ? f.url() + PypiHosted.METADATA_SUFFIX : f.url()));
                    } catch (URISyntaxException e) {
                        return Optional.empty();
                    }
                }));
    }

    public static String filesUrl(Repository repo) {
        String url = repo.setting(FILES_URL_SETTING, DEFAULT_FILES_URL).trim();
        return url.endsWith("/") ? url : url + "/";
    }

    // --- link rewriting --------------------------------------------------------------------------

    /**
     * The repository-relative cache path for an upstream file link: the link's own {@code packages/…} path when it
     * lives on the files host, otherwise {@code packages/-/<project>/<file>} (resolved again from the project
     * page when the file is requested).
     *
     * @param url      the upstream link, absolute
     * @param filename the file name the index states for the link, or {@code null} to take the link's last segment
     * @param project  the project the link belongs to
     * @param filesUrl the files host, see {@link #filesUrl(Repository)}
     */
    public static String localPath(String url, String filename, String project, String filesUrl) {
        String path = linkPath(url);
        Optional<String> onFilesHost = pathOnFilesHost(url, filesUrl);
        if (onFilesHost.isPresent()) {
            String rest = onFilesHost.get();
            String[] segments = rest.split("/", -1);
            boolean safe = segments.length >= 2;
            for (String segment : segments) {
                safe &= PypiNames.isSafeSegment(segment);
            }
            if (safe) {
                return PypiPath.PACKAGES + rest;
            }
        }
        String name = filename == null || filename.isBlank() ? path.substring(path.lastIndexOf('/') + 1) : filename.trim();
        return PypiPath.PACKAGES + FOREIGN_MARKER + "/" + PypiNames.normalise(project) + "/" + name;
    }

    /** The decoded path of a link, without query or fragment; the raw text up to them when it is not a valid URI. */
    private static String linkPath(String url) {
        try {
            String path = new URI(url).getPath();
            return path == null ? "" : path;
        } catch (URISyntaxException e) {
            int end = url.length();
            int hash = url.indexOf('#');
            int query = url.indexOf('?');
            if (hash >= 0) {
                end = hash;
            }
            if (query >= 0 && query < end) {
                end = query;
            }
            return url.substring(0, end);
        }
    }

    /** The part after {@code <filesUrl>packages/} when {@code url} lives there (same scheme, host and port). */
    static Optional<String> pathOnFilesHost(String url, String filesUrl) {
        try {
            URI link = new URI(url);
            URI files = new URI(filesUrl.endsWith("/") ? filesUrl : filesUrl + "/");
            if (link.getScheme() == null || link.getHost() == null || files.getScheme() == null || files.getHost() == null
                    || !link.getScheme().equalsIgnoreCase(files.getScheme())
                    || !link.getHost().equalsIgnoreCase(files.getHost())
                    || port(link) != port(files)) {
                return Optional.empty();
            }
            String prefix = (files.getPath() == null || files.getPath().isEmpty() ? "/" : files.getPath()) + PypiPath.PACKAGES;
            String path = link.getPath();
            return path != null && path.startsWith(prefix) ? Optional.of(path.substring(prefix.length())) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** The page with every file link pointing at {@code baseUrl} instead of upstream. */
    public static PypiProject rewrite(PypiProject project, String baseUrl, String filesUrl) {
        String name = PypiNames.normalise(project.name());
        return project.mapFiles(f -> f.withUrl(UrlPaths.join(baseUrl, localPath(f.url(), f.filename(), name, filesUrl))));
    }
}
