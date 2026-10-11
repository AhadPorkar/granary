package dev.granary.format.composer;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
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
import dev.granary.core.storage.StorageService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Composer v2 repository protocol, as Composer 2 speaks it: {@code packages.json} with {@code metadata-url},
 * minified {@code p2} metadata (releases and {@code ~dev} branches), dist archives, search, the package list, the
 * providers and security-advisory APIs and install notifications.
 *
 * <p>Hosted repositories take zip uploads ({@code PUT packages/{vendor}/{name}/{version}} or a multipart
 * {@code POST packages}) and generate their metadata from the archives' composer.json; proxies cache Packagist
 * (or any Composer 2 repository) including every dist archive a client downloads through them; groups merge their
 * members behind one URL. Once something was installed through Granary it installs again without internet.
 */
@org.springframework.stereotype.Component
public class ComposerFormatHandler implements FormatHandler {

    public static final String FORMAT = "composer";
    public static final String PACKAGIST = "https://repo.packagist.org/";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern ADVISORY_PARAMETER = Pattern.compile("^packages(\\[\\d*])?$");
    private static final int MAX_ADVISORY_PACKAGES = 2000;
    private static final int MAX_NOTIFICATION_BYTES = 1024 * 1024;

    private final StorageService storage;
    private final ComposerHosted hosted;
    private final ComposerMetadata metadata;
    private final ObjectMapper mapper;
    private final Path tempDir;

    public ComposerFormatHandler(StorageService storage, ComposerHosted hosted, ComposerMetadata metadata,
                                 ObjectMapper mapper, GranaryProperties properties) {
        this.storage = storage;
        this.hosted = hosted;
        this.metadata = metadata;
        this.mapper = mapper;
        this.tempDir = properties.getDataDir().resolve("tmp").resolve(FORMAT);
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Composer (Packagist)";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("packagist", FORMAT, PACKAGIST,
                        "Proxy of packagist.org, including the dist archives on GitHub, GitLab and Bitbucket"),
                RepositoryTemplate.hosted("composer-hosted", FORMAT, "Your own PHP packages"),
                RepositoryTemplate.group("composer-group", FORMAT, List.of("composer-hosted", "packagist"),
                        "Single Composer repository URL for private and public packages"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return List.of(SettingDescriptor.bool(ComposerProxy.DIST_REWRITE_SETTING, "Serve dist archives", true,
                        "Point every dist URL in the proxied metadata at this repository, so archives are downloaded "
                                + "through Granary and cached; when off, clients download them from GitHub and others directly.")
                .forTypes(RepositoryType.PROXY));
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return ComposerSnippets.of(context);
    }

    @Override
    public void validate(Repository repository) {
        Optional<String> rewrite = repository.setting(ComposerProxy.DIST_REWRITE_SETTING);
        if (rewrite.isPresent()) {
            String value = rewrite.get().trim().toLowerCase(Locale.ROOT);
            if (!value.equals("true") && !value.equals("false")) {
                throw GranaryException.badRequest("repository.composer.distRewrite.invalid",
                        ComposerProxy.DIST_REWRITE_SETTING + " must be true or false");
            }
        }
    }

    /** Everything except dist archives is a mutable document. */
    @Override
    public boolean isMetadataPath(String path) {
        return ComposerPath.parse(path).isMetadata();
    }

