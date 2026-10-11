package dev.granary.server.npm;

import static dev.granary.server.UpstreamCredentialFixtures.PASSWORD;
import static dev.granary.server.UpstreamCredentialFixtures.USER;
import static dev.granary.server.UpstreamCredentialFixtures.authorisedRequests;
import static dev.granary.server.UpstreamCredentialFixtures.hostOf;
import static dev.granary.server.UpstreamCredentialFixtures.proxyWithCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withCredentials;
import static dev.granary.server.npm.NpmFixtures.tarball;
import static dev.granary.server.npm.NpmFixtures.upstreamPackument;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A private registry whose packument names tarballs on another origin of the same host (another port here; the
 * {@code http://} links of a registry behind a TLS proxy in real life). Those URLs would be asked without the
 * proxy's credentials, so the proxy asks the registry itself under the conventional path instead, unless the
 * other origin is listed in {@code upstream.credentialHosts}.
 */
class NpmUpstreamCredentialsIntegrationTest extends IntegrationTest {

    private static final WireMockServer registry = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer elsewhere = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    @BeforeAll
    static void start() {
        registry.start();
        elsewhere.start();
    }

    @AfterAll
    static void stop() {
        registry.stop();
        elsewhere.stop();
    }

    @BeforeEach
    void stubServers() {
        registry.resetAll();
        elsewhere.resetAll();
        // the packument points the tarball at the other origin, on the same host name
        String packument = upstreamPackument(registry.baseUrl(), "@acme/ui", "1.0.0")
                .replace(registry.baseUrl() + "/@acme/ui/-/", elsewhere.baseUrl() + "/@acme/ui/-/");
        registry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/@acme/ui")).withBasicAuth(USER, PASSWORD)
                .willReturn(WireMock.okJson(packument)));
        registry.stubFor(WireMock.get(WireMock.urlPathEqualTo("/@acme/ui/-/ui-1.0.0.tgz")).withBasicAuth(USER, PASSWORD)
                .willReturn(WireMock.aResponse().withStatus(200).withBody(tarball("registry"))));
        elsewhere.stubFor(WireMock.get(WireMock.urlPathEqualTo("/@acme/ui/-/ui-1.0.0.tgz")).atPriority(5)
                .willReturn(WireMock.aResponse().withStatus(401)));
        elsewhere.stubFor(WireMock.get(WireMock.urlPathEqualTo("/@acme/ui/-/ui-1.0.0.tgz")).atPriority(1)
                .withBasicAuth(USER, PASSWORD)
                .willReturn(WireMock.aResponse().withStatus(200).withBody(tarball("elsewhere"))));
    }

    private void createProxy(String name, String trustedHosts) {
        Repository created = repositories.create(proxyWithCredentials(name, "npm", registry.baseUrl() + "/", trustedHosts));
        proxyService.invalidate(created);
    }

    @Test
    void aTarballOnAnotherOriginIsAskedFromTheRegistryWithTheCredentials() throws Exception {
        createProxy("npm-private", null);
        mvc.perform(get("/repository/npm-private/@acme/ui")).andExpect(status().isOk());
        assertThat(mvc.perform(get("/repository/npm-private/@acme/ui/-/ui-1.0.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tarball("registry"));

        registry.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/@acme/ui/-/ui-1.0.0.tgz"))));
        elsewhere.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void aListedOriginIsFollowedAndReceivesThem() throws Exception {
        createProxy("npm-listed", hostOf(elsewhere));
        mvc.perform(get("/repository/npm-listed/@acme/ui")).andExpect(status().isOk());
        assertThat(mvc.perform(get("/repository/npm-listed/@acme/ui/-/ui-1.0.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tarball("elsewhere"));

        elsewhere.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/@acme/ui/-/ui-1.0.0.tgz"))));
        registry.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/@acme/ui/-/ui-1.0.0.tgz")));
        assertThat(authorisedRequests(elsewhere)).isEqualTo(1);
    }
}
