package dev.granary.server.helm;

import static dev.granary.server.helm.HelmFixtures.chart;
import static dev.granary.server.helm.HelmFixtures.index;
import static dev.granary.server.helm.HelmFixtures.repositoryUrl;
import static dev.granary.server.helm.HelmFixtures.sha256;
import static dev.granary.server.helm.HelmFixtures.upload;
import static dev.granary.server.helm.HelmFixtures.upstreamChart;
import static dev.granary.server.helm.HelmFixtures.upstreamIndex;
import static dev.granary.server.helm.HelmFixtures.url;
import static dev.granary.server.helm.HelmFixtures.versionNumbers;
import static dev.granary.server.helm.HelmFixtures.versions;
import static dev.granary.server.helm.HelmFixtures.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** One chart repository URL for everything: a group of a hosted repository and a proxy of a fake public one. */
class HelmGroupIntegrationTest extends IntegrationTest {

    private static final String GROUP = "helm-group";
    private static final String URL = "/repository/" + GROUP;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static final byte[] HOSTED_DEMO = chart("demo", "1.0.0", "hosted copy");
    private static final byte[] UPSTREAM_DEMO_1 = chart("demo", "1.0.0", "upstream copy");
    private static final byte[] UPSTREAM_DEMO_2 = chart("demo", "2.0.0", "upstream copy");
    private static final byte[] NGINX = chart("nginx", "15.0.0");
    /** A different chart whose upstream file name collides with the hosted demo 1.0.0. */
    private static final byte[] CLASH = chart("clash", "0.1.0");

    @Autowired
    private ProxyService proxyService;

    private String auth;

    @BeforeAll
    static void startUpstream() {
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void createRepositories() throws Exception {
        upstream.resetAll();
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/charts/index.yaml")).willReturn(WireMock.aResponse()
                .withStatus(200).withBody(upstreamIndex(
                        upstreamChart("demo", "1.0.0", "demo-1.0.0.tgz", UPSTREAM_DEMO_1),
                        upstreamChart("demo", "2.0.0", "demo-2.0.0.tgz", UPSTREAM_DEMO_2),
                        upstreamChart("nginx", "15.0.0", "https://downloads.invalid/nginx-15.0.0.tgz", NGINX),
                        upstreamChart("clash", "0.1.0", "mirror/demo-1.0.0.tgz", CLASH)))));
        stubFile("/charts/demo-1.0.0.tgz", UPSTREAM_DEMO_1);
        stubFile("/charts/demo-2.0.0.tgz", UPSTREAM_DEMO_2);
        stubFile("/charts/mirror/demo-1.0.0.tgz", CLASH);

        hosted("helm-hosted", "helm");
        proxyService.invalidate(proxy("helm-proxy", "helm", upstream.baseUrl() + "/charts/"));
        group(GROUP, "helm", "helm-hosted", "helm-proxy");

