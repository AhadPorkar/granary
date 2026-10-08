package dev.granary.server.maven;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.model.VersionPolicy;
import dev.granary.core.proxy.NegativeCache;
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.server.IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** The Maven protocol end to end: deploy, resolve, checksums, metadata, proxying and offline behaviour. */
class MavenRepositoryIntegrationTest extends IntegrationTest {

    static final String JAR = "com/acme/demo/1.0/demo-1.0.jar";
    static final String POM = "com/acme/demo/1.0/demo-1.0.pom";
    static final String SHA1_OF_HELLO = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";

    @Autowired
    UpstreamHealth health;

    @Autowired
    NegativeCache negativeCache;

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

    // --- hosted ---

    @Test
    void deployAndResolveFromHosted() throws Exception {
        hosted("maven-releases", "maven2");

        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content("hello"))
                .andExpect(status().isCreated());
        mvc.perform(put("/repository/maven-releases/" + POM).header("Authorization", adminBasic())
                        .content("<project/>"))
                .andExpect(status().isCreated());

        mvc.perform(get("/repository/maven-releases/" + JAR))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/java-archive"))
                .andExpect(header().string("Content-Length", "5"))
                .andExpect(header().exists("ETag"))
                .andExpect(content().string("hello"));
        mvc.perform(head("/repository/maven-releases/" + JAR))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", "5"));
        mvc.perform(get("/repository/maven-releases/" + POM))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/xml"));

