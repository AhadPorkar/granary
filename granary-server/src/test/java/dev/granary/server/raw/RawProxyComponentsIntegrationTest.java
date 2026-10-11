package dev.granary.server.raw;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static dev.granary.server.testsupport.ComponentApi.gav;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import dev.granary.server.testsupport.ComponentApi;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * Files the raw-family proxies (raw, cdn, cran, hackage, cpan, elm) cache are components with the coordinates an
 * upload of the same path gets; the indexes each profile treats as metadata are not.
 */
class RawProxyComponentsIntegrationTest extends IntegrationTest {

    private static final String CRAN_TARBALL = "src/contrib/praise_1.0.0.tar.gz";
    private static final byte[] PRAISE = RawServerFixtures.tarGz("praise/DESCRIPTION", "Package: praise\nVersion: 1.0.0\n");

    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private StorageService storage;

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
    void setUp() {
        upstream.resetAll();
        stub("/shared/from-proxy.txt", "proxy".getBytes(StandardCharsets.UTF_8));
        stub("/dists/stable/Release", "release".getBytes(StandardCharsets.UTF_8));
        upstream.stubFor(WireMock.get(urlEqualTo("/npm/lodash/lodash.min.js"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", upstream.baseUrl() + "/npm/lodash@4.17.21/lodash.min.js")));
        stub("/npm/lodash@4.17.21/lodash.min.js", "/* lodash */".getBytes(StandardCharsets.UTF_8));
        stub("/src/contrib/PACKAGES", "Package: praise\nVersion: 1.0.0\n".getBytes(StandardCharsets.UTF_8));
        stub("/" + CRAN_TARBALL, PRAISE);
        stub("/01-index.tar.gz", "index".getBytes(StandardCharsets.UTF_8));
        stub("/package/aeson-2.1.0.0.tar.gz", "aeson".getBytes(StandardCharsets.UTF_8));
        stub("/modules/02packages.details.txt.gz", "packages".getBytes(StandardCharsets.UTF_8));
        stub("/authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz", "acme-hello".getBytes(StandardCharsets.UTF_8));
        stub("/packages/elm/core/1.0.5/elm.json", "{\"name\":\"elm/core\"}".getBytes(StandardCharsets.UTF_8));
        stub("/packages/elm/core/1.0.5/endpoint.json",
                "{\"url\":\"https://github.com/elm/core/zipball/1.0.5/\",\"hash\":\"4a9a1f5b\"}".getBytes(StandardCharsets.UTF_8));
        components = new ComponentApi(mvc, adminBearer());
    }

    @Test
    void everyProfileRecordsItsFilesAsComponentsAndItsIndexesAsNone() throws Exception {
        assertCaches("raw", List.of("dists/stable/Release"), "shared/from-proxy.txt", ":shared:from-proxy.txt");
        assertCaches("cdn", List.of("npm/lodash/lodash.min.js"), "npm/lodash@4.17.21/lodash.min.js", "npm:lodash:4.17.21");
        assertCaches("cran", List.of("src/contrib/PACKAGES"), CRAN_TARBALL, ":praise:1.0.0");
        assertCaches("hackage", List.of("01-index.tar.gz"), "package/aeson-2.1.0.0.tar.gz", ":aeson:2.1.0.0");
        assertCaches("cpan", List.of("modules/02packages.details.txt.gz"), "authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz",
                "ABC:Acme-Hello:1.00");
        assertCaches("elm", List.of("packages/elm/core/1.0.5/endpoint.json"), "packages/elm/core/1.0.5/elm.json", "elm:core:1.0.5");
    }

    /** Fetches the metadata paths and the file through a new proxy of {@code format}; the file alone is a component. */
    private void assertCaches(String format, List<String> metadata, String file, String expected) throws Exception {
        String name = format + "-proxy";
        Repository repo = proxy(name, format, upstream.baseUrl() + "/");
        for (String path : metadata) {
            fetch(name, path).andExpect(status().isOk());
        }
        fetch(name, file).andExpect(status().isOk());

        JsonNode component = components.single(name);
        assertThat(gav(component)).as(format).isEqualTo(expected);
        assertThat(component.path("format").asString()).isEqualTo(format);
        assertThat(components.assetPaths(name, component)).as(format).containsExactly(file);
        for (String path : metadata) {
            assertThat(storage.find(repo, path).orElseThrow().asset().getComponentId()).as(format + " " + path).isNull();
        }
    }

    @Test
    void cranArchiveDownloadsBelongToTheVersionTheyHold() throws Exception {
        String archived = "src/contrib/Archive/praise/praise_0.9.tar.gz";
        String formerlyCurrent = "src/contrib/Archive/praise/praise_1.0.0.tar.gz";
        stub("/" + archived, RawServerFixtures.tarGz("praise/DESCRIPTION", "Package: praise\nVersion: 0.9\n"));
        stub("/" + formerlyCurrent, PRAISE);
        proxy("cran-proxy", "cran", upstream.baseUrl() + "/");

        // R fetches every version but the current one from the package's archive directory
        fetch("cran-proxy", CRAN_TARBALL).andExpect(status().isOk());
        fetch("cran-proxy", archived).andExpect(status().isOk());
        fetch("cran-proxy", formerlyCurrent).andExpect(status().isOk());

        List<JsonNode> all = components.list("cran-proxy");
        assertThat(all).extracting(ComponentApi::gav).containsExactly(":praise:0.9", ":praise:1.0.0");
        assertThat(components.assetPaths("cran-proxy", all.get(0))).containsExactly(archived);
        // one version is one component, whether it was downloaded while current or after it moved to the archive
        assertThat(components.assetPaths("cran-proxy", all.get(1))).containsExactly(formerlyCurrent, CRAN_TARBALL);
    }

    @Test
    void repeatedAndConcurrentDownloadsKeepOneComponent() throws Exception {
        proxy("cran-proxy", "cran", upstream.baseUrl() + "/");
        List<Integer> statuses = ComponentApi.concurrently(8, i -> () ->
                fetch("cran-proxy", CRAN_TARBALL).andReturn().getResponse().getStatus());
        fetch("cran-proxy", CRAN_TARBALL).andExpect(status().isOk());

        assertThat(statuses).containsOnly(200);
        assertThat(gav(components.single("cran-proxy"))).isEqualTo(":praise:1.0.0");
        upstream.verify(1, getRequestedFor(urlEqualTo("/" + CRAN_TARBALL)));
    }

    @Test
    void searchFindsCachedPackages() throws Exception {
        proxy("cran-proxy", "cran", upstream.baseUrl() + "/");
        proxy("hackage-proxy", "hackage", upstream.baseUrl() + "/");
        fetch("cran-proxy", CRAN_TARBALL).andExpect(status().isOk());
        fetch("hackage-proxy", "package/aeson-2.1.0.0.tar.gz").andExpect(status().isOk());

        assertThat(components.search("praise")).extracting(c -> c.path("repository").asString()).containsExactly("cran-proxy");
        assertThat(components.search("AESON")).extracting(ComponentApi::gav).containsExactly(":aeson:2.1.0.0");
    }

    @Test
    void deletingTheComponentRemovesTheFileWhichComesBackOnlineButNotOffline() throws Exception {
        Repository repo = proxy("cran-proxy", "cran", upstream.baseUrl() + "/");
        fetch("cran-proxy", "src/contrib/PACKAGES").andExpect(status().isOk());
        fetch("cran-proxy", CRAN_TARBALL).andExpect(status().isOk());

        assertThat(components.delete("cran-proxy", components.single("cran-proxy"))).isEqualTo(1);
        assertThat(storage.find(repo, CRAN_TARBALL)).isEmpty();

        repositories.setOffline("cran-proxy", true);
        fetch("cran-proxy", CRAN_TARBALL).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        fetch("cran-proxy", "src/contrib/PACKAGES").andExpect(status().isOk());
        assertThat(components.list("cran-proxy")).isEmpty();

        repositories.setOffline("cran-proxy", false);
        assertThat(fetch("cran-proxy", CRAN_TARBALL).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(PRAISE);
        assertThat(gav(components.single("cran-proxy"))).isEqualTo(":praise:1.0.0");
        upstream.verify(2, getRequestedFor(urlEqualTo("/" + CRAN_TARBALL)));
    }

    @Test
    void filesCachedBeforeComponentsWereRecordedAreLinkedWhenServed() throws Exception {
        Repository repo = proxy("hackage-proxy", "hackage", upstream.baseUrl() + "/");
        storage.store(repo, "package/aeson-2.1.0.0.tar.gz", new ByteArrayInputStream("old".getBytes(StandardCharsets.UTF_8)),
                "application/x-gzip", Asset.Origin.CACHED, false);
        repositories.setOffline("hackage-proxy", true);

        fetch("hackage-proxy", "package/aeson-2.1.0.0.tar.gz").andExpect(status().isOk()).andExpect(content().string("old"));

        assertThat(gav(components.single("hackage-proxy"))).isEqualTo(":aeson:2.1.0.0");
        upstream.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void downloadsThroughAGroupAreRecordedInTheProxyMember() throws Exception {
        proxy("raw-proxy", "raw", upstream.baseUrl() + "/");
        hosted("raw-hosted", "raw");
        group("raw-all", "raw", "raw-hosted", "raw-proxy");
        mvc.perform(MockMvcRequestBuilders.put("/repository/raw-hosted/own/file.txt").header("Authorization", adminBasic())
                .content("own")).andExpect(status().isCreated());

        fetch("raw-all", "shared/from-proxy.txt").andExpect(status().isOk()).andExpect(content().string("proxy"));
        fetch("raw-all", "own/file.txt").andExpect(status().isOk());

        assertThat(gav(components.single("raw-proxy"))).isEqualTo(":shared:from-proxy.txt");
        assertThat(gav(components.single("raw-hosted"))).isEqualTo(":own:file.txt");
        assertThat(components.list("raw-all")).isEmpty();
    }

    private ResultActions fetch(String repository, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/repository/" + repository + "/" + path));
    }

    private static void stub(String path, byte[] body) {
        upstream.stubFor(WireMock.get(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(body)));
    }
}
