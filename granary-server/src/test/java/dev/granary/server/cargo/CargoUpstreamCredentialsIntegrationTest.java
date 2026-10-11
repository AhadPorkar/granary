package dev.granary.server.cargo;

import static dev.granary.server.UpstreamCredentialFixtures.PASSWORD;
import static dev.granary.server.UpstreamCredentialFixtures.USER;
import static dev.granary.server.UpstreamCredentialFixtures.authorisedRequests;
import static dev.granary.server.UpstreamCredentialFixtures.hostOf;
import static dev.granary.server.UpstreamCredentialFixtures.proxyWithCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withoutCredentials;
import static dev.granary.server.cargo.CargoFixtures.api;
import static dev.granary.server.cargo.CargoFixtures.crate;
import static dev.granary.server.cargo.CargoFixtures.download;
import static dev.granary.server.cargo.CargoFixtures.index;
import static dev.granary.server.cargo.CargoFixtures.indexFile;
import static dev.granary.server.cargo.CargoFixtures.indexLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * A private sparse index whose crate files ({@code cargo.downloadUrl}, the {@code dl} of crates.io's
 * {@code config.json}) and web API ({@code cargo.apiUrl}) live on another host, as static.crates.io and crates.io do
 * for index.crates.io: only the index gets the proxy's credentials, unless the other host is listed in
 * {@code upstream.credentialHosts}.
 */
class CargoUpstreamCredentialsIntegrationTest extends IntegrationTest {

    private static final WireMockServer index = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer foreign = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final byte[] ITOA = crate("itoa", "1.0.18");

    @Autowired
    private ProxyService proxyService;

    @BeforeAll
    static void start() {
        index.start();
        foreign.start();
    }

    @AfterAll
    static void stop() {
        index.stop();
        foreign.stop();
    }

    @BeforeEach
    void stubServers() {
        index.resetAll();
        foreign.resetAll();
        index.stubFor(WireMock.any(WireMock.anyUrl()).atPriority(10).willReturn(WireMock.unauthorized()));
        index.stubFor(WireMock.get(WireMock.urlPathEqualTo("/index/it/oa/itoa")).atPriority(1).withBasicAuth(USER, PASSWORD)
                .willReturn(WireMock.aResponse().withStatus(200).withBody(indexFile(indexLine("itoa", "1.0.18", ITOA, false)))));
        foreign.stubFor(WireMock.get(WireMock.urlPathEqualTo("/crates/itoa/itoa-1.0.18.crate"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/gzip").withBody(ITOA)));
        foreign.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/crates")).willReturn(WireMock.okJson(
                "{\"crates\":[{\"name\":\"itoa\",\"max_version\":\"1.0.18\",\"description\":\"Fast integer printing\"}],"
                        + "\"meta\":{\"total\":1}}")));
    }

    private void createProxy(String name, String trustedHosts) {
        Repository r = proxyWithCredentials(name, "cargo", index.baseUrl() + "/index/", trustedHosts);
        r.getFormatSettings().put("cargo.downloadUrl", foreign.baseUrl() + "/crates/{crate}/{crate}-{version}.crate");
        r.getFormatSettings().put("cargo.apiUrl", foreign.baseUrl());
        proxyService.invalidate(repositories.create(r));
    }

    private void useEverything(String repo) throws Exception {
        String url = "/repository/" + repo;
        mvc.perform(index(url + "/index/it/oa/itoa")).andExpect(status().isOk());
        assertThat(mvc.perform(download(url + "/api/v1/crates/itoa/1.0.18/download")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(ITOA);
        mvc.perform(api(get(url + "/api/v1/crates").param("q", "itoa"), null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.crates[0].name").value("itoa"));
    }

    @Test
    void crateFilesAndTheWebApiNeverSeeTheIndexsCredentials() throws Exception {
        createProxy("cargo-private", null);
        useEverything("cargo-private");

        index.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/index/it/oa/itoa"))));
        foreign.verify(1, withoutCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/crates/itoa/itoa-1.0.18.crate"))));
        foreign.verify(1, withoutCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/v1/crates"))));
        assertThat(authorisedRequests(foreign)).isZero();
    }

    @Test
    void aDownloadHostListedInTheSettingReceivesThem() throws Exception {
        createProxy("cargo-trusting", hostOf(foreign));
        useEverything("cargo-trusting");

        foreign.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/crates/itoa/itoa-1.0.18.crate"))));
        foreign.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/v1/crates"))));
    }
}
