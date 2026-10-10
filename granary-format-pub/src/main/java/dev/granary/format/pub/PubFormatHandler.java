package dev.granary.format.pub;

import dev.granary.core.format.AccessLevel;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Repository;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The pub repository protocol (Hosted Pub Repository Specification v2) as {@code dart pub} and
 * {@code flutter pub} speak it: package listings, version documents, advisories, archives and the
 * three-step publish, plus pub.dev's administrative endpoints for retracting and discontinuing.
 *
 * <p>Hosted repositories accept {@code dart pub publish}; proxies cache pub.dev (or any v2 repository) with
 * offline fallback; groups merge their members so a single {@code PUB_HOSTED_URL} serves everything. Every
 * {@code archive_url} a client sees points back at the repository it asked, which is what makes the cache and
 * the group work: the client downloads whatever URL the listing names.
 *
 * <h2>Authentication</h2>
 * The pub client shows the server's explanation of a 401 or 403 only when it comes as
 * {@code WWW-Authenticate: Bearer realm="pub", message="…"}. So that every refusal carries that challenge,
 * {@link #requiredAccess} lets requests the caller is not entitled to through to {@link #handle}, which
 * checks the very same rule first and answers with the pub challenge. Callers that are entitled are checked
 * by the server as usual.
 */
@org.springframework.stereotype.Component
public class PubFormatHandler implements FormatHandler {

    public static final String FORMAT = "pub";
    public static final String DEFAULT_REMOTE = "https://pub.dev/";
    static final String MISSING_AUTHENTICATION = "MissingAuthentication";
    static final String INSUFFICIENT_PERMISSIONS = "InsufficientPermissions";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final StorageService storage;
    private final PubResolver resolver;
    private final PubHosted hosted;
    private final PubUploads uploads;
    private final GroupResolver groups;
    private final ObjectMapper mapper;

    public PubFormatHandler(StorageService storage, PubResolver resolver, PubHosted hosted, PubUploads uploads,
                            GroupResolver groups, ObjectMapper mapper) {
        this.storage = storage;
        this.resolver = resolver;
        this.hosted = hosted;
        this.uploads = uploads;
        this.groups = groups;
        this.mapper = mapper;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "pub (Dart / Flutter)";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("pub-dev", FORMAT, DEFAULT_REMOTE,
                        "Proxy of pub.dev, the Dart and Flutter package repository"),
                RepositoryTemplate.hosted("pub-hosted", FORMAT, "Your own Dart and Flutter packages"),
                RepositoryTemplate.group("pub-group", FORMAT, List.of("pub-hosted", "pub-dev"),
                        "Single PUB_HOSTED_URL for dart pub and flutter pub"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return PubSettings.descriptors();
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return PubSnippets.of(context);
    }

    @Override
    public void validate(Repository repository) {
        PubSettings.validate(repository);
    }

    /** Listings, version documents and advisories change; archives never do once published. */
    @Override
    public boolean isMetadataPath(String path) {
        return PubPath.parse(path).isMetadata();
    }

    /**
     * The access a request needs; NONE for a caller who lacks it, so that {@link #handle} can refuse with the
     * challenge the pub client understands (see the class documentation).
     */
    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        AccessLevel needed = accessFor(parse(request), request.method());
        return entitled(request, needed) ? needed : AccessLevel.NONE;
    }

    static PubPath parse(RepositoryRequest request) {
        return PubPath.parse(request.path(), request.http() == null ? null : request.http().getRequestURI());
    }

    /** What a request needs, by endpoint: publishing and every change is a write, everything else a read. */
    static AccessLevel accessFor(PubPath path, String method) {
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        return switch (path.kind()) {
            case PUBLISH_NEW, PUBLISH_UPLOAD, PUBLISH_FINISH -> AccessLevel.WRITE;
            default -> read ? AccessLevel.READ : AccessLevel.WRITE;
        };
    }

    static boolean entitled(RepositoryRequest request, AccessLevel needed) {
        GranaryPrincipal principal = request.principal();
        String name = request.repository().getName();
        return switch (needed) {
            case NONE -> true;
            case READ -> principal.canRead(name);
            case WRITE -> principal.canWrite(name);
        };
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        PubPath path = parse(request);
        AccessLevel needed = accessFor(path, request.method());
        if (!entitled(request, needed)) {
            return refuse(request, needed);
        }
        try {
            return dispatch(request, path);
        } catch (PubError e) {
            return PubResponses.error(e);
        }
    }

    private ResponseEntity<?> refuse(RepositoryRequest request, AccessLevel needed) {
        String repo = request.repository().getName();
        String verb = needed == AccessLevel.WRITE ? "publish to or change" : "read";
        if (request.principal().anonymous()) {
            return PubResponses.challenge(HttpStatus.UNAUTHORIZED, MISSING_AUTHENTICATION,
                    "Granary repository '" + repo + "' needs an API token to " + verb + " it.");
        }
        return PubResponses.challenge(HttpStatus.FORBIDDEN, INSUFFICIENT_PERMISSIONS,
                "User '" + request.principal().username() + "' may not " + verb + " Granary repository '" + repo + "'.");
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, PubPath path) throws IOException {
        String method = request.method();
        boolean read = request.isGet();
        return switch (path.kind()) {
            case ROOT -> read ? root(request) : notAllowed(path, method);
            case PACKAGE -> switch (method) {
                case "GET", "HEAD" -> listing(request, path);
                case "DELETE" -> deletePackage(request, path);
                default -> notAllowed(path, method);
            };
            case VERSION -> switch (method) {
                case "GET", "HEAD" -> version(request, path);
                case "DELETE" -> deleteVersion(request, path);
                default -> notAllowed(path, method);
            };
            case ADVISORIES -> read ? advisories(request, path) : notAllowed(path, method);
            case ARCHIVE -> read ? archive(request, path) : notAllowed(path, method);
            case PACKAGE_OPTIONS -> switch (method) {
                case "GET", "HEAD" -> packageOptions(request, path);
                case "PUT", "POST" -> setPackageOptions(request, path);
                default -> notAllowed(path, method);
            };
            case VERSION_OPTIONS -> switch (method) {
                case "GET", "HEAD" -> versionOptions(request, path);
                case "PUT", "POST" -> setVersionOptions(request, path);
                default -> notAllowed(path, method);
            };
            case RETRACT -> switch (method) {
                case "POST", "PUT" -> retract(request, path, true);
                case "DELETE" -> retract(request, path, false);
                default -> notAllowed(path, method);
            };
            case PUBLISH_NEW -> read ? newUpload(request) : notAllowed(path, method);
            case PUBLISH_UPLOAD -> "POST".equals(method) || "PUT".equals(method) ? upload(request) : notAllowed(path, method);
            case PUBLISH_FINISH -> read ? finish(request) : notAllowed(path, method);
            case INVALID -> PubResponses.error(HttpStatus.BAD_REQUEST, PubError.INVALID_INPUT,
                    "'" + request.path() + "' names no valid package or version");
            case UNKNOWN -> PubResponses.error(HttpStatus.NOT_FOUND, PubError.NOT_FOUND, "Not found: " + request.path());
        };
    }

    // --- reads ---------------------------------------------------------------------------------------

    private ResponseEntity<?> root(RepositoryRequest request) {
        Repository repo = request.repository();
        ObjectNode info = NODES.objectNode();
        info.put("name", repo.getName());
        info.put("format", FORMAT);
        info.put("type", repo.getType().name().toLowerCase(Locale.ROOT));
        info.put("url", request.baseUrl());
        info.put("usage", "export PUB_HOSTED_URL=" + request.baseUrl());
        return document(request, info);
    }

    private ResponseEntity<?> listing(RepositoryRequest request, PubPath path) {
        return document(request, requireListing(request, path.name()));
    }

    private ResponseEntity<?> version(RepositoryRequest request, PubPath path) {
        ObjectNode listing = requireListing(request, path.name());
        PubListing.Entry entry = PubListing.find(listing, path.version()).orElseThrow(() ->
                PubError.notFound("Package " + path.name() + " has no version " + path.version()));
        return document(request, entry.node());
    }

    private ResponseEntity<?> advisories(RepositoryRequest request, PubPath path) {
        ObjectNode doc = resolver.advisories(request.repository(), path.name()).orElseThrow(() ->
                PubError.notFound("No advisories for package " + path.name()));
        return document(request, doc);
    }

    private ResponseEntity<?> archive(RepositoryRequest request, PubPath path) {
        Optional<StoredContent> content = resolver.archive(request.repository(), path.name(), path.version());
        if (content.isEmpty()) {
            throw PubError.notFound("Package " + path.name() + " has no archive for version " + path.version());
        }
        ResponseEntity<?> response = ContentResponses.stream(request, content.get());
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        if (!request.isHead()) {
            storage.recordDownload(content.get().asset());
        }
        // whatever the upstream called it (pub.dev says "application/octet"), an archive is a gzip file
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .contentType(PubResponses.ARCHIVE).body(response.getBody());
    }

    private ResponseEntity<?> packageOptions(RepositoryRequest request, PubPath path) {
        ObjectNode listing = requireListing(request, path.name());
        ObjectNode options = NODES.objectNode();
        options.put("isDiscontinued", listing.path(PubListing.IS_DISCONTINUED).asBoolean(false));
        String replacedBy = PubListing.string(listing, PubListing.REPLACED_BY);
        if (replacedBy == null) {
            options.putNull("replacedBy");
        } else {
            options.put("replacedBy", replacedBy);
        }
        options.put("isUnlisted", false);
        return document(request, options);
    }

    private ResponseEntity<?> versionOptions(RepositoryRequest request, PubPath path) {
        ObjectNode listing = requireListing(request, path.name());
        PubListing.Entry entry = PubListing.find(listing, path.version()).orElseThrow(() ->
                PubError.notFound("Package " + path.name() + " has no version " + path.version()));
        ObjectNode options = NODES.objectNode();
        options.put("isRetracted", entry.retracted());
        return document(request, options);
    }

    private ObjectNode requireListing(RepositoryRequest request, String name) {
        return resolver.listing(request.repository(), name, request.baseUrl()).orElseThrow(() ->
                PubError.notFound("Package " + name + " not found in repository '" + request.repository().getName() + "'"));
    }

    // --- publishing ----------------------------------------------------------------------------------

    private ResponseEntity<?> newUpload(RepositoryRequest request) {
        requireHosted(request);
        return document(request, uploads.newUpload(request.baseUrl()));
    }

    private ResponseEntity<?> upload(RepositoryRequest request) throws IOException {
        requireHosted(request);
        return PubResponses.uploaded(uploads.receive(request));
    }

    private ResponseEntity<?> finish(RepositoryRequest request) throws IOException {
        requireHosted(request);
        return uploads.finish(request);
    }

    // --- management ----------------------------------------------------------------------------------

    private ResponseEntity<?> deletePackage(RepositoryRequest request, PubPath path) {
        requireHosted(request);
        int versions = hosted.deletePackage(request.repository(), path.name());
        if (versions == 0) {
            throw PubError.notFound("Package " + path.name() + " not found in repository '" + request.repository().getName() + "'");
        }
        return PubResponses.success("Deleted package " + path.name() + " (" + versions + " version"
                + (versions == 1 ? "" : "s") + ").");
    }

    private ResponseEntity<?> deleteVersion(RepositoryRequest request, PubPath path) {
        requireHosted(request);
        if (!hosted.deleteVersion(request.repository(), path.name(), path.version())) {
            throw PubError.notFound("Package " + path.name() + " has no version " + path.version());
        }
        return PubResponses.success("Deleted " + path.name() + " " + path.version() + ".");
    }

    private ResponseEntity<?> retract(RepositoryRequest request, PubPath path, boolean retracted) {
        requireHosted(request);
        hosted.setRetracted(request.repository(), path.name(), path.version(), retracted);
        return PubResponses.success((retracted ? "Retracted " : "Restored ") + path.name() + " " + path.version() + ".");
    }

    /** pub.dev's {@code PUT /api/packages/<name>/versions/<version>/options} with {@code {"isRetracted": …}}. */
    private ResponseEntity<?> setVersionOptions(RepositoryRequest request, PubPath path) throws IOException {
        requireHosted(request);
        JsonNode body = readJson(request);
        JsonNode retracted = body.get("isRetracted");
        if (retracted == null || !retracted.isBoolean()) {
            throw PubError.invalid("Expected {\"isRetracted\": true|false}");
        }
        hosted.setRetracted(request.repository(), path.name(), path.version(), retracted.booleanValue());
        return versionOptions(request, path);
    }

    /** pub.dev's {@code PUT /api/packages/<name>/options} with {@code {"isDiscontinued": …, "replacedBy": …}}. */
    private ResponseEntity<?> setPackageOptions(RepositoryRequest request, PubPath path) throws IOException {
        requireHosted(request);
        JsonNode body = readJson(request);
        JsonNode discontinued = body.get("isDiscontinued");
        if (discontinued == null || !discontinued.isBoolean()) {
            throw PubError.invalid("Expected {\"isDiscontinued\": true|false, \"replacedBy\": \"<package>\"}");
        }
        JsonNode replacedBy = body.get("replacedBy");
        if (replacedBy != null && !replacedBy.isNull() && !replacedBy.isString()) {
            throw PubError.invalid("replacedBy must be a package name or null");
        }
        hosted.setDiscontinued(request.repository(), path.name(), discontinued.booleanValue(),
                replacedBy == null || replacedBy.isNull() ? null : replacedBy.stringValue());
        return packageOptions(request, path);
    }

    /**
     * Only hosted repositories change. A group names its hosted member; reads of the publishing endpoints are
     * answered with 400 (the client prints the message), other methods with 405.
     */
    private void requireHosted(RepositoryRequest request) {
        Repository repo = request.repository();
        if (repo.isHosted()) {
            return;
        }
        String message;
        if (repo.isGroup()) {
            message = groups.leafMembers(repo).stream().filter(Repository::isHosted).findFirst()
                    .map(member -> "'" + repo.getName() + "' is a group repository and cannot be changed; publish to its "
                            + "hosted member instead: publish_to: " + request.serverUrl() + "/repository/" + member.getName())
                    .orElse("'" + repo.getName() + "' is a group repository without a hosted member and cannot be changed");
        } else {
            message = "'" + repo.getName() + "' is a proxy of " + repo.getRemoteUrl() + " and cannot be changed";
        }
        if (request.isGet()) {
            throw PubError.invalid(message);
        }
        throw PubError.methodNotAllowed(message);
    }

    // --- helpers -------------------------------------------------------------------------------------

    private ResponseEntity<?> document(RepositoryRequest request, JsonNode node) {
        return PubResponses.document(request, mapper.writeValueAsBytes(node));
    }

    private JsonNode readJson(RepositoryRequest request) throws IOException {
        byte[] body;
        try (InputStream in = request.body()) {
            body = in.readNBytes(64 * 1024 + 1);
        }
        if (body.length > 64 * 1024) {
            throw PubError.invalid("The request body is too large");
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw PubError.invalid("The request body must be a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw PubError.invalid("The request body is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static ResponseEntity<?> notAllowed(PubPath path, String method) {
        HttpMethod[] allowed = switch (path.kind()) {
            case PACKAGE, VERSION -> new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD, HttpMethod.DELETE};
            case PACKAGE_OPTIONS, VERSION_OPTIONS -> new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT,
                    HttpMethod.POST};
            case RETRACT -> new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE};
            case PUBLISH_UPLOAD -> new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT};
            default -> new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD};
        };
        ResponseEntity<?> error = PubResponses.error(HttpStatus.METHOD_NOT_ALLOWED, PubError.METHOD_NOT_ALLOWED,
                method + " is not supported here");
        return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders()).allow(allowed).body(error.getBody());
    }
}
