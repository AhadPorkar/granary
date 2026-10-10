package dev.granary.format.cocoapods;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.format.cocoapods.SourceArchive.Type;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SourceArchiveTest {

    /** {@code type_with_url} of cocoapods-downloader: case-sensitive suffixes of the URL path. */
    @ParameterizedTest
    @CsvSource(nullValues = "none", value = {
            "/a/b.zip, ZIP", "/a/b.tgz, TGZ", "/a/b.tar.gz, TGZ", "/a/b.tar, TAR", "/a/b.tbz, TBZ", "/a/b.tar.bz2, TBZ",
            "/a/b.txz, TXZ", "/a/b.tar.xz, TXZ", "/a/b.dmg, DMG", "/a/b.ZIP, none", "/a/b.zip/, none", "/a/b, none",
            "/a/b.7z, none", "/a/b.gz, none"})
    void typesAreInferredFromThePathSuffix(String path, Type expected) {
        assertThat(Type.ofPath(path).orElse(null)).isEqualTo(expected);
    }

    @Test
    void explicitTypesWin() {
        assertThat(Type.ofOption("zip")).contains(Type.ZIP);
        assertThat(Type.ofOption(" TGZ ")).contains(Type.TGZ);
        assertThat(Type.ofOption("rar")).isEmpty();
        assertThat(Type.ofOption(null)).isEmpty();
        assertThat(SourceArchive.effectiveType("https://x/a.zip", "tgz")).contains(Type.TGZ);
        assertThat(SourceArchive.effectiveType("https://x/a.zip", null)).contains(Type.ZIP);
        assertThat(SourceArchive.effectiveType("https://x/a.zip", " ")).contains(Type.ZIP);
        assertThat(SourceArchive.effectiveType("https://x/download?f=a.zip", null)).isEmpty();
        assertThat(Type.TBZ.option()).isEqualTo("tbz");
        assertThat(Type.TBZ.extension()).isEqualTo("tar.bz2");
    }

    @Test
    void filenamesComeFromTheLastPathSegment() {
        assertThat(SourceArchive.filename("https://github.com/AFNetworking/AFNetworking/archive/4.0.1.zip", null)).isEqualTo("4.0.1.zip");
        assertThat(SourceArchive.filename("https://dl.example.com/sdk/Foo-1.0.0.tar.gz?token=abc#frag", null)).isEqualTo("Foo-1.0.0.tar.gz");
        assertThat(SourceArchive.filename("https://dl.example.com/My%20SDK%20(v2).zip", null)).isEqualTo("My_SDK__v2_.zip");
        assertThat(SourceArchive.filename("https://dl.example.com/download?id=5", "zip")).isEqualTo("download");
        assertThat(SourceArchive.filename("https://dl.example.com/", "tgz")).isEqualTo("source.tar.gz");
        assertThat(SourceArchive.filename("https://dl.example.com", null)).isEqualTo("source");
        assertThat(SourceArchive.filename("https://dl.example.com/.hidden.zip", null)).isEqualTo("source.zip");
        assertThat(SourceArchive.filename("https://dl.example.com/%2e%2e", null)).isEqualTo("source");
        // an encoded slash is a separator once decoded: only the last segment counts
        assertThat(SourceArchive.filename("https://dl.example.com/a/%2E%2E%2Fsecret.zip", null)).isEqualTo("secret.zip");
        assertThat(SourceArchive.filename("https://dl.example.com/" + "x".repeat(250) + ".zip", null)).isEqualTo("source.zip");
        assertThat(SourceArchive.filename("https://dl.example.com/日本.zip", null)).isEqualTo("__.zip");
        assertThat(SourceArchive.filename("not a url", null)).isEqualTo("source");
    }

    @Test
    void sanitisedNamesKeepTheArchiveSuffix() {
        assertThat(SourceArchive.sanitise("MyPod-1.0.0.zip", Type.ZIP)).isEqualTo("MyPod-1.0.0.zip");
        assertThat(SourceArchive.sanitise("C:\\Users\\me\\MyPod.tar.gz", Type.TGZ)).isEqualTo("MyPod.tar.gz");
        assertThat(SourceArchive.sanitise("../../etc/MyPod.zip", Type.ZIP)).isEqualTo("MyPod.zip");
        assertThat(SourceArchive.sanitise("..", Type.ZIP)).isEqualTo("source.zip");
        assertThat(SourceArchive.sanitise(null, Type.DMG)).isEqualTo("source.dmg");
        assertThat(SourceArchive.sanitise("", null)).isEqualTo("source");
        assertThat(SourceArchive.sanitise("a b;c?.zip", Type.ZIP)).isEqualTo("a_b_c_.zip");
    }

    @Test
    void onlyAbsoluteHttpUrlsAreDownloadable() {
        assertThat(SourceArchive.isHttpUrl("https://example.com/a.zip")).isTrue();
        assertThat(SourceArchive.isHttpUrl("HTTP://example.com/a.zip")).isTrue();
        assertThat(SourceArchive.isHttpUrl("file:///etc/passwd")).isFalse();
        assertThat(SourceArchive.isHttpUrl("ftp://example.com/a.zip")).isFalse();
        assertThat(SourceArchive.isHttpUrl("/relative/a.zip")).isFalse();
        assertThat(SourceArchive.isHttpUrl("https:///nohost.zip")).isFalse();
        assertThat(SourceArchive.isHttpUrl("https://exa mple.com/a.zip")).isFalse();
        assertThat(SourceArchive.isHttpUrl(null)).isFalse();
        assertThat(SourceArchive.httpPath("https://example.com")).contains("");
    }

    @Test
    void magicNumbersMatchTheirTypes() {
        assertThat(SourceArchive.looksLike(Type.ZIP, bytes("PK\u0003\u0004rest"))).isTrue();
        assertThat(SourceArchive.looksLike(Type.ZIP, bytes("PK\u0005\u0006"))).isTrue();
        assertThat(SourceArchive.looksLike(Type.ZIP, bytes("PK"))).isFalse();
        assertThat(SourceArchive.looksLike(Type.ZIP, bytes("<html>"))).isFalse();
        assertThat(SourceArchive.looksLike(Type.TGZ, new byte[] {0x1f, (byte) 0x8b, 8})).isTrue();
        assertThat(SourceArchive.looksLike(Type.TGZ, new byte[] {0x1f})).isFalse();
        assertThat(SourceArchive.looksLike(Type.TBZ, bytes("BZh91AY"))).isTrue();
        assertThat(SourceArchive.looksLike(Type.TXZ, new byte[] {(byte) 0xfd, '7', 'z', 'X', 'Z', 0})).isTrue();
        assertThat(SourceArchive.looksLike(Type.TXZ, new byte[] {(byte) 0xfd, '7', 'z', 'X', 'Z', 1})).isFalse();
        byte[] tar = new byte[512];
        System.arraycopy(bytes("ustar"), 0, tar, 257, 5);
        assertThat(SourceArchive.looksLike(Type.TAR, tar)).isTrue();
        assertThat(SourceArchive.looksLike(Type.TAR, new byte[512])).isFalse();
        assertThat(SourceArchive.looksLike(Type.DMG, new byte[0])).isTrue();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
