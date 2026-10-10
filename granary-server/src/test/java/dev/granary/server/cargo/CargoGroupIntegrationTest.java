package dev.granary.server.cargo;

import static dev.granary.server.cargo.CargoFixtures.api;
import static dev.granary.server.cargo.CargoFixtures.crate;
import static dev.granary.server.cargo.CargoFixtures.download;
import static dev.granary.server.cargo.CargoFixtures.errorDetail;
import static dev.granary.server.cargo.CargoFixtures.index;
import static dev.granary.server.cargo.CargoFixtures.indexFile;
import static dev.granary.server.cargo.CargoFixtures.indexLine;
import static dev.granary.server.cargo.CargoFixtures.lines;
import static dev.granary.server.cargo.CargoFixtures.metadata;
import static dev.granary.server.cargo.CargoFixtures.publish;
import static dev.granary.server.cargo.CargoFixtures.publishBody;
import static dev.granary.server.cargo.CargoFixtures.repositoryUrl;
import static dev.granary.server.cargo.CargoFixtures.sha256;
import static dev.granary.server.cargo.CargoFixtures.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.node.ObjectNode;

/** One registry URL for everything: a group of a hosted repository and a proxy of a fake crates.io. */
class CargoGroupIntegrationTest extends IntegrationTest {

    private static final String GROUP = "cargo-group";
    private static final String URL = "/repository/" + GROUP;
    private static final String HOSTED_URL = "/repository/cargo-hosted";
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    private String token;

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
        hosted("cargo-hosted", "cargo");
        Repository proxy = new Repository();
        proxy.setName("cargo-proxy");
        proxy.setFormat("cargo");
        proxy.setType(RepositoryType.PROXY);
        proxy.setRemoteUrl(upstream.baseUrl() + "/index/");
        proxy.setFormatSettings(Map.of(
                "cargo.downloadUrl", upstream.baseUrl() + "/crates/{crate}/{crate}-{version}.crate",
                "cargo.apiUrl", upstream.baseUrl()));
        proxyService.invalidate(repositories.create(proxy));
        // the proxy is listed first on purpose: hosted members still win
        group(GROUP, "cargo", "cargo-proxy", "cargo-hosted");
        token = tokens.issue(users.require(ADMIN), "cargo", null).secret();
    }

    private void stubIndex(String path, String body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/index/" + path))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody(body)));
    }

    private void stubCrate(String name, String version, byte[] crate) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/crates/" + name + "/" + name + "-" + version + ".crate"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody(crate)));
    }

    private byte[] downloadOk(String url) throws Exception {
        return mvc.perform(download(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void configJsonNamesTheGroup() throws Exception {
        mvc.perform(index(URL + "/index/config.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dl").value(repositoryUrl(GROUP) + "/api/v1/crates"))
                .andExpect(jsonPath("$.api").value(repositoryUrl(GROUP)))
                .andExpect(jsonPath("$.auth-required").value(false));
    }

    @Test
    void hostedAndProxiedCratesResolveThroughOneIndex() throws Exception {
        byte[] mine = crate("acme-hello", "0.1.0");
        mvc.perform(publish(HOSTED_URL, token, publishBody(metadata("acme-hello", "0.1.0"), mine))).andExpect(status().isOk());
        byte[] itoa = crate("itoa", "1.0.18");
        stubIndex("it/oa/itoa", indexFile(indexLine("itoa", "1.0.18", itoa, false)));
        stubCrate("itoa", "1.0.18", itoa);
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/index/ac/me/acme-hello")).willReturn(WireMock.notFound()));

        assertThat(versions(mvc.perform(index(URL + "/index/ac/me/acme-hello")).andExpect(status().isOk()).andReturn()))
                .containsExactly("0.1.0");
        assertThat(versions(mvc.perform(index(URL + "/index/it/oa/itoa")).andExpect(status().isOk()).andReturn()))
                .containsExactly("1.0.18");
        assertThat(downloadOk(URL + "/api/v1/crates/acme-hello/0.1.0/download")).isEqualTo(mine);
        assertThat(downloadOk(URL + "/api/v1/crates/itoa/1.0.18/download")).isEqualTo(itoa);
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/crates/acme-hello/acme-hello-0.1.0.crate")));
    }

    @Test
    void versionsOfTheSameCrateAreMergedAndHostedOnesCannotBeShadowed() throws Exception {
        byte[] mine = crate("acme", "1.0.0");
        mvc.perform(publish(HOSTED_URL, token, publishBody(metadata("acme", "1.0.0"), mine))).andExpect(status().isOk());
        // crates.io has a crate with the same name, including the same version with different bytes
        byte[] theirs = crate("acme", "1.0.0", "pub fn not_yours() {}\n");
        byte[] older = crate("acme", "0.5.0");
        stubIndex("ac/me/acme", indexFile(indexLine("acme", "0.5.0", older, false), indexLine("acme", "1.0.0", theirs, false)));
        stubCrate("acme", "0.5.0", older);
        stubCrate("acme", "1.0.0", theirs);

        List<ObjectNode> lines = lines(mvc.perform(index(URL + "/index/ac/me/acme")).andExpect(status().isOk()).andReturn());
        assertThat(lines).extracting(l -> l.path("vers").stringValue()).containsExactly("0.5.0", "1.0.0");
        assertThat(lines.get(1).path("cksum").stringValue()).isEqualTo(sha256(mine));

        assertThat(downloadOk(URL + "/api/v1/crates/acme/1.0.0/download")).isEqualTo(mine);
        assertThat(downloadOk(URL + "/api/v1/crates/acme/0.5.0/download")).isEqualTo(older);
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/crates/acme/acme-1.0.0.crate")));
        mvc.perform(download(URL + "/api/v1/crates/acme/2.0.0/download")).andExpect(status().isNotFound());
    }

    @Test
    void publishingThroughTheGroupPointsAtItsHostedMember() throws Exception {
        MvcResult result = mvc.perform(publish(URL, token, publishBody("acme", "1.0.0")))
                .andExpect(status().isMethodNotAllowed()).andReturn();
        assertThat(errorDetail(result)).contains("group repository").contains("`cargo-hosted`");
    }

    @Test
    void ownersAreReadFromTheHostedMember() throws Exception {
        mvc.perform(publish(HOSTED_URL, token, publishBody("acme", "1.0.0"))).andExpect(status().isOk());
        mvc.perform(api(get(URL + "/api/v1/crates/acme/owners"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].login").value("admin"));
        mvc.perform(api(get(URL + "/api/v1/crates/itoa/owners"), token)).andExpect(status().isNotFound());
    }

    @Test
    void searchListsHostedCratesFirstAndEachCrateOnce() throws Exception {
        mvc.perform(publish(HOSTED_URL, token, publishBody("itoa", "9.0.0"))).andExpect(status().isOk());
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/crates"))
                .willReturn(WireMock.okJson("{\"crates\":[{\"name\":\"itoa\",\"max_version\":\"1.0.18\",\"description\":\"Fast\"},"
                        + "{\"name\":\"itoap\",\"max_version\":\"1.0.1\",\"description\":\"Faster\"}],\"meta\":{\"total\":2}}")));
        mvc.perform(api(get(URL + "/api/v1/crates").param("q", "itoa"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crates.length()").value(2))
                .andExpect(jsonPath("$.crates[0].name").value("itoa"))
                .andExpect(jsonPath("$.crates[0].max_version").value("9.0.0"))
                .andExpect(jsonPath("$.crates[1].name").value("itoap"))
                .andExpect(jsonPath("$.meta.total").value(2));
    }

    @Test
    void anOfflineProxyMemberStillLetsHostedAndCachedCratesThrough() throws Exception {
        byte[] mine = crate("acme", "1.0.0");
        mvc.perform(publish(HOSTED_URL, token, publishBody(metadata("acme", "1.0.0"), mine))).andExpect(status().isOk());
        byte[] itoa = crate("itoa", "1.0.18");
        stubIndex("it/oa/itoa", indexFile(indexLine("itoa", "1.0.18", itoa, false)));
        stubCrate("itoa", "1.0.18", itoa);
        downloadOk(URL + "/api/v1/crates/itoa/1.0.18/download");
        upstream.resetAll();

        repositories.setOffline("cargo-proxy", true);
        assertThat(versions(mvc.perform(index(URL + "/index/ac/me/acme")).andExpect(status().isOk()).andReturn()))
                .containsExactly("1.0.0");
        assertThat(downloadOk(URL + "/api/v1/crates/acme/1.0.0/download")).isEqualTo(mine);
        assertThat(versions(mvc.perform(index(URL + "/index/it/oa/itoa")).andExpect(status().isOk()).andReturn()))
                .containsExactly("1.0.18");
        assertThat(downloadOk(URL + "/api/v1/crates/itoa/1.0.18/download")).isEqualTo(itoa);

        MvcResult missing = mvc.perform(index(URL + "/index/se/rd/serde"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"))
                .andReturn();
        assertThat(missing.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("cargo-proxy");
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void connectSnippetsMirrorCratesIoAndPublishThroughTheHostedMember() throws Exception {
        String body = mvc.perform(get("/api/v1/repositories/" + GROUP + "/connect").header("Authorization", adminBasic()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("cargo-mirror")
                .contains("replace-with")
                .contains("sparse+" + repositoryUrl(GROUP) + "/index/")
                .contains("sparse+" + repositoryUrl("cargo-hosted") + "/index/");
    }
}
