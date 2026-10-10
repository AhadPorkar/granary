package dev.granary.server.helm;

import static dev.granary.server.helm.HelmFixtures.chart;
import static dev.granary.server.helm.HelmFixtures.index;
import static dev.granary.server.helm.HelmFixtures.upload;
import static dev.granary.server.helm.HelmFixtures.upstreamChart;
import static dev.granary.server.helm.HelmFixtures.upstreamIndex;
import static dev.granary.server.helm.HelmFixtures.versionNumbers;
import static dev.granary.server.helm.HelmFixtures.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * The HTTP surface beyond the happy path: odd and hostile paths on every repository type, HEAD and conditional
 * requests through proxies and groups, the request shape of {@code curl --data-binary}, and concurrent uploads.
 */
class HelmHttpEdgeCasesIntegrationTest extends IntegrationTest {

    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final byte[] HOSTED_DEMO = chart("demo", "1.0.0", "hosted");
    private static final byte[] UPSTREAM_DEMO = chart("demo", "2.0.0", "upstream");

    /** Traversal attempts, encoded separators, control characters, non-ASCII names and empty segments. */
    private static final List<String> ODD_PATHS = List.of(
            "charts/..%2Fsecret.tgz",
            "charts/%2e%2e/secret.tgz",
            "charts/%2E%2E%2Fsecret.tgz",
            "charts/demo%5C..%5Csecret.tgz",
            "charts/demo%00-1.0.0.tgz",
            "charts/d%C3%A9mo-1.0.0.tgz",
            "charts//demo-1.0.0.tgz",
            "charts/demo/..%2F..%2Findex.yaml/demo-1.0.0.tgz",
            "charts/.hidden-1.0.0.tgz",
            "api/charts/..%2F..%2Findex.yaml",
            "api/charts/demo/1.0.0%2F..",
            "%2e%2e/%2e%2e/etc/passwd.tgz",
            "demo%201.0.0.tgz");

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    private Repository hosted;
    private String auth;

    @BeforeAll
    static void startUpstream() {
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void createRepositories() throws Exception {
        upstream.resetAll();
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/charts/index.yaml")).willReturn(WireMock.ok(
                upstreamIndex(upstreamChart("demo", "2.0.0", "demo-2.0.0.tgz", UPSTREAM_DEMO)))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/charts/demo-2.0.0.tgz"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody(UPSTREAM_DEMO)));
        hosted = hosted("helm-hosted", "helm");
        proxyService.invalidate(proxy("helm-proxy", "helm", upstream.baseUrl() + "/charts"));
        group("helm-group", "helm", "helm-hosted", "helm-proxy");
        auth = basic(ADMIN, tokens.issue(users.require(ADMIN), "helm", null).secret());
        mvc.perform(upload("/repository/helm-hosted", auth, HOSTED_DEMO)).andExpect(status().isCreated());
    }