    /**
     * Searching, listing, providers, security advisories and install notifications are reads even when Composer
     * POSTs them; uploads and deletes are writes.
     */
    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        return switch (ComposerPath.parse(request.path()).kind()) {
            case SEARCH, LIST, PROVIDERS, ADVISORIES, DOWNLOADS -> AccessLevel.READ;
            default -> request.isGet() ? AccessLevel.READ : AccessLevel.WRITE;
        };
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        ComposerPath path = ComposerPath.parse(request.path());
        try {
            return dispatch(request, path);
        } catch (ComposerError e) {
            return ComposerResponses.error(e);
        }
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, ComposerPath path) throws IOException {
        String method = request.method();
        return switch (path.kind()) {
            case ROOT, PACKAGES_JSON -> request.isGet() ? root(request) : methodNotAllowed(method);
            case P2 -> request.isGet() ? packageMetadata(request, path) : methodNotAllowed(method);
            case DIST -> request.isGet() ? dist(request, path) : methodNotAllowed(method);
            case SEARCH -> request.isGet() ? search(request) : methodNotAllowed(method);
            case LIST -> request.isGet() ? list(request) : methodNotAllowed(method);
            case PROVIDERS -> request.isGet() ? providers(request, path) : methodNotAllowed(method);
            case ADVISORIES -> request.isGet() || "POST".equals(method) ? advisories(request) : methodNotAllowed(method);
            case DOWNLOADS -> "POST".equals(method) ? downloads(request) : methodNotAllowed(method);
            case PACKAGES -> "PUT".equals(method) || "POST".equals(method) ? upload(request, null, null)
                    : methodNotAllowed(method);
            case PACKAGE -> switch (method) {
                case "PUT", "POST" -> upload(request, path.name(), null);
                case "DELETE" -> deletePackage(request, path);
                default -> methodNotAllowed(method);
            };
            case PACKAGE_VERSION -> switch (method) {
                case "PUT", "POST" -> upload(request, path.name(), path.version());
                case "DELETE" -> deleteVersion(request, path);
                default -> methodNotAllowed(method);
            };
            case INVALID -> ContentResponses.error(HttpStatus.BAD_REQUEST, "path.invalid", "Illegal path");
            case UNKNOWN -> ContentResponses.notFound();
        };
    }

    // --- reads -------------------------------------------------------------------------------------

    private ResponseEntity<?> root(RepositoryRequest request) {
        ObjectNode root = metadata.root(request.repository(), request.baseUrl());
        return ComposerResponses.json(request, mapper.writeValueAsBytes(root), null);
    }

    private ResponseEntity<?> packageMetadata(RepositoryRequest request, ComposerPath path) {
        Lookup lookup = metadata.metadata(request.repository(), path.name(), path.dev(), request.baseUrl());
        return switch (lookup.state()) {
            case FOUND -> ComposerResponses.json(request, mapper.writeValueAsBytes(lookup.document().toJson()),
                    lookup.document().lastModified());
            case MISSING_OFFLINE -> {
                // the upstream said 404 when it could still be asked; a 404 now would read as "unreachable" (503)
                ObjectNode empty = NODES.objectNode();
                empty.putObject(P2Document.PACKAGES);
                yield ComposerResponses.json(request, mapper.writeValueAsBytes(empty), null);
            }
            case MISSING, UNKNOWN -> ContentResponses.notFound("Package " + path.metadataFileName() + " not found");
        };
    }

    private ResponseEntity<?> dist(RepositoryRequest request, ComposerPath path) {
        ComposerProxy.Dist dist = metadata.distribution(request.repository(), path);
        if (!dist.isFound()) {
            if (dist.unavailable() != null) {
                return ContentResponses.error(HttpStatus.SERVICE_UNAVAILABLE, "upstream.unavailable",
                        "The archive of " + path.name() + " " + path.version() + " is not cached and " + dist.unavailable());
            }
            return ContentResponses.notFound("Archive " + path.file() + " of " + path.name() + " " + path.version() + " not found");
        }
        ResponseEntity<?> response = ContentResponses.stream(request, dist.content());
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        if (!request.isHead()) {
            storage.recordDownload(dist.content().asset());
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.file().replace("\"", "") + "\"")
                .body(response.getBody());
    }

    private ResponseEntity<?> search(RepositoryRequest request) {
        ComposerMetadata.SearchQuery query = new ComposerMetadata.SearchQuery(request.query("q"), request.query("type"),
                intParam(request, "page", 1, 1, 10_000),
                intParam(request, "per_page", ComposerMetadata.DEFAULT_PAGE_SIZE, 1, ComposerMetadata.MAX_PAGE_SIZE));
        return metadata.search(request.repository(), query, request.baseUrl())
                .<ResponseEntity<?>>map(result -> ComposerResponses.json(request, mapper.writeValueAsBytes(result), null))
                .orElseGet(() -> ContentResponses.notFound("Search is not available"));
    }

    private ResponseEntity<?> list(RepositoryRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String name : List.of("filter", "vendor", "type")) {
            String value = request.query(name);
            if (value != null && !value.isBlank()) {
                query.put(name, value.trim());
            }
        }
        return metadata.list(request.repository(), query)
                .<ResponseEntity<?>>map(result -> ComposerResponses.json(request, mapper.writeValueAsBytes(result), null))
                .orElseGet(() -> ContentResponses.notFound("The package list is not available"));
    }

    private ResponseEntity<?> providers(RepositoryRequest request, ComposerPath path) {
        return metadata.providers(request.repository(), path.name())
                .<ResponseEntity<?>>map(result -> ComposerResponses.json(request, mapper.writeValueAsBytes(result), null))
                .orElseGet(() -> ContentResponses.notFound("Providers of " + path.name() + " are not available"));
    }

    private ResponseEntity<?> advisories(RepositoryRequest request) throws IOException {
        List<String> names = advisoryPackages(request.http());
        return metadata.advisories(request.repository(), names)
                .<ResponseEntity<?>>map(result -> ComposerResponses.json(request, mapper.writeValueAsBytes(result), null))
                .orElseGet(() -> ContentResponses.notFound("Security advisories are not available"));
    }

    /**
     * The package names of an advisory request: {@code packages[]=a&packages[]=b} or PHP's
     * {@code packages[0]=a&packages[1]=b}, in the query string or a form body.
     */
    static List<String> advisoryPackages(HttpServletRequest http) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        http.getParameterMap().forEach((k, v) -> parameters.computeIfAbsent(k, x -> new ArrayList<>()).addAll(List.of(v)));
        boolean any = parameters.keySet().stream().anyMatch(k -> ADVISORY_PARAMETER.matcher(k).matches());
        String contentType = http.getContentType();
        if (!any && "POST".equals(http.getMethod()) && contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            try (InputStream in = http.getInputStream()) {
                parseForm(new String(in.readNBytes(MAX_NOTIFICATION_BYTES), StandardCharsets.UTF_8), parameters);
            }
        }
        for (Map.Entry<String, List<String>> e : parameters.entrySet()) {
            if (!ADVISORY_PARAMETER.matcher(e.getKey()).matches()) {
                continue;
            }
            for (String value : e.getValue()) {
                ComposerNames.normalise(value.trim()).ifPresent(names::add);
                if (names.size() >= MAX_ADVISORY_PACKAGES) {
                    return new ArrayList<>(names);
                }
            }
        }
        return new ArrayList<>(names);
    }

    static void parseForm(String body, Map<String, List<String>> into) {
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            try {
                String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                into.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            } catch (IllegalArgumentException ignored) {
                // a malformed escape in one pair does not invalidate the others
            }
        }
    }

    /** Composer reports what it installed; the archive downloads are already counted, so this only acknowledges. */
    private ResponseEntity<?> downloads(RepositoryRequest request) throws IOException {
        try (InputStream in = request.body()) {
            in.readNBytes(MAX_NOTIFICATION_BYTES);
        }
        byte[] body = "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).contentLength(body.length).body(body);
    }

    // --- writes ------------------------------------------------------------------------------------

    private ResponseEntity<?> upload(RepositoryRequest request, String name, String version) throws IOException {
        requireHosted(request.repository(), "upload to");
        Files.createDirectories(tempDir);
        Path file = Files.createTempFile(tempDir, "upload-", ".zip");
        try {
            UploadBody body = copyUpload(request, file);
            if (Files.size(file) == 0) {
                throw ComposerError.badRequest("The upload is empty; send the zip archive as the request body "
                        + "or as the multipart field 'package'");
            }
            String explicitVersion = version != null ? version : body.version();
            ComposerHosted.Published published = hosted.publish(request.repository(),
                    new ComposerHosted.Upload(file, body.sha1(), name, explicitVersion));
            String distUrl = ComposerDist.absolute(request.baseUrl(), published.distPath());
            ObjectNode response = NODES.objectNode();
            response.put("status", "created");
            response.put("name", published.name());
            response.put("version", published.version());
            response.put("version_normalized", published.normalizedVersion());
            response.put("dist", distUrl);
            response.put("replaced", published.replaced());
            return ComposerResponses.created(mapper.writeValueAsBytes(response),
                    request.baseUrl() + "/" + ComposerPath.metadataPath(published.name(), ComposerVersions.isDev(published.normalizedVersion())));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private record UploadBody(String sha1, String version) {
    }

    /**
     * Copies the archive to {@code target}: the multipart field {@code package} (or the only file part) of a form
     * upload, otherwise the raw request body. The version may come from a {@code version} form field or the
     * {@code ?version=} query parameter, which is read from the query string itself so that a raw body is never
     * mistaken for form parameters.
     */
    private UploadBody copyUpload(RepositoryRequest request, Path target) throws IOException {
        HttpServletRequest http = request.http();
        String version = queryParameter(http.getQueryString(), "version");
        MultipartHttpServletRequest multipart = WebUtils.getNativeRequest(http, MultipartHttpServletRequest.class);
        if (multipart != null) {
            MultipartFile file = multipart.getFile("package");
            if (file == null && multipart.getFileMap().size() == 1) {
                file = multipart.getFileMap().values().iterator().next();
            }
            if (file == null) {
                throw ComposerError.badRequest("The multipart upload has no file field 'package'");
            }
            String formVersion = multipart.getParameter("version");
            try (InputStream in = file.getInputStream()) {
                return new UploadBody(copy(in, target), version != null ? version : blankToNull(formVersion));
            }
        }
        String contentType = http.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            Part chosen = null;
            String formVersion = null;
            try {
                for (Part part : http.getParts()) {
                    if ("version".equals(part.getName()) && part.getSubmittedFileName() == null) {
                        try (InputStream in = part.getInputStream()) {
                            formVersion = new String(in.readNBytes(256), StandardCharsets.UTF_8).trim();
                        }
                    } else if ("package".equals(part.getName()) || (chosen == null && part.getSubmittedFileName() != null)) {
                        chosen = part;
                    }
                }
            } catch (ServletException | IllegalStateException e) {
                throw ComposerError.badRequest("The multipart upload cannot be read: " + e.getMessage());
            }
            if (chosen == null) {
                throw ComposerError.badRequest("The multipart upload has no file field 'package'");
            }
            try (InputStream in = chosen.getInputStream()) {
                return new UploadBody(copy(in, target), version != null ? version : blankToNull(formVersion));
            }
        }
        try (InputStream in = request.body()) {
            return new UploadBody(copy(in, target), version);
        }
    }

    /** Copies a stream to a file and returns the SHA-1 of what was copied. */
    static String copy(InputStream in, Path target) throws IOException {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (DigestInputStream digesting = new DigestInputStream(in, sha1);
             OutputStream out = Files.newOutputStream(target)) {
            digesting.transferTo(out);
        }
        return HexFormat.of().formatHex(sha1.digest());
    }

    /** One parameter of a raw query string, decoded; {@code null} when absent or blank. */
    static String queryParameter(String queryString, String name) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        parseForm(queryString, parsed);
        List<String> values = parsed.get(name);
        return values == null || values.isEmpty() ? null : blankToNull(values.getFirst());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ResponseEntity<?> deleteVersion(RepositoryRequest request, ComposerPath path) {
        requireHosted(request.repository(), "delete from");
        if (!hosted.deleteVersion(request.repository(), path.name(), path.version())) {
            throw ComposerError.notFound("Version " + path.version() + " of " + path.name() + " not found");
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> deletePackage(RepositoryRequest request, ComposerPath path) {
        requireHosted(request.repository(), "delete from");
        if (hosted.deletePackage(request.repository(), path.name()) == 0) {
            throw ComposerError.notFound("Package " + path.name() + " not found");
        }
        return ResponseEntity.noContent().build();
    }

    /** Uploads and deletes only make sense on hosted repositories; a group names the member to use instead. */
    private void requireHosted(Repository repo, String verb) {
        if (repo.isHosted()) {
            return;
        }
        String hint = "";
        if (repo.isGroup()) {
            hint = metadata.members(repo).stream().filter(Repository::isHosted).findFirst()
                    .map(member -> "; use its hosted member '" + member.getName() + "'")
                    .orElse("; it has no hosted member");
        }
        throw ComposerError.readOnly("'" + repo.getName() + "' is a " + repo.getType().name().toLowerCase(Locale.ROOT)
                + " repository, you cannot " + verb + " it" + hint);
    }

    private static ResponseEntity<?> methodNotAllowed(String method) {
        return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                method + " is not supported here by Composer repositories");
    }

    private static int intParam(RepositoryRequest request, String name, int defaultValue, int min, int max) {
        String raw = request.query(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
