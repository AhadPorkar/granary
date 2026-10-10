package dev.granary.format.docker;

import dev.granary.core.GranaryException;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.RootRequest;
import dev.granary.core.format.RootRoute;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.StorageService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Docker Registry HTTP API V2 / OCI Distribution Spec 1.1 handler. Everything lives under {@code v2/} inside
 * a repository; {@link #routeRoot} additionally serves the registry at the server root so container runtimes,
 * which cannot be pointed at a path, reach it as {@code host[:port]/<repository>/<image>} or, for the root
 * repository, {@code host[:port]/<image>}.
 */
@Component
public class DockerFormatHandler implements FormatHandler {

    public static final String FORMAT = "docker";
    public static final String ROOT_SETTING = "docker.rootRepository";
    public static final String CHALLENGE_SETTING = "docker.challengeAnonymous";
    static final String OCI_SUBJECT_HEADER = "OCI-Subject";
    static final String OCI_FILTERS_HEADER = "OCI-Filters-Applied";
    static final byte[] EMPTY_JSON = "{}".getBytes(StandardCharsets.UTF_8);

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Set<String> BOOLEAN_SETTINGS = Set.of(ROOT_SETTING, CHALLENGE_SETTING,
            DockerUpstream.LIBRARY_PREFIX_SETTING);

    private final DockerRegistry registry;
    private final DockerUploads uploads;
    private final GroupResolver groups;
    private final StorageService storage;

    public DockerFormatHandler(DockerRegistry registry, DockerUploads uploads, GroupResolver groups, StorageService storage) {
        this.registry = registry;
        this.uploads = uploads;
        this.groups = groups;
        this.storage = storage;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Docker / OCI";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("docker-hub", FORMAT, "https://registry-1.docker.io", "Proxy of Docker Hub")
                        .withSettings(Map.of(DockerUpstream.LIBRARY_PREFIX_SETTING, "true")),
                RepositoryTemplate.proxy("ghcr", FORMAT, "https://ghcr.io", "Proxy of the GitHub container registry"),
                RepositoryTemplate.proxy("quay", FORMAT, "https://quay.io", "Proxy of Quay.io"),
                RepositoryTemplate.hosted("docker-hosted", FORMAT, "Your own images, charts and OCI artifacts"),
                RepositoryTemplate.group("docker-group", FORMAT, List.of("docker-hosted", "docker-hub", "ghcr", "quay"),
                        "Single registry for docker, podman, containerd, helm and oras, served at the server root")
                        .withSettings(Map.of(ROOT_SETTING, "true")));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return List.of(
                SettingDescriptor.bool(ROOT_SETTING, "Registry root", false,
                        "Serve this repository at /v2/ of the server so images need no repository prefix "
                                + "(host:port/<image>). Without a flagged repository the first docker group is the root."),
                SettingDescriptor.bool(DockerUpstream.LIBRARY_PREFIX_SETTING, "Docker Hub library prefix", true,
                        "Request single-segment names such as 'alpine' upstream as 'library/alpine'. "
                                + "Defaults to on for Docker Hub and off for other registries.")
                        .forTypes(RepositoryType.PROXY),
                SettingDescriptor.bool(CHALLENGE_SETTING, "Challenge anonymous pings", true,
                        "Answer anonymous GET /v2/ with 401 and a Basic challenge. Docker only sends the credentials "
                                + "of 'docker login' after such a challenge; turn it off to let unauthenticated clients "
                                + "pull when anonymous read is enabled."));
    }

    @Override
    public void validate(Repository repository) {
        for (String key : BOOLEAN_SETTINGS) {
            Optional<String> value = repository.setting(key);
            if (value.isPresent() && !isBoolean(value.get())) {
                throw GranaryException.badRequest("repository.docker.setting.invalid",
                        "Setting '" + key + "' must be true or false, not '" + value.get() + "'");
            }
        }
    }

    private static boolean isBoolean(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("false") || v.equals("1") || v.equals("0") || v.equals("yes") || v.equals("no");
    }

    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        DockerPath path = DockerPath.parse(request.path());
        if (path.kind() == DockerPath.Kind.PING) {
            return AccessLevel.NONE;
        }
        return request.isGet() ? AccessLevel.READ : AccessLevel.WRITE;
    }

    @Override
    public boolean isMetadataPath(String path) {
        return DockerLayout.isMetadata(path);
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return DockerSnippets.of(context);
    }

    // ---- root routing ----------------------------------------------------------------------------------------

    @Override
    public Optional<RootRoute> routeRoot(RootRequest request) {
        String path = request.path();
        if (!path.equals("v2") && !path.startsWith("v2/")) {
            return Optional.empty();
        }
        List<Repository> all = request.repositories().ofFormat(FORMAT);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        String rest = path.length() > 3 ? path.substring(3) : "";
        int slash = rest.indexOf('/');
        if (slash > 0) {
            String first = rest.substring(0, slash);
            Optional<Repository> named = request.repositories().find(first).filter(r -> FORMAT.equals(r.getFormat()));
            if (named.isPresent()) {
                return Optional.of(new RootRoute(named.get().getName(), "v2/" + rest.substring(slash + 1)));
            }
        }
        return rootRepository(all).map(root -> new RootRoute(root.getName(), path));
    }

    /** The flagged root repository, else the first docker group, else the first docker repository, by name. */
    static Optional<Repository> rootRepository(List<Repository> dockerRepositories) {
        List<Repository> sorted = new ArrayList<>(dockerRepositories);
        sorted.sort(Comparator.comparing(Repository::getName));
        Optional<Repository> flagged = sorted.stream().filter(r -> r.settingEnabled(ROOT_SETTING, false)).findFirst();
        if (flagged.isPresent()) {
            return flagged;
        }
        Optional<Repository> group = sorted.stream().filter(Repository::isGroup).findFirst();
        if (group.isPresent()) {
            return group;
        }
        return sorted.stream().findFirst();
    }

    // ---- dispatch --------------------------------------------------------------------------------------------

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        DockerPath path = DockerPath.parse(request.path());
        try {
            return switch (path.kind()) {
                case PING -> ping(request);
                case CATALOG -> catalog(request);
                case MANIFEST -> manifest(request, path);
                case BLOB -> blob(request, path);
                case UPLOAD_START -> startUpload(request, path);
                case UPLOAD -> upload(request, path);
                case TAGS -> tags(request, path);
                case REFERRERS -> referrers(request, path);
                case UNKNOWN -> OciErrors.response(HttpStatus.NOT_FOUND, OciErrorCode.UNSUPPORTED,
                        "'" + request.path() + "' is not a registry endpoint", null);
            };
        } catch (DockerError e) {
            return OciErrors.response(e);
        }
    }

    private ResponseEntity<?> ping(RepositoryRequest request) {
        if (!request.isGet()) {
            throw DockerError.unsupported("only GET is supported on /v2/");
        }
        GranaryPrincipal principal = request.principal();
        Repository repo = request.repository();
        boolean challenge = repo.settingEnabled(CHALLENGE_SETTING, true);
        if (principal.anonymous() && (challenge || !principal.canRead(repo.getName()))) {
            throw DockerError.unauthorized("authentication required for repository " + repo.getName());
        }
        return DockerResponses.json(EMPTY_JSON, MediaTypes.JSON);
    }

    // ---- manifests -------------------------------------------------------------------------------------------

    private ResponseEntity<?> manifest(RepositoryRequest request, DockerPath path) throws IOException {
        String name = validName(path.name());
        String reference = validReference(path.reference());
        Repository repo = request.repository();
        return switch (request.method()) {
            case "GET", "HEAD" -> {
                Optional<DockerRegistry.Found> found = registry.manifest(repo, name, reference,
                        DockerResponses.listHeader(request, HttpHeaders.ACCEPT));
                if (found.isEmpty()) {
                    throw DockerError.notFound(OciErrorCode.MANIFEST_UNKNOWN, "manifest " + reference + " of " + name + " is unknown",
                            Map.of("name", name, "reference", reference));
                }
                yield serve(request, found.get(), false);
            }
            case "PUT" -> {
                requireWritable(request);
                byte[] body = readManifest(request);
                String contentType = request.header(HttpHeaders.CONTENT_TYPE);
                DockerRegistry.Pushed pushed = registry.putManifest(repo, name, reference, body,
                        contentType == null ? null : MediaTypes.stripParameters(contentType));
                ResponseEntity.BodyBuilder builder = DockerResponses.status(HttpStatus.CREATED)
                        .header(HttpHeaders.LOCATION, v2Base(request) + clientName(request, name) + "/manifests/" + pushed.digest())
                        .header(DockerResponses.DIGEST_HEADER, pushed.digest().toString())
                        .contentLength(0);
                pushed.subject().ifPresent(subject -> builder.header(OCI_SUBJECT_HEADER, subject.toString()));
                yield builder.build();
            }
            case "DELETE" -> {
                requireWritable(request);
                if (!registry.deleteManifest(repo, name, reference)) {
                    throw DockerError.notFound(OciErrorCode.MANIFEST_UNKNOWN, "manifest " + reference + " of " + name + " is unknown",
                            Map.of("name", name, "reference", reference));
                }
                yield DockerResponses.empty(HttpStatus.ACCEPTED);
            }
            default -> throw DockerError.unsupported(request.method() + " is not supported on manifests");
        };
    }

    private byte[] readManifest(RepositoryRequest request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = request.body()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                if (out.size() > Manifest.MAX_SIZE) {
                    throw DockerError.manifestInvalid("manifest exceeds " + Manifest.MAX_SIZE + " bytes");
                }
            }
        }
        return out.toByteArray();
    }

    private ResponseEntity<?> serve(RepositoryRequest request, DockerRegistry.Found found, boolean rangeable) {
        ResponseEntity<?> response = DockerResponses.content(request, found.content(), found.digest(), found.contentType(), rangeable);
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(found.content().asset());
        }
        return response;
    }

    // ---- blobs -----------------------------------------------------------------------------------------------

    private ResponseEntity<?> blob(RepositoryRequest request, DockerPath path) throws IOException {
        String name = validName(path.name());
        Digest digest = validDigest(path.reference());
        Repository repo = request.repository();
        return switch (request.method()) {
            case "GET", "HEAD" -> {
                Optional<DockerRegistry.Found> found = registry.blob(repo, name, digest);
                if (found.isEmpty()) {
                    throw DockerError.notFound(OciErrorCode.BLOB_UNKNOWN, "blob " + digest + " is unknown",
                            Map.of("digest", digest.toString()));
                }
                yield serve(request, found.get(), true);
            }
            case "DELETE" -> {
                requireWritable(request);
                if (!registry.deleteBlob(repo, digest)) {
                    throw DockerError.notFound(OciErrorCode.BLOB_UNKNOWN, "blob " + digest + " is unknown",
                            Map.of("digest", digest.toString()));
                }
                yield DockerResponses.empty(HttpStatus.ACCEPTED);
            }
            default -> throw DockerError.unsupported(request.method() + " is not supported on blobs");
        };
    }

    // ---- uploads ---------------------------------------------------------------------------------------------

    private ResponseEntity<?> startUpload(RepositoryRequest request, DockerPath path) throws IOException {
        String name = validName(path.name());
        if (!request.method().equals("POST")) {
            throw DockerError.unsupported(request.method() + " is not supported on blob uploads");
        }
        requireWritable(request);
        Repository repo = request.repository();
        String mount = request.query("mount");
        if (mount != null && !mount.isBlank()) {
            Optional<Digest> digest = Digest.parse(mount);
            if (digest.isPresent() && registry.blobExists(repo, digest.get())) {
                return blobCreated(request, name, digest.get());
            }
        }
        DockerUploads.Session session = uploads.start(repo, name);
        String digest = request.query("digest");
        if (digest != null && !digest.isBlank()) {
            Digest expected = validDigest(digest);
            try (InputStream in = request.body()) {
                uploads.append(session, in);
            }
            uploads.complete(repo, session, expected);
            return blobCreated(request, name, expected);
        }
        return DockerResponses.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, uploadUrl(request, name, session))
                .header(DockerResponses.UPLOAD_UUID_HEADER, session.uuid())
                .header(HttpHeaders.RANGE, "0-0")
                .contentLength(0)
                .build();
    }

    private ResponseEntity<?> upload(RepositoryRequest request, DockerPath path) throws IOException {
        String name = validName(path.name());
        Repository repo = request.repository();
        if (!request.isGet()) {
            requireWritable(request);
        }
        DockerUploads.Session session = uploads.find(repo, path.uuid()).orElseThrow(() ->
                DockerError.notFound(OciErrorCode.BLOB_UPLOAD_UNKNOWN, "upload " + path.uuid() + " is unknown or expired",
                        Map.of("uuid", path.uuid())));
        return switch (request.method()) {
            case "GET", "HEAD" -> DockerResponses.status(HttpStatus.NO_CONTENT)
                    .header(HttpHeaders.LOCATION, uploadUrl(request, name, session))
                    .header(DockerResponses.UPLOAD_UUID_HEADER, session.uuid())
                    .header(HttpHeaders.RANGE, rangeOf(session.size()))
                    .build();
            case "PATCH" -> {
                long size = session.size();
                String contentRange = request.header(HttpHeaders.CONTENT_RANGE);
                if (contentRange != null && !contentRange.isBlank()) {
                    long start = contentRangeStart(contentRange);
                    if (start != size) {
                        yield DockerResponses.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                                .header(HttpHeaders.LOCATION, uploadUrl(request, name, session))
                                .header(DockerResponses.UPLOAD_UUID_HEADER, session.uuid())
                                .header(HttpHeaders.RANGE, rangeOf(size))
                                .contentLength(0)
                                .build();
                    }
                }
                long total;
                try (InputStream in = request.body()) {
                    total = uploads.append(session, in);
                }
                yield DockerResponses.status(HttpStatus.ACCEPTED)
                        .header(HttpHeaders.LOCATION, uploadUrl(request, name, session))
                        .header(DockerResponses.UPLOAD_UUID_HEADER, session.uuid())
                        .header(HttpHeaders.RANGE, rangeOf(total))
                        .contentLength(0)
                        .build();
            }
            case "PUT" -> {
                String digest = request.query("digest");
                if (digest == null || digest.isBlank()) {
                    throw DockerError.badRequest(OciErrorCode.DIGEST_INVALID, "the digest query parameter is required to complete an upload");
                }
                Digest expected = validDigest(digest);
                try (InputStream in = request.body()) {
                    uploads.append(session, in);
                }
                uploads.complete(repo, session, expected);
                yield blobCreated(request, name, expected);
            }
            case "DELETE" -> {
                uploads.cancel(session);
                yield DockerResponses.empty(HttpStatus.NO_CONTENT);
            }
            default -> throw DockerError.unsupported(request.method() + " is not supported on blob uploads");
        };
    }

    private ResponseEntity<?> blobCreated(RepositoryRequest request, String name, Digest digest) {
        return DockerResponses.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, v2Base(request) + clientName(request, name) + "/blobs/" + digest)
                .header(DockerResponses.DIGEST_HEADER, digest.toString())
                .contentLength(0)
                .build();
    }

    private String uploadUrl(RepositoryRequest request, String name, DockerUploads.Session session) {
        return v2Base(request) + clientName(request, name) + "/blobs/uploads/" + session.uuid();
    }

    static String rangeOf(long size) {
        return "0-" + (size > 0 ? size - 1 : 0);
    }

    /** The start offset of a {@code Content-Range: start-end[/size]} (or {@code bytes start-end/size}) header. */
    static long contentRangeStart(String header) {
        String value = header.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("bytes")) {
            value = value.substring(5).trim();
            if (value.startsWith("=")) {
                value = value.substring(1).trim();
            }
        }
        int dash = value.indexOf('-');
        if (dash <= 0) {
            throw DockerError.badRequest(OciErrorCode.BLOB_UPLOAD_INVALID, "invalid Content-Range '" + header + "'");
        }
        try {
            return Long.parseLong(value.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            throw DockerError.badRequest(OciErrorCode.BLOB_UPLOAD_INVALID, "invalid Content-Range '" + header + "'");
        }
    }

    // ---- listings --------------------------------------------------------------------------------------------

    private ResponseEntity<?> tags(RepositoryRequest request, DockerPath path) throws IOException {
        String name = validName(path.name());
        if (!request.isGet()) {
            throw DockerError.unsupported(request.method() + " is not supported on the tag list");
        }
        Optional<SortedSet<String>> tags = registry.tags(request.repository(), name);
        if (tags.isEmpty()) {
            throw DockerError.notFound(OciErrorCode.NAME_UNKNOWN, "image " + name + " is unknown", Map.of("name", name));
        }
        String clientName = clientName(request, name);
        Page page = page(tags.get(), request.query("n"), request.query("last"));
        ObjectNode body = NODES.objectNode();
        body.put("name", clientName);
        ArrayNode array = body.putArray("tags");
        page.items().forEach(array::add);
        return listing(request, body, page, v2Base(request) + clientName + "/tags/list");
    }

    private ResponseEntity<?> catalog(RepositoryRequest request) {
        if (!request.isGet()) {
            throw DockerError.unsupported(request.method() + " is not supported on the catalog");
        }
        Page page = page(registry.catalog(request.repository()), request.query("n"), request.query("last"));
        ObjectNode body = NODES.objectNode();
        ArrayNode array = body.putArray("repositories");
        page.items().forEach(array::add);
        return listing(request, body, page, v2Base(request) + "_catalog");
    }

    private ResponseEntity<?> listing(RepositoryRequest request, ObjectNode body, Page page, String url) {
        byte[] bytes = registry.mapper().writeValueAsBytes(body);
        ResponseEntity.BodyBuilder builder = DockerResponses.status(HttpStatus.OK)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .contentLength(bytes.length)
                .cacheControl(org.springframework.http.CacheControl.noCache());
        if (page.next() != null) {
            builder.header(HttpHeaders.LINK, "<" + url + "?n=" + page.n() + "&last="
                    + URLEncoder.encode(page.next(), StandardCharsets.UTF_8) + ">; rel=\"next\"");
        }
        return request.isHead() ? builder.build() : builder.body(bytes);
    }

    /** One page of a sorted listing: the items after {@code last}, at most {@code n}, and the cursor of the next page. */
    record Page(List<String> items, int n, String next) {
    }

    static Page page(SortedSet<String> all, String nParam, String lastParam) {
        int n = -1;
        if (nParam != null && !nParam.isBlank()) {
            try {
                n = Integer.parseInt(nParam.trim());
            } catch (NumberFormatException e) {
                throw DockerError.badRequest(OciErrorCode.UNSUPPORTED, "invalid page size '" + nParam + "'");
            }
            if (n < 0) {
                throw DockerError.badRequest(OciErrorCode.UNSUPPORTED, "invalid page size '" + nParam + "'");
            }
        }
        List<String> items = new ArrayList<>();
        String next = null;
        for (String item : all) {
            if (lastParam != null && !lastParam.isEmpty() && item.compareTo(lastParam) <= 0) {
                continue;
            }
            if (n >= 0 && items.size() >= n) {
                next = items.isEmpty() ? null : items.get(items.size() - 1);
                break;
            }
            items.add(item);
        }
        return new Page(items, n, next);
    }

    private ResponseEntity<?> referrers(RepositoryRequest request, DockerPath path) throws IOException {
        String name = validName(path.name());
        if (!request.isGet()) {
            throw DockerError.unsupported(request.method() + " is not supported on referrers");
        }
        Digest subject = validDigest(path.reference());
        String artifactType = request.query("artifactType");
        ObjectNode index = registry.referrers(request.repository(), name, subject, artifactType);
        byte[] bytes = registry.mapper().writeValueAsBytes(index);
        ResponseEntity.BodyBuilder builder = DockerResponses.status(HttpStatus.OK)
                .contentType(org.springframework.http.MediaType.parseMediaType(MediaTypes.OCI_INDEX))
                .contentLength(bytes.length)
                .cacheControl(org.springframework.http.CacheControl.noCache());
        if (artifactType != null && !artifactType.isBlank()) {
            builder.header(OCI_FILTERS_HEADER, "artifactType");
        }
        return request.isHead() ? builder.build() : builder.body(bytes);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void requireWritable(RepositoryRequest request) {
        Repository repo = request.repository();
        if (repo.isGroup()) {
            String hosted = groups.leafMembers(repo).stream()
                    .filter(Repository::isHosted)
                    .map(Repository::getName)
                    .findFirst()
                    .orElse(null);
            throw DockerError.unsupported("repository " + repo.getName() + " is a group and read-only"
                    + (hosted == null ? "" : "; push to " + hosted + " instead"));
        }
        if (repo.isProxy()) {
            throw DockerError.unsupported("repository " + repo.getName() + " proxies " + repo.getRemoteUrl() + " and is read-only");
        }
    }

    static String validName(String name) {
        if (name == null || !DockerPath.isValidName(name)) {
            throw DockerError.nameInvalid(name == null ? "" : name);
        }
        return name;
    }

    static String validReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw DockerError.badRequest(OciErrorCode.TAG_INVALID, "a tag or digest is required");
        }
        if (Digest.isDigest(reference) || DockerPath.isValidTag(reference)) {
            return reference;
        }
        if (reference.contains(":")) {
            throw DockerError.badRequest(OciErrorCode.DIGEST_INVALID, "unsupported digest " + reference);
        }
        throw DockerError.badRequest(OciErrorCode.TAG_INVALID, "invalid tag " + reference);
    }

    static Digest validDigest(String value) {
        return Digest.parse(value == null ? "" : value.trim()).orElseThrow(() ->
                DockerError.badRequest(OciErrorCode.DIGEST_INVALID, "invalid digest '" + value + "'; only sha256:<64 hex> is supported"));
    }

    /** The {@code /v2/} base the client is talking to: the server root when the request came through root routing. */
    static String v2Base(RepositoryRequest request) {
        return rootPath(request) != null ? request.serverUrl() + "/v2/" : request.baseUrl() + "/v2/";
    }

    /** The image name as the client spelled it: prefixed with the repository name when it was addressed that way. */
    static String clientName(RepositoryRequest request, String name) {
        String rootPath = rootPath(request);
        if (rootPath == null) {
            return name;
        }
        String prefixed = "v2/" + request.repository().getName() + "/" + stripV2(request.path());
        return trimSlashes(rootPath).equals(trimSlashes(prefixed)) ? request.repository().getName() + "/" + name : name;
    }

    private static String rootPath(RepositoryRequest request) {
        Object value = request.http() == null ? null : request.http().getAttribute(RootRoute.ATTRIBUTE);
        return value instanceof String s ? s : null;
    }

    private static String stripV2(String path) {
        String p = trimSlashes(path);
        return p.startsWith("v2/") ? p.substring(3) : p.equals("v2") ? "" : p;
    }

    private static String trimSlashes(String path) {
        String p = path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
