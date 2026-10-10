package dev.granary.format.cocoapods;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The expected orders below were produced by {@code Pod::Version} of cocoapods-core 1.17.0
 * ({@code versions.map { |v| Pod::Version.new(v) }.sort}).
 */
class PodVersionTest {

    private static List<String> sorted(String spaceSeparated) {
        List<String> versions = new ArrayList<>(Arrays.asList(spaceSeparated.split(" ")));
        Collections.shuffle(versions, new java.util.Random(42));
        versions.sort(PodVersion.ORDER);
        return versions;
    }

    @Test
    void sortsTheVersionsOfAfNetworkingLikeCocoaPods() {
        assertThat(sorted("0.10.0 0.10.1 0.5.1 0.7.0 0.9.0 0.9.1 0.9.2 1.0 1.0.1 1.0RC1 1.0RC2 1.0RC3 1.1.0 2.0.0 2.0.0-RC1 "
                + "2.0.0-RC2 2.0.0-RC3 3.0.0-beta.1 3.0.0-beta.2 3.0.0 4.0.0 4.0.1"))
                .containsExactly("0.5.1", "0.7.0", "0.9.0", "0.9.1", "0.9.2", "0.10.0", "0.10.1", "1.0RC1", "1.0RC2", "1.0RC3",
                        "1.0", "1.0.1", "1.1.0", "2.0.0-RC1", "2.0.0-RC2", "2.0.0-RC3", "2.0.0", "3.0.0-beta.1", "3.0.0-beta.2",
                        "3.0.0", "4.0.0", "4.0.1");
    }

    @Test
    void sortsRubyGemsPrereleasesSemverPrereleasesAndBuildMetadata() {
        assertThat(sorted("1.0 1.0.0 1.0.0.0 1.0.a 1.0.a.2 1.0.b1 0.9 1.0.a10 1.0.a9 1.0.0-alpha 1.0.0-alpha.1 1.0.0-beta "
                + "1.0.0-rc.1 1.0.0+build.1 1.0.0+abc 1.0.1-1 1.0.1"))
                .containsExactly("0.9", "1.0.a", "1.0.a.2", "1.0.a9", "1.0.a10", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.b1",
                        "1.0.0-beta", "1.0.0-rc.1", "1.0", "1.0.0", "1.0.0+abc", "1.0.0+build.1", "1.0.0.0", "1.0.1", "1.0.1-1");
    }

    @Test
    void sortsNumbersNumericallyAndLettersByteWise() {
        assertThat(sorted("10.0 9.9 3.10 3.2 3.2.0 03.2 1.2.3-beta 1.2.3-Beta 1.2.3-BETA 1.2.3-b 0.0.1 0 2.0.0-1 2.0.0-a "
                + "2.0.0-a1 2.0.0-1a 7.4.0.1 7.4.1"))
                .containsExactly("0", "0.0.1", "1.2.3-BETA", "1.2.3-Beta", "1.2.3-b", "1.2.3-beta", "2.0.0-a", "2.0.0-a1",
                        "2.0.0-1a", "2.0.0-1", "03.2", "3.2", "3.2.0", "3.10", "7.4.0.1", "7.4.1", "9.9", "10.0");
    }

    @ParameterizedTest
    @CsvSource({
            "1.0, 1.0.0, true, -1",
            "1.0+a, 1.0+b, true, -1",
            "1.0.0-beta, 1.0.0-beta.0, false, -1",
            "1.0-beta, 1.0.0-beta, true, -1",
            "1.0.a, 1.0.A, false, 1",
            "01, 1, true, -1"})
    void equivalenceIgnoresTrailingZerosAndMetadataButTheOrderIsTotal(String a, String b, boolean equivalent, int order) {
        assertThat(PodVersion.of(a).isEquivalent(PodVersion.of(b))).isEqualTo(equivalent);
        assertThat(PodVersion.of(a).compareTo(PodVersion.of(b))).isEqualTo(order);
        assertThat(PodVersion.of(b).compareTo(PodVersion.of(a))).isEqualTo(-order);
    }

    @ParameterizedTest
    @CsvSource({
            "1.0, false", "1.0.0-beta, true", "1.0b1, true", "1.0+build-1, false", "1.0.0-rc.1+x, true", "2.0.0-RC1, true",
            "1.0.0+a-b, false"})
    void prereleasesHaveLettersOrADashOutsideTheMetadata(String version, boolean prerelease) {
        assertThat(PodVersion.of(version).isPrerelease()).isEqualTo(prerelease);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", " 1.0 ", "1", "1.0-", "1.0+a.b", "1.0-beta+exp.sha.5114f85", "1.0.0.0.0.0", "2.0.0-RC1", "1.0RC1"})
    void acceptsWhatCocoaPodsAccepts(String version) {
        assertThat(PodVersion.isValid(version)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "1.0 beta", "", " ", "1..0", "1.0.", "v1.0", "1.0+", "1_0", "1.0/2", "../1.0"})
    void refusesWhatCocoaPodsRefuses(String version) {
        assertThat(PodVersion.isValid(version)).isFalse();
    }

    @Test
    void nullIsNotAVersion() {
        assertThat(PodVersion.isValid(null)).isFalse();
        assertThat(PodVersion.of(null).text()).isEmpty();
    }

    @Test
    void anyTextCanBeComparedWithoutFailing() {
        List<String> odd = new ArrayList<>(List.of("", "garbage", "💩", "1.0", "...", "-", "+", "99999999999999999999999999.1",
                "99999999999999999999999999.0"));
        odd.sort(PodVersion.ORDER);
        assertThat(odd).hasSize(9);
        assertThat(odd.indexOf("99999999999999999999999999.0")).isLessThan(odd.indexOf("99999999999999999999999999.1"));
    }

    @Test
    void equalityIsByText() {
        assertThat(PodVersion.of("1.0")).isEqualTo(PodVersion.of(" 1.0 ")).hasSameHashCodeAs(PodVersion.of("1.0"));
        assertThat(PodVersion.of("1.0")).isNotEqualTo(PodVersion.of("1.0.0"));
        assertThat(PodVersion.of("1.0").toString()).isEqualTo("1.0");
    }
}
