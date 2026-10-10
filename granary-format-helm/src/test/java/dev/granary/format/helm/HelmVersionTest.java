package dev.granary.format.helm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class HelmVersionTest {

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0", "0.1.0", "1", "1.2", "v1.2.3", "1.0.0-rc.1", "1.0.0-alpha-1.beta",
            "1.0.0+build.5", "1.0.0-rc.1+sha.abcdef", "10.20.30", "01.2.3", "1.0.0-0", "1.0.0-x-y-z.--"})
    void acceptsWhatHelmAccepts(String version) {
        assertThat(HelmVersion.isValid(version)).as(version).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "latest", "1.0.0.0", "1.0.0-", "1.0.0+", "1.0.0-01", "1..0", "v", "V1.0.0", "1.0.0 ",
            " 1.0.0", "1.0.0/../x", "１.０.０", "1.0.0-ü", "99999999999999999999.0.0"})
    void refusesWhatHelmRefuses(String version) {
        assertThat(HelmVersion.isValid(version)).as(version).isFalse();
    }

    @Test
    void nullAndOverlongAreInvalid() {
        assertThat(HelmVersion.parse(null)).isEmpty();
        assertThat(HelmVersion.parse("1." + "1".repeat(300))).isEmpty();
    }

    @Test
    void missingMinorAndPatchAreZero() {
        HelmVersion v = HelmVersion.parse("v2").orElseThrow();
        assertThat(v.major()).isEqualTo(2);
        assertThat(v.minor()).isZero();
        assertThat(v.patch()).isZero();
        assertThat(v.original()).isEqualTo("v2");
        assertThat(v.toString()).isEqualTo("v2");
        assertThat(v.compareTo(HelmVersion.parse("2.0.0").orElseThrow())).isZero();
    }

    @Test
    void ordersLikeSemver() {
        List<String> ordered = List.of("0.9.0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.2.0", "1.10.0", "2.0.0");
        for (int i = 0; i < ordered.size() - 1; i++) {
            HelmVersion a = HelmVersion.parse(ordered.get(i)).orElseThrow();
            HelmVersion b = HelmVersion.parse(ordered.get(i + 1)).orElseThrow();
            assertThat(a.compareTo(b)).as(a + " < " + b).isNegative();
            assertThat(b.compareTo(a)).as(b + " > " + a).isPositive();
        }
    }

    @Test
    void buildMetadataIsIgnoredWhenComparing() {
        assertThat(HelmVersion.parse("1.0.0+a").orElseThrow().compareTo(HelmVersion.parse("1.0.0+b").orElseThrow())).isZero();
    }

    @Test
    void prereleaseFlag() {
        assertThat(HelmVersion.parse("1.0.0-rc.1").orElseThrow().isPrerelease()).isTrue();
        assertThat(HelmVersion.parse("1.0.0+build").orElseThrow().isPrerelease()).isFalse();
    }

    @Test
    void newestFirstPutsUnparsableVersionsLast() {
        List<String> versions = new ArrayList<>(List.of("1.0.0", "garbage", "2.0.0-rc.1", "0.1.0", "2.0.0", "1.10.0", "1.9.0", "aaa"));
        versions.sort(HelmVersion.NEWEST_FIRST);
        assertThat(versions).containsExactly("2.0.0", "2.0.0-rc.1", "1.10.0", "1.9.0", "1.0.0", "0.1.0", "garbage", "aaa");
    }

    @Test
    void newestFirstIsDeterministicForEqualVersions() {
        List<String> versions = new ArrayList<>(List.of("1.0.0+a", "v1.0.0", "1.0.0+b", "1.0.0"));
        versions.sort(HelmVersion.NEWEST_FIRST);
        List<String> again = new ArrayList<>(List.of("1.0.0", "1.0.0+b", "v1.0.0", "1.0.0+a"));
        again.sort(HelmVersion.NEWEST_FIRST);
        assertThat(versions).isEqualTo(again);
    }
}
