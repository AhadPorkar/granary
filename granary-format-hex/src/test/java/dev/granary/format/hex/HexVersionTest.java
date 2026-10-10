package dev.granary.format.hex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HexVersionTest {

    @Test
    void parsesSemanticVersions() {
        HexVersion v = HexVersion.parse("1.22.333-rc.1.alpha+build.7").orElseThrow();
        assertThat(v.major()).isEqualTo(1);
        assertThat(v.minor()).isEqualTo(22);
        assertThat(v.patch()).isEqualTo(333);
        assertThat(v.preRelease()).containsExactly("rc", "1", "alpha");
        assertThat(v.build()).isEqualTo("build.7");
        assertThat(v.isPreRelease()).isTrue();
        assertThat(v.toString()).isEqualTo("1.22.333-rc.1.alpha+build.7");
        assertThat(HexVersion.parse("0.0.0").orElseThrow().isPreRelease()).isFalse();
    }

    @Test
    void refusesWhatElixirsVersionRefuses() {
        for (String bad : new String[] {"1", "1.0", "v1.0.0", "01.0.0", "1.0.0-", "1.0.0+", "1.0.0-a..b", "1.0.0.0", "",
                "1.0.0-ü", "99999999999999999999.0.0"}) {
            assertThat(HexVersion.isValid(bad)).as(bad).isFalse();
        }
        assertThat(HexVersion.parse(null)).isEmpty();
    }

    @Test
    void ordersLikeElixir() {
        List<String> sorted = HexVersion.sorted(List.of("1.0.0", "1.0.0-rc.10", "1.0.0-rc.2", "1.0.0-alpha", "1.0.0-alpha.1",
                "1.0.0-beta", "0.9.12", "0.10.0", "2.0.0", "1.0.0-1", "not-a-version"));
        assertThat(sorted).containsExactly("0.9.12", "0.10.0", "1.0.0-1", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-beta",
                "1.0.0-rc.2", "1.0.0-rc.10", "1.0.0", "2.0.0", "not-a-version");
    }

    @Test
    void buildMetadataDoesNotChangePrecedence() {
        HexVersion a = HexVersion.parse("1.0.0+a").orElseThrow();
        HexVersion b = HexVersion.parse("1.0.0+b").orElseThrow();
        assertThat(a.compareTo(b)).isZero();
        assertThat(HexVersion.ORDER.compare("1.0.0+a", "1.0.0+b")).isNegative(); // a stable total order for sorting
    }

    @Test
    void latestPrefersStableReleases() {
        List<String> versions = List.of("1.0.0", "1.1.0-rc.1", "0.5.0");
        assertThat(HexVersion.latestStable(versions)).contains("1.0.0");
        assertThat(HexVersion.latest(versions)).contains("1.1.0-rc.1");
        assertThat(HexVersion.latestStable(List.of("2.0.0-rc.1"))).isEmpty();
        assertThat(HexVersion.latest(List.of())).isEmpty();
    }

    @Test
    void numericPreReleaseIdentifiersCompareNumericallyEvenWhenHuge() {
        assertThat(HexVersion.ORDER.compare("1.0.0-99999999999999999999", "1.0.0-100000000000000000000")).isNegative();
    }
}
