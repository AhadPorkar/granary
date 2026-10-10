package dev.granary.format.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TarReaderTest {

    @Test
    void readsUstarEntriesInOrderAndSkipsUnreadOnes() throws IOException {
        byte[] tar = TarFixtures.tar(List.of(
                new TarFixtures.File("pkg/", ""),
                new TarFixtures.File("pkg/DESCRIPTION", "Package: demo\n"),
                new TarFixtures.File("pkg/R/big.R", "x".repeat(1500)),
                new TarFixtures.File("pkg/NAMESPACE", "export(f)\n")));
        try (TarReader reader = new TarReader(new ByteArrayInputStream(tar))) {
            TarReader.Entry dir = reader.next();
            assertThat(dir.name()).isEqualTo("pkg/");
            assertThat(dir.isDirectory()).isTrue();
            assertThat(dir.isFile()).isFalse();
            TarReader.Entry description = reader.next();
            assertThat(description.name()).isEqualTo("pkg/DESCRIPTION");
            assertThat(description.nameBelowRoot()).isEqualTo("DESCRIPTION");
            assertThat(description.fileName()).isEqualTo("DESCRIPTION");
            assertThat(description.size()).isEqualTo(14);
            assertThat(new String(reader.readAll(1000), StandardCharsets.UTF_8)).isEqualTo("Package: demo\n");
            TarReader.Entry big = reader.next();
            assertThat(big.size()).isEqualTo(1500);
            TarReader.Entry namespace = reader.next();
            assertThat(namespace.name()).isEqualTo("pkg/NAMESPACE");
            assertThat(new String(reader.readAll(1000), StandardCharsets.UTF_8)).isEqualTo("export(f)\n");
            assertThat(reader.next()).isNull();
        }
    }

    @Test
    void gnuLongNamesAndUstarPrefixesAndPaxHeadersAreResolved() throws IOException {
        String longName = "Some-Distribution-1.0/lib/" + "Very/".repeat(30) + "Deep.pm";
        byte[] gnu = TarFixtures.tar(List.of(new TarFixtures.File(longName, "package Deep;\n")));
        try (TarReader reader = new TarReader(new ByteArrayInputStream(gnu))) {
            TarReader.Entry entry = reader.next();
            assertThat(entry.name()).isEqualTo(longName);
            assertThat(new String(reader.readAll(100), StandardCharsets.UTF_8)).isEqualTo("package Deep;\n");
            assertThat(reader.next()).isNull();
        }

        byte[] prefixed = TarFixtures.tarWithPrefix("dist-1.0/lib/Acme", "Hello.pm", "package Acme::Hello;\n".getBytes(StandardCharsets.UTF_8));
        try (TarReader reader = new TarReader(new ByteArrayInputStream(prefixed))) {
            assertThat(reader.next().name()).isEqualTo("dist-1.0/lib/Acme/Hello.pm");
        }

        byte[] pax = TarFixtures.paxTar("pkg/DESCRIPTION", "Package: paxed\n".getBytes(StandardCharsets.UTF_8));
        try (TarReader reader = new TarReader(new ByteArrayInputStream(pax))) {
            TarReader.Entry entry = reader.next();
            assertThat(entry.name()).isEqualTo("pkg/DESCRIPTION");
            assertThat(entry.size()).isEqualTo(15);
            assertThat(new String(reader.readAll(100), StandardCharsets.UTF_8)).isEqualTo("Package: paxed\n");
            assertThat(reader.next()).isNull();
        }
    }

    @Test
    void gzipArchivesAndLimits() throws IOException {
        byte[] gz = TarFixtures.tarGz("a/x.txt", "12345", "a/y.txt", "67");
        List<String> names = new ArrayList<>();
        try (TarReader reader = TarReader.gzip(new ByteArrayInputStream(gz))) {
            TarReader.Entry entry = reader.next();
            names.add(entry.name());
            assertThatThrownBy(() -> reader.readAll(3)).isInstanceOf(IOException.class).hasMessageContaining("exceeds");
            names.add(reader.next().name());
            assertThat(new String(reader.readAll(10), StandardCharsets.UTF_8)).isEqualTo("67");
        }
        assertThat(names).containsExactly("a/x.txt", "a/y.txt");
    }

    @Test
    void emptyAndTruncatedArchives() throws IOException {
        try (TarReader reader = new TarReader(new ByteArrayInputStream(new byte[1024]))) {
            assertThat(reader.next()).isNull();
        }
        try (TarReader reader = new TarReader(new ByteArrayInputStream(new byte[0]))) {
            assertThat(reader.next()).isNull();
        }
        byte[] header = TarFixtures.header("file", 100, '0', "");
        try (TarReader reader = new TarReader(new ByteArrayInputStream(header))) {
            assertThat(reader.next().size()).isEqualTo(100);
            assertThatThrownBy(() -> reader.readAll(1000)).isInstanceOf(IOException.class).hasMessageContaining("truncated");
        }
        byte[] bad = TarFixtures.header("file", 0, '0', "");
        System.arraycopy("zzzz".getBytes(StandardCharsets.US_ASCII), 0, bad, 124, 4);
        try (TarReader reader = new TarReader(new ByteArrayInputStream(bad))) {
            assertThatThrownBy(reader::next).isInstanceOf(IOException.class).hasMessageContaining("malformed");
        }
    }

    @Test
    void negativeOrHugeBase256SizesAreMalformedNotRuntimeErrors() throws IOException {
        for (byte fill : new byte[] {(byte) 0xff, (byte) 0x7f}) {
            byte[] header = TarFixtures.header("pkg/DESCRIPTION", 0, '0', "");
            header[124] = (byte) 0x80;
            for (int i = 125; i < 136; i++) {
                header[i] = fill;
            }
            byte[] archive = new byte[header.length + 1024];
            System.arraycopy(header, 0, archive, 0, header.length);
            try (TarReader reader = new TarReader(new ByteArrayInputStream(archive))) {
                assertThatThrownBy(() -> {
                    TarReader.Entry entry = reader.next();
                    reader.readAll(1024);
                    reader.next();
                    assertThat(entry).isNotNull();
                }).isInstanceOf(IOException.class);
            }
        }
        byte[] longName = TarFixtures.header("././@LongLink", 0, 'L', "");
        longName[124] = (byte) 0x80;
        for (int i = 125; i < 136; i++) {
            longName[i] = (byte) 0xff;
        }
        try (TarReader reader = new TarReader(new ByteArrayInputStream(longName))) {
            assertThatThrownBy(reader::next).isInstanceOf(IOException.class);
        }
    }

    @Test
    void hugeLongNameOrPaxHeadersAreRefusedBeforeTheyAreRead() throws IOException {
        for (char type : new char[] {'L', 'x'}) {
            byte[] header = TarFixtures.header("././@LongLink", 512L * 1024 * 1024, type, "");
            long[] consumed = {0};
            // an endless archive: reading the declared half gigabyte into memory would be the failure
            java.io.InputStream endless = new java.io.InputStream() {
                @Override
                public int read() {
                    consumed[0]++;
                    return consumed[0] <= header.length ? header[(int) consumed[0] - 1] & 0xff : 'a';
                }
            };
            try (TarReader reader = new TarReader(endless)) {
                assertThatThrownBy(reader::next).isInstanceOf(IOException.class).hasMessageContaining("extended header");
            }
            assertThat(consumed[0]).as("bytes read for type " + type).isLessThanOrEqualTo(TarReader.MAX_HEADER_DATA);
        }
    }
}
