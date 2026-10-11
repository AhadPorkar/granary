package dev.granary.server.helm;

import static dev.granary.server.UpstreamCredentialFixtures.authorisedRequests;
import static dev.granary.server.UpstreamCredentialFixtures.hostOf;
import static dev.granary.server.UpstreamCredentialFixtures.proxyWithCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withoutCredentials;
import static dev.granary.server.helm.HelmFixtures.chart;
import static dev.granary.server.helm.HelmFixtures.index;
import static dev.granary.server.helm.HelmFixtures.upstreamChart;
import static dev.granary.server.helm.HelmFixtures.upstreamIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.BasicCredentials;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import dev.granary.server.UpstreamCredentialFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A private chart repository whose index points at chart archives on another host (GitHub releases) and redirects
 * some downloads there (release assets live on objects.githubusercontent.com): the chart repository gets the
 * proxy's credentials, the other host never does unless it is listed in {@code upstream.credentialHosts}.
 */
class HelmUpstreamCredentialsIntegrationTest extends IntegrationTest {

    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer foreign = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final byte[] LOCAL = chart("local", "1.0.0");
    private static final byte[] RELEASED = chart("released", "1.0.0");
    private static final byte[] MOVED = chart("moved", "1.0.0");

    @Autowired
    private ProxyService proxyService;

    @BeforeAll
    static void start() {
        upstream.start();
        foreign.start();
    }

    @AfterAll
    static void stop() {
        upstream.stop();
        foreign.stop();
    }

    @BeforeEach
    void stubServers() {
        upstream.resetAll();
        foreign.resetAll();
        // the private chart repository refuses everything without its credentials
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).atPriority(10).willReturn(WireMock.unauthorized()));
        upstreamHas("/stable/index.yaml", upstreamIndex(
                upstreamChart("local", "1.0.0", "local-1.0.0.tgz", LOCAL),
                upstreamChart("released", "1.0.0", foreign.baseUrl() + "/releases/released-1.0.0.tgz", RELEASED),
                upstreamChart("moved", "1.0.0", upstream.baseUrl() + "/stable/download/moved-1.0.0.tgz", MOVED)).getBytes());
        upstreamHas("/stable/local-1.0.0.tgz", LOCAL);
        upstream.stubFor(WireMock.get("/stable/download/moved-1.0.0.tgz").atPriority(1)
                .withBasicAuth(UpstreamCredentialFixtures.USER, UpstreamCredentialFixtures.PASSWORD)
                .willReturn(WireMock.aResponse().withStatus(302).withHeader("Location", foreign.baseUrl() + "/objects/moved-1.0.0.tgz?sig=1")));
        foreignHas("/releases/released-1.0.0.tgz", RELEASED);
        foreignHas("/objects/moved-1.0.0.tgz", MOVED);
    }

    private static void upstreamHas(String path, byte[] body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).atPriority(1)
                .withBasicAuth(UpstreamCredentialFixtures.USER, UpstreamCredentialFixtures.PASSWORD)
                .willReturn(WireMock.aResponse().withStatus(200).withBody(body)));
    }

    private static void foreignHas(String path, byte[] body) {
        foreign.stubFor(WireMock.get(WireMock.urlPathEqualTo(path))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/gzip").withBody(body)));
    }

    private void createProxy(String name, String trustedHosts) {
        proxyService.invalidate(repositories.create(proxyWithCredentials(name, "helm", upstream.baseUrl() + "/stable", trustedHosts)));
    }

    private byte[] download(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void chartArchivesOnOtherHostsAreDownloadedWithoutTheCredentials() throws Exception {
        createProxy("helm-private", null);
        mvc.perform(index("/repository/helm-private")).andExpect(status().isOk());
        assertThat(download("/repository/helm-private/charts/local-1.0.0.tgz")).isEqualTo(LOCAL);
        assertThat(download("/repository/helm-private/charts/released-1.0.0.tgz")).isEqualTo(RELEASED);
        assertThat(download("/repository/helm-private/charts/moved-1.0.0.tgz")).isEqualTo(MOVED);

        upstream.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/index.yaml"))));
        upstream.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/local-1.0.0.tgz"))));
        upstream.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/download/moved-1.0.0.tgz"))));
        foreign.verify(1, withoutCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/releases/released-1.0.0.tgz"))));
        foreign.verify(1, withoutCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/objects/moved-1.0.0.tgz"))));
        assertThat(authorisedRequests(foreign)).isZero();
    }

    @Test
    void aHostListedInTheSettingReceivesThem() throws Exception {
        createProxy("helm-trusting", hostOf(foreign));
        mvc.perform(index("/repository/helm-trusting")).andExpect(status().isOk());
        assertThat(download("/repository/helm-trusting/charts/released-1.0.0.tgz")).isEqualTo(RELEASED);
        assertThat(download("/repository/helm-trusting/charts/moved-1.0.0.tgz")).isEqualTo(MOVED);

        foreign.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/releases/released-1.0.0.tgz"))
                .withBasicAuth(new BasicCredentials(UpstreamCredentialFixtures.USER, UpstreamCredentialFixtures.PASSWORD)));
        foreign.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/objects/moved-1.0.0.tgz"))));
    }
}
