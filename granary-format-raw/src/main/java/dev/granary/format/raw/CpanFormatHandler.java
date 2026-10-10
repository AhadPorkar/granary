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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

/**
 * CPAN for Perl. Proxies mirror a CPAN site verbatim with the index files re-validated; hosted repositories
 * accept distribution tarballs under {@code authors/id/<A>/<AB>/<AUTHOR>/} and regenerate
 * {@code modules/02packages.details.txt(.gz)} from the modules each tarball provides, which is what
 * {@code cpanm --mirror <url> --mirror-only} resolves names with. Groups merge the index of every member
 * (the first member that lists a module provides it), so one mirror URL serves hosted and proxied modules.
 */
@org.springframework.stereotype.Component
public class CpanFormatHandler extends RawFormatHandler {

    public static final String FORMAT = "cpan";
    static final String PACKAGES_TXT = "modules/02packages.details.txt";
    static final String PACKAGES_GZ = "modules/02packages.details.txt.gz";

    static final List<String> DEFAULT_METADATA = List.of(
            "modules/02packages.details.txt.gz", "modules/02packages.details.txt", "modules/03modlist.data.gz",
            "modules/03modlist.data", "modules/06perms.txt.gz", "modules/06perms.txt", "modules/02STAMP",
            "modules/01modules.index.html", "modules/01modules.mtime.html", "authors/01mailrc.txt.gz", "authors/01mailrc.txt",
            "indices/**", "modules/by-module/**", "modules/by-authors/**", "modules/by-category/**",
            "authors/id/**/CHECKSUMS", "authors/00whois.xml", "MIRRORED.BY", "RECENT*", "**/RECENT*");

    private static final Logger log = LoggerFactory.getLogger(CpanFormatHandler.class);
    private static final int CACHE_LIMIT = 4096;
    /** The public 02packages.details.txt is about 20 MB; anything far beyond that is not an index. */
    private static final int MAX_INDEX = 256 * 1024 * 1024;

    /** A group's merged index, valid as long as the member documents it was built from are unchanged. */
    private record MergedIndex(String sources, byte[] text, byte[] gzip) {
    }

    private final Map<String, MergedIndex> mergedIndexes = new ConcurrentHashMap<>();

    /** Modules per distribution, keyed by blob digest so a regeneration never re-reads unchanged tarballs. */
    private final Map<String, List<CpanIndex.Module>> modules = new ConcurrentHashMap<>();

    public CpanFormatHandler(StorageService storage, ProxyService proxy, GroupResolver groups, GranaryProperties properties) {
        super(storage, proxy, groups, properties);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "CPAN (Perl)";
    }

    @Override
    protected List<String> defaultMetadataPatterns() {
        return DEFAULT_METADATA;
    }

    @Override
    protected StorageService.ComponentCoordinates coordinates(String path) {
        return CpanIndex.dist(path)
                .map(d -> new StorageService.ComponentCoordinates(d.author(), d.name(), d.version()))
                .orElseGet(() -> RawComponents.generic(path));
    }

    @Override
    protected void afterUpload(Repository repo, String path, StoredContent content) throws IOException {
        if (CpanIndex.dist(path).isPresent()) {
            regenerate(repo);
        }
    }

    @Override
    protected void afterDelete(Repository repo, Collection<String> paths) throws IOException {
        if (repo.isHosted() && paths.stream().anyMatch(p -> CpanIndex.dist(p).isPresent())) {
            regenerate(repo);
        }
    }

    /** A group answers the index with the merge of its members' indexes instead of the first member's. */
    @Override
    protected Optional<ResponseEntity<?>> route(RepositoryRequest request, String path) throws IOException {
        Repository repo = request.repository();
        if (!repo.isGroup() || !request.isGet() || !(path.equals(PACKAGES_TXT) || path.equals(PACKAGES_GZ))) {
            return Optional.empty();
        }
        Optional<MergedIndex> index = mergedIndex(repo);
        if (index.isEmpty()) {
            return Optional.of(ContentResponses.notFound());
        }
        return Optional.of(path.equals(PACKAGES_GZ)
                ? document(request, index.get().gzip(), "application/gzip", true)
                : document(request, index.get().text(), "text/plain", true));
    }

