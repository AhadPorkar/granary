package dev.granary.server.swift;

import static dev.granary.server.swift.SwiftFixtures.ACCEPT_JSON;
import static dev.granary.server.swift.SwiftFixtures.ACCEPT_SWIFT;
import static dev.granary.server.swift.SwiftFixtures.ACCEPT_ZIP;
import static dev.granary.server.swift.SwiftFixtures.MANIFEST;
import static dev.granary.server.swift.SwiftFixtures.MANIFEST_6;
import static dev.granary.server.swift.SwiftFixtures.archive;
import static dev.granary.server.swift.SwiftFixtures.getJson;
import static dev.granary.server.swift.SwiftFixtures.getSwift;
import static dev.granary.server.swift.SwiftFixtures.getZip;
import static dev.granary.server.swift.SwiftFixtures.json;
import static dev.granary.server.swift.SwiftFixtures.publish;
import static dev.granary.server.swift.SwiftFixtures.repositoryUrl;
import static dev.granary.server.swift.SwiftFixtures.sha256;
import static dev.granary.server.swift.SwiftFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.dao.AssetDao;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.format.swift.SwiftLayout;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** A proxy of another Swift registry (WireMock speaking the registry protocol): caching and the offline-first fallbacks. */
class SwiftProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "swift-proxy";
    private static final String URL = "/repository/" + REPO;
    private static final String BASE = repositoryUrl(REPO);
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final byte[] ZIP = archive("acme.demo", "upstream", false);

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private ComponentDao components;

    @Autowired
    private AssetDao assets;

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
        repo = proxy(REPO, "swift", upstream.baseUrl() + "/");
        proxyService.invalidate(repo);
    }

    private Repository impatientProxy(String name) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat("swift");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(upstream.baseUrl() + "/");
        r.setMetadataMaxAgeMinutes(0); // re-validate release lists and metadata on every request
        return repositories.create(r);
    }

    // --- upstream registry -----------------------------------------------------------------------------

    private static String upstreamReleases(String... versions) {
        StringBuilder sb = new StringBuilder("{\"releases\":{");
        for (int i = 0; i < versions.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(versions[i]).append("\":{\"url\":\"").append(upstream.baseUrl()).append("/acme/demo/")
                    .append(versions[i]).append("\"}");
        }
        return sb.append("}}").toString();
    }

    private static String upstreamRelease(String version, byte[] zip) {
        return """
                {"id":"acme.demo","version":"%s",
                 "resources":[{"name":"source-archive","type":"application/zip","checksum":"%s"}],
                 "metadata":{"description":"from upstream","repositoryURLs":["https://github.com/acme/demo"]},
                 "publishedAt":"2024-01-31T12:00:00Z"}
                """.formatted(version, sha256(zip));
    }

    private static void stubJson(String path, String body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withHeader("Content-Version", "1").withBody(body)));
    }

    private static void stubRelease(String version, byte[] zip) {
        stubJson("/acme/demo", upstreamReleases(version));
        stubJson("/acme/demo/" + version, upstreamRelease(version, zip));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/acme/demo/" + version + ".zip")).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/zip").withHeader("Content-Version", "1").withBody(zip)));
    }

    private static void stubManifests(String version) {
        String manifestPath = "/acme/demo/" + version + "/Package.swift";
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(manifestPath)).withQueryParam("swift-version", WireMock.absent())
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/x-swift").withHeader("Content-Version", "1")
                        .withHeader("Link", "<" + upstream.baseUrl() + manifestPath + "?swift-version=6>; rel=\"alternate\"; "
                                + "filename=\"Package@swift-6.swift\"; swift-tools-version=\"6.0\"")
                        .withBody(MANIFEST)));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(manifestPath)).withQueryParam("swift-version", WireMock.equalTo("6"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/x-swift").withHeader("Content-Version", "1")
                        .withBody(MANIFEST_6)));
    }

    // --- tests -----------------------------------------------------------------------------------------

    @Test
    void releaseListIsCachedAndPointsAtGranary() throws Exception {
        stubJson("/acme/demo", """
                {"releases":{
                  "1.0.0":{"url":"%1$s/acme/demo/1.0.0"},
                  "1.1.0":{"url":"%1$s/acme/demo/1.1.0","problem":{"status":410,"title":"Gone","detail":"this release was removed"}},
                  "not-a-version":{"url":"%1$s/x"}
                }}""".formatted(upstream.baseUrl()));

        MvcResult result = mvc.perform(getJson(URL + "/acme/demo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Version", "1"))
                .andExpect(jsonPath("$.releases['1.0.0'].url").value(BASE + "/acme/demo/1.0.0"))
                .andExpect(jsonPath("$.releases['1.1.0'].problem.status").value(410))
                .andExpect(jsonPath("$.releases['not-a-version']").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getHeader("Link")).startsWith("<" + BASE + "/acme/demo/1.0.0>; rel=\"latest-version\"");
        assertThat(text(result)).doesNotContain(upstream.baseUrl());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo")).withHeader("Accept", WireMock.equalTo(ACCEPT_JSON)));
        assertThat(storage.find(repo, "acme/demo/releases.json")).hasValueSatisfying(c -> assertThat(c.asset().isMetadata()).isTrue());

        // fresh metadata is answered from the cache, whatever the spelling of the identity
        mvc.perform(getJson(URL + "/ACME/Demo")).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo")));
    }

    @Test
    void releaseMetadataManifestAndArchiveAreCachedOnce() throws Exception {
        stubRelease("1.0.0", ZIP);
        stubManifests("1.0.0");

        mvc.perform(getJson(URL + "/acme/demo/1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[0].checksum").value(sha256(ZIP)))
                .andExpect(jsonPath("$.metadata.description").value("from upstream"))
                .andExpect(header().string("Link", "<" + BASE + "/acme/demo/1.0.0>; rel=\"latest-version\""));

        for (int i = 0; i < 2; i++) {
            mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/zip"))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"demo-1.0.0.zip\""))
                    .andExpect(header().exists("Digest"))
                    .andExpect(content().bytes(ZIP));
            MvcResult manifest = mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "text/x-swift"))
                    .andReturn();
            assertThat(text(manifest)).isEqualTo(MANIFEST);
            // the variant the upstream advertised is advertised again, on Granary's URL
            assertThat(manifest.getResponse().getHeader("Link")).isEqualTo("<" + BASE + "/acme/demo/1.0.0/Package.swift?swift-version=6>; "
                    + "rel=\"alternate\"; filename=\"Package@swift-6.swift\"; swift-tools-version=\"6.0\"");
            assertThat(text(mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift?swift-version=6.0.0")).andExpect(status().isOk())
                    .andReturn())).isEqualTo(MANIFEST_6);
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo/1.0.0.zip")).withHeader("Accept", WireMock.equalTo(ACCEPT_ZIP)));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo/1.0.0/Package.swift"))
                .withQueryParam("swift-version", WireMock.absent()).withHeader("Accept", WireMock.equalTo(ACCEPT_SWIFT)));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo/1.0.0/Package.swift"))
                .withQueryParam("swift-version", WireMock.equalTo("6")));
        assertThat(storage.find(repo, "acme/demo/1.0.0.zip")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(2);
        });
        assertThat(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "acme", "demo", "1.0.0")).isPresent();

        // a Swift version without a variant is redirected to the default manifest without asking upstream
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift?swift-version=5.10")).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", BASE + "/acme/demo/1.0.0/Package.swift"));
    }

    @Test
    void releaseListsAreRevalidatedOnceTheMetadataMaxAgeHasPassed() throws Exception {
        impatientProxy("swift-proxy-fresh");
        String url = "/repository/swift-proxy-fresh";
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/acme/demo")).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withHeader("Content-Version", "1").withHeader("ETag", "\"v1\"")
                .withBody(upstreamReleases("1.0.0"))));
        mvc.perform(getJson(url + "/acme/demo")).andExpect(status().isOk()).andExpect(jsonPath("$.releases.length()").value(1));

        // unchanged upstream: a conditional request, answered 304, and the cached list is served
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/acme/demo")).withHeader("If-None-Match", WireMock.equalTo("\"v1\""))
                .atPriority(1).willReturn(WireMock.aResponse().withStatus(304).withHeader("ETag", "\"v1\"")));
        mvc.perform(getJson(url + "/acme/demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.releases['1.0.0'].url").value(repositoryUrl("swift-proxy-fresh") + "/acme/demo/1.0.0"));
        upstream.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo")).withHeader("If-None-Match", WireMock.equalTo("\"v1\"")));

        // a new release upstream shows up on the next request
        upstream.resetAll();
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/acme/demo")).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withHeader("Content-Version", "1").withHeader("ETag", "\"v2\"")
                .withBody(upstreamReleases("1.0.0", "1.1.0"))));
        MvcResult updated = mvc.perform(getJson(url + "/acme/demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.releases['1.1.0'].url").value(repositoryUrl("swift-proxy-fresh") + "/acme/demo/1.1.0"))
                .andReturn();
        assertThat(updated.getResponse().getHeader("Link")).startsWith("<" + repositoryUrl("swift-proxy-fresh")
                + "/acme/demo/1.1.0>; rel=\"latest-version\"");
    }

    @Test
    void staleCacheIsServedWhenUpstreamBreaks() throws Exception {
        impatientProxy("swift-proxy-stale");
        String url = "/repository/swift-proxy-stale";
        stubRelease("1.0.0", ZIP);
        stubManifests("1.0.0");
        mvc.perform(getJson(url + "/acme/demo")).andExpect(status().isOk());
        mvc.perform(getJson(url + "/acme/demo/1.0.0")).andExpect(status().isOk());
        mvc.perform(getSwift(url + "/acme/demo/1.0.0/Package.swift")).andExpect(status().isOk());
        mvc.perform(getZip(url + "/acme/demo/1.0.0.zip")).andExpect(status().isOk());

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        mvc.perform(getJson(url + "/acme/demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.releases['1.0.0'].url").value(repositoryUrl("swift-proxy-stale") + "/acme/demo/1.0.0"));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo"))); // it did try
        mvc.perform(getJson(url + "/acme/demo/1.0.0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[0].checksum").value(sha256(ZIP)));
        mvc.perform(getSwift(url + "/acme/demo/1.0.0/Package.swift")).andExpect(status().isOk())
                .andExpect(header().string("Link", containsString("Package@swift-6.swift")));
        mvc.perform(getZip(url + "/acme/demo/1.0.0.zip")).andExpect(status().isOk()).andExpect(content().bytes(ZIP));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/demo/1.0.0.zip")));

        // nothing cached while upstream is broken: a temporary 503, not a 404 SwiftPM would report as missing
        mvc.perform(getJson(url + "/acme/unknown")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(getZip(url + "/acme/demo/2.0.0.zip")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void offlineModeNeverTouchesUpstream() throws Exception {
        stubRelease("1.0.0", ZIP);
        stubManifests("1.0.0");
        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isOk());
        mvc.perform(getJson(URL + "/acme/demo/1.0.0")).andExpect(status().isOk());
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift")).andExpect(status().isOk());
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip")).andExpect(status().isOk());

        repositories.setOffline(REPO, true);
        upstream.resetAll();
        stubJson("/acme/other", upstreamReleases("1.0.0"));

        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isOk()).andExpect(jsonPath("$.releases['1.0.0']").exists());
        mvc.perform(getJson(URL + "/acme/demo/1.0.0")).andExpect(status().isOk());
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift")).andExpect(status().isOk());
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip")).andExpect(status().isOk()).andExpect(content().bytes(ZIP));
        mvc.perform(getJson(URL + "/acme/other")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift?swift-version=6")).andExpect(status().isServiceUnavailable());
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        repositories.setOffline(REPO, false);
        mvc.perform(getJson(URL + "/acme/other")).andExpect(status().isOk());
    }

    @Test
    void everyCachedFileOfAReleaseBelongsToItsComponentAndGoesWithIt() throws Exception {
        stubRelease("1.0.0", ZIP);
        stubManifests("1.0.0");
        mvc.perform(getZip(URL + "/acme/demo/1.0.0.zip")).andExpect(status().isOk());
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift")).andExpect(status().isOk());
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift?swift-version=6")).andExpect(status().isOk())
                .andExpect(content().string(MANIFEST_6));

        // the variant manifest belongs to the release like the default one, as it does in a hosted repository
        var component = components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "acme", "demo", "1.0.0")
                .orElseThrow();
        assertThat(assets.findByComponentId(component.getId())).extracting(Asset::getPath).containsExactlyInAnyOrder(
                "acme/demo/1.0.0.zip", "acme/demo/1.0.0/Package.swift", "acme/demo/1.0.0/Package@swift-6.swift",
                "acme/demo/1.0.0/" + SwiftLayout.MANIFEST_LINKS);
        assertThat(components.findAll()).filteredOn(c -> repo.getId().equals(c.getRepositoryId())).hasSize(1);

        mvc.perform(delete(URI.create("/api/v1/repositories/" + REPO + "/components/" + component.getId()))
                        .header("Authorization", adminBearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deletedAssets").value(4));
        assertThat(storage.list(repo, "acme/demo/1.0.0")).isEmpty();

        // offline nothing of the release is left to serve; online it is fetched and linked again
        repositories.setOffline(REPO, true);
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift?swift-version=6")).andExpect(status().isServiceUnavailable());
        repositories.setOffline(REPO, false);
        mvc.perform(getSwift(URL + "/acme/demo/1.0.0/Package.swift?swift-version=6")).andExpect(status().isOk())
                .andExpect(content().string(MANIFEST_6));
        var again = components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "acme", "demo", "1.0.0")
                .orElseThrow();
        assertThat(assets.findByComponentId(again.getId())).extracting(Asset::getPath).containsExactlyInAnyOrder(
                "acme/demo/1.0.0/Package.swift", "acme/demo/1.0.0/Package@swift-6.swift",
                "acme/demo/1.0.0/" + SwiftLayout.MANIFEST_LINKS);
    }

    @Test
    void upstream404IsRememberedByTheNegativeCache() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.anyUrl()).willReturn(WireMock.aResponse().withStatus(404)
                .withHeader("Content-Type", "application/problem+json").withHeader("Content-Version", "1")
                .withBody("{\"detail\":\"not found\"}")));
        for (int i = 0; i < 3; i++) {
            mvc.perform(getJson(URL + "/acme/missing")).andExpect(status().isNotFound())
                    .andExpect(header().string("Content-Type", "application/problem+json"))
                    .andExpect(jsonPath("$.detail").value("package acme.missing not found"));
            mvc.perform(getSwift(URL + "/acme/missing/1.0.0/Package.swift")).andExpect(status().isNotFound());
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/missing")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/acme/missing/1.0.0/Package.swift")));
    }

    @Test
    void identifierLookupsAreForwardedAndCached() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/identifiers"))
                .withQueryParam("url", WireMock.equalTo("https://github.com/acme/demo"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withHeader("Content-Version", "1").withBody("{\"identifiers\":[\"acme.demo\",\"not an id\"]}")));
        for (int i = 0; i < 2; i++) {
            mvc.perform(getJson(URL + "/identifiers?url=https://github.com/acme/demo"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.identifiers.length()").value(1))
                    .andExpect(jsonPath("$.identifiers[0]").value("acme.demo"));
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/identifiers")));
        mvc.perform(getJson(URL + "/identifiers?url=https://github.com/acme/unknown")).andExpect(status().isNotFound());
    }

    @Test
    void writesAreRefused() throws Exception {
        mvc.perform(publish(URL + "/acme/demo/1.0.0", ZIP, null).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.detail", containsString("proxies " + upstream.baseUrl())))
                .andExpect(jsonPath("$.detail", containsString("read-only")));
        mvc.perform(delete(URI.create(URL + "/acme/demo/1.0.0")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET, HEAD"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void upstreamDocumentsThatAreNotJsonObjectsAreMisses() throws Exception {
        stubJson("/acme/demo", "[1,2,3]");
        stubJson("/acme/demo/1.0.0", "not json at all");
        mvc.perform(getJson(URL + "/acme/demo")).andExpect(status().isNotFound());
        mvc.perform(getJson(URL + "/acme/demo/1.0.0")).andExpect(status().isNotFound());
        JsonNode problem = json(mvc.perform(getJson(URL + "/acme/demo/1.0.0")).andReturn());
        assertThat(problem.path("detail").stringValue()).isEqualTo("release acme.demo 1.0.0 not found");
    }
}
