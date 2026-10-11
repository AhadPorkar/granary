package dev.granary.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StorableTextTest {

    @Test
    void fitsWithoutNulUpToTheLimit() {
        assertThat(StorableText.fits(null, 3)).isTrue();
        assertThat(StorableText.fits("", 3)).isTrue();
        assertThat(StorableText.fits("abc", 3)).isTrue();
        assertThat(StorableText.fits("abcd", 3)).isFalse();
        assertThat(StorableText.fits("a\0b", 3)).isFalse();
        assertThat(StorableText.fits("\0", 3)).isFalse();
        // a supplementary character takes two Java chars but one database character: the check is the stricter one
        assertThat(StorableText.fits("😀", 2)).isTrue();
    }

    @Test
    void aLookupKeyMustBePresent() {
        assertThat(StorableText.usableKey(null, 10)).isFalse();
        assertThat(StorableText.usableKey("admin", 10)).isTrue();
        assertThat(StorableText.usableKey("ad\0min", 10)).isFalse();
        assertThat(StorableText.usableKey("a".repeat(11), 10)).isFalse();
    }

    @Test
    void checkRefusesWithTheFieldAndTheReason() {
        StorableText.check("description", null, 5);
        StorableText.check("description", "fine", 5);
        assertThatThrownBy(() -> StorableText.check("description", "a\0", 5))
                .isInstanceOfSatisfying(GranaryException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("validation");
                    assertThat(e.details()).containsEntry("description", "must not contain NUL characters");
                });
        assertThatThrownBy(() -> StorableText.check("remoteUrl", "x".repeat(6), 5))
                .isInstanceOfSatisfying(GranaryException.class, e ->
                        assertThat(e.details()).containsEntry("remoteUrl", "size must be between 0 and 5"));
    }
}
