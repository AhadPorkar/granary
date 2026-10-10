package dev.granary.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.repository.RepositoryService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Requests the framework rejects before any controller runs must still get a clean 4xx. MockMvc skips the
 * servlet container's multipart parsing, so this runs against the real embedded server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MalformedRequestIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    RepositoryService repositories;

    @Test
    void multipartUploadWithoutBoundaryIsABadRequest() throws Exception {
        if (repositories.find("multipart-hosted").isEmpty()) {
            Repository repo = new Repository();
            repo.setName("multipart-hosted");
            repo.setFormat("maven2");
            repo.setType(RepositoryType.HOSTED);
            repositories.create(repo);
        }
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:admin123".getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/repository/multipart-hosted/upload"))
                .header("Authorization", auth)
                .header("Content-Type", "multipart/form-data")
                .POST(HttpRequest.BodyPublishers.ofString("not really multipart"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("request.multipart.malformed");
    }
}
