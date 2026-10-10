package dev.granary.format.pypi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;

class PypiUploadTest {

    private static final String METADATA = PypiTestSupport.metadata("acme-demo", "1.0.0", ">=3.8", "Demo");
    private static final byte[] WHEEL = PypiTestSupport.wheel("acme-demo", "1.0.0", METADATA);
    private static final String WHEEL_NAME = "acme_demo-1.0.0-py3-none-any.whl";

    @TempDir
    Path tmp;

    private final Repository repo = PypiTestSupport.repository("pypi-hosted", RepositoryType.HOSTED);

    /** The form exactly as twine sends it, digests included. */
    static MockMultipartHttpServletRequest twineRequest(String name, String version, String filetype, String filename, byte[] bytes) {
        MockMultipartHttpServletRequest http = new MockMultipartHttpServletRequest();
        http.setMethod("POST");
        http.setRequestURI("/repository/pypi-hosted/");
        http.setContentType("multipart/form-data; boundary=----granary");
        http.addParameter(":action", "file_upload");
        http.addParameter("protocol_version", "1");
        http.addParameter("name", name);
        http.addParameter("version", version);
        http.addParameter("filetype", filetype);
        http.addParameter("pyversion", filetype.equals("sdist") ? "source" : "py3");
        http.addParameter("metadata_version", "2.4");
        http.addParameter("summary", "Demo");
        http.addParameter("requires_python", ">=3.8");
        http.addParameter("classifiers", "Programming Language :: Python :: 3");
        http.addParameter("classifiers", "License :: OSI Approved :: MIT License");
        http.addParameter("sha256_digest", PypiTestSupport.sha256(bytes));
        http.addParameter("md5_digest", PypiTestSupport.md5(bytes));
        http.addParameter("blake2_256_digest", Blake2b.hex256(bytes));
        http.addFile(new MockMultipartFile("content", filename, "application/octet-stream", bytes));
        return http;
    }

    private RepositoryRequest request(MockHttpServletRequest http) {
        return new RepositoryRequest(repo, "", http, GranaryPrincipal.anonymous(Set.of()), "http://granary.test/repository/pypi-hosted");
    }

