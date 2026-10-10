package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CargoVersionTest {

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0", "1.2.3", "10.20.30", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-0.3.7",
            "1.0.0-x.7.z.92", "1.0.0-x-y-z.--", "1.0.0+20130313144700", "1.0.0-beta+exp.sha.5114f85",
            "1.0.0+21AF26D3----117B344092BD", "9223372036854775807.0.0"})
    void validVersions(String v) {
        assertThat(CargoVersion.parse(v)).isPresent();
        assertThat(CargoVersion.parse(v).orElseThrow().toString()).isEqualTo(v);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1.2", "1.2.3.4", "01.2.3", "1.02.3", "1.2.03", "1.2.3-", "1.2.3+", "1.2.3-01",
            "1.2.3-a..b", "1.2.3+a..b", "a.b.c", "1.2.-3", " 1.2.3", "1.2.3 ", "1.2.3-é", "1.2.3+a/b",
            "99999999999999999999.0.0", "v1.2.3"})
    void invalidVersions(String v) {
        assertThat(CargoVersion.parse(v)).isEmpty();
        assertThat(CargoVersion.isValid(v)).isFalse();
    }

    @Test
    void nullIsNotAVersion() {
        assertThat(CargoVersion.parse(null)).isEmpty();
    }

    @Test
    void precedenceFollowsTheSemverSpecification() {
        List<String> ordered = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
                "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "1.10.0", "2.0.0");
        List<String> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled, new java.util.Random(42));
        shuffled.sort(CargoVersion.STRING_ORDER);
        assertThat(shuffled).containsExactlyElementsOf(ordered);
    }

    @Test
    void numericIdentifiersCompareNumericallyAndBeforeAlphanumericOnes() {
        assertThat(CargoVersion.parse("1.0.0-2").orElseThrow()).isLessThan(CargoVersion.parse("1.0.0-10").orElseThrow());
        assertThat(CargoVersion.parse("1.0.0-999").orElseThrow()).isLessThan(CargoVersion.parse("1.0.0-a").orElseThrow());
    }

    @Test
    void buildMetadataOnlyBreaksTies() {
        CargoVersion a = CargoVersion.parse("1.0.0+a").orElseThrow();
        CargoVersion b = CargoVersion.parse("1.0.0+b").orElseThrow();
        CargoVersion plain = CargoVersion.parse("1.0.0").orElseThrow();
        assertThat(a.samePrecedence(b)).isTrue();
        assertThat(a.samePrecedence(plain)).isTrue();
        assertThat(a).isLessThan(b);
        assertThat(plain).isLessThan(a);
        assertThat(a.withoutBuild()).isEqualTo("1.0.0");
        assertThat(CargoVersion.parse("1.0.0-rc.1+x").orElseThrow().withoutBuild()).isEqualTo("1.0.0-rc.1");
        assertThat(a.samePrecedence(CargoVersion.parse("1.0.0-rc.1").orElseThrow())).isFalse();
    }

    @Test
    void invalidStringsSortLast() {
        List<String> versions = new ArrayList<>(List.of("garbage", "2.0.0", "also-bad", "1.0.0"));
        versions.sort(CargoVersion.STRING_ORDER);
        assertThat(versions).containsExactly("1.0.0", "2.0.0", "garbage", "also-bad");
    }

    @Test
    void highestPrefersReleases() {
        assertThat(CargoVersion.highest(List.of("1.0.0", "2.0.0-rc.1", "1.5.0"))).contains("1.5.0");
        assertThat(CargoVersion.highest(List.of("2.0.0-rc.1", "2.0.0-beta"))).contains("2.0.0-rc.1");
        assertThat(CargoVersion.highest(List.of("bad"))).isEmpty();
        assertThat(CargoVersion.highest(List.of())).isEmpty();
    }

    @Test
    void largeNumbersStillOrder() {
        assertThat(CargoVersion.parse("9223372036854775807.0.0").orElseThrow())
                .isGreaterThan(CargoVersion.parse("9223372036854775806.99.99").orElseThrow());
    }
}
