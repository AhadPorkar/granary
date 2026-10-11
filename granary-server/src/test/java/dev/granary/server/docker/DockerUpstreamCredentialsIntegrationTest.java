package dev.granary.server.docker;

import static dev.granary.server.UpstreamCredentialFixtures.PASSWORD;
import static dev.granary.server.UpstreamCredentialFixtures.USER;
import static dev.granary.server.UpstreamCredentialFixtures.authorisedRequests;
import static dev.granary.server.UpstreamCredentialFixtures.proxyWithCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withoutCredentials;
import static dev.granary.server.docker.DockerFixtures.ALL_MANIFESTS;
import static dev.granary.server.docker.DockerFixtures.OCI_MANIFEST;
import static dev.granary.server.docker.DockerFixtures.config;
import static dev.granary.server.docker.DockerFixtures.digest;
import static dev.granary.server.docker.DockerFixtures.layer;
import static dev.granary.server.docker.DockerFixtures.manifest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.format.docker.DockerUpstream;
import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * A registry laid out like Docker Hub: the registry, its token service (the realm of the {@code WWW-Authenticate}
 * challenge) and the CDN its blob downloads redirect to are three origins. The token service is the one other host
 * the registry protocol sends the proxy's credentials to; the registry sees only the bearer token, the CDN neither.
 */
class DockerUpstreamCredentialsIntegrationTest extends IntegrationTest {

    private static final WireMockServer registry = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer auth = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer cdn = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer otherRegistry = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final String TOKEN = "Bearer tok-private";

    private final byte[] configBlob = config("private-app");
    private final byte[] layerBlob = layer("private-app");
    private final byte[] image = manifest(OCI_MANIFEST, configBlob, layerBlob);

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private DockerUpstream dockerUpstream;

    @BeforeAll
    static void start() {
        registry.start();
        auth.start();
        cdn.start();
        otherRegistry.start();
    }

    @AfterAll
    static void stop() {
        registry.stop();
        auth.stop();
        cdn.stop();
        otherRegistry.stop();
    }

