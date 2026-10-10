package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.PID;
import static dev.granary.format.conan.ConanTestSupport.PREV;
import static dev.granary.format.conan.ConanTestSupport.RREV;
import static dev.granary.format.conan.ConanTestSupport.hosted;
import static dev.granary.format.conan.ConanTestSupport.http;
import static dev.granary.format.conan.ConanTestSupport.request;
import static dev.granary.format.conan.ConanTestSupport.sha1;
import static dev.granary.format.conan.ConanTestSupport.tgz;
import static dev.granary.format.conan.ConanTestSupport.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ConanHostedWriterTest {

    private static final ConanReference ZLIB = new ConanReference("zlib", "1.3.1", null, null);
    private static final StorageService.ComponentCoordinates COORDINATES =
            new StorageService.ComponentCoordinates("", "zlib", "1.3.1");

    @TempDir
    Path spool;

    private final FakeStorage storage = new FakeStorage();
    private final Repository repo = hosted("conan-hosted");

    private ConanHostedWriter writer() {
        return new ConanHostedWriter(storage.mock, spool.resolve("tmp").resolve("conan"));
    }

    private RepositoryRequest put(String path, byte[] body, String sha1Header) {
        RepositoryRequest request = request(repo, "PUT", "v2/x", body);
        if (sha1Header != null) {
            http(request).addHeader(ConanHostedWriter.CHECKSUM_SHA1, sha1Header);
        }
        return request;
    }

    @Test
    void storesAVerifiedUploadWithItsComponent() throws Exception {
        byte[] body = tgz("export");
        String path = ConanLayout.exportPath(ZLIB, RREV, "conan_export.tgz");
        ResponseEntity<?> response = writer().store(put(path, body, sha1(body)), path, "application/gzip", COORDINATES);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.bytes(repo, path)).isEqualTo(body);
        Asset asset = storage.find(repo, path).orElseThrow().asset();
        assertThat(asset.getOrigin()).isEqualTo(Asset.Origin.UPLOADED);
        assertThat(asset.isMetadata()).isFalse();
        assertThat(asset.getContentType()).isEqualTo("application/gzip");
        assertThat(storage.coordinates(repo, path)).isEqualTo(COORDINATES);
        // the spool file is gone
        try (var files = Files.list(spool.resolve("tmp").resolve("conan"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void aParallelUploadThatCreatedTheComponentFirstIsNoError() throws Exception {
        doThrow(new DataIntegrityViolationException("uk_component_coordinates"))
                .doReturn(mock(Component.class))
                .when(storage.mock).ensureComponent(any(), any());
        byte[] body = utf8("from conan import ConanFile");
        String path = ConanLayout.exportPath(ZLIB, RREV, "conanfile.py");
        assertThat(writer().store(put(path, body, sha1(body)), path, "text/x-python", COORDINATES).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(storage.bytes(repo, path)).isEqualTo(body);
        verify(storage.mock, times(2)).ensureComponent(repo, COORDINATES);
    }

    @Test
    void uploadWithoutChecksumIsAccepted() throws Exception {
        String path = ConanLayout.exportPath(ZLIB, RREV, "conanfile.py");
        assertThat(writer().store(put(path, utf8("from conan import ConanFile"), null), path, "text/x-python", COORDINATES)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(writer().store(put(path, utf8("from conan import ConanFile"), "  "), path, "text/x-python", COORDINATES)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void checksumMismatchIsRefusedAndNothingIsStored() {
        String path = ConanLayout.exportPath(ZLIB, RREV, "conanfile.py");
        assertThatThrownBy(() -> writer().store(put(path, utf8("abc"), sha1(utf8("abd"))), path, "text/x-python", COORDINATES))
                .isInstanceOfSatisfying(ConanError.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("repository.conan.checksumMismatch");
                });
        assertThat(storage.paths(repo)).isEmpty();
    }

    @Test
    void checksumHeaderIsCaseInsensitive() throws Exception {
        String path = ConanLayout.exportPath(ZLIB, RREV, "conanfile.py");
        byte[] body = utf8("x");
        assertThat(writer().store(put(path, body, sha1(body).toUpperCase()), path, null, COORDINATES).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void identicalReUploadIsAcceptedAndDifferentContentIsAConflict() throws Exception {
        String path = ConanLayout.packagePath(ZLIB, RREV, PID, PREV, "conan_package.tgz");
        byte[] body = tgz("pkg");
        writer().store(put(path, body, sha1(body)), path, "application/gzip", COORDINATES);
        assertThat(writer().store(put(path, body, sha1(body)), path, "application/gzip", COORDINATES).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        byte[] other = tgz("other");
        assertThatThrownBy(() -> writer().store(put(path, other, sha1(other)), path, "application/gzip", COORDINATES))
                .isInstanceOfSatisfying(ConanError.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("repository.conan.revisionExists");
                    assertThat(e.getMessage()).contains("conan_package.tgz");
                });
        assertThat(storage.bytes(repo, path)).isEqualTo(body);
    }

    @Test
    void redeployReplacesWhenAllowed() throws Exception {
        repo.setAllowRedeploy(true);
        String path = ConanLayout.exportPath(ZLIB, RREV, "conanfile.py");
        writer().store(put(path, utf8("v1"), null), path, null, COORDINATES);
        assertThat(writer().store(put(path, utf8("v2"), null), path, null, COORDINATES).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(storage.text(repo, path)).isEqualTo("v2");
    }

    @Test
    void metadataFilesMayChangeWithoutRedeploy() throws Exception {
        String path = ConanLayout.exportPath(ZLIB, RREV, "metadata/logs/build.log");
        writer().store(put(path, utf8("first build"), null), path, "text/plain", COORDINATES);
        assertThat(writer().store(put(path, utf8("second build"), null), path, "text/plain", COORDINATES).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(storage.text(repo, path)).isEqualTo("second build");
    }

    @Test
    void checksumDeployAnswers201OnlyForTheVerySameFile() throws Exception {
        String path = ConanLayout.exportPath(ZLIB, RREV, "conan_export.tgz");
        byte[] body = tgz("export");

        RepositoryRequest dedupMiss = put(path, new byte[0], sha1(body));
        http(dedupMiss).addHeader(ConanHostedWriter.CHECKSUM_DEPLOY, "true");
        assertThatThrownBy(() -> writer().store(dedupMiss, path, "application/gzip", COORDINATES))
                .isInstanceOfSatisfying(ConanError.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(storage.paths(repo)).isEmpty();

        writer().store(put(path, body, sha1(body)), path, "application/gzip", COORDINATES);

        RepositoryRequest dedupHit = put(path, new byte[0], sha1(body));
        http(dedupHit).addHeader(ConanHostedWriter.CHECKSUM_DEPLOY, "TRUE");
        assertThat(writer().store(dedupHit, path, "application/gzip", COORDINATES).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storage.bytes(repo, path)).isEqualTo(body);

        RepositoryRequest dedupOtherContent = put(path, new byte[0], sha1(utf8("different")));
        http(dedupOtherContent).addHeader(ConanHostedWriter.CHECKSUM_DEPLOY, "true");
        assertThatThrownBy(() -> writer().store(dedupOtherContent, path, "application/gzip", COORDINATES))
                .isInstanceOf(ConanError.class);

        RepositoryRequest dedupWithoutChecksum = put(path, new byte[0], null);
        http(dedupWithoutChecksum).addHeader(ConanHostedWriter.CHECKSUM_DEPLOY, "true");
        assertThatThrownBy(() -> writer().store(dedupWithoutChecksum, path, "application/gzip", COORDINATES))
                .isInstanceOf(ConanError.class);
        assertThat(storage.bytes(repo, path)).isEqualTo(body);
    }

    @Test
    void checksumDeployFalseIsANormalUpload() throws Exception {
        String path = ConanLayout.exportPath(ZLIB, RREV, "conanfile.py");
        RepositoryRequest request = put(path, utf8("x"), null);
        http(request).addHeader(ConanHostedWriter.CHECKSUM_DEPLOY, "false");
        assertThat(writer().store(request, path, null, COORDINATES).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void emptyFilesAreStored() throws Exception {
        String path = ConanLayout.exportPath(ZLIB, RREV, "metadata/empty");
        assertThat(writer().store(put(path, new byte[0], sha1(new byte[0])), path, null, COORDINATES).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(storage.bytes(repo, path)).isEmpty();
    }

    @Test
    void deletePrefixRemovesOnlyWhatIsBelowIt() throws Exception {
        storage.put(repo, ConanLayout.exportPath(ZLIB, RREV, "conanfile.py"), "a");
        storage.put(repo, ConanLayout.exportPath(ZLIB, RREV, "conanmanifest.txt"), "b");
        storage.put(repo, ConanLayout.packagePath(ZLIB, RREV, PID, PREV, "conaninfo.txt"), "c");
        ConanReference zlib2 = new ConanReference("zlib", "1.3.10", null, null);
        storage.put(repo, ConanLayout.exportPath(zlib2, RREV, "conanfile.py"), "d");

        assertThat(writer().deletePrefix(repo, ConanLayout.packagesPrefix(ZLIB, RREV))).isEqualTo(1);
        assertThat(writer().deletePrefix(repo, ConanLayout.packagesPrefix(ZLIB, RREV))).isZero();
        assertThat(writer().deletePrefix(repo, ConanLayout.recipePrefix(ZLIB))).isEqualTo(2);
        assertThat(storage.paths(repo)).containsExactly(ConanLayout.exportPath(zlib2, RREV, "conanfile.py"));
    }
}