    /** The index of every leaf member that has one (hosted: generated, proxy: cached and re-validated), merged. */
    Optional<MergedIndex> mergedIndex(Repository group) throws IOException {
        List<StoredContent> sources = new ArrayList<>();
        for (Repository member : groups.leafMembers(group)) {
            Optional<StoredContent> index = resolve(member, PACKAGES_GZ);
            if (index.isEmpty()) {
                index = resolve(member, PACKAGES_TXT);
            }
            index.ifPresent(sources::add);
        }
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder key = new StringBuilder();
        Instant newest = Instant.EPOCH;
        for (StoredContent source : sources) {
            key.append(source.asset().getRepositoryId()).append(':').append(source.asset().getBlobSha256()).append(';');
            Instant updated = source.asset().getUpdatedAt() != null ? source.asset().getUpdatedAt() : source.asset().getLastVerifiedAt();
            if (updated != null && updated.isAfter(newest)) {
                newest = updated;
            }
        }
        MergedIndex cached = mergedIndexes.get(group.getName());
        if (cached != null && cached.sources().equals(key.toString())) {
            return Optional.of(cached);
        }
        List<List<CpanIndex.Provided>> indexes = new ArrayList<>();
        for (StoredContent source : sources) {
            try {
                byte[] bytes = source.readAllBytes();
                if (source.asset().getPath().endsWith(".gz")) {
                    bytes = Gzip.decompress(bytes, MAX_INDEX);
                }
                indexes.add(CpanIndex.parsePackagesDetails(new String(bytes, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                log.warn("{} of a member of {} is unreadable ({}); merged without it", source.asset().getPath(),
                        group.getName(), e.getMessage());
            }
        }
        byte[] text = CpanIndex.packagesDetails(CpanIndex.merge(indexes), newest);
        MergedIndex merged = new MergedIndex(key.toString(), text, Gzip.compress(text));
        if (mergedIndexes.size() >= 64) {
            mergedIndexes.clear();
        }
        mergedIndexes.put(group.getName(), merged);
        return Optional.of(merged);
    }

    @Override
    protected Optional<StoredContent> generated(Repository hosted, String path) throws IOException {
        if (path.equals(PACKAGES_TXT) || path.equals(PACKAGES_GZ)) {
            regenerate(hosted);
            return storage.find(hosted, path);
        }
        return Optional.empty();
    }

    /**
     * Rebuilds the index: for every module the highest version any distribution in the repository provides.
     * Regenerations of one repository are serialised, so the last one always reflects every stored upload.
     */
    void regenerate(Repository repo) throws IOException {
        locks.withLock(indexKey(repo), () -> {
            regenerateLocked(repo);
            return null;
        });
    }

    private void regenerateLocked(Repository repo) throws IOException {
        Map<String, CpanIndex.Provided> best = new TreeMap<>();
        for (Asset asset : storage.list(repo, "authors/id/")) {
            Optional<CpanIndex.Dist> dist = CpanIndex.dist(asset.getPath());
            if (dist.isEmpty()) {
                continue;
            }
            Optional<StoredContent> content = storage.find(repo, asset.getPath());
            if (content.isEmpty()) {
                continue;
            }
            for (CpanIndex.Module module : modulesOf(content.get())) {
                CpanIndex.Provided current = best.get(module.name());
                if (current == null || CpanIndex.compareVersions(module.version(), current.version()) > 0) {
                    best.put(module.name(), new CpanIndex.Provided(module.name(), module.version(), dist.get().file()));
                }
            }
        }
        byte[] text = CpanIndex.packagesDetails(best.values(), Instant.now());
        storeGenerated(repo, PACKAGES_TXT, text, "text/plain");
        storeGenerated(repo, PACKAGES_GZ, Gzip.compress(text), "application/gzip");
        log.debug("regenerated {} with {} modules in {}", PACKAGES_TXT, best.size(), repo.getName());
    }

    private List<CpanIndex.Module> modulesOf(StoredContent content) {
        String key = content.asset().getBlobSha256();
        List<CpanIndex.Module> cached = modules.get(key);
        if (cached == null) {
            if (modules.size() >= CACHE_LIMIT) {
                modules.clear();
            }
            try (InputStream in = content.open()) {
                cached = List.copyOf(CpanIndex.modules(in, RawPaths.fileName(content.asset().getPath())));
            } catch (IOException | RuntimeException e) {
                log.warn("{} is not a readable distribution archive ({}); it provides no modules",
                        content.asset().getPath(), e.getMessage());
                cached = List.of();
            }
            modules.put(key, cached);
        }
        return cached;
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(RepositoryTemplate.proxy("cpan-proxy", FORMAT, "https://www.cpan.org/", "Proxy of CPAN (Perl modules)"));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext ctx) {
        String url = ctx.urlWithSlash();
        String auth = ctx.username() + ":" + ctx.token();
        String authUrl = ctx.anonymousRead() ? url : url.replaceFirst("^(https?://)", "$1" + auth + "@");
        String note = ctx.anonymousRead() ? "" : "# the CPAN clients send no credentials of their own: put them into the URL or enable anonymous read\n";
        return List.of(
                ConnectSnippet.shell("cpanm", "cpanm: install from this mirror only",
                        note + "cpanm --mirror " + authUrl + " --mirror-only Acme::Meow"),
                ConnectSnippet.shell("cpanm-env", "cpanm: make it the default with PERL_CPANM_OPT",
                        "export PERL_CPANM_OPT=\"--mirror " + authUrl + " --mirror-only\"\n"
                                + "cpanm Moose"),
                ConnectSnippet.shell("cpan-shell", "cpan shell: urllist",
                        "cpan\n"
                                + "cpan> o conf urllist " + authUrl + "\n"
                                + "cpan> o conf commit\n"
                                + "cpan> install Acme::Meow"),
                ConnectSnippet.shell("carton", "Carton",
                        "export PERL_CARTON_MIRROR=" + authUrl + "\n"
                                + "carton install"),
                ConnectSnippet.shell("cpm", "cpm",
                        "cpm install -g --resolver 02packages," + authUrl + " Acme::Meow"),
                ConnectSnippet.shell("upload", "Publish: upload a distribution tarball (the index is regenerated)",
                        "curl -fsS -u " + auth + " -T Acme-Hello-1.00.tar.gz " + url + "authors/id/"
                                + "U/US/USER/Acme-Hello-1.00.tar.gz"));
    }
}