    @BeforeEach
    void stubServers() {
        registry.resetAll();
        auth.resetAll();
        cdn.resetAll();
        otherRegistry.resetAll();
        String challenge = "Bearer realm=\"" + auth.baseUrl() + "/token\",service=\"private-registry\"";
        registry.stubFor(WireMock.any(WireMock.anyUrl()).atPriority(10)
                .willReturn(WireMock.aResponse().withStatus(401).withHeader("WWW-Authenticate", challenge)));
        registry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/acme/app/manifests/1.0")).atPriority(1)
                .withHeader("Authorization", WireMock.equalTo(TOKEN))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", OCI_MANIFEST)
                        .withHeader("Docker-Content-Digest", digest(image)).withBody(image)));
        registry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/acme/app/blobs/" + digest(layerBlob))).atPriority(1)
                .withHeader("Authorization", WireMock.equalTo(TOKEN))
                .willReturn(WireMock.aResponse().withStatus(307)
                        .withHeader("Location", cdn.baseUrl() + "/blobs/" + digest(layerBlob) + "?signature=abc")));
        // the token service hands out a token to anyone, and a different one to the proxy's account
        auth.stubFor(WireMock.get(WireMock.urlPathEqualTo("/token")).atPriority(5)
                .willReturn(WireMock.okJson("{\"token\":\"tok-anonymous\",\"expires_in\":300}")));
        auth.stubFor(WireMock.get(WireMock.urlPathEqualTo("/token")).atPriority(1).withBasicAuth(USER, PASSWORD)
                .withQueryParam("scope", WireMock.equalTo("repository:acme/app:pull"))
                .willReturn(WireMock.okJson("{\"token\":\"tok-private\",\"expires_in\":300}")));
        cdn.stubFor(WireMock.get(WireMock.urlPathEqualTo("/blobs/" + digest(layerBlob)))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(layerBlob)));
    }

    private Repository createProxy(String name, boolean credentials) {
        Repository r = proxyWithCredentials(name, "docker", registry.baseUrl(), null);
        if (!credentials) {
            r.setRemoteUsername(null);
            r.setRemotePassword(null);
        }
        Repository created = repositories.create(r);
        proxyService.invalidate(created);
        dockerUpstream.forget(created);
        return created;
    }

    @Test
    void theTokenRealmGetsTheCredentialsTheRegistryTheTokenAndTheCdnNothing() throws Exception {
        createProxy("docker-private", true);
        mvc.perform(get("/repository/docker-private/v2/acme/app/manifests/1.0").accept(ALL_MANIFESTS)).andExpect(status().isOk());
        assertThat(mvc.perform(get("/repository/docker-private/v2/acme/app/blobs/" + digest(layerBlob))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(layerBlob);

        // the realm the registry named receives the credentials, deliberately
        auth.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/token"))));
        // the registry: an anonymous probe, then only the bearer token
        registry.verify(1, withoutCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/"))));
        registry.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/acme/app/manifests/1.0"))
                .withHeader("Authorization", WireMock.equalTo(TOKEN)));
        registry.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/acme/app/blobs/" + digest(layerBlob)))
                .withHeader("Authorization", WireMock.equalTo(TOKEN)));
        assertThat(registry.findAll(WireMock.anyRequestedFor(WireMock.anyUrl())
                .withHeader("Authorization", WireMock.matching("Basic .*")))).isEmpty();
        // the CDN the blob redirect points at gets neither the credentials nor the registry's token
        cdn.verify(1, withoutCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/blobs/" + digest(layerBlob)))));
        assertThat(authorisedRequests(cdn)).isZero();
    }

    @Test
    void withoutCredentialsTheRealmIsAskedAnonymously() throws Exception {
        createProxy("docker-anonymous", false);
        registry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/acme/app/manifests/1.0")).atPriority(1)
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-anonymous"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", OCI_MANIFEST).withBody(image)));
        mvc.perform(get("/repository/docker-anonymous/v2/acme/app/manifests/1.0").accept(ALL_MANIFESTS)).andExpect(status().isOk());
        auth.verify(1, withoutCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/token"))));
        assertThat(authorisedRequests(auth)).isZero();
    }

    @Test
    void aTokenIsNeverSentToTheRegistryTheRepositoryIsPointedAtNext() throws Exception {
        createProxy("docker-moved", true);
        mvc.perform(get("/repository/docker-moved/v2/acme/app/manifests/1.0").accept(ALL_MANIFESTS)).andExpect(status().isOk());
        registry.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/acme/app/manifests/1.0"))
                .withHeader("Authorization", WireMock.equalTo(TOKEN)));

        // another registry with a token service of its own; the first registry's token is still valid
        String challenge = "Bearer realm=\"" + otherRegistry.baseUrl() + "/token\",service=\"other\"";
        otherRegistry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/")).willReturn(WireMock.aResponse()
                .withStatus(401).withHeader("WWW-Authenticate", challenge)));
        otherRegistry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/token"))
                .willReturn(WireMock.okJson("{\"token\":\"tok-other\",\"expires_in\":300}")));
        otherRegistry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/acme/app/manifests/2.0"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", OCI_MANIFEST).withBody(image)));
        mvc.perform(put("/api/v1/repositories/docker-moved").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"docker-moved\",\"format\":\"docker\",\"type\":\"PROXY\",\"remoteUrl\":\""
                                + otherRegistry.baseUrl() + "\",\"remoteUsername\":\"" + USER + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/repository/docker-moved/v2/acme/app/manifests/2.0").accept(ALL_MANIFESTS)).andExpect(status().isOk());
        otherRegistry.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/acme/app/manifests/2.0"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-other")));
        assertThat(otherRegistry.findAll(WireMock.anyRequestedFor(WireMock.anyUrl())
                .withHeader("Authorization", WireMock.equalTo(TOKEN)))).isEmpty();
    }

    @Test
    void aRealmNamedByAHostTheRegistryRedirectedToGetsNoCredentials() throws Exception {
        createProxy("docker-redirected", true);
        // the registry's /v2/ sends the probe to another origin, whose challenge names a token service of its own
        registry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/")).atPriority(1).willReturn(WireMock.aResponse()
                .withStatus(302).withHeader("Location", otherRegistry.baseUrl() + "/v2/")));
        otherRegistry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/")).willReturn(WireMock.aResponse().withStatus(401)
                .withHeader("WWW-Authenticate", "Bearer realm=\"" + otherRegistry.baseUrl() + "/token\",service=\"other\"")));
        otherRegistry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/token"))
                .willReturn(WireMock.okJson("{\"token\":\"tok-other\",\"expires_in\":300}")));
        registry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/acme/app/manifests/3.0")).atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", OCI_MANIFEST).withBody(image)));

        mvc.perform(get("/repository/docker-redirected/v2/acme/app/manifests/3.0").accept(ALL_MANIFESTS))
                .andExpect(status().isOk());

        otherRegistry.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/token")));
        assertThat(authorisedRequests(otherRegistry)).isZero();
        // the registry's own origin still gets the credentials, as for any registry without a token service
        registry.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/acme/app/manifests/3.0"))));
    }
}
