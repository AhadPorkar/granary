package dev.granary.format.hex;

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
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The Hex package format as mix ({@code mix deps.get}, {@code mix hex.publish}) and rebar3 speak it: the
 * repository API (signed {@code names}, {@code versions} and {@code packages/<name>} documents, release tarballs,
 * documentation, the public key and the client install lists) and the HTTP API under {@code api/} for
 * publishing.
 *
 * <p>Hosted repositories sign their documents with Granary's key; proxies pass hex.pm's documents through
 * untouched so {@code HEX_MIRROR} keeps hex.pm's key; groups merge their members and sign the merged view with
 * Granary's key under the group's repository name.
 */
@Component
public class HexFormatHandler implements FormatHandler {

    public static final String FORMAT = "hex";
    public static final String DEFAULT_REMOTE = "https://repo.hex.pm/";
    static final String PEM_CONTENT_TYPE = "application/x-pem-file";
    static final String CSV_CONTENT_TYPE = "text/csv;charset=utf-8";

    private final StorageService storage;
    private final GroupResolver groups;
    private final HexContent content;
    private final HexApi api;
    private final HexSigningKey key;

    public HexFormatHandler(StorageService storage, GroupResolver groups, HexContent content, HexApi api, HexSigningKey key) {
        this.storage = storage;
        this.groups = groups;
        this.content = content;
        this.api = api;
        this.key = key;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Hex (Elixir / Erlang)";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("hexpm", FORMAT, DEFAULT_REMOTE, "Proxy of hex.pm (use it as HEX_MIRROR)"),
                RepositoryTemplate.hosted("hex-hosted", FORMAT, "Your own Elixir and Erlang packages"),
                RepositoryTemplate.group("hex-group", FORMAT, List.of("hex-hosted", "hexpm"),
                        "Your packages and hex.pm behind one repository, signed with Granary's key"));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return HexSettings.descriptors();
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        Repository repo = context.repository();
        String hostedMember = repo.isGroup()
                ? groups.leafMembers(repo).stream().filter(Repository::isHosted).map(Repository::getName).findFirst().orElse(null)
                : null;
        return HexSnippets.of(context, key.publicKeyPem(), key.fingerprint(), hostedMember);
    }

    @Override
    public void validate(Repository repository) {
        HexSettings.validate(repository);
    }

    /** Registry documents, the public key and the install lists change; tarballs and documentation never do. */
    @Override
    public boolean isMetadataPath(String path) {
        String p = path == null ? "" : path;
        return p.startsWith(HexPackageState.DIRECTORY) || HexPath.parse(p).isMetadata();
    }

    /**
     * Reads need read access and writes write access, except for the user-level API endpoints (who am I, my
     * keys, may my key do this), which need any account that can see the repository; the handler refuses
     * anonymous callers there itself.
     */
    @Override
    public AccessLevel requiredAccess(RepositoryRequest request) {
        HexPath path = HexPath.parse(request.path());
        return switch (path.kind()) {
            case API_USERS_ME, API_AUTH, API_KEYS, API_KEY -> AccessLevel.READ;
            default -> request.isGet() ? AccessLevel.READ : AccessLevel.WRITE;
        };
    }

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        HexPath path = hasEncodedSeparator(request) ? HexPath.invalidPath() : HexPath.parse(request.path());
        try {
            return dispatch(request, path);
        } catch (HexError e) {
            return HexResponses.error(request, e);
        }
    }

    /**
     * No Hex name, version or file contains a slash or a backslash, so an encoded one ({@code %2F}, {@code %5C}) in
     * the request is never legitimate: the path arrives decoded, and {@code packages/a%2Fb} must not be read as
     * {@code packages/a/b}.
     */
    static boolean hasEncodedSeparator(RepositoryRequest request) {
        String raw = request.http() == null ? null : request.http().getRequestURI();
        if (raw == null) {
            return false;
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        return upper.contains("%2F") || upper.contains("%5C");
    }

    private ResponseEntity<?> dispatch(RepositoryRequest request, HexPath path) throws IOException {
        if (path.kind() == HexPath.Kind.INVALID) {
            if (request.path().startsWith("api/") || request.path().equals("api")) {
                throw HexError.badRequest("not a valid Hex API path");
            }
            return ContentResponses.error(HttpStatus.BAD_REQUEST, "path.invalid", "Not a valid Hex repository path");
        }
        if (path.isApi()) {
            return api.handle(request, path);
        }
        if (path.kind() == HexPath.Kind.UNKNOWN) {
            return ContentResponses.notFound();
        }
        if (!request.isGet()) {
            Repository repo = request.repository();
            if (!repo.isHosted()) {
                throw HexHosted.notHosted(repo, repo.isGroup()
                        ? groups.leafMembers(repo).stream().filter(Repository::isHosted).map(Repository::getName).findFirst()
                        : Optional.empty());
            }
            return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                    request.method() + " is not supported on the Hex repository API; publish through " + request.baseUrl() + "/api");
        }
        Repository repo = request.repository();
        return switch (path.kind()) {
            case ROOT -> root(request);
            case NAMES, VERSIONS, PACKAGE -> registryDocument(request, path);
            case TARBALL -> download(request, content.tarball(repo, path.name(), path.version()),
                    "release " + path.name() + " " + path.version());
            case DOCS -> download(request, content.docs(repo, path.name(), path.version()),
                    "documentation of " + path.name() + " " + path.version());
            case PUBLIC_KEY -> publicKey(request);
            case INSTALLS -> installs(request, path);
            default -> ContentResponses.notFound();
        };
    }

    private ResponseEntity<?> root(RepositoryRequest request) {
        Repository repo = request.repository();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("format", FORMAT);
        body.put("type", repo.getType().name().toLowerCase(Locale.ROOT));
        body.put("repository", repo.isProxy() ? null : HexSettings.repositoryName(repo));
        body.put("repository_url", request.baseUrl());
        body.put("api_url", request.baseUrl() + "/api");
        body.put("public_key_fingerprint", repo.isProxy() ? null : key.fingerprint());
        return HexResponses.body(request, HttpStatus.OK, body);
    }

    private ResponseEntity<?> registryDocument(RepositoryRequest request, HexPath path) {
        Optional<HexContent.Document> document = content.registryDocument(request.repository(), path.kind(), path.name());
        if (document.isEmpty()) {
            return ContentResponses.notFound(path.kind() == HexPath.Kind.PACKAGE
                    ? "package " + path.name() + " does not exist"
                    : path.storagePath() + " is not available");
        }
        return switch (document.get()) {
            case HexContent.Stored stored -> ContentResponses.stream(request, stored.content());
            case HexContent.Composed composed -> HexResponses.document(request, composed.bytes(),
                    HexResponses.REGISTRY_CONTENT_TYPE);
        };
    }

    private ResponseEntity<?> download(RepositoryRequest request, Optional<StoredContent> found, String what) {
        if (found.isEmpty()) {
            return ContentResponses.notFound(what + " does not exist");
        }
        ResponseEntity<?> response = ContentResponses.stream(request, found.get());
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(found.get().asset());
        }
        return response;
    }

    private ResponseEntity<?> publicKey(RepositoryRequest request) {
        Repository repo = request.repository();
        if (repo.isProxy()) {
            Optional<StoredContent> upstream = content.proxyPublicKey(repo);
            return upstream.<ResponseEntity<?>>map(c -> ContentResponses.stream(request, c))
                    .orElseGet(() -> ContentResponses.notFound("public_key is not available"));
        }
        return HexResponses.text(request, key.publicKeyPem(), PEM_CONTENT_TYPE);
    }

    private ResponseEntity<?> installs(RepositoryRequest request, HexPath path) {
        Optional<StoredContent> found = content.installs(request.repository(), path);
        if (found.isPresent()) {
            return ContentResponses.stream(request, found.get());
        }
        Repository repo = request.repository();
        boolean proxied = repo.isProxy() || (repo.isGroup() && groups.leafMembers(repo).stream().anyMatch(Repository::isProxy));
        if (!proxied && "hex-1.x.csv".equals(path.rest())) {
            // no client builds are hosted here; a group with a proxy member answers 404 instead, so an unreachable
            // upstream surfaces as 503 rather than as an empty list of Hex versions
            return HexResponses.text(request, "", CSV_CONTENT_TYPE);
        }
        return ContentResponses.notFound(path.storagePath() + " does not exist");
    }
}
