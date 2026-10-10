package dev.granary.format.helm;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * A hosted chart repository. Each chart version is stored as
 *
 * <ul>
 *   <li>{@code charts/<name>-<version>.tgz}: the archive as uploaded (its sha256 is the index digest),</li>
 *   <li>{@code charts/<name>-<version>.tgz.prov}: the provenance file, when one was uploaded,</li>
 *   <li>{@code entries/<name>/<version>.json}: the index entry (validated Chart.yaml fields, digest, created),</li>
 * </ul>
 *
 * all linked to the chart's component. {@code index.yaml} is generated from the entries on request, so it cannot
 * get out of sync with the archives: an entry without its archive is not listed, and deleting a component in
 * the UI removes the chart from the index. The entry is written last, which makes it the publication point of
 * an upload.
 */
@Component
public class HelmHosted {

    static final String ENTRIES = "entries/";
    static final String ENTRY_SUFFIX = ".json";
    static final String ARCHIVE_CONTENT_TYPE = "application/gzip";
    static final String ARTIFACTHUB_CONTENT_TYPE = "application/x-yaml";

    private static final Logger log = LoggerFactory.getLogger(HelmHosted.class);
    /** Writes to one archive file name are serialised so two uploads cannot both pass the "already exists" check. */
    private static final int LOCK_STRIPES = 64;

