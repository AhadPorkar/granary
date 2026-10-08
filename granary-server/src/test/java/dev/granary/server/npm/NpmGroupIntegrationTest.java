package dev.granary.server.npm;

import static dev.granary.server.npm.NpmFixtures.object;
import static dev.granary.server.npm.NpmFixtures.publishBody;
import static dev.granary.server.npm.NpmFixtures.repositoryUrl;
import static dev.granary.server.npm.NpmFixtures.str;
import static dev.granary.server.npm.NpmFixtures.tarball;
import static dev.granary.server.npm.NpmFixtures.upstreamPackument;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.node.ObjectNode;

/** One URL for everything: a group of a hosted repository and a proxy of a fake npmjs.org. */
class NpmGroupIntegrationTest extends IntegrationTest {

    private static final String GROUP = "npm-all";
    private static final String URL = "/repository/" + GROUP;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    @BeforeAll
    static void startUpstream() {
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void createRepositories() {
        upstream.resetAll();
        hosted("npm-hosted", "npm");
        proxyService.invalidate(proxy("npm-proxy", "npm", upstream.baseUrl() + "/"));
        group(GROUP, "npm", "npm-hosted", "npm-proxy");
    }

    @Test
    void mergesHostedAndProxiedVersionsOfTheSamePackage() throws Exception {
        publishToHosted("demo", "1.0.0");
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/demo"))
                .willReturn(WireMock.okJson(upstreamPackument(upstream.baseUrl(), "demo", "1.5.0", "2.0.0"))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/demo/-/demo-2.0.0.tgz"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody(tarball("upstream-2.0.0"))));

        ObjectNode doc = object(mvc.perform(get(URL + "/demo")).andExpect(status().isOk()).andReturn());
        assertThat(doc.path("versions").propertyNames()).containsExactlyInAnyOrder("1.0.0", "1.5.0", "2.0.0");
        assertThat(str(doc, "dist-tags", "latest")).isEqualTo("2.0.0");
        assertThat(str(doc, "description")).isEqualTo("description of demo@1.0.0"); // first member wins
        assertThat(str(doc, "versions", "1.0.0", "dist", "tarball")).isEqualTo(repositoryUrl(GROUP) + "/demo/-/demo-1.0.0.tgz");
        assertThat(str(doc, "versions", "2.0.0", "dist", "tarball")).isEqualTo(repositoryUrl(GROUP) + "/demo/-/demo-2.0.0.tgz");
        assertThat(str(doc, "time", "1.0.0")).isNotNull();
        assertThat(str(doc, "time", "2.0.0")).isEqualTo("2021-01-01T00:00:00.000Z");

        assertThat(mvc.perform(get(URL + "/demo/-/demo-1.0.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tarball("demo-1.0.0"));
        assertThat(mvc.perform(get(URL + "/demo/-/demo-2.0.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tarball("upstream-2.0.0"));
        mvc.perform(get(URL + "/demo/latest")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("2.0.0"));
        mvc.perform(get(URL + "/-/package/demo/dist-tags")).andExpect(status().isOk()).andExpect(jsonPath("$.latest").value("2.0.0"));
    }

    @Test
    void latestIsTheHighestWhicheverMemberHasIt() throws Exception {
        publishToHosted("demo", "3.0.0");
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/demo"))
                .willReturn(WireMock.okJson(upstreamPackument(upstream.baseUrl(), "demo", "2.0.0"))));
        mvc.perform(get(URL + "/demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$['dist-tags'].latest").value("3.0.0"));
    }

    @Test
    void packagesOnlyOneMemberHasStillResolve() throws Exception {
        publishToHosted("internal", "1.0.0");
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/internal")).willReturn(WireMock.notFound()));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/lodash"))
                .willReturn(WireMock.okJson(upstreamPackument(upstream.baseUrl(), "lodash", "4.17.21"))));

        mvc.perform(get(URL + "/internal")).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions['1.0.0'].dist.tarball").value(repositoryUrl(GROUP) + "/internal/-/internal-1.0.0.tgz"));
        mvc.perform(get(URL + "/lodash")).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions['4.17.21'].dist.tarball").value(repositoryUrl(GROUP) + "/lodash/-/lodash-4.17.21.tgz"));
    }

    @Test
    void unknownPackageIs404WhenNoMemberHasIt() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/nope")).willReturn(WireMock.notFound()));
        mvc.perform(get(URL + "/nope")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("package not found: nope"));
        mvc.perform(get(URL + "/nope/-/nope-1.0.0.tgz")).andExpect(status().isNotFound());
    }

    @Test
    void scopedPackagesThroughTheGroup() throws Exception {
        publishToHosted("@acme/ui", "1.0.0");
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/@acme/ui")).willReturn(WireMock.notFound()));
        ObjectNode doc = object(mvc.perform(get(URI.create(URL + "/@acme%2Fui"))).andExpect(status().isOk()).andReturn());
        assertThat(str(doc, "versions", "1.0.0", "dist", "tarball")).isEqualTo(repositoryUrl(GROUP) + "/@acme/ui/-/ui-1.0.0.tgz");
        assertThat(mvc.perform(get(URL + "/@acme/ui/-/ui-1.0.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tarball("@acme/ui-1.0.0"));
    }

    @Test
    void writesAreRefused() throws Exception {
        mvc.perform(put(URL + "/demo").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("demo", "1.0.0", tarball("x"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("group repository")));
        mvc.perform(delete(URL + "/demo/-rev/1-a").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void searchUnionsTheMembers() throws Exception {
        publishToHosted("lodash", "9.0.0");
        publishToHosted("demo", "1.0.0");
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/-/v1/search"))
                .willReturn(WireMock.okJson("{\"objects\":[{\"package\":{\"name\":\"lodash\",\"version\":\"4.17.21\"}},"
                        + "{\"package\":{\"name\":\"lodash-es\",\"version\":\"4.17.21\"}}],\"total\":2}")));

        ObjectNode json = object(mvc.perform(get(URL + "/-/v1/search").param("text", "lo")).andExpect(status().isOk()).andReturn());
        assertThat(json.path("objects")).hasSize(2);
        assertThat(str(json.path("objects").get(0).path("package"), "name")).isEqualTo("lodash");
        assertThat(str(json.path("objects").get(0).path("package"), "version")).isEqualTo("9.0.0");
        assertThat(str(json.path("objects").get(1).path("package"), "name")).isEqualTo("lodash-es");
    }

    private void publishToHosted(String name, String version) throws Exception {
        mvc.perform(put(URI.create("/repository/npm-hosted/" + name.replace("/", "%2F"))).header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody(name, version, tarball(name + "-" + version))))
                .andExpect(status().isCreated());
    }
}
