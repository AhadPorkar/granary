package dev.granary.format.hex;

import static dev.granary.format.hex.HexTestSupport.golden;
import static dev.granary.format.hex.HexTestSupport.goldenText;
import static dev.granary.format.hex.HexTestSupport.header;
import static dev.granary.format.hex.HexTestSupport.pad;
import static dev.granary.format.hex.HexTestSupport.tar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HexTarballTest {

    @Test
    void readsATarballHexCoreBuiltWithTheSameChecksums() {
        HexTarball tarball = HexTarball.read(golden("acme_lib-1.2.3.tar"));
        String[] checksums = goldenText("acme_lib-1.2.3.checksums").strip().split("\n");
        assertThat(tarball.formatVersion()).isEqualTo("3");
        assertThat(tarball.innerChecksumHex()).isEqualTo(checksums[0]);
        assertThat(tarball.outerChecksumHex()).isEqualTo(checksums[1]);
        assertThat(tarball.contentsSize()).isEqualTo(162);
        assertThat(tarball.metadataText()).contains("{<<\"name\">>,<<\"acme_lib\">>}.");

        HexMetadata meta = tarball.metadata();
        assertThat(meta.name()).isEqualTo("acme_lib");
        assertThat(meta.version()).isEqualTo("1.2.3");
        assertThat(meta.app()).isEqualTo("acme_lib");
        assertThat(meta.description()).isEqualTo("Ünïcode description with \"quotes\" and a\nnewline");
        assertThat(meta.licenses()).containsExactly("Apache-2.0", "MIT");
        assertThat(meta.links()).containsEntry("GitHub", "https://example.test/acme");
        assertThat(meta.buildTools()).containsExactly("mix");
        assertThat(meta.elixir()).isEqualTo("~> 1.15");
        assertThat(meta.files()).containsExactly("lib", "lib/acme_lib.ex", "mix.exs");
        assertThat(meta.requirements()).containsExactly(
                new HexMetadata.Requirement("jason", "jason", "~> 1.4", false, "hexpm"),
                new HexMetadata.Requirement("telemetry", "telemetry", "~> 1.0", true, "hexpm"));
    }

    @Test
    void readsTarballsTheTestsBuild() {
        HexTarball tarball = HexTarball.read(HexTestSupport.release("acme", "0.1.0-rc.1"));
        assertThat(tarball.metadata().name()).isEqualTo("acme");
        assertThat(tarball.metadata().version()).isEqualTo("0.1.0-rc.1");
        assertThat(tarball.innerChecksum()).hasSize(32);
        assertThat(tarball.outerChecksum()).isEqualTo(HexTarball.sha256(HexTestSupport.release("acme", "0.1.0-rc.1")));
    }

    @Test
    void aWrongChecksumIsRefused() {
        Map<String, byte[]> files = entries(golden("acme_lib-1.2.3.tar"));
        files.put("CHECKSUM", "0".repeat(64).getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> HexTarball.read(tar(files))).isInstanceOf(HexTarball.TarballException.class)
                .hasMessageContaining("checksum mismatch");

        Map<String, byte[]> shortChecksum = entries(golden("acme_lib-1.2.3.tar"));
        shortChecksum.put("CHECKSUM", "ABC".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> HexTarball.read(tar(shortChecksum))).hasMessageContaining("checksum mismatch");

        Map<String, byte[]> tampered = entries(golden("acme_lib-1.2.3.tar"));
        byte[] contents = tampered.get("contents.tar.gz").clone();
        contents[contents.length - 1] ^= 1;
        tampered.put("contents.tar.gz", contents);
        assertThatThrownBy(() -> HexTarball.read(tar(tampered))).hasMessageContaining("checksum mismatch");
    }

    @Test
    void aLowerCaseChecksumWithANewlineIsAccepted() {
        Map<String, byte[]> files = entries(golden("acme_lib-1.2.3.tar"));
        String lower = new String(files.get("CHECKSUM"), StandardCharsets.US_ASCII).toLowerCase() + "\n";
        files.put("CHECKSUM", lower.getBytes(StandardCharsets.US_ASCII));
        assertThat(HexTarball.read(tar(files)).metadata().name()).isEqualTo("acme_lib");
    }

    @Test
    void missingEntriesAndOldFormatsAreRefused() {
        for (String missing : new String[] {"VERSION", "CHECKSUM", "metadata.config", "contents.tar.gz"}) {
            Map<String, byte[]> files = entries(golden("acme_lib-1.2.3.tar"));
            files.remove(missing);
            assertThatThrownBy(() -> HexTarball.read(tar(files))).as(missing)
                    .isInstanceOf(HexTarball.TarballException.class).hasMessageContaining("missing " + missing);
        }
        Map<String, byte[]> v2 = entries(golden("acme_lib-1.2.3.tar"));
        v2.put("VERSION", "2".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> HexTarball.read(tar(v2))).hasMessageContaining("unsupported tarball version '2'");
    }

    @Test
    void invalidMetadataIsReportedAsATarballProblem() {
        assertThatThrownBy(() -> HexTarball.read(HexTestSupport.release("{<<\"name\">>, <<\"x\">>")))
                .isInstanceOf(HexTarball.TarballException.class).hasMessageContaining("Erlang term syntax");
        assertThatThrownBy(() -> HexTarball.read(HexTestSupport.release(HexTestSupport.metadata("Bad-Name", "1.0.0", "d"))))
                .isInstanceOf(HexTarball.TarballException.class).hasMessageContaining("invalid package name");
        assertThatThrownBy(() -> HexTarball.read(HexTestSupport.release(HexTestSupport.metadata("ok", "1.0", "d"))))
                .isInstanceOf(HexTarball.TarballException.class).hasMessageContaining("invalid version");
    }

    @Test
    void notATarIsRefused() {
        assertThatThrownBy(() -> HexTarball.read(new byte[0])).hasMessageContaining("not a tar archive");
        assertThatThrownBy(() -> HexTarball.read(new byte[2048])).hasMessageContaining("not a tar archive");
        assertThatThrownBy(() -> HexTarball.read("PK\u0003\u0004 a zip".getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(HexTarball.TarballException.class);
        byte[] gzipped = HexTestSupport.gzip(golden("acme_lib-1.2.3.tar"));
        assertThatThrownBy(() -> HexTarball.read(gzipped)).isInstanceOf(HexTarball.TarballException.class);
    }

    @Test
    void truncatedTarballsAreRefused() {
        byte[] good = golden("acme_lib-1.2.3.tar");
        assertThatThrownBy(() -> HexTarball.read(Arrays.copyOf(good, 512 + 100)))
                .isInstanceOf(HexTarball.TarballException.class);
        byte[] badSize = good.clone();
        System.arraycopy("zzzzzzzzzzz".getBytes(StandardCharsets.US_ASCII), 0, badSize, 124, 11);
        assertThatThrownBy(() -> HexTarball.read(badSize)).hasMessageContaining("malformed size");
    }

    @Test
    void duplicateEntriesAndEntryFloodsAreRefused() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 2; i++) {
            out.writeBytes(header("VERSION", 1, '0'));
            out.writeBytes(padded("3".getBytes(StandardCharsets.US_ASCII)));
        }
        out.writeBytes(new byte[1024]);
        assertThatThrownBy(() -> HexTarball.entries(out.toByteArray())).hasMessageContaining("duplicate entry 'VERSION'");

        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 70; i++) {
            many.put("file" + i, new byte[] {1});
        }
        assertThatThrownBy(() -> HexTarball.entries(tar(many))).hasMessageContaining("too many entries");
    }

    @Test
    void understandsDotSlashNamesPaxAndGnuLongNamesAndSkipsDirectories() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header("./", 0, '5'));
        out.writeBytes(header("./VERSION", 1, '0'));
        out.writeBytes(padded("3".getBytes(StandardCharsets.US_ASCII)));
        String longName = "a/" + "x".repeat(150) + ".txt";
        byte[] longBytes = (longName + "\0").getBytes(StandardCharsets.UTF_8);
        out.writeBytes(header("././@LongLink", longBytes.length, 'L'));
        out.writeBytes(padded(longBytes));
        out.writeBytes(header("truncated-name", 2, '0'));
        out.writeBytes(padded("gn".getBytes(StandardCharsets.US_ASCII)));
        String record = " path=pax/name.txt\n";
        String pax = (record.length() + 2) + record;
        out.writeBytes(header("PaxHeader", pax.length(), 'x'));
        out.writeBytes(padded(pax.getBytes(StandardCharsets.UTF_8)));
        out.writeBytes(header("ignored", 3, '0'));
        out.writeBytes(padded("pax".getBytes(StandardCharsets.US_ASCII)));
        out.writeBytes(header("global", 0, 'g'));
        out.writeBytes(header("link", 0, '2'));
        out.writeBytes(new byte[1024]);

        Map<String, byte[]> files = HexTarball.entries(out.toByteArray());
        assertThat(files).containsOnlyKeys("VERSION", longName, "pax/name.txt");
        assertThat(new String(files.get(longName), StandardCharsets.US_ASCII)).isEqualTo("gn");
        assertThat(new String(files.get("pax/name.txt"), StandardCharsets.US_ASCII)).isEqualTo("pax");
    }

    @Test
    void ustarPrefixesAreJoined() {
        byte[] h = header("name.txt", 1, '0');
        byte[] prefix = "some/dir".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, h, 345, prefix.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h);
        out.writeBytes(padded(new byte[] {'z'}));
        out.writeBytes(new byte[1024]);
        assertThat(HexTarball.entries(out.toByteArray())).containsOnlyKeys("some/dir/name.txt");
    }

    @Test
    void gnuBase256SizesAreRead() {
        byte[] h = header("big", 0, '0');
        Arrays.fill(h, 124, 136, (byte) 0);
        h[124] = (byte) 0x80;
        h[135] = 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h);
        out.writeBytes(padded(new byte[] {1, 2, 3, 4}));
        out.writeBytes(new byte[1024]);
        assertThat(HexTarball.entries(out.toByteArray()).get("big")).containsExactly(1, 2, 3, 4);
    }

    private static byte[] padded(byte[] data) {
        return Arrays.copyOf(data, data.length + pad(data.length));
    }

    private static Map<String, byte[]> entries(byte[] tarball) {
        return new LinkedHashMap<>(HexTarball.entries(tarball));
    }
}
