package dev.granary.server.maven;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static dev.granary.server.testsupport.ComponentApi.gav;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import dev.granary.server.testsupport.ComponentApi;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** What a Maven proxy caches shows up in the component browser and search, the way a deployment to a hosted one does. */
class MavenProxyComponentsIntegrationTest extends IntegrationTest {

    static final String DIR = "com/acme/demo/1.0/";
    static final String JAR = DIR + "demo-1.0.jar";
    static final String POM = DIR + "demo-1.0.pom";
    static final String METADATA = "com/acme/demo/maven-metadata.xml";

    @Autowired
    StorageService storage;

    WireMockServer upstream;
    ComponentApi components;
    Repository central;

    @BeforeEach
    void setUp() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        central = proxy("central", "maven2", upstream.baseUrl() + "/maven2/");
        components = new ComponentApi(mvc, adminBearer());
        stub(JAR, "jar-bytes");
        stub(JAR + ".sha1", "0123456789abcdef0123456789abcdef01234567");
        stub(POM, "<project/>");
        stub(METADATA, "<metadata><groupId>com.acme</groupId><artifactId>demo</artifactId></metadata>");
        stub(METADATA + ".sha1", "89abcdef0123456789abcdef0123456789abcdef");
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop();
    }

    @Test
    void cachedArtifactsAndTheirChecksumsFormOneComponentAndMetadataNone() throws Exception {
        for (String path : List.of(METADATA, METADATA + ".sha1", POM, JAR, JAR + ".sha1")) {
            mvc.perform(get("/repository/central/" + path)).andExpect(status().isOk());
        }

        JsonNode component = components.single("central");
        assertThat(gav(component)).isEqualTo("com.acme:demo:1.0");
        assertThat(component.path("format").asString()).isEqualTo("maven2");
        assertThat(components.assetPaths("central", component)).containsExactly(JAR, JAR + ".sha1", POM);
        assertThat(storage.find(central, METADATA).orElseThrow().asset().getComponentId()).isNull();
        assertThat(storage.find(central, METADATA + ".sha1").orElseThrow().asset().getComponentId()).isNull();
        mvc.perform(get("/api/v1/repositories/central").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.statistics.components").value(1))
                .andExpect(jsonPath("$.statistics.assets").value(5));
    }

    @Test
    void repeatedAndConcurrentDownloadsKeepOneComponentAndOneUpstreamRequestPerFile() throws Exception {
        List<Integer> statuses = ComponentApi.concurrently(8, i -> () ->
                mvc.perform(get("/repository/central/" + (i % 2 == 0 ? JAR : POM))).andReturn().getResponse().getStatus());
        mvc.perform(get("/repository/central/" + JAR)).andExpect(content().string("jar-bytes"));

        assertThat(statuses).containsOnly(200);
        assertThat(gav(components.single("central"))).isEqualTo("com.acme:demo:1.0");
        assertThat(components.assetPaths("central", components.single("central"))).containsExactly(JAR, POM);
        upstream.verify(1, getRequestedFor(urlEqualTo("/maven2/" + JAR)));
        upstream.verify(1, getRequestedFor(urlEqualTo("/maven2/" + POM)));
    }

    @Test
    void searchFindsTheCachedComponent() throws Exception {
        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isOk());

        assertThat(components.search("demo")).extracting(ComponentApi::gav).containsExactly("com.acme:demo:1.0");
        assertThat(components.search("com.acme")).extracting(c -> c.path("repository").asString()).containsExactly("central");
        assertThat(components.list("central", "dem")).hasSize(1);
        assertThat(components.search("nothing-like-it")).isEmpty();
    }

    @Test
    void deletingTheComponentRemovesTheCachedFilesWhichAreFetchedAgainWhenNeeded() throws Exception {
        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isOk());
        mvc.perform(get("/repository/central/" + JAR + ".sha1")).andExpect(status().isOk());
        mvc.perform(get("/repository/central/" + METADATA)).andExpect(status().isOk());

        assertThat(components.delete("central", components.single("central"))).isEqualTo(2);
        assertThat(storage.find(central, JAR)).isEmpty();
        assertThat(storage.find(central, JAR + ".sha1")).isEmpty();
        assertThat(storage.find(central, METADATA)).isPresent(); // not part of the component
        assertThat(components.list("central")).isEmpty();

        // offline, a deleted file is as missing as one never cached
        repositories.setOffline("central", true);
        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        mvc.perform(get("/repository/central/" + METADATA)).andExpect(status().isOk());

        repositories.setOffline("central", false);
        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isOk()).andExpect(content().string("jar-bytes"));
        assertThat(gav(components.single("central"))).isEqualTo("com.acme:demo:1.0");
        upstream.verify(2, getRequestedFor(urlEqualTo("/maven2/" + JAR)));
    }

    @Test
    void artifactsCachedBeforeComponentsWereRecordedAreLinkedWhenServed() throws Exception {
        // what a proxy of an earlier version holds: the file, no component
        storage.store(central, JAR, new ByteArrayInputStream("old-jar".getBytes(StandardCharsets.UTF_8)), null,
                Asset.Origin.CACHED, false);
        repositories.setOffline("central", true);
        assertThat(components.list("central")).isEmpty();

        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isOk()).andExpect(content().string("old-jar"));

        assertThat(gav(components.single("central"))).isEqualTo("com.acme:demo:1.0");
        upstream.verify(0, getRequestedFor(urlEqualTo("/maven2/" + JAR)));
    }

    @Test
    void downloadsThroughAGroupAreRecordedInTheProxyMember() throws Exception {
        hosted("releases", "maven2");
        group("public", "maven2", "releases", "central");
        mvc.perform(put("/repository/releases/com/acme/own/2.0/own-2.0.jar").header("Authorization", adminBasic())
                .content("own")).andExpect(status().isCreated());

        mvc.perform(get("/repository/public/" + JAR)).andExpect(status().isOk());
        mvc.perform(get("/repository/public/" + JAR + ".sha1")).andExpect(status().isOk());
        mvc.perform(get("/repository/public/com/acme/own/2.0/own-2.0.jar")).andExpect(status().isOk());

        assertThat(gav(components.single("central"))).isEqualTo("com.acme:demo:1.0");
        assertThat(components.assetPaths("central", components.single("central"))).containsExactly(JAR, JAR + ".sha1");
        assertThat(gav(components.single("releases"))).isEqualTo("com.acme:own:2.0");
        assertThat(components.list("public")).isEmpty();
        assertThat(components.search("com.acme")).extracting(ComponentApi::gav)
                .containsExactlyInAnyOrder("com.acme:demo:1.0", "com.acme:own:2.0");
    }

    @Test
    void checksumsUploadedToAHostedRepositoryJoinTheirArtifactsComponent() throws Exception {
        hosted("releases", "maven2");
        mvc.perform(put("/repository/releases/" + JAR).header("Authorization", adminBasic()).content("hello"))
                .andExpect(status().isCreated());
        mvc.perform(put("/repository/releases/" + JAR + ".sha1").header("Authorization", adminBasic())
                .content("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d")).andExpect(status().isCreated());
        mvc.perform(put("/repository/releases/" + METADATA).header("Authorization", adminBasic())
                .content("<metadata/>")).andExpect(status().isCreated());
        mvc.perform(put("/repository/releases/" + METADATA + ".sha1").header("Authorization", adminBasic())
                .content(sha1("<metadata/>"))).andExpect(status().isCreated());

        JsonNode component = components.single("releases");
        assertThat(components.assetPaths("releases", component)).containsExactly(JAR, JAR + ".sha1");
        assertThat(components.delete("releases", component)).isEqualTo(2);
        mvc.perform(get("/repository/releases/" + JAR + ".sha1")).andExpect(status().isNotFound());
        mvc.perform(get("/repository/releases/" + METADATA + ".sha1")).andExpect(status().isOk());
    }

    private static String sha1(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private void stub(String path, String body) {
        upstream.stubFor(WireMock.get(urlEqualTo("/maven2/" + path)).willReturn(aResponse().withStatus(200).withBody(body)));
    }
}
