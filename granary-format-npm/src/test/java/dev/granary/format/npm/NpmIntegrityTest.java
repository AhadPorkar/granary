package dev.granary.format.npm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NpmIntegrityTest {

    private static final byte[] ABC = "abc".getBytes(StandardCharsets.UTF_8);

    @Test
    void shasumIsSha1Hex() {
        assertThat(NpmIntegrity.shasum(ABC)).isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    @Test
    void integrityIsSha512Base64WithPrefix() {
        assertThat(NpmIntegrity.integrity(ABC)).isEqualTo(
                "sha512-3a81oZNherrMQXNJriBBMRLm+k6JqX6iCp7u5ktV05ohkpkqJ0/BqDa6PCOj/uu9RU1EI2Q86A4qmslPpUyknw==");
    }

    @Test
    void matchesVerifiesTheDigestTheClientSent() {
        assertThat(NpmIntegrity.matches(NpmIntegrity.integrity(ABC), ABC)).isTrue();
        assertThat(NpmIntegrity.matches(NpmIntegrity.integrity(ABC), "abd".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(NpmIntegrity.matches("sha1-qZk+NkcGgWq6PiVxeFDCbJzQ2J0=", ABC)).isTrue();
        assertThat(NpmIntegrity.matches("sha256-ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=", ABC)).isTrue();
    }

    @Test
    void matchesToleratesWhatItCannotCheck() {
        assertThat(NpmIntegrity.matches(null, ABC)).isTrue();
        assertThat(NpmIntegrity.matches("", ABC)).isTrue();
        assertThat(NpmIntegrity.matches("sha999-abcd", ABC)).isTrue();
        assertThat(NpmIntegrity.matches("garbage", ABC)).isTrue();
    }

    @Test
    void matchesHandlesOptionsAndMultipleEntries() {
        String sri = NpmIntegrity.integrity(ABC);
        assertThat(NpmIntegrity.matches(sri + "?foo=bar", ABC)).isTrue();
        assertThat(NpmIntegrity.matches(sri + " sha1-xxxx", ABC)).isTrue();
        assertThat(NpmIntegrity.matches("sha512-!!!notbase64!!!", ABC)).isFalse();
    }
}
