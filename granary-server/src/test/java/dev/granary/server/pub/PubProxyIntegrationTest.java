package dev.granary.server.pub;

import static dev.granary.server.pub.PubFixtures.archive;
import static dev.granary.server.pub.PubFixtures.json;
import static dev.granary.server.pub.PubFixtures.repositoryUrl;
import static dev.granary.server.pub.PubFixtures.sha256;
import static dev.granary.server.pub.PubFixtures.str;
import static dev.granary.server.pub.PubFixtures.upstreamListing;
import static dev.granary.server.pub.PubFixtures.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** A proxy in front of a fake pub.dev: caching, archive_url rewriting and the offline-first fallbacks. */
class PubProxyIntegrationTest extends PubClientSupport {

    private static final String REPO = "pub-dev";
    private static final String URL = repositoryUrl(REPO);
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static final byte[] PATH_191 = archive("path", "1.9.1");
    private static final byte[] PATH_190 = archive("path", "1.9.0");

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
        repo = proxy(REPO, "pub", upstream.baseUrl() + "/");
        proxyService.invalidate(repo);
    }

    private static Map<String, byte[]> pathVersions() {
        Map<String, byte[]> versions = new LinkedHashMap<>();
        versions.put("1.9.0", PATH_190);
        versions.put("1.9.1", PATH_191);
        return versions;
    }

    /** {@code GET /api/packages/<name>} the way pub.dev answers it (only for the pub v2 Accept header). */
    private static void stubListing(String name, String body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/packages/" + name))
                .withHeader("Accept", WireMock.equalTo(PubFixtures.ACCEPT))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json; charset=\"utf-8\"")
                        .withHeader("Cache-Control", "public, max-age=120")
                        .withBody(body)));
    }

    private static void stubArchive(String name, String version, byte[] bytes) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/archives/" + name + "-" + version + ".tar.gz"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/octet").withBody(bytes)));
    }

    private void primePath() throws Exception {
        stubListing("path", upstreamListing(upstream.baseUrl(), "path", null, pathVersions()));
        stubArchive("path", "1.9.1", PATH_191);
        mvc.perform(api(URL + "/api/packages/path")).andExpect(status().isOk());
        mvc.perform(download(URL + "/packages/path/versions/1.9.1.tar.gz")).andExpect(status().isOk());
    }

    @Test
    void listingIsCachedAndArchiveUrlsPointAtTheProxy() throws Exception {
        stubListing("path", upstreamListing(upstream.baseUrl(), "path", null, pathVersions()));

        JsonNode listing = json(mvc.perform(api(URL + "/api/packages/path"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.pub.v2+json;charset=utf-8"))
                .andReturn());
        assertThat(versions(listing)).containsExactly("1.9.0", "1.9.1");
        assertThat(str(listing, "latest", "version")).isEqualTo("1.9.1");
        assertThat(str(listing, "latest", "archive_url")).isEqualTo(URL + "/packages/path/versions/1.9.1.tar.gz");
        for (JsonNode v : listing.path("versions")) {
            assertThat(str(v, "archive_url")).startsWith(URL + "/packages/path/versions/");
            assertThat(str(v, "pubspec", "name")).isEqualTo("path");
            assertThat(str(v, "published")).isEqualTo("2024-11-14T14:32:01.384716Z");
        }
        assertThat(str(listing.path("versions").get(1), "archive_sha256")).isEqualTo(sha256(PATH_191));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/packages/path"))
                .withHeader("Accept", WireMock.equalTo(PubFixtures.ACCEPT)));

        // the raw upstream document is what is cached; the rewrite happens on the way out
        String cached = storage.find(repo, "api/packages/path").orElseThrow().readUtf8();
        assertThat(cached).contains(upstream.baseUrl() + "/api/archives/path-1.9.1.tar.gz");
        assertThat(storage.find(repo, "api/packages/path").orElseThrow().asset().isMetadata()).isTrue();

        // fresh metadata comes from the cache
        mvc.perform(api(URL + "/api/packages/path")).andExpect(status().isOk());
        mvc.perform(api(URL + "/api/packages/path/versions/1.9.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archive_url").value(URL + "/packages/path/versions/1.9.0.tar.gz"));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/packages/path")));
    }

    @Test
    void archivesAreFetchedOnceFromTheListedArchiveUrlAndCachedForever() throws Exception {
        stubListing("path", upstreamListing(upstream.baseUrl(), "path", null, pathVersions()));
        stubArchive("path", "1.9.1", PATH_191);
        mvc.perform(api(URL + "/api/packages/path")).andExpect(status().isOk());

        for (int i = 0; i < 2; i++) {
            byte[] body = mvc.perform(download(URL + "/packages/path/versions/1.9.1.tar.gz"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/gzip"))
                    .andExpect(header().string("ETag", "\"" + sha256(PATH_191) + "\""))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(body).isEqualTo(PATH_191);
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/archives/path-1.9.1.tar.gz")));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/packages/path/versions/1.9.1.tar.gz")));
        assertThat(storage.find(repo, "packages/path/versions/1.9.1.tar.gz")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(2);
        });
        // cached versions show up in the component browser
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "path", "1.9.1")).isPresent();
    }

    @Test
    void anArchiveAskedForFirstLooksUpItsListing() throws Exception {
        stubListing("path", upstreamListing(upstream.baseUrl(), "path", null, pathVersions()));
        stubArchive("path", "1.9.0", PATH_190);
        assertThat(mvc.perform(download(URL + "/packages/path/versions/1.9.0.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(PATH_190);
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/packages/path")));
    }

    @Test
    void upstreamRedirectsToStorageAreFollowed() throws Exception {
        stubListing("path", upstreamListing(upstream.baseUrl(), "path", null, pathVersions()));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/archives/path-1.9.1.tar.gz"))
                .willReturn(WireMock.aResponse().withStatus(302)
                        .withHeader("Location", upstream.baseUrl() + "/storage/pub-packages/path-1.9.1.tar.gz?sig=abc")));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/storage/pub-packages/path-1.9.1.tar.gz"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody(PATH_191)));
        assertThat(mvc.perform(download(URL + "/packages/path/versions/1.9.1.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(PATH_191);
    }

    @Test
    void anArchiveThatDoesNotMatchItsChecksumIsRefusedAndNotCached() throws Exception {
        stubListing("path", upstreamListing(upstream.baseUrl(), "path", null, pathVersions()));
        stubArchive("path", "1.9.1", archive("path", "6.6.6"));
        mvc.perform(download(URL + "/packages/path/versions/1.9.1.tar.gz"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("UpstreamError"))
                .andExpect(jsonPath("$.error.message").value(containsString("does not match its archive_sha256")));
        assertThat(storage.find(repo, "packages/path/versions/1.9.1.tar.gz")).isEmpty();
        // once upstream is fixed the next request succeeds
        stubArchive("path", "1.9.1", PATH_191);
        mvc.perform(download(URL + "/packages/path/versions/1.9.1.tar.gz")).andExpect(status().isOk());
    }

    @Test
    void metadataIsRevalidatedAfterItsMaxAgeAndStaleIsServedWhenUpstreamBreaks() throws Exception {
        Repository impatient = new Repository();
        impatient.setName("pub-dev-stale");
        impatient.setFormat("pub");
        impatient.setType(RepositoryType.PROXY);
        impatient.setRemoteUrl(upstream.baseUrl() + "/");
        impatient.setMetadataMaxAgeMinutes(0); // re-validate the listing on every request
        repositories.create(impatient);
        String url = repositoryUrl("pub-dev-stale");

        Map<String, byte[]> one = new LinkedHashMap<>();
        one.put("1.9.0", PATH_190);
        stubListing("path", upstreamListing(upstream.baseUrl(), "path", null, one));
        stubArchive("path", "1.9.0", PATH_190);
        mvc.perform(api(url + "/api/packages/path")).andExpect(jsonPath("$.latest.version").value("1.9.0"));
        mvc.perform(download(url + "/packages/path/versions/1.9.0.tar.gz")).andExpect(status().isOk());

        // a new upstream version is seen on the next request
        stubListing("path", upstreamListing(upstream.baseUrl(), "path", null, pathVersions()));
        mvc.perform(api(url + "/api/packages/path")).andExpect(jsonPath("$.latest.version").value("1.9.1"));
        upstream.verify(2, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/packages/path")));

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));
        JsonNode stale = json(mvc.perform(api(url + "/api/packages/path")).andExpect(status().isOk()).andReturn());
        assertThat(versions(stale)).containsExactly("1.9.0", "1.9.1");
        assertThat(str(stale, "latest", "archive_url")).isEqualTo(url + "/packages/path/versions/1.9.1.tar.gz");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/packages/path"))); // it did try
        assertThat(mvc.perform(download(url + "/packages/path/versions/1.9.0.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(PATH_190);
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/archives/path-1.9.0.tar.gz")));

        // something never cached while upstream is broken is a temporary 503, not a 404 the client would believe
        mvc.perform(api(url + "/api/packages/collection")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(download(url + "/packages/path/versions/1.9.1.tar.gz")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void offlineModeNeverTouchesUpstream() throws Exception {
        primePath();
        repositories.setOffline(REPO, true);
        upstream.resetAll();
        stubListing("collection", upstreamListing(upstream.baseUrl(), "collection", null, Map.of("1.19.1", archive("collection", "1.19.1"))));

        mvc.perform(api(URL + "/api/packages/path")).andExpect(status().isOk());
        mvc.perform(download(URL + "/packages/path/versions/1.9.1.tar.gz")).andExpect(status().isOk());
        mvc.perform(api(URL + "/api/packages/collection")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        // a version that is listed but was never downloaded is not available either
        mvc.perform(download(URL + "/packages/path/versions/1.9.0.tar.gz")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        repositories.setOffline(REPO, false);
        mvc.perform(api(URL + "/api/packages/collection")).andExpect(status().isOk());
    }

    @Test
    void theServerWideOfflineSwitchWorksTheSameWay() throws Exception {
        primePath();
        settings.setGlobalOffline(true);
        upstream.resetAll();
        mvc.perform(api(URL + "/api/packages/path")).andExpect(status().isOk());
        mvc.perform(download(URL + "/packages/path/versions/1.9.1.tar.gz")).andExpect(status().isOk());
        mvc.perform(api(URL + "/api/packages/http")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void upstream404IsRememberedByTheNegativeCache() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/packages/no_such_package"))
                .willReturn(WireMock.aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":\"NotFound\",\"message\":\"Could not find `package \\\"no_such_package\\\"`.\"}}")));
        for (int i = 0; i < 3; i++) {
            mvc.perform(api(URL + "/api/packages/no_such_package"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NotFound"))
                    .andExpect(jsonPath("$.error.message").value("Package no_such_package not found in repository 'pub-dev'"));
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/packages/no_such_package")));
    }

    @Test
    void advisoriesArePassedThroughAndCached() throws Exception {
        stubListing("http", upstreamListing(upstream.baseUrl(), "http", "2026-05-04T16:03:47.124153Z",
                Map.of("1.6.0", archive("http", "1.6.0"))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/packages/http/advisories"))
                .willReturn(WireMock.okJson(PubFixtures.upstreamAdvisories("http", "GHSA-4rgh-jx4f-qfcq"))));

        mvc.perform(api(URL + "/api/packages/http"))
                .andExpect(jsonPath("$.advisoriesUpdated").value("2026-05-04T16:03:47.124153Z"));
        mvc.perform(api(URL + "/api/packages/http/advisories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories[0].id").value("GHSA-4rgh-jx4f-qfcq"))
                .andExpect(jsonPath("$.advisoriesUpdated").value("2026-05-04T16:03:47.124153Z"));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/packages/http/advisories"))
                .withHeader("Accept", WireMock.equalTo(PubFixtures.ACCEPT)));

        repositories.setOffline(REPO, true);
        mvc.perform(api(URL + "/api/packages/http")).andExpect(jsonPath("$.advisoriesUpdated").exists());
        mvc.perform(api(URL + "/api/packages/http/advisories")).andExpect(jsonPath("$.advisories[0].id").value("GHSA-4rgh-jx4f-qfcq"));
    }

    @Test
    void advisoriesThatCannotBeServedAreNotAnnounced() throws Exception {
        stubListing("http", upstreamListing(upstream.baseUrl(), "http", "2026-05-04T16:03:47.124153Z",
                Map.of("1.6.0", archive("http", "1.6.0"))));
        mvc.perform(api(URL + "/api/packages/http")).andExpect(jsonPath("$.advisoriesUpdated").exists());
        // offline with the advisories never fetched: the client must not be sent after them
        repositories.setOffline(REPO, true);
        mvc.perform(api(URL + "/api/packages/http")).andExpect(jsonPath("$.advisoriesUpdated").doesNotExist());
    }

    @Test
    void advisoriesCanBeSwitchedOff() throws Exception {
        Repository quiet = new Repository();
        quiet.setName("pub-dev-quiet");
        quiet.setFormat("pub");
        quiet.setType(RepositoryType.PROXY);
        quiet.setRemoteUrl(upstream.baseUrl() + "/");
        quiet.setFormatSettings(Map.of("pub.advisories", "false"));
        repositories.create(quiet);
        stubListing("http", upstreamListing(upstream.baseUrl(), "http", "2026-05-04T16:03:47.124153Z",
                Map.of("1.6.0", archive("http", "1.6.0"))));

        mvc.perform(api(repositoryUrl("pub-dev-quiet") + "/api/packages/http")).andExpect(jsonPath("$.advisoriesUpdated").doesNotExist());
        mvc.perform(api(repositoryUrl("pub-dev-quiet") + "/api/packages/http/advisories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories.length()").value(0));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/packages/http/advisories")));
    }

    @Test
    void theUpstreamsLatestAndStatusFlagsAreKept() throws Exception {
        ObjectNode doc = (ObjectNode) PubFixtures.MAPPER.readTree(upstreamListing(upstream.baseUrl(), "path", null, pathVersions()));
        doc.put("isDiscontinued", true);
        doc.put("replacedBy", "path2");
        doc.set("latest", doc.path("versions").get(0).deepCopy());
        ((ObjectNode) doc.path("versions").get(1)).put("retracted", true);
        stubListing("path", doc.toString());
        mvc.perform(api(URL + "/api/packages/path"))
                .andExpect(jsonPath("$.latest.version").value("1.9.0"))
                .andExpect(jsonPath("$.isDiscontinued").value(true))
                .andExpect(jsonPath("$.replacedBy").value("path2"))
                .andExpect(jsonPath("$.versions[1].retracted").value(true));
    }

    @Test
    void anUnreadableUpstreamListingIsNotFound() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/packages/broken"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody("<html>maintenance</html>")));
        mvc.perform(api(URL + "/api/packages/broken")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NotFound"));
    }

    @Test
    void writesAreRefused() throws Exception {
        String token = adminToken();
        mvc.perform(withToken(api(URL + "/api/packages/versions/new"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(containsString("'pub-dev' is a proxy of " + upstream.baseUrl())));
        mvc.perform(withToken(multipart(HttpMethod.POST, URI.create(URL + "/api/packages/versions/newUpload"))
                        .file(new MockMultipartFile("file", "package.tar.gz", "application/octet-stream", archive("x", "1.0.0"))), token))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(withToken(delete(URL + "/api/packages/path/versions/1.9.1"), token)).andExpect(status().isMethodNotAllowed());
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }
}
