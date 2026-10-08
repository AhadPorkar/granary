package dev.granary.server.npm;

import static dev.granary.server.npm.NpmFixtures.object;
import static dev.granary.server.npm.NpmFixtures.publishBody;
import static dev.granary.server.npm.NpmFixtures.repositoryUrl;
import static dev.granary.server.npm.NpmFixtures.str;
import static dev.granary.server.npm.NpmFixtures.tarball;
import static dev.granary.server.npm.NpmFixtures.upstreamPackument;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.node.ObjectNode;

/** A proxy in front of a fake registry.npmjs.org: caching, rewriting and the offline-first fallbacks. */
class NpmProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "npm-proxy";
    private static final String URL = "/repository/" + REPO;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

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
        repo = proxy(REPO, "npm", upstream.baseUrl() + "/");
        proxyService.invalidate(repo);
    }

    @Test
    void packumentIsCachedAndTarballUrlsRewritten() throws Exception {
        stubPackument("lodash", "4.17.20", "4.17.21");

        ObjectNode doc = object(mvc.perform(get(URL + "/lodash")).andExpect(status().isOk()).andReturn());
        assertThat(str(doc, "name")).isEqualTo("lodash");
        assertThat(str(doc, "dist-tags", "latest")).isEqualTo("4.17.21");
        assertThat(str(doc, "versions", "4.17.21", "dist", "tarball")).isEqualTo(repositoryUrl(REPO) + "/lodash/-/lodash-4.17.21.tgz");
        assertThat(str(doc, "versions", "4.17.20", "dist", "tarball")).isEqualTo(repositoryUrl(REPO) + "/lodash/-/lodash-4.17.20.tgz");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/lodash"))
                .withHeader("Accept", WireMock.equalTo("application/json")));

        // the raw upstream document is what is cached; the rewrite happens on the way out
        String cached = storage.find(repo, "lodash").orElseThrow().readUtf8();
        assertThat(cached).contains(upstream.baseUrl() + "/lodash/-/lodash-4.17.21.tgz");
        assertThat(storage.find(repo, "lodash").orElseThrow().asset().isMetadata()).isTrue();

        // fresh metadata is served from the cache without another upstream round trip
        mvc.perform(get(URL + "/lodash")).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/lodash")));
    }

    @Test
    void tarballIsFetchedOnceAndCachedForever() throws Exception {
        stubPackument("lodash", "4.17.21");
        byte[] tgz = tarball("lodash-4.17.21");
        stubTarball("/lodash/-/lodash-4.17.21.tgz", tgz);

        for (int i = 0; i < 2; i++) {
            assertThat(mvc.perform(get(URL + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tgz);
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/lodash/-/lodash-4.17.21.tgz")));
        assertThat(storage.find(repo, "lodash/-/lodash-4.17.21.tgz")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(2);
        });
    }

    @Test
    void staleCacheIsServedWhenUpstreamBreaks() throws Exception {
        Repository impatient = new Repository();
        impatient.setName("npm-proxy-stale");
        impatient.setFormat("npm");
        impatient.setType(RepositoryType.PROXY);
        impatient.setRemoteUrl(upstream.baseUrl() + "/");
        impatient.setMetadataMaxAgeMinutes(0); // re-validate every packument on every request
        repositories.create(impatient);
        String url = "/repository/npm-proxy-stale";

        stubPackument("lodash", "4.17.21");
        byte[] tgz = tarball("lodash-4.17.21");
        stubTarball("/lodash/-/lodash-4.17.21.tgz", tgz);
        mvc.perform(get(url + "/lodash")).andExpect(status().isOk());
        mvc.perform(get(url + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk());

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        ObjectNode doc = object(mvc.perform(get(url + "/lodash")).andExpect(status().isOk()).andReturn());
        assertThat(str(doc, "versions", "4.17.21", "dist", "tarball")).isEqualTo(repositoryUrl("npm-proxy-stale") + "/lodash/-/lodash-4.17.21.tgz");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/lodash"))); // it did try
        assertThat(mvc.perform(get(url + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tgz);
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/lodash/-/lodash-4.17.21.tgz")));

        // something never cached while upstream is broken is a temporary 503, not a 404 npm would remember
        mvc.perform(get(url + "/unknown")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void offlineModeNeverTouchesUpstream() throws Exception {
        stubPackument("lodash", "4.17.21");
        stubTarball("/lodash/-/lodash-4.17.21.tgz", tarball("x"));
        mvc.perform(get(URL + "/lodash")).andExpect(status().isOk());
        mvc.perform(get(URL + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk());

        repositories.setOffline(REPO, true);
        upstream.resetAll();
        stubPackument("express", "4.0.0");

        mvc.perform(get(URL + "/lodash")).andExpect(status().isOk());
        mvc.perform(get(URL + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk());
        mvc.perform(get(URL + "/express")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/express")));

        repositories.setOffline(REPO, false);
        mvc.perform(get(URL + "/express")).andExpect(status().isOk());
    }

    @Test
    void upstream404IsRememberedByTheNegativeCache() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/nope"))
                .willReturn(WireMock.aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Not found\"}")));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(URL + "/nope")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("package not found: nope"));
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/nope")));
        mvc.perform(get(URL + "/nope/-/nope-1.0.0.tgz")).andExpect(status().isNotFound());
    }

    @Test
    void scopedPackagesAreRequestedUpstreamWithTheirScope() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/@babel/core"))
                .willReturn(WireMock.okJson(upstreamPackument(upstream.baseUrl(), "@babel/core", "7.0.0"))));
        stubTarball("/@babel/core/-/core-7.0.0.tgz", tarball("core"));

        ObjectNode doc = object(mvc.perform(get(URI.create(URL + "/@babel%2Fcore"))).andExpect(status().isOk()).andReturn());
        assertThat(str(doc, "versions", "7.0.0", "dist", "tarball")).isEqualTo(repositoryUrl(REPO) + "/@babel/core/-/core-7.0.0.tgz");
        assertThat(mvc.perform(get(URL + "/@babel/core/-/core-7.0.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tarball("core"));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/@babel/core")));
        mvc.perform(get(URI.create(URL + "/-/package/@babel%2Fcore/dist-tags"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.latest").value("7.0.0"));
    }

    @Test
    void tarballIsFetchedFromWhereverThePackumentSaysOnTheUpstreamHost() throws Exception {
        String odd = upstream.baseUrl() + "/download/@acme/ui/1.0.0/ui-1.0.0.tgz";
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/@acme/ui")).willReturn(WireMock.okJson(
                upstreamPackument(upstream.baseUrl(), "@acme/ui", "1.0.0").replace(upstream.baseUrl() + "/@acme/ui/-/ui-1.0.0.tgz", odd))));
        stubTarball("/download/@acme/ui/1.0.0/ui-1.0.0.tgz", tarball("odd"));

        ObjectNode doc = object(mvc.perform(get(URL + "/@acme/ui")).andExpect(status().isOk()).andReturn());
        assertThat(str(doc, "versions", "1.0.0", "dist", "tarball")).isEqualTo(repositoryUrl(REPO) + "/@acme/ui/-/ui-1.0.0.tgz");
        assertThat(mvc.perform(get(URL + "/@acme/ui/-/ui-1.0.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tarball("odd"));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/download/@acme/ui/1.0.0/ui-1.0.0.tgz")));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/@acme/ui/-/ui-1.0.0.tgz")));
    }

    @Test
    void writesAreRefusedWith405() throws Exception {
        mvc.perform(put(URL + "/demo").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("demo", "1.0.0", tarball("x"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("proxy repository")));
        mvc.perform(put(URL + "/-/package/demo/dist-tags/beta").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content("\"1.0.0\""))
                .andExpect(status().isMethodNotAllowed());
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void searchIsForwardedUpstreamAndEmptyWhenOffline() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/-/v1/search"))
                .withQueryParam("text", WireMock.equalTo("lodash"))
                .withQueryParam("size", WireMock.equalTo("5"))
                .withQueryParam("from", WireMock.equalTo("0"))
                .willReturn(WireMock.okJson("{\"objects\":[{\"package\":{\"name\":\"lodash\",\"version\":\"4.17.21\"}}],\"total\":1}")));

        mvc.perform(get(URL + "/-/v1/search").param("text", "lodash").param("size", "5").param("from", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.objects[0].package.name").value("lodash"));
        assertThat(storage.list(repo, "-/")).isEmpty();

        repositories.setOffline(REPO, true);
        mvc.perform(get(URL + "/-/v1/search").param("text", "lodash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.objects").isEmpty());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/-/v1/search")));
    }

    private void stubPackument(String name, String... versions) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/" + name))
                .willReturn(WireMock.okJson(upstreamPackument(upstream.baseUrl(), name, versions))));
    }

    private static void stubTarball(String path, byte[] bytes) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(bytes)));
    }
}
