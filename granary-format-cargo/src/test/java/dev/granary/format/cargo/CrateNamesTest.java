package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CrateNamesTest {

    @ParameterizedTest
    @CsvSource({
            "a, 1",
            "ab, 2",
            "abc, 3/a",
            "abcd, ab/cd",
            "serde, se/rd",
            "Serde_JSON, se/rd",
            "A, 1",
            "x-y, 3/x",
            "a-b-c, a-/b-",
    })
    void indexDirectoryFollowsCargo(String name, String dir) {
        assertThat(CrateNames.indexDir(name)).isEqualTo(dir);
    }

    @Test
    void pathsUseTheLowerCasedName() {
        assertThat(CrateNames.indexRelative("Inflector")).isEqualTo("in/fl/inflector");
        assertThat(CrateNames.indexPath("Inflector")).isEqualTo("index/in/fl/inflector");
        assertThat(CrateNames.cratePath("Inflector", "0.11.4")).isEqualTo("crates/inflector/inflector-0.11.4.crate");
        assertThat(CrateNames.cratePath("x", "1.0.0+build.5")).isEqualTo("crates/x/x-1.0.0+build.5.crate");
        assertThat(CrateNames.crateFolder("Inflector")).isEqualTo("crates/inflector/");
        assertThat(CrateNames.metaPath("Inflector")).isEqualTo("meta/inflector.json");
    }

    @Test
    void prefixKeepsTheCaseForTheDownloadMarker() {
        assertThat(CrateNames.prefixOf("Inflector")).isEqualTo("In/fl");
        assertThat(CrateNames.prefixOf("Abc")).isEqualTo("3/A");
    }

    @Test
    void canonicalNamesIgnoreCaseAndSeparators() {
        assertThat(CrateNames.canonical("Foo_Bar-baz")).isEqualTo("foo-bar-baz");
        assertThat(CrateNames.canonical("foo-bar")).isEqualTo(CrateNames.canonical("FOO_BAR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"serde", "a", "Z9", "foo-bar_baz", "x0123456789012345678901234567890123456789012345678901234567890xy"})
    void publishableNames(String name) {
        assertThat(CrateNames.isPublishable(name)).isTrue();
        assertThat(CrateNames.publishProblem(name)).isNull();
    }

    @Test
    void publishProblemsAreExplained() {
        assertThat(CrateNames.publishProblem("")).contains("empty");
        assertThat(CrateNames.publishProblem(null)).contains("empty");
        assertThat(CrateNames.publishProblem("1password")).contains("must start with an ASCII letter");
        assertThat(CrateNames.publishProblem("_private")).contains("must start with an ASCII letter");
        assertThat(CrateNames.publishProblem("-dash")).contains("must start with an ASCII letter");
        assertThat(CrateNames.publishProblem("éclair")).contains("must start with an ASCII letter");
        assertThat(CrateNames.publishProblem("café")).contains("may only contain");
        assertThat(CrateNames.publishProblem("foo.bar")).contains("may only contain");
        assertThat(CrateNames.publishProblem("foo/bar")).contains("may only contain");
        assertThat(CrateNames.publishProblem("foo bar")).contains("may only contain");
        assertThat(CrateNames.publishProblem("a".repeat(65))).contains("longer than 64");
    }

    @ParameterizedTest
    @ValueSource(strings = {"serde", "_private", "1password", "foo-bar", "a"})
    void lookupNamesAreLenient(String name) {
        assertThat(CrateNames.isValidLookup(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "..", ".", "-x", "a/b", "a\\b", "a%2Fb", "café", "a b", "a\u0000"})
    void oddLookupNamesAreRefused(String name) {
        assertThat(CrateNames.isValidLookup(name)).isFalse();
    }

    @Test
    void urlVersionsCannotFormPaths() {
        assertThat(CrateNames.isValidUrlVersion("1.0.0")).isTrue();
        assertThat(CrateNames.isValidUrlVersion("1.0.0-alpha.1+build-5")).isTrue();
        assertThat(CrateNames.isValidUrlVersion("..")).isFalse();
        assertThat(CrateNames.isValidUrlVersion("1..0")).isFalse();
        assertThat(CrateNames.isValidUrlVersion("1.0/../x")).isFalse();
        assertThat(CrateNames.isValidUrlVersion(".1")).isFalse();
        assertThat(CrateNames.isValidUrlVersion("")).isFalse();
        assertThat(CrateNames.isValidUrlVersion(null)).isFalse();
        assertThat(CrateNames.isValidUrlVersion("1.0.0 ")).isFalse();
    }

    @Test
    void nameOfIndexPathRecognisesOnlyRealIndexFiles() {
        assertThat(CrateNames.nameOfIndexPath("index/se/rd/serde")).isEqualTo("serde");
        assertThat(CrateNames.nameOfIndexPath("index/1/a")).isEqualTo("a");
        assertThat(CrateNames.nameOfIndexPath("index/3/a/abc")).isEqualTo("abc");
        assertThat(CrateNames.nameOfIndexPath("index/xx/yy/serde")).isNull();
        assertThat(CrateNames.nameOfIndexPath("index/config.json")).isNull();
        assertThat(CrateNames.nameOfIndexPath("crates/serde/serde-1.0.0.crate")).isNull();
        assertThat(CrateNames.nameOfIndexPath(null)).isNull();
    }

    @Test
    void similarDirectoriesCoverSeparatorSwapsInTheFirstFourCharacters() {
        assertThat(CrateNames.similarIndexDirs("serde")).containsExactly("se/rd");
        assertThat(CrateNames.similarIndexDirs("a-b-c")).containsExactlyInAnyOrder("a-/b-", "a-/b_", "a_/b-", "a_/b_");
        assertThat(CrateNames.similarIndexDirs("ab_cdef")).containsExactlyInAnyOrder("ab/_c", "ab/-c");
        assertThat(CrateNames.similarIndexDirs("a_b")).containsExactly("3/a");
        assertThat(CrateNames.similarIndexDirs("x")).containsExactly("1");
    }
}