    @Test
    void oddPathsAreRefusedWithA400OnEveryRepositoryTypeAndNeverReachStorage() throws Exception {
        Set<String> before = Set.copyOf(storage.list(hosted, "").stream().map(a -> a.getPath()).toList());
        for (String repository : List.of("helm-hosted", "helm-proxy", "helm-group")) {
            for (String path : ODD_PATHS) {
                URI uri = URI.create("/repository/" + repository + "/" + path);
                int get = mvc.perform(get(uri)).andReturn().getResponse().getStatus();
                int headStatus = mvc.perform(head(uri)).andReturn().getResponse().getStatus();
                int putStatus = mvc.perform(put(uri).header("Authorization", auth).content(chart("evil", "6.6.6")))
                        .andReturn().getResponse().getStatus();
                assertThat(List.of(get, headStatus, putStatus)).as("%s %s", repository, path).containsOnly(400);
            }
        }
        assertThat(storage.list(hosted, "").stream().map(a -> a.getPath()).toList()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(versionNumbers(yaml(mvc.perform(index("/repository/helm-group")).andExpect(status().isOk()).andReturn()), "evil"))
                .isEmpty();
        // only the index and the archive the index lists were ever asked for upstream
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathMatching(".*(secret|passwd|evil|hidden).*")));
    }

    @Test
    void headAndConditionalRequestsThroughTheGroupAndTheProxy() throws Exception {
        // the group index: HEAD has the headers of GET without the body, and a matching ETag is a 304
        MvcResult full = mvc.perform(index("/repository/helm-group")).andExpect(status().isOk()).andReturn();
        String etag = full.getResponse().getHeader("ETag");
        int length = full.getResponse().getContentAsByteArray().length;
        MvcResult headIndex = mvc.perform(head("/repository/helm-group/index.yaml"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", etag))
                .andExpect(header().longValue("Content-Length", length))
                .andReturn();
        assertThat(headIndex.getResponse().getContentAsByteArray()).isEmpty();
        mvc.perform(index("/repository/helm-group").header("If-None-Match", etag)).andExpect(status().isNotModified());
        mvc.perform(index("/repository/helm-group").header("If-None-Match", "W/" + etag + ", \"other\""))
                .andExpect(status().isNotModified());
        mvc.perform(index("/repository/helm-group").header("If-None-Match", "\"stale\"")).andExpect(status().isOk());

        // a proxied archive: HEAD fetches and caches it but does not count as a download; GET then 304
        MvcResult headArchive = mvc.perform(head("/repository/helm-group/charts/demo-2.0.0.tgz"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", UPSTREAM_DEMO.length))
                .andExpect(header().string("Content-Type", "application/gzip"))
                .andReturn();
        assertThat(headArchive.getResponse().getContentAsByteArray()).isEmpty();
        String archiveTag = headArchive.getResponse().getHeader("ETag");
        assertThat(archiveTag).isEqualTo("\"" + HelmFixtures.sha256(UPSTREAM_DEMO) + "\"");
        mvc.perform(get("/repository/helm-group/charts/demo-2.0.0.tgz").header("If-None-Match", archiveTag))
                .andExpect(status().isNotModified());
        mvc.perform(head("/repository/helm-proxy/charts/demo-2.0.0.tgz").header("If-None-Match", archiveTag))
                .andExpect(status().isNotModified());
        Repository proxy = repositories.require("helm-proxy");
        assertThat(storage.find(proxy, "charts/demo-2.0.0.tgz").orElseThrow().asset().getDownloadCount()).isZero();
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/charts/demo-2.0.0.tgz")));

        // HEAD of a missing chart is a 404, and HEAD of the ChartMuseum API answers like GET
        mvc.perform(head("/repository/helm-group/charts/nothing-1.0.0.tgz")).andExpect(status().isNotFound());
        mvc.perform(head("/repository/helm-group/api/charts/demo")).andExpect(status().isOk());
        mvc.perform(head("/repository/helm-group/api/charts/demo/9.9.9")).andExpect(status().isNotFound());
    }

    @Test
    void curlDataBinaryUploadsWithItsFormContentType() throws Exception {
        // curl --data-binary @chart.tgz sends application/x-www-form-urlencoded; the body is still the archive
        byte[] archive = chart("curl-demo", "0.1.0");
        mvc.perform(post("/repository/helm-hosted/api/charts").header("Authorization", auth)
                        .contentType("application/x-www-form-urlencoded").content(archive))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saved").value(true));
        assertThat(mvc.perform(get("/repository/helm-hosted/charts/curl-demo-0.1.0.tgz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(archive);
    }

    @Test
    void concurrentUploadsOfOneVersionStoreItExactlyOnce() throws Exception {
        List<byte[]> copies = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            copies.add(chart("race", "1.0.0", "copy " + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> uploads = copies.stream().<Callable<Integer>>map(copy -> () ->
                    mvc.perform(upload("/repository/helm-hosted", auth, copy)).andReturn().getResponse().getStatus()).toList();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(uploads)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);

            // whichever copy won, the stored archive is the one the index describes
            byte[] stored = mvc.perform(get("/repository/helm-hosted/charts/race-1.0.0.tgz")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            var entries = HelmFixtures.versions(yaml(mvc.perform(index("/repository/helm-hosted")).andReturn()), "race");
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst()).containsEntry("digest", HelmFixtures.sha256(stored));

            // different versions of one chart go through side by side
            List<Callable<Integer>> versions = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                byte[] archive = chart("race", "2.0." + i);
                versions.add(() -> mvc.perform(upload("/repository/helm-hosted", auth, archive)).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(versions)) {
                assertThat(f.get()).isEqualTo(201);
            }
            assertThat(versionNumbers(yaml(mvc.perform(index("/repository/helm-hosted")).andReturn()), "race")).hasSize(9);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void readersCannotWriteThroughTheGroupAndAnonymousWritesAre401() throws Exception {
        users.create("helm-group-reader", "readerpassword", "Reader", null, Set.of("reader"));
        try {
            String reader = basic("helm-group-reader", tokens.issue(users.require("helm-group-reader"), "helm", null).secret());
            mvc.perform(upload("/repository/helm-group", reader, chart("demo", "3.0.0"))).andExpect(status().isForbidden());
            mvc.perform(upload("/repository/helm-group", null, chart("demo", "3.0.0")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists("WWW-Authenticate"));
            mvc.perform(upload("/repository/helm-group", basic(ADMIN, "wrong"), chart("demo", "3.0.0")))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/repository/helm-group/charts/demo-1.0.0.tgz").header("Authorization", reader)).andExpect(status().isOk());
        } finally {
            users.delete("helm-group-reader");
        }
    }
}
