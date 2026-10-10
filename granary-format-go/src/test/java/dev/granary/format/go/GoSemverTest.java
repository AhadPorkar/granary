package dev.granary.format.go;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GoSemverTest {

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v1.2", "v1.2.3", "v0.0.0", "v1.2.3-alpha", "v1.2.3-alpha.1", "v1.2.3-0.20200101000000-abcdef",
            "v1.2.3+build", "v1.2.3+build.1", "v1.2.3-rc.1+meta", "v2.0.0+incompatible", "v10.20.30", "v1.0.0-x.7.z.92"})
    void validVersions(String v) {
        assertThat(GoSemver.isValid(v)).as(v).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1.2.3", "v", "v01", "v1.02", "v1.2.03", "v1.2.3-", "v1.2.3-01", "v1.2.3-a..b", "v1.2.3+", "v1.2.3+a..b",
            "v1-rc1", "v1.2-rc1", "v1.2.3.4", "V1.2.3", "v1.2.3 ", "v1.2.3-é", "latest", "master"})
    void invalidVersions(String v) {
        assertThat(GoSemver.isValid(v)).as(v).isFalse();
    }

    @Test
    void precedenceFollowsTheSemverSpecification() {
        List<String> ordered = List.of("v1.0.0-alpha", "v1.0.0-alpha.1", "v1.0.0-alpha.beta", "v1.0.0-beta", "v1.0.0-beta.2",
                "v1.0.0-beta.11", "v1.0.0-rc.1", "v1.0.0", "v1.0.1", "v1.1.0", "v1.10.0", "v2.0.0", "v10.0.0");
        for (int i = 0; i < ordered.size(); i++) {
            for (int j = 0; j < ordered.size(); j++) {
                int expected = Integer.compare(i, j);
                assertThat(GoSemver.compare(ordered.get(i), ordered.get(j))).as("%s vs %s", ordered.get(i), ordered.get(j)).isEqualTo(expected);
            }
        }
    }

    @Test
    void buildMetadataIsIgnoredAndShortFormsExpand() {
        assertThat(GoSemver.compare("v1.0.0+a", "v1.0.0+b")).isZero();
        assertThat(GoSemver.compare("v2.0.0+incompatible", "v2.0.0")).isZero();
        assertThat(GoSemver.compare("v1", "v1.0.0")).isZero();
        assertThat(GoSemver.compare("v1.2", "v1.2.0")).isZero();
        assertThat(GoSemver.ORDER.compare("v1.0.0+a", "v1.0.0+b")).isNegative();
    }

    @Test
    void invalidVersionsSortFirstAndEqualToEachOther() {
        assertThat(GoSemver.compare("bogus", "v0.0.1")).isNegative();
        assertThat(GoSemver.compare("v0.0.1", "bogus")).isPositive();
        assertThat(GoSemver.compare("bogus", "other")).isZero();
    }

    @Test
    void numericIdentifiersCompareNumericallyAndBeforeAlphanumerics() {
        assertThat(GoSemver.compare("v1.0.0-2", "v1.0.0-10")).isNegative();
        assertThat(GoSemver.compare("v1.0.0-10", "v1.0.0-a")).isNegative();
        assertThat(GoSemver.compare("v1.0.0-a", "v1.0.0-a.1")).isNegative();
        assertThat(GoSemver.compare("v1.0.0-Z", "v1.0.0-a")).isNegative();
    }

    @Test
    void partsOfAVersion() {
        assertThat(GoSemver.major("v2.3.4-rc.1+x")).isEqualTo("v2");
        assertThat(GoSemver.prerelease("v2.3.4-rc.1+x")).isEqualTo("-rc.1");
        assertThat(GoSemver.build("v2.3.4-rc.1+x")).isEqualTo("+x");
        assertThat(GoSemver.prerelease("v2.3.4")).isEmpty();
        assertThat(GoSemver.major("nope")).isEmpty();
        assertThat(GoSemver.isPrerelease("v1.0.0-beta")).isTrue();
        assertThat(GoSemver.isPrerelease("v1.0.0")).isFalse();
    }

    @Test
    void canonicalForms() {
        assertThat(GoSemver.canonical("v1")).isEqualTo("v1.0.0");
        assertThat(GoSemver.canonical("v1.2")).isEqualTo("v1.2.0");
        assertThat(GoSemver.canonical("v1.2.3-pre+build")).isEqualTo("v1.2.3-pre");
        assertThat(GoSemver.canonical("v2.0.0+incompatible")).isEqualTo("v2.0.0+incompatible");
        assertThat(GoSemver.canonical("v2.0.0+incompatible.x")).isEqualTo("v2.0.0");
        assertThat(GoSemver.canonical("junk")).isEmpty();
    }

    @Test
    void pseudoVersionsAreRecognised() {
        assertThat(GoSemver.isPseudoVersion("v0.0.0-20191109021931-daa7c04131f5")).isTrue();
        assertThat(GoSemver.isPseudoVersion("v1.2.3-pre.0.20191109021931-daa7c04131f5")).isTrue();
        assertThat(GoSemver.isPseudoVersion("v1.2.4-0.20191109021931-daa7c04131f5")).isTrue();
        assertThat(GoSemver.isPseudoVersion("v1.2.4-0.20191109021931-daa7c04131f5+incompatible")).isTrue();
        assertThat(GoSemver.isPseudoVersion("v1.2.3")).isFalse();
        assertThat(GoSemver.isPseudoVersion("v1.2.3-beta.1")).isFalse();
        assertThat(GoSemver.isPseudoVersion("v0.0.0-2019110902193-daa7c04131f5")).isFalse();
        assertThat(GoSemver.isPseudoVersion(null)).isFalse();
    }

    @Test
    void sortedIsDistinctAndOrdered() {
        assertThat(GoSemver.sorted(List.of("v1.10.0", "v1.2.0", "v1.2.0", "v1.0.0-rc.1", "v0.9.0", "junk")))
                .containsExactly("junk", "v0.9.0", "v1.0.0-rc.1", "v1.2.0", "v1.10.0");
    }

    @Test
    void latestPrefersReleasesThenPrereleasesThenPseudoVersions() {
        assertThat(GoSemver.latest(List.of("v1.0.0", "v2.0.0-beta.1", "v0.0.0-20200101000000-abcdef123456", "v1.5.0")))
                .contains("v1.5.0");
        assertThat(GoSemver.latest(List.of("v2.0.0-beta.1", "v0.0.0-20200101000000-abcdef123456", "v2.0.0-alpha.9")))
                .contains("v2.0.0-beta.1");
        assertThat(GoSemver.latest(List.of("v0.0.0-20200101000000-abcdef123456", "v0.0.0-20210101000000-abcdef123456")))
                .contains("v0.0.0-20210101000000-abcdef123456");
        assertThat(GoSemver.latest(List.of("junk", ""))).isEmpty();
        assertThat(GoSemver.latest(List.of())).isEmpty();
        assertThat(GoSemver.latest(List.of("v1.0.0+incompatible", "v1.0.0"))).contains("v1.0.0+incompatible");
    }
}
