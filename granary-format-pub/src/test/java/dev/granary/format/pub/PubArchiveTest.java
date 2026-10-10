package dev.granary.format.pub;

import static dev.granary.format.pub.PubTestSupport.gzip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.format.pub.PubTestSupport.Tar;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PubArchiveTest {

    private static final byte[] PUBSPEC = PubTestSupport.pubspec("demo", "1.0.0").getBytes(StandardCharsets.UTF_8);
    private static final byte[] CODE = "void main() {}\n".getBytes(StandardCharsets.UTF_8);

    private static PubArchive read(byte[] targz) throws Exception {
        return PubArchive.read(new ByteArrayInputStream(targz));
    }

    private static PubArchive read(byte[] targz, PubArchive.Limits limits) throws Exception {
        return PubArchive.read(new ByteArrayInputStream(targz), limits);
    }

    @Test
    void readsThePubspecOfAnArchiveAsDartPubPublishBuildsIt() throws Exception {
        PubArchive archive = read(PubTestSupport.archive("demo", "1.2.3"));
        assertThat(new String(archive.pubspec(), StandardCharsets.UTF_8)).contains("version: 1.2.3");
        assertThat(archive.entries()).isEqualTo(5);
        assertThat(archive.uncompressedSize()).isGreaterThan(5 * 512);
    }

    @Test
    void acceptsDotSlashPrefixesDirectoriesAndInternalLinks() throws Exception {
        byte[] tar = new Tar()
                .directory("./")
                .directory("./lib/")
                .file("./lib/a.dart", CODE)
                .symlink("./lib/b.dart", "a.dart")
                .symlink("./lib/src/c.dart", "../a.dart")
                .hardlink("./lib/d.dart", "lib/a.dart")
                .file("./pubspec.yaml", PUBSPEC)
                .finish();
        assertThat(read(gzip(tar)).pubspec()).isEqualTo(PUBSPEC);
    }

    @Test
    void understandsGnuLongNamesPaxPathsAndUstarPrefixes() throws Exception {
        String deep = "lib/" + "very_long_directory_name/".repeat(6) + "file.dart";
        byte[] tar = new Tar()
                .gnuLongName(deep, CODE)
                .paxName("lib/" + "x".repeat(150) + ".dart", CODE)
                .ustarPrefix("lib/src", "y.dart", CODE)
                .file("pubspec.yaml", PUBSPEC)
                .finish();
        PubArchive archive = read(gzip(tar));
        assertThat(archive.pubspec()).isEqualTo(PUBSPEC);
        assertThat(archive.entries()).isEqualTo(4);
    }

    @Test
    void aPaxPathCanNameThePubspec() throws Exception {
        byte[] tar = new Tar().paxName("pubspec.yaml", PUBSPEC).finish();
        assertThat(read(gzip(tar)).pubspec()).isEqualTo(PUBSPEC);
    }

    @Test
    void toleratesAMissingEndOfArchiveMarker() throws Exception {
        byte[] tar = new Tar().file("pubspec.yaml", PUBSPEC).unterminated();
        assertThat(read(gzip(tar)).pubspec()).isEqualTo(PUBSPEC);
    }

    @Test
    void refusesArchivesWithoutARootPubspec() {
        byte[] nested = gzip(new Tar().file("demo/pubspec.yaml", PUBSPEC).finish());
        assertThatThrownBy(() -> read(nested)).isInstanceOf(PubArchive.InvalidArchiveException.class)
                .hasMessageContaining("no pubspec.yaml at its root");
        byte[] empty = gzip(new Tar().finish());
        assertThatThrownBy(() -> read(empty)).hasMessageContaining("no pubspec.yaml");
        byte[] directory = gzip(new Tar().directory("pubspec.yaml/").finish());
        assertThatThrownBy(() -> read(directory)).hasMessageContaining("no pubspec.yaml");
    }

    @Test
    void refusesTwoPubspecs() {
        byte[] tar = gzip(new Tar().file("pubspec.yaml", PUBSPEC).file("./pubspec.yaml", PUBSPEC).finish());
        assertThatThrownBy(() -> read(tar)).hasMessageContaining("twice");
    }

    @Test
    void refusesPathsOutsideThePackage() {
        assertThatThrownBy(() -> read(gzip(new Tar().file("../evil.dart", CODE).file("pubspec.yaml", PUBSPEC).finish())))
                .hasMessageContaining("outside the package");
        assertThatThrownBy(() -> read(gzip(new Tar().file("lib/../../evil", CODE).file("pubspec.yaml", PUBSPEC).finish())))
                .hasMessageContaining("outside the package");
        assertThatThrownBy(() -> read(gzip(new Tar().file("/etc/passwd", CODE).file("pubspec.yaml", PUBSPEC).finish())))
                .hasMessageContaining("absolute path");
        assertThatThrownBy(() -> read(gzip(new Tar().file("C:/x", CODE).file("pubspec.yaml", PUBSPEC).finish())))
                .hasMessageContaining("absolute path");
        assertThatThrownBy(() -> read(gzip(new Tar().file("lib\\..\\..\\x", CODE).file("pubspec.yaml", PUBSPEC).finish())))
                .hasMessageContaining("outside the package");
    }

    @Test
    void refusesLinksOutOfThePackage() {
        assertThatThrownBy(() -> read(gzip(new Tar().symlink("lib/x", "../../etc/passwd").file("pubspec.yaml", PUBSPEC).finish())))
                .hasMessageContaining("link pointing outside");
        assertThatThrownBy(() -> read(gzip(new Tar().symlink("x", "/etc/passwd").file("pubspec.yaml", PUBSPEC).finish())))
                .hasMessageContaining("link to an absolute path");
        assertThatThrownBy(() -> read(gzip(new Tar().hardlink("x", "../y").file("pubspec.yaml", PUBSPEC).finish())))
                .hasMessageContaining("link pointing outside");
    }

    @Test
    void checksTheWholeTargetOfLinksWithLongTargets() throws Exception {
        // the header holds only the first 100 bytes of the target; the rest climbs out of the package
        String escaping = "a/".repeat(50) + "../".repeat(60) + "etc/passwd";
        assertThat(escaping.substring(0, 100)).doesNotContain("..");
        assertThatThrownBy(() -> read(gzip(new Tar().gnuLongSymlink("lib/x", escaping).file("pubspec.yaml", PUBSPEC).finish())))
                .isInstanceOf(PubArchive.InvalidArchiveException.class)
                .hasMessageContaining("link pointing outside");
        assertThatThrownBy(() -> read(gzip(new Tar().paxSymlink("lib/x", "../../../../etc/passwd", "a.dart")
                .file("pubspec.yaml", PUBSPEC).finish())))
                .isInstanceOf(PubArchive.InvalidArchiveException.class)
                .hasMessageContaining("link pointing outside");
        assertThatThrownBy(() -> read(gzip(new Tar().paxSymlink("lib/x", "/etc/passwd", "a.dart")
                .file("pubspec.yaml", PUBSPEC).finish())))
                .hasMessageContaining("link to an absolute path");

        // long targets that stay inside the package are fine, and the long target does not leak into the next entry
        String inside = "very_long_directory_name/".repeat(6) + "file.dart";
        byte[] tar = new Tar().gnuLongSymlink("lib/x", inside).paxSymlink("lib/y", "../lib/" + inside, "a.dart")
                .symlink("lib/z", "a.dart").file("pubspec.yaml", PUBSPEC).finish();
        assertThat(read(gzip(tar)).pubspec()).isEqualTo(PUBSPEC);
    }

    @Test
    void parsesPaxLinkpathRecords() throws Exception {
        byte[] records = "16 path=foo/bar\n19 linkpath=../baz\n".getBytes(StandardCharsets.UTF_8);
        assertThat(PubArchive.paxRecord(records, "linkpath")).isEqualTo("../baz");
        assertThat(PubArchive.paxRecord(records, "path")).isEqualTo("foo/bar");
        assertThat(PubArchive.paxRecord(records, "uname")).isNull();
    }

    @Test
    void refusesInputThatIsNotGzip() {
        assertThatThrownBy(() -> read(new Tar().file("pubspec.yaml", PUBSPEC).finish()))
                .hasMessageContaining("not gzip-compressed");
        assertThatThrownBy(() -> read(new byte[0])).hasMessageContaining("not gzip-compressed");
        assertThatThrownBy(() -> read("PK\u0003\u0004 a zip".getBytes(StandardCharsets.ISO_8859_1)))
                .hasMessageContaining("not gzip-compressed");
    }

    @Test
    void refusesCorruptOrTruncatedArchives() {
        byte[] good = PubTestSupport.archive("demo", "1.0.0");
        byte[] truncated = Arrays.copyOf(good, good.length / 2);
        assertThatThrownBy(() -> read(truncated)).isInstanceOf(PubArchive.InvalidArchiveException.class);

        byte[] badChecksum = new Tar().raw(Tar.header("pubspec.yaml", '0', PUBSPEC.length, "", null, false))
                .raw(PUBSPEC).raw(new byte[512 - PUBSPEC.length % 512]).finish();
        assertThatThrownBy(() -> read(gzip(badChecksum))).hasMessageContaining("checksum");

        byte[] gzipOfText = gzip("this is not a tar file at all".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> read(gzipOfText)).isInstanceOf(PubArchive.InvalidArchiveException.class);

        byte[] partialData = new Tar().raw(Tar.header("pubspec.yaml", '0', 4096, "", null, true)).raw(PUBSPEC).unterminated();
        assertThatThrownBy(() -> read(gzip(partialData))).hasMessageContaining("truncated");

        byte[] corruptGzip = good.clone();
        for (int i = 20; i < corruptGzip.length - 8; i++) {
            corruptGzip[i] = (byte) 0xff;
        }
        assertThatThrownBy(() -> read(corruptGzip)).isInstanceOf(PubArchive.InvalidArchiveException.class);
    }

    @Test
    void boundsTheUncompressedSize() {
        byte[] big = new byte[2 * 1024 * 1024];
        byte[] tar = gzip(new Tar().file("assets/zeros.bin", big).file("pubspec.yaml", PUBSPEC).finish());
        assertThatThrownBy(() -> read(tar, new PubArchive.Limits(1024 * 1024, 100, 64 * 1024)))
                .isInstanceOf(PubArchive.InvalidArchiveException.class)
                .hasMessageContaining("expands to more than 1 MiB");
    }

    @Test
    void boundsTheNumberOfEntriesAndThePubspecSize() {
        Tar many = new Tar();
        for (int i = 0; i < 20; i++) {
            many.file("lib/f" + i + ".dart", CODE);
        }
        byte[] manyEntries = gzip(many.file("pubspec.yaml", PUBSPEC).finish());
        assertThatThrownBy(() -> read(manyEntries, new PubArchive.Limits(1 << 30, 10, 64 * 1024)))
                .hasMessageContaining("more than 10 entries");

        byte[] hugePubspec = gzip(new Tar().file("pubspec.yaml", new byte[200 * 1024]).finish());
        assertThatThrownBy(() -> read(hugePubspec, new PubArchive.Limits(1 << 30, 10, 64 * 1024)))
                .hasMessageContaining("pubspec.yaml in the package archive is larger");
    }

    @Test
    void readsBase256Sizes() throws Exception {
        byte[] header = Tar.header("pubspec.yaml", '0', 0, "", null, true);
        Arrays.fill(header, 124, 136, (byte) 0);
        header[124] = (byte) 0x80;
        header[135] = (byte) PUBSPEC.length;
        // recompute the checksum after editing the size field
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int sum = 0;
        for (byte b : header) {
            sum += b & 0xff;
        }
        Tar.octal(header, 148, 7, sum);
        header[155] = ' ';
        assertThat(PubArchive.size(header)).isEqualTo(PUBSPEC.length);
        byte[] tar = new Tar().raw(header).raw(PUBSPEC).raw(new byte[512 - PUBSPEC.length % 512]).finish();
        assertThat(read(gzip(tar)).pubspec()).isEqualTo(PUBSPEC);
    }

    @Test
    void normalisesNames() throws Exception {
        assertThat(PubArchive.normalise("./lib//a.dart")).isEqualTo("lib/a.dart");
        assertThat(PubArchive.normalise("lib/./a.dart/")).isEqualTo("lib/a.dart");
        assertThat(PubArchive.normalise("./")).isEmpty();
        assertThat(PubArchive.normalise("lib\\a.dart")).isEqualTo("lib/a.dart");
    }

    @Test
    void parsesPaxRecords() throws Exception {
        byte[] records = "20 mtime=1700000000\n16 path=foo/bar\n".getBytes(StandardCharsets.UTF_8);
        assertThat(PubArchive.paxPath(records)).isEqualTo("foo/bar");
        assertThat(PubArchive.paxPath("20 mtime=1700000000\n".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThatThrownBy(() -> PubArchive.paxPath("xx path=a\n".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("pax");
        assertThatThrownBy(() -> PubArchive.paxPath("99 path=a\n".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("pax");
    }
}
