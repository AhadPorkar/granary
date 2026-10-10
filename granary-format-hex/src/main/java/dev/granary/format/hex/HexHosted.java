package dev.granary.format.hex;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Everything a hosted Hex repository stores and how it stays consistent: release tarballs under
 * {@code tarballs/}, documentation under {@code docs/}, one JSON state per package under {@code .hex/packages/}
 * and the three signed registry documents ({@code names}, {@code versions}, {@code packages/<name>}) built
 * from them after every change.
 *
 * <p>Registry documents are checked whenever they are served: a document signed with another key, carrying
 * another repository name or listing releases whose tarballs are gone (deleted from the component browser,
 * for example) is rebuilt on the spot, so clients never see a registry that disagrees with the tarballs.
 */
@Component
public class HexHosted {

    public static final String TARBALL_CONTENT_TYPE = "application/octet-stream";
    public static final String DOCS_CONTENT_TYPE = "application/gzip";
    static final String STATE_CONTENT_TYPE = "application/json";
    static final int MAX_TARBALL_SIZE = 64 * 1024 * 1024;
    static final int MAX_DOCS_SIZE = 64 * 1024 * 1024;
    static final int MAX_RETIREMENT_MESSAGE = 140;
    static final String NAMES = "names";
    static final String VERSIONS = "versions";

    private static final Logger log = LoggerFactory.getLogger(HexHosted.class);

    private final StorageService storage;
    private final HexSigningKey key;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public HexHosted(StorageService storage, HexSigningKey key) {
        this.storage = storage;
        this.key = key;
    }

    /** A successful publish. */
    public record Published(HexPackageState state, HexPackageState.Release release, HexTarball tarball, boolean replaced) {
    }

    // --- reads -----------------------------------------------------------------------------------

