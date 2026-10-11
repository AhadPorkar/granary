package dev.granary.server.go;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static dev.granary.server.go.GoFixtures.MODULE;
import static dev.granary.server.go.GoFixtures.bytes;
import static dev.granary.server.go.GoFixtures.curlPut;
import static dev.granary.server.go.GoFixtures.goGet;
import static dev.granary.server.go.GoFixtures.moduleZip;
import static dev.granary.server.go.GoFixtures.upstreamInfo;
import static dev.granary.server.testsupport.ComponentApi.gav;
import static org.assertj.core.api.Assertions.assertThat;
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
import tools.jackson.databind.JsonNode;

/**
 * The {@code .info}, {@code .mod} and {@code .zip} of a module version a Go proxy caches form the component a publish
 * of that version records (module path without its last element, that element, version); lists, {@code @latest} and
 * branch queries do not.
 */
class GoProxyComponentsIntegrationTest extends IntegrationTest {

    private static final String REPO = "go-proxy";
    private static final String QUOTE = "rsc.io/quote";
    private static final String V = QUOTE + "/@v/v1.5.2";
    private static final String INFO = upstreamInfo("v1.5.2", "2018-02-14T15:44:20Z");
    private static final String MOD = "module rsc.io/quote\n";
    private static final byte[] ZIP = moduleZip(QUOTE, "v1.5.2");
    private static final String TOML = "github.com/!burnt!sushi/toml";

    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private StorageService storage;

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
    void setUp() {
        upstream.resetAll();
        stub("/rsc.io/quote/@v/list", "text/plain", "v1.5.1\nv1.5.2\n".getBytes());
        stub("/rsc.io/quote/@latest", "application/json", INFO.getBytes());
        stub("/rsc.io/quote/@v/master.info", "application/json", upstreamInfo("v1.5.3-pre1", "2019-01-01T00:00:00Z").getBytes());
        stub("/rsc.io/quote/@v/v1.5.2.info", "application/json", INFO.getBytes());
        stub("/rsc.io/quote/@v/v1.5.2.mod", "text/plain", MOD.getBytes());
        stub("/rsc.io/quote/@v/v1.5.2.zip", "application/zip", ZIP);
        stub("/" + TOML + "/@v/v1.3.2.mod", "text/plain", "module github.com/BurntSushi/toml\n".getBytes());
        upstream.stubFor(WireMock.get(WireMock.urlMatching("/example\\.com/.*")).atPriority(10)
                .willReturn(WireMock.notFound().withBody("not found")));
        repo = proxy(REPO, "go", upstream.baseUrl() + "/");
        proxyService.invalidate(repo);
        components = new ComponentApi(mvc, adminBearer());
    }

    @Test
    void theFilesOfAVersionFormOneComponentAndListsAndQueriesNone() throws Exception {
        for (String path : List.of(QUOTE + "/@v/list", QUOTE + "/@latest", QUOTE + "/@v/master.info",
                V + ".info", V + ".mod", V + ".zip")) {
            mvc.perform(goGet(REPO, path)).andExpect(status().isOk());
        }

        JsonNode component = components.single(REPO);
        assertThat(gav(component)).isEqualTo("rsc.io:quote:v1.5.2");
        assertThat(component.path("format").asString()).isEqualTo("go");
        assertThat(components.assetPaths(REPO, component)).containsExactly(V + ".info", V + ".mod", V + ".zip");
        assertThat(storage.find(repo, QUOTE + "/@v/list").orElseThrow().asset().getComponentId()).isNull();
    }

    @Test
    void caseEncodedModulesAreNamedAsTheirModulePathIsSpelled() throws Exception {
        mvc.perform(goGet(REPO, TOML + "/@v/v1.3.2.mod")).andExpect(status().isOk());

        assertThat(gav(components.single(REPO))).isEqualTo("github.com/BurntSushi:toml:v1.3.2");
        assertThat(components.search("burntsushi")).extracting(ComponentApi::gav).containsExactly("github.com/BurntSushi:toml:v1.3.2");
    }

