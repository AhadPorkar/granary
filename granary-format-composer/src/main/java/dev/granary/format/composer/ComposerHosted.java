package dev.granary.format.composer;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hosted repositories: uploads, deletes and the metadata generated from them.
 *
 * <p>Each package has up to two stored documents, {@code p2/{vendor}/{name}.json} for tagged releases and
 * {@code p2/{vendor}/{name}~dev.json} for branches, kept in the minified wire format with dist URLs relative to
 * the repository ({@code dist/acme/lib/1.0.0/<sha1>.zip}) so that they survive a change of the server's base
 * URL; they are made absolute for whoever asks. Writes to one package are serialised.
 */
@Component
public class ComposerHosted {

    private static final Logger log = LoggerFactory.getLogger(ComposerHosted.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final String JSON = "application/json";
    static final String ZIP = "application/zip";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /** composer.json keys that only mean something in a root project, or that the repository sets itself. */
    private static final Set<String> NOT_COPIED = Set.of(
            "name", "version", "version_normalized", "dist", "source", "installation-source", "notification-url",
            "transport-options", "repositories", "config", "minimum-stability", "prefer-stable");
    private static final List<String> LINK_KEYS = List.of("require", "require-dev", "conflict", "provide", "replace", "suggest");
    /**
     * Constraint syntax that {@code VersionParser::normalize} strips silently: an inline alias ({@code 1.0.0 as 2.0.0},
     * the only way whitespace survives the trim) and a stability flag ({@code 1.0.0@beta}). Published as a version they
     * would show up verbatim in the metadata while resolving as something else.
     */
    private static final Pattern NOT_A_RELEASE = Pattern.compile("\\s|@(?:stable|rc|beta|alpha|dev)$", Pattern.CASE_INSENSITIVE);

    private final StorageService storage;
    private final ObjectMapper mapper;
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public ComposerHosted(StorageService storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** An archive received for publication. */
    public record Upload(Path file, String sha1, String name, String version) {
    }

    /** What an upload stored. */
    public record Published(String name, String version, String normalizedVersion, String distPath, boolean replaced) {
    }

    // --- reads ---------------------------------------------------------------------------------------

    /**
     * The stored document of a package, limited to versions whose archive still exists (an archive deleted from the
     * web UI takes its version with it; the stored document is repaired on the spot).
     */
    public Optional<P2Document> document(Repository repo, String name, boolean dev) {
        Optional<P2Document> stored = stored(repo, name, dev);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Set<String> archives = archivePaths(repo, name);
        List<ObjectNode> present = new ArrayList<>();
        for (ObjectNode entry : stored.get().versions()) {
            String relative = relativeDist(entry);
            if (relative == null || archives.contains(relative)) {
                present.add(entry);
            }
        }
        if (present.size() == stored.get().versions().size()) {
            return stored.get().isEmpty() ? Optional.empty() : stored;
        }
        return locked(repo, name, () -> {
            // re-read under the lock; an upload may have happened in between
            Optional<P2Document> current = stored(repo, name, dev);
            if (current.isEmpty()) {
                return Optional.<P2Document>empty();
            }
            Set<String> now = archivePaths(repo, name);
            List<ObjectNode> kept = current.get().versions().stream()
                    .filter(e -> relativeDist(e) == null || now.contains(relativeDist(e)))
                    .toList();
            log.info("{}: dropping {} version(s) of {} whose archive is gone", repo.getName(),
                    current.get().versions().size() - kept.size(), name);
            return write(repo, current.get().withVersions(kept), dev);
        });
    }

    /** The document exactly as stored, without checking archives. */
    Optional<P2Document> stored(Repository repo, String name, boolean dev) {
        return storage.find(repo, ComposerPath.metadataPath(name, dev)).flatMap(content -> parse(content, name));
    }

    private Optional<P2Document> parse(StoredContent content, String name) {
        try {
            Optional<P2Document> doc = P2Document.parse(mapper, content.readAllBytes(), name, content.asset().getUpdatedAt());
            if (doc.isEmpty()) {
                log.warn("stored metadata {} is not a JSON object", content.asset().getPath());
            }
            return doc;
        } catch (IOException e) {
            log.warn("stored metadata {} is unreadable: {}", content.asset().getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    private Set<String> archivePaths(Repository repo, String name) {
        Set<String> paths = new HashSet<>();
        for (Asset asset : storage.list(repo, ComposerPath.DIST + name + "/")) {
            paths.add(asset.getPath());
        }
        return paths;
    }

    /** The repository-relative dist path an entry points at, or {@code null} for an absolute or missing URL. */
    static String relativeDist(ObjectNode entry) {
        String url = P2Document.string(entry.get(P2Document.DIST), "url");
        if (url == null || url.isEmpty() || ComposerDist.isAbsolute(url)) {
            return null;
        }
        return url;
    }

    /** Every package with stored metadata, sorted. */
    public List<String> packageNames(Repository repo) {
        TreeSet<String> names = new TreeSet<>();
        for (Asset asset : storage.list(repo, ComposerPath.P2)) {
            String path = asset.getPath();
            if (!path.endsWith(".json")) {
                continue;
            }
            String file = path.substring(ComposerPath.P2.length(), path.length() - ".json".length());
            if (file.endsWith(ComposerPath.DEV_SUFFIX)) {
                file = file.substring(0, file.length() - ComposerPath.DEV_SUFFIX.length());
            }
            if (ComposerNames.isValid(file)) {
                names.add(file.toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(names);
    }

    /** How often the archives of a package were downloaded, summed over its versions. */
    public long downloads(Repository repo, String name) {
        long total = 0;
        for (Asset asset : storage.list(repo, ComposerPath.DIST + name + "/")) {
            total += asset.getDownloadCount();
        }
        return total;
    }

    // --- writes --------------------------------------------------------------------------------------

    /**
     * Publishes an archive: validates its composer.json against the name and version the URL gave (either may be
     * absent), stores the zip under {@code dist/…/{sha1}.zip} and adds the version to the package's metadata.
     */
    public Published publish(Repository repo, Upload upload) {
        ObjectNode composerJson = ComposerZip.readComposerJson(upload.file(), mapper);
        String name = packageName(composerJson, upload.name());
        String prettyVersion = prettyVersion(composerJson, upload.version());
        if (NOT_A_RELEASE.matcher(prettyVersion).find()) {
            throw ComposerError.invalidPackage("'" + prettyVersion + "' is not a version a package can be published as: "
                    + "inline aliases (1.0.0 as 2.0.0), stability flags (@beta) and whitespace belong in constraints");
        }
        String normalized;
        try {
            normalized = ComposerVersions.normalize(prettyVersion);
        } catch (IllegalArgumentException e) {
            throw ComposerError.invalidPackage("'" + prettyVersion + "' is not a valid Composer version: " + e.getMessage());
        }
        if (!ComposerDist.isPathSafe(prettyVersion)) {
            throw ComposerError.invalidPackage("Version '" + prettyVersion + "' contains characters that cannot be used in a URL path");
        }
        validateMetadata(composerJson);
        boolean dev = ComposerVersions.isDev(normalized);
        String distPath = ComposerPath.distPath(name, prettyVersion, upload.sha1() + ".zip");
        ObjectNode entry = entry(composerJson, name, prettyVersion, normalized, upload.sha1(), distPath, Instant.now());
        return locked(repo, name, () -> {
            P2Document doc = stored(repo, name, dev).orElseGet(() -> P2Document.empty(name));
            Optional<ObjectNode> existing = doc.versions().stream()
                    .filter(e -> normalized.equals(P2Document.normalized(e)))
                    .findFirst();
            if (existing.isPresent() && !repo.isAllowRedeploy()) {
                throw ComposerError.conflict("Version " + P2Document.version(existing.get()) + " of " + name
                        + " already exists in " + repo.getName() + " and redeployment is disabled");
            }
            StorageService.ComponentCoordinates coordinates = new StorageService.ComponentCoordinates(
                    ComposerNames.vendor(name), ComposerNames.project(name), prettyVersion);
            try (InputStream in = Files.newInputStream(upload.file())) {
                storage.store(repo, distPath, in, ZIP, Asset.Origin.UPLOADED, false, coordinates);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read upload " + upload.file(), e);
            }
            List<ObjectNode> versions = new ArrayList<>();
            for (ObjectNode e : doc.versions()) {
                if (!normalized.equals(P2Document.normalized(e))) {
                    versions.add(e);
                }
            }
            versions.add(entry);
            write(repo, doc.withVersions(versions), dev);
            if (existing.isPresent()) {
                String old = relativeDist(existing.get());
                if (old != null && !old.equals(distPath)) {
                    storage.delete(repo, old);
                }
            }
            log.info("stored {} {} in {}", name, prettyVersion, repo.getName());
            return new Published(name, prettyVersion, normalized, distPath, existing.isPresent());
        });
    }

    /** Removes one version and its archive; false when the package has no such version. */
    public boolean deleteVersion(Repository repo, String name, String version) {
        return locked(repo, name, () -> {
            boolean dev = ComposerVersions.isDev(version);
            for (boolean file : new boolean[] {dev, !dev}) {
                Optional<P2Document> doc = stored(repo, name, file);
                if (doc.isEmpty()) {
                    continue;
                }
                Optional<ObjectNode> entry = doc.get().find(version);
                if (entry.isEmpty()) {
                    continue;
                }
                List<ObjectNode> remaining = doc.get().versions().stream().filter(e -> e != entry.get()).toList();
                write(repo, doc.get().withVersions(remaining), file);
                deleteArchives(repo, name, entry.get());
                log.info("deleted {} {} from {}", name, P2Document.version(entry.get()), repo.getName());
                return true;
            }
            return false;
        });
    }

    /** Removes every version of a package; returns how many files were deleted. */
    public int deletePackage(Repository repo, String name) {
        return locked(repo, name, () -> {
            int deleted = 0;
            for (boolean dev : new boolean[] {false, true}) {
                if (storage.delete(repo, ComposerPath.metadataPath(name, dev))) {
                    deleted++;
                }
            }
            for (Asset asset : storage.list(repo, ComposerPath.DIST + name + "/")) {
                if (storage.delete(repo, asset.getPath())) {
                    deleted++;
                }
            }
            if (deleted > 0) {
                log.info("deleted package {} ({} files) from {}", name, deleted, repo.getName());
            }
            return deleted;
        });
    }

    private void deleteArchives(Repository repo, String name, ObjectNode entry) {
        String relative = relativeDist(entry);
        if (relative != null) {
            storage.delete(repo, relative);
        }
        String version = P2Document.version(entry);
        if (version == null) {
            return;
        }
        String prefix = ComposerPath.DIST + name + "/" + version + "/";
        for (Asset asset : storage.list(repo, prefix)) {
            if (asset.getPath().indexOf('/', prefix.length()) < 0) {
                storage.delete(repo, asset.getPath());
            }
        }
    }

    /** Stores the document sorted newest first, or deletes it when no version is left. */
    private Optional<P2Document> write(Repository repo, P2Document doc, boolean dev) {
        String path = ComposerPath.metadataPath(doc.name(), dev);
        if (doc.isEmpty()) {
            storage.delete(repo, path);
            return Optional.empty();
        }
        List<ObjectNode> sorted = new ArrayList<>(doc.versions());
        sorted.sort((a, b) -> ComposerVersions.NEWEST_FIRST.compare(
                orEmpty(P2Document.normalized(a)), orEmpty(P2Document.normalized(b))));
        P2Document toStore = doc.withVersions(sorted);
        byte[] bytes = mapper.writeValueAsBytes(toStore.toJson());
        StoredContent stored = storage.store(repo, path, new ByteArrayInputStream(bytes), JSON, Asset.Origin.UPLOADED, true);
        return Optional.of(toStore.withLastModified(stored.asset().getUpdatedAt()));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private <T> T locked(Repository repo, String name, Supplier<T> work) {
        ReentrantLock lock = locks[Math.floorMod((repo.getId() + "|" + name).hashCode(), locks.length)];
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    // --- composer.json -------------------------------------------------------------------------------

    /** The package name: composer.json's, which must agree with the URL's when both are given. */
    static String packageName(ObjectNode composerJson, String fromUrl) {
        JsonNode raw = composerJson.get("name");
        String fromJson = null;
        if (raw != null && !raw.isNull()) {
            if (!raw.isString() || !ComposerNames.isValid(raw.stringValue())) {
                throw ComposerError.invalidPackage("composer.json has an invalid name '" + raw.asString()
                        + "'; a name is vendor/package in lower case, e.g. acme/library");
            }
            fromJson = raw.stringValue().toLowerCase(Locale.ROOT);
        }
        if (fromJson == null && fromUrl == null) {
            throw ComposerError.invalidPackage("composer.json in the archive has no name; add one (\"name\": \"vendor/package\")");
        }
        if (fromJson != null && fromUrl != null && !fromJson.equals(fromUrl)) {
            throw ComposerError.invalidPackage("The archive contains " + fromJson + " but was uploaded as " + fromUrl);
        }
        return fromJson != null ? fromJson : fromUrl;
    }

    /** The version: the one the URL or {@code ?version=} gave, else composer.json's; both must agree when present. */
    static String prettyVersion(ObjectNode composerJson, String explicit) {
        JsonNode raw = composerJson.get("version");
        String fromJson = raw != null && raw.isString() && !raw.stringValue().isBlank() ? raw.stringValue().trim() : null;
        if (explicit == null || explicit.isBlank()) {
            if (fromJson == null) {
                throw ComposerError.invalidPackage("No version given: add it to the URL (packages/vendor/name/1.0.0), "
                        + "pass ?version=1.0.0 or set \"version\" in composer.json");
            }
            return fromJson;
        }
        String version = explicit.trim();
        if (fromJson != null) {
            Optional<String> a = ComposerVersions.tryNormalize(version);
            Optional<String> b = ComposerVersions.tryNormalize(fromJson);
            if (a.isPresent() && b.isPresent() && !a.get().equals(b.get())) {
                throw ComposerError.invalidPackage("Version " + version + " does not match version " + fromJson
                        + " in composer.json");
            }
        }
        return version;
    }

    /** Rejects metadata Composer's loader would choke on, so one bad upload cannot break every install. */
    static void validateMetadata(ObjectNode json) {
        for (String key : LINK_KEYS) {
            JsonNode links = json.get(key);
            if (links == null || links.isNull()) {
                continue;
            }
            if (links.isArray() && links.isEmpty()) {
                continue; // PHP encodes an empty map as []
            }
            if (!links.isObject()) {
                throw ComposerError.invalidPackage("\"" + key + "\" in composer.json must be an object of package names to constraints");
            }
            for (Map.Entry<String, JsonNode> e : links.properties()) {
                if (!e.getValue().isString()) {
                    throw ComposerError.invalidPackage("\"" + key + "\"." + e.getKey() + " in composer.json must be a string");
                }
            }
        }
        for (String key : List.of("autoload", "autoload-dev")) {
            JsonNode node = json.get(key);
            if (node != null && !node.isNull() && !node.isObject() && !(node.isArray() && node.isEmpty())) {
                throw ComposerError.invalidPackage("\"" + key + "\" in composer.json must be an object");
            }
        }
        requireStringOrStrings(json, "license");
        requireStringOrStrings(json, "bin");
        JsonNode keywords = json.get("keywords");
        if (keywords != null && !keywords.isNull() && !(keywords.isArray() && allStrings(keywords))) {
            throw ComposerError.invalidPackage("\"keywords\" in composer.json must be an array of strings");
        }
        for (String key : List.of("description", "type", "homepage", "time")) {
            JsonNode node = json.get(key);
            if (node != null && !node.isNull() && !node.isString()) {
                throw ComposerError.invalidPackage("\"" + key + "\" in composer.json must be a string");
            }
        }
        JsonNode authors = json.get("authors");
        if (authors != null && !authors.isNull() && !authors.isArray()) {
            throw ComposerError.invalidPackage("\"authors\" in composer.json must be an array");
        }
    }

    private static void requireStringOrStrings(ObjectNode json, String key) {
        JsonNode node = json.get(key);
        if (node == null || node.isNull() || node.isString() || (node.isArray() && allStrings(node))) {
            return;
        }
        throw ComposerError.invalidPackage("\"" + key + "\" in composer.json must be a string or an array of strings");
    }

    private static boolean allStrings(JsonNode array) {
        for (JsonNode item : array) {
            if (!item.isString()) {
                return false;
            }
        }
        return true;
    }

    /** The version entry for the metadata: composer.json's package fields plus name, version, dist and time. */
    static ObjectNode entry(ObjectNode composerJson, String name, String prettyVersion, String normalized, String sha1,
                            String distPath, Instant now) {
        ObjectNode entry = NODES.objectNode();
        entry.put(P2Document.NAME, name);
        entry.put(P2Document.VERSION, prettyVersion);
        entry.put(P2Document.VERSION_NORMALIZED, normalized);
        for (Map.Entry<String, JsonNode> e : composerJson.properties()) {
            if (!NOT_COPIED.contains(e.getKey())) {
                entry.set(e.getKey(), e.getValue().deepCopy());
            }
        }
        ObjectNode dist = NODES.objectNode();
        dist.put("type", "zip");
        dist.put("url", distPath);
        dist.put("reference", sha1);
        dist.put("shasum", sha1);
        entry.set(P2Document.DIST, dist);
        JsonNode time = composerJson.get("time");
        if (time == null || !time.isString() || time.stringValue().isBlank()) {
            entry.put("time", TIME.format(now.atOffset(ZoneOffset.UTC)));
        }
        return entry;
    }
}
