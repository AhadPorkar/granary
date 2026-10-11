package dev.granary.server.storage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.dao.AssetDao;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.AssetSanitiser;
import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Values that do not fit the database columns: upstream headers are sanitised so the file is still cached, and
 * over-long paths are a client error, never a 500.
 */
class StorageLimitsIntegrationTest extends IntegrationTest {

    private static final String JAR = "com/acme/huge/1.0/huge-1.0.jar";

    @Autowired
    AssetDao assets;

    WireMockServer upstream;

    @BeforeEach
    void startUpstream() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop();
    }

    @Test
    void proxyCachesAndServesAFileWhoseUpstreamHeadersOverflowTheColumns() throws Exception {
        upstream.stubFor(WireMock.get(urlEqualTo("/maven2/" + JAR)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/java-archive; comment=\"" + "c".repeat(300) + "\"")
                .withHeader("ETag", "\"" + "e".repeat(800) + "\"")
                .withHeader("Last-Modified", "Wed, 01 Jan 2026 00:00:00 GMT; " + "l".repeat(200))
                .withBody("remote-jar")));
        Repository central = proxy("central", "maven2", upstream.baseUrl() + "/maven2/");

        mvc.perform(get("/repository/central/" + JAR))
                .andExpect(status().isOk())
                .andExpect(content().string("remote-jar"))
                .andExpect(header().string("Content-Type", "application/java-archive"));

        Asset cached = assets.findByRepositoryIdAndPath(central.getId(), JAR).orElseThrow();
        assertThat(cached.getContentType()).isEqualTo("application/java-archive");
        assertThat(cached.getUpstreamEtag()).isNull();
        assertThat(cached.getUpstreamLastModified()).isNull();

        // served from the cache from now on, also while the upstream is gone
        upstream.stop();
        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isOk()).andExpect(content().string("remote-jar"));
    }

    @Test
    void proxyFallsBackToTheGuessedTypeWhenTheBareContentTypeIsTooLong() throws Exception {
        upstream.stubFor(WireMock.get(urlEqualTo("/maven2/" + JAR)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/vnd." + "v".repeat(400))
                .withBody("remote-jar")));
        proxy("central", "maven2", upstream.baseUrl() + "/maven2/");

        mvc.perform(get("/repository/central/" + JAR))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/java-archive"));
        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isOk());
        upstream.verify(1, getRequestedFor(urlEqualTo("/maven2/" + JAR)));
    }

    @Test
    void overlongRequestPathIsABadRequest() throws Exception {
        hosted("files", "raw");
        String tooLong = "dir/" + "x".repeat(AssetSanitiser.MAX_PATH_LENGTH);

        mvc.perform(get("/repository/files/" + tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));
        mvc.perform(put("/repository/files/" + tooLong).header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));
    }

    @Test
    void overlongPathOnAProxyIsABadRequestAndNeverReachesTheUpstream() throws Exception {
        proxy("central", "maven2", upstream.baseUrl() + "/maven2/");
        String tooLong = "com/acme/" + "a".repeat(AssetSanitiser.MAX_PATH_LENGTH) + "/1.0/a-1.0.jar";

        mvc.perform(get("/repository/central/" + tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));
        upstream.verify(0, getRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void overlongPathInTheRestApiIsABadRequestToo() throws Exception {
        hosted("files", "raw");

        mvc.perform(get("/api/v1/repositories/files/assets")
                        .param("prefix", "p".repeat(AssetSanitiser.MAX_PATH_LENGTH + 1))
                        .header("Authorization", adminBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));
    }
}
