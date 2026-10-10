package dev.granary.format.conan;

import dev.granary.core.model.Asset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Everything a hosted repository knows about one recipe, read from the stored files of its
 * {@link ConanLayout#recipePrefix(ConanReference) prefix}.
 *
 * <p>A revision counts once its {@code conanmanifest.txt} is there. The client uploads that file last (file names
 * are uploaded in sorted order), so an interrupted upload never leaves a revision the client would later consider
 * "already in server" and skip, and the next upload completes it because identical files are accepted again.
 */
public final class ConanHostedIndex {

    static final String MANIFEST = "conanmanifest.txt";
    static final String CONANINFO = "conaninfo.txt";

    /** One package revision: its files and, when stored, the {@code conaninfo.txt} asset. */
    public record PackageRevision(String revision, Instant time, SortedSet<String> files, Asset conanInfo) {
        boolean complete() {
            return files.contains(MANIFEST);
        }
    }

    /** One binary package with its revisions. */
    public static final class Package {
        private final String id;
        private final Map<String, Builder> revisions = new TreeMap<>();

        Package(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Complete revisions, newest first. */
        public List<ConanRevisions.Entry> revisionEntries() {
            List<ConanRevisions.Entry> entries = new ArrayList<>();
            for (Builder b : revisions.values()) {
                if (b.files.contains(MANIFEST)) {
                    entries.add(ConanRevisions.entry(b.revision, b.time));
                }
            }
            return ConanRevisions.sorted(entries);
        }

        public Optional<PackageRevision> revision(String prev) {
            Builder b = revisions.get(prev);
            if (b == null || !b.files.contains(MANIFEST)) {
                return Optional.empty();
            }
            return Optional.of(new PackageRevision(b.revision, b.time, b.files, b.conanInfo));
        }

        public Optional<PackageRevision> latest() {
            List<ConanRevisions.Entry> entries = revisionEntries();
            return entries.isEmpty() ? Optional.empty() : revision(entries.get(0).revision());
        }
    }

    /** One recipe revision: its exported files and its packages. */
    public record Revision(String revision, Instant time, SortedSet<String> files, Map<String, Package> packages) {
        public Optional<Package> pkg(String packageId) {
            return Optional.ofNullable(packages.get(packageId));
        }
    }

    private static final class Builder {
        final String revision;
        final SortedSet<String> files = new TreeSet<>();
        final Map<String, Package> packages = new TreeMap<>();
        Instant time = Instant.EPOCH;
        Asset conanInfo;

        Builder(String revision) {
            this.revision = revision;
        }

        void touch(Instant instant) {
            if (instant != null && instant.isAfter(time)) {
                time = instant;
            }
        }
    }

    private final Map<String, Builder> revisions = new TreeMap<>();

    private ConanHostedIndex() {
    }

    /** Builds the index of {@code ref} from the assets stored under its prefix (other paths are ignored). */
    public static ConanHostedIndex of(ConanReference ref, List<Asset> assets) {
        ConanHostedIndex index = new ConanHostedIndex();
        for (Asset asset : assets) {
            Optional<ConanLayout.StoredFile> parsed = ConanLayout.parse(asset.getPath());
            if (parsed.isEmpty() || !parsed.get().reference().equals(ref)) {
                continue;
            }
            ConanLayout.StoredFile file = parsed.get();
            Builder revision = index.revisions.computeIfAbsent(file.recipeRevision(), Builder::new);
            Instant time = timeOf(asset);
            if (!file.isPackage()) {
                revision.files.add(file.file());
                revision.touch(time);
                continue;
            }
            Package pkg = revision.packages.computeIfAbsent(file.packageId(), Package::new);
            Builder prev = pkg.revisions.computeIfAbsent(file.packageRevision(), Builder::new);
            prev.files.add(file.file());
            prev.touch(time);
            if (CONANINFO.equals(file.file())) {
                prev.conanInfo = asset;
            }
        }
        return index;
    }

    static Instant timeOf(Asset asset) {
        if (asset.getCreatedAt() != null) {
            return asset.getCreatedAt();
        }
        if (asset.getLastVerifiedAt() != null) {
            return asset.getLastVerifiedAt();
        }
        return asset.getUpdatedAt() == null ? Instant.EPOCH : asset.getUpdatedAt();
    }

    /** Complete recipe revisions, newest first. */
    public List<ConanRevisions.Entry> recipeRevisions() {
        List<ConanRevisions.Entry> entries = new ArrayList<>();
        for (Builder b : revisions.values()) {
            if (b.files.contains(MANIFEST)) {
                entries.add(ConanRevisions.entry(b.revision, b.time));
            }
        }
        return ConanRevisions.sorted(entries);
    }

    public boolean isEmpty() {
        return recipeRevisions().isEmpty();
    }

    /** A complete recipe revision, with whatever packages it has. */
    public Optional<Revision> revision(String rrev) {
        Builder b = revisions.get(rrev);
        if (b == null || !b.files.contains(MANIFEST)) {
            return Optional.empty();
        }
        return Optional.of(new Revision(b.revision, b.time, b.files, new LinkedHashMap<>(b.packages)));
    }
}
