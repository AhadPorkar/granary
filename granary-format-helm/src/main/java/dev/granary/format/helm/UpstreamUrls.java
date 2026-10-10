package dev.granary.format.helm;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps the download URLs of an upstream index to paths inside a Granary proxy repository and back.
 *
 * <p>An entry's URL may be absolute (any host: GitHub releases, a CDN) or relative to the repository URL, and is
 * resolved the way Helm resolves it. The chart is then served as {@code charts/<file name>}; when two upstream
 * charts share a file name with different URLs, or the URL's last segment is not a usable {@code .tgz} file name,
 * the chart is addressed as {@code charts/<name>/<version>/<file>} instead. Provenance files live next to their
 * archive at {@code <archive URL>.prov}, as Helm expects.
 */
final class UpstreamUrls {

    /** Where a repository path comes from upstream. */
    record Target(String url, String name, String version) {
    }

    /**
     * The upstream index with repository-relative download paths, and where each path is fetched from.
     *
     * @param local   the index, each entry's {@code urls} holding exactly one repository path
     * @param targets repository path of every archive to its upstream location
     */
    record Mapping(HelmIndex local, Map<String, Target> targets) {

        /** The upstream URL of an archive or provenance path, if the index lists it. */
        Optional<String> upstreamUrl(String path) {
            if (path.endsWith(HelmPath.PROV_SUFFIX)) {
                Target target = targets.get(path.substring(0, path.length() - HelmPath.PROV_SUFFIX.length()));
                return target == null ? Optional.empty() : Optional.of(target.url() + HelmPath.PROV_SUFFIX);
            }
            Target target = targets.get(path);
            return target == null ? Optional.empty() : Optional.of(target.url());
        }

        Optional<Target> target(String path) {
            String archive = path.endsWith(HelmPath.PROV_SUFFIX) ? path.substring(0, path.length() - HelmPath.PROV_SUFFIX.length()) : path;
            return Optional.ofNullable(targets.get(archive));
        }

        /** The repository path the archive of a chart version is served under. */
        Optional<String> archivePath(String name, String version) {
            return local.find(name, version).map(e -> e.urls().getFirst());
        }
    }

    private UpstreamUrls() {
    }

    static Mapping map(HelmIndex upstream, String repositoryUrl) {
        record Candidate(ChartEntry entry, String url, String filename) {
        }
        List<Candidate> candidates = new ArrayList<>();
        Map<String, Set<String>> urlsByFilename = new HashMap<>();
        for (ChartEntry entry : upstream.all()) {
            List<String> urls = entry.urls();
            if (urls.isEmpty()) {
                continue; // Helm cannot download it either
            }
            Optional<String> resolved = resolve(repositoryUrl, urls.getFirst());
            if (resolved.isEmpty()) {
                continue;
            }
            String filename = lastSegment(resolved.get());
            candidates.add(new Candidate(entry, resolved.get(), filename));
            urlsByFilename.computeIfAbsent(filename, k -> new HashSet<>()).add(resolved.get());
        }
        List<ChartEntry> local = new ArrayList<>(candidates.size());
        Map<String, Target> targets = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            String name = c.entry().name();
            String version = c.entry().version();
            boolean usable = HelmPath.isSafeFilename(c.filename()) && HelmPath.isArchive(c.filename());
            String path;
            if (usable && urlsByFilename.get(c.filename()).size() == 1) {
                path = HelmPath.CHARTS + c.filename();
            } else if (HelmPath.isValidName(name) && HelmPath.isSafeFilename(version)) {
                String file = usable ? c.filename() : HelmPath.archiveName(name, version);
                if (!HelmPath.isSafeFilename(file)) {
                    continue;
                }
                path = HelmPath.aliasPath(name, version, file);
            } else {
                continue;
            }
            Target previous = targets.putIfAbsent(path, new Target(c.url(), name, version));
            if (previous != null && !previous.url().equals(c.url())) {
                continue;
            }
            local.add(c.entry().withUrls(List.of(path)));
        }
        return new Mapping(HelmIndex.of(local), targets);
    }

    /**
     * Resolves a chart URL from an index against the repository URL like Helm's {@code ResolveReferenceURL}: absolute
     * URLs stay as they are, relative ones are resolved against the repository URL with a trailing slash and inherit
     * its query string.
     */
    static Optional<String> resolve(String repositoryUrl, String ref) {
        try {
            URI refUri = new URI(ref.trim());
            if (refUri.isAbsolute()) {
                String scheme = refUri.getScheme().toLowerCase(java.util.Locale.ROOT);
                return scheme.equals("http") || scheme.equals("https") ? Optional.of(refUri.toString()) : Optional.empty();
            }
            URI base = new URI(repositoryUrl.trim());
            String path = base.getRawPath() == null || base.getRawPath().isEmpty() ? "/" : base.getRawPath();
            if (!path.endsWith("/")) {
                path = path + "/";
            }
            URI baseWithSlash = new URI(base.getScheme(), base.getRawAuthority(), null, null, null);
            String resolved = URI.create(baseWithSlash + path).resolve(refUri).toString();
            int hash = resolved.indexOf('#');
            if (hash >= 0) {
                resolved = resolved.substring(0, hash);
            }
            int q = resolved.indexOf('?');
            if (q >= 0) {
                resolved = resolved.substring(0, q); // Helm replaces the query with the repository URL's
            }
            return Optional.of(base.getRawQuery() == null ? resolved : resolved + "?" + base.getRawQuery());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The decoded last path segment of an absolute URL, or an empty string. */
    static String lastSegment(String url) {
        try {
            String path = new URI(url).getPath();
            if (path == null) {
                return "";
            }
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /**
     * The repository URL of a proxy: its remote URL without a trailing {@code index.yaml} someone may have pasted and
     * without trailing slashes. A query string (a signed storage URL, an access token) is kept.
     */
    static String repositoryUrl(String remoteUrl) {
        String url = remoteUrl == null ? "" : remoteUrl.trim();
        int hash = url.indexOf('#');
        if (hash >= 0) {
            url = url.substring(0, hash);
        }
        int q = url.indexOf('?');
        String query = q >= 0 ? url.substring(q) : "";
        String path = q >= 0 ? url.substring(0, q) : url;
        if (path.endsWith("/" + HelmPath.INDEX_FILE)) {
            path = path.substring(0, path.length() - HelmPath.INDEX_FILE.length());
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path + query;
    }

    /**
     * The URL of a document at the root of the upstream repository ({@code index.yaml}, {@code artifacthub-repo.yml})
     * the way helm builds it: the file name joined to the repository path, the repository URL's query string kept
     * ({@code https://host/charts?sig=…} → {@code https://host/charts/index.yaml?sig=…}).
     */
    static Optional<URI> documentUrl(String remoteUrl, String file) {
        try {
            return resolve(repositoryUrl(remoteUrl), file).map(URI::create)
                    .filter(uri -> uri.isAbsolute() && uri.getHost() != null);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
