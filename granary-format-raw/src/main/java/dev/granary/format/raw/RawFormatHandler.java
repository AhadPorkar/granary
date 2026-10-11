package dev.granary.format.raw;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.ContentTypes;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The behaviour every file-based registry shares, parameterised by a profile (the subclass): hosted
 * repositories take {@code PUT} (and multipart {@code POST}) of any path, answer {@code GET}/{@code HEAD} of any
 * path and {@code DELETE}; directory paths produce an index page; proxies fetch {@code remoteUrl/path} verbatim
 * and cache it (immutable files forever, paths matching the metadata patterns for the metadata max age);
 * groups answer from the first member that has the path and merge directory listings.
 *
 * <p>Profiles plug into the flow through the protected hooks: the default metadata patterns, extra metadata
 * rules, component coordinates, index regeneration after uploads and deletes, protocol endpoints that are not
 * plain files ({@link #route}), hosted documents generated on demand ({@link #generated}), custom upstream URLs
 * ({@link #upstreamUri}) and documents rewritten before they are served ({@link #transform}).
 */
public abstract class RawFormatHandler implements FormatHandler {

    private static final Logger log = LoggerFactory.getLogger(RawFormatHandler.class);

    protected final StorageService storage;
    protected final ProxyService proxy;
    protected final GroupResolver groups;
    private final GranaryProperties properties;
    private volatile GlobMatcher defaultPatterns;
    /** Serialises uploads of one path or component and the index regenerations of one repository. */
    protected final KeyedLocks locks = new KeyedLocks();

    protected RawFormatHandler(StorageService storage, ProxyService proxy, GroupResolver groups, GranaryProperties properties) {
        this.storage = storage;
        this.proxy = proxy;
        this.groups = groups;
        this.properties = properties;
    }

    // --- profile hooks ---------------------------------------------------------------------------------------

    /** Globs of the paths this profile treats as mutable metadata unless the repository overrides them. */
    protected abstract List<String> defaultMetadataPatterns();

    /** Metadata rules that cannot be expressed as globs (for example "no version pinned in the path"). */
    protected boolean isProfileMetadata(String path) {
        return false;
    }

    /** The component an uploaded file is attached to. */
    protected StorageService.ComponentCoordinates coordinates(String path) {
        return RawComponents.generic(path);
    }

    /** Whether re-uploading byte-identical content over an existing file succeeds even without allowRedeploy. */
    protected boolean allowsIdenticalOverwrite() {
        return false;
    }

    /** Called after a file was stored in a hosted repository (index regeneration). */
    protected void afterUpload(Repository repo, String path, StoredContent content) throws IOException {
    }

    /** Called after files were deleted from a hosted or proxy repository. */
    protected void afterDelete(Repository repo, Collection<String> paths) throws IOException {
    }

    /** Protocol endpoints that are not plain files. Returns empty to let the generic flow handle the request. */
    protected Optional<ResponseEntity<?>> route(RepositoryRequest request, String path) throws IOException {
        return Optional.empty();
    }

    /** A document a hosted repository can produce although nothing is stored at {@code path}. */
    protected Optional<StoredContent> generated(Repository hosted, String path) throws IOException {
        return Optional.empty();
    }

    /** The upstream URL of a path when it is not {@code remoteUrl + path}; {@code null} for the default. */
    protected URI upstreamUri(Repository proxyRepository, String path) {
        return null;
    }

    /** Bytes to serve instead of the stored content (link rewriting); empty streams the file unchanged. */
    protected Optional<byte[]> transform(RepositoryRequest request, String path, StoredContent content) throws IOException {
        return Optional.empty();
    }

    /** Extra validation of a repository definition beyond the shared settings. */
    protected void validateProfile(Repository repository) {
    }

    // --- FormatHandler ---------------------------------------------------------------------------------------

    @Override
    public List<SettingDescriptor> settings() {
        return RawSettings.descriptors(String.join(",", defaultMetadataPatterns()));
    }

    @Override
    public void validate(Repository repository) {
        if (!supportedTypes().contains(repository.getType())) {
            throw GranaryException.badRequest("repository.type.unsupported",
                    displayName() + " repositories can only be " + supportedTypes());
        }
        RawSettings.validate(repository);
        validateProfile(repository);
    }

    @Override
    public boolean isMetadataPath(String path) {
        return defaults().matches(path) || isProfileMetadata(path);
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        if (RawPaths.hasEncodedSeparator(request.http().getRequestURI())) {
            return RawResponses.badRequest("path.invalid",
                    "Illegal path: encoded slashes (%2F) and backslashes (%5C) are not allowed");
        }
        String path;
        try {
            path = RawPaths.validate(request.path());
        } catch (IllegalArgumentException e) {
            return RawResponses.badRequest("path.invalid", "Illegal path: " + e.getMessage());
        }
        Optional<ResponseEntity<?>> routed = route(request, path);
        if (routed.isPresent()) {
            return routed.get();
        }
        return switch (request.method()) {
            case "GET", "HEAD" -> get(request, path);
            case "PUT" -> put(request, path);
            case "POST" -> post(request, path);
            case "DELETE" -> delete(request, path);
            default -> RawResponses.methodNotAllowed(request.method());
        };
    }

    // --- reads -------------------------------------------------------------------------------------------------

    private ResponseEntity<?> get(RepositoryRequest request, String path) throws IOException {
        if (RawPaths.isDirectory(path)) {
            return listing(request, path);
        }
        Optional<StoredContent> content = resolve(request.repository(), path);
        if (content.isEmpty()) {
            return ContentResponses.notFound();
        }
        return respond(request, path, content.get());
    }

    private ResponseEntity<?> listing(RepositoryRequest request, String path) {
        Repository repo = request.repository();
        if (!RawSettings.listingEnabled(repo)) {
            return ContentResponses.notFound("Directory listings are disabled for this repository");
        }
        List<List<RawListing.Entry>> perSource = new ArrayList<>();
        for (Repository source : sources(repo)) {
            perSource.add(RawListing.children(storage.list(source, path), path));
        }
        List<RawListing.Entry> entries = RawListing.merge(perSource);
        if (entries.isEmpty() && !path.isEmpty()) {
            return ContentResponses.notFound();
        }
        if (RawListing.wantsJson(request.header("Accept"))) {
            return document(request, RawListing.json(repo.getName(), path, entries), MediaType.APPLICATION_JSON_VALUE, true);
        }
        byte[] html = RawListing.html(repo.getName(), path, entries).getBytes(StandardCharsets.UTF_8);
        return document(request, html, "text/html;charset=UTF-8", true);
    }

    /**
     * Looks a file up the way the repository type demands: hosted from storage (or {@link #generated}), proxy
     * through the cache and upstream, group from the first leaf member that has it.
     */
    public Optional<StoredContent> resolve(Repository repo, String path) throws IOException {
        return switch (repo.getType()) {
            case HOSTED -> {
                Optional<StoredContent> stored = storage.find(repo, path);
                yield stored.isPresent() ? stored : generated(repo, path);
            }
            case PROXY -> fetch(repo, path);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = resolve(member, path);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /**
     * Fetches a path through the proxy cache with the right immutability, honouring {@link #upstreamUri}. Immutable
     * files are linked to the component an upload of the same path would get ({@link #coordinates}), so the component
     * browser and search list what the proxy holds; metadata belongs to no component.
     */
    protected Optional<StoredContent> fetch(Repository proxyRepository, String path) {
        FetchOptions options = metadata(proxyRepository, path)
                ? FetchOptions.index()
                : FetchOptions.artifact(RawComponents.fit(coordinates(path)));
        URI custom = upstreamUri(proxyRepository, path);
        return custom == null
                ? proxy.fetch(proxyRepository, path, options)
                : proxy.fetch(proxyRepository, path, custom, options, Map.of());
    }

    /** Whether a path is mutable metadata for this repository: its own patterns, else the profile's. */
    public boolean metadata(Repository repo, String path) {
        return RawSettings.metadataPatterns(repo, defaults()).matches(path) || isProfileMetadata(path);
    }

    /** The repositories a read consults: the leaf members of a group, otherwise the repository itself. */
    protected List<Repository> sources(Repository repo) {
        return repo.isGroup() ? groups.leafMembers(repo) : List.of(repo);
    }

    /** Streams stored content (after {@link #transform}) and counts the download. */
    protected ResponseEntity<?> respond(RepositoryRequest request, String path, StoredContent content) throws IOException {
        Optional<byte[]> rewritten = transform(request, path, content);
        if (rewritten.isPresent()) {
            return document(request, rewritten.get(), content.contentType(), content.asset().isMetadata());
        }
        ResponseEntity<?> response = RawResponses.stream(request, content, RawSettings.attachment(request.repository()));
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.asset());
        }
        return response;
    }

    protected ResponseEntity<?> document(RepositoryRequest request, byte[] body, String contentType, boolean mutable) {
        return RawResponses.document(request, body, contentType, mutable);
    }

    // --- writes ------------------------------------------------------------------------------------------------

    private ResponseEntity<?> put(RepositoryRequest request, String path) throws IOException {
        Repository repo = request.repository();
        if (!repo.isHosted()) {
            return readOnly(repo);
        }
        if (RawPaths.isDirectory(path)) {
            return RawResponses.badRequest("path.invalid", "Cannot upload to a directory path; add the file name");
        }
        Optional<List<RawUpload.Part>> parts = RawUpload.multipart(request);
        try {
            if (parts.isPresent()) {
                if (parts.get().isEmpty()) {
                    return RawResponses.badRequest("upload.empty", "The multipart request contains no file");
                }
                try (InputStream in = parts.get().get(0).opener().open()) {
                    return uploaded(path, store(request, path, in));
                }
            }
            try (InputStream in = request.body()) {
                return uploaded(path, store(request, path, in));
            }
        } catch (Conflict conflict) {
            return RawResponses.conflict(conflict.path);
        }
    }

    /** The answer to a stored upload: {@code 201} with the path, size and SHA-256 of what was stored. */
    protected ResponseEntity<?> uploaded(String path, StoredContent content) {
        return RawResponses.created(path, content);
    }

    /**
     * {@code POST} of a multipart form to a directory stores every file part under its own name; a {@code POST} to
     * a file path behaves like {@code PUT} (with the first file part, or the raw body).
     */
    private ResponseEntity<?> post(RepositoryRequest request, String path) throws IOException {
        Repository repo = request.repository();
        if (!repo.isHosted()) {
            return readOnly(repo);
        }
        Optional<List<RawUpload.Part>> parts = RawUpload.multipart(request);
        try {
            if (parts.isEmpty()) {
                if (RawPaths.isDirectory(path)) {
                    return RawResponses.badRequest("path.invalid",
                            "POST a multipart form to a directory, or PUT the file to its full path");
                }
                try (InputStream in = request.body()) {
                    return uploaded(path, store(request, path, in));
                }
            }
            if (parts.get().isEmpty()) {
                return RawResponses.badRequest("upload.empty", "The multipart request contains no file");
            }
            if (!RawPaths.isDirectory(path)) {
                try (InputStream in = parts.get().get(0).opener().open()) {
                    return uploaded(path, store(request, path, in));
                }
            }
            List<Map<String, Object>> stored = new ArrayList<>();
            for (RawUpload.Part part : parts.get()) {
                String name = RawUpload.safeFileName(part.fileName());
                if (name.isEmpty()) {
                    return RawResponses.badRequest("upload.fileName", "Every file part needs a file name");
                }
                String target;
                try {
                    target = RawPaths.validate(path + name);
                } catch (IllegalArgumentException e) {
                    return RawResponses.badRequest("path.invalid", "Illegal file name '" + name + "': " + e.getMessage());
                }
                try (InputStream in = part.opener().open()) {
                    StoredContent content = store(request, target, in);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("path", target);
                    entry.put("size", content.size());
                    entry.put("sha256", content.asset().getBlobSha256());
                    stored.add(entry);
                }
            }
            return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("stored", stored));
        } catch (Conflict conflict) {
            return RawResponses.conflict(conflict.path);
        }
    }

    /**
     * Stores one uploaded file, applying the redeploy rules, then lets the profile regenerate its indexes. The
     * check for an existing file and the write happen under one lock per component (or per path when the file
     * has no component), so concurrent uploads of the same path cannot both succeed and concurrent uploads of
     * files that share a component cannot race to create it.
     */
    protected StoredContent store(RepositoryRequest request, String path, InputStream in) throws IOException {
        Repository repo = request.repository();
        StorageService.ComponentCoordinates coordinates = RawComponents.fit(coordinates(path));
        String key = coordinates == null ? pathKey(repo, path) : componentKey(repo, coordinates);
        return locks.withLock(key, () -> storeLocked(request, path, in, coordinates));
    }

    private StoredContent storeLocked(RepositoryRequest request, String path, InputStream in,
                                      StorageService.ComponentCoordinates coordinates) throws IOException {
        Repository repo = request.repository();
        Optional<StoredContent> existing = storage.find(repo, path);
        if (existing.isPresent() && !repo.isAllowRedeploy()) {
            if (!allowsIdenticalOverwrite()) {
                throw new Conflict(path);
            }
            Path spooled = RawUpload.spool(in, spoolDirectory());
            try {
                String sha256;
                try (InputStream spooledIn = Files.newInputStream(spooled)) {
                    sha256 = Checksums.hex("SHA-256", spooledIn);
                }
                if (!sha256.equals(existing.get().asset().getBlobSha256())) {
                    throw new Conflict(path);
                }
                log.debug("identical re-upload of {} in {} accepted", path, repo.getName());
                return existing.get();
            } finally {
                Files.deleteIfExists(spooled);
            }
        }
        StoredContent stored = storage.store(repo, path, in, uploadContentType(request, path), Asset.Origin.UPLOADED,
                metadata(repo, path), coordinates);
        afterUpload(repo, path, stored);
        return stored;
    }

    private ResponseEntity<?> delete(RepositoryRequest request, String path) throws IOException {
        Repository repo = request.repository();
        if (repo.isGroup()) {
            return readOnly(repo);
        }
        if (path.isEmpty()) {
            return RawResponses.badRequest("path.invalid", "Refusing to delete the whole repository; name a directory or file");
        }
        List<String> deleted = new ArrayList<>();
        if (RawPaths.isDirectory(path)) {
            for (Asset asset : storage.list(repo, path)) {
                if (storage.delete(repo, asset.getPath())) {
                    deleted.add(asset.getPath());
                }
            }
        } else if (storage.delete(repo, path)) {
            deleted.add(path);
        }
        if (deleted.isEmpty()) {
            return ContentResponses.notFound();
        }
        afterDelete(repo, deleted);
        return ResponseEntity.noContent().build();
    }

    /** The 405 for writes to proxies and groups; a group names the hosted member to use instead. */
    protected ResponseEntity<?> readOnly(Repository repo) {
        String message;
        if (repo.isGroup()) {
            Optional<Repository> hosted = groups.leafMembers(repo).stream().filter(Repository::isHosted).findFirst();
            message = hosted
                    .map(h -> "Repository '" + repo.getName() + "' is a group and read-only; upload to its hosted member '"
                            + h.getName() + "' instead")
                    .orElse("Repository '" + repo.getName() + "' is a group and read-only; it has no hosted member to upload to");
        } else {
            message = "Repository '" + repo.getName() + "' is a proxy and read-only; upload to a hosted repository instead";
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(org.springframework.http.HttpHeaders.ALLOW, RawResponses.ALLOW_READ)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "repository.readOnly", "message", message));
    }

    // --- helpers for profiles ------------------------------------------------------------------------------------

    /** Stores a server-generated index document (always metadata). */
    protected StoredContent storeGenerated(Repository repo, String path, byte[] bytes, String contentType) throws IOException {
        return locks.withLock(pathKey(repo, path), () ->
                storage.store(repo, path, new ByteArrayInputStream(bytes), contentType, Asset.Origin.GENERATED, true));
    }

    /** Lock key of one path of a repository. */
    protected static String pathKey(Repository repo, String path) {
        return repo.getName() + "\u0000path\u0000" + path;
    }

    /** Lock key of one component of a repository. */
    protected static String componentKey(Repository repo, StorageService.ComponentCoordinates c) {
        return repo.getName() + "\u0000component\u0000" + c.namespace() + "\u0000" + c.name() + "\u0000" + c.version();
    }

    /** Lock key of the generated indexes of a repository: regenerations must not interleave. */
    protected static String indexKey(Repository repo) {
        return repo.getName() + "\u0000index";
    }

    /** Where uploads are spooled when their digest must be known before storing: {@code <dataDir>/tmp/<format>}. */
    protected Path spoolDirectory() {
        return properties.getDataDir().resolve("tmp").resolve(format());
    }

    /** Size of the asset table's content type column; longer declarations are replaced by a guess. */
    static final int MAX_CONTENT_TYPE = 200;

    /**
     * The media type recorded for an upload: what the client declared when it is specific, otherwise a guess from
     * the file name. Form encodings, the generic octet-stream and declarations too long to store carry no usable
     * information and are replaced.
     */
    static String uploadContentType(RepositoryRequest request, String path) {
        String declared = request.header("Content-Type");
        if (declared != null) {
            String type = declared;
            int semicolon = type.indexOf(';');
            if (semicolon >= 0) {
                type = type.substring(0, semicolon);
            }
            type = type.trim().toLowerCase(Locale.ROOT);
            if (!type.isEmpty() && type.length() <= MAX_CONTENT_TYPE && type.indexOf('/') > 0
                    && !type.equals("application/x-www-form-urlencoded")
                    && !type.startsWith("multipart/")
                    && !type.equals(ContentTypes.OCTET_STREAM)
                    && RawResponses.isValidMediaType(type)) {
                return type;
            }
        }
        return RawContentTypes.guess(path);
    }

    private GlobMatcher defaults() {
        GlobMatcher matcher = defaultPatterns;
        if (matcher == null) {
            matcher = GlobMatcher.of(defaultMetadataPatterns());
            defaultPatterns = matcher;
        }
        return matcher;
    }

    /** Raised inside {@link #store} when the redeploy rules refuse an upload. */
    protected static final class Conflict extends RuntimeException {
        final String path;

        Conflict(String path) {
            super("'" + path + "' already exists");
            this.path = path;
        }
    }
}
