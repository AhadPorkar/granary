package dev.granary.format.swift;

import static dev.granary.format.swift.SwiftTestSupport.http;
import static dev.granary.format.swift.SwiftTestSupport.part;
import static dev.granary.format.swift.SwiftTestSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;

class SwiftUploadTest {

    @TempDir
    Path tmp;

    private final Repository repo = SwiftTestSupport.hosted("swift-hosted");
    private final byte[] archive = SwiftTestSupport.sourceArchive("upload");

    private static void assertProblem(ThrowingRead read, HttpStatus status, String detail) {
        assertThatThrownBy(read::run).isInstanceOfSatisfying(SwiftProblem.class, p -> {
            assertThat(p.status()).isEqualTo(status);
            assertThat(p.detail()).contains(detail);
        });
    }

    @FunctionalInterface
    private interface ThrowingRead {
        void run() throws Exception;
    }

    private long spooledFiles() throws IOException {
        try (var files = Files.list(tmp)) {
            return files.count();
        }
    }

    @Test
    void readsTheSwiftPmMultipartRequest() throws IOException {
        RepositoryRequest request = SwiftTestSupport.publish(repo, "acme/demo/1.0.0", archive, SwiftTestSupport.METADATA);
        http(request).addPart(part(SwiftUpload.SOURCE_ARCHIVE_SIGNATURE, new byte[] {1, 2, 3}, "application/octet-stream", "binary"));
        http(request).addPart(part(SwiftUpload.METADATA_SIGNATURE, new byte[] {4, 5}, "application/octet-stream", "binary"));
        http(request).addPart(part("something-new", new byte[] {9}, "application/octet-stream", null));
        Path spooled;
        try (SwiftUpload upload = SwiftUpload.read(request, tmp, 0)) {
            spooled = upload.archive();
            assertThat(Files.readAllBytes(upload.archive())).isEqualTo(archive);
            assertThat(upload.size()).isEqualTo(archive.length);
            assertThat(upload.sha256()).isEqualTo(sha256(archive));
            assertThat(new String(upload.metadata(), StandardCharsets.UTF_8)).isEqualTo(SwiftTestSupport.METADATA);
            assertThat(upload.signature()).containsExactly(1, 2, 3);
            assertThat(upload.metadataSignature()).containsExactly(4, 5);
        }
        assertThat(spooled).doesNotExist();
    }

    @Test
    void decodesBase64Parts() throws IOException {
        RepositoryRequest request = SwiftTestSupport.publish(repo, "acme/demo/1.0.0", null, null);
        http(request).addPart(part(SwiftUpload.SOURCE_ARCHIVE, Base64.getMimeEncoder().encode(archive), "application/zip", "base64"));
        http(request).addPart(part(SwiftUpload.SOURCE_ARCHIVE_SIGNATURE, Base64.getEncoder().encode(new byte[] {7, 8}),
                "application/octet-stream", "base64"));
        try (SwiftUpload upload = SwiftUpload.read(request, tmp, 0)) {
            assertThat(Files.readAllBytes(upload.archive())).isEqualTo(archive);
            assertThat(upload.signature()).containsExactly(7, 8);
            assertThat(upload.metadata()).isNull();
            assertThat(upload.metadataSignature()).isNull();
        }
    }

    @Test
    void emptySignaturesCountAsMissing() throws IOException {
        RepositoryRequest request = SwiftTestSupport.publish(repo, "acme/demo/1.0.0", archive, null);
        http(request).addPart(part(SwiftUpload.SOURCE_ARCHIVE_SIGNATURE, new byte[0], "application/octet-stream", "binary"));
        try (SwiftUpload upload = SwiftUpload.read(request, tmp, 0)) {
            assertThat(upload.signature()).isNull();
        }
    }

    @Test
    void acceptsABareZipBody() throws IOException {
        MockHttpServletRequest http = new MockHttpServletRequest("PUT", "/repository/swift-hosted/acme/demo/1.0.0");
        http.setContentType("application/zip");
        http.setContent(archive);
        RepositoryRequest request = new RepositoryRequest(repo, "acme/demo/1.0.0", http, SwiftTestSupport.user("u"), SwiftTestSupport.baseUrl(repo));
        try (SwiftUpload upload = SwiftUpload.read(request, tmp, 0)) {
            assertThat(upload.sha256()).isEqualTo(sha256(archive));
            assertThat(upload.metadata()).isNull();
        }
    }

