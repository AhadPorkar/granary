package dev.granary.server.conan;

import static dev.granary.server.conan.ConanFixtures.PID;
import static dev.granary.server.conan.ConanFixtures.PREV;
import static dev.granary.server.conan.ConanFixtures.RREV;
import static dev.granary.server.conan.ConanFixtures.packageFiles;
import static dev.granary.server.conan.ConanFixtures.packageRevision;
import static dev.granary.server.conan.ConanFixtures.recipe;
import static dev.granary.server.conan.ConanFixtures.recipeFiles;
import static dev.granary.server.conan.ConanFixtures.revision;
import static dev.granary.server.conan.ConanFixtures.sha1;
import static dev.granary.server.conan.ConanFixtures.upload;
import static dev.granary.server.conan.ConanFixtures.utf8;
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
import dev.granary.core.model.ApiToken;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Requests no Conan client sends but anybody can: oversized names, encoded separators, wildcard bombs, HEAD and
 * conditional requests on every route, methods a route does not have. Each must be a cheap, well-formed answer,
 * never a 500, a busy thread or a write to an unexpected path.
 */
class ConanEdgeCaseIntegrationTest extends IntegrationTest {

    private static final String HOSTED = "/repository/conan-hosted";
    private static final String GROUP = "/repository/conan-group";
    private static final String PROXY = "/repository/conancenter";
    private static final String ZLIB = recipe("zlib", "1.3.1");
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    private Repository hosted;

