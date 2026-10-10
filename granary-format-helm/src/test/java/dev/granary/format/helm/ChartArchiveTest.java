package dev.granary.format.helm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.format.helm.TarFixtures.Entry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class ChartArchiveTest {

    private static ChartArchive.Contents read(byte[] tgz) throws IOException {
        return ChartArchive.read(new ByteArrayInputStream(tgz));
    }

    @Test
    void readsAChartPackagedByHelm() throws IOException {
        ChartArchive.Contents contents = read(TarFixtures.resource("charts/demo-app-0.2.0.tgz"));
        assertThat(contents.chartYaml()).containsEntry("name", "demo-app").containsEntry("version", "0.2.0")
                .containsEntry("apiVersion", "v2").containsEntry("appVersion", "1.16.0").containsEntry("type", "application");
        assertThat(contents.requirements()).isNull();
    }

    @Test
    void readsBuiltCharts() throws IOException {
        ChartArchive.Contents contents = read(TarFixtures.chart("nginx", "1.2.3", "keywords:\n  - web\n"));
        assertThat(contents.chartYaml()).containsEntry("name", "nginx").containsEntry("version", "1.2.3")
                .containsEntry("keywords", List.of("web"));
    }

    @Test
    void ignoresChartYamlOfSubcharts() throws IOException {
        byte[] tgz = TarFixtures.tgz(List.of(
                Entry.dir("parent/"),
                Entry.file("parent/charts/child/Chart.yaml", "apiVersion: v2\nname: child\nversion: 9.9.9\n"),
                Entry.file("parent/Chart.yaml", "apiVersion: v2\nname: parent\nversion: 1.0.0\n")));
        assertThat(read(tgz).chartYaml()).containsEntry("name", "parent");
    }

    @Test
    void readsRequirementsOfV1Charts() throws IOException {
        byte[] tgz = TarFixtures.tgz(List.of(
                Entry.file("legacy/Chart.yaml", "apiVersion: v1\nname: legacy\nversion: 0.1.0\n"),
                Entry.file("legacy/requirements.yaml", "dependencies:\n- name: redis\n  version: 1.x\n  repository: https://charts.example\n")));
        ChartArchive.Contents contents = read(tgz);
        assertThat(contents.requirements()).containsKey("dependencies");
    }

    @Test
    void longNamesInPaxHeadersAreHonoured() throws IOException {
        String dir = "a-chart-with-a-really-long-directory-name-" + "x".repeat(60);
        byte[] tgz = TarFixtures.tgz(List.of(
                Entry.file(dir + "/templates/" + "y".repeat(80) + ".yaml", "kind: ConfigMap\n"),
                Entry.file(dir + "/Chart.yaml", "apiVersion: v2\nname: long\nversion: 1.0.0\n")));
        assertThat(read(tgz).chartYaml()).containsEntry("name", "long");
    }

    @Test
    void dotSlashPrefixesAreAccepted() throws IOException {
        byte[] tgz = TarFixtures.tgz(List.of(Entry.file("./demo/Chart.yaml", "apiVersion: v2\nname: demo\nversion: 1.0.0\n")));
        assertThat(read(tgz).chartYaml()).containsEntry("name", "demo");
    }

    @Test
    void refusesArchivesWithoutChartYaml() {
        byte[] tgz = TarFixtures.tgz(List.of(Entry.file("demo/values.yaml", "a: 1\n")));
        assertThatThrownBy(() -> read(tgz)).isInstanceOf(ChartArchive.InvalidChartException.class)
                .hasMessageContaining("Chart.yaml file is missing");
    }

    @Test
    void refusesChartYamlAtTheTopLevel() {
        byte[] tgz = TarFixtures.tgz(List.of(Entry.file("Chart.yaml", "apiVersion: v2\nname: demo\nversion: 1.0.0\n")));
        assertThatThrownBy(() -> read(tgz)).isInstanceOf(ChartArchive.InvalidChartException.class)
                .hasMessageContaining("outside the base directory");
    }

    @Test
    void refusesParentReferences() {
        byte[] tgz = TarFixtures.tgz(List.of(Entry.file("demo/../../etc/passwd", "x"),
                Entry.file("demo/Chart.yaml", "apiVersion: v2\nname: demo\nversion: 1.0.0\n")));
        assertThatThrownBy(() -> read(tgz)).isInstanceOf(ChartArchive.InvalidChartException.class)
                .hasMessageContaining("parent directory");
    }

    @Test
    void refusesAbsolutePaths() {
        assertThatThrownBy(() -> ChartArchive.belowTopDirectory("demo//etc/passwd"))
                .isInstanceOf(ChartArchive.InvalidChartException.class).hasMessageContaining("absolute");
        assertThat(ChartArchive.belowTopDirectory("demo/")).isNull();
        assertThat(ChartArchive.belowTopDirectory("demo/./templates/./a.yaml")).isEqualTo("templates/a.yaml");
        assertThat(ChartArchive.belowTopDirectory("demo\\Chart.yaml")).isEqualTo("Chart.yaml");
        assertThat(ChartArchive.belowTopDirectory("")).isNull();
    }

    @Test
    void refusesWhatIsNotGzip() {
        assertThatThrownBy(() -> read("this is not a chart".getBytes())).isInstanceOf(ChartArchive.InvalidChartException.class)
                .hasMessageContaining("gzip");
        assertThatThrownBy(() -> read(new byte[0])).isInstanceOf(ChartArchive.InvalidChartException.class);
    }

    @Test
    void refusesGzipThatIsNotTar() {
        assertThatThrownBy(() -> read(TarFixtures.gzip("hello world, definitely not a tar header".repeat(20).getBytes())))
                .isInstanceOf(ChartArchive.InvalidChartException.class).hasMessageContaining("not a tar archive");
    }

    @Test
    void refusesEmptyTar() {
        assertThatThrownBy(() -> read(TarFixtures.gzip(new byte[1024]))).isInstanceOf(ChartArchive.InvalidChartException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void refusesTruncatedArchives() {
        byte[] tgz = TarFixtures.chart("demo", "1.0.0");
        byte[] truncated = Arrays.copyOf(tgz, tgz.length / 2);
        assertThatThrownBy(() -> read(truncated)).isInstanceOf(ChartArchive.InvalidChartException.class);
        byte[] tar = TarFixtures.tar(List.of(Entry.file("demo/Chart.yaml", "apiVersion: v2\nname: demo\nversion: 1.0.0\n"),
                Entry.file("demo/values.yaml", "x".repeat(4000))));
        byte[] cut = TarFixtures.gzip(Arrays.copyOf(tar, 512 * 3));
        assertThatThrownBy(() -> read(cut)).isInstanceOf(ChartArchive.InvalidChartException.class).hasMessageContaining("truncated");
    }

    @Test
    void refusesCorruptHeaders() {
        byte[] tar = TarFixtures.tar(List.of(Entry.file("demo/Chart.yaml", "apiVersion: v2\nname: demo\nversion: 1.0.0\n")));
        tar[10] ^= 0x55; // inside the name, so the checksum no longer matches
        assertThatThrownBy(() -> read(TarFixtures.gzip(tar))).isInstanceOf(ChartArchive.InvalidChartException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void refusesMalformedChartYaml() {
        byte[] tgz = TarFixtures.chartWithYaml("demo", "name: [unclosed\n");
        assertThatThrownBy(() -> read(tgz)).isInstanceOf(ChartArchive.InvalidChartException.class)
                .hasMessageContaining("Chart.yaml is not valid YAML");
    }

    @Test
    void refusesDecompressionBombs() throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream();
            TarFixtures.writeHeader(header, "bomb/zeros", 200L * 1024 * 1024, '0');
            gz.write(header.toByteArray());
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 200; i++) {
                gz.write(zeros);
            }
        }
        assertThatThrownBy(() -> read(bytes.toByteArray())).isInstanceOf(ChartArchive.InvalidChartException.class)
                .hasMessageContaining("expands to more than 100 MiB");
    }

    @Test
    void refusesOversizedChartYaml() {
        byte[] tgz = TarFixtures.chartWithYaml("demo", "description: " + "x".repeat(HelmYaml.SMALL_DOCUMENT_LIMIT + 10) + "\n");
        assertThatThrownBy(() -> read(tgz)).isInstanceOf(ChartArchive.InvalidChartException.class).hasMessageContaining("too large");
    }

    @Test
    void ioErrorsOfTheStreamSurfaceAsInvalidCharts() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };
        assertThatThrownBy(() -> ChartArchive.read(broken)).isInstanceOf(ChartArchive.InvalidChartException.class);
    }

    @Test
    void readsMultiMemberGzipAndSymlinks() throws IOException {
        byte[] tar = TarFixtures.tar(List.of(new Entry("demo/link", new byte[0], '2'),
                Entry.file("demo/Chart.yaml", "apiVersion: v2\nname: demo\nversion: 1.0.0\n")));
        byte[] first = TarFixtures.gzip(Arrays.copyOfRange(tar, 0, 512));
        byte[] second = TarFixtures.gzip(Arrays.copyOfRange(tar, 512, tar.length));
        byte[] concatenated = new byte[first.length + second.length];
        System.arraycopy(first, 0, concatenated, 0, first.length);
        System.arraycopy(second, 0, concatenated, first.length, second.length);
        assertThat(read(concatenated).chartYaml()).containsEntry("name", "demo");
    }
}
