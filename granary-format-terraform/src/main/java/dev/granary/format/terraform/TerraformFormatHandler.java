package dev.granary.format.terraform;

import dev.granary.core.GranaryException;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.ConnectContext;
import dev.granary.core.format.ConnectSnippet;
import dev.granary.core.format.ContentResponses;
import dev.granary.core.format.FormatHandler;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.format.RepositoryTemplate;
import dev.granary.core.format.RootRequest;
import dev.granary.core.format.RootRoute;
import dev.granary.core.format.SettingDescriptor;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.repository.GroupResolver;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Terraform / OpenTofu registry protocols: remote service discovery, the provider registry protocol,
 * the module registry protocol and the provider network mirror protocol, for hosted, proxy and group
 * repositories.
 *
 * <p>Hosted repositories take provider releases (one zip per platform, the {@code SHA256SUMS} file, its
 * detached GPG signature and the signing key) and module archives by plain {@code PUT} and generate every
 * registry document from what is stored. Proxies cache the upstream registry's documents through
 * {@link ProxyService} and rewrite the archive URLs inside them to themselves, so a provider or module
 * that was installed once is installable without internet. Groups merge the version lists of their
 * members and serve every file from the member that owns it. The network mirror protocol is served from
 * the same data, so an air-gapped CLI with a {@code network_mirror} block needs nothing else.
 */
@org.springframework.stereotype.Component
public class TerraformFormatHandler implements FormatHandler {

    public static final String FORMAT = "terraform";
    public static final String TERRAFORM_REGISTRY = "https://registry.terraform.io/";
    public static final String OPENTOFU_REGISTRY = "https://registry.opentofu.org/";

    public static final String SETTING_ROOT_REPOSITORY = "terraform.rootRepository";
    public static final String SETTING_PROVIDER_PROTOCOLS = "terraform.providerProtocols";
    public static final String DEFAULT_PROVIDER_PROTOCOLS = "5.0,6.0";

    static final String X_TERRAFORM_GET = "X-Terraform-Get";
    static final String CODE_READ_ONLY = "repository.readOnly";
    static final String CODE_VERSION_EXISTS = "repository.terraform.versionExists";
    static final String CODE_INVALID_UPLOAD = "repository.terraform.invalidUpload";
    static final String CODE_CHECKSUM_MISMATCH = "repository.terraform.checksumMismatch";

    private static final Pattern PROTOCOLS = Pattern.compile("^\\d+\\.\\d+(\\s*,\\s*\\d+\\.\\d+)*$");
    private static final Set<String> BOOLEANS = Set.of("true", "false", "1", "0", "yes", "no");
    private static final long MAX_TEXT_UPLOAD = 1024 * 1024;
    private static final long MAX_SIGNATURE_UPLOAD = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(TerraformFormatHandler.class);

    private final StorageService storage;
    private final GroupResolver groups;
    private final ObjectMapper json;
    private final TerraformDocuments documents;
    private final UpstreamTerraform upstream;
    private final Path tmpDir;
    /**
     * Uploads check what is stored and then store; these locks make that one step per provider version or
     * module version, so two concurrent uploads of the same release cannot both pass the redeploy check
     * (one gets 409) and an archive cannot slip in between a SHA256SUMS check and its store.
     */
    private final ReentrantLock[] writeLocks = new ReentrantLock[64];

    public TerraformFormatHandler(StorageService storage, ProxyService proxy, UpstreamClient client, UpstreamHealth health,
                                  NegativeCache negativeCache, GroupResolver groups, ObjectMapper json,
                                  GranaryProperties properties) {
        this.storage = storage;
        this.groups = groups;
        this.json = json;
        this.documents = new TerraformDocuments(json);
        this.upstream = new UpstreamTerraform(proxy, client, health, negativeCache, storage, json);
        this.tmpDir = properties.getDataDir().resolve("tmp").resolve(FORMAT);
        for (int i = 0; i < writeLocks.length; i++) {
            writeLocks[i] = new ReentrantLock();
        }
    }

    // --- identity -----------------------------------------------------------------------------

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String displayName() {
        return "Terraform / OpenTofu";
    }

    @Override
    public List<RepositoryTemplate> templates() {
        return List.of(
                RepositoryTemplate.proxy("terraform-registry", FORMAT, TERRAFORM_REGISTRY,
                        "Proxy of the public Terraform registry (providers and modules)"),
                RepositoryTemplate.proxy("opentofu-registry", FORMAT, OPENTOFU_REGISTRY,
                        "Proxy of the public OpenTofu registry"),
                RepositoryTemplate.hosted("terraform-hosted", FORMAT, "Your own providers and modules"),
                RepositoryTemplate.group("terraform-group", FORMAT, List.of("terraform-hosted", "terraform-registry"),
                                "Single registry host for terraform and tofu; serves /.well-known/terraform.json")
                        .withSettings(Map.of(SETTING_ROOT_REPOSITORY, "true")));
    }

    @Override
    public List<SettingDescriptor> settings() {
        return List.of(
                SettingDescriptor.bool(SETTING_ROOT_REPOSITORY, "Serve /.well-known/terraform.json", false,
                        "Answer the server-wide service discovery document from this repository, so the Granary host "
                                + "name works in provider and module addresses. One repository per server, usually the group."),
                SettingDescriptor.string(SETTING_PROVIDER_PROTOCOLS, "Provider protocol versions", DEFAULT_PROVIDER_PROTOCOLS,
                                "Comma-separated provider protocol versions announced for hosted providers, e.g. 5.0,6.0.")
                        .forTypes(RepositoryType.HOSTED));
    }

    @Override
    public void validate(Repository repository) {
        repository.setting(SETTING_PROVIDER_PROTOCOLS).ifPresent(value -> {
            if (!PROTOCOLS.matcher(value).matches()) {
                throw GranaryException.badRequest("repository.terraform.providerProtocols.invalid",
                        SETTING_PROVIDER_PROTOCOLS + " must be a comma-separated list of protocol versions such as 5.0,6.0");
            }
        });
        repository.setting(SETTING_ROOT_REPOSITORY).ifPresent(value -> {
            if (!BOOLEANS.contains(value.toLowerCase(Locale.ROOT))) {
                throw GranaryException.badRequest("repository.terraform.rootRepository.invalid",
                        SETTING_ROOT_REPOSITORY + " must be true or false");
            }
        });
    }

