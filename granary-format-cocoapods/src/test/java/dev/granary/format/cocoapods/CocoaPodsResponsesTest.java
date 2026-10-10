package dev.granary.format.cocoapods;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.security.GranaryPrincipal;
import dev.granary.core.storage.Checksums;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class CocoaPodsResponsesTest {

    private static final byte[] SHARD = "AFNetworking/4.0.0/4.0.1\n".getBytes(StandardCharsets.UTF_8);
    private static final String ETAG = "\"" + Checksums.hex("SHA-256", SHARD) + "\"";

    private static RepositoryRequest request(String method, String ifNoneMatch) {
        MockHttpServletRequest http = new MockHttpServletRequest(method, "/repository/pods/all_pods_versions_a_7_5.txt");
        if (ifNoneMatch != null) {
            http.addHeader(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        }
        GranaryPrincipal anonymous = new GranaryPrincipal(null, "anonymous", Set.of(), Set.of(), true,
                GranaryPrincipal.AuthMethod.NONE);
        return new RepositoryRequest(CocoaPodsTestSupport.repository("pods", RepositoryType.HOSTED),
                "all_pods_versions_a_7_5.txt", http, anonymous, "http://granary.test/repository/pods");
    }

    @Test
    void documentsCarryAContentDigestETagAndNoCache() {
        ResponseEntity<?> response = CocoaPodsResponses.document(request("GET", null), SHARD, PodIndex.CONTENT_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        assertThat(response.getHeaders().getContentLength()).isEqualTo(SHARD.length);
        assertThat((byte[]) response.getBody()).isEqualTo(SHARD);
    }

    @Test
    void headHasTheHeadersButNoBody() {
        ResponseEntity<?> response = CocoaPodsResponses.document(request("HEAD", null), SHARD, PodIndex.CONTENT_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(SHARD.length);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"<etag>", "W/<etag>", "\"other\", <etag>", "\"other\",W/<etag>", "*", " * "})
    void matchingIfNoneMatchIsNotModified(String header) {
        ResponseEntity<?> response = CocoaPodsResponses.document(request("GET", header.replace("<etag>", ETAG)), SHARD,
                PodIndex.CONTENT_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"other\"", "<bare>", "\"<bare>\"x", "W/\"other\""})
    void anyOtherIfNoneMatchGetsTheDocument(String header) {
        String bare = ETAG.substring(1, ETAG.length() - 1);
        ResponseEntity<?> response = CocoaPodsResponses.document(request("GET", header.replace("<bare>", bare)), SHARD,
                PodIndex.CONTENT_TYPE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((byte[]) response.getBody()).isEqualTo(SHARD);
    }

    @Test
    void archivesAreNamedAsAttachmentsAndOnlySuccessfulResponsesChange() {
        ResponseEntity<?> ok = ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).body(new byte[] {1});
        ResponseEntity<?> named = CocoaPodsResponses.attachment(ok, "Foo \"1.0\".zip");
        assertThat(named.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"Foo 1.0.zip\"");
        assertThat(named.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/zip"));

        ResponseEntity<?> notModified = ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag("\"x\"").build();
        assertThat(CocoaPodsResponses.attachment(notModified, "Foo.zip")).isSameAs(notModified);
    }

    @Test
    void errorsAreCodeAndMessage() {
        ResponseEntity<?> response = CocoaPodsResponses.error(CocoaPodsError.versionExists("Foo", "1.0"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "repository.cocoapods.versionExists",
                "message", "Foo 1.0 already exists and redeployment is disabled for this repository"));
    }
}
