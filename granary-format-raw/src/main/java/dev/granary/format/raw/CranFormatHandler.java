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
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

/**
 * CRAN for R. Proxies cache a CRAN mirror with {@code PACKAGES*} and the metadata directories re-validated;
 * hosted repositories accept {@code R CMD build} tarballs under {@code src/contrib/} and regenerate
 * {@code PACKAGES} and {@code PACKAGES.gz} from each tarball's DESCRIPTION, which is all
 * {@code install.packages()} needs ({@code PACKAGES.rds} is optional and R falls back without it). Groups merge
 * {@code PACKAGES(.gz)} of all members and answer 404 for {@code PACKAGES.rds}, which would otherwise be one
 * member's index alone.
 */
@org.springframework.stereotype.Component
public class CranFormatHandler extends RawFormatHandler {

    public static final String FORMAT = "cran";
    static final String PACKAGES = "src/contrib/PACKAGES";
    static final String PACKAGES_GZ = "src/contrib/PACKAGES.gz";
    static final String PACKAGES_RDS = "src/contrib/PACKAGES.rds";

    static final List<String> DEFAULT_METADATA = List.of(
            "src/contrib/PACKAGES", "src/contrib/PACKAGES.gz", "src/contrib/PACKAGES.rds", "src/contrib/PACKAGES*",
            "bin/**/PACKAGES*", "src/contrib/Meta/**", "bin/**/Meta/**", "web/**");

    private static final Logger log = LoggerFactory.getLogger(CranFormatHandler.class);
    private static final int CACHE_LIMIT = 4096;
    /** CRAN's own PACKAGES is about 10 MB; anything far beyond that is not an index. */
    private static final int MAX_INDEX = 256 * 1024 * 1024;

    /** A group's merged index, valid as long as the member documents it was built from are unchanged. */
    private record MergedIndex(String sources, byte[] text, byte[] gzip) {
    }

    private final Map<String, MergedIndex> mergedIndexes = new ConcurrentHashMap<>();

    /** DESCRIPTION fields per tarball, keyed by blob digest so a regeneration never re-reads unchanged tarballs. */
    private final Map<String, Map<String, String>> descriptions = new ConcurrentHashMap<>();

    public CranFormatHandler(StorageService storage, ProxyService proxy, GroupResolver groups, GranaryProperties properties) {
        super(storage, proxy, groups, properties);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "CRAN (R)";
    }

    @Override
    protected List<String> defaultMetadataPatterns() {
        return DEFAULT_METADATA;
    }

    @Override
    protected StorageService.ComponentCoordinates coordinates(String path) {
        return CranPackages.coordinates(path)
                .or(() -> CranPackages.archivedCoordinates(path))
                .orElseGet(() -> RawComponents.generic(path));
    }

    @Override
    protected void afterUpload(Repository repo, String path, StoredContent content) throws IOException {
        if (CranPackages.isSourcePackage(path)) {
            regenerate(repo);
        }
    }

    @Override
    protected void afterDelete(Repository repo, Collection<String> paths) throws IOException {
        if (repo.isHosted() && paths.stream().anyMatch(CranPackages::isSourcePackage)) {
            regenerate(repo);
        }
    }

