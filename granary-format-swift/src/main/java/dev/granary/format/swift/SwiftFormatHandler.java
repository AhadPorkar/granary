package dev.granary.format.swift;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Swift Package Registry Service protocol (SE-0292, publishing per SE-0321) as SwiftPM 5.7 and later speak
 * it, for hosted, proxy and group repositories.
 *
 * <p>Hosted repositories take releases through {@code PUT <scope>/<name>/<version>} (the multipart request
 * {@code swift package-registry publish} sends), validate the source archive and metadata, and serve release
 * lists, release metadata, manifests (with their version-specific variants) and archives from what is stored.
 * Proxies front another Swift registry with the same paths and cache it offline-first. Groups merge the release
 * lists of their members (the first member listing a version owns it) and serve everything about one release
 * from its owner. {@code POST login} (and {@code POST} on the repository URL, which is where SwiftPM sends it
 * when the registry URL has a path) checks credentials, {@code identifiers?url=} maps source repository URLs to
 * package identities, and {@code DELETE} removes releases (a Granary extension).
 */
@org.springframework.stereotype.Component
public class SwiftFormatHandler implements FormatHandler {

    public static final String FORMAT = "swift";

    public static final String SETTING_REQUIRE_SIGNATURE = "swift.requireSignature";
    public static final String SETTING_MAX_ARCHIVE_MB = "swift.maxArchiveSizeMb";

    static final String SIGNATURE_FORMAT_HEADER = "X-Swift-Package-Signature-Format";
    static final String SIGNATURE_HEADER = "X-Swift-Package-Signature";
    static final String SERVICE_DOC = "https://github.com/swiftlang/swift-package-manager/blob/main/Documentation/PackageRegistry/Registry.md";

