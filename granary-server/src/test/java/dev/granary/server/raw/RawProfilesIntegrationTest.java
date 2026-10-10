package dev.granary.server.raw;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.server.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** The profiles with behaviour of their own: vcpkg caches, the Bower registry, Elm's registry and endpoint rewrite, CPAN and CRAN indexes. */
class RawProfilesIntegrationTest extends IntegrationTest {

    private static final String SHA512 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".repeat(2);

    private static WireMockServer upstream;
    private static String base;

    @BeforeAll
    static void startUpstream() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        base = upstream.baseUrl();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void resetUpstream() {
        upstream.resetAll();
    }

    @Test
    void vcpkgAssetAndBinaryCachesAcceptAzureStyleUploads() throws Exception {
        hosted("vcpkg-cache", "vcpkg");
        String url = "/repository/vcpkg-cache/assets/" + SHA512;
        mvc.perform(put(url).header("Authorization", adminBasic())
                        .header("x-ms-blob-type", "BlockBlob").header("x-ms-version", "2020-04-08")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content("asset-bytes"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("ETag"))
                .andExpect(content().string(""));
        // vcpkg re-uploads without checking first: identical content is fine, different content is not
        mvc.perform(put(url).header("Authorization", adminBasic()).header("x-ms-blob-type", "BlockBlob").content("asset-bytes"))
                .andExpect(status().isCreated());
        mvc.perform(put(url).header("Authorization", adminBasic()).header("x-ms-blob-type", "BlockBlob").content("other"))
                .andExpect(status().isConflict());
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().string("asset-bytes"));
        mvc.perform(head(url)).andExpect(status().isOk());
        mvc.perform(head("/repository/vcpkg-cache/assets/" + "0".repeat(128))).andExpect(status().isNotFound());

        String binary = "/repository/vcpkg-cache/binaries/zlib/1.3.1/0123456789abcdef";
        mvc.perform(head(binary)).andExpect(status().isNotFound());
        mvc.perform(put(binary).header("Authorization", adminBasic()).contentType("application/zip").content("zip-bytes"))
                .andExpect(status().isCreated());
        mvc.perform(head(binary)).andExpect(status().isOk());
        mvc.perform(get(binary)).andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/zip"));
        mvc.perform(put(binary).content("zip-bytes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/repositories/vcpkg-cache/components").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("zlib")));
    }

    @Test
    void vcpkgParallelUploadsNeverFail() throws Exception {
        hosted("vcpkg-cache", "vcpkg");
        // CI agents push the same asset and binaries of one port for several triplets at the same time
        int clients = 12;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(clients);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < clients; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    start.await();
                    String path = n % 2 == 0
                            ? "/repository/vcpkg-cache/assets/" + SHA512
                            : "/repository/vcpkg-cache/binaries/zlib/1.3.1/abi" + n;
                    return mvc.perform(put(path).header("Authorization", adminBasic()).header("x-ms-blob-type", "BlockBlob")
                            .content("same-bytes")).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Integer> result : results) {
                assertThat(result.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        mvc.perform(head("/repository/vcpkg-cache/binaries/zlib/1.3.1/abi1")).andExpect(status().isOk());
        mvc.perform(get("/repository/vcpkg-cache/assets/" + SHA512)).andExpect(content().string("same-bytes"));
    }

    @Test
    void parallelBowerRegistrationsOfOneNameHaveOneWinner() throws Exception {
        hosted("bower-hosted", "bower");
        int clients = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(clients);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < clients; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/repository/bower-hosted/packages").header("Authorization", adminBasic())
                                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                    .param("name", "widget").param("url", "https://git.example.com/widget-" + n + ".git"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            int created = 0;
            for (java.util.concurrent.Future<Integer> result : results) {
                int status = result.get(60, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(status).isIn(201, 409);
                created += status == 201 ? 1 : 0;
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void bowerRegistryCrudAndProxy() throws Exception {
        hosted("bower-hosted", "bower");
        String url = "/repository/bower-hosted";
        mvc.perform(post(url + "/packages").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", "jquery").param("url", "https://github.com/jquery/jquery.git"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("jquery"));
        mvc.perform(post(url + "/packages").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"widget\",\"url\":\"git+ssh://git@git.example.com/ui/widget.git\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post(url + "/packages").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", "jquery").param("url", "https://evil.example.com/jquery.git"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("bower.nameTaken"));
        mvc.perform(post(url + "/packages").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", "anon").param("url", "https://github.com/a/a.git"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(url + "/packages"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].name").value("jquery"))
                .andExpect(jsonPath("$[1].name").value("widget"));
        mvc.perform(get(url + "/packages/jquery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://github.com/jquery/jquery.git"));
        mvc.perform(get(url + "/packages/search/wid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("widget"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(url + "/packages/nope")).andExpect(status().isNotFound());
        mvc.perform(delete(url + "/packages/jquery").header("Authorization", adminBasic())).andExpect(status().isNoContent());
        mvc.perform(get(url + "/packages/jquery")).andExpect(status().isNotFound());
        mvc.perform(get(url + "/packages")).andExpect(jsonPath("$.length()").value(1));

        proxy("bower-registry", "bower", base + "/");
        upstream.stubFor(WireMock.get(urlEqualTo("/packages/jquery"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"jquery\",\"url\":\"https://github.com/jquery/jquery-dist.git\"}")));
        upstream.stubFor(WireMock.get(urlEqualTo("/packages/search/jq"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[{\"name\":\"jquery\",\"url\":\"https://github.com/jquery/jquery-dist.git\"}]")));
        mvc.perform(get("/repository/bower-registry/packages/jquery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://github.com/jquery/jquery-dist.git"));
        mvc.perform(get("/repository/bower-registry/packages/search/jq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("jquery"));
        mvc.perform(post("/repository/bower-registry/packages").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("name", "x").param("url", "https://github.com/x/x.git"))
                .andExpect(status().isMethodNotAllowed());

        group("bower-all", "bower", "bower-hosted", "bower-registry");
        mvc.perform(get("/repository/bower-all/packages/search/jq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("jquery"));
        mvc.perform(get("/repository/bower-all/packages/widget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("git+ssh://git@git.example.com/ui/widget.git"));
    }

    @Test
    void elmProxyRewritesEndpointsToTheProxyAndServesRegistryPosts() throws Exception {
        proxy("elm-proxy", "elm", base + "/");
        upstream.stubFor(WireMock.get(urlEqualTo("/packages/elm/core/1.0.5/endpoint.json"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"url\":\"https://github.com/elm/core/zipball/1.0.5/\",\"hash\":\"4a9a1f5b\"}")));
        upstream.stubFor(WireMock.get(urlEqualTo("/all-packages"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"elm/core\":[\"1.0.5\"],\"elm/html\":[\"1.0.0\"]}")));
        upstream.stubFor(WireMock.get(urlEqualTo("/all-packages/since/1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[\"elm/html@1.0.0\"]")));

        mvc.perform(get("/repository/elm-proxy/packages/elm/core/1.0.5/endpoint.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(jsonPath("$.url").value("http://localhost/repository/elm-proxy/github/elm/core/zip/1.0.5"))
                .andExpect(jsonPath("$.hash").value("4a9a1f5b"));
        // the compiler POSTs the registry documents without credentials
        mvc.perform(post("/repository/elm-proxy/all-packages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['elm/core'][0]").value("1.0.5"))
                .andExpect(jsonPath("$['elm/html'][0]").value("1.0.0"));
        mvc.perform(post("/repository/elm-proxy/all-packages/since/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("elm/html@1.0.0"));
        upstream.verify(1, getRequestedFor(urlEqualTo("/all-packages")));
        mvc.perform(put("/repository/elm-proxy/packages/elm/core/1.0.5/elm.json").header("Authorization", adminBasic()).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void registryDocumentsOfAnUnreachableProxyAre503NotEmptyLists() throws Exception {
        proxy("bower-dead", "bower", "http://127.0.0.1:1/");
        proxy("elm-dead", "elm", "http://127.0.0.1:1/");
        mvc.perform(get("/repository/bower-dead/packages"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(get("/repository/bower-dead/packages/search/jq"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/repository/elm-dead/all-packages"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(post("/repository/elm-dead/all-packages/since/0")).andExpect(status().isServiceUnavailable());

        // a group still answers from the members that can: the hosted registry
        hosted("bower-hosted", "bower");
        mvc.perform(post("/repository/bower-hosted/packages").header("Authorization", adminBasic())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("name", "widget").param("url", "https://git.example.com/widget.git"))
                .andExpect(status().isCreated());
        group("bower-all", "bower", "bower-hosted", "bower-dead");
        mvc.perform(get("/repository/bower-all/packages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("widget"));
        mvc.perform(get("/repository/bower-all/packages/search/wid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        hosted("elm-hosted", "elm");
        group("elm-all", "elm", "elm-hosted", "elm-dead");
        mvc.perform(post("/repository/elm-all/all-packages")).andExpect(status().isOk()).andExpect(content().json("{}"));
    }

    @Test
    void elmZipRouteOnlyAcceptsGithubNamesAndVersions() throws Exception {
        proxy("elm-proxy", "elm", base + "/");
        // the real route goes to github.com, which the test cannot reach: odd segments must not become a 500
        for (String odd : java.util.List.of("github/a%20b/c/zip/1.0.0", "github/a/b/zip/1.0.0%3Fx=1", "github/a/b/zip/1.0.0%23x",
                "github/a/b/zip/latest", "github/-a/b/zip/1.0.0")) {
            int status = mvc.perform(get(java.net.URI.create("/repository/elm-proxy/" + odd))).andReturn().getResponse().getStatus();
            assertThat(status).as(odd).isIn(404, 503);
        }
        // anything outside the route is proxied verbatim from the package site
        upstream.stubFor(WireMock.get(urlEqualTo("/packages/elm/core/1.0.5/elm.json"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"type\":\"package\"}")));
        mvc.perform(get("/repository/elm-proxy/packages/elm/core/1.0.5/elm.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age")));
    }

    @Test
    void elmHostedBuildsTheRegistryFromUploadedReleases() throws Exception {
        hosted("elm-hosted", "elm");
        String release = "/repository/elm-hosted/packages/acme/widget/1.0.0";
        byte[] zip = "zip-bytes".getBytes(StandardCharsets.UTF_8);
        mvc.perform(put(release + "/elm.json").header("Authorization", adminBasic()).content("{\"type\":\"package\"}")).andExpect(status().isCreated());
        mvc.perform(put(release + "/docs.json").header("Authorization", adminBasic()).content("[]")).andExpect(status().isCreated());
        mvc.perform(put(release + "/widget-1.0.0.zip").header("Authorization", adminBasic()).contentType("application/zip").content(zip)).andExpect(status().isCreated());
        mvc.perform(post("/repository/elm-hosted/packages/acme/widget/1.0.0/elm.json").content("{}")).andExpect(status().isUnauthorized());

        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
        mvc.perform(get(release + "/endpoint.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://localhost/repository/elm-hosted/packages/acme/widget/1.0.0/widget-1.0.0.zip"))
                .andExpect(jsonPath("$.hash").value(sha1));
        MvcResult download = mvc.perform(get(release + "/widget-1.0.0.zip")).andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(zip);
        mvc.perform(post("/repository/elm-hosted/all-packages"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"acme/widget\":[\"1.0.0\"]}"));
        mvc.perform(post("/repository/elm-hosted/all-packages/since/0"))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"acme/widget@1.0.0\"]"));
        mvc.perform(get("/repository/elm-hosted/all-packages/since/1")).andExpect(content().json("[]"));
    }

    @Test
    void cpanHostedRegeneratesThePackagesIndex() throws Exception {
        hosted("cpan-hosted", "cpan");
        byte[] dist = RawServerFixtures.tarGz(
                "Acme-Hello-1.00/META.json", "{\"name\":\"Acme-Hello\",\"version\":\"1.00\",\"provides\":{\"Acme::Hello\":{\"file\":\"lib/Acme/Hello.pm\",\"version\":\"1.00\"}}}",
                "Acme-Hello-1.00/lib/Acme/Hello.pm", "package Acme::Hello;\nour $VERSION = '1.00';\n1;\n");
        mvc.perform(put("/repository/cpan-hosted/authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz").header("Authorization", adminBasic())
                        .contentType("application/gzip").content(dist))
                .andExpect(status().isCreated());
        MvcResult index = mvc.perform(get("/repository/cpan-hosted/modules/02packages.details.txt.gz"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andReturn();
        String text = RawServerFixtures.gunzip(index.getResponse().getContentAsByteArray());
        assertThat(text).startsWith("File:         02packages.details.txt")
                .contains("Line-Count:   1\n")
                .containsPattern("Acme::Hello +1\\.00 +A/AB/ABC/Acme-Hello-1\\.00\\.tar\\.gz\n");
        mvc.perform(get("/repository/cpan-hosted/modules/02packages.details.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(text));
        mvc.perform(get("/repository/cpan-hosted/authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/gzip"));
        mvc.perform(get("/api/v1/repositories/cpan-hosted/components").header("Authorization", adminBearer()))
                .andExpect(content().string(containsString("Acme-Hello")));

        mvc.perform(delete("/repository/cpan-hosted/authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz").header("Authorization", adminBasic()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/repository/cpan-hosted/modules/02packages.details.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Line-Count:   0\n")));
    }

    @Test
    void cranHostedRegeneratesPackages() throws Exception {
        hosted("cran-hosted", "cran");
        byte[] tgz = RawServerFixtures.tarGz("praise/DESCRIPTION",
                "Package: praise\nVersion: 1.0.0\nDepends: R (>= 3.0.0)\nImports: utils\nLicense: MIT + file LICENSE\nNeedsCompilation: no\n",
                "praise/R/praise.R", "praise <- function() \"nice\"\n");
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(tgz));
        mvc.perform(put("/repository/cran-hosted/src/contrib/praise_1.0.0.tar.gz").header("Authorization", adminBasic())
                        .contentType("application/gzip").content(tgz))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/cran-hosted/src/contrib/PACKAGES"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(content().string(containsString("Package: praise\n")))
                .andExpect(content().string(containsString("Version: 1.0.0\n")))
                .andExpect(content().string(containsString("Imports: utils\n")))
                .andExpect(content().string(containsString("MD5sum: " + md5 + "\n")));
        MvcResult gz = mvc.perform(get("/repository/cran-hosted/src/contrib/PACKAGES.gz")).andExpect(status().isOk()).andReturn();
        assertThat(RawServerFixtures.gunzip(gz.getResponse().getContentAsByteArray())).contains("Package: praise\n");
        mvc.perform(get("/repository/cran-hosted/src/contrib/Archive/praise/praise_1.0.0.tar.gz")).andExpect(status().isOk());
        mvc.perform(get("/repository/cran-hosted/src/contrib/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("praise_1.0.0.tar.gz")));
    }
}
