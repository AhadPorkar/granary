package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PypiVersionTest {

    @ParameterizedTest
    @CsvSource({
            "1.0, 1.0",
            "v1.0, 1.0",
            "1.0.0, 1.0.0",
            "01.02, 1.2",
            "1!2.0, 1!2.0",
            "1.0a1, 1.0a1",
            "1.0-alpha-1, 1.0a1",
            "1.0alpha, 1.0a0",
            "1.0.beta.2, 1.0b2",
            "1.0rc1, 1.0rc1",
            "1.0c1, 1.0rc1",
            "1.0pre1, 1.0rc1",
            "1.0.post1, 1.0.post1",
            "1.0-1, 1.0.post1",
            "1.0.post, 1.0.post0",
            "1.0rev2, 1.0.post2",
            "1.0.dev3, 1.0.dev3",
            "1.0-dev, 1.0.dev0",
            "1.0+ubuntu-1, 1.0+ubuntu.1",
            "1.0+CPU, 1.0+cpu",
            "1.0A1.POST2.DEV3, 1.0a1.post2.dev3",
            "' 2.0 ', 2.0"})
    void normalises(String input, String expected) {
        assertThat(PypiVersion.parse(input).toString()).isEqualTo(expected);
        assertThat(PypiVersion.isValid(input)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "1.0.", ".1", "1.0-", "1.0+", "1.0+ubuntu!", "1..0", "latest", "1.0 beta", "1.0a1b2",
            "1.0.post1.post2", "99999999999999999999"})
    void rejectsNonVersions(String input) {
        assertThat(PypiVersion.tryParse(input)).isEmpty();
        assertThat(PypiVersion.isValid(input)).isFalse();
        assertThatThrownBy(() -> PypiVersion.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullIsNotAVersion() {
        assertThat(PypiVersion.tryParse(null)).isEmpty();
    }

    @Test
    void ordersLikePep440() {
        List<String> expected = List.of(
                "1.0.dev1", "1.0a1", "1.0a2.dev1", "1.0a2", "1.0b1", "1.0rc1", "1.0", "1.0+local", "1.0.post1.dev1",
                "1.0.post1", "1.0.1", "1.1", "1.10", "2.0", "1!0.1");
        for (int i = 0; i + 1 < expected.size(); i++) {
            PypiVersion a = PypiVersion.parse(expected.get(i));
            PypiVersion b = PypiVersion.parse(expected.get(i + 1));
            assertThat(a.compareTo(b)).as("%s < %s", a, b).isNegative();
            assertThat(b.compareTo(a)).as("%s > %s", b, a).isPositive();
        }
        assertThat(PypiVersion.sort(List.of("2.0", "1.0.dev1", "1!0.1", "1.10", "1.0", "1.0rc1", "1.0a1", "1.1")))
                .containsExactly("1.0.dev1", "1.0a1", "1.0rc1", "1.0", "1.1", "1.10", "2.0", "1!0.1");
    }

    @Test
    void trailingZerosDoNotMatter() {
        assertThat(PypiVersion.parse("1.0").compareTo(PypiVersion.parse("1.0.0"))).isZero();
        assertThat(PypiVersion.parse("1.0")).isEqualTo(PypiVersion.parse("1.0.0"));
        assertThat(PypiVersion.parse("1").compareTo(PypiVersion.parse("1.0.1"))).isNegative();
    }

    @Test
    void equalVersionsHaveEqualHashCodes() {
        String[][] equal = {{"1.0", "1.0.0", "1"}, {"1.0a1", "1.0.0alpha1", "1.0-a-1"}, {"2!1.0.post0", "2!1.0.0-0"},
                {"1.0+abc.7", "1.0.0+ABC-007"}, {"1.0.dev0", "1.0.0dev"}};
        for (String[] group : equal) {
            for (String other : group) {
                assertThat(PypiVersion.parse(other)).as(other).isEqualTo(PypiVersion.parse(group[0]));
                assertThat(PypiVersion.parse(other).hashCode()).as(other).isEqualTo(PypiVersion.parse(group[0]).hashCode());
            }
        }
        assertThat(new java.util.HashSet<>(List.of(PypiVersion.parse("1.0"), PypiVersion.parse("1.0.0"), PypiVersion.parse("1.0.1"))))
                .hasSize(2);
    }

    @Test
    void numbersBeyondIntAndLocalSegmentsOfAnyLengthAreFine() {
        // date-stamped nightlies and build numbers do not fit an int
        assertThat(PypiVersion.parse("2.4.0.dev20240101123456").compareTo(PypiVersion.parse("2.4.0.dev20240101123455"))).isPositive();
        assertThat(PypiVersion.parse("20240101123456").compareTo(PypiVersion.parse("2147483648"))).isPositive();
        assertThat(PypiVersion.parse("1.0.post9999999999").isPostRelease()).isTrue();
        assertThat(PypiVersion.tryParse("1." + "9".repeat(30))).isEmpty();
        String huge = "1.0+" + "9".repeat(40);
        assertThat(PypiVersion.parse(huge).compareTo(PypiVersion.parse("1.0+" + "9".repeat(39)))).isPositive();
        assertThat(PypiVersion.parse(huge).compareTo(PypiVersion.parse("1.0+" + "0".repeat(5) + "9".repeat(40)))).isZero();
        assertThat(PypiVersion.parse("1.0+build.007").toString()).isEqualTo("1.0+build.7");
        assertThat(PypiVersion.sort(List.of(huge, "1.0+1", "1.0"))).containsExactly("1.0", "1.0+1", huge);
    }

    @Test
    void localVersionsCompareSegmentWise() {
        assertThat(PypiVersion.parse("1.0+abc").compareTo(PypiVersion.parse("1.0+abd"))).isNegative();
        assertThat(PypiVersion.parse("1.0+2").compareTo(PypiVersion.parse("1.0+10"))).isNegative();
        assertThat(PypiVersion.parse("1.0+abc").compareTo(PypiVersion.parse("1.0+1"))).isNegative(); // numbers after text
        assertThat(PypiVersion.parse("1.0+a.1").compareTo(PypiVersion.parse("1.0+a"))).isPositive();
        assertThat(PypiVersion.parse("1.0+a").hasLocal()).isTrue();
    }

    @Test
    void classifiesReleases() {
        assertThat(PypiVersion.parse("1.0").isPrerelease()).isFalse();
        assertThat(PypiVersion.parse("1.0a1").isPrerelease()).isTrue();
        assertThat(PypiVersion.parse("1.0.dev1").isPrerelease()).isTrue();
        assertThat(PypiVersion.parse("1.0.dev1").isDevRelease()).isTrue();
        assertThat(PypiVersion.parse("1.0.post1").isPrerelease()).isFalse();
        assertThat(PypiVersion.parse("1.0.post1").isPostRelease()).isTrue();
        assertThat(PypiVersion.parse(" 1.0 ").original()).isEqualTo("1.0");
    }

    @Test
    void latestPrefersFinalReleases() {
        assertThat(PypiVersion.latest(List.of("1.0", "2.0a1", "1.5"))).contains("1.5");
        assertThat(PypiVersion.latest(List.of("2.0a1", "2.0b1"))).contains("2.0b1");
        assertThat(PypiVersion.latest(List.of("junk", "1.0"))).contains("1.0");
        assertThat(PypiVersion.latest(List.of("junk"))).isEmpty();
        assertThat(PypiVersion.latest(List.of())).isEmpty();
    }

    @Test
    void sortingKeepsNonVersionsLast() {
        assertThat(PypiVersion.sort(List.of("latest", "1.0", "0.9", "beta"))).containsExactly("0.9", "1.0", "beta", "latest");
        assertThat(PypiVersion.sort(List.of("1.0.0", "1.0"))).containsExactly("1.0", "1.0.0");
    }
}
