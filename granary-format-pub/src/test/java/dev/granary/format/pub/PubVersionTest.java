package dev.granary.format.pub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PubVersionTest {

    private static PubVersion v(String text) {
        return PubVersion.parse(text).orElseThrow(() -> new AssertionError("not a version: " + text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0", "1.2.3", "10.20.30", "1.0.0-dev", "1.0.0-dev.1", "1.0.0-alpha-1.b", "1.0.0+1",
            "1.0.0+build.7", "2.0.0-nullsafety.0+3", "007.08.09", "1.0.0--", "1.0.0-0.3.7"})
    void acceptsPubVersions(String text) {
        PubVersion version = v(text);
        assertThat(version.text()).isEqualTo(text);
        assertThat(version.toString()).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1.0", "1.0.0.0", "v1.0.0", "1.0.0-", "1.0.0+", "1.0.0-a..b", "1.0.0 ", " 1.0.0",
            "1.0.0-ü", "1.0.0/../x", "1.0.0%2F", "-1.0.0", "1.-1.0", "99999999999999999999.0.0", "1.0.0_1", "latest"})
    void refusesEverythingElse(String text) {
        assertThat(PubVersion.parse(text)).isEmpty();
        assertThat(PubVersion.isValid(text)).isFalse();
    }

    @Test
    void refusesNullAndOverlongInput() {
        assertThat(PubVersion.parse(null)).isEmpty();
        assertThat(PubVersion.parse("1.0.0-" + "a".repeat(PubVersion.MAX_LENGTH))).isEmpty();
    }

    @Test
    void splitsPartsIntoNumbersAndWords() {
        PubVersion version = v("1.2.3-beta.11.x-y+007.z");
        assertThat(version.major()).isEqualTo(1);
        assertThat(version.minor()).isEqualTo(2);
        assertThat(version.patch()).isEqualTo(3);
        assertThat(version.preRelease()).containsExactly("beta", 11L, "x-y");
        assertThat(version.build()).containsExactly(7L, "z");
        assertThat(version.isPreRelease()).isTrue();
        assertThat(v("1.0.0+5").isPreRelease()).isFalse();
    }

    @Test
    void ordersLikePubSemver() {
        // the ordering pub_semver documents, build metadata included
        List<String> ordered = List.of(
                "0.9.0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
                "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.0+1", "1.0.0+2", "1.0.0+10", "1.0.0+build",
                "1.0.0+build.1", "1.0.1", "1.2.0", "1.10.0", "2.0.0-dev", "2.0.0", "10.0.0");
        List<PubVersion> shuffled = new ArrayList<>(ordered.stream().map(PubVersionTest::v).toList());
        Collections.shuffle(shuffled, new Random(42));
        Collections.sort(shuffled);
        assertThat(shuffled).extracting(PubVersion::text).containsExactlyElementsOf(ordered);
    }

    @Test
    void comparesNumericPartsNumericallyAndBeforeWords() {
        assertThat(v("1.0.0-2")).isLessThan(v("1.0.0-10"));
        assertThat(v("1.0.0-10")).isLessThan(v("1.0.0-a"));
        assertThat(v("1.0.0-a")).isLessThan(v("1.0.0-a.0"));
        assertThat(v("1.0.0-B")).isLessThan(v("1.0.0-a")); // code-unit order, as in Dart
    }

    @Test
    void equalSpellingsAreTheSameVersion() {
        assertThat(v("1.0.0+01").sameAs(v("1.0.0+1"))).isTrue();
        assertThat(v("1.0.0-dev.01").sameAs(v("1.0.0-dev.1"))).isTrue();
        assertThat(v("01.0.0").sameAs(v("1.0.0"))).isTrue();
        assertThat(v("1.0.0").sameAs(v("1.0.0+0"))).isFalse();
        assertThat(v("1.0.0+01")).isNotEqualTo(v("1.0.0+1")); // records still keep the spelling
    }

    @Test
    void latestIsTheHighestReleaseThatIsNotRetracted() {
        List<PubVersion> all = List.of(v("1.0.0"), v("1.1.0"), v("2.0.0-dev.1"), v("1.2.0"));
        assertThat(PubVersion.latest(all, List.of())).contains(v("1.2.0"));
        assertThat(PubVersion.latest(all, List.of(v("1.2.0")))).contains(v("1.1.0"));
    }

    @Test
    void latestFallsBackToPreReleasesAndThenToRetractedVersions() {
        assertThat(PubVersion.latest(List.of(v("1.0.0-dev.1"), v("1.0.0-dev.2")), List.of())).contains(v("1.0.0-dev.2"));
        assertThat(PubVersion.latest(List.of(v("1.0.0"), v("1.1.0")), List.of(v("1.0.0"), v("1.1.0")))).contains(v("1.1.0"));
        assertThat(PubVersion.latest(List.of(), List.of())).isEmpty();
        // a retracted release does not hide behind a live pre-release
        assertThat(PubVersion.latest(List.of(v("1.0.0"), v("2.0.0-dev")), List.of(v("1.0.0")))).contains(v("2.0.0-dev"));
    }

    @Test
    void largeButValidNumbersParse() {
        assertThat(v("9223372036854775807.0.0").major()).isEqualTo(Long.MAX_VALUE);
        assertThat(v("1.0.0-99999999999999999999").preRelease()).containsExactly("99999999999999999999");
    }
}
