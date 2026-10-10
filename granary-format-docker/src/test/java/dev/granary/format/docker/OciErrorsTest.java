package dev.granary.format.docker;

import static dev.granary.format.docker.DockerTestSupport.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class OciErrorsTest {

    @Test
    void bodyHasTheOciShape() {
        JsonNode body = MAPPER.readTree(OciErrors.body(OciErrorCode.MANIFEST_UNKNOWN, "no such manifest", null));
        JsonNode error = body.path("errors").path(0);
        assertThat(body.path("errors").size()).isEqualTo(1);
        assertThat(error.path("code").stringValue()).isEqualTo("MANIFEST_UNKNOWN");
        assertThat(error.path("message").stringValue()).isEqualTo("no such manifest");
        assertThat(error.path("detail").isNull()).isTrue();
    }

    @Test
    void defaultMessageAndDetail() {
        JsonNode body = MAPPER.readTree(OciErrors.body(OciErrorCode.BLOB_UNKNOWN, null, Map.of("digest", "sha256:abc")));
        JsonNode error = body.path("errors").path(0);
        assertThat(error.path("message").stringValue()).isEqualTo(OciErrorCode.BLOB_UNKNOWN.defaultMessage());
        assertThat(error.path("detail").path("digest").stringValue()).isEqualTo("sha256:abc");
    }

    @Test
    void everyCodeHasADefaultMessage() {
        for (OciErrorCode code : OciErrorCode.values()) {
            assertThat(code.defaultMessage()).isNotBlank();
        }
    }

    @Test
    void responsesCarryVersionHeaderAndBasicChallengeOn401() {
        ResponseEntity<?> unauthorized = OciErrors.response(DockerError.unauthorized("login first"));
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthorized.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Basic realm=\"Granary\"");
        assertThat(unauthorized.getHeaders().getFirst(DockerResponses.API_VERSION_HEADER)).isEqualTo("registry/2.0");
        assertThat(unauthorized.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(unauthorized.getHeaders().getContentLength()).isEqualTo(((byte[]) unauthorized.getBody()).length);
        assertThat(DockerTestSupport.errorCode(unauthorized)).isEqualTo("UNAUTHORIZED");

        ResponseEntity<?> notFound = OciErrors.response(DockerError.notFound(OciErrorCode.NAME_UNKNOWN, "nope"));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
    }

    @Test
    void factoriesPickStatusAndCode() {
        assertThat(DockerError.nameInvalid("Bad").status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(DockerError.nameInvalid("Bad").code()).isEqualTo(OciErrorCode.NAME_INVALID);
        assertThat(DockerError.manifestInvalid("x").code()).isEqualTo(OciErrorCode.MANIFEST_INVALID);
        assertThat(DockerError.unsupported("x").status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(DockerError.unsupported("x").code()).isEqualTo(OciErrorCode.UNSUPPORTED);
        assertThat(DockerError.denied(HttpStatus.FORBIDDEN, "x").code()).isEqualTo(OciErrorCode.DENIED);
        assertThat(DockerError.badRequest(OciErrorCode.DIGEST_INVALID, "x", Map.of("a", 1)).detail()).containsEntry("a", 1);
        assertThat(DockerError.notFound(OciErrorCode.BLOB_UNKNOWN, "x").detail()).isNull();
    }
}
