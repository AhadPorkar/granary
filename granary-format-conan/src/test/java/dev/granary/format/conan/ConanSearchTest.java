package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConanSearchTest {

    @ParameterizedTest
    @CsvSource({
            "zlib, zlib/1.3.1, true",
            "zlib*, zlib/1.3.1, true",
            "zlib/*, zlib/1.3.1, true",
            "zlib/1.*, zlib/1.3.1, true",
            "zlib/1.3.1, zlib/1.3.1, true",
            "*, zlib/1.3.1, true",
            "*, acme/1.0@team/stable, true",
            "z?ib, zlib/1.3.1, true",
            "[xyz]lib, zlib/1.3.1, true",
            "[!a]lib, zlib/1.3.1, true",
            "[!z]lib, zlib/1.3.1, false",
            "lib, zlib/1.3.1, false",
            "zli, zlib/1.3.1, false",
            "zlib/2.*, zlib/1.3.1, false",
            "openssl, zlib/1.3.1, false",
            "acme/*@team/stable, acme/1.0@team/stable, true",
            "acme/*@team/*, acme/1.0@team/stable, true",
            "acme/*@other/*, acme/1.0@team/stable, false",
            "acme/1.0@team, acme/1.0@team/stable, true",
            "ZLIB, zlib/1.3.1, true",
            "zlib/1.3.1@_/_, zlib/1.3.1, true",
            "[a-z]lib, zlib/1.3.1, true",
            "[0-9]lib, zlib/1.3.1, false",
    })
    void wildcardPatterns(String pattern, String reference, boolean expected) {
        assertThat(ConanSearch.matches(pattern, reference, true)).as(pattern + " ~ " + reference).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "**, zlib/1.3.1, true",
            "*?, zlib/1.3.1, true",
            "?, zlib/1.3.1, false",
            "????, zlib/1.3.1, true",
            "z*b, zlib/1.3.1, true",
            "z**b*, zlib/1.3.1, true",
            "*1.3.1, zlib/1.3.1, true",
            "*/*, zlib/1.3.1, true",
            "*/*/*, zlib/1.3.1, false",
            "z?*?b, zlib/1.3.1, true",
            "z?*??b, zlib/1.3.1, false",
            "[!A-Z]lib, zlib/1.3.1, false",
            "[A-Z]LIB, zlib/1.3.1, true",
            "[a-c-z]lib, zlib/1.3.1, true",
            "[a-c-]lib, -lib/1.0, true",
    })
    void starsSetsAndSingleCharacters(String pattern, String reference, boolean expected) {
        assertThat(ConanSearch.matches(pattern, reference, true)).as(pattern + " ~ " + reference).isEqualTo(expected);
    }

    @Test
    void wildcardHeavyPatternsDoNotBacktrackExponentially() {
        // with a backtracking regular expression the first pattern takes hours against this reference
        String reference = "boost-headers-only-something-extended-name/1.84.0@company/stable";
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertThat(ConanSearch.matches("*?".repeat(40) + "X", reference, true)).isFalse();
            assertThat(ConanSearch.matches("*e".repeat(30) + "*X", reference, true)).isFalse();
            assertThat(ConanSearch.matches("*?".repeat(20), reference, true)).isTrue();
            assertThat(ConanSearch.matches("*" + "[a-z]*".repeat(25) + "@company/stable", reference, true)).isTrue();
        });
    }

    @Test
    void caseSensitiveSearch() {
        assertThat(ConanSearch.matches("ZLIB", "zlib/1.3.1", false)).isFalse();
        assertThat(ConanSearch.matches("zlib", "zlib/1.3.1", false)).isTrue();
        assertThat(ConanSearch.matches("Poco", "poco/1.13.3", true)).isTrue();
    }

    @Test
    void emptyPatternMatchesEverything() {
        assertThat(ConanSearch.matches(null, "zlib/1.3.1", true)).isTrue();
        assertThat(ConanSearch.matches("  ", "zlib/1.3.1", true)).isTrue();
    }

    @Test
    void regexCharactersInPatternsAreLiteral() {
        assertThat(ConanSearch.matches("a+b", "a+b/1.0", true)).isTrue();
        assertThat(ConanSearch.matches("a+b", "aab/1.0", true)).isFalse();
        assertThat(ConanSearch.matches("a.b", "axb/1.0", true)).isFalse();
        assertThat(ConanSearch.matches("(a|b)", "a/1.0", true)).isFalse();
        assertThat(ConanSearch.matches("a\\E.*\\Q", "a/1.0", true)).isFalse();
        // an unclosed bracket is a literal, never a regex syntax error
        assertThat(ConanSearch.matches("[abc", "[abc/1.0", true)).isTrue();
        assertThat(ConanSearch.matches("[abc", "a/1.0", true)).isFalse();
        // sets that would be regex syntax errors are handled too
        assertThat(ConanSearch.matches("[]x", "[]x/1.0", true)).isTrue();
        assertThat(ConanSearch.matches("[a[b]x", "[x/1.0", true)).isTrue();
        assertThat(ConanSearch.matches("[&&]x", "&x/1.0", true)).isTrue();
        assertThat(ConanSearch.matches("[\\]x", "\\x/1.0", true)).isTrue();
        assertThat(ConanSearch.matches("[z-a]x", "[z-a]x/1.0", true)).isTrue();
        assertThat(ConanSearch.matches("[a-]x", "-x/1.0", true)).isTrue();
    }

    @Test
    void prefixesAreCutAtSeparators() {
        assertThat(ConanSearch.prefixes("acme/1.0@team/stable"))
                .containsExactly("acme", "acme/", "acme/1.0", "acme/1.0@", "acme/1.0@team", "acme/1.0@team/",
                        "acme/1.0@team/stable");
        assertThat(ConanSearch.prefixes("")).isEmpty();
    }

    @Test
    void canonicalDropsArtifactoryPlaceholders() {
        assertThat(ConanSearch.canonical("zlib/1.3.1@_/_")).isEqualTo("zlib/1.3.1");
        assertThat(ConanSearch.canonical("zlib/1.3.1@_")).isEqualTo("zlib/1.3.1");
        assertThat(ConanSearch.canonical("acme/1.0@team/stable")).isEqualTo("acme/1.0@team/stable");
        assertThat(ConanSearch.canonical(null)).isEmpty();
    }

    @Test
    void parsesAndMergesResults() {
        List<String> upstream = ConanSearch.parseResults(json("{\"results\":[\"zlib/1.3.1@_/_\",\"zlib/1.2.13\",\"\",7]}"));
        assertThat(upstream).containsExactly("zlib/1.3.1", "zlib/1.2.13");
        assertThat(ConanSearch.parseResults(json("{}"))).isEmpty();
        assertThat(ConanSearch.parseResults(json("{\"results\":\"zlib\"}"))).isEmpty();
        assertThat(ConanSearch.parseResults(null)).isEmpty();

        List<String> merged = ConanSearch.merge(List.of(List.of("zlib/1.3.1", "acme/1.0"), upstream));
        assertThat(merged).containsExactly("acme/1.0", "zlib/1.2.13", "zlib/1.3.1");
    }

    @Test
    void resultsDocument() {
        assertThat(ConanSearch.resultsDocument(List.of("a/1", "b/2")).toString()).isEqualTo("{\"results\":[\"a/1\",\"b/2\"]}");
        assertThat(ConanSearch.resultsDocument(List.of()).toString()).isEqualTo("{\"results\":[]}");
    }
}
