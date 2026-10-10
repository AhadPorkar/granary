package dev.granary.server.cargo;

import static dev.granary.server.cargo.CargoFixtures.api;
import static dev.granary.server.cargo.CargoFixtures.crate;
import static dev.granary.server.cargo.CargoFixtures.download;
import static dev.granary.server.cargo.CargoFixtures.errorDetail;
import static dev.granary.server.cargo.CargoFixtures.index;
import static dev.granary.server.cargo.CargoFixtures.indexFile;
import static dev.granary.server.cargo.CargoFixtures.indexLine;
import static dev.granary.server.cargo.CargoFixtures.json;
import static dev.granary.server.cargo.CargoFixtures.publish;
import static dev.granary.server.cargo.CargoFixtures.publishBody;
import static dev.granary.server.cargo.CargoFixtures.repositoryUrl;
import static dev.granary.server.cargo.CargoFixtures.versions;
import static org.assertj.core.api.Assertions.assertThat;
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
import dev.granary.core.storage.StoredContent;
import dev.granary.server.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A proxy of a fake crates.io: the sparse index below {@code /index/}, crate files below {@code /crates/} (like
 * static.crates.io) and the search API below {@code /api/v1/crates}.
 */
class CargoProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "cargo-proxy";
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
        repo = createProxy(REPO, null);
    }

    private Repository createProxy(String name, Integer metadataMaxAgeMinutes) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat("cargo");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(upstream.baseUrl() + "/index/");
        r.setMetadataMaxAgeMinutes(metadataMaxAgeMinutes);
        r.setFormatSettings(Map.of(
                "cargo.downloadUrl", upstream.baseUrl() + "/crates/{crate}/{crate}-{version}.crate",
                "cargo.apiUrl", upstream.baseUrl()));
        Repository created = repositories.create(r);
        proxyService.invalidate(created);
        return created;
    }

    private void stubIndex(String path, String body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/index/" + path))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody(body)));
    }

    private void stubCrate(String name, String version, byte[] crate) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/crates/" + name + "/" + name + "-" + version + ".crate"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/gzip").withBody(crate)));
    }

    private byte[] downloadOk(String url) throws Exception {
        return mvc.perform(download(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void configJsonKeepsEveryRequestOnGranary() throws Exception {
        mvc.perform(index(URL + "/index/config.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dl").value(repositoryUrl(REPO) + "/api/v1/crates"))
                .andExpect(jsonPath("$.api").value(repositoryUrl(REPO)));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void indexFilesAreFetchedFromTheSparseUpstreamAndCached() throws Exception {
        byte[] v17 = crate("itoa", "1.0.17");
        byte[] v18 = crate("itoa", "1.0.18");
        stubIndex("it/oa/itoa", indexFile(indexLine("itoa", "1.0.17", v17, false), indexLine("itoa", "1.0.18", v18, false)));

        MvcResult result = mvc.perform(index(URL + "/index/it/oa/itoa")).andExpect(status().isOk()).andReturn();
        assertThat(versions(result)).containsExactly("1.0.17", "1.0.18");
        // upstream lines are passed through as they are
        assertThat(result.getResponse().getContentAsString()).contains(indexLine("itoa", "1.0.18", v18, false));

        // fresh index files are answered from the cache
        mvc.perform(index(URL + "/index/it/oa/itoa")).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/index/it/oa/itoa")));
        StoredContent cached = storage.find(repo, "index/it/oa/itoa").orElseThrow();
        assertThat(cached.asset().isMetadata()).isTrue();

        // the short-name directories cargo uses are the ones asked upstream
        stubIndex("1/a", indexFile(indexLine("a", "0.1.0", crate("a", "0.1.0"), false)));
        stubIndex("3/s/syn", indexFile(indexLine("syn", "2.0.0", crate("syn", "2.0.0"), false)));
        mvc.perform(index(URL + "/index/1/a")).andExpect(status().isOk());
        mvc.perform(index(URL + "/index/3/s/syn")).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/index/3/s/syn")));
    }

    @Test
    void crateFilesAreDownloadedOnceAndServedFromTheCache() throws Exception {
        byte[] crate = crate("itoa", "1.0.18");
        stubIndex("it/oa/itoa", indexFile(indexLine("itoa", "1.0.18", crate, false)));
        stubCrate("itoa", "1.0.18", crate);

        assertThat(downloadOk(URL + "/api/v1/crates/itoa/1.0.18/download")).isEqualTo(crate);
        assertThat(downloadOk(URL + "/api/v1/crates/itoa/1.0.18/download")).isEqualTo(crate);
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/crates/itoa/itoa-1.0.18.crate")));

        StoredContent stored = storage.find(repo, "crates/itoa/itoa-1.0.18.crate").orElseThrow();
        assertThat(stored.asset().isMetadata()).isFalse();
        assertThat(stored.asset().getDownloadCount()).isEqualTo(2);
        assertThat(stored.asset().getComponentId()).isNotNull();
    }

    @Test
    void concurrentFirstRequestsDownloadOnceAndAllSucceed() throws Exception {
        byte[] crate = crate("itoa", "1.0.18");
        // a slow upstream, so every request arrives while the first download is still running
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/index/it/oa/itoa"))
                .willReturn(WireMock.aResponse().withStatus(200).withFixedDelay(400)
                        .withBody(indexFile(indexLine("itoa", "1.0.18", crate, false)))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/crates/itoa/itoa-1.0.18.crate"))
                .willReturn(WireMock.aResponse().withStatus(200).withFixedDelay(400).withBody(crate)));

        int clients = 8;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                boolean crateFile = i % 2 == 0;
                calls.add(() -> mvc.perform(crateFile ? download(URL + "/api/v1/crates/itoa/1.0.18/download")
                        : index(URL + "/index/it/oa/itoa")).andReturn());
            }
            for (Future<MvcResult> result : pool.invokeAll(calls)) {
                assertThat(result.get().getResponse().getStatus()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(downloadOk(URL + "/api/v1/crates/itoa/1.0.18/download")).isEqualTo(crate);
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/index/it/oa/itoa")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/crates/itoa/itoa-1.0.18.crate")));
        assertThat(storage.find(repo, "crates/itoa/itoa-1.0.18.crate").orElseThrow().asset().getComponentId()).isNotNull();
    }

    @Test
    void staleIndexFilesAreRevalidatedWithTheUpstreamEtag() throws Exception {
        createProxy("cargo-proxy-revalidate", 0); // every request revalidates
        String url = "/repository/cargo-proxy-revalidate";
        byte[] v17 = crate("itoa", "1.0.17");
        byte[] v18 = crate("itoa", "1.0.18");
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/index/it/oa/itoa")).atPriority(5)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("ETag", "\"one\"")
                        .withBody(indexFile(indexLine("itoa", "1.0.17", v17, false)))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/index/it/oa/itoa")).atPriority(1)
                .withHeader("If-None-Match", WireMock.equalTo("\"one\""))
                .willReturn(WireMock.aResponse().withStatus(304)));
        assertThat(versions(mvc.perform(index(url + "/index/it/oa/itoa")).andExpect(status().isOk()).andReturn()))
                .containsExactly("1.0.17");
        // unchanged upstream: a conditional request, answered 304, and the cached file is served
        assertThat(versions(mvc.perform(index(url + "/index/it/oa/itoa")).andExpect(status().isOk()).andReturn()))
                .containsExactly("1.0.17");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/index/it/oa/itoa"))
                .withHeader("If-None-Match", WireMock.equalTo("\"one\"")));

        // a new version upstream shows up on the next revalidation
        upstream.resetAll();
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/index/it/oa/itoa"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("ETag", "\"two\"")
                        .withBody(indexFile(indexLine("itoa", "1.0.17", v17, false), indexLine("itoa", "1.0.18", v18, false)))));
        assertThat(versions(mvc.perform(index(url + "/index/it/oa/itoa")).andExpect(status().isOk()).andReturn()))
                .containsExactly("1.0.17", "1.0.18");
    }

    @Test
    void anUncachedCrateWhileTheUpstreamIsDownIsA503() throws Exception {
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.aResponse()
                .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
        mvc.perform(download(URL + "/api/v1/crates/itoa/1.0.18/download"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(index(URL + "/index/it/oa/itoa"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void onlyVersionsTheIndexListsAreDownloaded() throws Exception {
        byte[] crate = crate("itoa", "1.0.18");
        stubIndex("it/oa/itoa", indexFile(indexLine("itoa", "1.0.18", crate, false)));
        stubCrate("itoa", "9.9.9", crate);

        mvc.perform(download(URL + "/api/v1/crates/itoa/9.9.9/download")).andExpect(status().isNotFound());
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathMatching("/crates/.*")));
    }

    @Test
    void aCrateWhoseChecksumDiffersFromTheIndexIsRefusedAndNotCached() throws Exception {
        byte[] crate = crate("itoa", "1.0.18");
        stubIndex("it/oa/itoa", indexFile(indexLine("itoa", "1.0.18", crate, false)));
        stubCrate("itoa", "1.0.18", "<html>captive portal</html>".getBytes());

        MvcResult result = mvc.perform(download(URL + "/api/v1/crates/itoa/1.0.18/download"))
                .andExpect(status().isBadGateway()).andReturn();
        assertThat(errorDetail(result)).contains("checksum");
        assertThat(storage.find(repo, "crates/itoa/itoa-1.0.18.crate")).isEmpty();

        // once the upstream serves the right bytes the download works
        stubCrate("itoa", "1.0.18", crate);
        assertThat(downloadOk(URL + "/api/v1/crates/itoa/1.0.18/download")).isEqualTo(crate);
    }

    @Test
    void unknownCratesAre404AndRememberedForAWhile() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathMatching("/index/.*")).willReturn(WireMock.notFound()));
        MvcResult result = mvc.perform(index(URL + "/index/no/ne/nonexistent")).andExpect(status().isNotFound()).andReturn();
        assertThat(errorDetail(result)).isEqualTo("crate `nonexistent` not found");
        mvc.perform(index(URL + "/index/no/ne/nonexistent")).andExpect(status().isNotFound());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/index/no/ne/nonexistent")));
    }

    @Test
    void theCacheIsServedWhenTheUpstreamBreaks() throws Exception {
        Repository stale = createProxy("cargo-proxy-stale", 0); // revalidate index files on every request
        String url = "/repository/cargo-proxy-stale";
        byte[] crate = crate("itoa", "1.0.18");
        stubIndex("it/oa/itoa", indexFile(indexLine("itoa", "1.0.18", crate, false)));
        stubCrate("itoa", "1.0.18", crate);
        mvc.perform(index(url + "/index/it/oa/itoa")).andExpect(status().isOk());
        downloadOk(url + "/api/v1/crates/itoa/1.0.18/download");

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        assertThat(versions(mvc.perform(index(url + "/index/it/oa/itoa")).andExpect(status().isOk()).andReturn()))
                .containsExactly("1.0.18");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/index/it/oa/itoa"))); // it did try
        assertThat(downloadOk(url + "/api/v1/crates/itoa/1.0.18/download")).isEqualTo(crate);
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathMatching("/crates/.*")));

        // something never cached is a temporary 503 that cargo retries, not a 404 it would act on
        mvc.perform(index(url + "/index/se/rd/serde"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        assertThat(stale.isProxy()).isTrue();
    }

    @Test
    void offlineModeServesTheCacheAndNeverTouchesTheUpstream() throws Exception {
        byte[] crate = crate("itoa", "1.0.18");
        stubIndex("it/oa/itoa", indexFile(indexLine("itoa", "1.0.18", crate, false)));
        stubCrate("itoa", "1.0.18", crate);
        mvc.perform(index(URL + "/index/it/oa/itoa")).andExpect(status().isOk());
        downloadOk(URL + "/api/v1/crates/itoa/1.0.18/download");
        upstream.resetAll();

        repositories.setOffline(REPO, true);
        mvc.perform(index(URL + "/index/it/oa/itoa")).andExpect(status().isOk());
        assertThat(downloadOk(URL + "/api/v1/crates/itoa/1.0.18/download")).isEqualTo(crate);
        mvc.perform(index(URL + "/index/se/rd/serde"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        mvc.perform(download(URL + "/api/v1/crates/serde/1.0.0/download"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        repositories.setOffline(REPO, false);

        settings.setGlobalOffline(true);
        assertThat(downloadOk(URL + "/api/v1/crates/itoa/1.0.18/download")).isEqualTo(crate);
        mvc.perform(index(URL + "/index/se/rd/serde")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void searchAsksTheUpstreamApiAndFallsBackToTheCache() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/crates"))
                .withQueryParam("q", WireMock.equalTo("itoa"))
                .willReturn(WireMock.okJson("{\"crates\":[{\"name\":\"itoa\",\"max_version\":\"1.0.18\","
                        + "\"description\":\"Fast integer primitive to string conversion\"}],\"meta\":{\"total\":12}}")));
        mvc.perform(api(get(URL + "/api/v1/crates").param("q", "itoa").param("per_page", "10"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crates[0].name").value("itoa"))
                .andExpect(jsonPath("$.crates[0].max_version").value("1.0.18"))
                .andExpect(jsonPath("$.meta.total").value(12));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/v1/crates"))
                .withQueryParam("per_page", WireMock.equalTo("10"))
                .withHeader("Accept", WireMock.containing("application/json")));

        // with the API down, the crates already in the cache are still found
        byte[] crate = crate("itoa", "1.0.18");
        stubIndex("it/oa/itoa", indexFile(indexLine("itoa", "1.0.18", crate, false)));
        mvc.perform(index(URL + "/index/it/oa/itoa")).andExpect(status().isOk());
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/crates")).willReturn(WireMock.serverError()));
        mvc.perform(api(get(URL + "/api/v1/crates").param("q", "itoa"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crates[0].name").value("itoa"))
                .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    void searchWithTheApiSetToNoneOnlyLooksAtTheCache() throws Exception {
        String body = "{\"name\":\"cargo-proxy-quiet\",\"format\":\"cargo\",\"type\":\"PROXY\",\"remoteUrl\":\""
                + upstream.baseUrl() + "/index/\",\"formatSettings\":{\"cargo.apiUrl\":\"none\"}}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/repositories")
                        .header("Authorization", adminBasic())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        String url = "/repository/cargo-proxy-quiet";
        byte[] crate = crate("itoa", "1.0.18");
        stubIndex("it/oa/itoa", indexFile(indexLine("itoa", "1.0.18", crate, false)));
        mvc.perform(index(url + "/index/it/oa/itoa")).andExpect(status().isOk());

        mvc.perform(api(get(url + "/api/v1/crates").param("q", "itoa"), null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crates[0].name").value("itoa"))
                .andExpect(jsonPath("$.crates[0].max_version").value("1.0.18"))
                .andExpect(jsonPath("$.meta.total").value(1));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathMatching("/api/.*")));
    }

    @Test
    void proxiesAreReadOnly() throws Exception {
        String token = tokens.issue(users.require(ADMIN), "cargo", null).secret();
        MvcResult result = mvc.perform(publish(URL, token, publishBody("acme", "1.0.0")))
                .andExpect(status().isMethodNotAllowed()).andReturn();
        assertThat(errorDetail(result)).contains("proxy repository");
        mvc.perform(api(delete(URL + "/api/v1/crates/itoa/1.0.18/yank"), token)).andExpect(status().isMethodNotAllowed());
        mvc.perform(api(get(URL + "/api/v1/crates/itoa/owners"), token)).andExpect(status().isNotFound());
        assertThat(json(result).path("errors").isArray()).isTrue();
    }
}
