package dev.granary.server.cocoapods;

import static dev.granary.server.cocoapods.CocoaPodsFixtures.VERSION_FILE;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.gitPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.httpPodspec;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.podspecPath;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.repositoryUrl;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.sha256;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.str;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.text;
import static dev.granary.server.cocoapods.CocoaPodsFixtures.zip;
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
import dev.granary.core.proxy.UpstreamHealth;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Binary pods whose {@code source.http} lives on another host than the spec repository, as on trunk (GitHub
 * releases, S3, vendors' sites): the archive host never sees the spec repository's credentials, and its failures
 * neither open the proxy's circuit breaker nor end up cached.
 */
class CocoaPodsArchiveHostIntegrationTest extends IntegrationTest {

    private static final String PROXY = "cocoapods-private-cdn";
    private static final String GROUP = "cocoapods-group";
    private static final String URL = "/repository/" + PROXY;
    private static final WireMockServer cdn = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer archives = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final byte[] ARCHIVE = zip("Vendor.xcframework/Info.plist", "<plist version=\"1.0\"/>");

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private UpstreamHealth health;

    @Autowired
    private StorageService storage;

    private Repository proxyRepo;

    @BeforeAll
    static void startServers() {
        cdn.start();
        archives.start();
    }

    @AfterAll
    static void stopServers() {
        cdn.stop();
        archives.stop();
    }

    @BeforeEach
    void createRepositories() {
        cdn.resetAll();
        archives.resetAll();
        // a private spec CDN that wants its credentials on every request
        cdn.stubFor(WireMock.any(WireMock.anyUrl()).atPriority(10).willReturn(WireMock.unauthorized()));
        cdn.stubFor(WireMock.any(WireMock.anyUrl()).atPriority(5).withBasicAuth("mirror", "s3cret").willReturn(WireMock.notFound()));
        cdnHas("/CocoaPods-version.yml", VERSION_FILE);
        podOnCdn("Vendor", "2.0.0", archives.baseUrl() + "/releases/Vendor-2.0.0.zip", "\"sha256\": \"" + sha256(ARCHIVE) + "\"");
        podOnCdn("Dead", "1.0.0", archives.baseUrl() + "/gone/Dead-1.0.0.zip", null);
        podOnCdn("Broken", "1.0.0", archives.baseUrl() + "/broken/Broken-1.0.0.zip", null);
        podOnCdn("Tampered", "1.0.0", archives.baseUrl() + "/releases/Tampered-1.0.0.zip",
                "\"sha256\": \"" + "0".repeat(64) + "\"");
        cdnHas("/" + podspecPath("Plain", "1.0.0"), gitPodspec("Plain", "1.0.0"));
        archives.stubFor(WireMock.get("/releases/Vendor-2.0.0.zip").willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/zip").withBody(ARCHIVE)));
        archives.stubFor(WireMock.get("/gone/Dead-1.0.0.zip").willReturn(WireMock.notFound()));
        archives.stubFor(WireMock.get("/broken/Broken-1.0.0.zip").willReturn(WireMock.serverError()));
        archives.stubFor(WireMock.get("/releases/Tampered-1.0.0.zip").willReturn(WireMock.aResponse().withStatus(200).withBody(ARCHIVE)));

        Repository p = new Repository();
        p.setName(PROXY);
        p.setFormat("cocoapods");
        p.setType(RepositoryType.PROXY);
        p.setRemoteUrl(cdn.baseUrl() + "/");
        p.setRemoteUsername("mirror");
        p.setRemotePassword("s3cret");
        proxyRepo = repositories.create(p);
        proxyService.invalidate(proxyRepo);
        health.reset(proxyRepo);
        hosted("cocoapods-hosted", "cocoapods");
        group(GROUP, "cocoapods", "cocoapods-hosted", PROXY);
    }

    private static void cdnHas(String path, String body) {
        cdn.stubFor(WireMock.get(WireMock.urlEqualTo(path)).atPriority(1)
                .withBasicAuth("mirror", "s3cret").willReturn(WireMock.ok(body)));
    }

    private static void podOnCdn(String name, String version, String url, String extraSource) {
        cdnHas("/" + podspecPath(name, version), httpPodspec(name, version, url, extraSource));
    }

    private static String sourcePath(String name, String version) {
        return "sources/" + name + "/" + version + "/" + name + "-" + version + ".zip";
    }

    @Test
    void theArchiveHostNeverSeesTheSpecRepositorysCredentials() throws Exception {
        MvcResult podspec = mvc.perform(get(URL + "/" + podspecPath("Vendor", "2.0.0"))).andExpect(status().isOk()).andReturn();
        assertThat(str(CocoaPodsFixtures.json(podspec), "source", "http")).isEqualTo(repositoryUrl(PROXY) + "/" + sourcePath("Vendor", "2.0.0"));

        for (int i = 0; i < 2; i++) {
            MvcResult download = mvc.perform(get(URL + "/" + sourcePath("Vendor", "2.0.0"))).andExpect(status().isOk()).andReturn();
            assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(ARCHIVE);
        }
        archives.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/releases/Vendor-2.0.0.zip"))
                .withoutHeader("Authorization"));
        cdn.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/" + podspecPath("Vendor", "2.0.0")))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("mirror", "s3cret")));
        assertThat(storage.find(proxyRepo, sourcePath("Vendor", "2.0.0")).orElseThrow().asset().getComponentId()).isNotNull();

        // through the group as well
        assertThat(mvc.perform(get("/repository/" + GROUP + "/" + sourcePath("Vendor", "2.0.0"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(ARCHIVE);
    }

    @Test
    void aBrokenArchiveHostIsABadGatewayAndLeavesTheSpecRepositoryHealthy() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get(URL + "/" + sourcePath("Broken", "1.0.0"))).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("upstream.unavailable"))
                    .andExpect(jsonPath("$.message").value(Matchers.containsString(archives.baseUrl() + "/broken/Broken-1.0.0.zip")));
        }
        mvc.perform(get("/repository/" + GROUP + "/" + sourcePath("Broken", "1.0.0"))).andExpect(status().isBadGateway());
        archives.verify(6, WireMock.getRequestedFor(WireMock.urlEqualTo("/broken/Broken-1.0.0.zip")));
        assertThat(storage.find(proxyRepo, sourcePath("Broken", "1.0.0"))).isEmpty();

        // the spec repository itself is still asked, and a pod it does not have is still a plain 404
        assertThat(health.status(proxyRepo).consecutiveFailures()).isZero();
        assertThat(text(mvc.perform(get(URL + "/" + podspecPath("Plain", "1.0.0"))).andExpect(status().isOk()).andReturn()))
                .contains("\"Plain\"");
        mvc.perform(get(URL + "/" + podspecPath("Missing", "1.0.0"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("content.notFound"));
    }

    @Test
    void anArchiveTheHostNoLongerHasIsANotFoundAndRemembered() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(URL + "/" + sourcePath("Dead", "1.0.0"))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("content.notFound"));
        }
        archives.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/gone/Dead-1.0.0.zip")));
    }

    @Test
    void anArchiveThatDoesNotMatchItsPodspecIsNeverCached() throws Exception {
        mvc.perform(get(URL + "/" + sourcePath("Tampered", "1.0.0"))).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("upstream.checksumMismatch"));
        assertThat(storage.find(proxyRepo, sourcePath("Tampered", "1.0.0"))).isEmpty();

        // once the host serves the right bytes, the archive is cached
        byte[] fixed = zip("Tampered.xcframework/Info.plist", "fixed");
        cdn.resetAll();
        podOnCdn("Tampered", "1.0.0", archives.baseUrl() + "/releases/Tampered-1.0.0.zip", "\"sha256\": \"" + sha256(fixed) + "\"");
        archives.stubFor(WireMock.get("/releases/Tampered-1.0.0.zip").willReturn(WireMock.aResponse().withStatus(200).withBody(fixed)));
        Repository other = new Repository();
        other.setName("cocoapods-fixed-cdn");
        other.setFormat("cocoapods");
        other.setType(RepositoryType.PROXY);
        other.setRemoteUrl(cdn.baseUrl() + "/");
        other.setRemoteUsername("mirror");
        other.setRemotePassword("s3cret");
        proxyService.invalidate(repositories.create(other));
        assertThat(mvc.perform(get("/repository/cocoapods-fixed-cdn/" + sourcePath("Tampered", "1.0.0"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(fixed);
    }

    @Test
    void offlineProxiesNeverReachTheArchiveHost() throws Exception {
        mvc.perform(get(URL + "/" + podspecPath("Vendor", "2.0.0"))).andExpect(status().isOk());
        settings.setGlobalOffline(true);
        mvc.perform(get(URL + "/" + sourcePath("Vendor", "2.0.0"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        archives.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
        settings.setGlobalOffline(false);
        mvc.perform(get(URL + "/" + sourcePath("Vendor", "2.0.0"))).andExpect(status().isOk());
    }
}