    @Override
    public List<ConnectSnippet> connectSnippets(ConnectContext context) {
        return TerraformSnippets.build(context);
    }

    @Override
    public boolean isMetadataPath(String path) {
        String p = path == null ? "" : path;
        return TerraformPath.parse(p).isMetadata() || p.endsWith("/" + TerraformLayout.GPG_KEY_FILE);
    }

    /**
     * {@code /.well-known/terraform.json} belongs to the repository flagged {@code terraform.rootRepository},
     * else the first Terraform group, else the first Terraform repository (all ordered by name).
     */
    @Override
    public Optional<RootRoute> routeRoot(RootRequest request) {
        if (!request.path().equals(TerraformPath.DISCOVERY_PATH)
                || !("GET".equals(request.method()) || "HEAD".equals(request.method()))) {
            return Optional.empty();
        }
        List<Repository> candidates = request.repositories().ofFormat(FORMAT);
        Optional<Repository> target = candidates.stream().filter(r -> r.settingEnabled(SETTING_ROOT_REPOSITORY, false)).findFirst()
                .or(() -> candidates.stream().filter(Repository::isGroup).findFirst())
                .or(() -> candidates.stream().findFirst());
        return target.map(r -> new RootRoute(r.getName(), TerraformPath.DISCOVERY_PATH));
    }

    // --- dispatch -----------------------------------------------------------------------------

