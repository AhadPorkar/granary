package dev.granary.format.pub;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hosted repositories: publishing, deleting, retracting and discontinuing packages.
 *
 * <p>Each package has one state document at {@code api/packages/{name}} (its listing without archive URLs,
 * which are added per request so the document stays valid when the server's URL changes) and one archive
 * per version at {@code packages/{name}/versions/{version}.tar.gz}, linked to the component
 * {@code (name, version)}. Changes to a package are serialised, so concurrent publishes of two versions
 * never lose one of them.
 */
@Component
public class PubHosted {

    private static final Logger log = LoggerFactory.getLogger(PubHosted.class);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String JSON = "application/json";
    private static final String ARCHIVE_TYPE = "application/gzip";
    private static final int STRIPES = 64;

    private final StorageService storage;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Object[] locks = new Object[STRIPES];

    @Autowired
    public PubHosted(StorageService storage, ObjectMapper mapper) {
        this(storage, mapper, Clock.systemUTC());
    }

    PubHosted(StorageService storage, ObjectMapper mapper, Clock clock) {
        this.storage = storage;
        this.mapper = mapper;
        this.clock = clock;
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    /** What a successful publish stored. */
    public record Published(String name, String version, boolean replaced) {
    }

    // --- reads -------------------------------------------------------------------------------------

    /**
     * The package's listing (unrendered), restricted to versions whose archive is actually stored, so a version
     * whose component was deleted in the UI disappears from the listing too. Empty when nothing is left.
     */
    public Optional<ObjectNode> listing(Repository repo, String name) {
        Optional<ObjectNode> stored = stored(repo, name);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Set<String> archives = new HashSet<>();
        for (Asset asset : storage.list(repo, PubPath.archivePrefix(name))) {
            archives.add(asset.getPath());
        }
        ObjectNode doc = stored.get().deepCopy();
        ArrayNode versions = doc.putArray(PubListing.VERSIONS);
        for (PubListing.Entry entry : PubListing.entries(stored.get())) {
            if (archives.contains(PubPath.archivePath(name, entry.version().text()))) {
                versions.add(entry.node());
            }
        }
        return versions.isEmpty() ? Optional.empty() : Optional.of(doc);
    }

    public Optional<StoredContent> archive(Repository repo, String name, String version) {
        Optional<StoredContent> direct = storage.find(repo, PubPath.archivePath(name, version));
        if (direct.isPresent()) {
            return direct;
        }
        // another spelling of the same version (1.0.0+01 for 1.0.0+1)
        return stored(repo, name)
                .flatMap(doc -> PubListing.find(doc, version))
                .filter(e -> !e.version().text().equals(version))
                .flatMap(e -> storage.find(repo, PubPath.archivePath(name, e.version().text())));
    }

    // --- publish -----------------------------------------------------------------------------------

    /**
     * Validates and stores an uploaded archive.
     *
     * @throws PubError 400 for an archive or pubspec that cannot be published, 409 for an existing version
     *                  when the repository does not allow redeploying
     */
    public Published publish(Repository repo, Path archive) throws IOException {
        long size = Files.size(archive);
        long max = PubSettings.maxArchiveBytes(repo);
        if (size == 0) {
            throw PubError.rejected("The uploaded package archive is empty");
        }
        if (size > max) {
            throw PubError.rejected("The package archive is " + mib(size) + " MiB; repository '" + repo.getName()
                    + "' accepts at most " + mib(max) + " MiB");
        }
        PubArchive contents;
        try (InputStream in = Files.newInputStream(archive)) {
            contents = PubArchive.read(in);
        } catch (PubArchive.InvalidArchiveException e) {
            throw PubError.rejected(e.getMessage());
        }
        Pubspec pubspec;
        try {
            pubspec = Pubspec.parse(contents.pubspec());
        } catch (Pubspec.InvalidPubspecException e) {
            throw PubError.rejected(e.getMessage());
        }
        String sha256;
        try (InputStream in = Files.newInputStream(archive)) {
            sha256 = Checksums.hex("SHA-256", in);
        }
        String name = pubspec.name();
        String version = pubspec.version().text();
        synchronized (lock(repo, name)) {
            ObjectNode doc = stored(repo, name).orElseGet(() -> newDocument(name));
            Optional<PubListing.Entry> existing = PubListing.entries(doc).stream()
                    .filter(e -> e.version().sameAs(pubspec.version()))
                    .findFirst();
            boolean live = existing.isPresent()
                    && storage.exists(repo, PubPath.archivePath(name, existing.get().version().text()));
            if (live && !repo.isAllowRedeploy()) {
                throw PubError.exists("Version " + existing.get().version().text() + " of package " + name
                        + " already exists in repository '" + repo.getName() + "'. Publish a new version instead.");
            }
            if (existing.isPresent()) {
                removeEntry(doc, existing.get());
                String old = existing.get().version().text();
                if (!old.equals(version)) {
                    storage.delete(repo, PubPath.archivePath(name, old));
                }
            }
            try (InputStream in = Files.newInputStream(archive)) {
                storage.store(repo, PubPath.archivePath(name, version), in, ARCHIVE_TYPE, Asset.Origin.UPLOADED, false,
                        new StorageService.ComponentCoordinates("", name, version));
            }
            ObjectNode entry = NODES.objectNode();
            entry.put(PubListing.VERSION, version);
            entry.set(PubListing.PUBSPEC, pubspec.json());
            entry.put(PubListing.ARCHIVE_SHA256, sha256);
            entry.put(PubListing.PUBLISHED, PubListing.timestamp(clock.instant()));
            versions(doc).add(entry);
            save(repo, name, doc);
            log.info("published {} {} to {}", name, version, repo.getName());
            return new Published(name, version, existing.isPresent());
        }
    }

    // --- management --------------------------------------------------------------------------------

    /** Removes one version and its archive; false when the package has no such version. */
    public boolean deleteVersion(Repository repo, String name, String version) {
        synchronized (lock(repo, name)) {
            Optional<ObjectNode> doc = stored(repo, name);
            Optional<PubListing.Entry> entry = doc.flatMap(d -> PubListing.find(d, version));
            if (entry.isEmpty()) {
                return storage.delete(repo, PubPath.archivePath(name, version));
            }
            removeEntry(doc.get(), entry.get());
            storage.delete(repo, PubPath.archivePath(name, entry.get().version().text()));
            if (PubListing.entries(doc.get()).isEmpty()) {
                storage.delete(repo, PubPath.listingPath(name));
            } else {
                save(repo, name, doc.get());
            }
            log.info("deleted {} {} from {}", name, entry.get().version().text(), repo.getName());
            return true;
        }
    }

    /** Removes a package with every version; answers how many versions went. */
    public int deletePackage(Repository repo, String name) {
        synchronized (lock(repo, name)) {
            int versions = stored(repo, name).map(d -> PubListing.entries(d).size()).orElse(0);
            boolean any = storage.delete(repo, PubPath.listingPath(name));
            int archives = 0;
            for (Asset asset : storage.list(repo, PubPath.archivePrefix(name))) {
                if (storage.delete(repo, asset.getPath())) {
                    archives++;
                }
            }
            if (any || archives > 0) {
                log.info("deleted package {} ({} versions) from {}", name, Math.max(versions, archives), repo.getName());
            }
            return any || archives > 0 ? Math.max(versions, archives) : 0;
        }
    }

    /** Marks a version retracted (the solver avoids it unless nothing else fits) or restores it. */
    public void setRetracted(Repository repo, String name, String version, boolean retracted) {
        synchronized (lock(repo, name)) {
            ObjectNode doc = requireDocument(repo, name);
            PubListing.Entry entry = PubListing.find(doc, version).orElseThrow(() ->
                    PubError.notFound("Package " + name + " has no version " + version));
            if (retracted) {
                entry.node().put(PubListing.RETRACTED, true);
            } else {
                entry.node().remove(PubListing.RETRACTED);
            }
            save(repo, name, doc);
        }
    }

    /** Marks a package discontinued, optionally naming its replacement, or lifts the mark. */
    public void setDiscontinued(Repository repo, String name, boolean discontinued, String replacedBy) {
        if (replacedBy != null) {
            if (!PubNames.isValid(replacedBy)) {
                throw PubError.invalid("replacedBy must be a package name");
            }
            if (replacedBy.equals(name)) {
                throw PubError.invalid("A package cannot be replaced by itself");
            }
        }
        synchronized (lock(repo, name)) {
            ObjectNode doc = requireDocument(repo, name);
            if (discontinued) {
                doc.put(PubListing.IS_DISCONTINUED, true);
                if (replacedBy != null) {
                    doc.put(PubListing.REPLACED_BY, replacedBy);
                } else {
                    doc.remove(PubListing.REPLACED_BY);
                }
            } else {
                doc.remove(PubListing.IS_DISCONTINUED);
                doc.remove(PubListing.REPLACED_BY);
            }
            save(repo, name, doc);
        }
    }

    // --- storage -----------------------------------------------------------------------------------

    /** The stored state document, as stored. */
    Optional<ObjectNode> stored(Repository repo, String name) {
        Optional<StoredContent> content = storage.find(repo, PubPath.listingPath(name));
        if (content.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(content.get().readAllBytes());
            if (node != null && node.isObject()) {
                return Optional.of((ObjectNode) node);
            }
            log.warn("stored listing {} in {} is not a JSON object", name, repo.getName());
        } catch (IOException | JacksonException e) {
            log.warn("stored listing {} in {} is unreadable: {}", name, repo.getName(), e.getMessage());
        }
        return Optional.empty();
    }

    private ObjectNode requireDocument(Repository repo, String name) {
        return stored(repo, name).filter(d -> !PubListing.entries(d).isEmpty())
                .orElseThrow(() -> PubError.notFound("Package " + name + " not found in repository '" + repo.getName() + "'"));
    }

    private void save(Repository repo, String name, ObjectNode doc) {
        byte[] bytes = mapper.writeValueAsBytes(doc);
        storage.store(repo, PubPath.listingPath(name), new ByteArrayInputStream(bytes), JSON, Asset.Origin.UPLOADED, true);
    }

    private static ObjectNode newDocument(String name) {
        ObjectNode doc = NODES.objectNode();
        doc.put(PubListing.NAME, name);
        doc.putArray(PubListing.VERSIONS);
        return doc;
    }

    private static ArrayNode versions(ObjectNode doc) {
        JsonNode versions = doc.get(PubListing.VERSIONS);
        return versions != null && versions.isArray() ? (ArrayNode) versions : doc.putArray(PubListing.VERSIONS);
    }

    private static void removeEntry(ObjectNode doc, PubListing.Entry entry) {
        ArrayNode versions = versions(doc);
        for (int i = versions.size() - 1; i >= 0; i--) {
            if (versions.get(i) == entry.node()) {
                versions.remove(i);
            }
        }
    }

    private Object lock(Repository repo, String name) {
        return locks[Math.floorMod((repo.getId() + "/" + name).hashCode(), STRIPES)];
    }

    private static String mib(long bytes) {
        double value = bytes / (1024.0 * 1024.0);
        return value >= 10 ? Long.toString(Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
    }

    /** For tests. */
    List<PubListing.Entry> entries(Repository repo, String name) {
        return stored(repo, name).map(PubListing::entries).orElse(List.of());
    }
}
