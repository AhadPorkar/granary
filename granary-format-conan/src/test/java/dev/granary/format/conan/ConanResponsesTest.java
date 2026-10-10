package dev.granary.format.conan;

import static dev.granary.format.conan.ConanTestSupport.body;
import static dev.granary.format.conan.ConanTestSupport.hosted;
import static dev.granary.format.conan.ConanTestSupport.http;
import static dev.granary.format.conan.ConanTestSupport.json;
import static dev.granary.format.conan.ConanTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class ConanResponsesTest {

    private final Repository repo = hosted("conan-hosted");
    private final byte[] doc = "{\"revision\":\"abc\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void jsonDocumentsCarryExactlyTheContentTypeTheClientAccepts() {
        ResponseEntity<?> response = ConanResponses.json(request(repo, "GET", "v2/conans/a/1/_/_/latest"), doc);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(doc.length);
        assertThat(response.getHeaders().getETag()).startsWith("\"").endsWith("\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(body(response)).isEqualTo("{\"revision\":\"abc\"}");
    }

    @Test
    void matchingEtagIsNotModifiedAndHeadHasNoBody() {
        String etag = ConanResponses.json(request(repo, "GET", "x"), doc).getHeaders().getETag();
        RepositoryRequest conditional = request(repo, "GET", "x");
        http(conditional).addHeader(HttpHeaders.IF_NONE_MATCH, etag);
        ResponseEntity<?> notModified = ConanResponses.json(conditional, doc);
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getBody()).isNull();

        ResponseEntity<?> head = ConanResponses.json(request(repo, "HEAD", "x"), doc);
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(doc.length);
    }

    @Test
    void errorsAreArtifactoryShapedAndValidJson() {
        ResponseEntity<?> error = ConanResponses.error(HttpStatus.NOT_FOUND, "content.notFound",
                "Recipe \"x\" not found\\\n\t\u0001");
        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(error.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode node = json(body(error));
        assertThat(node.path("code").stringValue()).isEqualTo("content.notFound");
        assertThat(node.path("message").stringValue()).isEqualTo("Recipe \"x\" not found\\\n\t\u0001");
        assertThat(node.path("errors").get(0).path("status").intValue()).isEqualTo(404);
        assertThat(node.path("errors").get(0).path("message").stringValue()).startsWith("Recipe \"x\"");
    }

    @Test
    void errorFromException() {
        ResponseEntity<?> error = ConanResponses.error(ConanError.conflict("repository.conan.revisionExists", "exists"));
        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(body(error)).path("code").stringValue()).isEqualTo("repository.conan.revisionExists");
        assertThat(ConanResponses.notFound("gone").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void textIsPlainUtf8WithALength() {
        ResponseEntity<?> text = ConanResponses.text(HttpStatus.OK, "gry_tökén");
        assertThat(text.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(text.getHeaders().getContentLength()).isEqualTo("gry_tökén".getBytes(StandardCharsets.UTF_8).length);
        assertThat(body(text)).isEqualTo("gry_tökén");
    }

    @Test
    void escapeHandlesNull() {
        assertThat(ConanResponses.escape(null)).isEmpty();
        assertThat(ConanResponses.escape("a\rb")).isEqualTo("a\\rb");
    }

    @Test
    void errorFactories() {
        assertThat(ConanError.notFound("x").status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ConanError.badRequest("c", "x").status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ConanError.unauthorized("x").status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ConanError.unauthorized("x").code()).isEqualTo("auth.required");
        assertThat(ConanError.forbidden("c", "x").status()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
