package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.PID;
import static dev.granary.format.conan.ConanTestSupport.PREV;
import static dev.granary.format.conan.ConanTestSupport.RREV;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.conan.ConanPath.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConanPathTest {

    private static final String RECIPE = "v2/conans/zlib/1.3.1/_/_";
    private static final ConanReference ZLIB = new ConanReference("zlib", "1.3.1", null, null);

    @Test
    void serverEndpoints() {
        assertThat(ConanPath.parse("").kind()).isEqualTo(Kind.ROOT);
        assertThat(ConanPath.parse(null).kind()).isEqualTo(Kind.ROOT);
        assertThat(ConanPath.parse("/").kind()).isEqualTo(Kind.ROOT);
        assertThat(ConanPath.parse("v1/ping").kind()).isEqualTo(Kind.PING);
        assertThat(ConanPath.parse("v2/ping").kind()).isEqualTo(Kind.PING);
        assertThat(ConanPath.parse("v1/ping/").kind()).isEqualTo(Kind.PING);
        assertThat(ConanPath.parse("v2/users/authenticate").kind()).isEqualTo(Kind.AUTHENTICATE);
        assertThat(ConanPath.parse("v2/users/check_credentials").kind()).isEqualTo(Kind.CHECK_CREDENTIALS);
        assertThat(ConanPath.parse("v2/users/someone").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(ConanPath.parse("v2/conans/search").kind()).isEqualTo(Kind.SEARCH);
        assertThat(ConanPath.parse("v2/conans/search?q=zlib").kind()).isEqualTo(Kind.SEARCH);
        assertThat(ConanPath.parse("v1/conans/zlib/1.3.1/_/_/digest").kind()).isEqualTo(Kind.V1);
        // Conan 1.x logs in through v1 even when it speaks v2 for everything else
        assertThat(ConanPath.parse("v1/users/authenticate").kind()).isEqualTo(Kind.AUTHENTICATE);
        assertThat(ConanPath.parse("v1/users/check_credentials").kind()).isEqualTo(Kind.CHECK_CREDENTIALS);
        assertThat(ConanPath.parse("v1/users/token").kind()).isEqualTo(Kind.V1);
        assertThat(ConanPath.parse("v1/conans/search").kind()).isEqualTo(Kind.V1);
        assertThat(ConanPath.parse("v1").kind()).isEqualTo(Kind.V1);
        assertThat(ConanPath.parse("v3/anything").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(ConanPath.parse("index.html").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(ConanPath.parse("v2/conans/zlib/1.3.1").kind()).isEqualTo(Kind.UNKNOWN);
        assertThat(ConanPath.parse("v2").kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void recipeRoutes() {
        ConanPath recipe = ConanPath.parse(RECIPE);
        assertThat(recipe.kind()).isEqualTo(Kind.RECIPE);
        assertThat(recipe.reference()).isEqualTo(ZLIB);
        assertThat(recipe.recipeRevision()).isNull();

        assertThat(ConanPath.parse(RECIPE + "/latest").kind()).isEqualTo(Kind.RECIPE_LATEST);
        assertThat(ConanPath.parse(RECIPE + "/revisions").kind()).isEqualTo(Kind.RECIPE_REVISIONS);
        assertThat(ConanPath.parse(RECIPE + "/search").kind()).isEqualTo(Kind.PACKAGE_SEARCH);
        assertThat(ConanPath.parse(RECIPE + "/search").recipeRevision()).isNull();

        ConanPath revision = ConanPath.parse(RECIPE + "/revisions/" + RREV);
        assertThat(revision.kind()).isEqualTo(Kind.RECIPE_REVISION);
        assertThat(revision.recipeRevision()).isEqualTo(RREV);

        assertThat(ConanPath.parse(RECIPE + "/revisions/" + RREV + "/files").kind()).isEqualTo(Kind.RECIPE_FILES);
        ConanPath file = ConanPath.parse(RECIPE + "/revisions/" + RREV + "/files/conanfile.py");
        assertThat(file.kind()).isEqualTo(Kind.RECIPE_FILE);
        assertThat(file.file()).isEqualTo("conanfile.py");
        assertThat(file.isFile()).isTrue();
        assertThat(file.isPackageRoute()).isFalse();

        ConanPath search = ConanPath.parse(RECIPE + "/revisions/" + RREV + "/search");
        assertThat(search.kind()).isEqualTo(Kind.PACKAGE_SEARCH);
        assertThat(search.recipeRevision()).isEqualTo(RREV);
    }

    @Test
    void packageRoutes() {
        String base = RECIPE + "/revisions/" + RREV + "/packages";
        assertThat(ConanPath.parse(base).kind()).isEqualTo(Kind.PACKAGES);
        ConanPath pkg = ConanPath.parse(base + "/" + PID);
        assertThat(pkg.kind()).isEqualTo(Kind.PACKAGE);
        assertThat(pkg.packageId()).isEqualTo(PID);
        assertThat(pkg.isPackageRoute()).isTrue();
        assertThat(ConanPath.parse(base + "/" + PID + "/latest").kind()).isEqualTo(Kind.PACKAGE_LATEST);
        assertThat(ConanPath.parse(base + "/" + PID + "/revisions").kind()).isEqualTo(Kind.PACKAGE_REVISIONS);
        ConanPath prev = ConanPath.parse(base + "/" + PID + "/revisions/" + PREV);
        assertThat(prev.kind()).isEqualTo(Kind.PACKAGE_REVISION);
        assertThat(prev.packageRevision()).isEqualTo(PREV);
        assertThat(ConanPath.parse(base + "/" + PID + "/revisions/" + PREV + "/files").kind()).isEqualTo(Kind.PACKAGE_FILES);
        ConanPath file = ConanPath.parse(base + "/" + PID + "/revisions/" + PREV + "/files/conan_package.tgz");
        assertThat(file.kind()).isEqualTo(Kind.PACKAGE_FILE);
        assertThat(file.file()).isEqualTo("conan_package.tgz");
        assertThat(file.reference()).isEqualTo(ZLIB);
        assertThat(file.recipeRevision()).isEqualTo(RREV);
        assertThat(file.packageId()).isEqualTo(PID);
    }

    @Test
    void userAndChannelInTheUrl() {
        ConanPath path = ConanPath.parse("v2/conans/acme/1.0/team/stable/latest");
        assertThat(path.reference()).isEqualTo(new ConanReference("acme", "1.0", "team", "stable"));
    }

    @Test
    void metadataFilesKeepTheirFolders() {
        ConanPath path = ConanPath.parse(RECIPE + "/revisions/" + RREV + "/files/metadata/sign/pkgsign-manifest.json");
        assertThat(path.kind()).isEqualTo(Kind.RECIPE_FILE);
        assertThat(path.file()).isEqualTo("metadata/sign/pkgsign-manifest.json");
        ConanPath hidden = ConanPath.parse(RECIPE + "/revisions/" + RREV + "/files/metadata/.cache");
        assertThat(hidden.file()).isEqualTo("metadata/.cache");
    }

    @Test
    void queryStringIsIgnored() {
        ConanPath path = ConanPath.parse(RECIPE + "/revisions/" + RREV + "/search?list_only=True");
        assertThat(path.kind()).isEqualTo(Kind.PACKAGE_SEARCH);
        assertThat(path.path()).isEqualTo(RECIPE + "/revisions/" + RREV + "/search");
    }

    @Test
    void leadingAndTrailingSlashesAreTolerated() {
        assertThat(ConanPath.parse("/" + RECIPE + "/latest/").kind()).isEqualTo(Kind.RECIPE_LATEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "v2/conans/../1.3.1/_/_/latest",
            "v2/conans/zlib/../_/_/latest",
            "v2/conans/zlib/1.3.1/../_/latest",
            "v2/conans/zlib/1.3.1/_/a b/latest",
            "v2/conans/zlib/1.3.1/_/_/revisions/../files",
            "v2/conans/zlib/1.3.1/_/_/revisions/r%2Fx/files",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/../conanfile.py",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/./conanfile.py",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/a\\b",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/con fig.py",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/metadata//x",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages/../latest",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages/" + PID + "/revisions/..",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages/" + PID + "/revisions/" + PREV + "/files/ü.txt",
            "v2/conans/zl\u0000ib/1.3.1/_/_/latest",
    })
    void unsafeSegmentsAreInvalid(String raw) {
        assertThat(ConanPath.parse(raw).kind()).isEqualTo(Kind.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "v2/conans/zlib/1.3.1/_/_/nope",
            "v2/conans/zlib/1.3.1/_/_/latest/extra",
            "v2/conans/zlib/1.3.1/_/_/search/extra",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/nope",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/search/extra",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages/" + PID + "/nope",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages/" + PID + "/latest/x",
            "v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages/" + PID + "/revisions/" + PREV + "/nope",
    })
    void unknownTailsAreUnknown(String raw) {
        assertThat(ConanPath.parse(raw).kind()).isEqualTo(Kind.UNKNOWN);
    }

    @Test
    void overlongSegmentsAndFileNamesAreInvalid() {
        String longest = "a".repeat(ConanReference.MAX_SEGMENT_LENGTH);
        String recipe = "v2/conans/zlib/1.3.1/_/_/revisions/";
        assertThat(ConanPath.parse("v2/conans/" + longest + "/1.0/_/_/latest").kind()).isEqualTo(Kind.RECIPE_LATEST);
        assertThat(ConanPath.parse("v2/conans/" + longest + "a/1.0/_/_/latest").kind()).isEqualTo(Kind.INVALID);
        assertThat(ConanPath.parse(recipe + longest + "a/files").kind()).isEqualTo(Kind.INVALID);
        assertThat(ConanPath.parse(recipe + "r/packages/" + longest + "a/latest").kind()).isEqualTo(Kind.INVALID);
        assertThat(ConanPath.parse(recipe + "r/packages/p/revisions/" + longest + "a/files").kind()).isEqualTo(Kind.INVALID);

        String file = "metadata/" + "f".repeat(ConanPath.MAX_FILE_LENGTH - "metadata/".length());
        ConanPath accepted = ConanPath.parse(recipe + "r/files/" + file);
        assertThat(accepted.kind()).isEqualTo(Kind.RECIPE_FILE);
        assertThat(accepted.file()).hasSize(ConanPath.MAX_FILE_LENGTH);
        assertThat(ConanPath.parse(recipe + "r/files/" + file + "f").kind()).isEqualTo(Kind.INVALID);
        assertThat(ConanPath.parse(recipe + "r/packages/p/revisions/v/files/" + file + "f").kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void metadataKinds() {
        String rev = RECIPE + "/revisions/" + RREV;
        String pkg = rev + "/packages/" + PID;
        for (String doc : List.of("v2/conans/search", RECIPE + "/latest", RECIPE + "/revisions", rev + "/files",
                rev + "/search", RECIPE + "/search", pkg + "/latest", pkg + "/revisions", pkg + "/revisions/" + PREV + "/files")) {
            assertThat(ConanPath.parse(doc).isMetadata()).as(doc).isTrue();
        }
        for (String other : List.of("v1/ping", "", rev + "/files/conanfile.py", pkg + "/revisions/" + PREV + "/files/conaninfo.txt",
                RECIPE, rev, pkg, "v2/users/authenticate")) {
            assertThat(ConanPath.parse(other).isMetadata()).as(other).isFalse();
        }
    }

    @Test
    void safeFileSegments() {
        assertThat(ConanPath.isSafeFileSegment("conan_export.tgz")).isTrue();
        assertThat(ConanPath.isSafeFileSegment(".cache")).isTrue();
        assertThat(ConanPath.isSafeFileSegment("a+b-c_d.e")).isTrue();
        assertThat(ConanPath.isSafeFileSegment("..")).isFalse();
        assertThat(ConanPath.isSafeFileSegment(".")).isFalse();
        assertThat(ConanPath.isSafeFileSegment("")).isFalse();
        assertThat(ConanPath.isSafeFileSegment(null)).isFalse();
        assertThat(ConanPath.isSafeFileSegment("a:b")).isFalse();
        assertThat(ConanPath.file(List.of("metadata", "logs", "build.log"))).isEqualTo("metadata/logs/build.log");
        assertThat(ConanPath.file(List.of("metadata", "..", "x"))).isNull();
    }
}
