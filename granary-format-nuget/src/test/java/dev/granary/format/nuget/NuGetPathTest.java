package dev.granary.format.nuget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NuGetPathTest {

    @ParameterizedTest
    @CsvSource({
            "'', SERVICE_INDEX",
            "index.json, SERVICE_INDEX",
            "v3/flatcontainer/newtonsoft.json/index.json, FLAT_INDEX",
            "v3/flatcontainer/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg, PACKAGE",
            "v3/flatcontainer/newtonsoft.json/13.0.1/newtonsoft.json.nuspec, NUSPEC",
            "v3/flatcontainer/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.snupkg, SYMBOLS",
            "v3/registration/newtonsoft.json/index.json, REGISTRATION_INDEX",
            "v3/registration/newtonsoft.json/13.0.1.json, REGISTRATION_LEAF",
            "v3/query, SEARCH",
            "v2/package, PUBLISH",
            "v2/package/, PUBLISH",
            "v2/package/Acme.Lib/1.0.0, PACKAGE_VERSION",
            "v2/symbolpackage, SYMBOL_PUBLISH",
            "v2/symbolpackage/, SYMBOL_PUBLISH",
            "v3/flatcontainer/newtonsoft.json/13.0.1/readme.txt, UNKNOWN",
            "v3/flatcontainer/newtonsoft.json, UNKNOWN",
            "v3/registration/newtonsoft.json/index.json/extra, UNKNOWN",
            "v3/registration/newtonsoft.json/.json, UNKNOWN",
            "v3/something/else, UNKNOWN",
            "v2/package/only-id, UNKNOWN",
            "api/v2/package, UNKNOWN",
            "robots.txt, UNKNOWN"
    })
    void classifiesPaths(String path, NuGetPath.Kind kind) {
        assertThat(NuGetPath.parse(path).kind()).isEqualTo(kind);
    }

    @Test
    void lowerCasesIdsButKeepsVersionsAsSent() {
        NuGetPath p = NuGetPath.parse("v3/flatcontainer/Newtonsoft.Json/13.0.1-Beta/Newtonsoft.Json.13.0.1-Beta.nupkg");
        assertThat(p.id()).isEqualTo("newtonsoft.json");
        assertThat(p.version()).isEqualTo("13.0.1-Beta");
        assertThat(p.fileName()).isEqualTo("newtonsoft.json.13.0.1-beta.nupkg");

        NuGetPath leaf = NuGetPath.parse("v3/registration/Acme.Lib/2.0.0-rc.1.json");
        assertThat(leaf.id()).isEqualTo("acme.lib");
        assertThat(leaf.version()).isEqualTo("2.0.0-rc.1");

        NuGetPath delete = NuGetPath.parse("v2/package/Acme.Lib/1.0");
        assertThat(delete.id()).isEqualTo("acme.lib");
        assertThat(delete.version()).isEqualTo("1.0");
    }

    @Test
    void toleratesLeadingSlashesAndNull() {
        assertThat(NuGetPath.parse("/index.json").kind()).isEqualTo(NuGetPath.Kind.SERVICE_INDEX);
        assertThat(NuGetPath.parse(null).kind()).isEqualTo(NuGetPath.Kind.SERVICE_INDEX);
        assertThat(NuGetPath.parse("//v3/query").kind()).isEqualTo(NuGetPath.Kind.SEARCH);
    }

    @Test
    void indexDocumentsAreMutableAndPackagesAreNot() {
        assertThat(NuGetPath.parse("index.json").isMutable()).isTrue();
        assertThat(NuGetPath.parse("v3/flatcontainer/a/index.json").isMutable()).isTrue();
        assertThat(NuGetPath.parse("v3/registration/a/index.json").isMutable()).isTrue();
        assertThat(NuGetPath.parse("v3/registration/a/1.0.0.json").isMutable()).isTrue();
        assertThat(NuGetPath.parse("v3/query").isMutable()).isTrue();
        assertThat(NuGetPath.parse("v3/flatcontainer/a/1.0.0/a.1.0.0.nupkg").isMutable()).isFalse();
        assertThat(NuGetPath.parse("v3/flatcontainer/a/1.0.0/a.nuspec").isMutable()).isFalse();
    }

    @Test
    void writeEndpointsAreRecognised() {
        assertThat(NuGetPath.parse("v2/package").isWrite()).isTrue();
        assertThat(NuGetPath.parse("v2/package/a/1.0").isWrite()).isTrue();
        assertThat(NuGetPath.parse("v2/symbolpackage").isWrite()).isTrue();
        assertThat(NuGetPath.parse("v3/query").isWrite()).isFalse();
    }

    @Test
    void layoutProducesLowerCaseStoragePathsAndUrls() {
        String base = "http://host/repository/nuget";
        assertThat(NuGetLayout.nupkgPath("Acme.Lib", "1.0.0-Beta")).isEqualTo("acme.lib/1.0.0-beta/acme.lib.1.0.0-beta.nupkg");
        assertThat(NuGetLayout.nuspecPath("Acme.Lib", "1.0.0")).isEqualTo("acme.lib/1.0.0/acme.lib.nuspec");
        assertThat(NuGetLayout.snupkgPath("Acme.Lib", "1.0.0")).isEqualTo("acme.lib/1.0.0/acme.lib.1.0.0.snupkg");
        assertThat(NuGetLayout.versionPrefix("Acme.Lib", "1.0.0")).isEqualTo("acme.lib/1.0.0/");
        assertThat(NuGetLayout.flatIndexCachePath("Acme.Lib")).isEqualTo("acme.lib/index.json");
        assertThat(NuGetLayout.registrationCachePath("Acme.Lib")).isEqualTo("v3/registration/acme.lib/index.json");
        assertThat(NuGetLayout.registrationPageCachePath("Acme.Lib", 2)).isEqualTo("v3/registration/acme.lib/page-2.json");
        assertThat(NuGetLayout.packageContentUrl(base, "Acme.Lib", "1.0.0"))
                .isEqualTo(base + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.1.0.0.nupkg");
        assertThat(NuGetLayout.registrationIndexUrl(base, "Acme.Lib")).isEqualTo(base + "/v3/registration/acme.lib/index.json");
        assertThat(NuGetLayout.registrationLeafUrl(base, "Acme.Lib", "1.0.0")).isEqualTo(base + "/v3/registration/acme.lib/1.0.0.json");
        assertThat(NuGetLayout.registrationPageUrl(base, "Acme.Lib", "1.0.0", "2.0.0"))
                .isEqualTo(base + "/v3/registration/acme.lib/index.json#page/1.0.0/2.0.0");
        assertThat(NuGetLayout.flatIndexUrl(base, "Acme.Lib")).isEqualTo(base + "/v3/flatcontainer/acme.lib/index.json");
        assertThat(NuGetLayout.nuspecUrl(base, "Acme.Lib", "1.0.0")).isEqualTo(base + "/v3/flatcontainer/acme.lib/1.0.0/acme.lib.nuspec");
    }
}
