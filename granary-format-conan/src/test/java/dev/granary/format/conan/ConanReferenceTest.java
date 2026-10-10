package dev.granary.format.conan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConanReferenceTest {

    @Test
    void underscoreAndEmptyMeanNoUserOrChannel() {
        ConanReference ref = new ConanReference("zlib", "1.3.1", "_", "_");
        assertThat(ref.user()).isNull();
        assertThat(ref.channel()).isNull();
        assertThat(ref.userSegment()).isEqualTo("_");
        assertThat(ref.channelSegment()).isEqualTo("_");
        assertThat(ref.display()).isEqualTo("zlib/1.3.1");
        assertThat(ref.namespace()).isEmpty();
        assertThat(ref).isEqualTo(new ConanReference("zlib", "1.3.1", "", null));
        assertThat(ref.toString()).isEqualTo("zlib/1.3.1");
    }

    @Test
    void userAndChannelAreKept() {
        ConanReference ref = new ConanReference("acme", "2.0.0-rc.1+build.5", "team", "stable");
        assertThat(ref.display()).isEqualTo("acme/2.0.0-rc.1+build.5@team/stable");
        assertThat(ref.namespace()).isEqualTo("team/stable");
    }

    @Test
    void userWithoutChannel() {
        ConanReference ref = new ConanReference("acme", "1.0", "team", "_");
        assertThat(ref.display()).isEqualTo("acme/1.0@team");
        assertThat(ref.namespace()).isEqualTo("team");
        assertThat(ref.channelSegment()).isEqualTo("_");
    }

    @Test
    void channelWithoutUserIsDisplayedWithThePlaceholder() {
        ConanReference ref = new ConanReference("acme", "1.0", "_", "testing");
        assertThat(ref.display()).isEqualTo("acme/1.0@_/testing");
        assertThat(ref.namespace()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "..", ".hidden", "-dash", "a/b", "a\\b", "a b", "a%2Fb", "ünïcode", "a:b", "a#b", "a@b"})
    void unsafeSegmentsAreRejected(String bad) {
        assertThat(ConanReference.isValidSegment(bad)).isFalse();
        assertThatThrownBy(() -> new ConanReference(bad, "1.0", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConanReference("zlib", bad, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void segmentsAreLimitedInLength() {
        String longest = "a".repeat(ConanReference.MAX_SEGMENT_LENGTH);
        assertThat(ConanReference.isValidSegment(longest)).isTrue();
        assertThat(ConanReference.isValidSegment(longest + "a")).isFalse();
        assertThatThrownBy(() -> new ConanReference("zlib", "1." + longest, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ConanReference.parse("zlib/" + longest + "1")).isEmpty();
    }

    @Test
    void nullNameOrVersionIsRejected() {
        assertThatThrownBy(() -> new ConanReference(null, "1.0", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConanReference("zlib", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ConanReference.isValidSegment(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zlib", "1.3.1", "openssl", "3.0.13", "boost_1", "x+y", "Pkg-Name.2", "_private", "0"})
    void conventionalSegmentsAreAccepted(String good) {
        assertThat(ConanReference.isValidSegment(good)).isTrue();
    }

    @Test
    void parsesFullReferences() {
        assertThat(ConanReference.parse("zlib/1.3.1")).contains(new ConanReference("zlib", "1.3.1", null, null));
        assertThat(ConanReference.parse(" zlib/1.3.1@_/_ ")).contains(new ConanReference("zlib", "1.3.1", null, null));
        assertThat(ConanReference.parse("acme/1.0@team/stable#" + ConanTestSupport.RREV))
                .contains(new ConanReference("acme", "1.0", "team", "stable"));
        assertThat(ConanReference.parse("acme/1.0@team")).contains(new ConanReference("acme", "1.0", "team", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "zlib", "zlib/", "/1.0", "zlib/../1.0", "zlib/1.0@../x", "a/b/c", "zlib/1 0"})
    void parseRejectsGarbage(String text) {
        assertThat(ConanReference.parse(text)).isEmpty();
    }

    @Test
    void parseOfNullIsEmpty() {
        assertThat(ConanReference.parse(null)).isEmpty();
    }
}
