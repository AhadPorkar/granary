package dev.granary.format.rubygems;

import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A hosted RubyGems repository.
 *
 * <p>Layout: {@code gems/<full name>.gem} (the pushed file), {@code quick/Marshal.4.8/<full name>.gemspec.rz} (its
 * marshalled specification, generated at push time) and {@code meta/<name>/<full name>.json} (a {@link GemRecord}),
 * all three linked to one component. Every index ({@code versions}, {@code names}, {@code info/<name>} and the three
 * {@code specs} files) is derived from the records and rebuilt on the first read after they change, so a push, a
 * yank or a deletion in the UI is reflected immediately and nothing can drift out of sync.
 */
@Component
public class RubygemsHosted {

    public static final String META_PREFIX = "meta/";
    public static final String JSON = "application/json";

    private static final Logger log = LoggerFactory.getLogger(RubygemsHosted.class);
    private static final int LOCK_STRIPES = 64;
    private static final int RECORD_MEMO_SIZE = 20_000;

    private final StorageService storage;
    private final DerivedDocuments derived;
    private final ReentrantLock[] gemLocks = new ReentrantLock[LOCK_STRIPES];

    /** Parsed records by blob SHA-256; a record blob never changes. */
    private final Map<String, GemRecord> recordMemo = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, GemRecord> eldest) {
            return size() > RECORD_MEMO_SIZE;
        }
    };

    public RubygemsHosted(StorageService storage, DerivedDocuments derived) {
        this.storage = storage;
        this.derived = derived;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            gemLocks[i] = new ReentrantLock();
        }
    }

    public static String recordPath(String name, String fullName) {
        return META_PREFIX + name + "/" + fullName + ".json";
    }

    private ReentrantLock lockFor(Repository repo, String name) {
        return gemLocks[Math.floorMod((repo.getId() + "/" + name).hashCode(), LOCK_STRIPES)];
    }

    // --- records ------------------------------------------------------------------------------------

    /** Every record of the repository (yanked ones included), in a stable order. */
    public List<GemRecord> records(Repository repo) {
        return records(repo, storage.list(repo, META_PREFIX));
    }

    /** The records of one gem (yanked ones included), oldest first. */
    public List<GemRecord> records(Repository repo, String name) {
        return records(repo, storage.list(repo, META_PREFIX + name + "/"));
    }

    private List<GemRecord> records(Repository repo, List<Asset> assets) {
        List<GemRecord> records = new ArrayList<>(assets.size());
        for (Asset asset : assets) {
            if (!asset.getPath().endsWith(".json")) {
                continue;
            }
            GemRecord record = record(repo, asset);
            if (record != null && recordPath(record.name(), record.fullName()).equals(asset.getPath())) {
                records.add(record);
            }
        }
        records.sort(ORDER);
        return records;
    }

    /** Push order, then version order, so documents built from the same records are byte-identical. */
    static final Comparator<GemRecord> ORDER = Comparator
            .comparing((GemRecord r) -> r.createdAt() == null ? Instant.EPOCH : r.createdAt())
            .thenComparing(GemRecord::tuple);

    private GemRecord record(Repository repo, Asset asset) {
        synchronized (recordMemo) {
            GemRecord known = recordMemo.get(asset.getBlobSha256());
            if (known != null) {
                return known;
            }
        }
        try {
            GemRecord record = GemRecord.fromJson(new StoredContent(asset, storage.blobStoreOf(repo)).readAllBytes());
            synchronized (recordMemo) {
                recordMemo.put(asset.getBlobSha256(), record);
            }
            return record;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("ignoring unreadable gem record {} in {}: {}", asset.getPath(), repo.getName(), e.getMessage());
            return null;
        }
    }

    public Optional<GemRecord> record(Repository repo, String name, String fullName) {
        return storage.find(repo, recordPath(name, fullName)).map(c -> record(repo, c.asset()));
    }

    private static String fingerprint(String what, List<Asset> assets) {
        List<String> parts = new ArrayList<>(assets.size() + 1);
        parts.add(what);
        for (Asset a : assets) {
            parts.add(a.getPath() + " " + a.getBlobSha256());
        }
        return DerivedDocuments.fingerprint(parts);
    }

    // --- index documents ----------------------------------------------------------------------------

    public StoredContent versions(Repository repo) throws IOException {
        List<Asset> assets = storage.list(repo, META_PREFIX);
        return derived.get(repo, CompactIndex.VERSIONS, fingerprint(CompactIndex.VERSIONS, assets), GemResponses.TEXT_UTF8,
                out -> write(out, versionsDocument(records(repo, assets))));
    }

    public StoredContent names(Repository repo) throws IOException {
        List<Asset> assets = storage.list(repo, META_PREFIX);
        return derived.get(repo, CompactIndex.NAMES, fingerprint(CompactIndex.NAMES, assets), GemResponses.TEXT_UTF8,
                out -> write(out, CompactIndex.names(liveNames(records(repo, assets)))));
    }

    /** {@code info/<name>}; empty when the repository never had the gem. */
    public Optional<StoredContent> info(Repository repo, String name) throws IOException {
        List<Asset> assets = storage.list(repo, META_PREFIX + name + "/");
        List<GemRecord> records = records(repo, assets);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(derived.get(repo, CompactIndex.INFO_PREFIX + name, fingerprint("info " + name, assets),
                GemResponses.TEXT_UTF8, out -> write(out, infoDocument(records))));
    }

    public StoredContent specs(Repository repo, String which) throws IOException {
        List<Asset> assets = storage.list(repo, META_PREFIX);
        return derived.get(repo, which, fingerprint(which, assets), GemResponses.OCTET_STREAM, out -> {
            List<SpecTuple> live = records(repo, assets).stream().filter(r -> !r.yanked()).map(GemRecord::tuple).toList();
            List<SpecTuple> tuples = switch (which) {
                case SpecsIndex.LATEST_SPECS -> SpecsIndex.latest(live);
                case SpecsIndex.PRERELEASE_SPECS -> SpecsIndex.prerelease(live);
                default -> SpecsIndex.released(live);
            };
            SpecsIndex.write(tuples, out);
        });
    }

    /** {@code info/<name>} of a gem's records: its live versions in push order. */
    static String infoDocument(List<GemRecord> records) {
        return CompactIndex.info(records.stream().filter(r -> !r.yanked()).toList());
    }

    /** The {@code versions} file of a set of records, with the info checksums it must carry. */
    static String versionsDocument(List<GemRecord> records) {
        Map<String, List<GemRecord>> byName = new TreeMap<>();
        Instant newest = Instant.EPOCH;
        for (GemRecord r : records) {
            byName.computeIfAbsent(r.name(), n -> new ArrayList<>()).add(r);
            for (Instant t : new Instant[] {r.createdAt(), r.yankedAt()}) {
                if (t != null && t.isAfter(newest)) {
                    newest = t;
                }
            }
        }
        List<CompactIndex.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, List<GemRecord>> e : byName.entrySet()) {
            LinkedHashSet<String> versions = new LinkedHashSet<>();
            for (GemRecord r : e.getValue()) {
                if (!r.yanked()) {
                    versions.add(r.versionKey());
                }
            }
            entries.add(new CompactIndex.Entry(e.getKey(), versions, CompactIndex.md5(infoDocument(e.getValue()))));
        }
        return CompactIndex.versions(newest, entries);
    }

    private static List<String> liveNames(List<GemRecord> records) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (GemRecord r : records) {
            if (!r.yanked()) {
                names.add(r.name());
            }
        }
        return new ArrayList<>(names);
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    // --- files --------------------------------------------------------------------------------------

    public Optional<StoredContent> file(Repository repo, String path) {
        return storage.find(repo, path);
    }

    // --- push, yank, unyank -------------------------------------------------------------------------

    /** What a push stored. */
    public record Pushed(GemRecord record, boolean replaced) {
    }

    /**
     * Validates and stores a pushed gem. {@link GemError} for an invalid gem (422) or an existing version (409,
     * unless the repository allows redeploys).
     */
    public Pushed push(Repository repo, Path gemFile, String pushedBy) throws IOException {
        GemPackage.Contents contents;
        try (InputStream in = Files.newInputStream(gemFile)) {
            contents = GemPackage.read(in);
        } catch (InvalidGemException e) {
            throw GemError.invalidGem("RubyGems cannot process this gem.\n" + e.getMessage());
        }
        GemSpec spec = contents.spec();
        String fullName = spec.fullName();
        if (!GemNames.isSafeFullName(fullName)) {
            throw GemError.invalidGem("RubyGems cannot process this gem.\n'" + fullName + "' is not a usable file name");
        }
        byte[] quickSpec;
        try {
            quickSpec = QuickSpec.deflated(spec);
        } catch (RuntimeException e) {
            throw GemError.invalidGem("RubyGems cannot process this gem.\nIts specification cannot be indexed: " + e.getMessage());
        }
        ReentrantLock lock = lockFor(repo, spec.name());
        lock.lock();
        try {
            Optional<StoredContent> existing = storage.find(repo, recordPath(spec.name(), fullName));
            if (existing.isEmpty()) {
                existing = storage.find(repo, GemNames.gemPath(fullName));
            }
            if (existing.isPresent() && !repo.isAllowRedeploy()) {
                throw GemError.conflict("Repushing of gem versions is not allowed.\n"
                        + spec.name() + " (" + spec.versionKey() + ") already exists in " + repo.getName() + ".\n"
                        + "Please bump the version number and push a new release.");
            }
            String clash = caseClash(repo, spec.name());
            if (clash != null) {
                throw GemError.conflict("There is already a gem named '" + clash + "' in " + repo.getName()
                        + "; gem names must be unique regardless of case.");
            }
            StorageService.ComponentCoordinates coordinates =
                    new StorageService.ComponentCoordinates("", spec.name(), spec.versionKey());
            StoredContent gem;
            try (InputStream in = Files.newInputStream(gemFile)) {
                gem = storage.store(repo, GemNames.gemPath(fullName), in, GemResponses.OCTET_STREAM,
                        Asset.Origin.UPLOADED, false, coordinates);
            }
            storage.store(repo, GemNames.quickSpecPath(fullName), new ByteArrayInputStream(quickSpec),
                    GemResponses.OCTET_STREAM, Asset.Origin.GENERATED, false, coordinates);
            GemRecord record = GemRecord.of(spec, gem.asset().getBlobSha256(), gem.size(), Instant.now(), pushedBy);
            storeRecord(repo, record);
            return new Pushed(record, existing.isPresent());
        } finally {
            lock.unlock();
        }
    }

    /** Another gem whose name differs only in case, or {@code null}. */
    private String caseClash(Repository repo, String name) {
        for (Asset asset : storage.list(repo, META_PREFIX)) {
            String rest = asset.getPath().substring(META_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String other = rest.substring(0, slash);
                if (!other.equals(name) && other.equalsIgnoreCase(name)) {
                    return other;
                }
            }
        }
        return null;
    }

    private void storeRecord(Repository repo, GemRecord record) {
        storage.store(repo, recordPath(record.name(), record.fullName()), new ByteArrayInputStream(record.toJson()), JSON,
                Asset.Origin.GENERATED, true,
                new StorageService.ComponentCoordinates("", record.name(), record.versionKey()));
    }

    /**
     * Yanks ({@code yank = true}) or restores a version. Without a platform the {@code ruby} version is meant, or
     * the only version with that number. {@link GemError} when there is no such version or it already is in the
     * requested state.
     */
    public GemRecord yank(Repository repo, String name, String version, String platform, boolean yank) {
        if (name == null || name.isBlank()) {
            throw GemError.badRequest("The gem_name parameter is missing.");
        }
        if (version == null || version.isBlank()) {
            throw GemError.badRequest("The version parameter is missing.");
        }
        String gem = name.strip();
        String number = version.strip();
        if (!GemNames.isValidName(gem)) {
            throw GemError.notFound("This rubygem could not be found.");
        }
        ReentrantLock lock = lockFor(repo, gem);
        lock.lock();
        try {
            List<GemRecord> records = records(repo, gem);
            if (records.isEmpty()) {
                throw GemError.notFound("This rubygem could not be found.");
            }
            List<GemRecord> matching = records.stream().filter(r -> r.version().equals(number)).toList();
            GemRecord target;
            if (platform != null && !platform.isBlank()) {
                String p = GemPlatform.isRuby(platform.strip()) ? GemPlatform.RUBY : platform.strip();
                target = matching.stream().filter(r -> r.platform().equals(p)).findFirst().orElse(null);
            } else {
                target = matching.stream().filter(r -> GemPlatform.isRuby(r.platform())).findFirst()
                        .orElse(matching.size() == 1 ? matching.getFirst() : null);
            }
            if (target == null) {
                String suffix = platform == null || platform.isBlank() ? "" : " for platform " + platform.strip();
                throw GemError.notFound("The version " + number + suffix + " does not exist for " + gem + ".");
            }
            if (yank && target.yanked()) {
                throw GemError.unprocessable("The version " + target.versionKey() + " has already been yanked.");
            }
            if (!yank && !target.yanked()) {
                throw GemError.unprocessable("The version " + target.versionKey() + " is not yanked.");
            }
            GemRecord updated = target.withYanked(yank, Instant.now());
            storeRecord(repo, updated);
            return updated;
        } finally {
            lock.unlock();
        }
    }

    /** Downloads of every stored {@code .gem} of a gem, by full name. */
    public Map<String, Long> downloads(Repository repo, String name) {
        Map<String, Long> downloads = new LinkedHashMap<>();
        for (Asset asset : storage.list(repo, GemNames.GEMS_PREFIX + name + "-")) {
            String file = asset.getPath().substring(GemNames.GEMS_PREFIX.length());
            if (file.endsWith(GemNames.GEM_SUFFIX)) {
                downloads.put(file.substring(0, file.length() - GemNames.GEM_SUFFIX.length()), asset.getDownloadCount());
            }
        }
        return downloads;
    }
}
