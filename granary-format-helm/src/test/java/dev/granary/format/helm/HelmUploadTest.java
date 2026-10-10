package dev.granary.format.helm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockPart;

class HelmUploadTest {

    @TempDir
    Path temp;

    private final Repository repo = HelmTestSupport.hosted("helm-hosted");

    private long leftovers() throws IOException {
        try (Stream<Path> files = Files.list(temp)) {
            return files.count();
        }
    }

    @Test
    void rawBodyIsTheChartOrTheProvenanceFile() throws IOException {
        RepositoryRequest request = HelmTestSupport.request(repo, "POST", "api/charts", new byte[] {1, 2, 3}, "application/gzip");
        try (HelmUpload upload = HelmUpload.read(request, temp, HelmUpload.Body.CHART, 0)) {
            assertThat(Files.readAllBytes(upload.chart())).containsExactly(1, 2, 3);
            assertThat(upload.prov()).isNull();
        }
        RepositoryRequest prov = HelmTestSupport.request(repo, "POST", "api/prov", new byte[] {4}, null);
        try (HelmUpload upload = HelmUpload.read(prov, temp, HelmUpload.Body.PROV, 0)) {
            assertThat(upload.chart()).isNull();
            assertThat(Files.readAllBytes(upload.prov())).containsExactly(4);
        }
        assertThat(leftovers()).isZero();
    }

    @Test
    void multipartFieldsChartAndProv() throws IOException {
        RepositoryRequest request = HelmTestSupport.multipart(repo, "api/charts", Map.of("chart", new byte[] {1}, "prov", new byte[] {2}));
        try (HelmUpload upload = HelmUpload.read(request, temp, HelmUpload.Body.CHART, 0)) {
            assertThat(Files.readAllBytes(upload.chart())).containsExactly(1);
            assertThat(Files.readAllBytes(upload.prov())).containsExactly(2);
        }
        assertThat(leftovers()).isZero();
    }

    @Test
    void servletPartsWhenNoMultipartResolverRan() throws IOException {
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/repository/helm-hosted/api/charts");
        http.setContentType("multipart/form-data; boundary=x");
        http.addPart(new MockPart("chart", "demo-1.0.0.tgz", new byte[] {7}));
        http.addPart(new MockPart("prov", "demo-1.0.0.tgz.prov", new byte[] {8}));
        http.addPart(new MockPart("note", "ignored".getBytes()));
        RepositoryRequest request = new RepositoryRequest(repo, "api/charts", http, HelmTestSupport.user(), "http://x/repository/helm-hosted");
        try (HelmUpload upload = HelmUpload.read(request, temp, HelmUpload.Body.CHART, 0)) {
            assertThat(Files.readAllBytes(upload.chart())).containsExactly(7);
            assertThat(Files.readAllBytes(upload.prov())).containsExactly(8);
        }

        MockHttpServletRequest single = new MockHttpServletRequest("POST", "/repository/helm-hosted/api/charts");
        single.setContentType("multipart/form-data; boundary=x");
        single.addPart(new MockPart("file", "demo-1.0.0.tgz.prov", new byte[] {9}));
        RepositoryRequest provOnly = new RepositoryRequest(repo, "api/charts", single, HelmTestSupport.user(), "http://x/repository/helm-hosted");
        try (HelmUpload upload = HelmUpload.read(provOnly, temp, HelmUpload.Body.CHART, 0)) {
            assertThat(upload.chart()).isNull();
            assertThat(Files.readAllBytes(upload.prov())).containsExactly(9);
        }

        MockHttpServletRequest none = new MockHttpServletRequest("POST", "/repository/helm-hosted/api/charts");
        none.setContentType("multipart/form-data; boundary=x");
        none.addPart(new MockPart("note", "text".getBytes()));
        RepositoryRequest nothing = new RepositoryRequest(repo, "api/charts", none, HelmTestSupport.user(), "http://x/repository/helm-hosted");
        assertThatThrownBy(() -> HelmUpload.read(nothing, temp, HelmUpload.Body.CHART, 0)).isInstanceOf(HelmError.class)
                .hasMessageContaining("neither a 'chart' nor a 'prov' field");
        assertThat(leftovers()).isZero();
    }

    @Test
    void singleFileUnderAnotherFieldIsJudgedByItsName() throws IOException {
        RepositoryRequest request = HelmTestSupport.multipart(repo, "api/charts", Map.of("file", new byte[] {5}));
        try (HelmUpload upload = HelmUpload.read(request, temp, HelmUpload.Body.CHART, 0)) {
            assertThat(upload.chart()).isNotNull(); // "file.bin" does not end in .prov
        }
    }

    @Test
    void limitsAndEmptyBodies() throws IOException {
        RepositoryRequest big = HelmTestSupport.request(repo, "POST", "api/charts", new byte[2 * 1024 * 1024 + 1], null);
        assertThatThrownBy(() -> HelmUpload.read(big, temp, HelmUpload.Body.CHART, 2 * 1024 * 1024))
                .isInstanceOfSatisfying(HelmError.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE))
                .hasMessageContaining("2 MiB");
        RepositoryRequest empty = HelmTestSupport.request(repo, "POST", "api/charts", new byte[0], null);
        assertThatThrownBy(() -> HelmUpload.read(empty, temp, HelmUpload.Body.CHART, 0)).hasMessageContaining("no chart");
        RepositoryRequest emptyProv = HelmTestSupport.request(repo, "POST", "api/prov", new byte[0], null);
        assertThatThrownBy(() -> HelmUpload.read(emptyProv, temp, HelmUpload.Body.PROV, 0)).hasMessageContaining("no provenance");
        RepositoryRequest emptyField = HelmTestSupport.multipart(repo, "api/charts", Map.of("prov", new byte[0]));
        assertThatThrownBy(() -> HelmUpload.read(emptyField, temp, HelmUpload.Body.CHART, 0)).hasMessageContaining("prov field is empty");
        RepositoryRequest bigPart = HelmTestSupport.multipart(repo, "api/charts", Map.of("chart", new byte[2048]));
        assertThatThrownBy(() -> HelmUpload.read(bigPart, temp, HelmUpload.Body.CHART, 1024)).isInstanceOf(HelmError.class);
        assertThat(leftovers()).as("failed uploads leave nothing behind").isZero();
    }
}