    @Override
    public ResponseEntity<?> handle(RepositoryRequest request) throws IOException {
        TerraformPath path = TerraformPath.parse(request.path());
        if (path.kind() == TerraformPath.Kind.INVALID) {
            return ContentResponses.error(HttpStatus.BAD_REQUEST, "path.invalid", "Illegal path");
        }
        return switch (request.method()) {
            case "GET", "HEAD" -> get(request, path);
            case "PUT", "POST" -> put(request, path);
            case "DELETE" -> delete(request, path);
            default -> ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                    request.method() + " is not supported by Terraform repositories");
        };
    }

    // --- GET ----------------------------------------------------------------------------------

    private ResponseEntity<?> get(RepositoryRequest request, TerraformPath p) {
        Repository repo = request.repository();
        String base = request.baseUrl();
        String ns = p.namespace();
        String name = p.name();
        return switch (p.kind()) {
            case ROOT -> json(request, info(request));
            case DISCOVERY -> json(request, documents.discovery(base));
            case PROVIDER_VERSIONS -> providerVersionsDocument(repo, ns, name)
                    .<ResponseEntity<?>>map(d -> json(request, d)).orElseGet(() -> providerNotFound(ns, name));
            case PROVIDER_DOWNLOAD -> providerDownload(repo, base, ns, name, p.version(), p.os(), p.arch())
                    .<ResponseEntity<?>>map(d -> json(request, d))
                    .orElseGet(() -> ContentResponses.notFound("Provider " + ns + "/" + name + " " + p.version()
                            + " has no build for " + p.os() + "_" + p.arch()));
            case PROVIDER_INFO, PROVIDER_VERSION_INFO -> providerInfo(repo, ns, name, p.version())
                    .<ResponseEntity<?>>map(d -> json(request, d)).orElseGet(() -> providerNotFound(ns, name));
            case PROVIDER_GPG_KEY -> signingKeys(repo, ns, name)
                    .<ResponseEntity<?>>map(d -> json(request, d)).orElseGet(() -> ContentResponses.notFound("No signing key stored for " + ns + "/" + name));
            case PROVIDER_FILE, PROVIDER_ARCHIVE, PROVIDER_SHASUMS, PROVIDER_SHASUMS_SIG ->
                    stream(request, providerFile(repo, ns, name, p.version(), p.filename()));
            case MODULE_VERSIONS -> moduleVersionsDocument(repo, ns, name, p.system())
                    .<ResponseEntity<?>>map(d -> json(request, d)).orElseGet(() -> moduleNotFound(ns, name, p.system()));
            case MODULE_DOWNLOAD -> moduleDownload(repo, base, ns, name, p.system(), p.version())
                    .<ResponseEntity<?>>map(source -> ResponseEntity.noContent().header(X_TERRAFORM_GET, source)
                            .cacheControl(CacheControl.noCache()).build())
                    .orElseGet(() -> moduleNotFound(ns, name, p.system()));
            case MODULE_INFO, MODULE_VERSION_INFO -> moduleInfo(repo, ns, name, p.system(), p.version())
                    .<ResponseEntity<?>>map(d -> json(request, d)).orElseGet(() -> moduleNotFound(ns, name, p.system()));
            case MODULE_ARCHIVE, MODULE_FILE -> stream(request, moduleFile(repo, ns, name, p.system(), p.version(), p.extension()));
            case MIRROR_INDEX -> mirrorVersions(repo, request, p.hostname(), ns, name)
                    .<ResponseEntity<?>>map(versions -> json(request, documents.mirrorIndex(versions.keySet())))
                    .orElseGet(() -> providerNotFound(ns, name));
            case MIRROR_VERSION -> mirrorArchives(repo, request, p.hostname(), ns, name, p.version())
                    .<ResponseEntity<?>>map(archives -> json(request, documents.mirrorVersion(archives)))
                    .orElseGet(() -> ContentResponses.notFound("Provider " + ns + "/" + name + " " + p.version() + " not found"));
            case UNKNOWN, INVALID -> ContentResponses.notFound();
        };
    }

    private ObjectNode info(RepositoryRequest request) {
        ObjectNode doc = documents.discovery(request.baseUrl());
        doc.put("format", FORMAT);
        doc.put("repository", request.repository().getName());
        doc.put("type", request.repository().getType().name().toLowerCase(Locale.ROOT));
        return doc;
    }

    // --- providers: versions ------------------------------------------------------------------

    /** Every version of a provider with its platforms, in version order; empty when no member knows it. */
    Optional<List<TerraformDocuments.ProviderVersion>> providerVersionList(Repository repo, String ns, String type) {
        return switch (repo.getType()) {
            case HOSTED -> {
                List<TerraformDocuments.ProviderVersion> list = hostedProviderVersions(repo, ns, type);
                yield list.isEmpty() ? Optional.empty() : Optional.of(list);
            }
            case PROXY -> upstream.providerVersions(repo, ns, type).map(documents::parseProviderVersions);
            case GROUP -> {
                List<List<TerraformDocuments.ProviderVersion>> all = new ArrayList<>();
                for (Repository member : groups.leafMembers(repo)) {
                    providerVersionList(member, ns, type).ifPresent(all::add);
                }
                yield all.isEmpty() ? Optional.empty() : Optional.of(documents.mergeProviderVersions(all));
            }
        };
    }

    private Optional<ObjectNode> providerVersionsDocument(Repository repo, String ns, String type) {
        if (repo.isProxy()) {
            return upstream.providerVersions(repo, ns, type).filter(JsonNode::isObject).map(n -> (ObjectNode) n);
        }
        return providerVersionList(repo, ns, type).map(documents::providerVersions);
    }

    /** One stored provider archive of a hosted repository: {@code <version>/<os>/<arch>/<file>} below the provider prefix. */
    private record StoredArchive(String version, String os, String arch, String filename, Asset asset) {
        TerraformDocuments.Platform platform() {
            return new TerraformDocuments.Platform(os, arch);
        }
    }

    private List<StoredArchive> hostedArchives(Repository repo, String ns, String type, String version) {
        String prefix = version == null ? TerraformLayout.providerPrefix(ns, type) : TerraformLayout.providerVersionPrefix(ns, type, version);
        String providerPrefix = TerraformLayout.providerPrefix(ns, type);
        List<StoredArchive> result = new ArrayList<>();
        for (Asset asset : storage.list(repo, prefix)) {
            if (!asset.getPath().startsWith(providerPrefix)) {
                continue;
            }
            String[] s = asset.getPath().substring(providerPrefix.length()).split("/");
            if (s.length == 4 && TerraformVersion.isValid(s[0])) {
                result.add(new StoredArchive(s[0], s[1], s[2], s[3], asset));
            }
        }
        return result;
    }

    private List<TerraformDocuments.ProviderVersion> hostedProviderVersions(Repository repo, String ns, String type) {
        TreeMap<String, Map<String, TerraformDocuments.Platform>> byVersion = new TreeMap<>(TerraformVersion.STRING_ORDER);
        for (StoredArchive archive : hostedArchives(repo, ns, type, null)) {
            byVersion.computeIfAbsent(archive.version(), v -> new LinkedHashMap<>())
                    .putIfAbsent(archive.platform().key(), archive.platform());
        }
        List<String> protocols = protocols(repo);
        List<TerraformDocuments.ProviderVersion> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, TerraformDocuments.Platform>> e : byVersion.entrySet()) {
            result.add(new TerraformDocuments.ProviderVersion(e.getKey(), protocols, new ArrayList<>(e.getValue().values())));
        }
        return result;
    }

    private static List<String> protocols(Repository repo) {
        return TerraformDocuments.parseProtocols(repo.setting(SETTING_PROVIDER_PROTOCOLS, DEFAULT_PROVIDER_PROTOCOLS));
    }

    // --- providers: download document ----------------------------------------------------------

    /** The download document for one platform, with URLs on {@code baseUrl} (the group's URL when asked through a group). */
    Optional<ObjectNode> providerDownload(Repository repo, String baseUrl, String ns, String type, String version,
                                          String os, String arch) {
        return switch (repo.getType()) {
            case HOSTED -> hostedProviderDownload(repo, baseUrl, ns, type, version, os, arch);
            case PROXY -> upstream.providerDownload(repo, ns, type, version, os, arch)
                    .map(doc -> documents.rewriteProviderDownload(doc, baseUrl, ns, type, version));
            case GROUP -> {
                Optional<Repository> owner = providerVersionOwner(repo, ns, type, version);
                if (owner.isPresent()) {
                    yield providerDownload(owner.get(), baseUrl, ns, type, version, os, arch);
                }
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<ObjectNode> hit = providerDownload(member, baseUrl, ns, type, version, os, arch);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /**
     * The member of a group that answers for one provider version: the first leaf member whose version list
     * has it, the same member {@link TerraformDocuments#mergeProviderVersions} took the version's entry from.
     * Its download documents, archives, {@code SHA256SUMS}, signature and key are served together, never mixed
     * with another member's build of the same version. Empty when no member lists the version (for example a
     * proxy whose version list was never cached), in which case callers fall back to the first member that
     * has the requested document or file.
     */
    private Optional<Repository> providerVersionOwner(Repository group, String ns, String type, String version) {
        String wanted = TerraformPath.canonical(version);
        for (Repository member : groups.leafMembers(group)) {
            boolean listed = providerVersionList(member, ns, type)
                    .map(list -> list.stream().anyMatch(v -> TerraformPath.canonical(v.version()).equals(wanted)))
                    .orElse(false);
            if (listed) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    private Optional<ObjectNode> hostedProviderDownload(Repository repo, String baseUrl, String ns, String type,
                                                        String version, String os, String arch) {
        Optional<StoredArchive> archive = hostedArchives(repo, ns, type, version).stream()
                .filter(a -> a.os().equals(os) && a.arch().equals(arch))
                .findFirst();
        if (archive.isEmpty()) {
            return Optional.empty();
        }
        String filename = archive.get().filename();
        String shasum = shaSums(repo, ns, type, version).flatMap(s -> s.digestOf(filename))
                .orElse(archive.get().asset().getBlobSha256());
        JsonNode keys = signingKeys(repo, ns, type).orElse(null);
        return Optional.of(documents.providerDownload(baseUrl, ns, type, version, os, arch, filename, shasum, protocols(repo), keys));
    }

    private Optional<Sha256Sums> shaSums(Repository repo, String ns, String type, String version) {
        return storage.find(repo, TerraformLayout.shaSumsPath(ns, type, version)).flatMap(content -> {
            try {
                return Optional.of(Sha256Sums.parse(content.readUtf8()));
            } catch (IOException e) {
                log.warn("cannot read SHA256SUMS of {}/{} {} in {}: {}", ns, type, version, repo.getName(), e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** The stored signing keys of a hosted provider ({@code {"gpg_public_keys":[…]}}); groups answer from their hosted members. */
    Optional<ObjectNode> signingKeys(Repository repo, String ns, String type) {
        return switch (repo.getType()) {
            case HOSTED -> storage.find(repo, TerraformLayout.gpgKeyPath(ns, type)).flatMap(upstream::readJson)
                    .filter(JsonNode::isObject).map(n -> (ObjectNode) n);
            case PROXY -> Optional.empty();
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<ObjectNode> hit = signingKeys(member, ns, type);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    // --- providers: info and files -------------------------------------------------------------

    private Optional<ObjectNode> providerInfo(Repository repo, String ns, String type, String version) {
        if (repo.isProxy()) {
            return upstream.providerInfo(repo, ns, type, version).filter(JsonNode::isObject).map(n -> (ObjectNode) n);
        }
        Optional<List<TerraformDocuments.ProviderVersion>> versions = providerVersionList(repo, ns, type);
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        List<String> all = TerraformDocuments.sortVersions(versions.get().stream().map(TerraformDocuments.ProviderVersion::version).toList());
        Optional<String> target = version == null ? TerraformDocuments.latest(all)
                : all.stream().filter(v -> TerraformVersion.STRING_ORDER.compare(v, version) == 0).findFirst();
        return target.map(t -> documents.providerInfo(ns, type, t, all, versions.get().stream()
                .filter(v -> TerraformVersion.STRING_ORDER.compare(v.version(), t) == 0)
                .findFirst().map(TerraformDocuments.ProviderVersion::platforms).orElse(List.of())));
    }

    /** An archive, checksum file or signature by file name, wherever the repository type keeps it. */
    Optional<StoredContent> providerFile(Repository repo, String ns, String type, String version, String filename) {
        return switch (repo.getType()) {
            case HOSTED -> hostedProviderFile(repo, ns, type, version, filename);
            case PROXY -> upstream.providerFile(repo, ns, type, version, filename);
            case GROUP -> {
                Optional<Repository> owner = providerVersionOwner(repo, ns, type, version);
                if (owner.isPresent()) {
                    yield providerFile(owner.get(), ns, type, version, filename);
                }
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = providerFile(member, ns, type, version, filename);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    private Optional<StoredContent> hostedProviderFile(Repository repo, String ns, String type, String version, String filename) {
        if (filename.equals(TerraformPath.SHASUMS)) {
            return storage.find(repo, TerraformLayout.shaSumsPath(ns, type, version));
        }
        if (filename.equals(TerraformPath.SHASUMS_SIG)) {
            return storage.find(repo, TerraformLayout.shaSumsSigPath(ns, type, version));
        }
        return hostedArchives(repo, ns, type, version).stream()
                .filter(a -> a.filename().equals(filename))
                .findFirst()
                .flatMap(a -> storage.find(repo, a.asset().getPath()));
    }

    // --- modules -------------------------------------------------------------------------------

    Optional<List<String>> moduleVersionList(Repository repo, String ns, String name, String system) {
        return switch (repo.getType()) {
            case HOSTED -> {
                List<String> versions = hostedModuleVersions(repo, ns, name, system);
                yield versions.isEmpty() ? Optional.empty() : Optional.of(versions);
            }
            case PROXY -> upstream.moduleVersions(repo, ns, name, system)
                    .map(doc -> TerraformDocuments.sortVersions(documents.parseModuleVersions(doc)));
            case GROUP -> {
                List<String> union = new ArrayList<>();
                boolean any = false;
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<List<String>> versions = moduleVersionList(member, ns, name, system);
                    if (versions.isPresent()) {
                        any = true;
                        union.addAll(versions.get());
                    }
                }
                yield any ? Optional.of(TerraformDocuments.sortVersions(union)) : Optional.empty();
            }
        };
    }

    private List<String> hostedModuleVersions(Repository repo, String ns, String name, String system) {
        String prefix = TerraformLayout.modulePrefix(ns, name, system);
        List<String> versions = new ArrayList<>();
        for (Asset asset : storage.list(repo, prefix)) {
            String rest = asset.getPath().substring(prefix.length());
            if (rest.endsWith(".tgz") && !rest.contains("/")) {
                String version = rest.substring(0, rest.length() - 4);
                if (TerraformVersion.isValid(version)) {
                    versions.add(version);
                }
            }
        }
        return TerraformDocuments.sortVersions(versions);
    }

    private Optional<ObjectNode> moduleVersionsDocument(Repository repo, String ns, String name, String system) {
        if (repo.isProxy()) {
            return upstream.moduleVersions(repo, ns, name, system).filter(JsonNode::isObject).map(n -> (ObjectNode) n);
        }
        return moduleVersionList(repo, ns, name, system).map(v -> documents.moduleVersions(ns, name, system, v));
    }

    /** The {@code X-Terraform-Get} source of a module version; {@code null} version means the latest one. */
    Optional<String> moduleDownload(Repository repo, String baseUrl, String ns, String name, String system, String version) {
        if (version == null) {
            return moduleVersionList(repo, ns, name, system).flatMap(TerraformDocuments::latest)
                    .flatMap(latest -> moduleDownload(repo, baseUrl, ns, name, system, latest));
        }
        return switch (repo.getType()) {
            case HOSTED -> storage.exists(repo, TerraformLayout.moduleArchivePath(ns, name, system, version))
                    ? Optional.of(TerraformLayout.moduleFileUrl(baseUrl, ns, name, system, version, "tgz"))
                    : Optional.empty();
            case PROXY -> upstream.moduleDownload(repo, ns, name, system, version).map(raw -> ModuleSource.archive(raw)
                    .map(a -> ModuleSource.rewrite(a, TerraformLayout.moduleFileUrl(baseUrl, ns, name, system, version, a.extension())))
                    .orElse(raw));
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<String> hit = moduleDownload(member, baseUrl, ns, name, system, version);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    Optional<StoredContent> moduleFile(Repository repo, String ns, String name, String system, String version, String extension) {
        return switch (repo.getType()) {
            case HOSTED -> "tgz".equals(extension)
                    ? storage.find(repo, TerraformLayout.moduleArchivePath(ns, name, system, version))
                    : Optional.empty();
            case PROXY -> upstream.moduleFile(repo, ns, name, system, version, extension);
            case GROUP -> {
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<StoredContent> hit = moduleFile(member, ns, name, system, version, extension);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    private Optional<ObjectNode> moduleInfo(Repository repo, String ns, String name, String system, String version) {
        if (repo.isProxy()) {
            return upstream.moduleInfo(repo, ns, name, system, version).filter(JsonNode::isObject).map(n -> (ObjectNode) n);
        }
        Optional<List<String>> versions = moduleVersionList(repo, ns, name, system);
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> target = version == null ? TerraformDocuments.latest(versions.get())
                : versions.get().stream().filter(v -> TerraformVersion.STRING_ORDER.compare(v, version) == 0).findFirst();
        return target.map(t -> documents.moduleInfo(ns, name, system, t, versions.get()));
    }

    // --- network mirror ------------------------------------------------------------------------

    /** Versions and their platforms a repository mirrors for {@code hostname}; empty when it knows nothing about the provider. */
    Optional<Map<String, List<TerraformDocuments.Platform>>> mirrorVersions(Repository repo, RepositoryRequest request,
                                                                             String hostname, String ns, String type) {
        return switch (repo.getType()) {
            case HOSTED -> {
                Map<String, List<TerraformDocuments.Platform>> map = new TreeMap<>(TerraformVersion.STRING_ORDER);
                for (TerraformDocuments.ProviderVersion v : hostedProviderVersions(repo, ns, type)) {
                    map.put(v.version(), v.platforms());
                }
                yield map.isEmpty() ? Optional.empty() : Optional.of(map);
            }
            case PROXY -> {
                if (!mirrorsHost(repo, request, hostname)) {
                    yield Optional.empty();
                }
                yield providerVersionList(repo, ns, type).map(list -> {
                    Map<String, List<TerraformDocuments.Platform>> map = new TreeMap<>(TerraformVersion.STRING_ORDER);
                    for (TerraformDocuments.ProviderVersion v : list) {
                        map.put(v.version(), v.platforms());
                    }
                    return map;
                });
            }
            case GROUP -> {
                // union of the versions; each version's platforms are its owner's (the first member that has it)
                Map<String, List<TerraformDocuments.Platform>> merged = new TreeMap<>(TerraformVersion.STRING_ORDER);
                boolean any = false;
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<Map<String, List<TerraformDocuments.Platform>>> part = mirrorVersions(member, request, hostname, ns, type);
                    if (part.isPresent()) {
                        any = true;
                        part.get().forEach(merged::putIfAbsent);
                    }
                }
                yield any ? Optional.of(merged) : Optional.empty();
            }
        };
    }

    /** The archives of one mirrored version keyed by {@code os_arch}; empty when the version is unknown. */
    Optional<Map<String, TerraformDocuments.MirrorArchive>> mirrorArchives(Repository repo, RepositoryRequest request,
                                                                            String hostname, String ns, String type, String version) {
        String baseUrl = request.baseUrl();
        return switch (repo.getType()) {
            case HOSTED -> {
                Map<String, TerraformDocuments.MirrorArchive> archives = new LinkedHashMap<>();
                for (StoredArchive a : hostedArchives(repo, ns, type, version)) {
                    archives.putIfAbsent(a.platform().key(), new TerraformDocuments.MirrorArchive(
                            TerraformLayout.providerFileUrl(baseUrl, ns, type, version, a.filename()),
                            List.of(TerraformDocuments.zipHash(a.asset().getBlobSha256()))));
                }
                yield archives.isEmpty() ? Optional.empty() : Optional.of(archives);
            }
            case PROXY -> {
                if (!mirrorsHost(repo, request, hostname)) {
                    yield Optional.empty();
                }
                Optional<List<TerraformDocuments.Platform>> platforms = providerVersionList(repo, ns, type)
                        .flatMap(list -> list.stream()
                                .filter(v -> TerraformVersion.STRING_ORDER.compare(v.version(), version) == 0)
                                .findFirst())
                        .map(TerraformDocuments.ProviderVersion::platforms);
                if (platforms.isEmpty()) {
                    yield Optional.empty();
                }
                Map<String, TerraformDocuments.MirrorArchive> archives = new LinkedHashMap<>();
                for (TerraformDocuments.Platform p : platforms.get()) {
                    upstream.providerDownload(repo, ns, type, version, p.os(), p.arch()).ifPresent(doc -> {
                        String filename = TerraformDocuments.text(doc, "filename");
                        if (filename == null) {
                            filename = TerraformLayout.fileNameOf(TerraformDocuments.text(doc, "download_url"));
                        }
                        if (filename == null || !TerraformPath.filename(filename)) {
                            return;
                        }
                        String shasum = TerraformDocuments.text(doc, "shasum");
                        List<String> hashes = shasum != null && shasum.matches("[0-9a-fA-F]{64}")
                                ? List.of(TerraformDocuments.zipHash(shasum.toLowerCase(Locale.ROOT))) : List.of();
                        archives.putIfAbsent(p.key(), new TerraformDocuments.MirrorArchive(
                                TerraformLayout.providerFileUrl(baseUrl, ns, type, version, filename), hashes));
                    });
                }
                // no platform document could be had (upstream down or offline, nothing cached): a miss, not an
                // empty version, so the dispatcher can answer 503 and the CLI does not record "no packages"
                yield archives.isEmpty() ? Optional.empty() : Optional.of(archives);
            }
            case GROUP -> {
                // the first member that has archives for the version owns it, as in the provider protocol
                for (Repository member : groups.leafMembers(repo)) {
                    Optional<Map<String, TerraformDocuments.MirrorArchive>> part = mirrorArchives(member, request, hostname, ns, type, version);
                    if (part.isPresent()) {
                        yield part;
                    }
                }
                yield Optional.empty();
            }
        };
    }

    /** A proxy mirrors its upstream's host and, for addresses written against Granary, the Granary host itself. */
    private static boolean mirrorsHost(Repository proxy, RepositoryRequest request, String hostname) {
        return hostname.equalsIgnoreCase(UpstreamTerraform.upstreamHost(proxy))
                || hostname.equalsIgnoreCase(serverHost(request));
    }

    static String serverHost(RepositoryRequest request) {
        String url = request.serverUrl().replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int slash = url.indexOf('/');
        return slash >= 0 ? url.substring(0, slash) : url;
    }

    // --- PUT (hosted uploads) ------------------------------------------------------------------

    private ResponseEntity<?> put(RepositoryRequest request, TerraformPath p) throws IOException {
        Repository repo = request.repository();
        if (!repo.isHosted()) {
            return readOnly(repo);
        }
        return switch (p.kind()) {
            case PROVIDER_ARCHIVE -> storeProviderArchive(request, p);
            case PROVIDER_SHASUMS -> storeShaSums(request, p);
            case PROVIDER_SHASUMS_SIG -> storeSignature(request, p);
            case PROVIDER_GPG_KEY -> storeSigningKeys(request, p);
            case MODULE_ARCHIVE -> storeModuleArchive(request, p);
            default -> ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                    "Upload provider builds with PUT v1/providers/<ns>/<type>/<version>/<os>/<arch>/<file>.zip, "
                            + "SHA256SUMS, SHA256SUMS.sig and gpg-key beside them, and modules with "
                            + "PUT v1/modules/<ns>/<name>/<system>/<version>.tgz");
        };
    }

    private ResponseEntity<?> readOnly(Repository repo) {
        String message;
        if (repo.isGroup()) {
            Optional<Repository> hosted = groups.leafMembers(repo).stream().filter(Repository::isHosted).findFirst();
            message = "Repository '" + repo.getName() + "' is a group and read-only; " + hosted
                    .map(h -> "upload to its hosted member '" + h.getName() + "'")
                    .orElse("it has no hosted member to upload to");
        } else {
            message = "Repository '" + repo.getName() + "' proxies " + repo.getRemoteUrl() + " and is read-only";
        }
        return ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, CODE_READ_ONLY, message);
    }

    private ResponseEntity<?> storeProviderArchive(RepositoryRequest request, TerraformPath p) throws IOException {
        Repository repo = request.repository();
        Path tmp = tempFile(".zip");
        try {
            Upload upload = copyBody(request, tmp);
            if (upload.size() == 0) {
                return invalidUpload("The request body is empty");
            }
            if (!upload.startsWith((byte) 'P', (byte) 'K')) {
                return invalidUpload("'" + p.filename() + "' is not a zip archive");
            }
            ReentrantLock lock = writeLock(repo, "providers", p.namespace(), p.name(), p.version());
            lock.lock();
            try {
                return storeProviderArchive(repo, p, tmp, upload);
            } finally {
                lock.unlock();
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private ResponseEntity<?> storeProviderArchive(Repository repo, TerraformPath p, Path tmp, Upload upload) throws IOException {
        String path = TerraformLayout.providerArchivePath(p.namespace(), p.name(), p.version(), p.os(), p.arch(), p.filename());
        List<StoredArchive> existing = hostedArchives(repo, p.namespace(), p.name(), p.version()).stream()
                .filter(a -> a.os().equals(p.os()) && a.arch().equals(p.arch()))
                .toList();
        if (!existing.isEmpty() && !repo.isAllowRedeploy()) {
            return ContentResponses.error(HttpStatus.CONFLICT, CODE_VERSION_EXISTS,
                    "Provider " + p.namespace() + "/" + p.name() + " " + p.version() + " already has a build for "
                            + p.os() + "_" + p.arch() + " and redeployment is disabled");
        }
        Optional<String> expected = shaSums(repo, p.namespace(), p.name(), p.version()).flatMap(s -> s.digestOf(p.filename()));
        if (expected.isPresent() && !expected.get().equals(upload.sha256())) {
            return ContentResponses.error(HttpStatus.BAD_REQUEST, CODE_CHECKSUM_MISMATCH,
                    "SHA256SUMS lists " + expected.get() + " for " + p.filename() + " but the upload is " + upload.sha256());
        }
        for (StoredArchive old : existing) {
            if (!old.asset().getPath().equals(path)) {
                storage.delete(repo, old.asset().getPath());
            }
        }
        try (InputStream in = Files.newInputStream(tmp)) {
            storage.store(repo, path, in, "application/zip", Asset.Origin.UPLOADED, false,
                    providerCoordinates(p.namespace(), p.name(), p.version()));
        }
        log.info("stored provider {}/{} {} {}_{} in {}", p.namespace(), p.name(), p.version(), p.os(), p.arch(), repo.getName());
        ObjectNode body = json.createObjectNode();
        body.put("namespace", p.namespace());
        body.put("type", p.name());
        body.put("version", p.version());
        body.put("os", p.os());
        body.put("arch", p.arch());
        body.put("filename", p.filename());
        body.put("sha256", upload.sha256());
        return created(body);
    }

    private ResponseEntity<?> storeShaSums(RepositoryRequest request, TerraformPath p) throws IOException {
        Repository repo = request.repository();
        byte[] bytes = readLimited(request, MAX_TEXT_UPLOAD);
        if (bytes == null) {
            return tooLarge("SHA256SUMS", MAX_TEXT_UPLOAD);
        }
        Sha256Sums sums = Sha256Sums.parse(new String(bytes, StandardCharsets.UTF_8));
        if (sums.isEmpty()) {
            return invalidUpload("SHA256SUMS must contain at least one '<sha256>  <file name>' line");
        }
        ReentrantLock lock = writeLock(repo, "providers", p.namespace(), p.name(), p.version());
        lock.lock();
        try {
            return storeShaSums(repo, p, bytes, sums);
        } finally {
            lock.unlock();
        }
    }

    private ResponseEntity<?> storeShaSums(Repository repo, TerraformPath p, byte[] bytes, Sha256Sums sums) {
        String path = TerraformLayout.shaSumsPath(p.namespace(), p.name(), p.version());
        if (storage.exists(repo, path) && !repo.isAllowRedeploy()) {
            return ContentResponses.error(HttpStatus.CONFLICT, CODE_VERSION_EXISTS,
                    "SHA256SUMS of " + p.namespace() + "/" + p.name() + " " + p.version() + " already exists and redeployment is disabled");
        }
        for (StoredArchive archive : hostedArchives(repo, p.namespace(), p.name(), p.version())) {
            Optional<String> listed = sums.digestOf(archive.filename());
            if (listed.isPresent() && !listed.get().equals(archive.asset().getBlobSha256())) {
                return ContentResponses.error(HttpStatus.BAD_REQUEST, CODE_CHECKSUM_MISMATCH,
                        "SHA256SUMS lists " + listed.get() + " for " + archive.filename() + " but the stored archive is "
                                + archive.asset().getBlobSha256());
            }
        }
        storage.store(repo, path, new ByteArrayInputStream(bytes), MediaType.TEXT_PLAIN_VALUE, Asset.Origin.UPLOADED, false,
                providerCoordinates(p.namespace(), p.name(), p.version()));
        ObjectNode body = json.createObjectNode();
        body.put("files", sums.entries().size());
        body.put("ignoredLines", sums.malformedLines());
        return created(body);
    }

    private ResponseEntity<?> storeSignature(RepositoryRequest request, TerraformPath p) throws IOException {
        Repository repo = request.repository();
        byte[] bytes = readLimited(request, MAX_SIGNATURE_UPLOAD);
        if (bytes == null) {
            return tooLarge("SHA256SUMS.sig", MAX_SIGNATURE_UPLOAD);
        }
        String armored = bytes.length > 10 ? new String(bytes, 0, 10, StandardCharsets.US_ASCII) : "";
        if (bytes.length == 0 || !((bytes[0] & 0x80) != 0 || armored.startsWith("-----BEGIN"))) {
            return invalidUpload("SHA256SUMS.sig is not an OpenPGP signature");
        }
        ReentrantLock lock = writeLock(repo, "providers", p.namespace(), p.name(), p.version());
        lock.lock();
        try {
            return storeSignature(repo, p, bytes);
        } finally {
            lock.unlock();
        }
    }

    private ResponseEntity<?> storeSignature(Repository repo, TerraformPath p, byte[] bytes) {
        String path = TerraformLayout.shaSumsSigPath(p.namespace(), p.name(), p.version());
        if (storage.exists(repo, path) && !repo.isAllowRedeploy()) {
            return ContentResponses.error(HttpStatus.CONFLICT, CODE_VERSION_EXISTS,
                    "SHA256SUMS.sig of " + p.namespace() + "/" + p.name() + " " + p.version() + " already exists and redeployment is disabled");
        }
        storage.store(repo, path, new ByteArrayInputStream(bytes), MediaType.APPLICATION_OCTET_STREAM_VALUE,
                Asset.Origin.UPLOADED, false, providerCoordinates(p.namespace(), p.name(), p.version()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private ResponseEntity<?> storeSigningKeys(RepositoryRequest request, TerraformPath p) throws IOException {
        Repository repo = request.repository();
        byte[] bytes = readLimited(request, MAX_TEXT_UPLOAD);
        if (bytes == null) {
            return tooLarge("gpg-key", MAX_TEXT_UPLOAD);
        }
        JsonNode uploaded;
        try {
            uploaded = bytes.length == 0 ? null : json.readTree(bytes);
        } catch (JacksonException e) {
            return invalidUpload("gpg-key must be JSON: " + e.getOriginalMessage());
        }
        Optional<ObjectNode> keys = documents.normaliseSigningKeys(uploaded);
        if (keys.isEmpty()) {
            return invalidUpload("gpg-key must be {\"key_id\":\"…\",\"ascii_armor\":\"-----BEGIN PGP PUBLIC KEY BLOCK-----…\"} "
                    + "or {\"gpg_public_keys\":[…]}");
        }
        String path = TerraformLayout.gpgKeyPath(p.namespace(), p.name());
        boolean replaced = storage.exists(repo, path);
        storage.store(repo, path, new ByteArrayInputStream(json.writeValueAsBytes(keys.get())), MediaType.APPLICATION_JSON_VALUE,
                Asset.Origin.UPLOADED, true);
        return ResponseEntity.status(replaced ? HttpStatus.OK : HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsBytes(keys.get()));
    }

    private ResponseEntity<?> storeModuleArchive(RepositoryRequest request, TerraformPath p) throws IOException {
        Repository repo = request.repository();
        Path tmp = tempFile(".tgz");
        try {
            Upload upload = copyBody(request, tmp);
            if (upload.size() == 0) {
                return invalidUpload("The request body is empty");
            }
            if (!upload.startsWith((byte) 0x1f, (byte) 0x8b)) {
                return invalidUpload("'" + p.filename() + "' is not a gzip-compressed tar archive");
            }
            ReentrantLock lock = writeLock(repo, "modules", p.namespace(), p.name(), p.system(), p.version());
            lock.lock();
            try {
                return storeModuleArchive(repo, p, tmp, upload);
            } finally {
                lock.unlock();
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private ResponseEntity<?> storeModuleArchive(Repository repo, TerraformPath p, Path tmp, Upload upload) throws IOException {
        String path = TerraformLayout.moduleArchivePath(p.namespace(), p.name(), p.system(), p.version());
        if (storage.exists(repo, path) && !repo.isAllowRedeploy()) {
            return ContentResponses.error(HttpStatus.CONFLICT, CODE_VERSION_EXISTS,
                    "Module " + p.namespace() + "/" + p.name() + "/" + p.system() + " " + p.version()
                            + " already exists and redeployment is disabled");
        }
        try (InputStream in = Files.newInputStream(tmp)) {
            storage.store(repo, path, in, "application/gzip", Asset.Origin.UPLOADED, false,
                    new StorageService.ComponentCoordinates(p.namespace(), p.name() + "/" + p.system(), p.version()));
        }
        log.info("stored module {}/{}/{} {} in {}", p.namespace(), p.name(), p.system(), p.version(), repo.getName());
        ObjectNode body = json.createObjectNode();
        body.put("namespace", p.namespace());
        body.put("name", p.name());
        body.put("system", p.system());
        body.put("version", p.version());
        body.put("sha256", upload.sha256());
        return created(body);
    }

    private ReentrantLock writeLock(Repository repo, String... coordinates) {
        int hash = 31 * String.valueOf(repo.getId()).hashCode() + String.join("/", coordinates).hashCode();
        return writeLocks[Math.floorMod(hash, writeLocks.length)];
    }

    private static StorageService.ComponentCoordinates providerCoordinates(String ns, String type, String version) {
        return new StorageService.ComponentCoordinates(ns, type, version);
    }

    // --- DELETE -------------------------------------------------------------------------------

    private ResponseEntity<?> delete(RepositoryRequest request, TerraformPath p) {
        Repository repo = request.repository();
        if (!repo.isHosted()) {
            return readOnly(repo);
        }
        String ns = p.namespace();
        String name = p.name();
        return switch (p.kind()) {
            case PROVIDER_INFO -> deletePrefix(repo, TerraformLayout.providerPrefix(ns, name));
            case PROVIDER_VERSION_INFO -> deletePrefix(repo, TerraformLayout.providerVersionPrefix(ns, name, p.version()));
            case PROVIDER_ARCHIVE -> deleteOne(repo, TerraformLayout.providerArchivePath(ns, name, p.version(), p.os(), p.arch(), p.filename()));
            case PROVIDER_SHASUMS -> deleteOne(repo, TerraformLayout.shaSumsPath(ns, name, p.version()));
            case PROVIDER_SHASUMS_SIG -> deleteOne(repo, TerraformLayout.shaSumsSigPath(ns, name, p.version()));
            case PROVIDER_GPG_KEY -> deleteOne(repo, TerraformLayout.gpgKeyPath(ns, name));
            case MODULE_INFO -> deletePrefix(repo, TerraformLayout.modulePrefix(ns, name, p.system()));
            case MODULE_VERSION_INFO, MODULE_ARCHIVE -> deleteOne(repo, TerraformLayout.moduleArchivePath(ns, name, p.system(), p.version()));
            default -> ContentResponses.error(HttpStatus.METHOD_NOT_ALLOWED, "method.notAllowed",
                    "Delete a provider version with DELETE v1/providers/<ns>/<type>/<version> and a module version with "
                            + "DELETE v1/modules/<ns>/<name>/<system>/<version>");
        };
    }

    private ResponseEntity<?> deletePrefix(Repository repo, String prefix) {
        int deleted = 0;
        for (Asset asset : storage.list(repo, prefix)) {
            if (asset.getPath().startsWith(prefix) && storage.delete(repo, asset.getPath())) {
                deleted++;
            }
        }
        if (deleted == 0) {
            return ContentResponses.notFound();
        }
        log.info("deleted {} files under {} from {}", deleted, prefix, repo.getName());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> deleteOne(Repository repo, String path) {
        return storage.delete(repo, path) ? ResponseEntity.noContent().build() : ContentResponses.notFound();
    }

    // --- bodies -------------------------------------------------------------------------------

    /** What arrived: size, SHA-256 and the first bytes (for magic-number checks). */
    record Upload(long size, String sha256, byte[] head) {
        boolean startsWith(byte a, byte b) {
            return head.length >= 2 && head[0] == a && head[1] == b;
        }
    }

    private Path tempFile(String suffix) throws IOException {
        Files.createDirectories(tmpDir);
        return Files.createTempFile(tmpDir, "upload-", suffix);
    }

    private static Upload copyBody(RepositoryRequest request, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] head = new byte[4];
        int headLength = 0;
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = request.body(); OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (headLength < head.length) {
                    int n = Math.min(head.length - headLength, read);
                    System.arraycopy(buffer, 0, head, headLength, n);
                    headLength += n;
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                size += read;
            }
        }
        return new Upload(size, HexFormat.of().formatHex(digest.digest()), java.util.Arrays.copyOf(head, headLength));
    }

    /** The whole body, or {@code null} when it exceeds {@code limit}. */
    private static byte[] readLimited(RepositoryRequest request, long limit) throws IOException {
        try (InputStream in = request.body()) {
            byte[] bytes = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 16));
            return bytes.length > limit ? null : bytes;
        }
    }

    // --- responses ----------------------------------------------------------------------------

    /** A generated document with a content hash as ETag so clients polling versions get cheap 304s. */
    private ResponseEntity<?> json(RepositoryRequest request, JsonNode document) {
        byte[] bytes = json.writeValueAsBytes(document);
        String etag = "\"" + Checksums.hex("SHA-256", bytes) + "\"";
        String ifNoneMatch = request.header(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .eTag(etag)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(bytes.length)
                .cacheControl(CacheControl.noCache());
        return request.isHead() ? builder.build() : builder.body(bytes);
    }

    private ResponseEntity<?> created(JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsBytes(body));
    }

    private ResponseEntity<?> stream(RepositoryRequest request, Optional<StoredContent> content) {
        if (content.isEmpty()) {
            return ContentResponses.notFound();
        }
        ResponseEntity<?> response = ContentResponses.stream(request, content.get());
        if (!request.isHead() && response.getStatusCode().is2xxSuccessful()) {
            storage.recordDownload(content.get().asset());
        }
        return response;
    }

    private static ResponseEntity<?> invalidUpload(String message) {
        return ContentResponses.error(HttpStatus.BAD_REQUEST, CODE_INVALID_UPLOAD, message);
    }

    private static ResponseEntity<?> tooLarge(String what, long limit) {
        return ContentResponses.error(HttpStatus.PAYLOAD_TOO_LARGE, CODE_INVALID_UPLOAD,
                what + " may not exceed " + limit + " bytes");
    }

    private static ResponseEntity<?> providerNotFound(String ns, String type) {
        return ContentResponses.notFound("Provider " + ns + "/" + type + " not found");
    }

    private static ResponseEntity<?> moduleNotFound(String ns, String name, String system) {
        return ContentResponses.notFound("Module " + ns + "/" + name + "/" + system + " not found");
    }
}