    private final StorageService storage;
    private final ObjectMapper json;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public HelmHosted(StorageService storage, ObjectMapper json) {
        this.storage = storage;
        this.json = json;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** The outcome of an upload: the index entry, whether an existing version was replaced, whether a .prov came with it. */
    public record Stored(ChartEntry entry, boolean replaced, boolean provenance) {
    }

    /** What a hosted index is computed from: the published entries and a digest identifying them. */
    record Listing(String key, List<Asset> entries) {
    }

    static String entryPath(String name, String version) {
        return ENTRIES + name + "/" + version + ENTRY_SUFFIX;
    }

    /**
     * The lock of an archive file name. Two chart versions can share one ({@code a-1} 2.0.0 and {@code a} 1-2.0.0 are
     * both {@code a-1-2.0.0.tgz}), so locking by file name rather than by chart name makes the ownership check and
     * the write of such uploads atomic.
     */
    private ReentrantLock lockFor(Repository repo, String archiveName) {
        return locks[Math.floorMod((repo.getId() + "/" + archiveName).hashCode(), LOCK_STRIPES)];
    }

    /**
     * Another published chart version whose archive has the same file name as {@code name}-{@code version}: the
     * name and version are split at a different hyphen ({@code a-1} 2.0.0 versus {@code a} 1-2.0.0), and both are
     * valid by Helm's rules.
     */
    Optional<String[]> archiveOwner(Repository repo, String name, String version) {
        String base = name + "-" + version;
        for (int i = base.indexOf('-'); i > 0; i = base.indexOf('-', i + 1)) {
            if (i == name.length()) {
                continue; // that split is this chart version itself
            }
            String otherName = base.substring(0, i);
            String otherVersion = base.substring(i + 1);
            if (HelmPath.isValidName(otherName) && HelmVersion.isValid(otherVersion)
                    && storage.exists(repo, entryPath(otherName, otherVersion))) {
                return Optional.of(new String[] {otherName, otherVersion});
            }
        }
        return Optional.empty();
    }

    private void requireOwnArchiveName(Repository repo, String name, String version) {
        Optional<String[]> owner = archiveOwner(repo, name, version);
        if (owner.isPresent()) {
            throw HelmError.conflict("chart " + name + " " + version + " would be stored as "
                    + HelmPath.archiveName(name, version) + ", which belongs to chart " + owner.get()[0] + " "
                    + owner.get()[1] + " in '" + repo.getName() + "'");
        }
    }

    // --- uploads ---------------------------------------------------------------------------------

    /**
     * Stores an uploaded chart, and its provenance file when given. Refuses invalid charts, provenance files that
     * belong to another archive, and existing versions unless the repository allows redeployment.
     */
    public Stored storeChart(Repository repo, Path archive, Path prov) throws IOException {
        ChartArchive.Contents contents;
        try (InputStream in = Files.newInputStream(archive)) {
            contents = ChartArchive.read(in);
        }
        Map<String, Object> metadata = ChartMetadata.of(contents);
        String name = (String) metadata.get("name");
        String version = (String) metadata.get("version");
        if (!HelmPath.isSafeFilename(HelmPath.archiveName(name, version))) {
            throw HelmError.badRequest("chart " + name + " " + version + " has no valid file name");
        }
        String digest = sha256(archive);
        Provenance provenance = null;
        if (prov != null) {
            provenance = Provenance.parse(Files.readAllBytes(prov));
            requireMatch(provenance, name, version, digest);
        }
        StorageService.ComponentCoordinates coordinates = new StorageService.ComponentCoordinates("", name, version);
        String archivePath = HelmPath.archivePath(name, version);
        String provPath = archivePath + HelmPath.PROV_SUFFIX;
        ReentrantLock lock = lockFor(repo, HelmPath.archiveName(name, version));
        lock.lock();
        try {
            requireOwnArchiveName(repo, name, version);
            boolean published = isPublished(repo, name, version);
            if (published && !repo.isAllowRedeploy()) {
                throw HelmError.conflict("chart " + name + " " + version + " already exists in '" + repo.getName()
                        + "' and redeployment is disabled");
            }
            try (InputStream in = Files.newInputStream(archive)) {
                storage.store(repo, archivePath, in, ARCHIVE_CONTENT_TYPE, Asset.Origin.UPLOADED, false, coordinates);
            }
            if (provenance != null) {
                try (InputStream in = Files.newInputStream(prov)) {
                    storage.store(repo, provPath, in, Provenance.CONTENT_TYPE, Asset.Origin.UPLOADED, false, coordinates);
                }
            } else {
                dropStaleProvenance(repo, provPath, HelmPath.archiveName(name, version), digest);
            }
            ChartEntry entry = ChartEntry.of(metadata)
                    .with(ChartEntry.CREATED, DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                    .with(ChartEntry.DIGEST, digest);
            byte[] document = json.writeValueAsBytes(entry.fields());
            storage.store(repo, entryPath(name, version), new ByteArrayInputStream(document), "application/json",
                    Asset.Origin.UPLOADED, true, coordinates);
            log.info("stored chart {} {} in {}{}", name, version, repo.getName(), published ? " (replaced)" : "");
            return new Stored(entry, published, provenance != null);
        } finally {
            lock.unlock();
        }
    }

    /** Stores a provenance file for a chart version (uploaded before or after the chart itself). */
    public Provenance storeProvenance(Repository repo, Path prov) throws IOException {
        Provenance provenance = Provenance.parse(Files.readAllBytes(prov));
        String archiveName = HelmPath.archiveName(provenance.name(), provenance.version());
        if (!HelmPath.isSafeFilename(archiveName)) {
            throw HelmError.badRequest("the provenance file names no valid chart");
        }
        String archivePath = HelmPath.CHARTS + archiveName;
        String provPath = archivePath + HelmPath.PROV_SUFFIX;
        ReentrantLock lock = lockFor(repo, archiveName);
        lock.lock();
        try {
            requireOwnArchiveName(repo, provenance.name(), provenance.version());
            Optional<StoredContent> archive = storage.find(repo, archivePath);
            if (archive.isPresent()) {
                requireMatch(provenance, provenance.name(), provenance.version(), archive.get().asset().getBlobSha256());
            }
            if (storage.exists(repo, provPath) && !repo.isAllowRedeploy()) {
                throw HelmError.conflict("the provenance file of " + provenance.name() + " " + provenance.version()
                        + " already exists in '" + repo.getName() + "' and redeployment is disabled");
            }
            try (InputStream in = Files.newInputStream(prov)) {
                storage.store(repo, provPath, in, Provenance.CONTENT_TYPE, Asset.Origin.UPLOADED, false,
                        new StorageService.ComponentCoordinates("", provenance.name(), provenance.version()));
            }
            return provenance;
        } finally {
            lock.unlock();
        }
    }

    private static void requireMatch(Provenance provenance, String name, String version, String digest) {
        if (!provenance.name().equals(name) || !provenance.version().equals(version)) {
            throw HelmError.badRequest("the provenance file is for " + provenance.name() + " " + provenance.version()
                    + ", not for " + name + " " + version);
        }
        Optional<String> stated = provenance.sha256Of(HelmPath.archiveName(name, version));
        if (stated.isPresent() && !stated.get().equalsIgnoreCase(digest)) {
            throw HelmError.badRequest("the provenance file does not match the chart archive (sha256 " + stated.get()
                    + " expected, the archive has " + digest + ")");
        }
    }

    /** A redeployed archive invalidates the provenance file of the previous one. */
    private void dropStaleProvenance(Repository repo, String provPath, String archiveName, String digest) {
        Optional<StoredContent> existing = storage.find(repo, provPath);
        if (existing.isEmpty()) {
            return;
        }
        try {
            Optional<String> stated = Provenance.parse(existing.get().readAllBytes()).sha256Of(archiveName);
            if (stated.isPresent() && stated.get().equalsIgnoreCase(digest)) {
                return;
            }
        } catch (IOException | RuntimeException e) {
            log.debug("existing provenance {} in {} is unreadable: {}", provPath, repo.getName(), e.getMessage());
        }
        storage.delete(repo, provPath);
    }

    public void storeArtifactHubMetadata(Repository repo, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            HelmYaml.loadMap(bytes, HelmYaml.SMALL_DOCUMENT_LIMIT);
        } catch (HelmYaml.InvalidYamlException e) {
            throw HelmError.badRequest(HelmPath.ARTIFACTHUB_FILE + " is not valid YAML: " + e.getMessage());
        }
        storage.store(repo, HelmPath.ARTIFACTHUB_FILE, new ByteArrayInputStream(bytes), ARTIFACTHUB_CONTENT_TYPE,
                Asset.Origin.UPLOADED, true);
    }

    // --- deletes ---------------------------------------------------------------------------------

    /** Removes a chart version (entry, archive and provenance file); false when there was nothing to remove. */
    public boolean deleteVersion(Repository repo, String name, String version) {
        ReentrantLock lock = lockFor(repo, HelmPath.archiveName(name, version));
        lock.lock();
        try {
            boolean entry = storage.delete(repo, entryPath(name, version));
            if (archiveOwner(repo, name, version).isPresent()) {
                // the archive and provenance file under this name belong to another chart version
                return entry;
            }
            boolean archive = storage.delete(repo, HelmPath.archivePath(name, version));
            boolean prov = storage.delete(repo, HelmPath.archivePath(name, version) + HelmPath.PROV_SUFFIX);
            if (entry || archive || prov) {
                log.info("deleted chart {} {} from {}", name, version, repo.getName());
            }
            return entry || archive || prov;
        } finally {
            lock.unlock();
        }
    }

    /** The chart version whose archive is stored as {@code charts/<filename>}, found through the stored entries. */
    public Optional<String[]> versionOfArchive(Repository repo, String filename) {
        for (Asset asset : storage.list(repo, ENTRIES)) {
            Optional<String[]> nameVersion = parseEntryPath(asset.getPath());
            if (nameVersion.isPresent() && HelmPath.archiveName(nameVersion.get()[0], nameVersion.get()[1]).equals(filename)) {
                return nameVersion;
            }
        }
        return Optional.empty();
    }

    // --- reads -----------------------------------------------------------------------------------

    public Optional<StoredContent> file(Repository repo, String path) {
        return storage.find(repo, path);
    }

    /** The archive (or provenance file) of a chart version. */
    public Optional<StoredContent> file(Repository repo, String name, String version, boolean provenance) {
        if (!HelmPath.isSafeFilename(HelmPath.archiveName(name, version))) {
            return Optional.empty();
        }
        if (!storage.exists(repo, entryPath(name, version)) && archiveOwner(repo, name, version).isPresent()) {
            return Optional.empty(); // the file under this name is another chart version's
        }
        String path = HelmPath.archivePath(name, version) + (provenance ? HelmPath.PROV_SUFFIX : "");
        return storage.find(repo, path);
    }

    public boolean isPublished(Repository repo, String name, String version) {
        return storage.exists(repo, entryPath(name, version)) && storage.exists(repo, HelmPath.archivePath(name, version));
    }

    /** The published entries and their identity; cheap (two listings, no blob reads). */
    Listing listing(Repository repo) {
        Set<String> archives = new HashSet<>();
        for (Asset asset : storage.list(repo, HelmPath.CHARTS)) {
            archives.add(asset.getPath());
        }
        List<Asset> published = new ArrayList<>();
        MessageDigest digest = sha256();
        digest.update(String.valueOf(repo.getId()).getBytes(StandardCharsets.UTF_8));
        for (Asset asset : storage.list(repo, ENTRIES)) {
            Optional<String[]> nameVersion = parseEntryPath(asset.getPath());
            if (nameVersion.isEmpty() || !archives.contains(HelmPath.archivePath(nameVersion.get()[0], nameVersion.get()[1]))) {
                continue;
            }
            published.add(asset);
            digest.update((asset.getPath() + "\0" + asset.getBlobSha256() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return new Listing("hosted:" + HexFormat.of().formatHex(digest.digest()), published);
    }

    /** The index of the listed entries, each pointing at {@code charts/<name>-<version>.tgz}. */
    @SuppressWarnings("unchecked")
    HelmIndex index(Repository repo, Listing listing) {
        List<ChartEntry> entries = new ArrayList<>(listing.entries().size());
        for (Asset asset : listing.entries()) {
            String[] nameVersion = parseEntryPath(asset.getPath()).orElseThrow();
            try {
                byte[] bytes = new StoredContent(asset, storage.blobStoreOf(repo)).readAllBytes();
                Map<String, Object> fields = json.readValue(bytes, LinkedHashMap.class);
                entries.add(ChartEntry.of(fields)
                        .withName(nameVersion[0])
                        .with("version", nameVersion[1])
                        .withUrls(List.of(HelmPath.archivePath(nameVersion[0], nameVersion[1]))));
            } catch (IOException | JacksonException | ClassCastException e) {
                log.warn("index entry {} in {} is unreadable and left out: {}", asset.getPath(), repo.getName(), e.getMessage());
            }
        }
        return HelmIndex.of(entries);
    }

    /** {@code entries/<name>/<version>.json} → name and version. */
    static Optional<String[]> parseEntryPath(String path) {
        if (!path.startsWith(ENTRIES) || !path.endsWith(ENTRY_SUFFIX)) {
            return Optional.empty();
        }
        String rest = path.substring(ENTRIES.length(), path.length() - ENTRY_SUFFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash != rest.lastIndexOf('/') || slash == rest.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new String[] {rest.substring(0, slash), rest.substring(slash + 1)});
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
