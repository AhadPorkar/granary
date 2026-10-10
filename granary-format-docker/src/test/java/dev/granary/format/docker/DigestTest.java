package dev.granary.format.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DigestTest {

    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void parsesSha256Digests() {
        Digest digest = Digest.parse("sha256:" + EMPTY_SHA256).orElseThrow();
        assertThat(digest.algorithm()).isEqualTo("sha256");
        assertThat(digest.hex()).isEqualTo(EMPTY_SHA256);
        assertThat(digest.toString()).isEqualTo("sha256:" + EMPTY_SHA256);
        assertThat(Digest.isDigest("sha256:" + EMPTY_SHA256)).isTrue();
        assertThat(Digest.parse("  sha256:" + EMPTY_SHA256 + " ")).isPresent();
    }

    @Test
    void rejectsEverythingElse() {
        assertThat(Digest.parse(null)).isEmpty();
        assertThat(Digest.parse("")).isEmpty();
        assertThat(Digest.parse("latest")).isEmpty();
        assertThat(Digest.parse("sha256:abc")).isEmpty();
        assertThat(Digest.parse("sha256:" + EMPTY_SHA256.toUpperCase())).isEmpty();
        assertThat(Digest.parse("sha512:" + EMPTY_SHA256 + EMPTY_SHA256)).isEmpty();
        assertThat(Digest.parse("sha256:" + EMPTY_SHA256 + "/../x")).isEmpty();
        assertThat(Digest.isDigest("md5:d41d8cd98f00b204e9800998ecf8427e")).isFalse();
    }

    @Test
    void hashesBytesAndStreams() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        Digest fromBytes = Digest.of(data);
        Digest fromStream = Digest.of(new ByteArrayInputStream(data));
        assertThat(fromBytes).isEqualTo(fromStream);
        assertThat(fromBytes.hex()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(Digest.of(new byte[0]).hex()).isEqualTo(EMPTY_SHA256);
    }

    @Test
    void constructorValidatesHex() {
        assertThatThrownBy(() -> Digest.sha256("nothex")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Digest("md5", EMPTY_SHA256)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Digest.sha256(EMPTY_SHA256).equals(Digest.parse("sha256:" + EMPTY_SHA256).orElseThrow())).isTrue();
    }
}
