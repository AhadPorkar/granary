package dev.granary.server.conan;

import static dev.granary.server.conan.ConanFixtures.PID;
import static dev.granary.server.conan.ConanFixtures.PREV;
import static dev.granary.server.conan.ConanFixtures.RREV;
import static dev.granary.server.conan.ConanFixtures.RREV2;
import static dev.granary.server.conan.ConanFixtures.USER_AGENT;
import static dev.granary.server.conan.ConanFixtures.latestJson;
import static dev.granary.server.conan.ConanFixtures.packageRevision;
import static dev.granary.server.conan.ConanFixtures.recipe;
import static dev.granary.server.conan.ConanFixtures.revision;
import static dev.granary.server.conan.ConanFixtures.revisionsJson;
import static dev.granary.server.conan.ConanFixtures.tgz;
import static dev.granary.server.conan.ConanFixtures.upload;
import static dev.granary.server.conan.ConanFixtures.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** A proxy in front of a fake ConanCenter (or Artifactory): caching, re-validation, the offline-first fallbacks and upstream login. */
class ConanProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "conancenter";
    private static final String URL = "/repository/" + REPO;
    private static final String ZLIB = recipe("zlib", "1.3.1");
    private static final String TIME = "2025-11-03T10:20:30.123+0000";
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
        repo = proxy(REPO, "conan", upstream.baseUrl());
        proxyService.invalidate(repo);
    }

    @Test
    void installThroughTheProxyCachesFilesForever() throws Exception {
        byte[] export = tgz("export");
        byte[] pkg = tgz("package");
        stubRecipe(ZLIB, RREV, export);
        stubPackage(ZLIB, RREV, PID, PREV, pkg);

        for (int round = 0; round < 2; round++) {
            mvc.perform(get(URL + ZLIB + "/latest").header("User-Agent", USER_AGENT))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/json"))
                    .andExpect(jsonPath("$.revision").value(RREV))
                    .andExpect(jsonPath("$.time").value(TIME));
            mvc.perform(get(URL + revision(ZLIB, RREV) + "/files"))
                    .andExpect(jsonPath("$.files['conan_export.tgz']").isMap());
            mvc.perform(get(URL + revision(ZLIB, RREV) + "/files/conan_export.tgz"))
                    .andExpect(status().isOk()).andExpect(content().bytes(export));
            mvc.perform(get(URL + revision(ZLIB, RREV) + "/packages/" + PID + "/latest")
                            .header("Conan-PkgID-Settings", "os=Linux"))
                    .andExpect(jsonPath("$.revision").value(PREV));
            mvc.perform(get(URL + packageRevision(ZLIB, RREV, PID, PREV) + "/files"))
                    .andExpect(jsonPath("$.files['conan_package.tgz']").isMap());
            mvc.perform(get(URL + packageRevision(ZLIB, RREV, PID, PREV) + "/files/conan_package.tgz"))
                    .andExpect(status().isOk()).andExpect(content().bytes(pkg));
        }

        // files once, documents once (still fresh), every document asked for as JSON
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo(ZLIB + "/latest"))
                .withHeader("Accept", WireMock.equalTo("application/json")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo(revision(ZLIB, RREV) + "/files/conan_export.tgz")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo(packageRevision(ZLIB, RREV, PID, PREV) + "/files/conan_package.tgz")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo(revision(ZLIB, RREV) + "/packages/" + PID + "/latest")));

        Asset cachedExport = storage.find(repo, "conans/zlib/1.3.1/_/_/" + RREV + "/export/conan_export.tgz").orElseThrow().asset();
        assertThat(cachedExport.getOrigin()).isEqualTo(Asset.Origin.CACHED);
        assertThat(cachedExport.isMetadata()).isFalse();
        assertThat(cachedExport.getComponentId()).isNotNull();
        assertThat(storage.find(repo, ZLIB.substring(1) + "/latest").orElseThrow().asset().isMetadata()).isTrue();
        mvc.perform(get("/api/v1/repositories/" + REPO + "/components").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.items[0].name").value("zlib"))
                .andExpect(jsonPath("$.items[0].version").value("1.3.1"));
    }

    @Test
    void filesKeepTheirContentTypeWhateverTheUpstreamSends() throws Exception {
        // conan_server answers "Content-Type: auto" for every file, which is not even a media type
        byte[] export = tgz("export");
        stubJson(revision(ZLIB, RREV) + "/files", "{\"files\":{\"conan_export.tgz\":{},\"conanfile.py\":{}}}");
        for (Map.Entry<String, byte[]> file : Map.of("conan_export.tgz", export, "conanfile.py", utf8("from conan import ConanFile")).entrySet()) {
            upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(revision(ZLIB, RREV) + "/files/" + file.getKey()))
                    .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "auto").withBody(file.getValue())));
        }
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/files/conan_export.tgz"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/gzip"))
                .andExpect(content().bytes(export));
        for (int round = 0; round < 2; round++) {
            mvc.perform(get(URL + revision(ZLIB, RREV) + "/files/conanfile.py"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/x-python")));
        }
    }

    @Test
    void parallelDownloadsOfOneRevisionShareOneComponent() throws Exception {
        // conan fetches the files of a revision in parallel; the first ones to arrive all create the component
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        for (String file : List.of("conan_export.tgz", "conanfile.py", "conanmanifest.txt")) {
            files.put(revision(ZLIB, RREV) + "/files/" + file, tgz("recipe " + file));
        }
        for (String file : List.of("conan_package.tgz", "conaninfo.txt", "conanmanifest.txt")) {
            files.put(packageRevision(ZLIB, RREV, PID, PREV) + "/files/" + file, tgz("package " + file));
        }
        files.forEach((path, body) -> upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(
                WireMock.aResponse().withStatus(200).withFixedDelay(300)
                        .withHeader("Content-Type", "application/octet-stream").withBody(body))));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(files.size())) {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (String path : files.keySet()) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(get(URL + path)).andReturn();
                }));
            }
            start.countDown();
            List<String> paths = List.copyOf(files.keySet());
            for (int i = 0; i < paths.size(); i++) {
                MvcResult result = results.get(i).get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).as(paths.get(i)).isEqualTo(200);
                assertThat(result.getResponse().getContentAsByteArray()).as(paths.get(i)).isEqualTo(files.get(paths.get(i)));
            }
        }
        List<java.util.UUID> components = storage.list(repo, "conans/zlib/1.3.1/").stream().map(Asset::getComponentId).distinct().toList();
        assertThat(components).hasSize(1).doesNotContainNull();
        mvc.perform(get("/api/v1/repositories/" + REPO + "/components").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void metadataIsRevalidatedAfterItsMaxAge() throws Exception {
        Repository eager = eagerProxy("conan-eager");
        String url = "/repository/" + eager.getName();
        stubJson(ZLIB + "/latest", latestJson(RREV, TIME));
        mvc.perform(get(url + ZLIB + "/latest")).andExpect(jsonPath("$.revision").value(RREV));
        stubJson(ZLIB + "/latest", latestJson(RREV2, "2025-12-01T00:00:00.000+0000"));
        mvc.perform(get(url + ZLIB + "/latest")).andExpect(jsonPath("$.revision").value(RREV2));
        upstream.verify(2, WireMock.getRequestedFor(WireMock.urlPathEqualTo(ZLIB + "/latest")));
    }

    @Test
    void staleDocumentsAndCachedFilesAreServedWhenUpstreamBreaks() throws Exception {
        Repository eager = eagerProxy("conan-stale");
        String url = "/repository/" + eager.getName();
        byte[] export = tgz("export");
        stubRecipe(ZLIB, RREV, export);
        mvc.perform(get(url + ZLIB + "/latest")).andExpect(status().isOk());
        mvc.perform(get(url + revision(ZLIB, RREV) + "/files")).andExpect(status().isOk());
        mvc.perform(get(url + revision(ZLIB, RREV) + "/files/conan_export.tgz")).andExpect(status().isOk());

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        mvc.perform(get(url + ZLIB + "/latest")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(RREV));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo(ZLIB + "/latest"))); // it did try
        mvc.perform(get(url + revision(ZLIB, RREV) + "/files")).andExpect(jsonPath("$.files['conan_export.tgz']").isMap());
        mvc.perform(get(url + revision(ZLIB, RREV) + "/files/conan_export.tgz")).andExpect(content().bytes(export));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo(revision(ZLIB, RREV) + "/files/conan_export.tgz")));

        // never cached while upstream is broken: a temporary 503, not a 404 the client would believe
        mvc.perform(get(url + recipe("openssl", "3.3.2") + "/latest"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void offlineModeNeverTouchesUpstream() throws Exception {
        byte[] export = tgz("export");
        stubRecipe(ZLIB, RREV, export);
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(status().isOk());
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/files/conan_export.tgz")).andExpect(status().isOk());

        repositories.setOffline(REPO, true);
        upstream.resetAll();
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(RREV));
        mvc.perform(get(URL + revision(ZLIB, RREV) + "/files/conan_export.tgz")).andExpect(content().bytes(export));
        mvc.perform(get(URL + recipe("openssl", "3.3.2") + "/latest"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        // a search never made before cannot be answered offline: 503, not an authoritative "nothing found"
        mvc.perform(get(URL + "/v2/conans/search").param("q", "openssl"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        repositories.setOffline(REPO, false);
        settings.setGlobalOffline(true);
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(status().isOk());
        mvc.perform(get(URL + recipe("openssl", "3.3.2") + "/latest")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void upstream404IsRemembered() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathMatching("/v2/conans/nope/.*"))
                .willReturn(WireMock.aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"errors\":[{\"status\":404,\"message\":\"Not found\"}]}")));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(URL + recipe("nope", "1.0") + "/latest"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errors[0].status").value(404));
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo(recipe("nope", "1.0") + "/latest")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo(recipe("nope", "1.0") + "/revisions")));
    }

    @Test
    void searchIsPassedThroughAndArtifactoryPlaceholdersDropped() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/conans/search"))
                .withQueryParam("q", WireMock.equalTo("zlib/*"))
                .willReturn(WireMock.okJson("{\"results\":[\"zlib/1.3.1@_/_\",\"zlib/1.2.13@_/_\"]}")));
        mvc.perform(get(URL + "/v2/conans/search").param("q", "zlib/*"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0]").value("zlib/1.2.13"))
                .andExpect(jsonPath("$.results[1]").value("zlib/1.3.1"));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/conans/search"))
                .withQueryParam("q", WireMock.equalTo("Zlib"))
                .withQueryParam("ignorecase", WireMock.equalTo("False"))
                .willReturn(WireMock.okJson("{\"results\":[]}")));
        mvc.perform(get(URL + "/v2/conans/search").param("q", "Zlib").param("ignorecase", "False"))
                .andExpect(jsonPath("$.results").isEmpty());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/conans/search"))
                .withQueryParam("ignorecase", WireMock.equalTo("False")));
    }

    @Test
    void privateUpstreamGetsOneLoginAndABearerToken() throws Exception {
        Repository priv = new Repository();
        priv.setName("conan-artifactory");
        priv.setFormat("conan");
        priv.setType(RepositoryType.PROXY);
        priv.setRemoteUrl(upstream.baseUrl());
        priv.setRemoteUsername("ci");
        priv.setRemotePassword("s3cret-pass");
        repositories.create(priv);
        String url = "/repository/conan-artifactory";

        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/users/authenticate"))
                .withBasicAuth("ci", "s3cret-pass")
                .willReturn(WireMock.ok("upstream-jwt-token").withHeader("Content-Type", "text/plain")));
        byte[] export = tgz("private");
        stubRecipe(ZLIB, RREV, export);

        mvc.perform(get(url + ZLIB + "/latest")).andExpect(jsonPath("$.revision").value(RREV));
        mvc.perform(get(url + revision(ZLIB, RREV) + "/files")).andExpect(status().isOk());
        mvc.perform(get(url + revision(ZLIB, RREV) + "/files/conan_export.tgz")).andExpect(content().bytes(export));

        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/users/authenticate")));
        for (String path : new String[] {ZLIB + "/latest", revision(ZLIB, RREV) + "/files",
                revision(ZLIB, RREV) + "/files/conan_export.tgz"}) {
            upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo(path))
                    .withHeader("Authorization", WireMock.equalTo("Bearer upstream-jwt-token")));
        }
    }

    @Test
    void basicUpstreamAuthenticationWhenConfigured() throws Exception {
        Repository priv = new Repository();
        priv.setName("conan-basic");
        priv.setFormat("conan");
        priv.setType(RepositoryType.PROXY);
        priv.setRemoteUrl(upstream.baseUrl());
        priv.setRemoteUsername("ci");
        priv.setRemotePassword("s3cret-pass");
        priv.setFormatSettings(Map.of("conan.upstreamAuth", "basic"));
        repositories.create(priv);
        stubJson(ZLIB + "/latest", latestJson(RREV, TIME));
        mvc.perform(get("/repository/conan-basic" + ZLIB + "/latest")).andExpect(jsonPath("$.revision").value(RREV));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/users/authenticate")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo(ZLIB + "/latest"))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("ci", "s3cret-pass")));
    }

    @Test
    void failedUpstreamLoginFallsBackToBasic() throws Exception {
        Repository priv = new Repository();
        priv.setName("conan-nologin");
        priv.setFormat("conan");
        priv.setType(RepositoryType.PROXY);
        priv.setRemoteUrl(upstream.baseUrl());
        priv.setRemoteUsername("ci");
        priv.setRemotePassword("s3cret-pass");
        repositories.create(priv);
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/users/authenticate")).willReturn(WireMock.notFound()));
        stubJson(ZLIB + "/latest", latestJson(RREV, TIME));
        mvc.perform(get("/repository/conan-nologin" + ZLIB + "/latest")).andExpect(jsonPath("$.revision").value(RREV));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo(ZLIB + "/latest"))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("ci", "s3cret-pass")));
    }

    @Test
    void latestFallsBackToTheRevisionListWhenTheUpstreamHasNoLatestEndpoint() throws Exception {
        stubJson(ZLIB + "/revisions", revisionsJson(RREV2, "2025-12-01T00:00:00.000+0000", RREV, TIME));
        mvc.perform(get(URL + ZLIB + "/latest")).andExpect(jsonPath("$.revision").value(RREV2));
    }

    @Test
    void proxiesAreReadOnly() throws Exception {
        mvc.perform(upload(URL + revision(ZLIB, RREV) + "/files/conanfile.py", utf8("x"), adminBasic()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("repository.readOnly"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("hosted repository")));
        mvc.perform(delete(URL + revision(ZLIB, RREV)).header("Authorization", adminBasic()))
                .andExpect(status().isForbidden());
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    private Repository eagerProxy(String name) {
        Repository eager = new Repository();
        eager.setName(name);
        eager.setFormat("conan");
        eager.setType(RepositoryType.PROXY);
        eager.setRemoteUrl(upstream.baseUrl());
        eager.setMetadataMaxAgeMinutes(0); // re-validate every document on every request
        Repository created = repositories.create(eager);
        proxyService.invalidate(created);
        return created;
    }

    private void stubRecipe(String recipe, String rrev, byte[] export) {
        stubJson(recipe + "/latest", latestJson(rrev, TIME));
        stubJson(recipe + "/revisions", revisionsJson(rrev, TIME));
        stubJson(revision(recipe, rrev) + "/files",
                "{\"files\":{\"conan_export.tgz\":{},\"conanfile.py\":{},\"conanmanifest.txt\":{}}}");
        stubBytes(revision(recipe, rrev) + "/files/conan_export.tgz", export);
    }

    private void stubPackage(String recipe, String rrev, String pid, String prev, byte[] pkg) {
        stubJson(revision(recipe, rrev) + "/search", "{\"" + pid + "\":{\"settings\":{\"os\":\"Linux\"},\"options\":{},\"requires\":[]}}");
        stubJson(revision(recipe, rrev) + "/packages/" + pid + "/latest", latestJson(prev, TIME));
        stubJson(revision(recipe, rrev) + "/packages/" + pid + "/revisions", revisionsJson(prev, TIME));
        stubJson(packageRevision(recipe, rrev, pid, prev) + "/files",
                "{\"files\":{\"conan_package.tgz\":{},\"conaninfo.txt\":{},\"conanmanifest.txt\":{}}}");
        stubBytes(packageRevision(recipe, rrev, pid, prev) + "/files/conan_package.tgz", pkg);
    }

    private static void stubJson(String path, String body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.okJson(body)));
    }

    private static void stubBytes(String path, byte[] body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/octet-stream").withBody(body)));
    }
}
