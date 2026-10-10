package dev.granary.format.go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GoModulePathsTest {

    @Test
    void escapesUpperCaseLettersAsBangLower() {
        assertThat(GoModulePaths.escape("github.com/BurntSushi/toml")).isEqualTo("github.com/!burnt!sushi/toml");
        assertThat(GoModulePaths.escape("v1.0.0-RC1")).isEqualTo("v1.0.0-!r!c1");
        assertThat(GoModulePaths.escape("example.com/acme/hello")).isEqualTo("example.com/acme/hello");
        assertThat(GoModulePaths.escape("")).isEmpty();
        assertThatThrownBy(() -> GoModulePaths.escape("example.com/a!b")).isInstanceOf(GoError.class)
                .hasMessageContaining("internal !");
    }

    @Test
    void unescapesBangLowerToUpperCase() {
        assertThat(GoModulePaths.unescape("github.com/!burnt!sushi/toml")).isEqualTo("github.com/BurntSushi/toml");
        assertThat(GoModulePaths.unescape("v1.0.0-!r!c1")).isEqualTo("v1.0.0-RC1");
        assertThat(GoModulePaths.unescape("plain")).isEqualTo("plain");
        for (String path : new String[] {"github.com/!burnt!sushi/toml", "example.com/x", "v0.0.0-20200101000000-abcdef123456"}) {
            assertThat(GoModulePaths.escape(GoModulePaths.unescape(path))).isEqualTo(path);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"github.com/BurntSushi/toml", "example.com/!1x", "example.com/!!x", "example.com/x!", "example.com/!X", "example.com/café"})
    void refusesMalformedEscapes(String escaped) {
        assertThatThrownBy(() -> GoModulePaths.unescape(escaped)).isInstanceOf(GoError.class)
                .satisfies(e -> assertThat(((GoError) e).status().value()).isEqualTo(400));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "example.com", "example.com/acme/hello", "github.com/BurntSushi/toml/v2", "gopkg.in/yaml.v3",
            "gopkg.in/user/pkg.v2", "gopkg.in/pkg.v2-unstable", "golang.org/x/mod", "k8s.io/api", "mvdan.cc/sh/v3",
            "go.uber.org/zap", "example.com/a_b/c~d", "example.com/x/v2/y", "example.com/123/v10", "rsc.io/quote"})
    void acceptsModulePathsTheGoCommandAccepts(String path) {
        GoModulePaths.checkModulePath(path);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "'' | empty string",
            "nodot/x | missing dot in first path element",
            "-example.com/x | leading dash",
            "Example.com/x | invalid char 'E' in first path element",
            "example.com//x | double slash",
            "example.com/x/ | trailing slash",
            "example.com/.x | leading dot in path element",
            "example.com/x. | trailing dot in path element",
            "example.com/x/.. | invalid path element",
            "example.com/../x | invalid path element",
            "example.com/x/con.go | \"con\" disallowed",
            "example.com/x/LPT1 | disallowed as path element",
            "example.com/x~1/y | trailing tilde and digits",
            "example.com/x/v1 | invalid version",
            "example.com/x/v02 | invalid version",
            "example.com/x/v2.1 | invalid version",
            "gopkg.in/yaml | invalid version",
            "gopkg.in/yaml.v02 | invalid version",
            "example.com/a b | invalid char",
            "example.com/x\\y | invalid char",
            "example.com/café | invalid char",
            "example.com/x/@v | invalid char"})
    void refusesModulePathsTheGoCommandRefuses(String path, String problem) {
        assertThatThrownBy(() -> GoModulePaths.checkModulePath(path)).isInstanceOf(GoError.class)
                .hasMessageContaining(problem);
    }

    @ParameterizedTest
    @ValueSource(strings = {"go.mod", "cmd/hello/main.go", "docs/README (draft).md", "a-b_c~d", "café.txt", "naïve/x.go", "LICENSE", "vendor/modules.txt"})
    void acceptsFilePaths(String path) {
        GoModulePaths.checkFilePath(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a//b", "a/", "a/b.", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a|b", "con.txt", "x/nul/y", "a\u0001b"})
    void refusesFilePaths(String path) {
        assertThatThrownBy(() -> GoModulePaths.checkFilePath(path)).isInstanceOf(GoError.class);
    }

    @Test
    void splitsMajorVersionSuffixes() {
        assertThat(GoModulePaths.splitPathVersion("example.com/x")).contains("");
        assertThat(GoModulePaths.splitPathVersion("example.com/x/v2")).contains("/v2");
        assertThat(GoModulePaths.splitPathVersion("example.com/x/v10")).contains("/v10");
        assertThat(GoModulePaths.splitPathVersion("example.com/x/v1")).isEmpty();
        assertThat(GoModulePaths.splitPathVersion("example.com/x/v0")).isEmpty();
        assertThat(GoModulePaths.splitPathVersion("example.com/x/v02")).isEmpty();
        assertThat(GoModulePaths.splitPathVersion("example.com/x/v2.0")).isEmpty();
        assertThat(GoModulePaths.splitPathVersion("example.com/v2x")).contains("");
        assertThat(GoModulePaths.splitPathVersion("gopkg.in/yaml.v3")).contains(".v3");
        assertThat(GoModulePaths.splitPathVersion("gopkg.in/yaml.v0")).contains(".v0");
        assertThat(GoModulePaths.splitPathVersion("gopkg.in/yaml.v3-unstable")).contains(".v3-unstable");
        assertThat(GoModulePaths.splitPathVersion("gopkg.in/yaml")).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @CsvSource({
            "example.com/x, v1.0.0",
            "example.com/x, v0.1.0-beta.1",
            "example.com/x, v0.0.0-20200101000000-abcdef123456",
            "example.com/x, v2.0.0+incompatible",
            "example.com/x/v2, v2.3.4",
            "example.com/x/v2, v2.0.0-rc.1",
            "gopkg.in/yaml.v2, v2.4.0",
            "gopkg.in/yaml.v1, v0.0.0-20200101000000-abcdef123456",
            "gopkg.in/pkg.v3-unstable, v3.0.1"})
    void versionMajorAgreesWithThePath(String path, String version) {
        GoModulePaths.checkPathMajor(path, version);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "example.com/x | v2.0.0 | should be v0 or v1, not v2",
            "example.com/x/v2 | v1.0.0 | should be v2, not v1",
            "example.com/x/v2 | v3.0.0 | should be v2, not v3",
            "example.com/x/v2 | v2.0.0+incompatible | +incompatible suffix not allowed",
            "gopkg.in/yaml.v2 | v2.0.0+incompatible | +incompatible suffix not allowed",
            "gopkg.in/yaml.v2 | v1.0.0 | should be v2, not v1",
            "example.com/x | 1.0.0 | must be a semantic version starting with v",
            "example.com/x | v1.0 | non-canonical version",
            "example.com/x | v1.0.0+build.1 | non-canonical version",
            "example.com/x | latest | must be a semantic version",
            "example.com/x | v1.0.0-01 | must be a semantic version"})
    void versionMajorDisagreementsAreRefused(String path, String version, String problem) {
        assertThatThrownBy(() -> GoModulePaths.checkPathMajor(path, version)).isInstanceOf(GoError.class)
                .hasMessageContaining(problem);
    }

    @Test
    void componentCoordinatesSplitOffTheLastElement() {
        assertThat(GoModulePaths.namespace("github.com/BurntSushi/toml")).isEqualTo("github.com/BurntSushi");
        assertThat(GoModulePaths.name("github.com/BurntSushi/toml")).isEqualTo("toml");
        assertThat(GoModulePaths.namespace("example.com")).isEmpty();
        assertThat(GoModulePaths.name("example.com")).isEqualTo("example.com");
        assertThat(GoModulePaths.namespace("example.com/x/v2")).isEqualTo("example.com/x");
        assertThat(GoModulePaths.name("example.com/x/v2")).isEqualTo("v2");
    }
}