        mvc.perform(get("/api/v1/repositories/maven-releases").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.statistics.assets").value(2))
                .andExpect(jsonPath("$.statistics.components").value(1))
                .andExpect(jsonPath("$.statistics.bytes").value(15));
        mvc.perform(get("/api/v1/repositories/maven-releases/components").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.items[0].namespace").value("com.acme"))
                .andExpect(jsonPath("$.items[0].name").value("demo"))
                .andExpect(jsonPath("$.items[0].version").value("1.0"));
    }

    @Test
    void conditionalGetReturns304() throws Exception {
        hosted("maven-releases", "maven2");
        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", adminBasic()).content("hello"))
                .andExpect(status().isCreated());
        String etag = mvc.perform(get("/repository/maven-releases/" + JAR)).andReturn().getResponse().getHeader("ETag");

        mvc.perform(get("/repository/maven-releases/" + JAR).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void checksumsAreComputedWhenMissingAndVerifiedWhenUploaded() throws Exception {
        hosted("maven-releases", "maven2");
        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", adminBasic()).content("hello"))
                .andExpect(status().isCreated());

        mvc.perform(get("/repository/maven-releases/" + JAR + ".sha1"))
                .andExpect(status().isOk())
                .andExpect(content().string(SHA1_OF_HELLO));
        mvc.perform(get("/repository/maven-releases/" + JAR + ".md5"))
                .andExpect(status().isOk())
                .andExpect(content().string("5d41402abc4b2a76b9719d911017c592"));
        mvc.perform(get("/repository/maven-releases/" + JAR + ".sha256"))
                .andExpect(status().isOk())
                .andExpect(content().string("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));

        mvc.perform(put("/repository/maven-releases/" + JAR + ".sha1").header("Authorization", adminBasic())
                        .content("deadbeef"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.maven.checksumMismatch"));
        mvc.perform(put("/repository/maven-releases/" + JAR + ".sha1").header("Authorization", adminBasic())
                        .content(SHA1_OF_HELLO + "  demo-1.0.jar\n"))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/maven-releases/" + JAR + ".sha1"))
                .andExpect(content().string(SHA1_OF_HELLO + "  demo-1.0.jar\n"));
        mvc.perform(get("/repository/maven-releases/" + JAR + ".sha1.sha1")).andExpect(status().isNotFound());
    }

    @Test
    void releaseRedeployIsRejectedUnlessAllowed() throws Exception {
        Repository repo = hosted("maven-releases", "maven2");
        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", adminBasic()).content("v1"))
                .andExpect(status().isCreated());
        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", adminBasic()).content("v2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.maven.redeploy"));
        mvc.perform(get("/repository/maven-releases/" + JAR)).andExpect(content().string("v1"));

        repo.setAllowRedeploy(true);
        repositories.update("maven-releases", repo);
        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", adminBasic()).content("v2"))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/maven-releases/" + JAR)).andExpect(content().string("v2"));
    }

    @Test
    void versionPolicyIsEnforced() throws Exception {
        Repository releases = hosted("maven-releases", "maven2");
        releases.setVersionPolicy(VersionPolicy.RELEASE);
        repositories.update("maven-releases", releases);
        Repository snapshots = hosted("maven-snapshots", "maven2");
        snapshots.setVersionPolicy(VersionPolicy.SNAPSHOT);
        repositories.update("maven-snapshots", snapshots);

        mvc.perform(put("/repository/maven-releases/com/acme/demo/1.0-SNAPSHOT/demo-1.0-SNAPSHOT.jar")
                        .header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.maven.policy.release"));
        mvc.perform(put("/repository/maven-snapshots/" + JAR).header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("repository.maven.policy.snapshot"));
        mvc.perform(put("/repository/maven-snapshots/com/acme/demo/1.0-SNAPSHOT/demo-1.0-20260101.120000-1.jar")
                        .header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isCreated());
        // snapshots may always be redeployed
        mvc.perform(put("/repository/maven-snapshots/com/acme/demo/1.0-SNAPSHOT/demo-1.0-20260101.120000-1.jar")
                        .header("Authorization", adminBasic()).content("y"))
                .andExpect(status().isCreated());
    }

    @Test
    void artifactLevelMetadataIsGeneratedFromComponentsAndUploadedMetadataWins() throws Exception {
        hosted("maven-releases", "maven2");
        mvc.perform(put("/repository/maven-releases/com/acme/demo/1.0/demo-1.0.jar").header("Authorization", adminBasic()).content("a"))
                .andExpect(status().isCreated());
        mvc.perform(put("/repository/maven-releases/com/acme/demo/1.10/demo-1.10.jar").header("Authorization", adminBasic()).content("b"))
                .andExpect(status().isCreated());
        mvc.perform(put("/repository/maven-releases/com/acme/demo/1.2/demo-1.2.jar").header("Authorization", adminBasic()).content("c"))
                .andExpect(status().isCreated());

        String xml = mvc.perform(get("/repository/maven-releases/com/acme/demo/maven-metadata.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/xml"))
                .andReturn().getResponse().getContentAsString();
        assertThat(xml).contains("<version>1.0</version><version>1.2</version><version>1.10</version>")
                .contains("<latest>1.10</latest>").contains("<release>1.10</release>");
        mvc.perform(get("/repository/maven-releases/com/acme/demo/maven-metadata.xml.sha1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{40}")));

        String uploaded = "<metadata><groupId>com.acme</groupId><artifactId>demo</artifactId><versioning><versions><version>9.9</version></versions></versioning></metadata>";
        mvc.perform(put("/repository/maven-releases/com/acme/demo/maven-metadata.xml").header("Authorization", adminBasic()).content(uploaded))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/maven-releases/com/acme/demo/maven-metadata.xml"))
                .andExpect(content().string(uploaded));
        mvc.perform(get("/repository/maven-releases/com/acme/missing/maven-metadata.xml")).andExpect(status().isNotFound());
    }

    @Test
    void deleteAndDirectoryListing() throws Exception {
        hosted("maven-releases", "maven2");
        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", adminBasic()).content("a")).andExpect(status().isCreated());
        mvc.perform(put("/repository/maven-releases/" + POM).header("Authorization", adminBasic()).content("b")).andExpect(status().isCreated());

        mvc.perform(get("/repository/maven-releases/com/acme/demo/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<a href=\"1.0/\">1.0/</a>")));
        mvc.perform(get("/repository/maven-releases/"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<a href=\"com/\">com/</a>")));

        mvc.perform(delete("/repository/maven-releases/" + JAR)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/repository/maven-releases/" + JAR).header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(get("/repository/maven-releases/" + JAR)).andExpect(status().isNotFound());
        mvc.perform(delete("/repository/maven-releases/com/acme/").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(get("/repository/maven-releases/" + POM)).andExpect(status().isNotFound());
        mvc.perform(delete("/repository/maven-releases/nothing/").header("Authorization", adminBasic())).andExpect(status().isNotFound());
    }

    @Test
    void unsupportedMethodsAndIllegalPaths() throws Exception {
        hosted("maven-releases", "maven2");
        mvc.perform(post("/repository/maven-releases/" + JAR).header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/repository/maven-releases/com/../etc/passwd")).andExpect(status().isBadRequest());
        mvc.perform(get("/repository/nope/" + JAR)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("repository.notFound"));
    }

    // --- proxy ---

    @Test
    void proxyCachesArtifactsAndServesThemDuringAnOutage() throws Exception {
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/maven2/" + JAR)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/java-archive").withBody("remote-jar")));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/maven2/" + JAR + ".sha1")).willReturn(aResponse().withStatus(404)));
        Repository central = proxy("central", "maven2", upstream.baseUrl() + "/maven2/");

        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isOk()).andExpect(content().string("remote-jar"));
        // the checksum is computed locally because upstream has none
        mvc.perform(get("/repository/central/" + JAR + ".sha1")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{40}")));
        mvc.perform(put("/repository/central/" + JAR).header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"));

        upstream.stop();
        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isOk()).andExpect(content().string("remote-jar"));
        // what was never cached is a temporary failure, not a 404 Maven would remember
        mvc.perform(get("/repository/central/com/acme/demo/1.0/demo-1.0-sources.jar"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        assertThat(health.status(central).lastError()).isNotNull();

        mvc.perform(get("/api/v1/repositories/central").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.upstream.consecutiveFailures").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.statistics.assets").value(1));
    }

    @Test
    void proxyRevalidatesMetadataAndFallsBackToStaleCopy() throws Exception {
        String v1 = "<metadata><groupId>com.acme</groupId><artifactId>demo</artifactId><versioning><versions><version>1.0</version></versions></versioning></metadata>";
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/maven2/com/acme/demo/maven-metadata.xml")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml").withBody(v1)));
        Repository central = proxy("central", "maven2", upstream.baseUrl() + "/maven2/");
        central.setMetadataMaxAgeMinutes(0);
        repositories.update("central", central);

        mvc.perform(get("/repository/central/com/acme/demo/maven-metadata.xml")).andExpect(content().string(v1));
        String v2 = v1.replace("1.0", "2.0");
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/maven2/com/acme/demo/maven-metadata.xml")).willReturn(aResponse().withStatus(200).withBody(v2)));
        mvc.perform(get("/repository/central/com/acme/demo/maven-metadata.xml")).andExpect(content().string(v2));
        upstream.verify(2, getRequestedFor(urlEqualTo("/maven2/com/acme/demo/maven-metadata.xml")));

        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/maven2/com/acme/demo/maven-metadata.xml")).willReturn(aResponse().withStatus(503)));
        mvc.perform(get("/repository/central/com/acme/demo/maven-metadata.xml")).andExpect(status().isOk()).andExpect(content().string(v2));

        // offline switch: served from cache, upstream never asked
        mvc.perform(post("/api/v1/repositories/central/offline").header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"offline\":true}"))
                .andExpect(jsonPath("$.offlineMode").value(true));
        upstream.resetRequests();
        mvc.perform(get("/repository/central/com/acme/demo/maven-metadata.xml")).andExpect(content().string(v2));
        upstream.verify(0, getRequestedFor(urlEqualTo("/maven2/com/acme/demo/maven-metadata.xml")));
    }

