package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PypiMergerTest {

    private static PypiFile file(String name, String sha) {
        return PypiFile.of(name, "https://a/" + name, sha);
    }

    @Test
    void namesAreUnionedByNormalisedNameFirstSpellingWins() {
        List<String> merged = PypiMerger.mergeNames(List.of(
                List.of("acme-demo", "Django"),
                List.of("django", "six", "Acme_Demo", "zope.interface"),
                List.of()));
        assertThat(merged).containsExactly("acme-demo", "Django", "six", "zope.interface");
        assertThat(PypiMerger.mergeNames(List.of())).isEmpty();
    }

    @Test
    void filesAreUnionedByNameEarlierMemberWins() {
        PypiProject hosted = PypiProject.of("six", List.of(file("six-2.0.0.tar.gz", "hosted"), file("six-1.0.0.tar.gz", "h1")));
        PypiProject upstream = new PypiProject("six", "1.3", List.of(
                file("six-1.0.0.tar.gz", "upstream").withYanked("old"),
                file("six-1.5.0-py2.py3-none-any.whl", "u15").withYanked("")),
                List.of("1.0.0", "1.5.0"), Map.of());
        PypiProject merged = PypiMerger.mergeProjects("six", List.of(hosted, upstream));
        assertThat(merged.name()).isEqualTo("six");
        assertThat(merged.apiVersion()).isEqualTo("1.3");
        assertThat(merged.files()).extracting(PypiFile::filename)
                .containsExactly("six-2.0.0.tar.gz", "six-1.0.0.tar.gz", "six-1.5.0-py2.py3-none-any.whl");
        assertThat(merged.files().get(1).sha256()).contains("h1");
        assertThat(merged.files().get(1).isYanked()).isFalse();
        assertThat(merged.files().get(2).isYanked()).isTrue();
        assertThat(merged.versions()).containsExactly("1.0.0", "1.5.0", "2.0.0");
        assertThat(merged.extra()).isEmpty();
    }

    @Test
    void mergingASinglePageKeepsIt() {
        PypiProject only = PypiProject.of("x", List.of(file("x-1.0.tar.gz", "a")));
        PypiProject merged = PypiMerger.mergeProjects("x", List.of(only));
        assertThat(merged.files()).isEqualTo(only.files());
        assertThat(merged.versions()).containsExactly("1.0");
        assertThat(PypiMerger.mergeProjects("x", List.of()).files()).isEmpty();
        assertThat(PypiMerger.mergeProjects("x", List.of()).versions()).isNull();
    }

    @Test
    void apiVersionsCompareNumerically() {
        assertThat(PypiMerger.compareApiVersion("1.1", "1.10")).isNegative();
        assertThat(PypiMerger.compareApiVersion("1.10", "1.9")).isPositive();
        assertThat(PypiMerger.compareApiVersion("1.1", "1.1")).isZero();
        assertThat(PypiMerger.compareApiVersion("2", "1.9")).isPositive();
        assertThat(PypiMerger.compareApiVersion("x", "1.0")).isNegative();
    }
}