    @Override
    protected Optional<ResponseEntity<?>> route(RepositoryRequest request, String path) throws IOException {
        Repository repo = request.repository();
        if (!repo.isGroup() || !request.isGet()) {
            return Optional.empty();
        }
        if (path.equals(PACKAGES_RDS)) {
            // R tries PACKAGES.rds first and falls back to PACKAGES.gz; one member's rds would hide the others
            return Optional.of(ContentResponses.notFound("Groups serve the merged PACKAGES and PACKAGES.gz only"));
        }
        if (!path.equals(PACKAGES) && !path.equals(PACKAGES_GZ)) {
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

    /** {@code PACKAGES} of every leaf member that has one (hosted: generated, proxy: cached and re-validated), merged. */
    Optional<MergedIndex> mergedIndex(Repository group) throws IOException {
        List<StoredContent> sources = new ArrayList<>();
        for (Repository member : groups.leafMembers(group)) {
            Optional<StoredContent> index = resolve(member, PACKAGES_GZ);
            if (index.isEmpty()) {
                index = resolve(member, PACKAGES);
            }
            index.ifPresent(sources::add);
        }
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder key = new StringBuilder();
        for (StoredContent source : sources) {
            key.append(source.asset().getRepositoryId()).append(':').append(source.asset().getBlobSha256()).append(';');
        }
        MergedIndex cached = mergedIndexes.get(group.getName());
        if (cached != null && cached.sources().equals(key.toString())) {
            return Optional.of(cached);
        }
        List<List<Map<String, String>>> indexes = new ArrayList<>();
        for (StoredContent source : sources) {
            try {
                byte[] bytes = source.readAllBytes();
                if (source.asset().getPath().endsWith(".gz")) {
                    bytes = Gzip.decompress(bytes, MAX_INDEX);
                }
                indexes.add(CranPackages.parseRecords(new String(bytes, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                log.warn("{} of a member of {} is unreadable ({}); merged without it", source.asset().getPath(),
                        group.getName(), e.getMessage());
            }
        }
        byte[] text = CranPackages.writeAll(CranPackages.merge(indexes)).getBytes(StandardCharsets.UTF_8);
        MergedIndex merged = new MergedIndex(key.toString(), text, Gzip.compress(text));
        if (mergedIndexes.size() >= 64) {
            mergedIndexes.clear();
        }
        mergedIndexes.put(group.getName(), merged);
        return Optional.of(merged);
    }

    @Override
    protected Optional<StoredContent> generated(Repository hosted, String path) throws IOException {
        if (path.equals(PACKAGES) || path.equals(PACKAGES_GZ)) {
            regenerate(hosted);
            return storage.find(hosted, path);
        }
        Optional<String> archived = CranPackages.archivedFile(path);
        if (archived.isPresent()) {
            return storage.find(hosted, "src/contrib/" + archived.get());
        }
        return Optional.empty();
    }

    /**
     * Rebuilds {@code src/contrib/PACKAGES} and {@code PACKAGES.gz}: the newest version of every package.
     * Regenerations of one repository are serialised, so the last one always reflects every stored upload.
     */
    void regenerate(Repository repo) throws IOException {
        locks.withLock(indexKey(repo), () -> {
            regenerateLocked(repo);
            return null;
        });
    }

    private void regenerateLocked(Repository repo) throws IOException {
        Map<String, Map<String, String>> newest = new TreeMap<>();
        for (Asset asset : storage.list(repo, "src/contrib/")) {
            String path = asset.getPath();
            Optional<StorageService.ComponentCoordinates> coordinates = CranPackages.coordinates(path);
            if (coordinates.isEmpty() || !CranPackages.isSourcePackage(path)) {
                continue;
            }
            Optional<StoredContent> content = storage.find(repo, path);
            if (content.isEmpty()) {
                continue;
            }
            Map<String, String> record = describe(content.get(), coordinates.get());
            String name = record.get("Package");
            String version = record.getOrDefault("Version", "0");
            Map<String, String> current = newest.get(name);
            if (current == null || CranPackages.compareVersions(version, current.getOrDefault("Version", "0")) > 0) {
                newest.put(name, record);
            }
        }
        byte[] text = CranPackages.write(new ArrayList<>(newest.values())).getBytes(StandardCharsets.UTF_8);
        storeGenerated(repo, PACKAGES, text, "text/plain");
        storeGenerated(repo, PACKAGES_GZ, Gzip.compress(text), "application/gzip");
        log.debug("regenerated {} with {} packages in {}", PACKAGES, newest.size(), repo.getName());
    }

    private Map<String, String> describe(StoredContent content, StorageService.ComponentCoordinates fromName) throws IOException {
        String key = content.asset().getBlobSha256();
        Map<String, String> cached = descriptions.get(key);
        if (cached == null) {
            if (descriptions.size() >= CACHE_LIMIT) {
                descriptions.clear();
            }
            Map<String, String> fields = new LinkedHashMap<>();
            try (InputStream in = content.open()) {
                CranPackages.description(in).ifPresent(fields::putAll);
            } catch (IOException | RuntimeException e) {
                // a malformed upload must never break the index of the whole repository
                log.warn("{} is not a readable source tarball ({}); indexed from its file name", content.asset().getPath(), e.getMessage());
            }
            fields.putIfAbsent("Package", fromName.name());
            fields.putIfAbsent("Version", fromName.version());
            fields.put("MD5sum", Checksums.of(content, "MD5"));
            descriptions.put(key, Map.copyOf(fields));
            cached = fields;
        }
        return new LinkedHashMap<>(cached);
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(RepositoryTemplate.proxy("cran-proxy", FORMAT, "https://cloud.r-project.org/", "Proxy of CRAN (R packages)"));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext ctx) {
        String url = ctx.urlWithSlash();
        String auth = ctx.username() + ":" + ctx.token();
        String authUrl = ctx.anonymousRead() ? url : url.replaceFirst("^(https?://)", "$1" + auth + "@");
        String note = ctx.anonymousRead() ? "" : "# R sends no credentials of its own: put them into the URL or enable anonymous read\n";
        return List.of(
                ConnectSnippet.file("rprofile", "R: ~/.Rprofile (named CRAN so pak, renv and devtools find it)", "text", "~/.Rprofile",
                        note + "options(repos = c(CRAN = \"" + authUrl + "\"))"),
                ConnectSnippet.shell("install-packages", "R: install from this repository",
                        "Rscript -e 'install.packages(\"praise\", repos = \"" + authUrl + "\")'"),
                ConnectSnippet.file("renv", "renv: renv.lock repository", "json", "renv.lock",
                        "{\n"
                                + "  \"R\": {\n"
                                + "    \"Version\": \"4.5.1\",\n"
                                + "    \"Repositories\": [\n"
                                + "      { \"Name\": \"CRAN\", \"URL\": \"" + url + "\" }\n"
                                + "    ]\n"
                                + "  }\n"
                                + "}"),
                ConnectSnippet.shell("pak-remotes", "pak / remotes",
                        "Rscript -e 'options(repos = c(CRAN = \"" + authUrl + "\")); pak::pak(\"praise\")'\n"
                                + "Rscript -e 'remotes::install_version(\"praise\", \"1.0.0\", repos = \"" + authUrl + "\")'"),
                ConnectSnippet.shell("build-upload", "Publish: R CMD build, then upload the tarball",
                        "R CMD build mypkg\n"
                                + "curl -fsS -u " + auth + " -T mypkg_1.0.0.tar.gz " + url + "src/contrib/mypkg_1.0.0.tar.gz\n"
                                + "# PACKAGES and PACKAGES.gz are regenerated from the tarball's DESCRIPTION"),
                ConnectSnippet.file("rprofile-site", "Site-wide: Rprofile.site or a Docker image", "text", "/etc/R/Rprofile.site",
                        "local({\n"
                                + "  r <- getOption(\"repos\")\n"
                                + "  r[\"CRAN\"] <- \"" + url + "\"\n"
                                + "  options(repos = r)\n"
                                + "})"));
    }
}
