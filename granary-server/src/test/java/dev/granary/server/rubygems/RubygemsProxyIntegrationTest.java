package dev.granary.server.rubygems;

import static dev.granary.server.rubygems.RubygemsFixtures.gem;
import static dev.granary.server.rubygems.RubygemsFixtures.md5;
import static dev.granary.server.rubygems.RubygemsFixtures.repositoryUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** A proxy in front of a fake rubygems.org: caching, revalidation, component linking and the offline fallbacks. */
class RubygemsProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "gems-proxy";
    private static final String URL = "/repository/" + REPO;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static final String RAKE_INFO = "---\n"
            + "13.4.1 |checksum:b4e81bd6a748308a6799619d824ec6a23cd1acd07d9ec41e5f2ebfb2294447c8,ruby:>= 2.3,created_at:2026-04-14T08:25:34Z\n"
            + "13.4.2 |checksum:cb825b2bd5f1f8e91ca37bddb4b9aaf345551b4731da62949be002fa89283701,ruby:>= 2.3,created_at:2026-04-16T04:47:46Z\n";
    static final String VERSIONS = "created_at: 2026-10-01T00:00:00Z\n---\n"
            + "minitest 5.0.0 0123456789abcdef0123456789abcdef\n"
            + "rake 13.4.1,13.4.2 " + md5(RAKE_INFO) + "\n";

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

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
        repo = proxy(REPO, "rubygems", upstream.baseUrl() + "/");
        proxyService.invalidate(repo);
    }

    private void stub(String path, String body) {
        stub(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private void stub(String path, byte[] body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(body)));
    }

    @Test
    void compactIndexIsCachedAsMetadata() throws Exception {
        stub("/versions", VERSIONS);
        stub("/info/rake", RAKE_INFO);

        for (int i = 0; i < 2; i++) {
            mvc.perform(get(URL + "/versions")).andExpect(status().isOk())
                    .andExpect(content().string(VERSIONS))
                    .andExpect(header().string("ETag", "\"" + md5(VERSIONS) + "\""));
            mvc.perform(get(URL + "/info/rake")).andExpect(status().isOk()).andExpect(content().string(RAKE_INFO));
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/versions")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/info/rake")));
        assertThat(storage.find(repo, "versions")).hasValueSatisfying(c -> assertThat(c.asset().isMetadata()).isTrue());
        assertThat(storage.find(repo, "info/rake")).hasValueSatisfying(c -> assertThat(c.asset().isMetadata()).isTrue());

        // Bundler's incremental update works on the cached copy too
        byte[] versions = VERSIONS.getBytes(StandardCharsets.UTF_8);
        mvc.perform(get(URL + "/versions").header("Range", "bytes=" + (versions.length - 1) + "-"))
                .andExpect(status().isPartialContent())
                .andExpect(content().string("\n"));
    }

    @Test
    void gemsAreFetchedOnceAndLinkedToComponents() throws Exception {
        byte[] rake = gem("rake", "13.4.2");
        stub("/gems/rake-13.4.2.gem", rake);
        stub("/quick/Marshal.4.8/rake-13.4.2.gemspec.rz", new byte[] {0x78, (byte) 0x9c, 3, 0, 0, 0, 0, 1});

        for (int i = 0; i < 2; i++) {
            assertThat(mvc.perform(get(URL + "/gems/rake-13.4.2.gem")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray()).isEqualTo(rake);
        }
        mvc.perform(get(URL + "/quick/Marshal.4.8/rake-13.4.2.gemspec.rz")).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/gems/rake-13.4.2.gem")));

        assertThat(storage.find(repo, "gems/rake-13.4.2.gem")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(2);
            assertThat(c.asset().getComponentId()).isNotNull();
        });
        Component component = storage.ensureComponent(repo, new StorageService.ComponentCoordinates("", "rake", "13.4.2"));
        assertThat(storage.find(repo, "quick/Marshal.4.8/rake-13.4.2.gemspec.rz").orElseThrow().asset().getComponentId())
                .isEqualTo(component.getId());
        assertThat(storage.find(repo, "gems/rake-13.4.2.gem").orElseThrow().asset().getComponentId())
                .isEqualTo(component.getId());
    }

    @Test
    void theJsonApiPointsGemUriAtGranary() throws Exception {
        stub("/api/v1/gems/rake.json", "{\"name\":\"rake\",\"version\":\"13.4.2\",\"platform\":\"ruby\","
                + "\"gem_uri\":\"https://rubygems.org/gems/rake-13.4.2.gem\"}");
        mvc.perform(get(URL + "/api/v1/gems/rake.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.gem_uri").value(repositoryUrl(REPO) + "/gems/rake-13.4.2.gem"));
        String cached = storage.find(repo, "api/v1/gems/rake.json").orElseThrow().readUtf8();
        assertThat(cached).contains("https://rubygems.org/gems/rake-13.4.2.gem");
    }

    @Test
    void staleIndexesAndCachedGemsAreServedWhenUpstreamBreaks() throws Exception {
        Repository impatient = new Repository();
        impatient.setName("gems-proxy-stale");
        impatient.setFormat("rubygems");
        impatient.setType(RepositoryType.PROXY);
        impatient.setRemoteUrl(upstream.baseUrl() + "/");
        impatient.setMetadataMaxAgeMinutes(0);
        repositories.create(impatient);
        String url = "/repository/gems-proxy-stale";

        byte[] rake = gem("rake", "13.4.2");
        stub("/versions", VERSIONS);
        stub("/info/rake", RAKE_INFO);
        stub("/gems/rake-13.4.2.gem", rake);
        mvc.perform(get(url + "/versions")).andExpect(status().isOk());
        mvc.perform(get(url + "/info/rake")).andExpect(status().isOk());
        mvc.perform(get(url + "/gems/rake-13.4.2.gem")).andExpect(status().isOk());

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        mvc.perform(get(url + "/versions")).andExpect(status().isOk()).andExpect(content().string(VERSIONS));
        mvc.perform(get(url + "/info/rake")).andExpect(status().isOk()).andExpect(content().string(RAKE_INFO));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/info/rake")));
        assertThat(mvc.perform(get(url + "/gems/rake-13.4.2.gem")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(rake);
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/gems/rake-13.4.2.gem")));

        // never cached while upstream is broken: a temporary 503, which gem and Bundler retry instead of giving up
        mvc.perform(get(url + "/info/rails")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(get(url + "/gems/rails-8.0.0.gem")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void offlineModeServesTheCacheAndNeverTouchesUpstream() throws Exception {
        stub("/versions", VERSIONS);
        stub("/info/rake", RAKE_INFO);
        stub("/gems/rake-13.4.2.gem", gem("rake", "13.4.2"));
        mvc.perform(get(URL + "/versions")).andExpect(status().isOk());
        mvc.perform(get(URL + "/info/rake")).andExpect(status().isOk());
        mvc.perform(get(URL + "/gems/rake-13.4.2.gem")).andExpect(status().isOk());

        repositories.setOffline(REPO, true);
        upstream.resetAll();
        stub("/info/rails", "---\n8.0.0 |checksum:00\n");

        mvc.perform(get(URL + "/versions")).andExpect(status().isOk());
        mvc.perform(get(URL + "/info/rake")).andExpect(status().isOk()).andExpect(content().string(RAKE_INFO));
        mvc.perform(get(URL + "/gems/rake-13.4.2.gem")).andExpect(status().isOk());
        mvc.perform(get(URL + "/info/rails")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        repositories.setOffline(REPO, false);
        mvc.perform(get(URL + "/info/rails")).andExpect(status().isOk());
    }

    @Test
    void upstream404IsRememberedByTheNegativeCache() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/info/nope"))
                .willReturn(WireMock.aResponse().withStatus(404).withBody("This rubygem could not be found.")));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(URL + "/info/nope")).andExpect(status().isNotFound());
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/info/nope")));
    }

    @Test
    void aGemUpstreamDeniesIsAMissNotAnOutage() throws Exception {
        // rubygems.org keeps gems in S3 behind its CDN, which answers 403 AccessDenied for a file it does not have
        String denied = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>AccessDenied</Code></Error>";
        upstream.stubFor(WireMock.get(WireMock.urlPathMatching("/(gems|quick)/.*nope.*")).willReturn(WireMock.aResponse()
                .withStatus(403).withHeader("Content-Type", "application/xml").withBody(denied)));
        stub("/info/rake", RAKE_INFO);

        for (int i = 0; i < 5; i++) {
            mvc.perform(get(URL + "/gems/nope-1.0.gem")).andExpect(status().isNotFound());
            mvc.perform(head(URL + "/quick/Marshal.4.8/nope-1.0.gemspec.rz")).andExpect(status().isNotFound());
        }
        // remembered like a 404, and the proxy stays usable for everything else
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/gems/nope-1.0.gem")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/quick/Marshal.4.8/nope-1.0.gemspec.rz")));
        assertThat(proxyService.status(repo).consecutiveFailures()).isZero();
        mvc.perform(get(URL + "/info/rake")).andExpect(status().isOk()).andExpect(content().string(RAKE_INFO));

        // a 403 for an index document still means upstream refuses us
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/info/secret")).willReturn(WireMock.aResponse().withStatus(403)));
        mvc.perform(get(URL + "/info/secret")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void proxiesRefuseWrites() throws Exception {
        mvc.perform(post(URL + "/api/v1/gems").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(gem("x", "1.0")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("is a proxy repository")));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }
}
