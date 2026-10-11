package dev.granary.server.nuget;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static dev.granary.server.UpstreamCredentialFixtures.PASSWORD;
import static dev.granary.server.UpstreamCredentialFixtures.USER;
import static dev.granary.server.UpstreamCredentialFixtures.authorisedRequests;
import static dev.granary.server.UpstreamCredentialFixtures.hostOf;
import static dev.granary.server.UpstreamCredentialFixtures.proxyWithCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withoutCredentials;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A private feed whose service index names resources on another host (nuget.org's search lives on
 * azuresearch-*.nuget.org, packages on a CDN): the feed's own resources get the proxy's credentials, the other host
 * does not, unless it is listed in {@code upstream.credentialHosts}.
 */
class NugetUpstreamCredentialsIntegrationTest extends IntegrationTest {

    private static final String ID = "acme.remote";
    private static final byte[] NUPKG = NugetTestPackages.nupkg("Acme.Remote", "2.0.0", "remote package");
    private static final WireMockServer feed = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer foreign = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    @BeforeAll
    static void start() {
        feed.start();
        foreign.start();
    }

    @AfterAll
    static void stop() {
        feed.stop();
        foreign.stop();
    }

    @BeforeEach
    void stubServers() {
        feed.resetAll();
        foreign.resetAll();
        String serviceIndex = """
                {"version": "3.0.0", "resources": [
                  {"@id": "%2$s/v3-flatcontainer/", "@type": "PackageBaseAddress/3.0.0"},
                  {"@id": "%1$s/v3/registration5-gz-semver2/", "@type": "RegistrationsBaseUrl/3.6.0"},
                  {"@id": "%2$s/query", "@type": ["SearchQueryService", "SearchQueryService/3.5.0"]}
                ]}""".formatted(feed.baseUrl(), foreign.baseUrl());
        feed.stubFor(WireMock.any(WireMock.anyUrl()).atPriority(10).willReturn(WireMock.unauthorized()));
        feed.stubFor(get(urlEqualTo("/v3/index.json")).atPriority(1).withBasicAuth(USER, PASSWORD).willReturn(okJson(serviceIndex)));
        feed.stubFor(get(urlEqualTo("/v3/registration5-gz-semver2/" + ID + "/index.json")).atPriority(1).withBasicAuth(USER, PASSWORD)
                .willReturn(okJson(NugetTestPackages.registrationIndexWithRemotePage(feed.baseUrl(), ID, "2.0.0", "2.0.0"))));
        feed.stubFor(get(urlEqualTo("/v3/registration5-gz-semver2/" + ID + "/page/2.0.0/2.0.0.json")).atPriority(1)
                .withBasicAuth(USER, PASSWORD)
                .willReturn(okJson(NugetTestPackages.registrationPage(feed.baseUrl(), ID, "2.0.0"))));
        foreign.stubFor(get(urlEqualTo("/v3-flatcontainer/" + ID + "/index.json")).willReturn(okJson(NugetTestPackages.flatIndex("2.0.0"))));
        foreign.stubFor(get(urlEqualTo("/v3-flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.nupkg"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(NUPKG)));
        foreign.stubFor(get(urlPathEqualTo("/query")).willReturn(okJson(NugetTestPackages.searchResult(feed.baseUrl(), "Acme.Remote", "2.0.0"))));
    }

    private void createProxy(String name, String trustedHosts) {
        proxyService.invalidate(repositories.create(proxyWithCredentials(name, "nuget", feed.baseUrl() + "/v3/index.json", trustedHosts)));
    }

    private void restore(String repo) throws Exception {
        String url = "/repository/" + repo + "/";
        mvc.perform(MockMvcRequestBuilders.get(url + "v3/registration/" + ID + "/index.json")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(url + "v3/flatcontainer/" + ID + "/index.json")).andExpect(status().isOk());
        assertThat(mvc.perform(MockMvcRequestBuilders.get(url + "v3/flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.nupkg"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).isEqualTo(NUPKG);
        mvc.perform(MockMvcRequestBuilders.get(url + "v3/query").param("q", "acme")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("Acme.Remote"));
    }

    @Test
    void resourcesOnAnotherHostNeverSeeTheFeedsCredentials() throws Exception {
        createProxy("nuget-private", null);
        restore("nuget-private");

        feed.verify(1, withCredentials(getRequestedFor(urlEqualTo("/v3/index.json"))));
        feed.verify(1, withCredentials(getRequestedFor(urlEqualTo("/v3/registration5-gz-semver2/" + ID + "/index.json"))));
        foreign.verify(1, withoutCredentials(getRequestedFor(urlEqualTo("/v3-flatcontainer/" + ID + "/index.json"))));
        foreign.verify(1, withoutCredentials(getRequestedFor(urlEqualTo("/v3-flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.nupkg"))));
        foreign.verify(1, withoutCredentials(getRequestedFor(urlPathEqualTo("/query"))));
        assertThat(authorisedRequests(foreign)).isZero();
    }

    @Test
    void aResourceHostListedInTheSettingReceivesThem() throws Exception {
        createProxy("nuget-trusting", hostOf(foreign));
        restore("nuget-trusting");

        foreign.verify(1, withCredentials(getRequestedFor(urlEqualTo("/v3-flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.nupkg"))));
        foreign.verify(1, withCredentials(getRequestedFor(urlPathEqualTo("/query"))));
    }
}