    @Test
    void proxyRemembersMissingArtifactsUntilInvalidated() throws Exception {
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/maven2/" + JAR)).willReturn(aResponse().withStatus(404)));
        proxy("central", "maven2", upstream.baseUrl() + "/maven2/");

        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isNotFound());
        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isNotFound());
        upstream.verify(1, getRequestedFor(urlEqualTo("/maven2/" + JAR)));

        mvc.perform(post("/api/v1/repositories/central/invalidate").header("Authorization", adminBearer()))
                .andExpect(status().isOk());
        mvc.perform(get("/repository/central/" + JAR)).andExpect(status().isNotFound());
        upstream.verify(2, getRequestedFor(urlEqualTo("/maven2/" + JAR)));
    }

    // --- group ---

    @Test
    void groupResolvesFromMembersInOrderAndMergesMetadata() throws Exception {
        String remoteMetadata = "<metadata><groupId>com.acme</groupId><artifactId>demo</artifactId><versioning><latest>0.9</latest><release>0.9</release><versions><version>0.9</version></versions><lastUpdated>20200101000000</lastUpdated></versioning></metadata>";
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/maven2/com/acme/demo/maven-metadata.xml")).willReturn(aResponse().withStatus(200).withBody(remoteMetadata)));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/maven2/com/acme/demo/0.9/demo-0.9.jar")).willReturn(aResponse().withStatus(200).withBody("remote")));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/maven2/" + JAR)).willReturn(aResponse().withStatus(200).withBody("remote-1.0")));
        hosted("maven-releases", "maven2");
        proxy("central", "maven2", upstream.baseUrl() + "/maven2/");
        group("maven-public", "maven2", "maven-releases", "central");
        mvc.perform(put("/repository/maven-releases/" + JAR).header("Authorization", adminBasic()).content("local-1.0"))
                .andExpect(status().isCreated());

        mvc.perform(get("/repository/maven-public/" + JAR)).andExpect(content().string("local-1.0"));
        mvc.perform(get("/repository/maven-public/com/acme/demo/0.9/demo-0.9.jar")).andExpect(content().string("remote"));
        mvc.perform(get("/repository/maven-public/com/acme/demo/0.9/demo-0.9.jar.sha1"))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{40}")));

        String merged = mvc.perform(get("/repository/maven-public/com/acme/demo/maven-metadata.xml"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(merged).contains("<version>0.9</version><version>1.0</version>")
                .contains("<latest>1.0</latest>").contains("<release>1.0</release>");
        String checksum = mvc.perform(get("/repository/maven-public/com/acme/demo/maven-metadata.xml.sha256"))
                .andReturn().getResponse().getContentAsString();
        assertThat(checksum).isEqualTo(dev.granary.core.storage.Checksums.hex("SHA-256", merged.getBytes(StandardCharsets.UTF_8)));

        mvc.perform(put("/repository/maven-public/" + JAR).header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/repository/maven-public/" + JAR).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/repository/maven-public/com/acme/nothing/maven-metadata.xml")).andExpect(status().isNotFound());
    }
}