    private static final Set<String> BOOLEANS = Set.of("true", "false", "1", "0", "yes", "no");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9]\\d{0,6}");
    private static final Pattern SIGNATURE_FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final StorageService storage;
    private final GroupResolver groups;
    private final SwiftDocuments documents;
    private final SwiftResponses responses;
    private final SwiftHosted hosted;
    private final SwiftUpstream upstream;
    private final Path tmpDir;

    public SwiftFormatHandler(StorageService storage, ProxyService proxy, UpstreamClient client, UpstreamHealth health,
                              NegativeCache negativeCache, GroupResolver groups, ObjectMapper json, GranaryProperties properties) {
        this.storage = storage;
        this.groups = groups;
        this.documents = new SwiftDocuments(json);
        this.responses = new SwiftResponses(json);
        this.hosted = new SwiftHosted(storage, documents);
        this.upstream = new SwiftUpstream(proxy, client, health, negativeCache, storage, json);
        this.tmpDir = properties.getDataDir().resolve("tmp").resolve(FORMAT);
    }

    // --- identity -----------------------------------------------------------------------------------

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Swift package registry";
    }

    @Override
    public Set<RepositoryType> supportedTypes() {
        return EnumSet.allOf(RepositoryType.class);
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.hosted("swift-hosted", FORMAT, "Your own Swift packages (swift package-registry publish)"),
                RepositoryTemplate.group("swift-group", FORMAT, List.of("swift-hosted"),
                        "Single registry URL for swift package-registry set; add proxies of other Swift registries as members"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return List.of(
                SettingDescriptor.bool(SETTING_REQUIRE_SIGNATURE, "Require signed releases", false,
                                "Refuse releases published without a source archive signature (swift package-registry publish "
                                        + "--signing-identity or --private-key-path with --cert-chain-paths).")
                        .forTypes(RepositoryType.HOSTED),
                SettingDescriptor.integer(SETTING_MAX_ARCHIVE_MB, "Largest source archive (MB)", null,
                                "Refuse source archives larger than this many megabytes; empty means no limit.")
                        .forTypes(RepositoryType.HOSTED));
    }

    @Override
    public void validate(Repository repository) {
        repository.setting(SETTING_REQUIRE_SIGNATURE).ifPresent(value -> {
            if (!BOOLEANS.contains(value.toLowerCase(Locale.ROOT))) {
                throw GranaryException.badRequest("repository.swift.requireSignature.invalid",
                        SETTING_REQUIRE_SIGNATURE + " must be true or false");
            }
        });
        repository.setting(SETTING_MAX_ARCHIVE_MB).ifPresent(value -> {
            if (!POSITIVE_INTEGER.matcher(value).matches()) {
                throw GranaryException.badRequest("repository.swift.maxArchiveSizeMb.invalid",
                        SETTING_MAX_ARCHIVE_MB + " must be a whole number of megabytes between 1 and 9999999");
            }
        });
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return SwiftSnippets.build(context);
    }

    @Override
    public boolean isMetadataPath(String path) {
        return SwiftLayout.isMetadata(path);
    }

    /**
     * Reads need READ; so does logging in, which only proves the caller may use the repository; everything else
     * needs WRITE.
     *
     * <p>An anonymous caller without that privilege is let through as {@link AccessLevel#NONE} and refused by
     * {@link #handle} instead: the server's own refusal carries a {@code WWW-Authenticate: Basic} challenge, and
     * SwiftPM on Linux (swift-corelibs-foundation) never completes a request answered with a challenge, so
     * {@code swift package resolve} or {@code publish} would hang instead of reporting the missing login.
     * Callers that have the privilege, and authenticated callers that lack it (403), go through the normal checks.
     */
    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        AccessLevel level = protocolAccess(request);
        if (request.principal().anonymous() && !permitted(request, level)) {
            return AccessLevel.NONE;
        }
        return level;
    }

    static AccessLevel protocolAccess(RepositoryRequest request) {
        if (request.isGet() || "OPTIONS".equals(request.method())) {
            return AccessLevel.READ;
        }
        if ("POST".equals(request.method())) {
            SwiftPath.Kind kind = SwiftPath.parse(request.path()).kind();
            if (kind == SwiftPath.Kind.LOGIN || kind == SwiftPath.Kind.ROOT) {
                return AccessLevel.READ;
            }
        }
        return AccessLevel.WRITE;
    }

    private static boolean permitted(RepositoryRequest request, AccessLevel level) {
        String name = request.repository().getName();
        return switch (level) {
            case NONE -> true;
            case READ -> request.principal().canRead(name);
            case WRITE -> request.principal().canWrite(name);
        };
    }

    // --- dispatch -----------------------------------------------------------------------------------

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        SwiftPath path = SwiftPath.parse(request.path());
        try {
            AccessLevel level = protocolAccess(request);
            if (!permitted(request, level)) {
                // only anonymous callers get here (see requiredAccess): a 401 without a challenge
                throw new SwiftProblem(HttpStatus.UNAUTHORIZED, "auth.required", "authentication required for "
                        + level.name().toLowerCase(Locale.ROOT) + " access to repository '" + request.repository().getName()
                        + "'; log in with swift package-registry login");
            }
            if (hasEncodedSeparator(request)) {
                throw SwiftProblem.badRequest("swift.path.invalid",
                        "Illegal path: encoded slashes (%2F) and backslashes (%5C) are not allowed");
            }
            if (path.kind() == SwiftPath.Kind.INVALID) {
                throw SwiftProblem.badRequest("swift.path.invalid", path.error());
            }
            return switch (request.method()) {
                case "GET", "HEAD" -> {
                    checkAccept(request);
                    yield get(request, path);
                }
                case "PUT" -> put(request, path);
                case "POST" -> post(request, path);
                case "DELETE" -> delete(request, path);
                case "OPTIONS" -> options();
                default -> throw new SwiftProblem(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                        request.method() + " is not supported by Swift package registries");
            };
        } catch (SwiftProblem p) {
            if (p.status() == HttpStatus.METHOD_NOT_ALLOWED) {
                String allow = allowedMethods(request.repository(), path);
                return responses.problem(request, p.status(), p.code(), p.detail(), h -> h.set(HttpHeaders.ALLOW, allow));
            }
            return responses.problem(request, p);
        }
    }

    /** The {@code Allow} header of a {@code 405}: what the endpoint at {@code path} accepts in this repository. */
    static String allowedMethods(Repository repo, SwiftPath path) {
        boolean writable = repo.isHosted();
        return switch (path.kind()) {
            case ROOT -> "GET, HEAD, POST";
            case LOGIN -> "POST";
            case PACKAGE -> writable ? "GET, HEAD, DELETE" : "GET, HEAD";
            case RELEASE -> writable ? "GET, HEAD, PUT, DELETE" : "GET, HEAD";
            default -> "GET, HEAD";
        };
    }

    /**
     * Whether the still percent-encoded request path hides a separator ({@code %2F}) or a backslash ({@code %5C}).
     * No scope, name, version or manifest name contains either and SwiftPM never sends one, but the path the
     * handler sees is already decoded, so {@code acme%2Fdemo/1.0.0} would otherwise be read as {@code acme/demo/1.0.0}.
     * The query string (where {@code identifiers?url=} carries its URL) is not part of the request URI.
     */
    static boolean hasEncodedSeparator(RepositoryRequest request) {
        String uri = request.http() == null ? null : request.http().getRequestURI();
        if (uri == null) {
            return false;
        }
        String lower = uri.toLowerCase(Locale.ROOT);
        return lower.contains("%2f") || lower.contains("%5c");
    }

    private static void checkAccept(RepositoryRequest request) {
        switch (SwiftAccept.check(request.header(HttpHeaders.ACCEPT))) {
            case UNSUPPORTED_VERSION -> throw new SwiftProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "swift.apiVersion.unsupported",
                    "unsupported API version");
            case INVALID_VERSION -> throw SwiftProblem.badRequest("swift.apiVersion.invalid", "invalid API version");
            case SUPPORTED -> {
                // version 1, the only one there is
            }
        }
    }

    private ResponseEntity<?> options() {
        return SwiftResponses.empty(HttpStatus.NO_CONTENT, h -> {
            h.setAllow(Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.POST, HttpMethod.DELETE, HttpMethod.OPTIONS));
            h.add(HttpHeaders.LINK, "<" + SERVICE_DOC + ">; rel=\"service-doc\"");
        });
    }

    // --- GET ----------------------------------------------------------------------------------------

    private ResponseEntity<?> get(RepositoryRequest request, SwiftPath path) {
        return switch (path.kind()) {
            case ROOT -> responses.json(request, info(request), null);
            case AVAILABILITY -> SwiftResponses.empty(HttpStatus.OK, null);
            case LOGIN -> throw new SwiftProblem(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed", "Log in with POST");
            case IDENTIFIERS -> identifiers(request);
            case PACKAGE -> releaseList(request, path.identity());
            case RELEASE -> releaseMetadata(request, path.identity(), path.version());
            case MANIFEST -> manifest(request, path.identity(), path.version(), request.query("swift-version"));
            case ARCHIVE -> archive(request, path.identity(), path.version());
            case UNKNOWN, INVALID -> responses.notFound(request, "not found");
        };
    }

    private ObjectNode info(RepositoryRequest request) {
        ObjectNode doc = documents.mapper().createObjectNode();
        doc.put("format", FORMAT);
        doc.put("repository", request.repository().getName());
        doc.put("type", request.repository().getType().name().toLowerCase(Locale.ROOT));
        doc.put("url", request.baseUrl());
        doc.put("specification", SERVICE_DOC);
        return doc;
    }

    // --- release lists ------------------------------------------------------------------------------

    /** The releases a repository knows for a package; empty when it does not know the package at all. */
    Optional<Map<String, SwiftDocuments.Release>> releases(Repository repo, SwiftIdentity id) {
        return switch (repo.getType()) {
            case HOSTED -> {
                Map<String, SwiftDocuments.Release> releases = hosted.releases(repo, id);
                yield releases.isEmpty() ? Optional.empty() : Optional.of(releases);
            }
            case PROXY -> upstream.releases(repo, id).map(doc -> SwiftDocuments.parseReleases(doc, repo));
            case GROUP -> {
                List<Map<String, SwiftDocuments.Release>> parts = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    releases(member, id).ifPresent(parts::add);
                }
                yield parts.isEmpty() ? Optional.empty() : Optional.of(SwiftDocuments.merge(parts));
            }
        };
    }

    private ResponseEntity<?> releaseList(RepositoryRequest request, SwiftIdentity id) {
        Optional<Map<String, SwiftDocuments.Release>> releases = releases(request.repository(), id);
        if (releases.isEmpty()) {
            return responses.notFound(request, "package " + id.id() + " not found");
        }
        List<String> available = SwiftDocuments.available(releases.get());
        List<String> repositoryUrls = available.isEmpty() ? List.of()
                : releaseDocument(request.repository(), id, available.getFirst())
                .map(doc -> SwiftMetadata.repositoryUrls(doc.path("metadata"))).orElse(List.of());
        ObjectNode doc = documents.releasesDocument(request.baseUrl(), id, releases.get().values());
        return responses.json(request, doc, h -> SwiftDocuments.releasesLinks(request.baseUrl(), id, available, repositoryUrls)
                .ifPresent(link -> h.set(HttpHeaders.LINK, link)));
    }

    // --- release metadata ---------------------------------------------------------------------------

    /**
     * The member of a group that owns a release: the first leaf member whose release list has the version, the
     * same member {@link SwiftDocuments#merge} took the entry from. Empty when no member lists it (for example a
     * proxy whose list cannot be fetched right now).
     */
    private Optional<Repository> owner(Repository group, SwiftIdentity id, String version) {
        for (Repository member : groups.leafMembers(group)) {
            boolean listed = releases(member, id).map(r -> r.containsKey(version)).orElse(false);
            if (listed) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    /** The members to ask for one release of a group: its owner first, then the others in order. */
    private List<Repository> candidates(Repository repo, SwiftIdentity id, String version) {
        if (!repo.isGroup()) {
            return List.of(repo);
        }
        List<Repository> members = new ArrayList<>(groups.leafMembers(repo));
        owner(repo, id, version).ifPresent(owner -> {
            members.removeIf(m -> m.getId().equals(owner.getId()));
            members.addFirst(owner);
        });
        return members;
    }

    Optional<ObjectNode> releaseDocument(Repository repo, SwiftIdentity id, String version) {
        for (Repository member : candidates(repo, id, version)) {
            Optional<ObjectNode> doc = switch (member.getType()) {
                case HOSTED -> hosted.releaseDocument(member, id, version);
                case PROXY -> upstream.release(member, id, version);
                case GROUP -> Optional.empty(); // leaf members only
            };
            if (doc.isPresent()) {
                return doc;
            }
        }
        return Optional.empty();
    }

    private ResponseEntity<?> releaseMetadata(RepositoryRequest request, SwiftIdentity id, String version) {
        Optional<ObjectNode> doc = releaseDocument(request.repository(), id, version);
        if (doc.isEmpty()) {
            return responses.notFound(request, "release " + id.id() + " " + version + " not found");
        }
        List<String> available = releases(request.repository(), id).map(SwiftDocuments::available).orElse(List.of());
        return responses.json(request, doc.get(), h -> SwiftDocuments.releaseLinks(request.baseUrl(), id, available, version)
                .ifPresent(link -> h.set(HttpHeaders.LINK, link)));
    }

    // --- manifests ----------------------------------------------------------------------------------

    /** The default manifest of a release with its variants, from the member that has it. */
    private Optional<SwiftUpstream.Manifest> defaultManifest(Repository member, SwiftIdentity id, String version) {
        return switch (member.getType()) {
            case HOSTED -> hosted.manifest(member, id, version, SwiftManifests.MANIFEST)
                    .map(content -> new SwiftUpstream.Manifest(content, hosted.alternates(member, id, version)));
            case PROXY -> upstream.manifest(member, id, version);
            case GROUP -> Optional.empty();
        };
    }

    private ResponseEntity<?> manifest(RepositoryRequest request, SwiftIdentity id, String version, String swiftVersion) {
        for (Repository member : candidates(request.repository(), id, version)) {
            Optional<SwiftUpstream.Manifest> manifest = defaultManifest(member, id, version);
            if (manifest.isEmpty()) {
                continue;
            }
            String manifestUrl = SwiftDocuments.manifestUrl(request.baseUrl(), id, version);
            if (swiftVersion != null && !swiftVersion.isBlank()) {
                Optional<SwiftManifests.Alternate> alternate = SwiftManifests.select(manifest.get().alternates(), swiftVersion);
                if (alternate.isEmpty()) {
                    return SwiftResponses.empty(HttpStatus.SEE_OTHER, h -> h.set(HttpHeaders.LOCATION, manifestUrl));
                }
                Optional<StoredContent> content = member.isProxy()
                        ? upstream.alternate(member, id, version, alternate.get())
                        : hosted.manifest(member, id, version, alternate.get().filename());
                if (content.isEmpty()) {
                    return responses.notFound(request, alternate.get().filename() + " of " + id.id() + " " + version + " is not available");
                }
                return serveManifest(request, content.get(), alternate.get().filename(), null);
            }
            Optional<String> links = SwiftManifests.linkHeader(manifestUrl, manifest.get().alternates());
            return serveManifest(request, manifest.get().content(), SwiftManifests.MANIFEST, links.orElse(null));
        }
        return responses.notFound(request, "release " + id.id() + " " + version + " not found");
    }

    private ResponseEntity<?> serveManifest(RepositoryRequest request, StoredContent content, String filename, String links) {
        ResponseEntity<?> response = SwiftResponses.file(request, content, SwiftResponses.SWIFT, h -> {
            h.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            if (links != null) {
                h.set(HttpHeaders.LINK, links);
            }
        });
        recordDownload(request, response, content);
        return response;
    }

    // --- archives -----------------------------------------------------------------------------------

    private ResponseEntity<?> archive(RepositoryRequest request, SwiftIdentity id, String version) {
        for (Repository member : candidates(request.repository(), id, version)) {
            Optional<StoredContent> content = switch (member.getType()) {
                case HOSTED -> hosted.archive(member, id, version);
                case PROXY -> upstream.archive(member, id, version);
                case GROUP -> Optional.empty();
            };
            if (content.isEmpty()) {
                continue;
            }
            Optional<ObjectNode> doc = member.isHosted() ? hosted.releaseDocument(member, id, version)
                    : storage.find(member, SwiftLayout.releaseDocumentPath(id, version)).flatMap(upstream::readObject);
            Optional<String[]> signing = doc.flatMap(SwiftDocuments::signing);
            String sha256 = content.get().asset().getBlobSha256();
            ResponseEntity<?> response = SwiftResponses.file(request, content.get(), SwiftResponses.ZIP, h -> {
                h.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id.name() + "-" + version + ".zip\"");
                h.set("Digest", "sha-256=" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256)));
                signing.ifPresent(s -> {
                    h.set(SIGNATURE_FORMAT_HEADER, s[1]);
                    h.set(SIGNATURE_HEADER, s[0]);
                });
            });
            recordDownload(request, response, content.get());
            return response;
        }
        return responses.notFound(request, "release " + id.id() + " " + version + " not found");
    }

    private void recordDownload(RepositoryRequest request, ResponseEntity<?> response, StoredContent content) {
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.asset());
        }
    }

    // --- identifiers --------------------------------------------------------------------------------

    private ResponseEntity<?> identifiers(RepositoryRequest request) {
        String url = request.query("url");
        if (url == null || url.isBlank()) {
            throw SwiftProblem.badRequest("swift.identifiers.urlMissing", "the url query parameter is required");
        }
        String normalised = RepositoryUrls.normalise(url)
                .orElseThrow(() -> SwiftProblem.badRequest("swift.identifiers.urlInvalid", "'" + url + "' is not a repository URL"));
        List<String> ids = identifiers(request.repository(), url, normalised);
        if (ids.isEmpty()) {
            return responses.notFound(request, "no package identifiers are registered for " + url);
        }
        return responses.json(request, documents.identifiersDocument(ids), null);
    }

    private List<String> identifiers(Repository repo, String url, String normalised) {
        return switch (repo.getType()) {
            case HOSTED -> hosted.identifiers(repo, normalised);
            case PROXY -> upstream.identifiers(repo, url, normalised).orElse(List.of());
            case GROUP -> {
                List<String> merged = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    SwiftDocuments.addAll(merged, identifiers(member, url, normalised));
                }
                yield merged;
            }
        };
    }

    // --- POST (login) -------------------------------------------------------------------------------

    private ResponseEntity<?> post(RepositoryRequest request, SwiftPath path) {
        if (path.kind() != SwiftPath.Kind.LOGIN && path.kind() != SwiftPath.Kind.ROOT) {
            throw new SwiftProblem(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                    "POST is only used to log in; publish with PUT <scope>/<name>/<version>");
        }
        if (request.principal().anonymous()) {
            // no challenge header: SwiftPM on Linux hangs on one (see requiredAccess)
            throw new SwiftProblem(HttpStatus.UNAUTHORIZED, "auth.required", "log in with a username and password or an API token");
        }
        return SwiftResponses.empty(HttpStatus.OK, null);
    }

    // --- PUT (publish) ------------------------------------------------------------------------------

    private ResponseEntity<?> put(RepositoryRequest request, SwiftPath path) throws IOException {
        Repository repo = request.repository();
        // the last segment as sent: a version such as 1.0.0-rc.zip parses as the archive URL of 1.0.0-rc
        String literal = lastSegment(request.path());
        if ((path.kind() == SwiftPath.Kind.RELEASE || path.kind() == SwiftPath.Kind.ARCHIVE)
                && SwiftVersion.isAmbiguousInPaths(literal) && SwiftVersion.isValid(literal)) {
            throw SwiftProblem.unprocessable("swift.version.ambiguous", "version " + literal
                    + " ends in .zip or .json and cannot be told apart from the archive and metadata URLs of another version");
        }
        if (path.kind() != SwiftPath.Kind.RELEASE) {
            throw new SwiftProblem(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                    "Publish with PUT <scope>/<name>/<version> (multipart/form-data with a source-archive part)");
        }
        if (!repo.isHosted()) {
            throw readOnly(repo);
        }
        checkAccept(request);
        SwiftIdentity id = path.identity();
        String version = path.version();
        String signatureFormat = request.header(SIGNATURE_FORMAT_HEADER);
        long maxBytes = repo.setting(SETTING_MAX_ARCHIVE_MB).filter(v -> POSITIVE_INTEGER.matcher(v).matches())
                .map(v -> Long.parseLong(v) * 1024L * 1024L).orElse(0L);
        try (SwiftUpload upload = SwiftUpload.read(request, tmpDir, maxBytes)) {
            if (upload.signature() != null) {
                if (signatureFormat == null || signatureFormat.isBlank()) {
                    throw SwiftProblem.badRequest("swift.signature.formatMissing",
                            SIGNATURE_FORMAT_HEADER + " is required when the source archive is signed");
                }
                if (!SIGNATURE_FORMAT.matcher(signatureFormat.trim()).matches()) {
                    throw SwiftProblem.badRequest("swift.signature.formatInvalid", "'" + signatureFormat + "' is not a signature format");
                }
            } else if (repo.settingEnabled(SETTING_REQUIRE_SIGNATURE, false)) {
                throw SwiftProblem.unprocessable("swift.signature.required",
                        "repository '" + repo.getName() + "' only accepts signed releases");
            }
            SourceArchive archive = SourceArchive.inspect(upload.archive());
            ObjectNode metadata = SwiftMetadata.parse(documents.mapper(), upload.metadata());
            SwiftHosted.Published published = hosted.publish(repo, id, version, upload, archive, metadata,
                    signatureFormat == null ? null : signatureFormat.trim());
            String location = SwiftDocuments.releaseUrl(request.baseUrl(), id, version);
            return responses.json(request, HttpStatus.CREATED, published.document(), h -> h.set(HttpHeaders.LOCATION, location));
        }
    }

    private static String lastSegment(String path) {
        String p = path == null ? "" : path;
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private SwiftProblem readOnly(Repository repo) {
        String detail;
        if (repo.isGroup()) {
            Optional<Repository> member = groups.leafMembers(repo).stream().filter(Repository::isHosted).findFirst();
            detail = "repository '" + repo.getName() + "' is a group and read-only; " + member
                    .map(h -> "publish to its hosted member '" + h.getName() + "'")
                    .orElse("it has no hosted member to publish to");
        } else {
            detail = "repository '" + repo.getName() + "' proxies " + repo.getRemoteUrl() + " and is read-only";
        }
        return new SwiftProblem(HttpStatus.METHOD_NOT_ALLOWED, "repository.readOnly", detail);
    }

    // --- DELETE -------------------------------------------------------------------------------------

    private ResponseEntity<?> delete(RepositoryRequest request, SwiftPath path) {
        Repository repo = request.repository();
        if (path.kind() != SwiftPath.Kind.RELEASE && path.kind() != SwiftPath.Kind.PACKAGE) {
            throw new SwiftProblem(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                    "Delete a release with DELETE <scope>/<name>/<version> or a whole package with DELETE <scope>/<name>");
        }
        if (!repo.isHosted()) {
            throw readOnly(repo);
        }
        SwiftIdentity id = path.identity();
        if (path.kind() == SwiftPath.Kind.PACKAGE) {
            return hosted.deletePackage(repo, id) > 0 ? SwiftResponses.empty(HttpStatus.NO_CONTENT, null)
                    : responses.notFound(request, "package " + id.id() + " not found");
        }
        return hosted.delete(repo, id, path.version()) ? SwiftResponses.empty(HttpStatus.NO_CONTENT, null)
                : responses.notFound(request, "release " + id.id() + " " + path.version() + " not found");
    }
}