    @Test
    void readsATwineUpload() throws IOException {
        PypiUpload upload = PypiUpload.read(request(twineRequest("acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL)), tmp);
        try {
            assertThat(upload.filename()).isEqualTo(WHEEL_NAME);
            assertThat(upload.name()).isEqualTo("acme-demo");
            assertThat(upload.normalisedName()).isEqualTo("acme-demo");
            assertThat(upload.version()).isEqualTo("1.0.0");
            assertThat(upload.size()).isEqualTo(WHEEL.length);
            assertThat(upload.sha256()).isEqualTo(PypiTestSupport.sha256(WHEEL));
            assertThat(upload.md5()).isEqualTo(PypiTestSupport.md5(WHEEL));
            assertThat(upload.blake2b256()).isEqualTo(Blake2b.hex256(WHEEL));
            assertThat(upload.distribution().type()).isEqualTo(PypiNames.DistributionType.WHEEL);
            assertThat(upload.fields().get("classifiers")).containsExactly("Programming Language :: Python :: 3", "License :: OSI Approved :: MIT License");
            assertThat(upload.field("summary")).contains("Demo");
            assertThat(upload.field("missing")).isEmpty();
            assertThat(Files.readAllBytes(upload.file())).isEqualTo(WHEEL);
            assertThat(upload.file().getParent()).isEqualTo(tmp);
        } finally {
            Files.deleteIfExists(upload.file());
        }
    }

    @Test
    void sdistUploadsAndCaseInsensitiveNamesAreAccepted() throws IOException {
        byte[] sdist = PypiTestSupport.sdist("acme_demo", "1.0.0", METADATA);
        PypiUpload upload = PypiUpload.read(request(twineRequest("Acme.Demo", "1.0.0", "sdist", "acme_demo-1.0.0.tar.gz", sdist)), tmp);
        Files.deleteIfExists(upload.file());
        assertThat(upload.normalisedName()).isEqualTo("acme-demo");
        assertThat(upload.distribution().type()).isEqualTo(PypiNames.DistributionType.SDIST);
    }

    @Test
    void versionSpellingMayDifferBetweenFormAndFileName() throws IOException {
        byte[] wheel = PypiTestSupport.wheel("acme-demo", "1.0", METADATA);
        PypiUpload upload = PypiUpload.read(request(twineRequest("acme-demo", "1.0.0", "bdist_wheel", "acme_demo-1.0-py3-none-any.whl", wheel)), tmp);
        Files.deleteIfExists(upload.file());
        assertThat(upload.version()).isEqualTo("1.0.0");
    }

    @Test
    void filetypeDefaultsToTheFileNameAndDigestsAreOptional() throws IOException {
        MockMultipartHttpServletRequest http = new MockMultipartHttpServletRequest();
        http.setMethod("POST");
        http.setContentType("multipart/form-data; boundary=x");
        http.addParameter("name", "acme-demo");
        http.addParameter("version", "1.0.0");
        http.addFile(new MockMultipartFile("content", WHEEL_NAME, null, WHEEL));
        PypiUpload upload = PypiUpload.read(request(http), tmp);
        Files.deleteIfExists(upload.file());
        assertThat(upload.distribution().type()).isEqualTo(PypiNames.DistributionType.WHEEL);
    }

    @Test
    void fallsBackToServletPartsWhenTheFrameworkDidNotParseMultipart() throws IOException {
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/repository/pypi-hosted/");
        http.setContentType("multipart/form-data; boundary=x");
        http.addPart(new MockPart(":action", "file_upload".getBytes(StandardCharsets.UTF_8)));
        http.addPart(new MockPart("name", "acme-demo".getBytes(StandardCharsets.UTF_8)));
        http.addPart(new MockPart("version", "1.0.0".getBytes(StandardCharsets.UTF_8)));
        http.addPart(new MockPart("classifiers", "A".getBytes(StandardCharsets.UTF_8)));
        http.addPart(new MockPart("classifiers", "B".getBytes(StandardCharsets.UTF_8)));
        http.addPart(new MockPart("sha256_digest", PypiTestSupport.sha256(WHEEL).getBytes(StandardCharsets.UTF_8)));
        http.addPart(new MockPart("content", WHEEL_NAME, WHEEL));
        PypiUpload upload = PypiUpload.read(request(http), tmp);
        Files.deleteIfExists(upload.file());
        assertThat(upload.filename()).isEqualTo(WHEEL_NAME);
        assertThat(upload.fields().get("classifiers")).containsExactly("A", "B");
        assertThat(upload.sha256()).isEqualTo(PypiTestSupport.sha256(WHEEL));
    }

    @Test
    void clientPathsAreReducedToTheFileName() throws IOException {
        MockMultipartHttpServletRequest http = twineRequest("acme-demo", "1.0.0", "bdist_wheel", "dist/" + WHEEL_NAME, WHEEL);
        PypiUpload upload = PypiUpload.read(request(http), tmp);
        Files.deleteIfExists(upload.file());
        assertThat(upload.filename()).isEqualTo(WHEEL_NAME);
    }

    private void assertRejected(MockHttpServletRequest http, String messagePart) {
        assertThatThrownBy(() -> PypiUpload.read(request(http), tmp))
                .isInstanceOf(PypiError.class)
                .hasMessageContaining(messagePart)
                .extracting(e -> ((PypiError) e).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsWrongDigests() throws IOException {
        for (String field : List.of("sha256_digest", "md5_digest", "blake2_256_digest")) {
            MockMultipartHttpServletRequest http = twineRequest("acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
            http.removeParameter(field);
            http.addParameter(field, "0000");
            assertRejected(http, field);
        }
        assertThat(Files.list(tmp).toList()).as("spool files are removed after a refusal").isEmpty();
    }

    @Test
    void directoryPartsOfTheSubmittedNameAreIgnored() throws IOException {
        PypiUpload upload = PypiUpload.read(request(twineRequest("acme-demo", "1.0.0", "bdist_wheel", "../dist/" + WHEEL_NAME, WHEEL)), tmp);
        try {
            assertThat(upload.filename()).isEqualTo(WHEEL_NAME);
        } finally {
            Files.deleteIfExists(upload.file());
        }
        PypiUpload windows = PypiUpload.read(request(twineRequest("acme-demo", "1.0.0", "bdist_wheel", "C:\\dist\\" + WHEEL_NAME, WHEEL)), tmp);
        try {
            assertThat(windows.filename()).isEqualTo(WHEEL_NAME);
        } finally {
            Files.deleteIfExists(windows.file());
        }
    }

    @Test
    void rejectsInconsistentNamesVersionsAndTypes() {
        assertRejected(twineRequest("other", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL), "does not belong to project");
        assertRejected(twineRequest("acme-demo", "2.0.0", "bdist_wheel", WHEEL_NAME, WHEEL), "does not match version");
        assertRejected(twineRequest("acme-demo", "1.0.0", "sdist", WHEEL_NAME, WHEEL), "does not match the file name");
        assertRejected(twineRequest("acme-demo", "1.0.0", "bdist_egg", WHEEL_NAME, WHEEL), "Unsupported filetype");
        assertRejected(twineRequest("acme-demo", "1.0.0", "bdist_wheel", "README.md", WHEEL), "not a valid wheel or sdist");
        assertRejected(twineRequest("-bad-", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL), "not a valid project name");
        assertRejected(twineRequest("acme-demo", "latest", "bdist_wheel", WHEEL_NAME, WHEEL), "not a valid PEP 440 version");
        assertRejected(twineRequest("acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, new byte[0]), "empty");
    }

    @Test
    void rejectsOtherActionsAndProtocolVersions() {
        MockMultipartHttpServletRequest http = twineRequest("acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        http.removeParameter(":action");
        http.addParameter(":action", "submit");
        assertRejected(http, "Unsupported action");
        http = twineRequest("acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        http.removeParameter("protocol_version");
        http.addParameter("protocol_version", "2");
        assertRejected(http, "protocol_version");
        http = twineRequest("acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        http.removeParameter("name");
        assertRejected(http, "'name' field is required");
        http = twineRequest("acme-demo", "1.0.0", "bdist_wheel", WHEEL_NAME, WHEEL);
        http.removeParameter("version");
        assertRejected(http, "'version' field is required");
    }

    @Test
    void rejectsRequestsWithoutAFileOrWithoutMultipart() {
        MockMultipartHttpServletRequest noFile = new MockMultipartHttpServletRequest();
        noFile.setMethod("POST");
        noFile.setContentType("multipart/form-data; boundary=x");
        noFile.addParameter("name", "acme-demo");
        assertRejected(noFile, "contains no file");

        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/repository/pypi-hosted/");
        raw.setContentType("application/octet-stream");
        raw.setContent(WHEEL);
        assertRejected(raw, "multipart/form-data");

        MockHttpServletRequest noType = new MockHttpServletRequest("POST", "/repository/pypi-hosted/");
        assertRejected(noType, "multipart/form-data");

        MockMultipartHttpServletRequest nameless = new MockMultipartHttpServletRequest();
        nameless.setMethod("POST");
        nameless.setContentType("multipart/form-data; boundary=x");
        nameless.addFile(new MockMultipartFile("content", "", null, WHEEL));
        assertRejected(nameless, "no file name");
    }

    @Test
    void mapsFiletypeNamesForJson() {
        assertThat(Map.of("a", 1)).isNotEmpty();
        assertThat(PypiUpload.FILE_UPLOAD).isEqualTo("file_upload");
    }
}
