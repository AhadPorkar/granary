package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PypiMetadataTest {

    @TempDir
    Path tmp;

    private static final String METADATA = PypiTestSupport.metadata("acme-demo", "1.0.0", ">=3.8", "Demo package");

    @Test
    void parsesHeadersAndBody() {
        PypiMetadata m = PypiMetadata.parse(METADATA);
        assertThat(m.name()).contains("acme-demo");
        assertThat(m.version()).contains("1.0.0");
        assertThat(m.requiresPython()).contains(">=3.8");
        assertThat(m.first("summary")).contains("Demo package");
        assertThat(m.first("Author-email")).contains("Acme <dev@acme.example>");
        assertThat(m.all("Classifier")).containsExactly("Programming Language :: Python :: 3");
        assertThat(m.all("Requires-Dist")).containsExactly("six>=1.16");
        assertThat(m.first("Nope")).isEmpty();
        assertThat(m.description()).isEqualTo("# acme-demo\n\nA demo package.\n");
    }

    @Test
    void handlesContinuationLinesUnknownValuesAndCrlf() {
        String text = "Metadata-Version: 2.1\r\nName: x\r\nSummary: first line\r\n  second line\r\nLicense: UNKNOWN\r\nKeywords: \r\nBroken line without colon\r\nClassifier: A\r\nClassifier: B\r\n\r\nBody\r\nmore\r\n";
        PypiMetadata m = PypiMetadata.parse(text.getBytes(StandardCharsets.UTF_8));
        assertThat(m.first("Summary")).contains("first line\nsecond line");
        assertThat(m.first("License")).isEmpty();
        assertThat(m.first("Keywords")).isEmpty();
        assertThat(m.all("Classifier")).containsExactly("A", "B");
        assertThat(m.description()).isEqualTo("Body\nmore\n");
        assertThat(m.headers()).extracting(Map.Entry::getKey).contains("Metadata-Version", "Name", "Summary", "Classifier");
    }

    @Test
    void legacyDescriptionHeaderBecomesTheBody() {
        PypiMetadata m = PypiMetadata.parse("Metadata-Version: 1.2\nName: old\nDescription: line one\n        line two\n");
        assertThat(m.description()).isEqualTo("line one\nline two");
        assertThat(PypiMetadata.parse("").description()).isEmpty();
        assertThat(PypiMetadata.parse("").name()).isEmpty();
    }

    @Test
    void roundTripsThroughBytes() {
        PypiMetadata m = PypiMetadata.parse(METADATA);
        PypiMetadata again = PypiMetadata.parse(m.toBytes());
        assertThat(again.headers()).isEqualTo(m.headers());
        assertThat(again.description()).isEqualTo(m.description());
        assertThat(new String(m.toBytes(), StandardCharsets.UTF_8)).isEqualTo(METADATA);
    }

    @Test
    void buildsMetadataFromUploadFields() {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put("name", List.of("acme-demo"));
        fields.put("version", List.of("1.0.0"));
        fields.put("metadata_version", List.of("2.1"));
        fields.put("summary", List.of("Demo"));
        fields.put("requires_python", List.of(">=3.8"));
        fields.put("classifiers", List.of("A :: B", "C :: D"));
        fields.put("requires_dist", List.of("six>=1.16", "attrs; extra == \"dev\""));
        fields.put("project_urls", List.of("Homepage, https://example.com"));
        fields.put("license", List.of("UNKNOWN"));
        fields.put("author", List.of(""));
        fields.put("description", List.of("Long\r\ndescription"));
        fields.put("description_content_type", List.of("text/markdown"));
        fields.put("filetype", List.of("sdist"));
        fields.put(":action", List.of("file_upload"));
        PypiMetadata m = PypiMetadata.fromUploadFields(fields);
        String text = new String(m.toBytes(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("Metadata-Version: 2.1\nName: acme-demo\nVersion: 1.0.0\n");
        assertThat(text).contains("Summary: Demo\n").contains("Requires-Python: >=3.8\n");
        assertThat(text).contains("Classifier: A :: B\nClassifier: C :: D\n");
        assertThat(text).contains("Requires-Dist: six>=1.16\nRequires-Dist: attrs; extra == \"dev\"\n");
        assertThat(text).contains("Project-URL: Homepage, https://example.com\n");
        assertThat(text).contains("Description-Content-Type: text/markdown\n");
        assertThat(text).doesNotContain("License").doesNotContain("Author:").doesNotContain("filetype").doesNotContain(":action");
        assertThat(text).endsWith("\nLong\ndescription\n");
        assertThat(m.requiresPython()).contains(">=3.8");

        PypiMetadata bare = PypiMetadata.fromUploadFields(Map.of("name", List.of("x"), "version", List.of("1")));
        assertThat(new String(bare.toBytes(), StandardCharsets.UTF_8)).isEqualTo("Metadata-Version: 2.1\nName: x\nVersion: 1\n");
    }

    @Test
    void extractsMetadataFromAWheel() throws IOException {
        byte[] wheel = PypiTestSupport.wheel("acme-demo", "1.0.0", METADATA);
        Path file = tmp.resolve("acme_demo-1.0.0-py3-none-any.whl");
        Files.write(file, wheel);
        assertThat(PypiMetadata.fromWheel(file, "acme-demo", "1.0.0")).hasValueSatisfying(bytes ->
                assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(METADATA));
        assertThat(PypiMetadata.fromWheelBytes(wheel)).hasValueSatisfying(bytes ->
                assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(METADATA));
        assertThat(PypiMetadata.distInfoPrefix("Acme.Demo", "1.0.0")).isEqualTo("acme_demo-1.0.0.dist-info/");
    }

    @Test
    void prefersTheMatchingDistInfoDirectoryAndFallsBackToAnyOther() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("other-9.9.dist-info/METADATA", "Name: other\n".getBytes(StandardCharsets.UTF_8));
        entries.put("acme_demo-1.0.0.dist-info/METADATA", "Name: acme-demo\n".getBytes(StandardCharsets.UTF_8));
        entries.put("nested/x-1.dist-info/METADATA", "Name: nested\n".getBytes(StandardCharsets.UTF_8));
        Path file = tmp.resolve("w.whl");
        Files.write(file, PypiTestSupport.zip(entries));
        assertThat(PypiMetadata.fromWheel(file, "acme-demo", "1.0.0")).hasValueSatisfying(b ->
                assertThat(new String(b, StandardCharsets.UTF_8)).isEqualTo("Name: acme-demo\n"));
        assertThat(PypiMetadata.fromWheel(file, "nothing", "1")).hasValueSatisfying(b ->
                assertThat(new String(b, StandardCharsets.UTF_8)).isEqualTo("Name: other\n"));
        Path noMetadata = tmp.resolve("empty.whl");
        Files.write(noMetadata, PypiTestSupport.zip(Map.of("pkg/__init__.py", new byte[0])));
        assertThat(PypiMetadata.fromWheel(noMetadata, "x", "1")).isEmpty();
    }

    @Test
    void corruptWheelsAreAnIoError() throws IOException {
        Path file = tmp.resolve("bad.whl");
        Files.write(file, "this is not a zip".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> PypiMetadata.fromWheel(file, "x", "1")).isInstanceOf(IOException.class);
    }

    @Test
    void extractsPkgInfoFromTarGzAndZipSdists() throws IOException {
        byte[] sdist = PypiTestSupport.sdist("acme_demo", "1.0.0", METADATA);
        Path file = tmp.resolve("acme_demo-1.0.0.tar.gz");
        Files.write(file, sdist);
        assertThat(PypiMetadata.fromSdist(file, "acme_demo-1.0.0.tar.gz")).hasValueSatisfying(bytes ->
                assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(METADATA));

        Path zip = tmp.resolve("acme_demo-1.0.0.zip");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("acme_demo-1.0.0/setup.py", new byte[0]);
        entries.put("acme_demo-1.0.0/PKG-INFO", METADATA.getBytes(StandardCharsets.UTF_8));
        Files.write(zip, PypiTestSupport.zip(entries));
        assertThat(PypiMetadata.fromSdist(zip, "acme_demo-1.0.0.zip")).hasValueSatisfying(bytes ->
                assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(METADATA));

        Path noPkgInfo = tmp.resolve("x-1.tar.gz");
        Files.write(noPkgInfo, PypiTestSupport.targz(Map.of("x-1/setup.py", new byte[0], "x-1/deep/PKG-INFO", new byte[1])));
        assertThat(PypiMetadata.fromSdist(noPkgInfo, "x-1.tar.gz")).isEmpty();
        assertThat(PypiMetadata.fromSdist(noPkgInfo, "x-1.tar.bz2")).isEmpty();

        Path plainTar = tmp.resolve("x-1.tar");
        Files.write(plainTar, PypiTestSupport.tar(Map.of("x-1/PKG-INFO", "Name: x\n".getBytes(StandardCharsets.UTF_8))));
        assertThat(PypiMetadata.fromSdist(plainTar, "x-1.tar")).hasValueSatisfying(b -> assertThat(b).isEqualTo("Name: x\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void tarReaderSurvivesGarbageAndLongNames() throws IOException {
        Path garbage = tmp.resolve("g.tar.gz");
        Files.write(garbage, "not gzip".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> PypiMetadata.fromSdist(garbage, "g.tar.gz")).isInstanceOf(IOException.class);

        // a truncated archive simply has no PKG-INFO
        byte[] truncated = java.util.Arrays.copyOf(PypiTestSupport.tar(Map.of("a-1/PKG-INFO", new byte[600])), 700);
        assertThat(PypiMetadata.pkgInfoFromTar(new java.io.ByteArrayInputStream(truncated))).isEmpty();

        // GNU long name entries: the following header's name is replaced by the long one
        String longDir = "a".repeat(120) + "-1.0/";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("././@LongLink", (longDir + "PKG-INFO\0").getBytes(StandardCharsets.UTF_8));
        entries.put("placeholder", "Name: long\n".getBytes(StandardCharsets.UTF_8));
        byte[] tar = PypiTestSupport.tar(entries);
        tar[156] = 'L'; // mark the first entry as a GNU long-name record
        assertThat(PypiMetadata.pkgInfoFromTar(new java.io.ByteArrayInputStream(tar))).hasValueSatisfying(b ->
                assertThat(new String(b, StandardCharsets.UTF_8)).isEqualTo("Name: long\n"));
    }
}
