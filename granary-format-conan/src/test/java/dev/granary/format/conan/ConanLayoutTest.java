package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.PID;
import static dev.granary.format.conan.ConanTestSupport.PREV;
import static dev.granary.format.conan.ConanTestSupport.RREV;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConanLayoutTest {

    private static final ConanReference ZLIB = new ConanReference("zlib", "1.3.1", null, null);
    private static final ConanReference ACME = new ConanReference("acme", "1.0", "team", "stable");

    @Test
    void pathBuilders() {
        assertThat(ConanLayout.recipePrefix(ZLIB)).isEqualTo("conans/zlib/1.3.1/_/_/");
        assertThat(ConanLayout.recipePrefix(ACME)).isEqualTo("conans/acme/1.0/team/stable/");
        assertThat(ConanLayout.revisionPrefix(ZLIB, RREV)).isEqualTo("conans/zlib/1.3.1/_/_/" + RREV + "/");
        assertThat(ConanLayout.exportPath(ZLIB, RREV, "conanfile.py"))
                .isEqualTo("conans/zlib/1.3.1/_/_/" + RREV + "/export/conanfile.py");
        assertThat(ConanLayout.packagesPrefix(ZLIB, RREV)).isEqualTo("conans/zlib/1.3.1/_/_/" + RREV + "/package/");
        assertThat(ConanLayout.packagePrefix(ZLIB, RREV, PID)).isEqualTo("conans/zlib/1.3.1/_/_/" + RREV + "/package/" + PID + "/");
        assertThat(ConanLayout.packageRevisionPrefix(ZLIB, RREV, PID, PREV))
                .isEqualTo("conans/zlib/1.3.1/_/_/" + RREV + "/package/" + PID + "/" + PREV + "/");
        assertThat(ConanLayout.packagePath(ZLIB, RREV, PID, PREV, "conaninfo.txt"))
                .isEqualTo("conans/zlib/1.3.1/_/_/" + RREV + "/package/" + PID + "/" + PREV + "/conaninfo.txt");
    }

    @Test
    void parseIsTheReverseOfTheBuilders() {
        ConanLayout.StoredFile export = ConanLayout.parse(ConanLayout.exportPath(ACME, RREV, "conan_export.tgz")).orElseThrow();
        assertThat(export.reference()).isEqualTo(ACME);
        assertThat(export.recipeRevision()).isEqualTo(RREV);
        assertThat(export.isPackage()).isFalse();
        assertThat(export.file()).isEqualTo("conan_export.tgz");

        ConanLayout.StoredFile pkg = ConanLayout.parse(ConanLayout.packagePath(ZLIB, RREV, PID, PREV, "metadata/logs/build.log"))
                .orElseThrow();
        assertThat(pkg.reference()).isEqualTo(ZLIB);
        assertThat(pkg.isPackage()).isTrue();
        assertThat(pkg.packageId()).isEqualTo(PID);
        assertThat(pkg.packageRevision()).isEqualTo(PREV);
        assertThat(pkg.file()).isEqualTo("metadata/logs/build.log");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "v2/conans/zlib/1.3.1/_/_/latest",
            "conans/zlib/1.3.1/_/_",
            "conans/zlib/1.3.1/_/_/rrev/export",
            "conans/zlib/1.3.1/_/_/rrev/other/file",
            "conans/zlib/1.3.1/_/_/rrev/package/pid/prev",
            "conans/zlib/1.3.1/_/_/rrev/package/../prev/file",
            "conans/zlib/1.3.1/_/_/../export/file",
            "conans/../1.3.1/_/_/rrev/export/file",
            "conans/zlib/1.3.1/_/_/rrev/export/../file",
    })
    void parseRefusesPathsOutsideTheLayout(String path) {
        assertThat(ConanLayout.parse(path)).isEmpty();
    }

    @Test
    void parseOfNullIsEmpty() {
        assertThat(ConanLayout.parse(null)).isEmpty();
    }

    @Test
    void contentTypes() {
        assertThat(ConanLayout.contentType("conanfile.py")).isEqualTo("text/x-python");
        assertThat(ConanLayout.contentType("conanmanifest.txt")).isEqualTo("text/plain");
        assertThat(ConanLayout.contentType("conaninfo.txt")).isEqualTo("text/plain");
        assertThat(ConanLayout.contentType("conan_package.tgz")).isEqualTo("application/gzip");
        assertThat(ConanLayout.contentType("conan_package.txz")).isEqualTo("application/x-xz");
        assertThat(ConanLayout.contentType("conan_package.tzst")).isEqualTo("application/zstd");
        assertThat(ConanLayout.contentType("CONAN_EXPORT.TGZ")).isEqualTo("application/gzip");
        assertThat(ConanLayout.contentType("metadata/sign/x.json")).isEqualTo("application/json");
        assertThat(ConanLayout.contentType(null)).isNotBlank();
    }

    @Test
    void onlyMetadataFolderFilesAreMutable() {
        assertThat(ConanLayout.isMutableFile(ConanLayout.exportPath(ZLIB, RREV, "metadata/sign/sig.asc"))).isTrue();
        assertThat(ConanLayout.isMutableFile(ConanLayout.packagePath(ZLIB, RREV, PID, PREV, "metadata/logs/x.log"))).isTrue();
        assertThat(ConanLayout.isMutableFile("metadata/logs/x.log")).isTrue();
        assertThat(ConanLayout.isMutableFile(ConanLayout.exportPath(ZLIB, RREV, "conanfile.py"))).isFalse();
        assertThat(ConanLayout.isMutableFile(ConanLayout.packagePath(ZLIB, RREV, PID, PREV, "conan_package.tgz"))).isFalse();
        assertThat(ConanLayout.isMutableFile("conans/garbage")).isFalse();
        assertThat(ConanLayout.isMutableFile(null)).isFalse();
    }
}
