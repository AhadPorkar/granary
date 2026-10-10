package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.PID;
import static dev.granary.format.conan.ConanTestSupport.PREV;
import static dev.granary.format.conan.ConanTestSupport.PREV2;
import static dev.granary.format.conan.ConanTestSupport.RREV;
import static dev.granary.format.conan.ConanTestSupport.RREV2;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.model.Asset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConanHostedIndexTest {

    private static final ConanReference ZLIB = new ConanReference("zlib", "1.3.1", null, null);
    private static final Instant T1 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-01-03T10:00:00Z");

    private final List<Asset> assets = new ArrayList<>();

    private void export(String rrev, Instant at, String... files) {
        for (String file : files) {
            assets.add(asset(ConanLayout.exportPath(ZLIB, rrev, file), at));
        }
    }

    private void pkg(String rrev, String pid, String prev, Instant at, String... files) {
        for (String file : files) {
            assets.add(asset(ConanLayout.packagePath(ZLIB, rrev, pid, prev, file), at));
        }
    }

    private static Asset asset(String path, Instant at) {
        Asset asset = new Asset();
        asset.setPath(path);
        asset.setLastVerifiedAt(at);
        return asset;
    }

    @Test
    void aRevisionCountsOnceItsManifestIsStored() {
        export(RREV, T1, "conan_export.tgz", "conanfile.py");
        ConanHostedIndex partial = ConanHostedIndex.of(ZLIB, assets);
        assertThat(partial.isEmpty()).isTrue();
        assertThat(partial.recipeRevisions()).isEmpty();
        assertThat(partial.revision(RREV)).isEmpty();

        export(RREV, T2, "conanmanifest.txt");
        ConanHostedIndex complete = ConanHostedIndex.of(ZLIB, assets);
        assertThat(complete.isEmpty()).isFalse();
        assertThat(complete.recipeRevisions()).singleElement().satisfies(e -> {
            assertThat(e.revision()).isEqualTo(RREV);
            assertThat(e.instant()).isEqualTo(T2); // the time of the newest file of the revision
        });
        assertThat(complete.revision(RREV).orElseThrow().files())
                .containsExactly("conan_export.tgz", "conanfile.py", "conanmanifest.txt");
    }

    @Test
    void revisionsAreNewestFirst() {
        export(RREV, T1, "conanfile.py", "conanmanifest.txt");
        export(RREV2, T3, "conanfile.py", "conanmanifest.txt");
        ConanHostedIndex index = ConanHostedIndex.of(ZLIB, assets);
        assertThat(index.recipeRevisions()).extracting(ConanRevisions.Entry::revision).containsExactly(RREV2, RREV);
    }

    @Test
    void packagesAndTheirRevisions() {
        export(RREV, T1, "conanfile.py", "conanmanifest.txt");
        pkg(RREV, PID, PREV, T1, "conan_package.tgz", "conaninfo.txt", "conanmanifest.txt");
        pkg(RREV, PID, PREV2, T3, "conan_package.tgz", "conaninfo.txt", "conanmanifest.txt");
        pkg(RREV, PID, "incomplete", T3, "conan_package.tgz");
        pkg(RREV, "otherpid", PREV, T2, "conan_package.tgz");

        ConanHostedIndex.Revision revision = ConanHostedIndex.of(ZLIB, assets).revision(RREV).orElseThrow();
        assertThat(revision.packages()).containsOnlyKeys(PID, "otherpid");
        ConanHostedIndex.Package pkg = revision.pkg(PID).orElseThrow();
        assertThat(pkg.id()).isEqualTo(PID);
        assertThat(pkg.revisionEntries()).extracting(ConanRevisions.Entry::revision).containsExactly(PREV2, PREV);
        assertThat(pkg.latest()).hasValueSatisfying(p -> {
            assertThat(p.revision()).isEqualTo(PREV2);
            assertThat(p.files()).containsExactly("conan_package.tgz", "conaninfo.txt", "conanmanifest.txt");
            assertThat(p.conanInfo()).isNotNull();
            assertThat(p.conanInfo().getPath()).endsWith(PREV2 + "/conaninfo.txt");
        });
        assertThat(pkg.revision("incomplete")).isEmpty();
        assertThat(pkg.revision("missing")).isEmpty();
        // a binary without a complete revision exists as a folder but has nothing to offer
        ConanHostedIndex.Package other = revision.pkg("otherpid").orElseThrow();
        assertThat(other.revisionEntries()).isEmpty();
        assertThat(other.latest()).isEmpty();
        assertThat(revision.pkg("nope")).isEmpty();
    }

    @Test
    void packagesWithoutACompleteRecipeAreHidden() {
        export(RREV, T1, "conanfile.py");
        pkg(RREV, PID, PREV, T1, "conan_package.tgz", "conaninfo.txt", "conanmanifest.txt");
        assertThat(ConanHostedIndex.of(ZLIB, assets).revision(RREV)).isEmpty();
    }

    @Test
    void foreignAndMalformedPathsAreIgnored() {
        export(RREV, T1, "conanfile.py", "conanmanifest.txt");
        ConanReference other = new ConanReference("zlib", "1.3.1", "team", "stable");
        assets.add(asset(ConanLayout.exportPath(other, RREV2, "conanmanifest.txt"), T3));
        assets.add(asset("conans/zlib/1.3.1/_/_/garbage", T3));
        assets.add(asset("v2/conans/zlib/1.3.1/_/_/revisions", T3));
        ConanHostedIndex index = ConanHostedIndex.of(ZLIB, assets);
        assertThat(index.recipeRevisions()).extracting(ConanRevisions.Entry::revision).containsExactly(RREV);
    }

    @Test
    void timeFallsBackFromCreationToVerificationToUpdate() {
        Asset none = new Asset();
        assertThat(ConanHostedIndex.timeOf(none)).isEqualTo(Instant.EPOCH);
        Asset verified = new Asset();
        verified.setLastVerifiedAt(T2);
        assertThat(ConanHostedIndex.timeOf(verified)).isEqualTo(T2);
    }
}
