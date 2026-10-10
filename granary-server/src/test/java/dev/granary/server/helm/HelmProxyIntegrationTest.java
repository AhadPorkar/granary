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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import dev.granary.core.storage.StoredContent;
import dev.granary.server.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A proxy of a fake public chart repository: the index is cached as metadata and rewritten so every download goes
 * through Granary, archives are fetched once from wherever the upstream index says, and the cache keeps working
 * when the upstream does not.
 */
class HelmProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "helm-proxy";
    private static final String URL = "/repository/" + REPO;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static final byte[] DEMO_1 = chart("demo", "1.0.0");
    private static final byte[] DEMO_2 = chart("demo", "2.0.0");

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
        repo = createProxy(REPO, null);
    }

    private Repository createProxy(String name, Integer metadataMaxAgeMinutes) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat("helm");
        r.setType(RepositoryType.PROXY);
        // no trailing slash on purpose: helm repo add accepts both
        r.setRemoteUrl(upstream.baseUrl() + "/stable");
        r.setMetadataMaxAgeMinutes(metadataMaxAgeMinutes);
        Repository created = repositories.create(r);
        proxyService.invalidate(created);
        return created;
    }

    /** demo 1.0.0 next to the index (a relative URL), demo 2.0.0 on a release download host (an absolute URL). */
    private void stubUpstream() {
        stubIndex(upstreamIndex(
                upstreamChart("demo", "1.0.0", "demo-1.0.0.tgz", DEMO_1),
                upstreamChart("demo", "2.0.0", upstream.baseUrl() + "/releases/download/demo-2.0.0/demo-2.0.0.tgz", DEMO_2)));
        stubFile("/stable/demo-1.0.0.tgz", DEMO_1);
        stubFile("/releases/download/demo-2.0.0/demo-2.0.0.tgz", DEMO_2);
    }

    private void stubIndex(String yaml) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/stable/index.yaml"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/yaml").withBody(yaml)));
    }

    private void stubFile(String path, byte[] body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(body)));
    }

    private byte[] downloadOk(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void theIndexIsCachedAndPointsEveryDownloadAtGranary() throws Exception {
        stubUpstream();

        Map<String, Object> index = yaml(mvc.perform(index(URL)).andExpect(status().isOk()).andReturn());
        assertThat(versionNumbers(index, "demo")).containsExactly("2.0.0", "1.0.0");
        assertThat(url(versions(index, "demo").get(0))).isEqualTo(repositoryUrl(REPO) + "/charts/demo-2.0.0.tgz");
        assertThat(url(versions(index, "demo").get(1))).isEqualTo(repositoryUrl(REPO) + "/charts/demo-1.0.0.tgz");
        assertThat(versions(index, "demo").get(0)).containsEntry("digest", sha256(DEMO_2));
        assertThat(index.get("serverInfo")).isEqualTo(Map.of("contextPath", "/repository/helm-proxy"));
        assertThat(index.toString()).doesNotContain(upstream.baseUrl());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/index.yaml"))
                .withHeader("Accept", WireMock.containing("application/x-yaml")));

        // the raw upstream document is cached as metadata; a fresh copy is served without asking again
        StoredContent cached = storage.find(repo, "index.yaml").orElseThrow();
        assertThat(cached.asset().isMetadata()).isTrue();
        assertThat(cached.readUtf8()).contains(upstream.baseUrl() + "/releases/download/");
        mvc.perform(index(URL)).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/index.yaml")));

        // the ChartMuseum read API works on proxies as well
        mvc.perform(get(URL + "/api/charts/demo/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2.0.0"))
                .andExpect(jsonPath("$.urls[0]").value(repositoryUrl(REPO) + "/charts/demo-2.0.0.tgz"));
    }

    @Test
    void archivesAreFetchedOnceFromWhereverTheIndexSays() throws Exception {
        stubUpstream();
        mvc.perform(index(URL)).andExpect(status().isOk());

        assertThat(downloadOk(URL + "/charts/demo-1.0.0.tgz")).isEqualTo(DEMO_1);
        assertThat(downloadOk(URL + "/charts/demo-1.0.0.tgz")).isEqualTo(DEMO_1);
        assertThat(downloadOk(URL + "/charts/demo-2.0.0.tgz")).isEqualTo(DEMO_2);
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/demo-1.0.0.tgz")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/releases/download/demo-2.0.0/demo-2.0.0.tgz")));

        StoredContent stored = storage.find(repo, "charts/demo-1.0.0.tgz").orElseThrow();
        assertThat(stored.asset().isMetadata()).isFalse();
        assertThat(stored.asset().getDownloadCount()).isEqualTo(2);
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "demo", "1.0.0")).isPresent();

        // provenance files live next to their archive upstream
        stubFile("/stable/demo-1.0.0.tgz.prov", "signed".getBytes());
        assertThat(downloadOk(URL + "/charts/demo-1.0.0.tgz.prov")).isEqualTo("signed".getBytes());

        // a chart is fetched even when the index was never requested through Granary (helm keeps its own cache)
        Repository other = createProxy("helm-proxy-cold", null);
        assertThat(downloadOk("/repository/helm-proxy-cold/charts/demo-2.0.0.tgz")).isEqualTo(DEMO_2);
        assertThat(other.isProxy()).isTrue();
    }

    @Test
    void onlyChartsTheIndexListsAreFetched() throws Exception {
        stubUpstream();
        stubFile("/stable/demo-9.9.9.tgz", DEMO_1);
        mvc.perform(get(URL + "/charts/demo-9.9.9.tgz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("chart not found: demo-9.9.9.tgz"));
        mvc.perform(get(URL + "/api/charts/demo/9.9.9")).andExpect(status().isNotFound());
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/demo-9.9.9.tgz")));
    }

    @Test
    void anErrorPageServedInsteadOfAnArchiveIsNotCached() throws Exception {
        stubUpstream();
        stubFile("/stable/demo-1.0.0.tgz", "<html>captive portal</html>".getBytes());
        mvc.perform(get(URL + "/charts/demo-1.0.0.tgz"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(containsString("not a gzip-compressed chart archive")));
        assertThat(storage.find(repo, "charts/demo-1.0.0.tgz")).isEmpty();

        stubFile("/stable/demo-1.0.0.tgz", DEMO_1);
        assertThat(downloadOk(URL + "/charts/demo-1.0.0.tgz")).isEqualTo(DEMO_1);
    }

    @Test
    void anArchiveWhoseDigestDiffersFromTheIndexIsServedAsHelmWouldAcceptIt() throws Exception {
        // the helm/examples repository is like this: its release asset was rebuilt after the index was generated
        stubIndex(upstreamIndex(upstreamChart("demo", "1.0.0", "demo-1.0.0.tgz", DEMO_2)));
        stubFile("/stable/demo-1.0.0.tgz", DEMO_1);
        assertThat(downloadOk(URL + "/charts/demo-1.0.0.tgz")).isEqualTo(DEMO_1);
    }

    @Test
    void theIndexIsRevalidatedWithItsETag() throws Exception {
        createProxy("helm-proxy-eager", 0);
        String url = "/repository/helm-proxy-eager";
        String first = upstreamIndex(upstreamChart("demo", "1.0.0", "demo-1.0.0.tgz", DEMO_1));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/stable/index.yaml")).atPriority(5)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("ETag", "\"v1\"").withBody(first)));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/stable/index.yaml")).atPriority(1)
                .withHeader("If-None-Match", WireMock.equalTo("\"v1\""))
                .willReturn(WireMock.aResponse().withStatus(304)));

        assertThat(versionNumbers(yaml(mvc.perform(index(url)).andExpect(status().isOk()).andReturn()), "demo")).containsExactly("1.0.0");
        assertThat(versionNumbers(yaml(mvc.perform(index(url)).andExpect(status().isOk()).andReturn()), "demo")).containsExactly("1.0.0");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/index.yaml"))
                .withHeader("If-None-Match", WireMock.equalTo("\"v1\"")));

        // a new upstream release shows up on the next revalidation
        upstream.resetAll();
        stubIndex(upstreamIndex(
                upstreamChart("demo", "1.0.0", "demo-1.0.0.tgz", DEMO_1),
                upstreamChart("demo", "2.0.0", "demo-2.0.0.tgz", DEMO_2)));
        assertThat(versionNumbers(yaml(mvc.perform(index(url)).andExpect(status().isOk()).andReturn()), "demo"))
                .containsExactly("2.0.0", "1.0.0");
    }

    @Test
    void theCacheIsServedWhenTheUpstreamBreaks() throws Exception {
        createProxy("helm-proxy-stale", 0);
        String url = "/repository/helm-proxy-stale";
        stubUpstream();
        mvc.perform(index(url)).andExpect(status().isOk());
        downloadOk(url + "/charts/demo-1.0.0.tgz");

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        assertThat(versionNumbers(yaml(mvc.perform(index(url)).andExpect(status().isOk()).andReturn()), "demo"))
                .containsExactly("2.0.0", "1.0.0");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/index.yaml"))); // it did try
        assertThat(downloadOk(url + "/charts/demo-1.0.0.tgz")).isEqualTo(DEMO_1);
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/demo-1.0.0.tgz")));

        // a chart that was never cached is a temporary 503 that helm reports, not a 404
        mvc.perform(get(url + "/charts/demo-2.0.0.tgz"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        // and so is the index of a proxy that never had one
        createProxy("helm-proxy-empty", null);
        mvc.perform(index("/repository/helm-proxy-empty"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void offlineModeServesTheCacheAndNeverTouchesTheUpstream() throws Exception {
        stubUpstream();
        mvc.perform(index(URL)).andExpect(status().isOk());
        downloadOk(URL + "/charts/demo-1.0.0.tgz");
        upstream.resetAll();

        repositories.setOffline(REPO, true);
        mvc.perform(index(URL)).andExpect(status().isOk());
        assertThat(downloadOk(URL + "/charts/demo-1.0.0.tgz")).isEqualTo(DEMO_1);
        mvc.perform(get(URL + "/charts/demo-2.0.0.tgz"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        repositories.setOffline(REPO, false);

        settings.setGlobalOffline(true);
        assertThat(downloadOk(URL + "/charts/demo-1.0.0.tgz")).isEqualTo(DEMO_1);
        mvc.perform(get(URL + "/charts/demo-2.0.0.tgz"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        createProxy("helm-proxy-never", null);
        mvc.perform(index("/repository/helm-proxy-never"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void aMissingUpstreamIndexIsRememberedByTheNegativeCache() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/stable/index.yaml")).willReturn(WireMock.notFound()));
        for (int i = 0; i < 3; i++) {
            mvc.perform(index(URL))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("the index of 'helm-proxy' is not available"));
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/index.yaml")));
    }

    @Test
    void somethingThatIsNotAChartIndexIsABadGateway() throws Exception {
        stubIndex("<html><body>Sign in to continue</body></html>");
        mvc.perform(index(URL))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(containsString("did not serve a valid index.yaml")));
    }

    @Test
    void aSignedRemoteUrlKeepsItsQueryOnTheIndexAndOnRelativeChartUrls() throws Exception {
        // storage-backed chart repositories are added with a signed URL; helm requests <path>/index.yaml?<query>
        Repository signed = new Repository();
        signed.setName("helm-proxy-signed");
        signed.setFormat("helm");
        signed.setType(RepositoryType.PROXY);
        signed.setRemoteUrl(upstream.baseUrl() + "/signed/charts?sv=2024&sig=abc%2Fdef");
        proxyService.invalidate(repositories.create(signed));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/signed/charts/index.yaml"))
                .withQueryParam("sig", WireMock.equalTo("abc/def"))
                .willReturn(WireMock.ok(upstreamIndex(upstreamChart("demo", "1.0.0", "demo-1.0.0.tgz", DEMO_1)))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/signed/charts/demo-1.0.0.tgz"))
                .withQueryParam("sig", WireMock.equalTo("abc/def"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody(DEMO_1)));

        String url = "/repository/helm-proxy-signed";
        Map<String, Object> index = yaml(mvc.perform(index(url)).andExpect(status().isOk()).andReturn());
        assertThat(url(versions(index, "demo").getFirst())).isEqualTo(repositoryUrl("helm-proxy-signed") + "/charts/demo-1.0.0.tgz");
        assertThat(index.toString()).doesNotContain("sig=");
        assertThat(downloadOk(url + "/charts/demo-1.0.0.tgz")).isEqualTo(DEMO_1);
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/signed/charts/index.yaml?sv=2024&sig=abc%2Fdef")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/signed/charts/demo-1.0.0.tgz?sv=2024&sig=abc%2Fdef")));
    }

    @Test
    void upstreamCredentialsAreSent() throws Exception {
        repo.setRemoteUsername("mirror");
        repo.setRemotePassword("s3cret");
        repositories.update(REPO, repo);
        stubUpstream();
        mvc.perform(index(URL)).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/stable/index.yaml"))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("mirror", "s3cret")));
    }

    @Test
    void proxiesAreReadOnly() throws Exception {
        String auth = basic(ADMIN, tokens.issue(users.require(ADMIN), "helm", null).secret());
        mvc.perform(upload(URL, auth, chart("demo", "3.0.0")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("'helm-proxy' is a proxy repository, you cannot upload to it"));
        mvc.perform(put(URL + "/charts/demo-3.0.0.tgz").header("Authorization", auth).content(chart("demo", "3.0.0")))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(URL + "/api/charts/demo/1.0.0").header("Authorization", auth)).andExpect(status().isMethodNotAllowed());
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }
}