    public Optional<HexPackageState> state(Repository repo, String name) {
        return storage.find(repo, HexPackageState.path(name)).flatMap(content -> {
            try {
                return Optional.of(HexPackageState.fromJson(content.readAllBytes()));
            } catch (IOException | IllegalArgumentException e) {
                log.warn("package state {} of {} is unreadable: {}", name, repo.getName(), e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** Every package of the repository, by name. */
    public List<HexPackageState> states(Repository repo) {
        List<HexPackageState> result = new ArrayList<>();
        for (Asset asset : storage.list(repo, HexPackageState.DIRECTORY)) {
            String file = asset.getPath().substring(HexPackageState.DIRECTORY.length());
            if (!file.endsWith(".json") || file.contains("/")) {
                continue;
            }
            state(repo, file.substring(0, file.length() - ".json".length())).ifPresent(result::add);
        }
        result.sort(Comparator.comparing(HexPackageState::name));
        return result;
    }

    public Optional<StoredContent> tarball(Repository repo, String name, String version) {
        return storage.find(repo, HexPath.tarballPath(name, version));
    }

    public Optional<StoredContent> docs(Repository repo, String name, String version) {
        return storage.find(repo, docsPath(name, version));
    }

    /**
     * {@code names}, {@code versions} or {@code packages/<name>}, rebuilt first when the stored copy is
     * missing or no longer matches the key, the repository name or the tarballs. A package without releases
     * answers empty; {@code names} and {@code versions} always exist.
     */
    public Optional<StoredContent> registryDocument(Repository repo, HexPath.Kind kind, String name) {
        String path = documentPath(kind, name);
        Optional<StoredContent> stored = storage.find(repo, path);
        if (stored.isPresent() && isCurrent(repo, kind, name, stored.get())) {
            return stored;
        }
        if (kind == HexPath.Kind.PACKAGE && stored.isEmpty() && !knows(repo, name)) {
            return Optional.empty(); // the common miss (a group asking its hosted member for a hex.pm package): nothing to heal
        }
        return locked(repo, () -> {
            Optional<StoredContent> again = storage.find(repo, path);
            if (again.isPresent() && isCurrent(repo, kind, name, again.get())) {
                return again;
            }
            log.info("regenerating hex registry document {} of {}", path, repo.getName());
            if (kind == HexPath.Kind.PACKAGE) {
                Optional<HexPackageState> state = reconcile(repo, name);
                writePackageDocument(repo, name, state);
                rebuildIndexes(repo);
            } else {
                reconcileAll(repo);
                rebuildIndexes(repo);
            }
            return storage.find(repo, path);
        });
    }

    // --- publish ---------------------------------------------------------------------------------

    /**
     * Stores a release tarball. Validates everything first (tarball structure, checksums, metadata, the name
     * the URL named, the redeploy rules) so a refused publish leaves the repository untouched.
     *
     * @param expectedName the package name from the URL ({@code api/packages/<name>/releases}), or {@code null}
     */
    public Published publish(Repository repo, byte[] bytes, String expectedName, boolean replace, GranaryPrincipal principal) {
        if (bytes.length == 0) {
            throw HexError.validation("tar", "the request body is empty; send the release tarball");
        }
        if (bytes.length > MAX_TARBALL_SIZE) {
            throw HexError.validation("tar", "the tarball exceeds " + (MAX_TARBALL_SIZE / (1024 * 1024)) + " MiB");
        }
        HexTarball tarball;
        try {
            tarball = HexTarball.read(bytes);
        } catch (IllegalArgumentException | ArithmeticException e) {
            // TarballException, ParseException and MetadataException, and anything else a hostile tarball provokes:
            // a refused upload is the client's 422, never a server error
            throw HexError.validation("tar", e.getMessage() == null ? "the tarball cannot be read" : e.getMessage());
        }
        HexMetadata metadata = tarball.metadata();
        if (expectedName != null && !expectedName.equals(metadata.name())) {
            throw HexError.validation("name", "the tarball is " + metadata.name() + ", but the URL names " + expectedName);
        }
        String name = metadata.name();
        String version = HexVersion.parse(metadata.version()).map(HexVersion::toString).orElse(metadata.version());
        return locked(repo, () -> {
            Instant now = Instant.now();
            Optional<HexPackageState> existing = state(repo, name);
            Optional<HexPackageState.Release> previous = existing.flatMap(s -> s.release(version));
            if (previous.isEmpty()) {
                previous = fromStoredTarball(repo, name, version); // a tarball the state does not know about yet
            }
            boolean exists = previous.isPresent() || tarball(repo, name, version).isPresent();
            if (exists) {
                if (!replace) {
                    throw HexError.validation("inserted_at", "must include the --replace flag to update an existing release");
                }
                if (!repo.isAllowRedeploy()) {
                    throw HexError.validation("inserted_at", "release " + name + " " + version + " already exists and "
                            + "repository '" + repo.getName() + "' does not allow replacing releases (allowRedeploy is off)");
                }
            }
            storage.store(repo, HexPath.tarballPath(name, version), new ByteArrayInputStream(bytes), TARBALL_CONTENT_TYPE,
                    Asset.Origin.UPLOADED, false, coordinates(name, version));
            HexPackageState.Release release = new HexPackageState.Release(version, tarball.innerChecksumHex(),
                    tarball.outerChecksumHex(),
                    previous.map(HexPackageState.Release::insertedAt).orElse(now.toString()), now.toString(),
                    principal == null || principal.anonymous() ? null : principal.username(),
                    dependencies(metadata), previous.map(HexPackageState.Release::retirement).orElse(null),
                    HexPackageState.Meta.of(metadata), previous.map(HexPackageState.Release::hasDocs).orElse(false),
                    bytes.length);
            HexPackageState state = existing.orElseGet(() -> new HexPackageState(name, now.toString(), now.toString(), List.of()))
                    .with(release, now);
            saveAndRegenerate(repo, state);
            log.info("published hex package {} {} to {}{}", name, version, repo.getName(), exists ? " (replaced)" : "");
            return new Published(state, release, tarball, exists);
        });
    }

    private static List<HexPackageState.Dependency> dependencies(HexMetadata metadata) {
        return metadata.requirements().stream()
                .map(r -> new HexPackageState.Dependency(r.name(), r.app(), r.requirement(), r.optional(), r.repository()))
                .toList();
    }

    // --- revert, retire, docs --------------------------------------------------------------------

    /** {@code mix hex.publish --revert VERSION}: removes the release, its tarball and its documentation. */
    public void revert(Repository repo, String name, String version, GranaryPrincipal principal) {
        locked(repo, () -> {
            HexPackageState state = reconcile(repo, name)
                    .orElseThrow(() -> HexError.notFound("package " + name + " does not exist"));
            HexPackageState.Release release = state.release(version)
                    .orElseThrow(() -> HexError.notFound("release " + name + " " + version + " does not exist"));
            Optional<Duration> window = HexSettings.revertWindow(repo);
            boolean admin = principal != null && principal.isAdmin();
            if (window.isPresent() && !admin && release.inserted().plus(window.get()).isBefore(Instant.now())) {
                throw HexError.validation("inserted_at", "can only revert a release up to " + window.get().toMinutes()
                        + " minutes after publication");
            }
            storage.delete(repo, HexPath.tarballPath(name, version));
            storage.delete(repo, docsPath(name, version));
            HexPackageState remaining = state.without(version, Instant.now());
            if (remaining.releases().isEmpty()) {
                storage.delete(repo, HexPackageState.path(name));
                storage.delete(repo, HexPath.packagePath(name));
                rebuildIndexes(repo);
            } else {
                saveAndRegenerate(repo, remaining);
            }
            log.info("reverted hex package {} {} in {}", name, version, repo.getName());
            return null;
        });
    }

    public HexPackageState.Release retire(Repository repo, String name, String version, String reason, String message) {
        HexRegistry.RetirementReason code = HexRegistry.RetirementReason.fromKey(reason)
                .orElseThrow(() -> HexError.validation("reason", "is invalid; use one of other, invalid, security, "
                        + "deprecated, renamed"));
        String text = message == null ? "" : message.strip();
        if (text.codePointCount(0, text.length()) > MAX_RETIREMENT_MESSAGE) {
            throw HexError.validation("message", "should be at most " + MAX_RETIREMENT_MESSAGE + " character(s)");
        }
        return updateRelease(repo, name, version,
                (release, now) -> release.withRetirement(new HexPackageState.Retirement(code.key(), text.isEmpty() ? null : text), now));
    }

    public HexPackageState.Release unretire(Repository repo, String name, String version) {
        return updateRelease(repo, name, version, (release, now) -> release.withRetirement(null, now));
    }

    public HexPackageState.Release publishDocs(Repository repo, String name, String version, byte[] docs) {
        if (docs.length == 0) {
            throw HexError.validation("tar", "the request body is empty; send the documentation tarball");
        }
        if (docs.length > MAX_DOCS_SIZE) {
            throw HexError.validation("tar", "the documentation exceeds " + (MAX_DOCS_SIZE / (1024 * 1024)) + " MiB");
        }
        if (docs.length < 2 || (docs[0] & 0xFF) != 0x1F || (docs[1] & 0xFF) != 0x8B) {
            throw HexError.validation("tar", "documentation must be a gzip-compressed tarball");
        }
        return updateRelease(repo, name, version, (release, now) -> {
            storage.store(repo, docsPath(name, version), new ByteArrayInputStream(docs), DOCS_CONTENT_TYPE,
                    Asset.Origin.UPLOADED, false, coordinates(name, version));
            return release.withDocs(true, now);
        });
    }

    public HexPackageState.Release deleteDocs(Repository repo, String name, String version) {
        return updateRelease(repo, name, version, (release, now) -> {
            if (!storage.delete(repo, docsPath(name, version)) && !release.hasDocs()) {
                throw HexError.notFound("release " + name + " " + version + " has no documentation");
            }
            return release.withDocs(false, now);
        });
    }

    private interface ReleaseUpdate {
        HexPackageState.Release apply(HexPackageState.Release release, Instant now);
    }

    private HexPackageState.Release updateRelease(Repository repo, String name, String version, ReleaseUpdate update) {
        return locked(repo, () -> {
            HexPackageState state = reconcile(repo, name)
                    .orElseThrow(() -> HexError.notFound("package " + name + " does not exist"));
            HexPackageState.Release release = state.release(version)
                    .orElseThrow(() -> HexError.notFound("release " + name + " " + version + " does not exist"));
            Instant now = Instant.now();
            HexPackageState.Release updated = update.apply(release, now);
            saveAndRegenerate(repo, state.with(updated, now));
            return updated;
        });
    }

    // --- document generation ---------------------------------------------------------------------

    /** The {@code Package} message of a hosted package, as the registry serves it. */
    HexRegistry.Package packageMessage(Repository repo, HexPackageState state) {
        String repository = HexSettings.repositoryName(repo);
        List<HexRegistry.Release> releases = new ArrayList<>();
        for (HexPackageState.Release r : state.releases()) {
            List<HexRegistry.Dependency> deps = r.dependencies().stream()
                    .map(d -> new HexRegistry.Dependency(d.name(), d.requirement(), d.optional(),
                            d.app() == null || d.app().equals(d.name()) ? "" : d.app(),
                            d.repository() == null || d.repository().equals(repository) ? "" : d.repository()))
                    .toList();
            HexRegistry.Retirement retirement = r.retirement() == null ? null
                    : new HexRegistry.Retirement(r.retirement().reasonCode(), r.retirement().message());
            Instant inserted = r.inserted();
            releases.add(new HexRegistry.Release(r.version(), r.innerChecksumBytes(), deps, retirement,
                    r.outerChecksumBytes(), List.of(), inserted.equals(Instant.EPOCH) ? null : HexRegistry.Timestamp.of(inserted)));
        }
        return new HexRegistry.Package(state.name(), repository, releases);
    }

    static HexRegistry.VersionsEntry versionsEntry(HexPackageState state) {
        List<String> versions = state.versions();
        List<Integer> retired = new ArrayList<>();
        for (int i = 0; i < state.releases().size(); i++) {
            if (state.releases().get(i).retirement() != null) {
                retired.add(i);
            }
        }
        return new HexRegistry.VersionsEntry(state.name(), versions, retired);
    }

    static HexRegistry.NamesEntry namesEntry(HexPackageState state) {
        Instant updated = HexPackageState.parseInstant(state.updatedAt());
        return new HexRegistry.NamesEntry(state.name(), updated.equals(Instant.EPOCH) ? null : HexRegistry.Timestamp.of(updated));
    }

    private void saveAndRegenerate(Repository repo, HexPackageState state) {
        storage.store(repo, HexPackageState.path(state.name()), new ByteArrayInputStream(state.toJson()),
                STATE_CONTENT_TYPE, Asset.Origin.UPLOADED, true);
        writePackageDocument(repo, state.name(), Optional.of(state));
        updateIndexes(repo, state);
    }

    private void writePackageDocument(Repository repo, String name, Optional<HexPackageState> state) {
        if (state.isEmpty() || state.get().releases().isEmpty()) {
            storage.delete(repo, HexPath.packagePath(name));
            return;
        }
        byte[] payload = HexRegistry.encodePackage(packageMessage(repo, state.get()));
        storeDocument(repo, HexPath.packagePath(name), payload);
    }

    /** Replaces one package's entries in {@code names} and {@code versions}; rebuilds both when they are not ours. */
    private void updateIndexes(Repository repo, HexPackageState state) {
        String repository = HexSettings.repositoryName(repo);
        Optional<HexRegistry.Versions> versions = ownDocument(repo, VERSIONS, HexRegistry::decodeVersions)
                .filter(v -> v.repository().equals(repository));
        Optional<HexRegistry.Names> names = ownDocument(repo, NAMES, HexRegistry::decodeNames)
                .filter(n -> n.repository().equals(repository));
        if (versions.isEmpty() || names.isEmpty()) {
            rebuildIndexes(repo);
            return;
        }
        TreeMap<String, HexRegistry.VersionsEntry> versionEntries = new TreeMap<>();
        versions.get().packages().forEach(e -> versionEntries.put(e.name(), e));
        TreeMap<String, HexRegistry.NamesEntry> nameEntries = new TreeMap<>();
        names.get().packages().forEach(e -> nameEntries.put(e.name(), e));
        if (state.releases().isEmpty()) {
            versionEntries.remove(state.name());
            nameEntries.remove(state.name());
        } else {
            versionEntries.put(state.name(), versionsEntry(state));
            nameEntries.put(state.name(), namesEntry(state));
        }
        storeDocument(repo, VERSIONS, HexRegistry.encodeVersions(
                new HexRegistry.Versions(repository, List.copyOf(versionEntries.values()))));
        storeDocument(repo, NAMES, HexRegistry.encodeNames(
                new HexRegistry.Names(repository, List.copyOf(nameEntries.values()))));
    }

    private void rebuildIndexes(Repository repo) {
        String repository = HexSettings.repositoryName(repo);
        List<HexPackageState> states = states(repo).stream().filter(s -> !s.releases().isEmpty()).toList();
        storeDocument(repo, VERSIONS, HexRegistry.encodeVersions(new HexRegistry.Versions(repository,
                states.stream().map(HexHosted::versionsEntry).toList())));
        storeDocument(repo, NAMES, HexRegistry.encodeNames(new HexRegistry.Names(repository,
                states.stream().map(HexHosted::namesEntry).toList())));
    }

    private void storeDocument(Repository repo, String path, byte[] payload) {
        byte[] packed = HexRegistry.pack(payload, key::sign);
        storage.store(repo, path, new ByteArrayInputStream(packed), HexResponses.REGISTRY_CONTENT_TYPE,
                Asset.Origin.UPLOADED, true);
    }

    /** A stored document Granary signed with its current key, decoded; empty when missing or not verifiable. */
    private <T> Optional<T> ownDocument(Repository repo, String path, java.util.function.Function<byte[], T> decoder) {
        Optional<StoredContent> stored = storage.find(repo, path);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            HexRegistry.Signed signed = HexRegistry.decodeSigned(HexRegistry.gunzip(stored.get().readAllBytes()));
            if (!key.verify(signed.payload(), signed.signature())) {
                return Optional.empty();
            }
            return Optional.of(decoder.apply(signed.payload()));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // --- consistency -----------------------------------------------------------------------------

    private boolean isCurrent(Repository repo, HexPath.Kind kind, String name, StoredContent stored) {
        String repository = HexSettings.repositoryName(repo);
        try {
            HexRegistry.Signed signed = HexRegistry.decodeSigned(HexRegistry.gunzip(stored.readAllBytes()));
            if (!key.verify(signed.payload(), signed.signature())) {
                return false;
            }
            Map<String, Set<String>> tarballs = kind == HexPath.Kind.PACKAGE ? tarballVersions(repo, name) : tarballVersions(repo, null);
            return switch (kind) {
                case PACKAGE -> {
                    HexRegistry.Package pkg = HexRegistry.decodePackage(signed.payload());
                    Set<String> listed = new HashSet<>();
                    pkg.releases().forEach(r -> listed.add(r.version()));
                    yield pkg.repository().equals(repository) && pkg.name().equals(name)
                            && listed.equals(tarballs.getOrDefault(name, Set.of()));
                }
                case VERSIONS -> {
                    HexRegistry.Versions versions = HexRegistry.decodeVersions(signed.payload());
                    Map<String, Set<String>> listed = new HashMap<>();
                    versions.packages().forEach(e -> listed.put(e.name(), new HashSet<>(e.versions())));
                    yield versions.repository().equals(repository) && listed.equals(tarballs);
                }
                case NAMES -> {
                    HexRegistry.Names names = HexRegistry.decodeNames(signed.payload());
                    yield names.repository().equals(repository) && new HashSet<>(names.names()).equals(tarballs.keySet());
                }
                default -> false;
            };
        } catch (IOException | IllegalArgumentException e) {
            log.warn("stored hex registry document {} of {} is unreadable: {}", stored.asset().getPath(), repo.getName(),
                    e.getMessage());
            return false;
        }
    }

    /** Whether anything of the package is stored: its state or at least one tarball. */
    private boolean knows(Repository repo, String name) {
        return storage.exists(repo, HexPackageState.path(name)) || !tarballVersions(repo, name).isEmpty();
    }

    /** Package → versions that have a tarball, for one package or (name {@code null}) the whole repository. */
    private Map<String, Set<String>> tarballVersions(Repository repo, String name) {
        Map<String, Set<String>> result = new HashMap<>();
        String prefix = name == null ? "tarballs/" : "tarballs/" + name + "-";
        for (Asset asset : storage.list(repo, prefix)) {
            HexPath path = HexPath.parse(asset.getPath());
            if (path.kind() == HexPath.Kind.TARBALL && (name == null || name.equals(path.name()))) {
                result.computeIfAbsent(path.name(), k -> new TreeSet<>(HexVersion.ORDER)).add(path.version());
            }
        }
        result.replaceAll((k, v) -> new HashSet<>(v));
        return result;
    }

    /**
     * Brings one package's state in line with its tarballs: releases whose tarball is gone are dropped,
     * tarballs without a release (a state lost or written by an older version) are read back in.
     */
    private Optional<HexPackageState> reconcile(Repository repo, String name) {
        Set<String> present = tarballVersions(repo, name).getOrDefault(name, Set.of());
        Optional<HexPackageState> state = state(repo, name);
        Set<String> known = new HashSet<>(state.map(HexPackageState::versions).orElse(List.of()));
        if (known.equals(present)) {
            return state;
        }
        Instant now = Instant.now();
        HexPackageState updated = state.orElseGet(() -> new HexPackageState(name, now.toString(), now.toString(), List.of()));
        for (String version : known) {
            if (!present.contains(version)) {
                log.info("hex release {} {} of {} has no tarball any more; dropping it from the registry", name, version,
                        repo.getName());
                updated = updated.without(version, now);
            }
        }
        for (String version : present) {
            if (!known.contains(version)) {
                Optional<HexPackageState.Release> recovered = fromStoredTarball(repo, name, version);
                if (recovered.isPresent()) {
                    updated = updated.with(recovered.get(), now);
                }
            }
        }
        if (updated.releases().isEmpty()) {
            storage.delete(repo, HexPackageState.path(name));
            return Optional.empty();
        }
        storage.store(repo, HexPackageState.path(name), new ByteArrayInputStream(updated.toJson()), STATE_CONTENT_TYPE,
                Asset.Origin.UPLOADED, true);
        return Optional.of(updated);
    }

    private void reconcileAll(Repository repo) {
        Set<String> names = new TreeSet<>(tarballVersions(repo, null).keySet());
        states(repo).forEach(s -> names.add(s.name()));
        for (String name : names) {
            Optional<HexPackageState> state = reconcile(repo, name);
            writePackageDocument(repo, name, state);
        }
    }

    /** Rebuilds a release entry from a stored tarball; empty (and logged) when the tarball is unreadable. */
    private Optional<HexPackageState.Release> fromStoredTarball(Repository repo, String name, String version) {
        Optional<StoredContent> content = tarball(repo, name, version);
        if (content.isEmpty()) {
            return Optional.empty();
        }
        try {
            byte[] bytes = content.get().readAllBytes();
            HexTarball tarball = HexTarball.read(bytes);
            if (!tarball.metadata().name().equals(name)) {
                log.warn("stored hex tarball {} contains package {}", content.get().asset().getPath(), tarball.metadata().name());
                return Optional.empty();
            }
            Asset asset = content.get().asset();
            String inserted = (asset.getCreatedAt() == null ? Instant.now() : asset.getCreatedAt()).toString();
            boolean hasDocs = storage.exists(repo, docsPath(name, version));
            return Optional.of(new HexPackageState.Release(version, tarball.innerChecksumHex(), tarball.outerChecksumHex(),
                    inserted, inserted, null, dependencies(tarball.metadata()), null,
                    HexPackageState.Meta.of(tarball.metadata()), hasDocs, bytes.length));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("stored hex tarball {} of {} is unreadable: {}", content.get().asset().getPath(), repo.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    static String docsPath(String name, String version) {
        return "docs/" + name + "-" + version + ".tar.gz";
    }

    static String documentPath(HexPath.Kind kind, String name) {
        return switch (kind) {
            case NAMES -> NAMES;
            case VERSIONS -> VERSIONS;
            case PACKAGE -> HexPath.packagePath(name);
            default -> throw new IllegalArgumentException(kind + " is not a registry document");
        };
    }

    static StorageService.ComponentCoordinates coordinates(String name, String version) {
        return new StorageService.ComponentCoordinates("", name, version);
    }

    /** Writes of one repository are serialised: every write rewrites the shared names and versions documents. */
    private <T> T locked(Repository repo, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(repo.getId() == null ? new UUID(0, 0) : repo.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    static String describe(Repository repo) {
        return repo.getType().name().toLowerCase(Locale.ROOT);
    }

    static HexError notHosted(Repository repo, Optional<String> hostedMember) {
        String hint = hostedMember.map(h -> "; publish to the hosted repository '" + h + "' instead")
                .orElse("; publish to a hosted repository instead");
        return new HexError(HttpStatus.METHOD_NOT_ALLOWED, "'" + repo.getName() + "' is a " + describe(repo)
                + " repository and does not accept writes" + hint);
    }
}
