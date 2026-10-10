package dev.granary.format.cargo;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.User;
import dev.granary.core.security.UserService;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Everything a hosted Cargo repository stores and changes: publish, yank and unyank, owners.
 *
 * <p>Per crate there are three kinds of files: the index file ({@code index/<dir>/<name>}, one JSON line per
 * version, served to cargo as is), one {@code .crate} per version ({@code crates/<name>/<name>-<version>.crate},
 * linked to its component) and a small document with what the index has no room for
 * ({@code meta/<name>.json}: description, keywords, owners, who published what and when).
 *
 * <p>A version exists exactly when its index line and its crate file both do: a crate file removed through
 * the component browser takes the version out of the index the next moment, and the line is pruned on the
 * next write. Writes to one crate are serialised, so concurrent publishes of two versions never lose a line.
 */
@Component
public class CargoHosted {

    private static final Logger log = LoggerFactory.getLogger(CargoHosted.class);

    static final String CRATE_CONTENT_TYPE = "application/gzip";
    static final String INDEX_CONTENT_TYPE = "text/plain";
    static final String META_CONTENT_TYPE = "application/json";
    private static final List<String> DESCRIPTIVE = List.of("description", "documentation", "homepage", "repository",
            "license", "license_file", "readme_file", "keywords", "categories", "authors", "rust_version");

