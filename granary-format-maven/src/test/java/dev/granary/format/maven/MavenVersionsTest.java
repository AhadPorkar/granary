package dev.granary.format.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MavenVersionsTest {

    @ParameterizedTest
    @CsvSource({
            "1.0, 1.0.1",
            "1.0.1, 1.1",
            "1.1, 1.10",
            "1.9, 1.10",
            "1.0-alpha1, 1.0-beta1",
            "1.0-beta1, 1.0-rc1",
            "1.0-rc1, 1.0-SNAPSHOT",
            "1.0-SNAPSHOT, 1.0",
            "1.0, 1.0-sp1",
            "1.0-rc, 1.0.1",
            "2.0-M1, 2.0",
            "1.0-alpha-10, 1.0-beta-1",
            "1.0.0-alpha.1, 1.0.0-alpha.2",
            "3.5.0, 4.0.0",
            "4.13.2, 5.0.0-M1",
    })
    void ordersVersionsLikeMaven(String lower, String higher) {
        assertThat(MavenVersions.compare(lower, higher)).as("%s < %s", lower, higher).isNegative();
        assertThat(MavenVersions.compare(higher, lower)).as("%s > %s", higher, lower).isPositive();
    }

    @ParameterizedTest
    @CsvSource({
            "1.0, 1",
            "1.0, 1.0.0",
            "1.0-final, 1.0",
            "1.0-ga, 1",
            "1.0-RELEASE, 1.0",
            "1.0-alpha, 1.0-a",
            "1.0-beta, 1.0-b",
            "1.0-milestone, 1.0-m",
            "1.0-cr, 1.0-rc",
            "1.0-SNAPSHOT, 1.0-snapshot",
            "01.002, 1.2",
    })
    void treatsEquivalentSpellingsAsEqual(String a, String b) {
        assertThat(MavenVersions.compare(a, b)).as("%s == %s", a, b).isZero();
    }

    @Test
    void unknownQualifiersSortAfterKnownOnesLexically() {
        assertThat(MavenVersions.compare("1.0-sp1", "1.0-zeta")).isNegative();
        assertThat(MavenVersions.compare("1.0-foo", "1.0-bar")).isPositive();
        assertThat(MavenVersions.compare("1.0", "1.0-foo")).isNegative();
    }

    @Test
    void maxPicksTheHighestVersion() {
        List<String> versions = new ArrayList<>(List.of("1.0", "1.10", "1.9", "2.0-SNAPSHOT", "1.10-rc1"));
        assertThat(MavenVersions.max(versions)).isEqualTo("2.0-SNAPSHOT");
        assertThat(MavenVersions.max(List.of())).isNull();
        versions.sort(MavenVersions.COMPARATOR);
        assertThat(versions).containsExactly("1.0", "1.9", "1.10-rc1", "1.10", "2.0-SNAPSHOT");
    }

    @Test
    void handlesNullsAndEmptyStrings() {
        assertThat(MavenVersions.compare(null, "")).isZero();
        assertThat(MavenVersions.compare("", "1")).isNegative();
    }
}
