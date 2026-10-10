package dev.granary.format.pub;

import static dev.granary.format.pub.PubTestSupport.archive;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.granary.core.config.GranaryProperties;
import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PubUploadsTest {

    @TempDir
    Path dataDir;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final PubTestSupport.FakeStorage storage = new PubTestSupport.FakeStorage();
    private PubUploads uploads;
    private Repository repo;
    private final GranaryPrincipal alice = PubTestSupport.user("alice", "repository:write:pub-hosted");

    @BeforeEach
    void setUp() {
        GranaryProperties properties = new GranaryProperties();
        properties.setDataDir(dataDir);
        uploads = new PubUploads(new PubHosted(storage.mock, json), json, properties);
        repo = PubTestSupport.repository("pub-hosted", RepositoryType.HOSTED);
    }

    private RepositoryRequest multipart(byte[] bytes, String field, GranaryPrincipal principal) {
        MockMultipartHttpServletRequest http = new MockMultipartHttpServletRequest();
        http.setMethod("POST");
        http.setRequestURI("/repository/pub-hosted/api/packages/versions/newUpload");
        if (bytes != null) {
            http.addFile(new MockMultipartFile(field, "package.tar.gz", "application/octet-stream", bytes));
        }
        return PubTestSupport.request(repo, http, "api/packages/versions/newUpload", principal);
    }

    private RepositoryRequest finishRequest(String location, GranaryPrincipal principal) {
        String id = location.substring(location.indexOf("?id=") + 4);
        MockHttpServletRequest http = new MockHttpServletRequest("GET", "/repository/pub-hosted/api/packages/versions/newUploadFinish");
        http.setParameter("id", id);
        return PubTestSupport.request(repo, http, "api/packages/versions/newUploadFinish", principal);
    }

    private JsonNode body(ResponseEntity<?> response) {
        return json.readTree((byte[]) response.getBody());
    }

    @Test
    void newUploadNamesTheUploadUrlWithoutFields() {
        JsonNode body = uploads.newUpload("http://granary.test/repository/pub-hosted");
        assertThat(body.get("url").stringValue()).isEqualTo("http://granary.test/repository/pub-hosted/api/packages/versions/newUpload");
        assertThat(body.get("fields").isObject()).isTrue();
        assertThat(body.get("fields").isEmpty()).isTrue();
    }

    @Test
    void uploadPublishesAndFinishReportsSuccess() throws Exception {
        String location = uploads.receive(multipart(archive("demo", "1.0.0"), "file", alice));
        assertThat(location).matches("http://granary\\.test/repository/pub-hosted/api/packages/versions/newUploadFinish\\?id=[0-9a-f-]{36}");
        assertThat(storage.paths(repo)).contains("packages/demo/versions/1.0.0.tar.gz");

        ResponseEntity<?> finish = uploads.finish(finishRequest(location, alice));
        assertThat(finish.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(finish).get("success").get("message").stringValue())
                .isEqualTo("Successfully uploaded demo version 1.0.0 to http://granary.test/repository/pub-hosted.");
        // a retried finish gets the same answer
        assertThat(uploads.finish(finishRequest(location, alice)).getStatusCode()).isEqualTo(HttpStatus.OK);
        // nothing but the outcome is left behind
        try (var files = Files.list(uploads.directory())) {
            assertThat(files.map(p -> p.getFileName().toString())).allMatch(n -> n.endsWith(".json"));
        }
    }

    @Test
    void finishReportsTheReasonOfARejectedUpload() throws Exception {
        uploads.receive(multipart(archive("demo", "1.0.0"), "file", alice));
        String location = uploads.receive(multipart(archive("demo", "1.0.0"), "file", alice));
        ResponseEntity<?> finish = uploads.finish(finishRequest(location, alice));
        assertThat(finish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(finish).get("error").get("code").stringValue()).isEqualTo("PackageRejected");
        assertThat(body(finish).get("error").get("message").stringValue()).contains("already exists");

        String bad = uploads.receive(multipart("garbage".getBytes(StandardCharsets.UTF_8), "file", alice));
        ResponseEntity<?> badFinish = uploads.finish(finishRequest(bad, alice));
        assertThat(badFinish.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(badFinish).get("error").get("message").stringValue()).contains("not gzip-compressed");
    }

    @Test
    void aMissingFileFieldIsReportedAtFinish() throws Exception {
        String location = uploads.receive(multipart(null, "file", alice));
        ResponseEntity<?> finish = uploads.finish(finishRequest(location, alice));
        assertThat(finish.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(finish).get("error").get("message").stringValue()).contains("no \"file\" field");
    }

    @Test
    void anyFilePartIsAcceptedWhenTheFieldIsNamedDifferently() throws Exception {
        String location = uploads.receive(multipart(archive("demo", "1.0.0"), "package", alice));
        assertThat(uploads.finish(finishRequest(location, alice)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void servletPartsAndRawBodiesWorkToo() throws Exception {
        MockHttpServletRequest parts = new MockHttpServletRequest("POST", "/x");
        parts.setContentType("multipart/form-data; boundary=x");
        MockPart part = new MockPart("file", "package.tar.gz", archive("demo", "1.0.0"));
        parts.addPart(part);
        String first = uploads.receive(PubTestSupport.request(repo, parts, "api/packages/versions/newUpload", alice));
        assertThat(uploads.finish(finishRequest(first, alice)).getStatusCode()).isEqualTo(HttpStatus.OK);

        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/x");
        raw.setContentType("application/octet-stream");
        raw.setContent(archive("demo", "1.1.0"));
        String second = uploads.receive(PubTestSupport.request(repo, raw, "api/packages/versions/newUpload", alice));
        assertThat(uploads.finish(finishRequest(second, alice)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storage.paths(repo)).contains("packages/demo/versions/1.0.0.tar.gz", "packages/demo/versions/1.1.0.tar.gz");
    }

    @Test
    void oversizedUploadsAreRefused() throws Exception {
        repo.setFormatSettings(Map.of(PubSettings.MAX_ARCHIVE_SIZE, "1"));
        byte[] big = new byte[1024 * 1024 + 1];
        String location = uploads.receive(multipart(big, "file", alice));
        ResponseEntity<?> finish = uploads.finish(finishRequest(location, alice));
        assertThat(finish.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(finish).get("error").get("message").stringValue()).contains("larger than the 1 MiB");

        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/x");
        raw.setContent(big);
        String rawLocation = uploads.receive(PubTestSupport.request(repo, raw, "api/packages/versions/newUpload", alice));
        assertThat(uploads.finish(finishRequest(rawLocation, alice)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyTheUploaderCanReadTheOutcomeInTheSameRepository() throws Exception {
        String location = uploads.receive(multipart(archive("demo", "1.0.0"), "file", alice));
        GranaryPrincipal bob = PubTestSupport.user("bob", "repository:write:*");
        assertThatThrownBy(() -> uploads.finish(finishRequest(location, bob)))
                .isInstanceOfSatisfying(PubError.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        Repository other = PubTestSupport.repository("pub-other", RepositoryType.HOSTED);
        String id = location.substring(location.indexOf("?id=") + 4);
        MockHttpServletRequest http = new MockHttpServletRequest("GET", "/x");
        http.setParameter("id", id);
        assertThatThrownBy(() -> uploads.finish(PubTestSupport.request(other, http, "api/packages/versions/newUploadFinish", alice)))
                .isInstanceOf(PubError.class).hasMessageContaining("unknown or has expired");
    }

    @Test
    void finishRefusesMissingMalformedAndUnknownIds() {
        MockHttpServletRequest none = new MockHttpServletRequest("GET", "/x");
        assertThatThrownBy(() -> uploads.finish(PubTestSupport.request(repo, none, "x", alice)))
                .isInstanceOfSatisfying(PubError.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        for (String id : new String[] {"../../etc/passwd", "ABC", "00000000-0000-0000-0000-00000000000G", ""}) {
            MockHttpServletRequest http = new MockHttpServletRequest("GET", "/x");
            http.setParameter("id", id);
            assertThatThrownBy(() -> uploads.finish(PubTestSupport.request(repo, http, "x", alice)))
                    .isInstanceOfSatisfying(PubError.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        MockHttpServletRequest unknown = new MockHttpServletRequest("GET", "/x");
        unknown.setParameter("id", "00000000-0000-0000-0000-000000000000");
        assertThatThrownBy(() -> uploads.finish(PubTestSupport.request(repo, unknown, "x", alice)))
                .isInstanceOfSatisfying(PubError.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aCorruptOutcomeFileIsTreatedAsUnknown() throws Exception {
        String location = uploads.receive(multipart(archive("demo", "1.0.0"), "file", alice));
        String id = location.substring(location.indexOf("?id=") + 4);
        Files.writeString(uploads.directory().resolve("upload-" + id + ".json"), "{not json");
        assertThatThrownBy(() -> uploads.finish(finishRequest(location, alice))).isInstanceOf(PubError.class);
    }

    @Test
    void oldOutcomesAreCleanedUp() throws Exception {
        String old = uploads.receive(multipart(archive("demo", "1.0.0"), "file", alice));
        String oldId = old.substring(old.indexOf("?id=") + 4);
        Path oldFile = uploads.directory().resolve("upload-" + oldId + ".json");
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(PubUploads.RETENTION).minusSeconds(60)));
        Path leftover = uploads.directory().resolve("upload-leftover.tar.gz");
        Files.write(leftover, new byte[10]);
        Files.setLastModifiedTime(leftover, FileTime.from(Instant.now().minusSeconds(7200)));
        Path unrelated = uploads.directory().resolve("keep-me.txt");
        Files.write(unrelated, new byte[1]);
        Files.setLastModifiedTime(unrelated, FileTime.from(Instant.now().minusSeconds(7200)));

        String fresh = uploads.receive(multipart(archive("demo", "1.1.0"), "file", alice));
        assertThat(oldFile).doesNotExist();
        assertThat(leftover).doesNotExist();
        assertThat(unrelated).exists();
        assertThat(uploads.finish(finishRequest(fresh, alice)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
