package dev.granary.format.pub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PubNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"path", "flutter_bloc", "_internal", "a", "x86_64", "build_runner2"})
    void publishableNames(String name) {
        assertThat(PubNames.isValid(name)).isTrue();
        assertThat(PubNames.publishProblem(name)).isEmpty();
    }

    @Test
    void upperCaseCanBeReadButNotPublished() {
        assertThat(PubNames.isValid("RxDart")).isTrue();
        assertThat(PubNames.publishProblem("RxDart")).hasValueSatisfying(p -> assertThat(p).contains("lower-case"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1abc", "foo-bar", "foo.bar", "foo bar", "ü", "foo/bar", "..", "foo\\bar"})
    void nonIdentifiersAreRefusedEverywhere(String name) {
        assertThat(PubNames.isValid(name)).isFalse();
        assertThat(PubNames.publishProblem(name)).isPresent();
    }

    @Test
    void reservedWordsCannotBePublished() {
        for (String word : new String[] {"class", "import", "void", "async", "late", "required", "yield"}) {
            assertThat(PubNames.publishProblem(word)).as(word)
                    .hasValueSatisfying(p -> assertThat(p).contains("reserved word"));
        }
        assertThat(PubNames.publishProblem("classy")).isEmpty();
    }

    @Test
    void lengthIsBounded() {
        assertThat(PubNames.publishProblem("a".repeat(64))).isEmpty();
        assertThat(PubNames.publishProblem("a".repeat(65))).hasValueSatisfying(p -> assertThat(p).contains("longer"));
        assertThat(PubNames.isValid("a".repeat(65))).isFalse();
        assertThat(PubNames.isValid(null)).isFalse();
        assertThat(PubNames.publishProblem(null)).isPresent();
    }
}
