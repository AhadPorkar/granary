package dev.granary.format.nuget;

import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The NuGet V3 protocol: service index, flat container, registration, search and the V2-style push and
 * delete endpoints, for hosted, proxy and group repositories.
 *
 * <p>Hosted repositories keep a package as two files, the {@code .nupkg} and its extracted {@code .nuspec},
 * and generate every index document from them on request, so there is no index to rebuild or to get out
 * of sync. Proxies cache upstream documents through {@link ProxyService} and rewrite their URLs so the
 * client never learns the upstream address; once a package has been restored through the proxy it is
 * available without internet. Groups answer from the first member that has a file and merge the version
 * lists and registration indexes of all members.
 */
@org.springframework.stereotype.Component
public class NuGetFormatHandler implements FormatHandler {

    public static final String FORMAT = "nuget";

    /** What nuget.org accepts as an id: letters, digits, dots, hyphens and underscores, up to 100 characters. */
    static final Pattern PACKAGE_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");

    private static final Logger log = LoggerFactory.getLogger(NuGetFormatHandler.class);

    private final StorageService storage;
    private final GroupResolver groups;
    private final ObjectMapper json;
    private final UpstreamNuGet upstream;
    private final RegistrationBuilder registrations;
    private final SearchResponse searches;

    public NuGetFormatHandler(StorageService storage, ProxyService proxy, UpstreamClient upstreamClient,
                              GroupResolver groups, ObjectMapper json) {
        this.storage = storage;
        this.groups = groups;
        this.json = json;
        this.upstream = new UpstreamNuGet(proxy, upstreamClient, json);
        this.registrations = new RegistrationBuilder(json);
        this.searches = new SearchResponse(json);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "NuGet";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("nuget.org", FORMAT, "https://api.nuget.org/v3/index.json", "Proxy of nuget.org"),
                RepositoryTemplate.hosted("nuget-hosted", FORMAT, "Your own packages"),
                RepositoryTemplate.group("nuget-group", FORMAT, List.of("nuget-hosted", "nuget.org"),
                        "Single package source for dotnet, nuget.exe and Visual Studio"));
    }

    /** Index documents (service, flat-container, registration) change with every push; packages never do. */
    @Override
    public boolean isMetadataPath(String path) {
        String p = path == null ? "" : path;
        return NuGetPath.parse(p).isMutable()
                || p.equals(NuGetLayout.SERVICE_INDEX_CACHE_PATH)
                || p.endsWith("/index.json")
                || p.startsWith("v3/registration/");
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        NuGetPath path = NuGetPath.parse(request.path());
        try {
            return switch (request.method()) {
                case "GET", "HEAD" -> get(request, path);
                case "PUT", "POST" -> put(request, path);
                case "DELETE" -> delete(request, path);
                default -> ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                        request.method() + " is not supported by NuGet repositories");
            };
        } catch (NuspecReader.InvalidPackageException e) {
            return ContentResponses.error(HttpStatus.BAD_REQUEST, "repository.nuget.invalidPackage", e.getMessage());
        }
    }

    // --- GET ---------------------------------------------------------------------------------

    private ResponseEntity<?> get(RepositoryRequest request, NuGetPath path) throws IOException {
        Repository repo = request.repository();
        String baseUrl = request.baseUrl();
        return switch (path.kind()) {
            case SERVICE_INDEX -> json(ServiceIndex.build(json, baseUrl));
            case FLAT_INDEX -> {
                List<NuGetVersion> versions = versions(repo, path.id());
                yield versions.isEmpty() ? notFound(path.id()) : json(flatIndex(versions));
            }
            case PACKAGE, NUSPEC, SYMBOLS -> {
                Optional<NuGetVersion> version = NuGetVersion.tryParse(path.version());
                if (version.isEmpty()) {
                    yield ContentResponses.notFound("'" + path.version() + "' is not a NuGet version");
                }
                Optional<StoredContent> content = file(repo, path.id(), version.get().toLowerCaseString(), path.kind());
                yield content.isPresent() ? respond(request, content.get()) : notFound(path.id());
            }
            case REGISTRATION_INDEX -> registrationIndex(repo, path.id(), baseUrl)
                    .<ResponseEntity<?>>map(this::json)
                    .orElseGet(() -> notFound(path.id()));
            case REGISTRATION_LEAF -> {
                Optional<NuGetVersion> version = NuGetVersion.tryParse(path.version());
                if (version.isEmpty()) {
                    yield ContentResponses.notFound("'" + path.version() + "' is not a NuGet version");
                }
                yield registrationIndex(repo, path.id(), baseUrl)
                        .flatMap(index -> registrations.leaf(index, baseUrl, path.id(), version.get()))
                        .<ResponseEntity<?>>map(this::json)
                        .orElseGet(() -> notFound(path.id()));
            }
            case SEARCH -> json(search(repo, SearchResponse.Query.of(request.query("q"), request.query("skip"),
                    request.query("take"), request.query("prerelease"), request.query("semVerLevel")), baseUrl));
            case PUBLISH, SYMBOL_PUBLISH, PACKAGE_VERSION -> ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED,
                    "method.notAllowed", "Use PUT to push a package and DELETE to remove a version");
            case UNKNOWN -> ContentResponses.notFound();
        };
    }

    private ResponseEntity<?> respond(RepositoryRequest request, StoredContent content) {
        ResponseEntity<?> response = ContentResponses.stream(request, content);
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.asset());
        }
        return response;
    }

    private ObjectNode flatIndex(List<NuGetVersion> versions) {
        ObjectNode root = json.createObjectNode();
        ArrayNode array = root.putArray("versions");
        for (NuGetVersion version : versions) {
            array.add(version.toLowerCaseString());
        }
        return root;
    }

    /** Every version of a package this repository can serve, in NuGet order. */
    List<NuGetVersion> versions(Repository repo, String id) {
        return switch (repo.getType()) {
            case HOSTED -> new ArrayList<>(hostedVersions(repo, id).keySet());
            case PROXY -> upstream.flatVersions(repo, id).orElse(List.of());
            case GROUP -> {
                TreeSet<NuGetVersion> union = new TreeSet<>();
                for (Repository member : groups.leafMembers(repo)) {
                    union.addAll(versions(member, id));
                }
                yield new ArrayList<>(union);
            }
        };
    }

    /** The nupkg asset of every stored version of a hosted package, keyed and ordered by version. */
    private TreeMap<NuGetVersion, Asset> hostedVersions(Repository repo, String id) {
        TreeMap<NuGetVersion, Asset> result = new TreeMap<>();
        String prefix = NuGetLayout.lower(id) + "/";
        for (Asset asset : storage.list(repo, prefix)) {
            nupkgVersion(asset, prefix).ifPresent(v -> result.putIfAbsent(v, asset));
        }
        return result;
    }

    /** The version of a stored {@code {id}/{version}/{id}.{version}.nupkg}, or empty for any other asset. */
    private static Optional<NuGetVersion> nupkgVersion(Asset asset, String idPrefix) {
        String path = asset.getPath();
        if (!path.startsWith(idPrefix)) {
            return Optional.empty();
        }
        String[] s = path.split("/");
        if (s.length != 3 || !s[2].endsWith(".nupkg")) {
            return Optional.empty();
        }
        return NuGetVersion.tryParse(s[1]);
    }

    /** A package, manifest or symbol file, wherever the repository type keeps it. */
    private Optional<StoredContent> file(Repository repo, String id, String version, NuGetPath.Kind kind) {
        return switch (repo.getType()) {
            case HOSTED -> storage.find(repo, storagePath(id, version, kind));
            case PROXY -> upstream.flatFile(repo, id, version, kind);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = file(member, id, version, kind);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    private static String storagePath(String id, String version, NuGetPath.Kind kind) {
        return switch (kind) {
            case NUSPEC -> NuGetLayout.nuspecPath(id, version);
            case SYMBOLS -> NuGetLayout.snupkgPath(id, version);
            default -> NuGetLayout.nupkgPath(id, version);
        };
    }

    /** The registration index for {@code baseUrl}, which for a group is the group's own URL. */
    Optional<ObjectNode> registrationIndex(Repository repo, String id, String baseUrl) {
        return switch (repo.getType()) {
            case HOSTED -> {
                List<PackageEntry> entries = hostedEntries(repo, id);
                yield entries.isEmpty() ? Optional.empty() : Optional.of(registrations.index(baseUrl, id, entries));
            }
            case PROXY -> upstream.registrationIndex(repo, id).map(index -> registrations.rewrite(index, baseUrl, id));
            case GROUP -> {
                List<ObjectNode> indexes = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    registrationIndex(member, id, baseUrl).ifPresent(indexes::add);
                }
                yield indexes.isEmpty() ? Optional.empty() : Optional.of(registrations.merge(baseUrl, id, indexes));
            }
        };
    }

    /** All versions of a hosted package with their manifests, oldest first. */
    private List<PackageEntry> hostedEntries(Repository repo, String id) {
        List<PackageEntry> entries = new ArrayList<>();
        for (Map.Entry<NuGetVersion, Asset> e : hostedVersions(repo, id).entrySet()) {
            entries.add(entry(repo, id, e.getKey(), e.getValue(), true));
        }
        return entries;
    }

    /**
     * Describes one stored version. The manifest is read when {@code full} is set; otherwise a minimal one
     * stands in, which is enough for version lists and saves a parse per version on searches.
     */
    private PackageEntry entry(Repository repo, String id, NuGetVersion version, Asset nupkg, boolean full) {
        Nuspec nuspec = null;
        if (full) {
            Optional<StoredContent> stored = storage.find(repo, NuGetLayout.nuspecPath(id, version.toLowerCaseString()));
            if (stored.isPresent()) {
                try {
                    nuspec = NuspecReader.parse(stored.get().readAllBytes());
                } catch (IOException | NuspecReader.InvalidPackageException ex) {
                    log.warn("stored manifest of {} {} in {} is unreadable: {}", id, version, repo.getName(), ex.getMessage());
                }
            }
        }
        if (nuspec == null) {
            nuspec = Nuspec.minimal(id, version.toNormalizedString());
        }
        Instant published = nupkg.getCreatedAt() != null ? nupkg.getCreatedAt() : Instant.now();
        return new PackageEntry(nuspec, version, published, true, nupkg.getDownloadCount());
    }

    // --- search ------------------------------------------------------------------------------

    private ObjectNode search(Repository repo, SearchResponse.Query query, String baseUrl) {
        return switch (repo.getType()) {
            case HOSTED -> searches.fromPackages(baseUrl, hostedPackages(repo, query), query);
            case PROXY -> upstream.search(repo, query).map(result -> searches.rewrite(result, baseUrl)).orElseGet(searches::empty);
            case GROUP -> {
                List<ObjectNode> results = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    results.add(search(member, query.widened(), baseUrl));
                }
                yield searches.merge(results, query);
            }
        };
    }

    /**
     * Every package of a hosted repository with all of its versions; only the version that will represent
     * the package in the search result (the latest one the query allows) carries its full manifest.
     */
    private Map<String, List<PackageEntry>> hostedPackages(Repository repo, SearchResponse.Query query) {
        Map<String, TreeMap<NuGetVersion, Asset>> byId = new TreeMap<>();
        for (Asset asset : storage.list(repo, "")) {
            String[] s = asset.getPath().split("/");
            if (s.length == 3 && s[2].endsWith(".nupkg")) {
                NuGetVersion.tryParse(s[1]).ifPresent(v ->
                        byId.computeIfAbsent(s[0].toLowerCase(Locale.ROOT), k -> new TreeMap<>()).putIfAbsent(v, asset));
            }
        }
        Map<String, List<PackageEntry>> result = new LinkedHashMap<>();
        for (Map.Entry<String, TreeMap<NuGetVersion, Asset>> pkg : byId.entrySet()) {
            NuGetVersion representative = pkg.getValue().keySet().stream()
                    .filter(v -> query.prerelease() || !v.isPrerelease())
                    .reduce((a, b) -> b)
                    .orElse(null);
            List<PackageEntry> entries = new ArrayList<>();
            for (Map.Entry<NuGetVersion, Asset> v : pkg.getValue().entrySet()) {
                entries.add(entry(repo, pkg.getKey(), v.getKey(), v.getValue(), v.getKey().equals(representative)));
            }
            result.put(pkg.getKey(), entries);
        }
        return result;
    }

    // --- PUT (push) --------------------------------------------------------------------------

    private ResponseEntity<?> put(RepositoryRequest request, NuGetPath path) throws IOException {
        if (path.kind() != NuGetPath.Kind.PUBLISH && path.kind() != NuGetPath.Kind.SYMBOL_PUBLISH) {
            return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                    "Packages are pushed to " + NuGetLayout.publishUrl(request.baseUrl()));
        }
        Repository repo = request.repository();
        if (!repo.isHosted()) {
            return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly",
                    "Packages can only be pushed to hosted repositories");
        }
        Path upload = Files.createTempFile("granary-nuget-", ".nupkg");
        try {
            String fileName = copyUpload(request, upload);
            NuspecReader.NupkgContents contents;
            try (InputStream in = Files.newInputStream(upload)) {
                contents = NuspecReader.readNupkg(in);
            }
            Nuspec nuspec = contents.nuspec();
            if (!PACKAGE_ID.matcher(nuspec.id()).matches()) {
                throw new NuspecReader.InvalidPackageException("'" + nuspec.id() + "' is not a valid package id");
            }
            NuGetVersion version = NuGetVersion.tryParse(nuspec.version()).orElseThrow(() ->
                    new NuspecReader.InvalidPackageException("'" + nuspec.version() + "' is not a valid NuGet version"));
            String id = nuspec.lowerCaseId();
            String v = version.toLowerCaseString();
            StorageService.ComponentCoordinates coordinates =
                    new StorageService.ComponentCoordinates("", nuspec.id(), version.toNormalizedString());
            boolean symbols = path.kind() == NuGetPath.Kind.SYMBOL_PUBLISH || nuspec.isSymbolsPackage()
                    || (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".snupkg"));
            if (symbols) {
                // symbol packages are re-pushed freely: they carry no API surface, only debugging aids
                try (InputStream in = Files.newInputStream(upload)) {
                    storage.store(repo, NuGetLayout.snupkgPath(id, v), in, null, Asset.Origin.UPLOADED, false, coordinates);
                }
                log.info("stored symbols {} {} in {}", nuspec.id(), version, repo.getName());
                return ResponseEntity.status(HttpStatus.CREATED).build();
            }
            if (!repo.isAllowRedeploy() && storage.exists(repo, NuGetLayout.nupkgPath(id, v))) {
                return ContentResponses.error(HttpStatus.CONFLICT, "repository.nuget.versionExists",
                        "Package " + nuspec.id() + " " + version + " already exists and redeployment is disabled");
            }
            try (InputStream in = Files.newInputStream(upload)) {
                storage.store(repo, NuGetLayout.nupkgPath(id, v), in, null, Asset.Origin.UPLOADED, false, coordinates);
            }
            storage.store(repo, NuGetLayout.nuspecPath(id, v), new ByteArrayInputStream(contents.nuspecXml()),
                    "application/xml", Asset.Origin.UPLOADED, false, coordinates);
            log.info("stored {} {} in {}", nuspec.id(), version, repo.getName());
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } finally {
            Files.deleteIfExists(upload);
        }
    }

    /**
     * Copies the uploaded package to {@code target}: the file part of a multipart request (the NuGet
     * client sends one named {@code package}) or the raw body. Returns the client's file name, if any.
     *
     * <p>The multipart request is looked up through the wrapper chain because the security filters wrap
     * the request before it reaches the handler; a plain {@code instanceof} would miss it.
     */
    private static String copyUpload(RepositoryRequest request, Path target) throws IOException {
        HttpServletRequest http = request.http();
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(http, MultipartHttpServletRequest.class);
        if (multipart != null) {
            for (MultipartFile file : multipart.getFileMap().values()) {
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return file.getOriginalFilename();
            }
            throw new NuspecReader.InvalidPackageException("The multipart upload contains no file");
        }
        String contentType = http.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            // multipart support was not applied by the framework; fall back to the Servlet API
            Collection<Part> parts;
            try {
                parts = http.getParts();
            } catch (ServletException | IllegalStateException e) {
                throw new NuspecReader.InvalidPackageException("The multipart upload cannot be read: " + e.getMessage(), e);
            }
            for (Part part : parts) {
                if (part.getSubmittedFileName() != null || "package".equals(part.getName())) {
                    try (InputStream in = part.getInputStream()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return part.getSubmittedFileName();
                }
            }
            throw new NuspecReader.InvalidPackageException("The multipart upload contains no file");
        }
        try (InputStream in = request.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return null;
    }

    // --- DELETE ------------------------------------------------------------------------------

    private ResponseEntity<?> delete(RepositoryRequest request, NuGetPath path) {
        if (path.kind() != NuGetPath.Kind.PACKAGE_VERSION) {
            return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                    "Delete a version with DELETE " + NuGetLayout.publishUrl(request.baseUrl()) + "/{id}/{version}");
        }
        Repository repo = request.repository();
        if (!repo.isHosted()) {
            return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly",
                    "Packages can only be deleted from hosted repositories");
        }
        Optional<NuGetVersion> version = NuGetVersion.tryParse(path.version());
        if (version.isEmpty()) {
            return ContentResponses.error(HttpStatus.BAD_REQUEST, "repository.nuget.invalidVersion",
                    "'" + path.version() + "' is not a valid NuGet version");
        }
        String prefix = NuGetLayout.versionPrefix(path.id(), version.get().toLowerCaseString());
        int deleted = 0;
        for (Asset asset : storage.list(repo, prefix)) {
            if (asset.getPath().startsWith(prefix) && storage.delete(repo, asset.getPath())) {
                deleted++;
            }
        }
        if (deleted == 0) {
            return notFound(path.id());
        }
        log.info("deleted {} {} from {}", path.id(), version.get(), repo.getName());
        return ResponseEntity.noContent().build();
    }

    // --- helpers -----------------------------------------------------------------------------

    private ResponseEntity<?> json(JsonNode document) {
        byte[] bytes = json.writeValueAsBytes(document);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noCache())
                .contentLength(bytes.length)
                .body(bytes);
    }

    private static ResponseEntity<?> notFound(String id) {
        return ContentResponses.notFound("Package '" + id + "' not found");
    }
}
