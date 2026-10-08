package dev.granary.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContentTypesAndChecksumsTest {

    @Test
    void guessesMediaTypesFromExtensions() {
        assertThat(ContentTypes.guess("org/x/lib/1.0/lib-1.0.jar")).isEqualTo("application/java-archive");
        assertThat(ContentTypes.guess("org/x/lib/1.0/lib-1.0.pom")).isEqualTo("application/xml");
        assertThat(ContentTypes.guess("lodash/-/lodash-4.17.21.tgz")).isEqualTo("application/gzip");
        assertThat(ContentTypes.guess("newtonsoft.json/13.0.3/newtonsoft.json.13.0.3.nupkg")).isEqualTo("application/zip");
        assertThat(ContentTypes.guess("x.SHA1")).isEqualTo("text/plain");
        assertThat(ContentTypes.guess("README")).isEqualTo(ContentTypes.OCTET_STREAM);
        assertThat(ContentTypes.guess("weird.")).isEqualTo(ContentTypes.OCTET_STREAM);
        assertThat(ContentTypes.guess(null)).isEqualTo(ContentTypes.OCTET_STREAM);
    }

    @Test
    void mapsChecksumExtensionsToAlgorithms() {
        assertThat(Checksums.algorithmFor("sha1")).contains("SHA-1");
        assertThat(Checksums.algorithmFor("MD5")).contains("MD5");
        assertThat(Checksums.algorithmFor("sha512")).contains("SHA-512");
        assertThat(Checksums.algorithmFor("jar")).isEmpty();
        assertThat(Checksums.isChecksumExtension("sha256")).isTrue();
        assertThat(Checksums.isChecksumExtension(null)).isFalse();
    }

    @Test
    void computesWellKnownDigests() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertThat(Checksums.hex("SHA-1", data)).isEqualTo("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d");
        assertThat(Checksums.hex("MD5", new ByteArrayInputStream(data))).isEqualTo("5d41402abc4b2a76b9719d911017c592");
        assertThat(Checksums.hex("SHA-256", data))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThatThrownBy(() -> Checksums.hex("NOPE", data)).isInstanceOf(IllegalArgumentException.class);
    }
}
