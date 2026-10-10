package dev.granary.format.swift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SwiftVersionTest {

    @Test
    void acceptsSemanticVersions() {
        for (String v : List.of("0.0.0", "1.2.3", "10.20.30", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-0.3.7",
                "1.0.0-x.7.z.92", "1.0.0+20130313144700", "1.0.0-beta+exp.sha.5114f85", "1.0.0-x-y-z.--",
                "99999999999999999999.0.0")) {
            assertThat(SwiftVersion.isValid(v)).as(v).isTrue();
        }
    }

    @Test
    void rejectsEverythingElse() {
        for (String v : List.of("", "1", "1.0", "v1.0.0", "01.0.0", "1.01.0", "1.0.01", "1.0.0-", "1.0.0-01",
                "1.0.0-alpha..1", "1.0.0+", "1.0.0+a..b", "1.0.0 ", "1.0.0-al pha", "1.0.0/x", "1.0.0-é")) {
            assertThat(SwiftVersion.isValid(v)).as(v).isFalse();
        }
        assertThat(SwiftVersion.isValid(null)).isFalse();
        assertThat(SwiftVersion.isValid("1.0.0-" + "a".repeat(SwiftVersion.MAX_LENGTH))).isFalse();
    }

    @Test
    void ordersBySemverPrecedence() {
        List<String> expected = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
                "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.0+build.1", "1.0.1", "1.2.0", "1.10.0", "2.0.0",
                "10.0.0", "99999999999999999999.0.0");
        List<String> shuffled = new ArrayList<>(expected.reversed());
        shuffled.sort(SwiftVersion.ORDER);
        assertThat(shuffled).isEqualTo(expected);
    }

    @Test
    void invalidVersionsSortAfterValidOnes() {
        List<String> versions = new ArrayList<>(List.of("zzz", "1.0.0", "abc", "0.1.0"));
        versions.sort(SwiftVersion.ORDER);
        assertThat(versions).containsExactly("0.1.0", "1.0.0", "abc", "zzz");
    }

    @Test
    void equalityIsTextual() {
        SwiftVersion a = SwiftVersion.parse("1.0.0+a").orElseThrow();
        SwiftVersion b = SwiftVersion.parse("1.0.0+b").orElseThrow();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.compareTo(b)).isNegative();
        assertThat(a).isEqualTo(SwiftVersion.parse("1.0.0+a").orElseThrow());
        assertThat(a.hashCode()).isEqualTo("1.0.0+a".hashCode());
        assertThat(a.text()).isEqualTo("1.0.0+a");
        assertThat(a).hasToString("1.0.0+a");
    }

    @Test
    void versionsThatCollideWithEndpointSuffixes() {
        assertThat(SwiftVersion.isAmbiguousInPaths("1.0.0-beta.zip")).isTrue();
        assertThat(SwiftVersion.isAmbiguousInPaths("1.0.0+meta.json")).isTrue();
        assertThat(SwiftVersion.isAmbiguousInPaths("1.0.0-zip")).isFalse();
        assertThat(SwiftVersion.isAmbiguousInPaths("1.0.0")).isFalse();
        assertThat(SwiftVersion.isAmbiguousInPaths(null)).isFalse();
    }
}
