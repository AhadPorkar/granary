package dev.granary.format.cargo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.format.RepositoryRequest;
import dev.granary.core.model.Repository;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class CargoResponsesTest {

    private final Repository repo = CargoTestSupport.hosted("cargo-hosted");

    @Test
    void errorsHaveTheRegistryApiShape() {
        ResponseEntity<?> response = CargoResponses.error(HttpStatus.BAD_REQUEST, "bad \"thing\"\nhappened");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(CargoTestSupport.text(response)).isEqualTo("{\"errors\":[{\"detail\":\"bad \\\"thing\\\"\\nhappened\"}]}");
        assertThat(CargoTestSupport.errorDetail(CargoResponses.error(CargoError.notFound("gone")))).isEqualTo("gone");
        assertThat(CargoTestSupport.text(CargoResponses.error(HttpStatus.NOT_FOUND, null))).isEqualTo("{\"errors\":[{\"detail\":\"\"}]}");
    }

    @Test
    void okBodies() {
        assertThat(CargoTestSupport.text(CargoResponses.ok(null))).isEqualTo("{\"ok\":true}");
        assertThat(CargoTestSupport.text(CargoResponses.ok("done"))).isEqualTo("{\"ok\":true,\"msg\":\"done\"}");
    }

    @Test
    void documentsCarryAnEtagAndAnswerConditionalRequests() {
        byte[] body = "line\n".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<?> first = CargoResponses.document(CargoTestSupport.request(repo, "GET", "index/1/a"), body,
                CargoResponses.INDEX_CONTENT_TYPE);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String etag = first.getHeaders().getETag();
        assertThat(etag).startsWith("\"").hasSize(66);
        assertThat(first.getHeaders().getContentLength()).isEqualTo(5);
        assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(CargoTestSupport.body(first)).isEqualTo(body);

        RepositoryRequest conditional = CargoTestSupport.request(repo, "GET", "index/1/a", null, Map.of("If-None-Match", etag));
        ResponseEntity<?> notModified = CargoResponses.document(conditional, body, CargoResponses.INDEX_CONTENT_TYPE);
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getBody()).isNull();
        assertThat(notModified.getHeaders().getETag()).isEqualTo(etag);

        RepositoryRequest stale = CargoTestSupport.request(repo, "GET", "index/1/a", null, Map.of("If-None-Match", "\"other\""));
        assertThat(CargoResponses.document(stale, body, CargoResponses.INDEX_CONTENT_TYPE).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<?> head = CargoResponses.document(CargoTestSupport.request(repo, "HEAD", "index/1/a"), body,
                CargoResponses.INDEX_CONTENT_TYPE);
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getBody()).isNull();
        assertThat(head.getHeaders().getContentLength()).isEqualTo(5);
    }

    @Test
    void htmlIsEscaped() {
        assertThat(CargoResponses.escapeHtml("<a href=\"x\">'&'</a>")).isEqualTo("&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;");
        assertThat(CargoResponses.escapeHtml(null)).isEmpty();
    }
}