    private final StorageService storage;
    private final UserService users;
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public CargoHosted(StorageService storage, UserService users) {
        this.storage = storage;
        this.users = users;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    // --- reads ---------------------------------------------------------------------------------

    /** The lines of the stored index file, whether or not their crate files still exist. */
    List<IndexEntry> storedEntries(Repository repo, String name) {
        return storage.find(repo, CrateNames.indexPath(name)).map(CargoHosted::read).orElseGet(List::of);
    }

    /** The versions the repository really has: index lines whose crate file exists. */
    List<IndexEntry> liveEntries(Repository repo, String name) {
        List<IndexEntry> stored = storedEntries(repo, name);
        if (stored.isEmpty()) {
            return stored;
        }
        Set<String> files = crateFiles(repo, name);
        return stored.stream().filter(e -> files.contains(CrateNames.cratePath(e.name(), e.vers()))).toList();
    }

    Optional<StoredContent> crate(Repository repo, String name, String version) {
        return storage.find(repo, CrateNames.cratePath(name, version));
    }

    Optional<ObjectNode> meta(Repository repo, String name) {
        return storage.find(repo, CrateNames.metaPath(name)).flatMap(content -> {
            try {
                return CargoJson.parseObject(content.readAllBytes());
            } catch (IOException e) {
                log.warn("crate document {} of {} is unreadable: {}", content.asset().getPath(), repo.getName(), e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** The owners recorded for a crate, in the order they were added. */
    List<String> owners(Repository repo, String name) {
        requireCrate(repo, name);
        return meta(repo, name).map(CargoHosted::ownersOf).orElseGet(List::of);
    }

    // --- publish -------------------------------------------------------------------------------

    /**
     * Stores a published version: checks the name and version, refuses an existing version unless the
     * repository allows redeploys, checks the archive, then writes the crate, the index line and the crate
     * document.
     *
     * @return the body cargo expects back ({@code {"warnings":{…}}})
     */
    ObjectNode publish(Repository repo, PublishRequest request, PublishMetadata meta, String publisher) throws IOException {
        String name = meta.name();
        String vers = meta.vers();
        return locked(repo, name, () -> {
            checkSimilarNames(repo, name);
            List<IndexEntry> live = liveEntries(repo, name);
            for (IndexEntry e : live) {
                if (!e.name().equals(name)) {
                    throw CargoError.conflict("crate `" + e.name() + "` already exists; publish it under that exact name "
                            + "instead of `" + name + "`");
                }
            }
            CargoVersion version = CargoVersion.parse(vers).orElseThrow();
            Optional<IndexEntry> existing = live.stream()
                    .filter(e -> CargoVersion.parse(e.vers()).map(v -> v.samePrecedence(version)).orElse(e.vers().equals(vers)))
                    .findFirst();
            if (existing.isPresent() && !repo.isAllowRedeploy()) {
                // a refused write is a 4xx like on crates.io (which answers 400 here); cargo prints the detail with it
                throw CargoError.conflict("crate version `" + existing.get().vers() + "` is already uploaded");
            }
            try (InputStream in = request.openCrate()) {
                CrateArchive.verify(in, name, vers);
            }
            Instant now = Instant.now();
            try (InputStream in = request.openCrate()) {
                storage.store(repo, CrateNames.cratePath(name, vers), in, CRATE_CONTENT_TYPE, Asset.Origin.UPLOADED, false,
                        new StorageService.ComponentCoordinates("", name, vers));
            }
            List<IndexEntry> lines = new ArrayList<>();
            for (IndexEntry e : live) {
                if (existing.isPresent() && e == existing.get()) {
                    if (!e.vers().equals(vers)) {
                        storage.delete(repo, CrateNames.cratePath(e.name(), e.vers()));
                    }
                    continue;
                }
                lines.add(e);
            }
            lines.add(IndexEntry.of(meta.toIndexLine(request.sha256(), now)).orElseThrow());
            writeIndex(repo, name, lines);
            updateMeta(repo, meta, publisher, now, lines);
            return warnings();
        });
    }

    static ObjectNode warnings() {
        ObjectNode body = CargoJson.object();
        ObjectNode warnings = body.putObject("warnings");
        warnings.putArray("invalid_categories");
        warnings.putArray("invalid_badges");
        warnings.putArray("other");
        return body;
    }

    /**
     * crates.io refuses a name that differs from an existing crate only in case or in {@code -} versus {@code _};
     * so does this registry, because cargo would treat the two as different crates while people would not.
     */
    private void checkSimilarNames(Repository repo, String name) {
        String lower = CrateNames.lower(name);
        String canonical = CrateNames.canonical(name);
        for (String dir : CrateNames.similarIndexDirs(name)) {
            for (Asset asset : storage.list(repo, CrateNames.INDEX_PREFIX + dir + "/")) {
                String other = CrateNames.nameOfIndexPath(asset.getPath());
                if (other == null || other.equals(lower) || !CrateNames.canonical(other).equals(canonical)) {
                    continue;
                }
                List<IndexEntry> entries = liveEntries(repo, other);
                if (!entries.isEmpty()) {
                    throw CargoError.conflict("crate name `" + name + "` is too similar to the existing crate `"
                            + entries.getFirst().name() + "`: names that differ only in `-` and `_` are the same crate");
                }
            }
        }
    }

    // --- yank ----------------------------------------------------------------------------------

    /** Sets or clears the {@code yanked} flag of one version. */
    void setYanked(Repository repo, String name, String version, boolean yanked) throws IOException {
        locked(repo, name, () -> {
            List<IndexEntry> live = requireCrate(repo, name);
            IndexEntry entry = IndexEntry.find(live, version).orElseThrow(() -> CargoError.notFound(
                    "crate `" + live.getFirst().name() + "` does not have a version `" + version + "`"));
            List<IndexEntry> lines = new ArrayList<>(live.size());
            for (IndexEntry e : live) {
                lines.add(e == entry ? e.withYanked(yanked) : e);
            }
            writeIndex(repo, name, lines);
            return null;
        });
    }

    // --- owners --------------------------------------------------------------------------------

    /** Adds owners; every login must be a Granary user. Returns the message cargo prints. */
    String addOwners(Repository repo, String name, List<String> logins) throws IOException {
        return locked(repo, name, () -> {
            List<IndexEntry> live = requireCrate(repo, name);
            List<String> resolved = new ArrayList<>();
            for (String login : logins) {
                resolved.add(users.find(login).map(User::getUsername).orElseThrow(() ->
                        CargoError.notFound("could not find user with login `" + login + "`")));
            }
            ObjectNode doc = meta(repo, name).orElseGet(() -> newMeta(live.getLast().name()));
            Set<String> owners = new LinkedHashSet<>(ownersOf(doc));
            owners.addAll(resolved);
            setOwners(doc, owners);
            writeMeta(repo, name, doc);
            String list = String.join(", ", resolved);
            return "user " + list + (resolved.size() == 1 ? " has" : " have") + " been added as "
                    + (resolved.size() == 1 ? "an owner" : "owners") + " of crate " + live.getLast().name();
        });
    }

    /** Removes owners; a crate keeps at least one. Returns the message cargo prints. */
    String removeOwners(Repository repo, String name, List<String> logins) throws IOException {
        return locked(repo, name, () -> {
            List<IndexEntry> live = requireCrate(repo, name);
            ObjectNode doc = meta(repo, name).orElseGet(() -> newMeta(live.getLast().name()));
            Set<String> owners = new LinkedHashSet<>(ownersOf(doc));
            Set<String> remove = new HashSet<>();
            for (String login : logins) {
                String match = owners.stream().filter(o -> o.equalsIgnoreCase(login)).findFirst().orElseThrow(() ->
                        CargoError.notFound("`" + login + "` is not an owner of crate " + live.getLast().name()));
                remove.add(match);
            }
            owners.removeAll(remove);
            if (owners.isEmpty()) {
                throw CargoError.badRequest("cannot remove all owners of crate " + live.getLast().name());
            }
            setOwners(doc, owners);
            writeMeta(repo, name, doc);
            return "owners successfully removed";
        });
    }

    // --- helpers -------------------------------------------------------------------------------

    private List<IndexEntry> requireCrate(Repository repo, String name) {
        List<IndexEntry> live = liveEntries(repo, name);
        if (live.isEmpty()) {
            throw CargoError.notFound("crate `" + name + "` does not exist");
        }
        return live;
    }

    private Set<String> crateFiles(Repository repo, String name) {
        Set<String> files = new HashSet<>();
        for (Asset asset : storage.list(repo, CrateNames.crateFolder(name))) {
            files.add(asset.getPath());
        }
        return files;
    }

    private void writeIndex(Repository repo, String name, List<IndexEntry> lines) {
        byte[] bytes = IndexEntry.render(lines).getBytes(StandardCharsets.UTF_8);
        storage.store(repo, CrateNames.indexPath(name), new ByteArrayInputStream(bytes), INDEX_CONTENT_TYPE,
                Asset.Origin.UPLOADED, true);
    }

    private void updateMeta(Repository repo, PublishMetadata meta, String publisher, Instant now, List<IndexEntry> lines) {
        ObjectNode doc = meta(repo, meta.name()).orElseGet(() -> newMeta(meta.name()));
        doc.put("name", meta.name());
        List<String> versions = lines.stream().filter(e -> !e.yanked()).map(IndexEntry::vers).toList();
        boolean first = !doc.has("versions") || doc.get("versions").isEmpty();
        boolean newest = CargoVersion.highest(versions).map(h -> h.equals(meta.vers())).orElse(true);
        if (first || newest) {
            for (String field : DESCRIPTIVE) {
                JsonNode value = meta.json().get(field);
                if (value == null || value.isNull()) {
                    doc.remove(field);
                } else {
                    doc.set(field, value.deepCopy());
                }
            }
        }
        Set<String> owners = new LinkedHashSet<>(ownersOf(doc));
        if (owners.isEmpty() && publisher != null && !publisher.isBlank()) {
            owners.add(publisher);
        }
        setOwners(doc, owners);
        ObjectNode published = doc.has("versions") && doc.get("versions").isObject()
                ? (ObjectNode) doc.get("versions") : doc.putObject("versions");
        ObjectNode entry = published.putObject(meta.vers());
        entry.put("published_by", publisher);
        entry.put("published_at", now.toString());
        writeMeta(repo, meta.name(), doc);
    }

    private static ObjectNode newMeta(String name) {
        ObjectNode doc = CargoJson.object();
        doc.put("name", name);
        doc.putArray("owners");
        doc.putObject("versions");
        return doc;
    }

    private void writeMeta(Repository repo, String name, ObjectNode doc) {
        byte[] bytes = CargoJson.bytes(doc);
        storage.store(repo, CrateNames.metaPath(name), new ByteArrayInputStream(bytes), META_CONTENT_TYPE,
                Asset.Origin.UPLOADED, true);
    }

    static List<String> ownersOf(ObjectNode doc) {
        List<String> owners = new ArrayList<>();
        JsonNode node = doc.get("owners");
        if (node != null && node.isArray()) {
            for (JsonNode o : node) {
                if (o.isString() && !o.stringValue().isBlank()) {
                    owners.add(o.stringValue());
                }
            }
        }
        return owners;
    }

    private static void setOwners(ObjectNode doc, Set<String> owners) {
        ArrayNode array = doc.putArray("owners");
        owners.forEach(array::add);
    }

    /** Descriptive field of the crate document as text ({@code keywords} joined), for search. */
    static String text(ObjectNode doc, String field) {
        JsonNode value = doc == null ? null : doc.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            value.forEach(v -> parts.add(v.asString()));
            return String.join(" ", parts);
        }
        return value.asString();
    }

    private static List<IndexEntry> read(StoredContent content) {
        try {
            return IndexEntry.parseFile(content.readUtf8());
        } catch (IOException e) {
            log.warn("index file {} is unreadable: {}", content.asset().getPath(), e.getMessage());
            return List.of();
        }
    }

    private <T> T locked(Repository repo, String name, IoSupplier<T> body) throws IOException {
        String key = repo.getId() + "|" + CrateNames.lower(name);
        ReentrantLock lock = locks[Math.floorMod(key.hashCode(), locks.length)];
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
