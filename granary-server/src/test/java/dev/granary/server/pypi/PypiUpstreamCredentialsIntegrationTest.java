package dev.granary.server.pypi;

import static dev.granary.server.UpstreamCredentialFixtures.PASSWORD;
import static dev.granary.server.UpstreamCredentialFixtures.USER;
import static dev.granary.server.UpstreamCredentialFixtures.authorisedRequests;
import static dev.granary.server.UpstreamCredentialFixtures.hostOf;
import static dev.granary.server.UpstreamCredentialFixtures.proxyWithCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withoutCredentials;
import static dev.granary.server.pypi.PypiFixtures.JSON_V1;
import static dev.granary.server.pypi.PypiFixtures.PIP_ACCEPT;
import static dev.granary.server.pypi.PypiFixtures.sdist;
import static dev.granary.server.pypi.PypiFixtures.upstreamJson;
import static dev.granary.server.pypi.PypiFixtures.wheel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import dev.granary.server.pypi.PypiFixtures.UpstreamFile;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A private index whose files live on another host ({@code pypi.filesUrl}, as files.pythonhosted.org does for
 * pypi.org) next to a file on the index's own host: the index gets the proxy's credentials, the files host does
 * not, unless it is listed in {@code upstream.credentialHosts}.
 */
class PypiUpstreamCredentialsIntegrationTest extends IntegrationTest {

    private static final WireMockServer index = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer files = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final String SDIST_PATH = "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz";
    private static final String WHEEL_PATH = "/own/six-1.16.0-py2.py3-none-any.whl";

    @Autowired
    private ProxyService proxyService;

    private UpstreamFile sdist;
    private UpstreamFile ownWheel;

    @BeforeAll
    static void start() {
        index.start();
        files.start();
    }

    @AfterAll
    static void stop() {
        index.stop();
        files.stop();
    }

    @BeforeEach
    void stubServers() {
        index.resetAll();
        files.resetAll();
        sdist = new UpstreamFile("six-1.16.0.tar.gz", files.baseUrl() + SDIST_PATH, sdist("six", "1.16.0"));
        ownWheel = new UpstreamFile("six-1.16.0-py2.py3-none-any.whl", index.baseUrl() + WHEEL_PATH, wheel("six", "1.16.0"));
        index.stubFor(WireMock.any(WireMock.anyUrl()).atPriority(10).willReturn(WireMock.unauthorized()));
        index.stubFor(WireMock.get(WireMock.urlPathEqualTo("/simple/six/")).atPriority(1).withBasicAuth(USER, PASSWORD)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", JSON_V1)
                        .withBody(upstreamJson("six", List.of(sdist, ownWheel)))));
        index.stubFor(WireMock.get(WireMock.urlPathEqualTo(WHEEL_PATH)).atPriority(1).withBasicAuth(USER, PASSWORD)
                .willReturn(WireMock.aResponse().withStatus(200).withBody(ownWheel.bytes())));
        files.stubFor(WireMock.get(WireMock.urlPathEqualTo(SDIST_PATH))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream")
                        .withBody(sdist.bytes())));
    }

    private void createProxy(String name, String trustedHosts) {
        Repository r = proxyWithCredentials(name, "pypi", index.baseUrl() + "/", trustedHosts);
        r.getFormatSettings().put("pypi.filesUrl", files.baseUrl() + "/");
        proxyService.invalidate(repositories.create(r));
    }

    private byte[] download(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private void installSix(String repo) throws Exception {
        String url = "/repository/" + repo;
        mvc.perform(get(url + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk());
        assertThat(download(url + SDIST_PATH)).isEqualTo(sdist.bytes());
        assertThat(download(url + "/packages/-/six/six-1.16.0-py2.py3-none-any.whl")).isEqualTo(ownWheel.bytes());
    }

    @Test
    void theFilesHostNeverSeesTheIndexsCredentials() throws Exception {
        createProxy("pypi-private", null);
        installSix("pypi-private");

        index.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/simple/six/"))));
        index.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo(WHEEL_PATH))));
        files.verify(1, withoutCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo(SDIST_PATH))));
        assertThat(authorisedRequests(files)).isZero();
    }

    @Test
    void aFilesHostListedInTheSettingReceivesThem() throws Exception {
        createProxy("pypi-trusting", hostOf(files));
        installSix("pypi-trusting");

        files.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlPathEqualTo(SDIST_PATH))));
    }
}
