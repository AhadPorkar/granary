package dev.granary.format.rubygems;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link GemVersion} against what RubyGems 3.5's {@code Gem::Version} answers for the same strings. */
class RubygemsVersionTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1|1|false",
            "1.0|1.0|false",
            "1.0.a|1.0.a|true",
            "1.0.0.pre|1.0.0.pre|true",
            "1.0.0-rc1|1.0.0.pre.rc1|true",
            "1.0.0.rc.1|1.0.0.rc.1|true",
            "2.0.0.alpha|2.0.0.alpha|true",
            "3.4.dev|3.4.dev|true",
            "1.01|1.01|false",
            "13.4.2|13.4.2|false",
            "' 2.1 '|2.1|false",
            "''|0|false",
    })
    void parsesLikeRubyGems(String input, String toS, boolean prerelease) {
        GemVersion v = GemVersion.of(input);
        assertThat(v.toString()).isEqualTo(toS);
        assertThat(v.isPrerelease()).isEqualTo(prerelease);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0 1.0", "abc", "1..0", ".1", "1.", "-", "1.0-", "-1", "1.0+build", "v1.0", "1,0",
            "1.0\n2", "../1.0", "1/0"})
    void refusesWhatRubyGemsRefuses(String input) {
        assertThat(GemVersion.isValid(input)).isFalse();
        assertThatThrownBy(() -> GemVersion.of(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesNullAndOverlongVersions() {
        assertThat(GemVersion.parse(null)).isEmpty();
        assertThat(GemVersion.isValid("1." + "1".repeat(200))).isFalse();
    }

    @Test
    void sortsLikeRubyGems() {
        // sorted by Ruby: vs.map { |v| Gem::Version.new(v) }.sort
        List<String> input = Arrays.asList("1", "1.0", "1.0.0", "1.0.a", "1.0.0.pre", "1.0.0-rc1", "1.0.0.rc1",
                "1.0.0.rc.1", "2.0.0.alpha", "2.0.0.b", "0.9.9", "1.10", "1.9", "1.01", "1.0.0.0.1", "3.4.dev", "1.a",
                "1.a.0", "1.0.b1", "0", "0.0.0", "13.4.2", "1.16.0", "10", "9.99");
        List<GemVersion> versions = new ArrayList<>(input.stream().map(GemVersion::of).toList());
        java.util.Collections.shuffle(versions, new java.util.Random(7));
        versions.sort(null);
        List<GemVersion> distinct = new ArrayList<>();
        for (GemVersion v : versions) {
            if (distinct.isEmpty() || distinct.getLast().compareTo(v) != 0) {
                distinct.add(v);
            }
        }
        // Ruby's order of the distinct values (equal versions such as 1, 1.0 and 1.0.0 form one group)
        List<GemVersion> expected = List.of("0", "0.9.9", "1.0.a", "1.0.b1", "1.0.0.pre.rc1", "1.0.0.pre",
                "1.0.0.rc1", "1", "1.0.0.0.1", "1.01", "1.9", "1.10", "1.16.0", "2.0.0.alpha", "2.0.0.b", "3.4.dev", "9.99",
                "10", "13.4.2").stream().map(GemVersion::of).toList();
        assertThat(distinct).usingElementComparator(java.util.Comparator.naturalOrder()).containsExactlyElementsOf(expected);
    }

    @Test
    void equalVersionsAreEqual() {
        assertThat(GemVersion.of("1.0")).isEqualTo(GemVersion.of("1")).hasSameHashCodeAs(GemVersion.of("1.0.0"));
        assertThat(GemVersion.of("1.a")).isEqualTo(GemVersion.of("1.a.0"));
        assertThat(GemVersion.of("1.0.0.rc1")).isEqualTo(GemVersion.of("1.0.0.rc.1"));
        assertThat(GemVersion.of("1.01")).isEqualTo(GemVersion.of("1.1"));
        assertThat(GemVersion.of("1.0")).isNotEqualTo(GemVersion.of("1.0.1"));
    }

    @Test
    void comparesNumericallyNotTextually() {
        assertThat(GemVersion.of("1.10").compareTo(GemVersion.of("1.9"))).isPositive();
        assertThat(GemVersion.of("10").compareTo(GemVersion.of("9.99"))).isPositive();
        assertThat(GemVersion.of("1.0.0.pre").compareTo(GemVersion.of("1.0.0"))).isNegative();
        assertThat(GemVersion.of("99999999999999999999.1").compareTo(GemVersion.of("99999999999999999999"))).isPositive();
    }

    @Test
    void compareStringsPutsInvalidVersionsLast() {
        List<String> list = new ArrayList<>(List.of("zzz", "1.10", "1.9", "abc"));
        list.sort(GemVersion::compareStrings);
        assertThat(list).containsExactly("1.9", "1.10", "abc", "zzz");
        assertThat(GemVersion.compareStrings("1.0", "1")).isPositive(); // equal versions fall back to the text
    }
}
