package dev.granary.format.rubygems;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RubygemsNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"rake", "net-http", "acme_hello", "A", "rails4", "a.b", "-x", "x-", "_x"})
    void acceptsGemNames(String name) {
        assertThat(GemNames.isValidName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "_", "-", "123", "a b", "a/b", "a\\b", "ünïcödé", "a:b", "a%2Fb", "a\nb"})
    void refusesWhatCannotBeAGemName(String name) {
        assertThat(GemNames.isValidName(name)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-x", "_x", ".x", "-", "123", ""})
    void newGemsCannotTakeNamesRubyGemsRefuses(String name) {
        // Gem::SpecificationPolicy: "cannot begin with a period, dash, or underscore", "must include at least one letter"
        assertThat(GemNames.isPublishableName(name)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rake", "x-", "x_", "x.y", "1x", "Rails4"})
    void newGemsCanTakeOrdinaryNames(String name) {
        assertThat(GemNames.isPublishableName(name)).isTrue();
    }

    @Test
    void refusesNullAndOverlongNames() {
        assertThat(GemNames.isValidName(null)).isFalse();
        assertThat(GemNames.isValidName("a".repeat(129))).isFalse();
        assertThat(GemNames.isValidName("a".repeat(128))).isTrue();
    }

    @Test
    void buildsFullNamesAndPaths() {
        assertThat(GemNames.fullName("rake", "13.4.2", "ruby")).isEqualTo("rake-13.4.2");
        assertThat(GemNames.fullName("nokogiri", "1.16.0", "x86_64-linux")).isEqualTo("nokogiri-1.16.0-x86_64-linux");
        assertThat(GemNames.versionKey("1.16.0", "ruby")).isEqualTo("1.16.0");
        assertThat(GemNames.versionKey("1.16.0", "java")).isEqualTo("1.16.0-java");
        assertThat(GemNames.gemPath("rake-13.4.2")).isEqualTo("gems/rake-13.4.2.gem");
        assertThat(GemNames.quickSpecPath("rake-13.4.2")).isEqualTo("quick/Marshal.4.8/rake-13.4.2.gemspec.rz");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "rake-13.4.2|rake|13.4.2|ruby",
            "net-http-0.4.1|net-http|0.4.1|ruby",
            "nokogiri-1.16.0-x86_64-linux|nokogiri|1.16.0|x86_64-linux",
            "nokogiri-1.16.0-x86_64-linux-gnu|nokogiri|1.16.0|x86_64-linux-gnu",
            "ffi-1.17.0-x64-mingw-ucrt|ffi|1.17.0|x64-mingw-ucrt",
            "foo-2fa-1.0|foo-2fa|1.0|ruby",
            "rails-7.1.0.rc1|rails|7.1.0.rc1|ruby",
            "jruby-openssl-0.14.2-java|jruby-openssl|0.14.2|java",
            "a-1|a|1|ruby",
    })
    void parsesFullNames(String fullName, String name, String version, String platform) {
        GemNames.FullName parsed = GemNames.parse(fullName).orElseThrow();
        assertThat(parsed.name()).isEqualTo(name);
        assertThat(parsed.version()).isEqualTo(version);
        assertThat(parsed.platform()).isEqualTo(platform);
        assertThat(parsed.fullName()).isEqualTo(fullName);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rake", "rake-", "-1.0", "rake-x", "../rake-1.0", "rake-1.0/..", "rake 1.0", "", "..-1.0",
            "rake-1.0-", "rake-1.0-x/y"})
    void refusesWhatIsNotAFullName(String fullName) {
        assertThat(GemNames.parse(fullName)).isEmpty();
    }

    @Test
    void splitsWithAKnownName() {
        assertThat(GemNames.split("foo", "foo-2fa-1.0")).isEmpty(); // "2fa-1.0" is no version and platform
        assertThat(GemNames.split("foo-2fa", "foo-2fa-1.0").orElseThrow().version()).isEqualTo("1.0");
        assertThat(GemNames.split("rake", "rails-1.0")).isEmpty();
        assertThat(GemNames.split("..", "..-1.0")).isEmpty();
    }

    @Test
    void safeFullNames() {
        assertThat(GemNames.isSafeFullName("rake-13.4.2")).isTrue();
        assertThat(GemNames.isSafeFullName("../x-1")).isFalse();
        assertThat(GemNames.isSafeFullName(".hidden-1")).isFalse();
        assertThat(GemNames.isSafeFullName("a/b-1")).isFalse();
        assertThat(GemNames.isSafeFullName(null)).isFalse();
    }
}
