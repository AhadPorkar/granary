package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.golden;
import static dev.granary.format.hex.HexTestSupport.goldenKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.format.hex.HexRegistry.Dependency;
import dev.granary.format.hex.HexRegistry.Names;
import dev.granary.format.hex.HexRegistry.NamesEntry;
import dev.granary.format.hex.HexRegistry.Package;
import dev.granary.format.hex.HexRegistry.Release;
import dev.granary.format.hex.HexRegistry.Retirement;
import dev.granary.format.hex.HexRegistry.RetirementReason;
import dev.granary.format.hex.HexRegistry.Signed;
import dev.granary.format.hex.HexRegistry.Timestamp;
import dev.granary.format.hex.HexRegistry.Versions;
import dev.granary.format.hex.HexRegistry.VersionsEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registry codecs against bytes hex_core itself produced ({@code src/test/resources/hex/golden}, made by
 * {@code generate.exs} with the hex_core shipped in hex 2.5.1): encoding must be byte-identical, decoding must
 * read everything, and signatures made with the same key must match exactly.
 */
class HexRegistryTest {

    @TempDir
    Path tempDir;

    private static byte[] sha256(String text) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    static Names goldenNames() {
        return new Names("acme", List.of(
                new NamesEntry("alpha", new Timestamp(1_700_000_000L, 123_000_000)),
                new NamesEntry("beta", null),
                new NamesEntry("ünïcode_pkg", new Timestamp(0, 0))));
    }

    static Versions goldenVersions() {
        return new Versions("acme", List.of(
                new VersionsEntry("alpha", List.of("0.1.0", "0.2.0-rc.1", "1.0.0"), List.of(0, 2), List.of(1)),
                new VersionsEntry("beta", List.of("2.0.0"), List.of())));
    }

    static Package goldenPackage(byte[] advisory) throws Exception {
        byte[] inner = sha256("inner");
        byte[] outer = sha256("outer");
        return new Package("alpha", "acme", List.of(
                new Release("0.1.0", inner, List.of(), new Retirement(RetirementReason.SECURITY, "use 1.0.0"), outer),
                new Release("0.2.0-rc.1", inner, List.of(
                        new Dependency("jason", "~> 1.4", false, "", ""),
                        new Dependency("plug_crypto", ">= 1.0.0 and < 3.0.0", true, "plug_crypto_app", ""),
                        new Dependency("private_dep", "~> 0.1", false, "", "hexpm:acme")),
                        null, outer, List.of(0), new Timestamp(1_700_000_100L, 5)),
                new Release("1.0.0", inner, List.of(), new Retirement(RetirementReason.OTHER, null), outer)),
                List.of(advisory));
    }

    @Test
    void namesEncodeExactlyLikeHexCore() {
        assertThat(HexRegistry.encodeNames(goldenNames())).isEqualTo(golden("names.bin"));
    }

