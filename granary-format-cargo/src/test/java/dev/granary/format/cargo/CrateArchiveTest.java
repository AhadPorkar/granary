package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.format.cargo.CargoTestSupport.Tar;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrateArchiveTest {

    private static final byte[] TOML = CargoTestSupport.manifest("acme", "1.0.0").getBytes(StandardCharsets.UTF_8);

    private static void verify(byte[] crate) throws Exception {
        CrateArchive.verify(new ByteArrayInputStream(crate), "acme", "1.0.0");
    }

    private static void assertRefused(byte[] crate, String message) {
        assertThatThrownBy(() -> verify(crate)).isInstanceOf(CargoError.class).hasMessageContaining(message);
    }

    @Test
    void aCrateAsCargoPackagesIt() {
        assertThatCode(() -> verify(CargoTestSupport.crate("acme", "1.0.0"))).doesNotThrowAnyException();
    }

    @Test
    void directoriesAndTheRootEntryAreFine() {
        byte[] crate = CargoTestSupport.gzip(new Tar().directory("acme-1.0.0").directory("acme-1.0.0/src")
                .file("acme-1.0.0/Cargo.toml", TOML).finish());
        assertThatCode(() -> verify(crate)).doesNotThrowAnyException();
    }

    @Test
    void dotSlashPrefixedEntriesAreAccepted() {
        byte[] crate = CargoTestSupport.gzip(new Tar().file("./acme-1.0.0/Cargo.toml", TOML).finish());
        assertThatCode(() -> verify(crate)).doesNotThrowAnyException();
    }

    @Test
    void buildMetadataIsPartOfTheDirectory() {
        byte[] crate = CargoTestSupport.crate("acme", "1.0.0+linux");
        assertThatCode(() -> CrateArchive.verify(new ByteArrayInputStream(crate), "acme", "1.0.0+linux"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> verify(crate)).isInstanceOf(CargoError.class).hasMessageContaining("outside");
    }

    @Test
    void longNamesViaGnuAndPaxExtensions() {
        String deep = "acme-1.0.0/src/" + "very-long-directory-name/".repeat(6) + "lib.rs";
        byte[] crate = CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .gnuLongName(deep, "x".getBytes(StandardCharsets.UTF_8))
                .paxPath(deep + ".bak", "y".getBytes(StandardCharsets.UTF_8))
                .finish());
        assertThatCode(() -> verify(crate)).doesNotThrowAnyException();
    }

    @Test
    void longNamesAreCheckedToo() {
        String escape = "../../" + "x".repeat(120);
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .gnuLongName(escape, new byte[1]).finish()), "outside");
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .paxPath("/etc/" + "x".repeat(120), new byte[1]).finish()), "outside");
    }

    @Test
    void missingManifest() {
        assertRefused(CargoTestSupport.tarGz(Map.of("acme-1.0.0/src/lib.rs", "")), "has no acme-1.0.0/Cargo.toml");
        // a directory called Cargo.toml is not a manifest
        assertRefused(CargoTestSupport.gzip(new Tar().directory("acme-1.0.0/Cargo.toml").finish()), "has no");
    }

    @Test
    void entriesOutsideTheCrateDirectory() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("acme-1.0.0/Cargo.toml", "x");
        files.put("other/evil.rs", "x");
        assertRefused(CargoTestSupport.tarGz(files), "`other/evil.rs`, which is outside");
        assertRefused(CargoTestSupport.tarGz(Map.of("/etc/passwd", "x")), "outside");
        assertRefused(CargoTestSupport.tarGz(Map.of("acme-1.0.0x/Cargo.toml", "x")), "outside");
        // another crate's directory is outside too, whatever the case
        assertRefused(CargoTestSupport.tarGz(Map.of("Acme-1.0.0/Cargo.toml", "x")), "outside");
    }

    @Test
    void traversalSegments() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("acme-1.0.0/Cargo.toml", "x");
        files.put("acme-1.0.0/../../etc/cron.d/x", "x");
        assertRefused(CargoTestSupport.tarGz(files), "`..` segment");
    }

    @Test
    void backslashesAndLinks() {
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .file("acme-1.0.0\\..\\x", new byte[0]).finish()), "outside");
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .file("acme-1.0.0/a\\b", new byte[0]).finish()), "illegal path");
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .entry("acme-1.0.0/hard", '1', new byte[0]).finish()), "hard link");
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .entry("acme-1.0.0/dev", '3', new byte[0]).finish()), "is a device");
    }

    @Test
    void onlyRegularFilesAndDirectoriesAreAccepted() {
        // cargo package never writes anything else, and crates.io refuses it too
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .entry("acme-1.0.0/README", '2', new byte[0]).finish()), "`acme-1.0.0/README`, which is a symbolic link");
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .entry("acme-1.0.0/pipe", '6', new byte[0]).finish()), "a FIFO");
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .entry("acme-1.0.0/big", '7', new byte[0]).finish()), "tar type `7`");
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML)
                .entry("acme-1.0.0/sparse", 'S', new byte[0]).finish()), "tar type `S`");
        byte[] plain = CargoTestSupport.gzip(new Tar().directory("acme-1.0.0").file("acme-1.0.0/Cargo.toml", TOML)
                .entry("acme-1.0.0/old-style", '\0', new byte[0]).finish());
        assertThatCode(() -> verify(plain)).doesNotThrowAnyException();
    }

    @Test
    void notGzip() {
        assertRefused("not a crate".getBytes(StandardCharsets.UTF_8), "not a valid gzip-compressed tar archive");
        assertRefused(new byte[]{0x1f, (byte) 0x8b, 8, 0}, "not a valid gzip-compressed tar archive");
    }

    @Test
    void gzipOfSomethingElse() {
        assertRefused(CargoTestSupport.gzip("hello world, not a tar".getBytes(StandardCharsets.UTF_8)), "truncated header");
        byte[] block = new byte[512];
        Arrays.fill(block, (byte) 'a');
        assertRefused(CargoTestSupport.gzip(block), "bad header checksum");
    }

    @Test
    void emptyArchive() {
        assertRefused(CargoTestSupport.gzip(new byte[1024]), "empty archive");
        assertRefused(CargoTestSupport.gzip(new byte[0]), "empty archive");
    }

    @Test
    void corruptHeader() {
        assertRefused(CargoTestSupport.gzip(new Tar().file("acme-1.0.0/Cargo.toml", TOML).corrupt("acme-1.0.0/x").finish()),
                "bad header checksum");
    }

    @Test
    void truncatedEntry() {
        byte[] tar = new Tar().file("acme-1.0.0/Cargo.toml", new byte[2000]).finish();
        assertRefused(CargoTestSupport.gzip(Arrays.copyOf(tar, 1024)), "truncated entry");
    }

    @Test
    void truncatedGzipStream() {
        byte[] crate = CargoTestSupport.crate("acme", "1.0.0");
        assertRefused(Arrays.copyOf(crate, crate.length / 2), "not a valid gzip-compressed tar archive");
    }

    @Test
    void paxRecordsAreParsedByByteLength() {
        // lengths count bytes: the two-byte characters make this record 20 bytes long
        byte[] records = "20 path=naïve/x.rs\n12 mtime=17\n".getBytes(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(CrateArchive.paxValue(records, "path")).isEqualTo("naïve/x.rs");
        org.assertj.core.api.Assertions.assertThat(CrateArchive.paxValue(records, "mtime")).isEqualTo("17");
        org.assertj.core.api.Assertions.assertThat(CrateArchive.paxValue(records, "size")).isNull();
        assertThatThrownBy(() -> CrateArchive.paxValue("99 path=x\n".getBytes(StandardCharsets.UTF_8), "path"))
                .isInstanceOf(CargoError.class);
        assertThatThrownBy(() -> CrateArchive.paxValue("xx path=x\n".getBytes(StandardCharsets.UTF_8), "path"))
                .isInstanceOf(CargoError.class);
    }
}