    @BeforeAll
    static void startUpstream() {
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void createRepositories() {
        upstream.resetAll();
        hosted = hosted("conan-hosted", "conan");
        proxyService.invalidate(proxy("conancenter", "conan", upstream.baseUrl()));
        group("conan-group", "conan", "conan-hosted", "conancenter");
    }

    @Test
    void overlongNamesAndFileNamesAre400NotDatabaseErrors() throws Exception {
        String tooLong = "a".repeat(129);
        String deepFile = "metadata/" + String.join("/", java.util.Collections.nCopies(12, "d".repeat(200)));
        for (String path : List.of(
                "/v2/conans/zlib/" + tooLong + "/_/_/revisions/" + RREV + "/files/conanfile.py",
                "/v2/conans/" + tooLong + "/1.0/_/_/revisions/" + RREV + "/files/conanfile.py",
                "/v2/conans/zlib/1.0/" + tooLong + "/stable/revisions/" + RREV + "/files/conanfile.py",
                revision(ZLIB, tooLong) + "/files/conanfile.py",
                packageRevision(ZLIB, RREV, tooLong, PREV) + "/files/conaninfo.txt")) {
            mvc.perform(upload(HOSTED + path, utf8("x"), adminBasic()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].status").value(400));
        }
        // Paths longer than the storage limit are refused by the dispatcher before the handler runs.
        mvc.perform(upload(HOSTED + revision(ZLIB, RREV) + "/files/" + deepFile, utf8("x"), adminBasic()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("path.invalid"));
        mvc.perform(get(HOSTED + "/v2/conans/" + tooLong + "/1.0/_/_/latest")).andExpect(status().isBadRequest());
        mvc.perform(get(GROUP + "/v2/conans/" + tooLong + "/1.0/_/_/latest")).andExpect(status().isBadRequest());
        assertThat(storage.list(hosted, "")).isEmpty();
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void encodedSlashesNeverShiftTheSegments() throws Exception {
        // decoded, a%2Fb/1.0/_ would be the valid reference a/b@1.0/_ followed by a revision route
        for (String raw : List.of("/v2/conans/a%2Fb/1.0/_/revisions/" + RREV + "/files/conanfile.py",
                "/v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/metadata%2Fsign.txt",
                "/v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/metadata%2fsign.txt")) {
            mvc.perform(put(URI.create(HOSTED + raw)).header("Authorization", adminBasic())
                            .header("X-Checksum-Sha1", sha1(utf8("x"))).content(utf8("x")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("path.invalid"));
        }
        // an encoded backslash is already refused before the handler (the firewall), still with a 400
        mvc.perform(put(URI.create(HOSTED + "/v2/conans/zlib/1.3.1/_/_/revisions/" + RREV + "/files/a%5cb"))
                        .header("Authorization", adminBasic()).content(utf8("x")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URI.create(GROUP + "/v2/conans/a%2Fb/1.0/_/latest"))).andExpect(status().isBadRequest());
        assertThat(storage.list(hosted, "")).isEmpty();
    }

    @Test
    void wildcardBombsAreAnsweredQuicklyAndHugePatternsRefused() throws Exception {
        publish(recipe("a-rather-long-recipe-name-with-many-letters", "1.0.0", "company", "stable"));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/conans/search"))
                .willReturn(WireMock.okJson("{\"results\":[]}")));
        String bomb = "*?".repeat(16) + "X";
        MvcResult result = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> mvc.perform(get(GROUP + "/v2/conans/search").param("q", bomb)).andReturn());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(ConanFixtures.json(result).path("results")).isEmpty();

        mvc.perform(get(HOSTED + "/v2/conans/search").param("q", "z".repeat(513)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("search.patternTooLong"));
        mvc.perform(get(PROXY + "/v2/conans/search").param("q", "z".repeat(513))).andExpect(status().isBadRequest());
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/conans/search"))
                .withQueryParam("q", WireMock.matching("z+")));
    }

    @Test
    void headOnEveryReadMatchesTheGetWithoutABody() throws Exception {
        publish(ZLIB);
        String files = packageRevision(ZLIB, RREV, PID, PREV) + "/files";
        for (String repo : List.of(HOSTED, GROUP)) {
            for (String path : List.of("", "/v1/ping", "/v2/conans/search?q=zlib", ZLIB + "/latest", ZLIB + "/revisions",
                    ZLIB + "/search", revision(ZLIB, RREV) + "/files", revision(ZLIB, RREV) + "/files/conanfile.py",
                    revision(ZLIB, RREV) + "/search", revision(ZLIB, RREV) + "/packages/" + PID + "/latest",
                    revision(ZLIB, RREV) + "/packages/" + PID + "/revisions", files, files + "/conan_package.tgz")) {
                MvcResult get = mvc.perform(get(URI.create(repo + path))).andReturn();
                MvcResult head = mvc.perform(head(URI.create(repo + path))).andReturn();
                assertThat(get.getResponse().getStatus()).as("GET " + repo + path).isEqualTo(200);
                assertThat(head.getResponse().getStatus()).as("HEAD " + repo + path).isEqualTo(200);
                assertThat(head.getResponse().getContentAsByteArray()).as("HEAD " + repo + path).isEmpty();
                assertThat(head.getResponse().getContentType()).as(repo + path).isEqualTo(get.getResponse().getContentType());
                assertThat(head.getResponse().getHeader("ETag")).as(repo + path).isEqualTo(get.getResponse().getHeader("ETag"));
                String etag = get.getResponse().getHeader("ETag");
                if (etag != null) {
                    assertThat(get.getResponse().getHeader("Content-Length")).as(repo + path)
                            .isEqualTo(String.valueOf(get.getResponse().getContentAsByteArray().length));
                    for (String condition : List.of(etag, "*")) {
                        assertThat(mvc.perform(get(URI.create(repo + path)).header("If-None-Match", condition)).andReturn()
                                .getResponse().getStatus()).as(condition + " " + repo + path).isEqualTo(304);
                    }
                }
            }
        }
    }

    @Test
    void headOnLoginCreatesNoToken() throws Exception {
        mvc.perform(head(HOSTED + "/v2/users/authenticate").header("Authorization", adminBasic())).andExpect(status().isOk());
        assertThat(tokens.list(users.require(ADMIN))).extracting(ApiToken::getName).doesNotContain("conan");
        mvc.perform(get(HOSTED + "/v2/users/authenticate").header("Authorization", adminBasic())).andExpect(status().isOk());
        assertThat(tokens.list(users.require(ADMIN))).extracting(ApiToken::getName).contains("conan");
    }

    @Test
    void unsupportedMethodsAre405WithAnAllowHeader() throws Exception {
        mvc.perform(post(HOSTED + revision(ZLIB, RREV) + "/files/conanfile.py").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET, HEAD, PUT"));
        mvc.perform(put(HOSTED + ZLIB + "/latest").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET, HEAD"))
                .andExpect(jsonPath("$.errors[0].status").value(405));
        mvc.perform(put(HOSTED + "/v2/users/authenticate").header("Authorization", adminBasic()))
                .andExpect(header().string("Allow", "GET, HEAD, POST"));
    }

    @Test
    void metadataFilesAreNeverCachedForAYear() throws Exception {
        publish(ZLIB);
        String log = HOSTED + revision(ZLIB, RREV) + "/files/metadata/logs/build.log";
        mvc.perform(upload(log, utf8("first"), adminBasic())).andExpect(status().isCreated());
        mvc.perform(get(log)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-cache"));
        mvc.perform(get(HOSTED + revision(ZLIB, RREV) + "/files/conanfile.py"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age")));
    }

    @Test
    void searchesThroughAnOfflineProxyAreServedFromTheCacheOr503() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/conans/search")).withQueryParam("q", WireMock.equalTo("zlib"))
                .willReturn(WireMock.okJson("{\"results\":[\"zlib/1.3.1@_/_\"]}")));
        mvc.perform(get(PROXY + "/v2/conans/search").param("q", "zlib")).andExpect(jsonPath("$.results[0]").value("zlib/1.3.1"));
        publish(recipe("acme", "1.0"));

        settings.setGlobalOffline(true);
        mvc.perform(get(PROXY + "/v2/conans/search").param("q", "zlib")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0]").value("zlib/1.3.1"));
        // never asked before: "nothing found" would be a lie, so the client is told the upstream is offline
        mvc.perform(get(PROXY + "/v2/conans/search").param("q", "openssl"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        // the group answers with what its hosted member knows
        mvc.perform(get(GROUP + "/v2/conans/search").param("q", "acme")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0]").value("acme/1.0"));
        settings.setGlobalOffline(false);

        // an upstream that answers 404 to a search means "no results"
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/conans/search")).withQueryParam("q", WireMock.equalTo("boost"))
                .willReturn(WireMock.notFound()));
        mvc.perform(get(PROXY + "/v2/conans/search").param("q", "boost")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isEmpty());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/conans/search"))
                .withQueryParam("q", WireMock.equalTo("zlib")));
    }

    private void publish(String recipe) throws Exception {
        for (Map.Entry<String, byte[]> file : recipeFiles(recipe).entrySet()) {
            mvc.perform(upload(HOSTED + revision(recipe, RREV) + "/files/" + file.getKey(), file.getValue(), adminBasic()))
                    .andExpect(status().is2xxSuccessful());
        }
        for (Map.Entry<String, byte[]> file : packageFiles(recipe).entrySet()) {
            mvc.perform(upload(HOSTED + packageRevision(recipe, RREV, PID, PREV) + "/files/" + file.getKey(), file.getValue(),
                    adminBasic())).andExpect(status().is2xxSuccessful());
        }
    }
}