    @Test
    void namesDecodeFromHexCore() {
        Names names = HexRegistry.decodeNames(golden("names.bin"));
        assertThat(names).isEqualTo(goldenNames());
        assertThat(names.names()).containsExactly("alpha", "beta", "ünïcode_pkg");
        assertThat(names.packages().get(0).updatedAt().toInstant()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L, 123_000_000));
    }

    @Test
    void versionsEncodeExactlyLikeHexCore() {
        assertThat(HexRegistry.encodeVersions(goldenVersions())).isEqualTo(golden("versions.bin"));
    }

    @Test
    void versionsDecodeFromHexCore() {
        assertThat(HexRegistry.decodeVersions(golden("versions.bin"))).isEqualTo(goldenVersions());
    }

    @Test
    void packageRoundTripsHexCoreBytesIncludingAdvisories() throws Exception {
        Package decoded = HexRegistry.decodePackage(golden("package.bin"));
        assertThat(decoded.name()).isEqualTo("alpha");
        assertThat(decoded.repository()).isEqualTo("acme");
        assertThat(decoded.advisories()).hasSize(1);
        assertThat(new String(decoded.advisories().getFirst(), StandardCharsets.UTF_8)).contains("GHSA-xxxx-yyyy-zzzz");

        Package expected = goldenPackage(decoded.advisories().getFirst());
        assertThat(HexRegistry.encodePackage(expected)).isEqualTo(golden("package.bin"));
        assertThat(HexRegistry.encodePackage(decoded)).isEqualTo(golden("package.bin"));
    }

    @Test
    void packageDecodesEveryField() throws Exception {
        Package pkg = HexRegistry.decodePackage(golden("package.bin"));
        assertThat(pkg.releases()).extracting(Release::version).containsExactly("0.1.0", "0.2.0-rc.1", "1.0.0");
        Release first = pkg.release("0.1.0").orElseThrow();
        assertThat(first.isRetired()).isTrue();
        assertThat(first.retired()).isEqualTo(new Retirement(RetirementReason.SECURITY, "use 1.0.0"));
        assertThat(first.innerChecksum()).isEqualTo(sha256("inner"));
        assertThat(first.outerChecksum()).isEqualTo(sha256("outer"));
        assertThat(first.publishedAt()).isNull();

        Release rc = pkg.release("0.2.0-rc.1").orElseThrow();
        assertThat(rc.isRetired()).isFalse();
        assertThat(rc.advisoryIndexes()).containsExactly(0);
        assertThat(rc.publishedAt()).isEqualTo(new Timestamp(1_700_000_100L, 5));
        assertThat(rc.dependencies()).containsExactly(
                new Dependency("jason", "~> 1.4", false, "", ""),
                new Dependency("plug_crypto", ">= 1.0.0 and < 3.0.0", true, "plug_crypto_app", ""),
                new Dependency("private_dep", "~> 0.1", false, "", "hexpm:acme"));

        assertThat(pkg.release("1.0.0").orElseThrow().retired()).isEqualTo(new Retirement(RetirementReason.OTHER, ""));
        assertThat(pkg.release("9.9.9")).isEmpty();
    }

    @Test
    void appEqualToThePackageNameIsLeftOutLikeHexpmDoes() {
        byte[] explicit = HexRegistry.encodePackage(new Package("a", "r", List.of(new Release("1.0.0", new byte[32],
                List.of(new Dependency("jason", "~> 1.0", false, "jason", "")), null, new byte[32]))));
        byte[] implicit = HexRegistry.encodePackage(new Package("a", "r", List.of(new Release("1.0.0", new byte[32],
                List.of(new Dependency("jason", "~> 1.0", false, "", "")), null, new byte[32]))));
        assertThat(explicit).isEqualTo(implicit);
    }

    @Test
    void signaturesAreIdenticalToHexCoreWithTheSameKey() {
        HexSigningKey key = goldenKey(tempDir);
        for (String doc : List.of("names", "versions", "package")) {
            byte[] payload = golden(doc + ".bin");
            byte[] signedByJava = HexRegistry.encodeSigned(new Signed(payload, key.sign(payload)));
            assertThat(signedByJava).as(doc).isEqualTo(golden(doc + ".signed"));
        }
    }

    @Test
    void unpacksDocumentsHexCoreBuiltAndVerifiesTheirSignatures() {
        HexSigningKey key = goldenKey(tempDir);
        for (String doc : List.of("names", "versions", "package")) {
            Signed signed = HexRegistry.decodeSigned(HexRegistry.gunzip(golden(doc + ".gz")));
            assertThat(signed.payload()).isEqualTo(golden(doc + ".bin"));
            assertThat(key.verify(signed.payload(), signed.signature())).as(doc).isTrue();
        }
        assertThat(HexRegistry.unpack(golden("names.gz"), HexRegistry::decodeNames)).isEqualTo(goldenNames());
    }

    @Test
    void packFramesSignsAndGzips() {
        HexSigningKey key = goldenKey(tempDir);
        byte[] payload = HexRegistry.encodeNames(goldenNames());
        byte[] packed = HexRegistry.pack(payload, key::sign);
        assertThat(packed[0] & 0xFF).isEqualTo(0x1F);
        assertThat(packed[1] & 0xFF).isEqualTo(0x8B);
        assertThat(HexRegistry.gunzip(packed)).isEqualTo(golden("names.signed"));
    }

    @Test
    void emptyDocumentsStillCarryTheRequiredRepositoryField() {
        byte[] names = HexRegistry.encodeNames(new Names("", List.of()));
        assertThat(names).containsExactly(0x12, 0x00);
        assertThat(HexRegistry.decodeNames(names)).isEqualTo(new Names("", List.of()));
        assertThat(HexRegistry.decodeVersions(HexRegistry.encodeVersions(new Versions("x", List.of()))))
                .isEqualTo(new Versions("x", List.of()));
        Signed empty = HexRegistry.decodeSigned(HexRegistry.encodeSigned(new Signed(new byte[0], null)));
        assertThat(empty.payload()).isEmpty();
        assertThat(empty.signature()).isEmpty();
    }

    @Test
    void unknownFieldsAreSkipped() {
        byte[] known = HexRegistry.encodeNames(goldenNames());
        byte[] extra = new Protobuf.Writer()
                .requiredString(9, "future string field")
                .requiredVarint(10, 42)
                .toByteArray();
        byte[] fixed = {(byte) ((11 << 3) | 1), 1, 2, 3, 4, 5, 6, 7, 8, (byte) ((12 << 3) | 5), 1, 2, 3, 4};
        byte[] combined = concat(extra, known, fixed);
        assertThat(HexRegistry.decodeNames(combined)).isEqualTo(goldenNames());
    }

    @Test
    void malformedProtobufIsRefused() {
        byte[] good = golden("package.bin");
        byte[] truncated = Arrays.copyOf(good, good.length - 7);
        assertThatThrownBy(() -> HexRegistry.decodePackage(truncated)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HexRegistry.decodeSigned(new byte[] {0x0A, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HexRegistry.decodeNames(new byte[] {(byte) 0x80}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gunzipRefusesWhatIsNotGzip() {
        assertThatThrownBy(() -> HexRegistry.gunzip("plain text".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a gzip document");
        assertThatThrownBy(() -> HexRegistry.gunzip(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        byte[] gz = HexRegistry.gzip("hello".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> HexRegistry.gunzip(Arrays.copyOf(gz, gz.length - 4)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gunzipRefusesDecompressionBombs() {
        byte[] bomb = HexRegistry.gzip(new byte[HexRegistry.MAX_DOCUMENT_SIZE + 1]);
        assertThat(bomb.length).isLessThan(200_000);
        assertThatThrownBy(() -> HexRegistry.gunzip(bomb)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void retirementReasons() {
        assertThat(RetirementReason.fromKey("security")).contains(RetirementReason.SECURITY);
        assertThat(RetirementReason.fromKey("RETIRED_DEPRECATED")).contains(RetirementReason.DEPRECATED);
        assertThat(RetirementReason.fromKey(" Renamed ")).contains(RetirementReason.RENAMED);
        assertThat(RetirementReason.fromKey("invalid")).contains(RetirementReason.INVALID);
        assertThat(RetirementReason.fromKey("other")).contains(RetirementReason.OTHER);
        assertThat(RetirementReason.fromKey("bogus")).isEmpty();
        assertThat(RetirementReason.fromKey(null)).isEmpty();
        assertThat(RetirementReason.fromCode(2)).isEqualTo(RetirementReason.SECURITY);
        assertThat(RetirementReason.fromCode(99)).isEqualTo(RetirementReason.OTHER);
        assertThat(RetirementReason.SECURITY.key()).isEqualTo("security");
        assertThat(RetirementReason.RENAMED.code()).isEqualTo(4);
    }

    @Test
    void recordsNormaliseNulls() {
        Release release = new Release(null, null, List.of(), null, null);
        assertThat(release.version()).isEmpty();
        assertThat(release.innerChecksum()).isEmpty();
        assertThat(release.outerChecksum()).isEmpty();
        assertThat(release.advisoryIndexes()).isEmpty();
        assertThat(new Dependency(null, null, false, null, null)).isEqualTo(new Dependency("", "", false, "", ""));
        assertThat(new Retirement(null, null)).isEqualTo(new Retirement(RetirementReason.OTHER, ""));
        assertThat(new Package(null, null, List.of()).advisories()).isEmpty();
        assertThat(Timestamp.of(Instant.parse("2024-05-06T07:08:09.123456789Z")).toInstant())
                .isEqualTo(Instant.parse("2024-05-06T07:08:09.123456789Z"));
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }
}
