package dev.granary.format.npm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class NpmSemverTest {

    @Test
    void numericComponentsCompareAsNumbersNotStrings() {
        assertThat(NpmSemver.compare("1.10.0", "1.9.0")).isPositive();
        assertThat(NpmSemver.compare("2.0.0", "1.99.99")).isPositive();
        assertThat(NpmSemver.compare("1.0.1", "1.0.0")).isPositive();
        assertThat(NpmSemver.compare("1.0.0", "1.0.0")).isZero();
        assertThat(NpmSemver.compare("0.0.9", "0.0.10")).isNegative();
    }

    @Test
    void preReleaseIsLowerThanTheRelease() {
        assertThat(NpmSemver.compare("1.0.0-alpha", "1.0.0")).isNegative();
        assertThat(NpmSemver.compare("1.0.0", "1.0.0-rc.1")).isPositive();
        assertThat(NpmSemver.compare("1.0.0-rc.1", "0.9.9")).isPositive();
    }

    @Test
    void preReleaseIdentifiersFollowTheSpecificationExample() {
        List<String> expected = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0");
        List<String> shuffled = new ArrayList<>(expected);
        Collections.reverse(shuffled);
        shuffled.sort(NpmSemver.COMPARATOR);
        assertThat(shuffled).containsExactlyElementsOf(expected);
    }

    @Test
    void numericIdentifiersRankBelowAlphanumericOnes() {
        assertThat(NpmSemver.compare("1.0.0-1", "1.0.0-a")).isNegative();
        assertThat(NpmSemver.compare("1.0.0-2", "1.0.0-10")).isNegative();
        assertThat(NpmSemver.compare("1.0.0-alpha.1", "1.0.0-alpha")).isPositive();
    }

    @Test
    void buildMetadataIsIgnored() {
        assertThat(NpmSemver.compare("1.0.0+build.1", "1.0.0+build.2")).isZero();
        assertThat(NpmSemver.compare("1.0.0-beta+exp.sha.5114f85", "1.0.0-beta")).isZero();
        assertThat(NpmSemver.isValid("1.0.0+build")).isTrue();
    }

    @Test
    void invalidVersionsSortBelowValidOnesAndAlphabeticallyAmongThemselves() {
        assertThat(NpmSemver.isValid("1.0")).isFalse();
        assertThat(NpmSemver.isValid("latest")).isFalse();
        assertThat(NpmSemver.isValid("1.0.0.0")).isFalse();
        assertThat(NpmSemver.isValid(null)).isFalse();
        assertThat(NpmSemver.compare("latest", "0.0.1")).isNegative();
        assertThat(NpmSemver.compare("0.0.1", "garbage")).isPositive();
        assertThat(NpmSemver.compare("abc", "abd")).isNegative();
        assertThat(NpmSemver.compare(null, "1.0.0")).isNegative();
    }

    @Test
    void leadingVIsTolerated() {
        assertThat(NpmSemver.isValid("v1.2.0")).isTrue();
        assertThat(NpmSemver.compare("v1.2.0", "1.2.0")).isZero();
        assertThat(NpmSemver.compare("v1.2.0", "1.1.0")).isPositive();
    }

    @Test
    void highestAndHighestRelease() {
        List<String> versions = List.of("1.0.0", "2.0.0-beta.1", "1.5.0", "1.5.0-rc.2");
        assertThat(NpmSemver.highest(versions)).contains("2.0.0-beta.1");
        assertThat(NpmSemver.highestRelease(versions)).contains("1.5.0");
        assertThat(NpmSemver.highestRelease(List.of("1.0.0-rc.1", "1.0.0-rc.2"))).contains("1.0.0-rc.2");
        assertThat(NpmSemver.highest(List.of())).isEmpty();
        assertThat(NpmSemver.highestRelease(List.of())).isEmpty();
    }

    @Test
    void preReleaseDetection() {
        assertThat(NpmSemver.isPreRelease("1.0.0-beta.1")).isTrue();
        assertThat(NpmSemver.isPreRelease("1.0.0")).isFalse();
        assertThat(NpmSemver.isPreRelease("1.0.0+build")).isFalse();
        assertThat(NpmSemver.isPreRelease("not-a-version")).isFalse();
    }

    @Test
    void absurdlyLargeComponentsAreNotVersions() {
        assertThat(NpmSemver.isValid("99999999999999999999999.0.0")).isFalse();
    }
}
