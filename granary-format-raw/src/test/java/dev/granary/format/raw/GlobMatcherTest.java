package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GlobMatcherTest {

    @Test
    void doubleStarCrossesSegmentsAndSingleStarDoesNot() {
        GlobMatcher m = GlobMatcher.of("**/PACKAGES*");
        assertThat(m.matches("PACKAGES")).isTrue();
        assertThat(m.matches("PACKAGES.gz")).isTrue();
        assertThat(m.matches("bin/windows/contrib/4.5/PACKAGES.rds")).isTrue();
        assertThat(m.matches("src/contrib/PACKAGES")).isTrue();
        assertThat(m.matches("src/contrib/PACKAGES/x")).isFalse();
        assertThat(m.matches("src/contrib/notPACKAGES")).isFalse();

        GlobMatcher single = GlobMatcher.of("packages/*");
        assertThat(single.matches("packages/jquery")).isTrue();
        assertThat(single.matches("packages/search/jquery")).isFalse();
        assertThat(single.matches("packages")).isFalse();
    }

    @Test
    void trailingDoubleStarMatchesEverythingBelow() {
        GlobMatcher m = GlobMatcher.of("src/contrib/Meta/**");
        assertThat(m.matches("src/contrib/Meta/current.rds")).isTrue();
        assertThat(m.matches("src/contrib/Meta/a/b/c")).isTrue();
        assertThat(m.matches("src/contrib/Meta/")).isTrue();
        assertThat(m.matches("src/contrib/Meta")).isFalse();
        assertThat(m.matches("src/contrib/Metadata/x")).isFalse();
    }

    @Test
    void prefixGlobsStayInTheirSegment() {
        GlobMatcher m = GlobMatcher.of("lts*, nightly*, */cabal.config");
        assertThat(m.matches("lts-22.0")).isTrue();
        assertThat(m.matches("lts")).isTrue();
        assertThat(m.matches("nightly-2026-01-01")).isTrue();
        assertThat(m.matches("lts-22.0/cabal.config")).isTrue();
        assertThat(m.matches("lts-22.0/other")).isFalse();
        assertThat(m.matches("a/b/cabal.config")).isFalse();
    }

    @Test
    void questionMarkMatchesOneCharacterOfASegment() {
        GlobMatcher m = GlobMatcher.of("file?.txt");
        assertThat(m.matches("file1.txt")).isTrue();
        assertThat(m.matches("file12.txt")).isFalse();
        assertThat(m.matches("file/.txt")).isFalse();
    }

    @Test
    void regexCharactersInGlobsAreLiteral() {
        GlobMatcher m = GlobMatcher.of("PACKAGES.gz, a+b(c).txt, [x].md, a$b, a|b, a^b");
        assertThat(m.matches("PACKAGES.gz")).isTrue();
        assertThat(m.matches("PACKAGESxgz")).isFalse();
        assertThat(m.matches("a+b(c).txt")).isTrue();
        assertThat(m.matches("ab(c).txt")).isFalse();
        assertThat(m.matches("[x].md")).isTrue();
        assertThat(m.matches("x.md")).isFalse();
        assertThat(m.matches("a$b")).isTrue();
        assertThat(m.matches("a|b")).isTrue();
        assertThat(m.matches("a")).isFalse();
        assertThat(m.matches("a^b")).isTrue();
    }

    @Test
    void commaListsAreTrimmedAndBlanksIgnored() {
        GlobMatcher m = GlobMatcher.of(" packages , ,packages/search/*,, ");
        assertThat(m.globs()).containsExactly("packages", "packages/search/*");
        assertThat(m.matches("packages")).isTrue();
        assertThat(m.matches("packages/search/jq")).isTrue();
        assertThat(m.toString()).isEqualTo("packages,packages/search/*");
    }

    @Test
    void emptyMatchersMatchNothing() {
        assertThat(GlobMatcher.of("")).isSameAs(GlobMatcher.NONE);
        assertThat(GlobMatcher.of((String) null)).isSameAs(GlobMatcher.NONE);
        assertThat(GlobMatcher.of(List.of(" ", ""))).isSameAs(GlobMatcher.NONE);
        assertThat(GlobMatcher.NONE.isEmpty()).isTrue();
        assertThat(GlobMatcher.NONE.matches("anything")).isFalse();
        assertThat(GlobMatcher.of("**").matches(null)).isFalse();
    }

    @Test
    void leadingSlashesAreStrippedAndMatchingIsCaseSensitive() {
        GlobMatcher m = GlobMatcher.of("/root.json");
        assertThat(m.matches("root.json")).isTrue();
        assertThat(m.matches("ROOT.json")).isFalse();
        assertThat(GlobMatcher.toRegex("**/x")).isEqualTo("^(?:.*/)?x$");
        assertThat(GlobMatcher.toRegex("a**b")).isEqualTo("^a.*b$");
        assertThat(GlobMatcher.toRegex("a*b")).isEqualTo("^a[^/]*b$");
    }

    @Test
    void doubleStarAloneMatchesEverything() {
        GlobMatcher m = GlobMatcher.of("**");
        assertThat(m.matches("")).isTrue();
        assertThat(m.matches("a/b/c")).isTrue();
    }
}