    @Test
    void repeatedAndConcurrentDownloadsKeepOneComponent() throws Exception {
        List<String> files = List.of(V + ".info", V + ".mod", V + ".zip");
        List<Integer> statuses = ComponentApi.concurrently(9, i -> () ->
                mvc.perform(goGet(REPO, files.get(i % 3))).andReturn().getResponse().getStatus());
        assertThat(bytes(mvc.perform(goGet(REPO, V + ".zip")).andExpect(status().isOk()).andReturn())).isEqualTo(ZIP);

        assertThat(statuses).containsOnly(200);
        assertThat(components.assetPaths(REPO, components.single(REPO))).containsExactly(V + ".info", V + ".mod", V + ".zip");
        for (String ext : List.of("info", "mod", "zip")) {
            upstream.verify(1, getRequestedFor(urlEqualTo("/rsc.io/quote/@v/v1.5.2." + ext)));
        }
    }

    @Test
    void searchFindsTheCachedModule() throws Exception {
        mvc.perform(goGet(REPO, V + ".zip")).andExpect(status().isOk());

        assertThat(components.search("quote")).extracting(ComponentApi::gav).containsExactly("rsc.io:quote:v1.5.2");
        assertThat(components.search("rsc.io")).extracting(c -> c.path("repository").asString()).containsExactly(REPO);
    }

    @Test
    void deletingTheComponentRemovesItsFilesWhichComeBackOnlineButNotOffline() throws Exception {
        mvc.perform(goGet(REPO, QUOTE + "/@v/list")).andExpect(status().isOk());
        mvc.perform(goGet(REPO, V + ".mod")).andExpect(status().isOk());
        mvc.perform(goGet(REPO, V + ".zip")).andExpect(status().isOk());

        assertThat(components.delete(REPO, components.single(REPO))).isEqualTo(2);
        assertThat(storage.find(repo, V + ".zip")).isEmpty();
        assertThat(storage.find(repo, QUOTE + "/@v/list")).isPresent();

        repositories.setOffline(REPO, true);
        mvc.perform(goGet(REPO, V + ".zip")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        mvc.perform(goGet(REPO, QUOTE + "/@v/list")).andExpect(status().isOk());
        assertThat(components.list(REPO)).isEmpty();

        repositories.setOffline(REPO, false);
        assertThat(bytes(mvc.perform(goGet(REPO, V + ".zip")).andExpect(status().isOk()).andReturn())).isEqualTo(ZIP);
        assertThat(gav(components.single(REPO))).isEqualTo("rsc.io:quote:v1.5.2");
        upstream.verify(2, getRequestedFor(urlEqualTo("/rsc.io/quote/@v/v1.5.2.zip")));
    }

    @Test
    void modulesCachedBeforeComponentsWereRecordedAreLinkedWhenServed() throws Exception {
        storage.store(repo, V + ".zip", new ByteArrayInputStream(ZIP), "application/zip", Asset.Origin.CACHED, false);
        repositories.setOffline(REPO, true);

        assertThat(bytes(mvc.perform(goGet(REPO, V + ".zip")).andExpect(status().isOk()).andReturn())).isEqualTo(ZIP);

        assertThat(gav(components.single(REPO))).isEqualTo("rsc.io:quote:v1.5.2");
        upstream.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void downloadsThroughAGroupAreRecordedInTheProxyMember() throws Exception {
        hosted("go-hosted", "go");
        group("go-all", "go", "go-hosted", REPO);
        mvc.perform(curlPut("go-hosted", MODULE + "/@v/v0.1.0.zip", moduleZip(MODULE, "v0.1.0"))
                .header("Authorization", adminBasic())).andExpect(status().isCreated());

        // @latest of a group reads the .info of the highest version from the member that has it
        mvc.perform(goGet("go-all", QUOTE + "/@latest")).andExpect(jsonPath("$.Version").value("v1.5.2"));
        mvc.perform(goGet("go-all", V + ".mod")).andExpect(status().isOk());
        mvc.perform(goGet("go-all", MODULE + "/@v/v0.1.0.zip")).andExpect(status().isOk());

        JsonNode cached = components.single(REPO);
        assertThat(gav(cached)).isEqualTo("rsc.io:quote:v1.5.2");
        assertThat(components.assetPaths(REPO, cached)).containsExactly(V + ".info", V + ".mod");
        assertThat(gav(components.single("go-hosted"))).isEqualTo("example.com/acme:hello:v0.1.0");
        assertThat(components.list("go-all")).isEmpty();
    }

    private static void stub(String path, String contentType, byte[] body) {
        upstream.stubFor(WireMock.get(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", contentType).withBody(body)));
    }
}
