package dev.granary.server.npm;

import static dev.granary.server.npm.NpmFixtures.publishBody;
import static dev.granary.server.npm.NpmFixtures.tarball;
import static dev.granary.server.npm.NpmFixtures.upstreamPackument;
import static dev.granary.server.testsupport.ComponentApi.gav;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import dev.granary.server.testsupport.ComponentApi;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** Tarballs an npm proxy caches are components (scope, name, version) like published ones; packuments are not. */
class NpmProxyComponentsIntegrationTest extends IntegrationTest {

    private static final String REPO = "npm-proxy";
    private static final String URL = "/repository/" + REPO;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    private Repository repo;
    private ComponentApi components;

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
        components = new ComponentApi(mvc, adminBearer());
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/lodash"))
                .willReturn(WireMock.okJson(upstreamPackument(upstream.baseUrl(), "lodash", "4.17.20", "4.17.21"))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/@babel/core"))
                .willReturn(WireMock.okJson(upstreamPackument(upstream.baseUrl(), "@babel/core", "7.26.0"))));
        stubTarball("/lodash/-/lodash-4.17.21.tgz", tarball("lodash-4.17.21"));
        stubTarball("/lodash/-/lodash-4.17.20.tgz", tarball("lodash-4.17.20"));
        stubTarball("/@babel/core/-/core-7.26.0.tgz", tarball("core-7.26.0"));
    }

    @Test
    void cachedTarballsAreComponentsAndPackumentsAreNot() throws Exception {
        mvc.perform(get(URL + "/lodash")).andExpect(status().isOk());
        mvc.perform(get(URL + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk());
        mvc.perform(get(URL + "/@babel/core")).andExpect(status().isOk());
        mvc.perform(get(URL + "/@babel/core/-/core-7.26.0.tgz")).andExpect(status().isOk());

        List<JsonNode> all = components.list(REPO);
        // scope as namespace without the @, like a published scoped package
        assertThat(all).extracting(ComponentApi::gav).containsExactly(":lodash:4.17.21", "babel:core:7.26.0");
        assertThat(components.assetPaths(REPO, all.get(0))).containsExactly("lodash/-/lodash-4.17.21.tgz");
        assertThat(components.assetPaths(REPO, all.get(1))).containsExactly("@babel/core/-/core-7.26.0.tgz");
        assertThat(storage.find(repo, "lodash").orElseThrow().asset().getComponentId()).isNull();
        assertThat(storage.find(repo, "@babel/core").orElseThrow().asset().getComponentId()).isNull();
    }

    @Test
    void aTarballFetchedBeforeItsPackumentIsNamedByItsFileName() throws Exception {
        mvc.perform(get(URL + "/lodash/-/lodash-4.17.20.tgz")).andExpect(status().isOk());

        assertThat(gav(components.single(REPO))).isEqualTo(":lodash:4.17.20");
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/lodash")));
    }

    @Test
    void repeatedAndConcurrentDownloadsKeepOneComponent() throws Exception {
        mvc.perform(get(URL + "/lodash")).andExpect(status().isOk());
        List<Integer> statuses = ComponentApi.concurrently(8, i -> () ->
                mvc.perform(get(URL + "/lodash/-/lodash-4.17.21.tgz")).andReturn().getResponse().getStatus());
        mvc.perform(get(URL + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk());

        assertThat(statuses).containsOnly(200);
        assertThat(gav(components.single(REPO))).isEqualTo(":lodash:4.17.21");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/lodash/-/lodash-4.17.21.tgz")));
    }

    @Test
    void searchFindsCachedPackagesByNameAndScope() throws Exception {
        mvc.perform(get(URL + "/@babel/core/-/core-7.26.0.tgz")).andExpect(status().isOk());

        assertThat(components.search("core")).extracting(ComponentApi::gav).containsExactly("babel:core:7.26.0");
        assertThat(components.search("babel")).extracting(c -> c.path("repository").asString()).containsExactly(REPO);
        assertThat(components.list(REPO, "lodash")).isEmpty();
    }

    @Test
    void deletingTheComponentRemovesTheTarballWhichComesBackOnlineButNotOffline() throws Exception {
        mvc.perform(get(URL + "/lodash")).andExpect(status().isOk());
        mvc.perform(get(URL + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk());

        assertThat(components.delete(REPO, components.single(REPO))).isEqualTo(1);
        assertThat(storage.find(repo, "lodash/-/lodash-4.17.21.tgz")).isEmpty();
        assertThat(storage.find(repo, "lodash")).isPresent();

        repositories.setOffline(REPO, true);
        mvc.perform(get(URL + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        mvc.perform(get(URL + "/lodash")).andExpect(status().isOk());
        assertThat(components.list(REPO)).isEmpty();

        repositories.setOffline(REPO, false);
        assertThat(mvc.perform(get(URL + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tarball("lodash-4.17.21"));
        assertThat(gav(components.single(REPO))).isEqualTo(":lodash:4.17.21");
        upstream.verify(2, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/lodash/-/lodash-4.17.21.tgz")));
    }

    @Test
    void tarballsCachedBeforeComponentsWereRecordedAreLinkedWhenServed() throws Exception {
        storage.store(repo, "lodash/-/lodash-4.17.21.tgz", new ByteArrayInputStream(tarball("old")), "application/octet-stream",
                Asset.Origin.CACHED, false);
        repositories.setOffline(REPO, true);

        assertThat(mvc.perform(get(URL + "/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tarball("old"));

        assertThat(gav(components.single(REPO))).isEqualTo(":lodash:4.17.21");
        upstream.verify(0, WireMock.getRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void downloadsThroughAGroupAreRecordedInTheProxyMember() throws Exception {
        hosted("npm-hosted", "npm");
        group("npm-all", "npm", "npm-hosted", REPO);
        mvc.perform(put("/repository/npm-hosted/own").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody("own", "1.0.0", tarball("own"))))
                .andExpect(status().isCreated());

        mvc.perform(get("/repository/npm-all/lodash")).andExpect(status().isOk());
        mvc.perform(get("/repository/npm-all/lodash/-/lodash-4.17.21.tgz")).andExpect(status().isOk());
        mvc.perform(get("/repository/npm-all/own/-/own-1.0.0.tgz")).andExpect(status().isOk());

        assertThat(gav(components.single(REPO))).isEqualTo(":lodash:4.17.21");
        assertThat(gav(components.single("npm-hosted"))).isEqualTo(":own:1.0.0");
        assertThat(components.list("npm-all")).isEmpty();
    }

    private static void stubTarball(String path, byte[] bytes) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(bytes)));
    }
}
