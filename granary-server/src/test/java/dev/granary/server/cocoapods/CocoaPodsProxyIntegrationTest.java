package dev.granary.server.cocoapods;

import static dev.granary.server.cocoapods.CocoaPodsFixtures.VERSION_FILE;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.gitPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.httpPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.podspecPath;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.putPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.repositoryUrl;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.sha256;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.str;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.text;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * A proxy in front of a WireMock fake of {@code cdn.cocoapods.org}: index files as metadata, podspecs behind a
 * redirect (the real CDN redirects them to jsDelivr), http sources rewritten and cached, and the offline-first
 * fallbacks.
 */
class CocoaPodsProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "cocoapods-cdn";
    private static final String URL = "/repository/" + REPO;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static final String AF_PATH = "Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json";
    private static final String AF_SPEC = gitPodspec("AFNetworking", "4.0.1");
    private static final byte[] ARCHIVE = zip("Zipped.xcframework/Info.plist", "<plist version=\"1.0\"/>");
    private static final String ZIPPED_PATH = "Specs/f/e/5/Zipped/1.2.0/Zipped.podspec.json";

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private ComponentDao components;

    private Repository repo;

    @BeforeAll
    static void startUpstream() {
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void createRepository() {
        upstream.resetAll();
        stubCdn();
        repo = createProxy(REPO, null, Map.of());
    }

    private Repository createProxy(String name, Integer metadataMaxAgeMinutes, Map<String, String> settings) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat("cocoapods");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(upstream.baseUrl() + "/");
        r.setMetadataMaxAgeMinutes(metadataMaxAgeMinutes);
        r.setFormatSettings(settings);
        Repository created = repositories.create(r);
        proxyService.invalidate(created);
        return created;
    }

    private static String zippedSpec() {
        return httpPodspec("Zipped", "1.2.0", upstream.baseUrl() + "/downloads/Zipped-1.2.0.zip",
                "\"sha256\": \"" + sha256(ARCHIVE) + "\", \"headers\": [\"Accept: application/octet-stream\", \"Host: evil.example\"]");
    }

    private static void stubText(String path, String body) {
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo(path)).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "text/plain; charset=utf-8").withBody(body)));
    }

    private static void stubCdn() {
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/CocoaPods-version.yml")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "text/yaml").withHeader("ETag", "\"v1\"").withBody(VERSION_FILE)));
        stubText("/all_pods_versions_a_7_5.txt", "AFNetworking/4.0.0/4.0.1\n");
        stubText("/all_pods_versions_f_e_5.txt", "Zipped/1.2.0\n");
        stubText("/all_pods.txt", "AFNetworking\nID.me WebVerify\nZipped\n");
        stubText("/deprecated_podspecs.txt", "Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json\n");
        // like the real CDN, podspecs redirect to the jsDelivr mirror of the Specs repository
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/" + AF_PATH)).willReturn(WireMock.aResponse().withStatus(301)
                .withHeader("Location", upstream.baseUrl() + "/jsdelivr/" + AF_PATH)));
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/jsdelivr/" + AF_PATH)).willReturn(WireMock.okJson(AF_SPEC)));
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/" + ZIPPED_PATH)).willReturn(WireMock.okJson(zippedSpec())));
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/downloads/Zipped-1.2.0.zip")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/zip").withBody(ARCHIVE)));
    }

    private MvcResult fetch(String path) throws Exception {
        return mvc.perform(get(URL + "/" + path)).andExpect(status().isOk()).andReturn();
    }

    @Test
    void indexFilesArePassedThroughAndCachedAsMetadata() throws Exception {
        assertThat(text(fetch("CocoaPods-version.yml"))).isEqualTo(VERSION_FILE);
        assertThat(text(fetch("all_pods_versions_a_7_5.txt"))).isEqualTo("AFNetworking/4.0.0/4.0.1\n");
        assertThat(text(fetch("all_pods.txt"))).isEqualTo("AFNetworking\nID.me WebVerify\nZipped\n");
        assertThat(text(fetch("deprecated_podspecs.txt"))).isEqualTo("Specs/0/0/4/AFCategory/0.1.0/AFCategory.podspec.json\n");
        for (String path : new String[] {"CocoaPods-version.yml", "all_pods_versions_a_7_5.txt", "all_pods.txt", "deprecated_podspecs.txt"}) {
            assertThat(storage.find(repo, path).orElseThrow().asset().isMetadata()).as(path).isTrue();
        }
        // fresh metadata comes from the cache
        fetch("all_pods_versions_a_7_5.txt");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/all_pods_versions_a_7_5.txt")));

        // a shard the upstream does not have is a 404 pod understands
        mvc.perform(get(URL + "/all_pods_versions_0_0_0.txt")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("content.notFound"));
    }

    @Test
    void podspecsFollowTheRedirectAndAreCachedForever() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(text(fetch(AF_PATH))).isEqualTo(AF_SPEC);
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/" + AF_PATH)));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/jsdelivr/" + AF_PATH)));
        assertThat(storage.find(repo, AF_PATH).orElseThrow().asset().isMetadata()).isFalse();
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "AFNetworking", "4.0.1")).isPresent();
        // git sources are never touched, and the shorter path names the same podspec
        assertThat(text(fetch("Specs/AFNetworking/4.0.1/AFNetworking.podspec.json"))).isEqualTo(AF_SPEC);
        mvc.perform(get(URL + "/Specs/0/0/0/AFNetworking/4.0.1/AFNetworking.podspec.json")).andExpect(status().isNotFound());
    }

    @Test
    void httpSourcesAreDownloadedThroughTheProxyAndCachedOnce() throws Exception {
        String local = repositoryUrl(REPO) + "/sources/Zipped/1.2.0/Zipped-1.2.0.zip";
        JsonNode spec = CocoaPodsFixtures.MAPPER.readTree(text(fetch(ZIPPED_PATH)));
        assertThat(str(spec, "source", "http")).isEqualTo(local);
        assertThat(str(spec, "source", "sha256")).isEqualTo(sha256(ARCHIVE));
        assertThat(spec.path("source").has("headers")).isFalse();
        // what is cached is the upstream's podspec; the rewrite happens on the way out
        assertThat(storage.find(repo, ZIPPED_PATH).orElseThrow().readUtf8()).isEqualTo(zippedSpec());

        for (int i = 0; i < 2; i++) {
            MvcResult download = mvc.perform(get(URL + "/sources/Zipped/1.2.0/Zipped-1.2.0.zip")).andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"Zipped-1.2.0.zip\""))
                    .andReturn();
            assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(ARCHIVE);
        }
        // the podspec's own headers go upstream, except those Granary owns
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/downloads/Zipped-1.2.0.zip"))
                .withHeader("Accept", WireMock.equalTo("application/octet-stream"))
                .withHeader("Host", WireMock.notContaining("evil")));
        assertThat(storage.find(repo, "sources/Zipped/1.2.0/Zipped-1.2.0.zip").orElseThrow().asset().getComponentId()).isNotNull();

        // only the file the podspec names can be fetched this way
        mvc.perform(get(URL + "/sources/Zipped/1.2.0/other.zip")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/sources/AFNetworking/4.0.1/4.0.1.zip")).andExpect(status().isNotFound());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlMatching("/downloads/.*")));
    }

    @Test
    void theRewriteCanBeSwitchedOff() throws Exception {
        createProxy("cocoapods-direct", null, Map.of("cocoapods.rewriteHttpSources", "false"));
        MvcResult result = mvc.perform(get("/repository/cocoapods-direct/" + ZIPPED_PATH)).andExpect(status().isOk()).andReturn();
        assertThat(text(result)).isEqualTo(zippedSpec());
    }

    @Test
    void podNamesAreSentUpstreamPercentEncoded() throws Exception {
        String spec = gitPodspec("ID.me WebVerify", "6.4.0");
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/Specs/e/5/f/ID.me%20WebVerify/6.4.0/ID.me%20WebVerify.podspec.json"))
                .willReturn(WireMock.okJson(spec)));
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/Specs/2/8/c/cocoapods%E5%88%B6%E4%BD%9C/0.1/cocoapods%E5%88%B6%E4%BD%9C.podspec.json"))
                .willReturn(WireMock.okJson(gitPodspec("cocoapods制作", "0.1"))));

        MvcResult spaced = mvc.perform(get(URI.create(URL + "/Specs/e/5/f/ID.me%20WebVerify/6.4.0/ID.me%20WebVerify.podspec.json")))
                .andExpect(status().isOk()).andReturn();
        assertThat(text(spaced)).isEqualTo(spec);
        MvcResult cjk = mvc.perform(get(URI.create(URL + "/Specs/2/8/c/cocoapods%E5%88%B6%E4%BD%9C/0.1/cocoapods%E5%88%B6%E4%BD%9C.podspec.json")))
                .andExpect(status().isOk()).andReturn();
        assertThat(str(CocoaPodsFixtures.json(cjk), "name")).isEqualTo("cocoapods制作");
    }

    @Test
    void metadataIsRevalidatedWithTheUpstreamETag() throws Exception {
        createProxy("cocoapods-fresh", 0, Map.of());
        String url = "/repository/cocoapods-fresh/";
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/all_pods_versions_a_7_5.txt")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("ETag", "\"s1\"").withBody("AFNetworking/4.0.1\n")));
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/all_pods_versions_a_7_5.txt")).atPriority(1)
                .withHeader("If-None-Match", WireMock.equalTo("\"s1\"")).willReturn(WireMock.aResponse().withStatus(304)));

        assertThat(text(mvc.perform(get(url + "all_pods_versions_a_7_5.txt")).andExpect(status().isOk()).andReturn()))
                .isEqualTo("AFNetworking/4.0.1\n");
        assertThat(text(mvc.perform(get(url + "all_pods_versions_a_7_5.txt")).andExpect(status().isOk()).andReturn()))
                .isEqualTo("AFNetworking/4.0.1\n");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/all_pods_versions_a_7_5.txt"))
                .withHeader("If-None-Match", WireMock.equalTo("\"s1\"")));

        // a new version on the upstream shows up on the next request
        upstream.resetMappings();
        stubText("/all_pods_versions_a_7_5.txt", "AFNetworking/4.0.1/4.1.0\n");
        assertThat(text(mvc.perform(get(url + "all_pods_versions_a_7_5.txt")).andExpect(status().isOk()).andReturn()))
                .isEqualTo("AFNetworking/4.0.1/4.1.0\n");
    }

    @Test
    void deprecatedPodspecsAreRevalidatedLikeMetadata() throws Exception {
        Repository fresh = createProxy("cocoapods-fresh", 0, Map.of());
        String url = "/repository/cocoapods-fresh/";
        String oldPath = podspecPath("OldPod", "1.0");
        stubText("/deprecated_podspecs.txt", oldPath + "\n");
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/" + oldPath)).willReturn(WireMock.okJson(gitPodspec("OldPod", "1.0"))));
        mvc.perform(get(url + "deprecated_podspecs.txt")).andExpect(status().isOk());

        mvc.perform(get(url + oldPath)).andExpect(status().isOk());
        // pod trunk deprecate rewrites the podspec on the upstream
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/" + oldPath))
                .willReturn(WireMock.okJson(CocoaPodsFixtures.deprecatedPodspec("OldPod", "1.0", "NewPod"))));
        mvc.perform(get(url + oldPath)).andExpect(status().isOk()).andExpect(jsonPath("$.deprecated_in_favor_of").value("NewPod"));
        upstream.verify(2, WireMock.getRequestedFor(WireMock.urlEqualTo("/" + oldPath)));
        assertThat(storage.find(fresh, oldPath).orElseThrow().asset().isMetadata()).isTrue();

        // podspecs that are not deprecated are still fetched once
        mvc.perform(get(url + AF_PATH)).andExpect(status().isOk());
        mvc.perform(get(url + AF_PATH)).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/jsdelivr/" + AF_PATH)));
    }

    @Test
    void anUpstreamWithoutShardsIsProxiedAsItIs() throws Exception {
        upstream.resetMappings();
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/CocoaPods-version.yml"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody("---\nmin: 1.0.0\nlast: 1.16.2\n")));
        stubText("/all_pods_versions.txt", "Flat/1.0\n");
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/Specs/Flat/1.0/Flat.podspec.json"))
                .willReturn(WireMock.okJson(gitPodspec("Flat", "1.0"))));

        assertThat(text(fetch("CocoaPods-version.yml"))).doesNotContain("prefix_lengths");
        assertThat(text(fetch("all_pods_versions.txt"))).isEqualTo("Flat/1.0\n");
        assertThat(str(CocoaPodsFixtures.json(fetch("Specs/Flat/1.0/Flat.podspec.json")), "name")).isEqualTo("Flat");
        // a client of this proxy never asks for the sharded path; it is not this upstream's layout
        mvc.perform(get(URL + "/" + podspecPath("Flat", "1.0"))).andExpect(status().isNotFound());
    }

    @Test
    void theCacheIsServedWhenTheUpstreamBreaks() throws Exception {
        createProxy("cocoapods-fresh", 0, Map.of());
        String url = "/repository/cocoapods-fresh/";
        mvc.perform(get(url + "all_pods_versions_a_7_5.txt")).andExpect(status().isOk());
        mvc.perform(get(url + AF_PATH)).andExpect(status().isOk());
        mvc.perform(get(url + "sources/Zipped/1.2.0/Zipped-1.2.0.zip")).andExpect(status().isOk());

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        assertThat(text(mvc.perform(get(url + "all_pods_versions_a_7_5.txt")).andExpect(status().isOk()).andReturn()))
                .isEqualTo("AFNetworking/4.0.0/4.0.1\n");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/all_pods_versions_a_7_5.txt"))); // it did try
        assertThat(text(mvc.perform(get(url + AF_PATH)).andExpect(status().isOk()).andReturn())).isEqualTo(AF_SPEC);
        assertThat(mvc.perform(get(url + "sources/Zipped/1.2.0/Zipped-1.2.0.zip")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray()).isEqualTo(ARCHIVE);

        // what was never cached is a temporary 503, which pod retries, not a 404
        mvc.perform(get(url + "all_pods_versions_f_e_5.txt")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(get(url + "Specs/a/7/5/AFNetworking/4.0.0/AFNetworking.podspec.json")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void offlineModeServesTheCacheAndNeverCallsTheUpstream() throws Exception {
        fetch("CocoaPods-version.yml");
        fetch("all_pods_versions_a_7_5.txt");
        fetch(AF_PATH);
        repositories.setOffline(REPO, true);
        upstream.resetRequests();

        assertThat(text(fetch("all_pods_versions_a_7_5.txt"))).isEqualTo("AFNetworking/4.0.0/4.0.1\n");
        assertThat(text(fetch(AF_PATH))).isEqualTo(AF_SPEC);
        mvc.perform(get(URL + "/" + ZIPPED_PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        mvc.perform(get(URL + "/all_pods.txt")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        repositories.setOffline(REPO, false);
        settings.setGlobalOffline(true);
        assertThat(text(fetch(AF_PATH))).isEqualTo(AF_SPEC);
        mvc.perform(get(URL + "/" + ZIPPED_PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        settings.setGlobalOffline(false);
        fetch(ZIPPED_PATH);
    }

    @Test
    void missingPodspecsAreRememberedByTheNegativeCache() throws Exception {
        String missing = podspecPath("Nope", "1.0");
        upstream.stubFor(WireMock.get(WireMock.urlEqualTo("/" + missing)).willReturn(WireMock.notFound()));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(URL + "/" + missing)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("content.notFound"));
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/" + missing)));
    }

    @Test
    void proxiesAreReadOnly() throws Exception {
        mvc.perform(putPodspec(REPO, "Mine", "1.0", gitPodspec("Mine", "1.0")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }
}