        auth = basic(ADMIN, tokens.issue(users.require(ADMIN), "helm", null).secret());
        mvc.perform(upload("/repository/helm-hosted", auth, HOSTED_DEMO)).andExpect(status().isCreated());
    }

    private void stubFile(String path, byte[] body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse().withStatus(200).withBody(body)));
    }

    private Map<String, Object> groupIndex() throws Exception {
        return yaml(mvc.perform(index(URL)).andExpect(status().isOk()).andReturn());
    }

    private byte[] downloadOk(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void theIndexIsTheUnionOfTheMembersAndTheFirstMemberWinsAVersion() throws Exception {
        Map<String, Object> index = groupIndex();
        assertThat(versionNumbers(index, "demo")).containsExactly("2.0.0", "1.0.0");
        assertThat(versionNumbers(index, "nginx")).containsExactly("15.0.0");
        Map<String, Object> demo1 = versions(index, "demo").get(1);
        assertThat(demo1).containsEntry("description", "hosted copy").containsEntry("digest", sha256(HOSTED_DEMO));
        assertThat(url(demo1)).isEqualTo(repositoryUrl(GROUP) + "/charts/demo-1.0.0.tgz");
        assertThat(url(versions(index, "demo").get(0))).isEqualTo(repositoryUrl(GROUP) + "/charts/demo-2.0.0.tgz");
        assertThat(url(versions(index, "nginx").getFirst())).isEqualTo(repositoryUrl(GROUP) + "/charts/nginx-15.0.0.tgz");
        assertThat(index.get("serverInfo")).isEqualTo(Map.of("contextPath", "/repository/helm-group"));
        assertThat(index.toString()).doesNotContain(upstream.baseUrl()).doesNotContain("downloads.invalid");
    }

    @Test
    void filesComeFromTheMemberThatWonTheVersion() throws Exception {
        groupIndex();
        assertThat(downloadOk(URL + "/charts/demo-1.0.0.tgz")).isEqualTo(HOSTED_DEMO);
        assertThat(downloadOk(URL + "/charts/demo-2.0.0.tgz")).isEqualTo(UPSTREAM_DEMO_2);
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/charts/demo-1.0.0.tgz")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/charts/demo-2.0.0.tgz")));
        mvc.perform(get(URL + "/api/charts/demo/latest")).andExpect(jsonPath("$.version").value("2.0.0"));
        mvc.perform(get(URL + "/api/charts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo.length()").value(2))
                .andExpect(jsonPath("$.nginx[0].urls[0]").value(repositoryUrl(GROUP) + "/charts/nginx-15.0.0.tgz"));
    }

    @Test
    void aFileNameTwoMembersUseForDifferentChartsGetsItsOwnAddress() throws Exception {
        Map<String, Object> index = groupIndex();
        String clash = url(versions(index, "clash").getFirst());
        assertThat(clash).isEqualTo(repositoryUrl(GROUP) + "/charts/clash/0.1.0/demo-1.0.0.tgz");
        assertThat(downloadOk(clash.substring(HelmFixtures.SERVER.length()))).isEqualTo(CLASH);
        assertThat(downloadOk(URL + "/charts/demo-1.0.0.tgz")).isEqualTo(HOSTED_DEMO);
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/charts/mirror/demo-1.0.0.tgz")));
    }

    @Test
    void aBrokenMemberDoesNotTakeTheGroupDown() throws Exception {
        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));
        Map<String, Object> index = groupIndex();
        assertThat(versionNumbers(index, "demo")).containsExactly("1.0.0");
        assertThat(downloadOk(URL + "/charts/demo-1.0.0.tgz")).isEqualTo(HOSTED_DEMO);
        // what only the unreachable member could have is a temporary 503
        mvc.perform(get(URL + "/charts/demo-2.0.0.tgz"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));

        // an upstream that answers with something that is not an index is left out as well
        upstream.resetAll();
        upstream.stubFor(WireMock.get(WireMock.anyUrl()).willReturn(WireMock.ok("<html>maintenance</html>")));
        proxyService.invalidate(repositories.require("helm-proxy"));
        assertThat(versionNumbers(groupIndex(), "demo")).containsExactly("1.0.0");
    }

    @Test
    void newUploadsToTheHostedMemberShowUpInTheGroupIndex() throws Exception {
        MvcResult before = mvc.perform(index(URL)).andExpect(status().isOk()).andReturn();
        mvc.perform(upload("/repository/helm-hosted", auth, chart("internal", "0.1.0"))).andExpect(status().isCreated());
        MvcResult after = mvc.perform(index(URL)).andExpect(status().isOk()).andReturn();
        assertThat(after.getResponse().getHeader("ETag")).isNotEqualTo(before.getResponse().getHeader("ETag"));
        assertThat(versionNumbers(yaml(after), "internal")).containsExactly("0.1.0");
    }

    @Test
    void writesAreRefusedAndNameTheHostedMember() throws Exception {
        mvc.perform(upload(URL, auth, chart("demo", "3.0.0")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET, HEAD"))
                .andExpect(jsonPath("$.error").value(
                        "'helm-group' is a group repository, you cannot upload to it; use its hosted member 'helm-hosted'"));
        mvc.perform(delete(URL + "/api/charts/demo/1.0.0").header("Authorization", auth)).andExpect(status().isMethodNotAllowed());
        assertThat(versionNumbers(groupIndex(), "demo")).containsExactly("2.0.0", "1.0.0");
    }

    @Test
    void groupsOfGroupsAreFlattened() throws Exception {
        group("helm-all", "helm", GROUP);
        Map<String, Object> index = yaml(mvc.perform(index("/repository/helm-all")).andExpect(status().isOk()).andReturn());
        assertThat(versionNumbers(index, "demo")).containsExactly("2.0.0", "1.0.0");
        assertThat(url(versions(index, "nginx").getFirst())).isEqualTo(repositoryUrl("helm-all") + "/charts/nginx-15.0.0.tgz");
        assertThat(List.of(downloadOk("/repository/helm-all/charts/demo-1.0.0.tgz"))).containsExactly(HOSTED_DEMO);
    }
}
