package dev.granary.format.nuget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NuGetVersionTest {

    @ParameterizedTest
    @CsvSource({
            "1.0.0.0, 1.0.0",
            "1.0.01, 1.0.1",
            "1.0, 1.0.0",
            "1, 1.0.0",
            "01.002.0003, 1.2.3",
            "1.0.0.5, 1.0.0.5",
            "1.0.0-Beta.1, 1.0.0-Beta.1",
            "1.0.0-beta+build.7, 1.0.0-beta",
            "1.2.3.4-rc.1+meta, 1.2.3.4-rc.1",
            "2.0.0+sha.abcdef, 2.0.0",
            "1.0.0-alpha-2, 1.0.0-alpha-2",
            "1.0.0-beta.01, 1.0.0-beta.01"
    })
    void normalisesLikeTheNuGetClient(String input, String expected) {
        assertThat(NuGetVersion.parse(input).toNormalizedString()).isEqualTo(expected);
        assertThat(NuGetVersion.normalise(input)).isEqualTo(expected);
    }

    @Test
    void lowerCaseFormKeepsLabelsButLowerCasesThem() {
        NuGetVersion v = NuGetVersion.parse("1.0.0-Beta.1");
        assertThat(v.toLowerCaseString()).isEqualTo("1.0.0-beta.1");
        assertThat(v.toNormalizedString()).isEqualTo("1.0.0-Beta.1");
        assertThat(v.isPrerelease()).isTrue();
        assertThat(v.releaseLabels()).containsExactly("Beta", "1");
    }

    @Test
    void buildMetadataIsKeptOnlyInTheFullString() {
        NuGetVersion v = NuGetVersion.parse("1.0.0+build.42");
        assertThat(v.metadata()).contains("build.42");
        assertThat(v.toFullString()).isEqualTo("1.0.0+build.42");
        assertThat(v.toNormalizedString()).isEqualTo("1.0.0");
        assertThat(v.isSemVer2()).isTrue();
        assertThat(v).isEqualTo(NuGetVersion.parse("1.0.0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1.0.0-", "1..0", "-1.0", "1.0.0-beta..1", "1.0.0+", "1.0.0.0.0", "99999999999.0"})
    void rejectsThingsThatAreNotVersions(String input) {
        assertThat(NuGetVersion.isValid(input)).isFalse();
        assertThatThrownBy(() -> NuGetVersion.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> NuGetVersion.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(NuGetVersion.tryParse(null)).isEmpty();
    }

    @Test
    void prereleaseSortsBeforeRelease() {
        assertThat(NuGetVersion.parse("1.0.0-beta")).isLessThan(NuGetVersion.parse("1.0.0"));
        assertThat(NuGetVersion.parse("1.0.0")).isGreaterThan(NuGetVersion.parse("1.0.0-rc.1"));
        assertThat(NuGetVersion.parse("1.0.0-alpha")).isLessThan(NuGetVersion.parse("1.0.0-beta"));
    }

    @Test
    void ordersLikeSemVer2() {
        List<String> expected = List.of(
                "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
                "1.0.0-rc.1", "1.0.0", "1.0.0.1", "1.0.1", "1.1.0", "2.0.0", "10.0.0");
        List<NuGetVersion> shuffled = new ArrayList<>(expected.stream().map(NuGetVersion::parse).toList());
        java.util.Collections.shuffle(shuffled, new java.util.Random(7));
        List<String> sorted = new TreeSet<>(shuffled).stream().map(NuGetVersion::toNormalizedString).toList();
        assertThat(sorted).containsExactlyElementsOf(expected);
    }

    @Test
    void numericPartsCompareNumericallyNotLexically() {
        assertThat(NuGetVersion.parse("1.10.0")).isGreaterThan(NuGetVersion.parse("1.9.0"));
        assertThat(NuGetVersion.parse("1.0.0-beta.10")).isGreaterThan(NuGetVersion.parse("1.0.0-beta.9"));
    }

    @Test
    void labelsCompareCaseInsensitivelyAndNumericBeforeAlphanumeric() {
        assertThat(NuGetVersion.parse("1.0.0-BETA")).isEqualByComparingTo(NuGetVersion.parse("1.0.0-beta"));
        assertThat(NuGetVersion.parse("1.0.0-BETA")).isEqualTo(NuGetVersion.parse("1.0.0-beta"));
        assertThat(NuGetVersion.parse("1.0.0-BETA").hashCode()).isEqualTo(NuGetVersion.parse("1.0.0-beta").hashCode());
        assertThat(NuGetVersion.parse("1.0.0-1")).isLessThan(NuGetVersion.parse("1.0.0-a"));
    }

    @Test
    void equalityIgnoresNormalisationDifferences() {
        assertThat(NuGetVersion.parse("1.0")).isEqualTo(NuGetVersion.parse("1.0.0.0"));
        assertThat(NuGetVersion.parse("1.0.01")).isEqualTo(NuGetVersion.parse("1.0.1"));
        assertThat(NuGetVersion.parse("1.0.0.1")).isNotEqualTo(NuGetVersion.parse("1.0.0"));
        assertThat(new TreeSet<>(List.of(NuGetVersion.parse("1.0"), NuGetVersion.parse("1.0.0"), NuGetVersion.parse("1.0.0.0"))))
                .hasSize(1);
    }

    @Test
    void stringOrderPutsInvalidVersionsLast() {
        List<String> input = new ArrayList<>(List.of("zzz", "2.0.0", "1.0.0-beta", "abc", "1.0.0"));
        input.sort(NuGetVersion.STRING_ORDER);
        assertThat(input).containsExactly("1.0.0-beta", "1.0.0", "2.0.0", "abc", "zzz");
    }

    @Test
    void keepsTheOriginalSpelling() {
        NuGetVersion v = NuGetVersion.parse(" 1.0.01 ");
        assertThat(v.original()).isEqualTo("1.0.01");
        assertThat(v.major()).isEqualTo(1);
        assertThat(v.minor()).isZero();
        assertThat(v.patch()).isEqualTo(1);
        assertThat(v.revision()).isZero();
        assertThat(v.toString()).isEqualTo("1.0.1");
    }
}