    @Test
    void fallsBackToSpringMultipartFiles() throws IOException {
        MockMultipartHttpServletRequest http = new MockMultipartHttpServletRequest();
        http.setMethod("PUT");
        http.setContentType("multipart/form-data; boundary=x");
        http.addFile(new MockMultipartFile(SwiftUpload.SOURCE_ARCHIVE, "demo.zip", "application/zip", archive));
        http.addParameter(SwiftUpload.METADATA, "{\"description\":\"x\"}");
        RepositoryRequest request = new RepositoryRequest(repo, "acme/demo/1.0.0", http, SwiftTestSupport.user("u"), SwiftTestSupport.baseUrl(repo));
        try (SwiftUpload upload = SwiftUpload.read(request, tmp, 0)) {
            assertThat(upload.sha256()).isEqualTo(sha256(archive));
            assertThat(new String(upload.metadata(), StandardCharsets.UTF_8)).isEqualTo("{\"description\":\"x\"}");
            assertThat(upload.signature()).isNull();
        }
    }

    @Test
    void refusesOtherContentTypes() throws IOException {
        MockHttpServletRequest http = new MockHttpServletRequest("PUT", "/x");
        http.setContentType("application/json");
        http.setContent("{}".getBytes(StandardCharsets.UTF_8));
        RepositoryRequest request = new RepositoryRequest(repo, "acme/demo/1.0.0", http, SwiftTestSupport.user("u"), SwiftTestSupport.baseUrl(repo));
        assertProblem(() -> SwiftUpload.read(request, tmp, 0), HttpStatus.UNSUPPORTED_MEDIA_TYPE, "multipart/form-data");
        MockHttpServletRequest none = new MockHttpServletRequest("PUT", "/x");
        assertProblem(() -> SwiftUpload.read(new RepositoryRequest(repo, "p", none, SwiftTestSupport.user("u"), "b"), tmp, 0),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "multipart/form-data");
        assertThat(spooledFiles()).isZero();
    }

    @Test
    void requiresTheArchivePart() throws IOException {
        RepositoryRequest request = SwiftTestSupport.publish(repo, "acme/demo/1.0.0", null, SwiftTestSupport.METADATA);
        assertProblem(() -> SwiftUpload.read(request, tmp, 0), HttpStatus.BAD_REQUEST, "no 'source-archive' part");
        assertThat(spooledFiles()).isZero();
    }

    @Test
    void refusesAnEmptyArchive() throws IOException {
        RepositoryRequest request = SwiftTestSupport.publish(repo, "acme/demo/1.0.0", new byte[0], null);
        assertProblem(() -> SwiftUpload.read(request, tmp, 0), HttpStatus.UNPROCESSABLE_CONTENT, "empty");
        assertThat(spooledFiles()).isZero();
    }

    @Test
    void enforcesSizeLimits() throws IOException {
        RepositoryRequest request = SwiftTestSupport.publish(repo, "acme/demo/1.0.0", archive, null);
        assertProblem(() -> SwiftUpload.read(request, tmp, archive.length - 1), HttpStatus.CONTENT_TOO_LARGE,
                "'source-archive' part may not exceed " + (archive.length - 1) + " bytes");
        assertThat(spooledFiles()).isZero();
        try (SwiftUpload upload = SwiftUpload.read(SwiftTestSupport.publish(repo, "acme/demo/1.0.0", archive, null), tmp, archive.length)) {
            assertThat(upload.size()).isEqualTo(archive.length);
        }

        RepositoryRequest bigSignature = SwiftTestSupport.publish(repo, "acme/demo/1.0.0", archive, null);
        http(bigSignature).addPart(part(SwiftUpload.SOURCE_ARCHIVE_SIGNATURE, new byte[SwiftUpload.MAX_SIGNATURE_BYTES + 1],
                "application/octet-stream", "binary"));
        assertProblem(() -> SwiftUpload.read(bigSignature, tmp, 0), HttpStatus.CONTENT_TOO_LARGE, "source-archive-signature");

        RepositoryRequest bigMetadata = SwiftTestSupport.publish(repo, "acme/demo/1.0.0", archive, "x".repeat(SwiftMetadata.MAX_BYTES + 1));
        assertProblem(() -> SwiftUpload.read(bigMetadata, tmp, 0), HttpStatus.CONTENT_TOO_LARGE, "'metadata' part");
        assertThat(spooledFiles()).isZero();
    }

    @Test
    void refusesBrokenBase64() throws IOException {
        RepositoryRequest request = SwiftTestSupport.publish(repo, "acme/demo/1.0.0", archive, null);
        http(request).addPart(part(SwiftUpload.SOURCE_ARCHIVE_SIGNATURE, "@@@not base64@@@".getBytes(StandardCharsets.US_ASCII),
                "application/octet-stream", "base64"));
        assertProblem(() -> SwiftUpload.read(request, tmp, 0), HttpStatus.BAD_REQUEST, "not valid base64");
        assertThat(spooledFiles()).isZero();
    }
}
