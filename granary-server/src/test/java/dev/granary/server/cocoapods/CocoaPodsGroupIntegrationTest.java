package dev.granary.server.cocoapods;

import static dev.granary.server.cocoapods.CocoaPodsFixtures.VERSION_FILE;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.gitPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.httpPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.podspecPath;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.pushWithArchive;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.putPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.repositoryUrl;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.shardFile;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.str;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.text;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** One CDN source for Podfiles: private pods from the hosted member, everything else from the trunk proxy. */
class CocoaPodsGroupIntegrationTest extends IntegrationTest {

    private static final String HOSTED = "cocoapods-hosted";
    private static final String PROXY = "cocoapods-cdn";
    private static final String GROUP = "cocoapods-group";
    private static final String URL = "/repository/" + GROUP;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final byte[] ARCHIVE = zip("Remote.xcframework/Info.plist", "<plist/>");

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    private Repository proxyRepo;

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
        stubText("/CocoaPods-version.yml", VERSION_FILE);
        stubText("/all_pods_versions_a_7_5.txt", "AFNetworking/4.0.0/4.0.1\nAppseeAnalytics/2.4.7\n");
        stubText("/" + shardFile("Remote"), "Remote/1.0\n");
        stubText("/all_pods.txt", "AFNetworking\nAppseeAnalytics\nRemote\n");
        stubText("/deprecated_podspecs.txt", "Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json\n");
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/" + podspecPath("AFNetworking", "4.0.1")))
                .willReturn(WireMock.okJson(gitPodspec("AFNetworking", "4.0.1"))));
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/" + podspecPath("Remote", "1.0")))
                .willReturn(WireMock.okJson(httpPodspec("Remote", "1.0", upstream.baseUrl() + "/dl/Remote-1.0.zip", null))));
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/dl/Remote-1.0.zip"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody(ARCHIVE)));

        hosted(HOSTED, "cocoapods");
        Repository p = new Repository();
        p.setName(PROXY);
        p.setFormat("cocoapods");
        p.setType(RepositoryType.PROXY);
        p.setRemoteUrl(upstream.baseUrl() + "/");
        proxyRepo = repositories.create(p);
        proxyService.invalidate(proxyRepo);
        group(GROUP, "cocoapods", HOSTED, PROXY);
    }

    private static void stubText(String path, String body) {
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo(path)).willReturn(WireMock.aResponse().withStatus(200).withBody(body)));
    }

    private void publish(String name, String version, String json) throws Exception {
        mvc.perform(putPodspec(HOSTED, name, version, json).header("Authorization", adminBasic())).andExpect(status().isCreated());
    }

    private String fetch(String path) throws Exception {
        return text(mvc.perform(get(URL + "/" + path)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void indexesAreMergedFromAllMembers() throws Exception {
        publish("AFNetworking", "4.0.2-internal", gitPodspec("AFNetworking", "4.0.2-internal"));
        publish("Private", "0.1.0", gitPodspec("Private", "0.1.0"));
        publish("OldPrivate", "1.0", CocoaPodsFixtures.deprecatedPodspec("OldPrivate", "1.0", "Private"));

        assertThat(fetch("CocoaPods-version.yml")).contains("prefix_lengths:\n- 1\n- 1\n- 1\n");
        assertThat(fetch("all_pods_versions_a_7_5.txt")).isEqualTo("AFNetworking/4.0.0/4.0.1/4.0.2-internal\nAppseeAnalytics/2.4.7\n");
        assertThat(fetch(shardFile("Private"))).isEqualTo("Private/0.1.0\n");
        assertThat(fetch("all_pods.txt")).isEqualTo("AFNetworking\nAppseeAnalytics\nOldPrivate\nPrivate\nRemote\n");
        assertThat(fetch("deprecated_podspecs.txt")).isEqualTo("Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json\n"
                + podspecPath("OldPrivate", "1.0") + "\n");

        // podspecs come from whichever member has them
        assertThat(str(CocoaPodsFixtures.MAPPER.readTree(fetch(podspecPath("AFNetworking", "4.0.2-internal"))), "version"))
                .isEqualTo("4.0.2-internal");
        assertThat(str(CocoaPodsFixtures.MAPPER.readTree(fetch(podspecPath("AFNetworking", "4.0.1"))), "version")).isEqualTo("4.0.1");
        mvc.perform(get(URL + "/" + podspecPath("AFNetworking", "9.9.9"))).andExpect(status().isNotFound());
    }

    @Test
    void theHostedMemberWinsWhenBothHaveAVersion() throws Exception {
        publish("AFNetworking", "4.0.1", gitPodspec("AFNetworking", "4.0.1").replace("The AFNetworking pod.", "Our patched fork."));
        assertThat(fetch(podspecPath("AFNetworking", "4.0.1"))).contains("Our patched fork.");
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlMatching("/Specs/.*")));
        assertThat(fetch("all_pods_versions_a_7_5.txt")).isEqualTo("AFNetworking/4.0.0/4.0.1\nAppseeAnalytics/2.4.7\n");
    }

    @Test
    void archivesAreDownloadedThroughTheGroup() throws Exception {
        // a proxied http source
        JsonNode remote = CocoaPodsFixtures.MAPPER.readTree(fetch(podspecPath("Remote", "1.0")));
        assertThat(str(remote, "source", "http")).isEqualTo(repositoryUrl(GROUP) + "/sources/Remote/1.0/Remote-1.0.zip");
        MvcResult download = mvc.perform(get(URL + "/sources/Remote/1.0/Remote-1.0.zip")).andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(ARCHIVE);
        assertThat(storage.find(proxyRepo, "sources/Remote/1.0/Remote-1.0.zip")).isPresent();
        // the proxy itself hands out its own URL
        assertThat(text(mvc.perform(get("/repository/" + PROXY + "/" + podspecPath("Remote", "1.0"))).andReturn()))
                .contains(repositoryUrl(PROXY) + "/sources/Remote/1.0/Remote-1.0.zip");

        // a hosted archive is addressed through the group as well
        byte[] mine = zip("Mine.xcframework/Info.plist", "<plist/>");
        mvc.perform(pushWithArchive(HOSTED, httpPodspec("Mine", "1.0", "https://example.com/Mine-1.0.zip", null), "Mine-1.0.zip", mine)
                .header("Authorization", adminBasic())).andExpect(status().isCreated());
        JsonNode hostedSpec = CocoaPodsFixtures.MAPPER.readTree(fetch(podspecPath("Mine", "1.0")));
        assertThat(str(hostedSpec, "source", "http")).isEqualTo(repositoryUrl(GROUP) + "/sources/Mine/1.0/Mine-1.0.zip");
        assertThat(mvc.perform(get(URL + "/sources/Mine/1.0/Mine-1.0.zip")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray()).isEqualTo(mine);
    }

    @Test
    void theGroupCanLeaveProxiedSourcesAlone() throws Exception {
        Repository changes = repositories.require(GROUP);
        changes.setFormatSettings(Map.of("cocoapods.rewriteHttpSources", "false"));
        changes.setMembers(List.of(HOSTED, PROXY));
        repositories.update(GROUP, changes);
        assertThat(str(CocoaPodsFixtures.MAPPER.readTree(fetch(podspecPath("Remote", "1.0"))), "source", "http"))
                .isEqualTo(upstream.baseUrl() + "/dl/Remote-1.0.zip");
    }

    @Test
    void writesAreSentToTheHostedMember() throws Exception {
        mvc.perform(putPodspec(GROUP, "Mine", "1.0", gitPodspec("Mine", "1.0")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"))
                .andExpect(jsonPath("$.message").value(containsString("use its hosted member 'cocoapods-hosted'")));
        mvc.perform(delete(URL + "/Specs/Mine/1.0").header("Authorization", adminBasic())).andExpect(status().isMethodNotAllowed());
        mvc.perform(pushWithArchive(GROUP, gitPodspec("Mine", "1.0"), null, null).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void anOfflineProxyMemberLeavesTheHostedPodsAvailable() throws Exception {
        publish("Private", "0.1.0", gitPodspec("Private", "0.1.0"));
        fetch("all_pods_versions_a_7_5.txt");
        repositories.setOffline(PROXY, true);

        // cached and hosted content is still there
        assertThat(fetch("all_pods_versions_a_7_5.txt")).isEqualTo("AFNetworking/4.0.0/4.0.1\nAppseeAnalytics/2.4.7\n");
        assertThat(fetch(shardFile("Private"))).isEqualTo("Private/0.1.0\n");
        assertThat(fetch(podspecPath("Private", "0.1.0"))).contains("\"Private\"");
        // a shard nobody can answer is a retryable 503 instead of an empty shard pod would cache
        mvc.perform(get(URL + "/" + shardFile("Remote"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        mvc.perform(get(URL + "/" + podspecPath("Remote", "1.0"))).andExpect(status().isServiceUnavailable());
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo("/" + shardFile("Remote"))));
    }
}
