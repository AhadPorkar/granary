package dev.granary.format.rubygems;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.rubygems.GemPath.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RubygemsPathTest {

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
        "'',                                                ROOT,             -,          -",
        "versions,                                          VERSIONS,         -,          -",
        "names,                                             NAMES,            -,          -",
        "info/rake,                                         INFO,             rake,       -",
        "info/net-http,                                     INFO,             net-http,   -",
        "specs.4.8.gz,                                      SPECS,            -,          -",
        "latest_specs.4.8.gz,                               LATEST_SPECS,     -,          -",
        "prerelease_specs.4.8.gz,                           PRERELEASE_SPECS, -,          -",
        "quick/Marshal.4.8/rake-13.4.2.gemspec.rz,          QUICK_SPEC,       -,          rake-13.4.2",
        "gems/rake-13.4.2.gem,                              GEM,              -,          rake-13.4.2",
        "gems/nokogiri-1.16.0-x86_64-linux.gem,             GEM,              -,          nokogiri-1.16.0-x86_64-linux",
        "api/v1/gems,                                       API_GEMS,         -,          -",
        "api/v1/gems/,                                      API_GEMS,         -,          -",
        "api/v1/gems/yank,                                  API_YANK,         -,          -",
        "api/v1/gems/unyank,                                API_UNYANK,       -,          -",
        "api/v1/api_key,                                    API_KEY,          -,          -",
        "api/v1/api_key.json,                               API_KEY,          -,          -",
        "api/v1/api_key.yaml,                               API_KEY,          -,          -",
        "api/v1/gems/rake.json,                             API_GEM,          rake,       -",
        "api/v1/versions/rake.json,                         API_VERSIONS,     rake,       -",
        "api/v1/versions/rake/latest.json,                  API_LATEST,       rake,       -",
        "api/v1/dependencies,                               API_DEPENDENCIES, -,          -",
        "api/v1/dependencies.json,                          API_DEPENDENCIES, -,          -",
    })
    void recognisesEveryEndpoint(String path, Kind kind, String name, String fullName) {
        GemPath parsed = GemPath.parse(path);
        assertThat(parsed.kind()).isEqualTo(kind);
        assertThat(parsed.path()).isEqualTo(path);
        assertThat(parsed.name()).isEqualTo(name);
        assertThat(parsed.fullName()).isEqualTo(fullName);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../versions", "info/../versions", "info/./rake", "gems\\rake-1.gem", "/versions",
        "info//rake", "versions\n", "info/ra\u007fke", "gems/..", "."})
    void refusesImplausiblePaths(String path) {
        assertThat(GemPath.parse(path).kind()).isEqualTo(Kind.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"info/", "info/123", "info/a b", "info/a/b", "gems/.gem", "gems/a/b-1.gem",
        "gems/rake.gem.tmp", "quick/Marshal.4.8/.gemspec.rz", "quick/Marshal.4.7/rake-1.gemspec.rz",
        "api/v1/gems/.json", "api/v1/versions/a b.json", "api/v1/versions//latest.json", "api/v2/gems",
        "specs.4.8", "versions.gz", "favicon.ico", "api/v1/gems/rake"})
    void treatsEverythingElseAsUnknown(String path) {
        Kind kind = GemPath.parse(path).kind();
        assertThat(kind).isIn(Kind.UNKNOWN, Kind.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"versions", "names", "info/rake", "specs.4.8.gz", "latest_specs.4.8.gz",
        "prerelease_specs.4.8.gz", "api/v1/gems/rake.json", "api/v1/versions/rake.json", "api/v1/versions/rake/latest.json"})
    void indexesAreMetadata(String path) {
        assertThat(GemPath.parse(path).isMetadata()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gems/rake-13.4.2.gem", "quick/Marshal.4.8/rake-13.4.2.gemspec.rz", "api/v1/gems",
        "api/v1/api_key", "", "unknown", "../x"})
    void filesAndEndpointsAreNot(String path) {
        assertThat(GemPath.parse(path).isMetadata()).isFalse();
    }

    @org.junit.jupiter.api.Test
    void nullIsTheRoot() {
        assertThat(GemPath.parse(null).kind()).isEqualTo(Kind.ROOT);
    }
}
