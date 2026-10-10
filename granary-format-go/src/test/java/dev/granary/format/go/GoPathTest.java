package dev.granary.format.go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GoPathTest {

    @Test
    void rootAndLists() {
        assertThat(GoPath.parse("").kind()).isEqualTo(GoPath.Kind.ROOT);
        assertThat(GoPath.parse(null).kind()).isEqualTo(GoPath.Kind.ROOT);
        GoPath list = GoPath.parse("github.com/!burnt!sushi/toml/@v/list");
        assertThat(list.kind()).isEqualTo(GoPath.Kind.LIST);
        assertThat(list.module()).isEqualTo("github.com/BurntSushi/toml");
        assertThat(list.escapedModule()).isEqualTo("github.com/!burnt!sushi/toml");
        assertThat(list.version()).isNull();
        assertThat(list.isMetadata()).isTrue();
    }

    @Test
    void versionFiles() {
        GoPath info = GoPath.parse("rsc.io/quote/@v/v1.5.2.info");
        assertThat(info.kind()).isEqualTo(GoPath.Kind.INFO);
        assertThat(info.module()).isEqualTo("rsc.io/quote");
        assertThat(info.version()).isEqualTo("v1.5.2");
        assertThat(info.isMetadata()).isFalse();
        assertThat(info.hasCanonicalVersion()).isTrue();
        assertThat(info.filePath("zip")).isEqualTo("rsc.io/quote/@v/v1.5.2.zip");

        GoPath mod = GoPath.parse("example.com/x/@v/v2.0.0+incompatible.mod");
        assertThat(mod.kind()).isEqualTo(GoPath.Kind.MOD);
        assertThat(mod.version()).isEqualTo("v2.0.0+incompatible");

        GoPath zip = GoPath.parse("example.com/x/@v/v1.0.0-!r!c1.zip");
        assertThat(zip.kind()).isEqualTo(GoPath.Kind.ZIP);
        assertThat(zip.version()).isEqualTo("v1.0.0-RC1");
        assertThat(zip.escapedVersion()).isEqualTo("v1.0.0-!r!c1");
        assertThat(zip.filePath("zip")).isEqualTo("example.com/x/@v/v1.0.0-!r!c1.zip");

        GoPath pseudo = GoPath.parse("example.com/x/@v/v0.0.0-20200101000000-abcdef123456.info");
        assertThat(pseudo.hasCanonicalVersion()).isTrue();
    }

    @Test
    void branchQueriesAreMetadataInfoLookups() {
        GoPath master = GoPath.parse("example.com/x/@v/master.info");
        assertThat(master.kind()).isEqualTo(GoPath.Kind.INFO);
        assertThat(master.version()).isEqualTo("master");
        assertThat(master.isMetadata()).isTrue();
        assertThat(master.hasCanonicalVersion()).isFalse();
        assertThat(GoPath.parse("example.com/x/@v/v1.0.info").hasCanonicalVersion()).isFalse();
        // a version prefix or a commit resolves to whatever upstream answers today: metadata too
        assertThat(GoPath.parse("example.com/x/@v/v1.0.info").isMetadata()).isTrue();
        assertThat(GoPath.parse("example.com/x/@v/v1.info").isMetadata()).isTrue();
        assertThat(GoPath.parse("example.com/x/@v/abcdef123456.info").isMetadata()).isTrue();
        assertThat(GoPath.parse("example.com/x/@v/v1.0.0.info").isMetadata()).isFalse();
        assertThat(GoPath.parse("example.com/x/@v/v2.0.0+incompatible.info").isMetadata()).isFalse();
        assertThat(GoPath.parse("example.com/x/@v/v0.0.0-20200101000000-abcdef123456.info").isMetadata()).isFalse();
        assertThat(GoPath.parse("example.com/x/@v/v1.0.mod").isMetadata()).isFalse();
    }

    @Test
    void latestAndBareVersion() {
        GoPath latest = GoPath.parse("example.com/x/@latest");
        assertThat(latest.kind()).isEqualTo(GoPath.Kind.LATEST);
        assertThat(latest.module()).isEqualTo("example.com/x");
        assertThat(latest.isMetadata()).isTrue();
        GoPath version = GoPath.parse("example.com/x/@v/v1.0.0");
        assertThat(version.kind()).isEqualTo(GoPath.Kind.VERSION);
        assertThat(version.version()).isEqualTo("v1.0.0");
    }

    @Test
    void sumdbPaths() {
        GoPath supported = GoPath.parse("sumdb/sum.golang.org/supported");
        assertThat(supported.kind()).isEqualTo(GoPath.Kind.SUMDB_SUPPORTED);
        assertThat(supported.sumdbName()).isEqualTo("sum.golang.org");
        GoPath lookup = GoPath.parse("sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2");
        assertThat(lookup.kind()).isEqualTo(GoPath.Kind.SUMDB);
        assertThat(lookup.sumdbRest()).isEqualTo("lookup/rsc.io/quote@v1.5.2");
        assertThat(lookup.isMetadata()).isFalse();
        assertThat(GoPath.parse("sumdb/sum.golang.org/tile/8/0/x123/456.p/3").isMetadata()).isFalse();
        assertThat(GoPath.parse("sumdb/sum.golang.org/latest").isMetadata()).isTrue();
        assertThat(GoPath.parse("sumdb/sum.golang.google.cn/lookup/x@v1").sumdbName()).isEqualTo("sum.golang.google.cn");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sumdb", "sumdb/", "sumdb/sum.golang.org", "sumdb/sum.golang.org/", "sumdb//lookup/x", "sumdb/Sum.golang.org/latest",
            "sumdb/sum.golang.org/../latest", "sumdb/sum.golang.org/lookup//x", "sumdb/sum.golang.org/./latest", "sumdb/-bad-/latest",
            "sumdb/bad_host/latest", "@v/list", "example.com/x/@v/", "example.com/x/@v/a/b.info", "example.com/x", "example.com/x/@foo",
            "@latest", "example.com/x/@v/list/", "example.com/x/@latest/", "example.com/x/@v/v1.0.0/..info"})
    void shapesItDoesNotKnowAreUnknown(String path) {
        assertThat(GoPath.parse(path).kind()).as(path).isEqualTo(GoPath.Kind.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"github.com/BurntSushi/toml/@v/list", "example.com/!1/@v/list", "nodot/@v/list", "example.com/x/@v/v1.0.0 .info",
            "example.com/x/@v/v1.0.0!.info", "example.com/x/@v/v1.0.0%2f.info", "example.com/x/@v/v1.0.0-é.info",
            "example.com/x/v1/@v/list", "-example.com/x/@latest", "example.com/x/@v/#.info"})
    void malformedPathsAre400(String path) {
        assertThatThrownBy(() -> GoPath.parse(path)).as(path).isInstanceOf(GoError.class)
                .satisfies(e -> assertThat(((GoError) e).status().value()).isEqualTo(400));
    }

    @Test
    void staticPathBuilders() {
        assertThat(GoPath.listPath("example.com/x")).isEqualTo("example.com/x/@v/list");
        assertThat(GoPath.latestPath("example.com/x")).isEqualTo("example.com/x/@latest");
        assertThat(GoPath.versionsPrefix("example.com/x")).isEqualTo("example.com/x/@v/");
        assertThat(GoPath.filePath("example.com/x", "v1.0.0", "mod")).isEqualTo("example.com/x/@v/v1.0.0.mod");
    }
}
