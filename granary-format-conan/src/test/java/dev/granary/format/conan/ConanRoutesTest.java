package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.PID;
import static dev.granary.format.conan.ConanTestSupport.PREV;
import static dev.granary.format.conan.ConanTestSupport.RREV;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.conan.ConanPath.Kind;
import org.junit.jupiter.api.Test;

class ConanRoutesTest {

    private static final ConanReference ZLIB = new ConanReference("zlib", "1.3.1", null, null);
    private static final ConanReference ACME = new ConanReference("acme", "1.0", "team", "stable");

    @Test
    void routesMatchWhatTheClientRequests() {
        assertThat(ConanRoutes.recipe(ZLIB)).isEqualTo("v2/conans/zlib/1.3.1/_/_");
        assertThat(ConanRoutes.recipe(ACME)).isEqualTo("v2/conans/acme/1.0/team/stable");
        assertThat(ConanRoutes.recipeLatest(ZLIB)).isEqualTo("v2/conans/zlib/1.3.1/_/_/latest");
        assertThat(ConanRoutes.recipeRevisions(ZLIB)).isEqualTo("v2/conans/zlib/1.3.1/_/_/revisions");
        assertThat(ConanRoutes.recipeFiles(ZLIB, RREV)).isEqualTo("v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files");
        assertThat(ConanRoutes.recipeFile(ZLIB, RREV, "conanfile.py"))
                .isEqualTo("v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/conanfile.py");
        assertThat(ConanRoutes.packageSearch(ZLIB, RREV)).isEqualTo("v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/search");
        assertThat(ConanRoutes.packages(ZLIB, RREV)).isEqualTo("v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages");
        assertThat(ConanRoutes.packageLatest(ZLIB, RREV, PID))
                .isEqualTo("v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages/" + PID + "/latest");
        assertThat(ConanRoutes.packageRevisions(ZLIB, RREV, PID))
                .isEqualTo("v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages/" + PID + "/revisions");
        assertThat(ConanRoutes.packageFiles(ZLIB, RREV, PID, PREV))
                .isEqualTo("v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages/" + PID + "/revisions/" + PREV + "/files");
        assertThat(ConanRoutes.packageFile(ZLIB, RREV, PID, PREV, "conaninfo.txt"))
                .isEqualTo("v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/packages/" + PID + "/revisions/" + PREV
                        + "/files/conaninfo.txt");
    }

    @Test
    void everyBuiltRouteParsesBackToItsKind() {
        assertThat(ConanPath.parse(ConanRoutes.search()).kind()).isEqualTo(Kind.SEARCH);
        assertThat(ConanPath.parse(ConanRoutes.recipeLatest(ACME)).kind()).isEqualTo(Kind.RECIPE_LATEST);
        assertThat(ConanPath.parse(ConanRoutes.recipeLatest(ACME)).reference()).isEqualTo(ACME);
        assertThat(ConanPath.parse(ConanRoutes.recipeRevisions(ACME)).kind()).isEqualTo(Kind.RECIPE_REVISIONS);
        assertThat(ConanPath.parse(ConanRoutes.recipeRevision(ACME, RREV)).kind()).isEqualTo(Kind.RECIPE_REVISION);
        assertThat(ConanPath.parse(ConanRoutes.recipeFiles(ACME, RREV)).kind()).isEqualTo(Kind.RECIPE_FILES);
        assertThat(ConanPath.parse(ConanRoutes.recipeFile(ACME, RREV, "conan_export.tgz")).kind()).isEqualTo(Kind.RECIPE_FILE);
        assertThat(ConanPath.parse(ConanRoutes.packageSearch(ACME, RREV)).kind()).isEqualTo(Kind.PACKAGE_SEARCH);
        assertThat(ConanPath.parse(ConanRoutes.packages(ACME, RREV)).kind()).isEqualTo(Kind.PACKAGES);
        assertThat(ConanPath.parse(ConanRoutes.packageLatest(ACME, RREV, PID)).kind()).isEqualTo(Kind.PACKAGE_LATEST);
        assertThat(ConanPath.parse(ConanRoutes.packageRevisions(ACME, RREV, PID)).kind()).isEqualTo(Kind.PACKAGE_REVISIONS);
        assertThat(ConanPath.parse(ConanRoutes.packageFiles(ACME, RREV, PID, PREV)).kind()).isEqualTo(Kind.PACKAGE_FILES);
        assertThat(ConanPath.parse(ConanRoutes.packageFile(ACME, RREV, PID, PREV, "conan_package.tgz")).kind())
                .isEqualTo(Kind.PACKAGE_FILE);
    }

    @Test
    void searchQueryIsEncodedLikeTheClientDoes() {
        assertThat(ConanRoutes.search(null, true)).isEqualTo("v2/conans/search");
        assertThat(ConanRoutes.search(" ", false)).isEqualTo("v2/conans/search");
        assertThat(ConanRoutes.search("zlib", true)).isEqualTo("v2/conans/search?q=zlib");
        assertThat(ConanRoutes.search("zlib/*", true)).isEqualTo("v2/conans/search?q=zlib%2F*");
        assertThat(ConanRoutes.search("acme/*@team/*", false)).isEqualTo("v2/conans/search?q=acme%2F*%40team%2F*&ignorecase=False");
        assertThat(ConanRoutes.search("a b&c", true)).isEqualTo("v2/conans/search?q=a+b%26c");
    }
}
