package dev.granary.server.go;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static dev.granary.server.go.GoFixtures.bytes;
import static dev.granary.server.go.GoFixtures.curlPut;
import static dev.granary.server.go.GoFixtures.goGet;
import static dev.granary.server.go.GoFixtures.moduleZip;
import static dev.granary.server.go.GoFixtures.sumdbLookup;
import static dev.granary.server.go.GoFixtures.text;
import static dev.granary.server.go.GoFixtures.upstreamInfo;
import static dev.granary.server.go.GoFixtures.utf8;
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
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * A proxy in front of a fake proxy.golang.org (and a fake sum.golang.org reached through {@code go.sumdbUrl}):
 * caching, case encoding, negative caching of 404 and 410, the checksum-database passthrough and offline mode.
 */
class GoProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "go-proxy";
    private static final String QUOTE = "rsc.io/quote";
    private static final String LIST = "v1.5.0\nv1.5.1\nv1.5.2\n";
    private static final String MOD = "module rsc.io/quote\n\nrequire rsc.io/sampler v1.3.0\n";
    private static final String INFO = upstreamInfo("v1.5.2", "2018-02-14T15:44:20Z");
    private static final byte[] ZIP = moduleZip(QUOTE, "v1.5.2");
    private static final String LOOKUP = sumdbLookup(QUOTE, "v1.5.2");
    private static final String TREE_HEAD = "go.sum database tree\n42000000\nO6x0Wnxo8yRvGUCzjGAc6a5vH3xHfNn1J6M2hBu4kBE=\n\n— sum.golang.org Az3g\n";
    private static final byte[] TILE = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};

    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private StorageService storage;

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
    void setUp() {
        upstream.resetAll();
        stubHealthyUpstream();
        repo = createProxy(REPO, null);
    }

    /** A proxy of the fake GOPROXY whose checksum database is the fake sum.golang.org. */
    private Repository createProxy(String name, Integer metadataMaxAgeMinutes) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat("go");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(upstream.baseUrl() + "/");
        r.setMetadataMaxAgeMinutes(metadataMaxAgeMinutes);
        r.setFormatSettings(Map.of("go.sumdbUrl", upstream.baseUrl() + "/sumdb-upstream"));
        Repository created = repositories.create(r);
        proxyService.invalidate(created);
        return created;
    }

    private static void stubHealthyUpstream() {
        upstream.stubFor(WireMock.get(urlEqualTo("/rsc.io/quote/@v/list"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain; charset=UTF-8")
                        .withHeader("ETag", "\"list-1\"").withBody(LIST)));
        upstream.stubFor(WireMock.get(urlEqualTo("/rsc.io/quote/@v/list")).withHeader("If-None-Match", equalTo("\"list-1\""))
                .atPriority(1)
                .willReturn(aResponse().withStatus(304)));
        upstream.stubFor(WireMock.get(urlEqualTo("/rsc.io/quote/@v/v1.5.2.info"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(INFO)));
        upstream.stubFor(WireMock.get(urlEqualTo("/rsc.io/quote/@v/v1.5.2.mod"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain; charset=UTF-8").withBody(MOD)));
        upstream.stubFor(WireMock.get(urlEqualTo("/rsc.io/quote/@v/v1.5.2.zip"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(ZIP)));
        upstream.stubFor(WireMock.get(urlEqualTo("/rsc.io/quote/@latest"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(INFO)));
        upstream.stubFor(WireMock.get(urlEqualTo("/rsc.io/quote/@v/master.info"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(upstreamInfo("v1.5.3-pre1", "2019-01-01T00:00:00Z"))));
        upstream.stubFor(WireMock.get(urlEqualTo("/github.com/!burnt!sushi/toml/@v/list"))
                .willReturn(aResponse().withStatus(200).withBody("v1.3.2\nv1.4.0\n")));
        upstream.stubFor(WireMock.get(urlEqualTo("/github.com/!burnt!sushi/toml/@v/v1.3.2.mod"))
                .willReturn(aResponse().withStatus(200).withBody("module github.com/BurntSushi/toml\n\ngo 1.16\n")));
        upstream.stubFor(WireMock.get(urlEqualTo("/example.com/missing/@v/list"))
                .willReturn(notFound().withBody("not found: module example.com/missing: no matching versions")));
        upstream.stubFor(WireMock.get(urlEqualTo("/example.com/gone/@v/v1.0.0.info"))
                .willReturn(aResponse().withStatus(410).withBody("not found: example.com/gone@v1.0.0: invalid version")));
        upstream.stubFor(WireMock.get(urlEqualTo("/sumdb-upstream/lookup/rsc.io/quote@v1.5.2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain; charset=UTF-8").withBody(LOOKUP)));
        upstream.stubFor(WireMock.get(urlEqualTo("/sumdb-upstream/tile/8/0/x164/062.p/128"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(TILE)));
        upstream.stubFor(WireMock.get(urlEqualTo("/sumdb-upstream/latest"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain; charset=UTF-8").withBody(TREE_HEAD)));
    }

    private static void breakUpstream() {
        upstream.resetAll();
        upstream.stubFor(any(anyUrl()).willReturn(serverError().withBody("upstream is on fire")));
    }

    @Test
    void theVersionListIsServedByteForByteAndCachedAsMetadata() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(text(mvc.perform(goGet(REPO, QUOTE + "/@v/list"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                    .andExpect(header().string("Cache-Control", "no-cache"))
                    .andReturn())).isEqualTo(LIST);
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/rsc.io/quote/@v/list")));
        assertThat(storage.find(repo, QUOTE + "/@v/list").orElseThrow().asset().isMetadata()).isTrue();
    }

    @Test
    void metadataIsRevalidatedConditionallyAfterItsMaxAge() throws Exception {
        createProxy("go-proxy-eager", 0);
        for (int i = 0; i < 3; i++) {
            assertThat(text(mvc.perform(goGet("go-proxy-eager", QUOTE + "/@v/list")).andExpect(status().isOk()).andReturn())).isEqualTo(LIST);
            mvc.perform(goGet("go-proxy-eager", QUOTE + "/@latest")).andExpect(status().isOk()).andExpect(jsonPath("$.Version").value("v1.5.2"));
        }
        upstream.verify(3, getRequestedFor(urlEqualTo("/rsc.io/quote/@v/list")));
        upstream.verify(2, getRequestedFor(urlEqualTo("/rsc.io/quote/@v/list")).withHeader("If-None-Match", equalTo("\"list-1\"")));
        upstream.verify(3, getRequestedFor(urlEqualTo("/rsc.io/quote/@latest")));
        // a branch or commit query resolves to a different version over time: metadata as well
        mvc.perform(goGet("go-proxy-eager", QUOTE + "/@v/master.info")).andExpect(jsonPath("$.Version").value("v1.5.3-pre1"));
        mvc.perform(goGet("go-proxy-eager", QUOTE + "/@v/master.info")).andExpect(status().isOk());
        upstream.verify(2, getRequestedFor(urlEqualTo("/rsc.io/quote/@v/master.info")));
    }

    @Test
    void moduleFilesAreFetchedOnceAndCachedForever() throws Exception {
        createProxy("go-proxy-eager", 0); // even with metadata re-validated on every request
        for (int i = 0; i < 2; i++) {
            mvc.perform(goGet("go-proxy-eager", QUOTE + "/@v/v1.5.2.info"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().string(INFO));
            mvc.perform(goGet("go-proxy-eager", QUOTE + "/@v/v1.5.2.mod")).andExpect(status().isOk()).andExpect(content().string(MOD));
            assertThat(bytes(mvc.perform(goGet("go-proxy-eager", QUOTE + "/@v/v1.5.2.zip"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("application/zip"))
                    .andExpect(header().longValue("Content-Length", ZIP.length))
                    .andExpect(header().string("Cache-Control", containsString("max-age=")))
                    .andReturn())).isEqualTo(ZIP);
        }
        for (String ext : new String[] {"info", "mod", "zip"}) {
            upstream.verify(1, getRequestedFor(urlEqualTo("/rsc.io/quote/@v/v1.5.2." + ext)));
        }
        Repository eager = repositories.require("go-proxy-eager");
        assertThat(storage.find(eager, QUOTE + "/@v/v1.5.2.zip")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(2);
        });
    }

    @Test
    void caseEncodedPathsTravelUpstreamUnchanged() throws Exception {
        mvc.perform(goGet(REPO, "github.com/!burnt!sushi/toml/@v/list")).andExpect(status().isOk()).andExpect(content().string("v1.3.2\nv1.4.0\n"));
        mvc.perform(goGet(REPO, "github.com/!burnt!sushi/toml/@v/v1.3.2.mod"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("module github.com/BurntSushi/toml")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/github.com/!burnt!sushi/toml/@v/v1.3.2.mod")));
        assertThat(storage.find(repo, "github.com/!burnt!sushi/toml/@v/v1.3.2.mod")).isPresent();
        // a raw upper-case path is never forwarded
        mvc.perform(goGet(REPO, "github.com/BurntSushi/toml/@v/list")).andExpect(status().isBadRequest());
        upstream.verify(0, getRequestedFor(urlEqualTo("/github.com/BurntSushi/toml/@v/list")));
    }

    @Test
    void notFoundAndGoneAreBoth404AndRemembered() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(goGet(REPO, "example.com/missing/@v/list"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("content.notFound"));
            mvc.perform(goGet(REPO, "example.com/gone/@v/v1.0.0.info")).andExpect(status().isNotFound());
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/example.com/missing/@v/list")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/example.com/gone/@v/v1.0.0.info")));
    }

    @Test
    void everythingCachedIsServedWhileUpstreamIsDown() throws Exception {
        createProxy("go-proxy-eager", 0);
        String eager = "go-proxy-eager";
        mvc.perform(goGet(eager, QUOTE + "/@v/list")).andExpect(status().isOk());
        mvc.perform(goGet(eager, QUOTE + "/@latest")).andExpect(status().isOk());
        for (String ext : new String[] {"info", "mod", "zip"}) {
            mvc.perform(goGet(eager, QUOTE + "/@v/v1.5.2." + ext)).andExpect(status().isOk());
        }
        mvc.perform(goGet(eager, "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2")).andExpect(status().isOk());
        mvc.perform(goGet(eager, "sumdb/sum.golang.org/latest")).andExpect(status().isOk());

        breakUpstream();

        assertThat(text(mvc.perform(goGet(eager, QUOTE + "/@v/list")).andExpect(status().isOk()).andReturn())).isEqualTo(LIST);
        upstream.verify(1, getRequestedFor(urlEqualTo("/rsc.io/quote/@v/list"))); // it did try
        mvc.perform(goGet(eager, QUOTE + "/@latest")).andExpect(status().isOk()).andExpect(jsonPath("$.Version").value("v1.5.2"));
        assertThat(bytes(mvc.perform(goGet(eager, QUOTE + "/@v/v1.5.2.zip")).andExpect(status().isOk()).andReturn())).isEqualTo(ZIP);
        mvc.perform(goGet(eager, QUOTE + "/@v/v1.5.2.mod")).andExpect(status().isOk()).andExpect(content().string(MOD));
        mvc.perform(goGet(eager, "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2")).andExpect(content().bytes(utf8(LOOKUP)));
        mvc.perform(goGet(eager, "sumdb/sum.golang.org/latest")).andExpect(content().bytes(utf8(TREE_HEAD)));
        mvc.perform(goGet(eager, "sumdb/sum.golang.org/supported")).andExpect(status().isOk());

        // what was never cached is a temporary failure, so the go command reports it instead of trying the next path
        mvc.perform(goGet(eager, "golang.org/x/text/@v/list"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(goGet(eager, QUOTE + "/@v/v1.5.1.zip")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void anUnreachableUpstreamWithNothingCachedIs503() throws Exception {
        Repository r = new Repository();
        r.setName("go-dead");
        r.setFormat("go");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl("http://127.0.0.1:1/");
        proxyService.invalidate(repositories.create(r));
        mvc.perform(goGet("go-dead", QUOTE + "/@v/v1.5.2.info"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(goGet("go-dead", QUOTE + "/@v/list")).andExpect(status().isServiceUnavailable());
        // the sumdb handshake needs no upstream
        mvc.perform(goGet("go-dead", "sumdb/sum.golang.org/supported")).andExpect(status().isOk());
    }

    @Test
    void offlineModeServesTheCacheAndNeverTouchesUpstream() throws Exception {
        mvc.perform(goGet(REPO, QUOTE + "/@v/list")).andExpect(status().isOk());
        mvc.perform(goGet(REPO, QUOTE + "/@v/v1.5.2.zip")).andExpect(status().isOk());
        mvc.perform(goGet(REPO, "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2")).andExpect(status().isOk());
        upstream.resetAll();
        repositories.setOffline(REPO, true);

        mvc.perform(goGet(REPO, QUOTE + "/@v/list")).andExpect(status().isOk()).andExpect(content().string(LIST));
        assertThat(bytes(mvc.perform(goGet(REPO, QUOTE + "/@v/v1.5.2.zip")).andExpect(status().isOk()).andReturn())).isEqualTo(ZIP);
        mvc.perform(goGet(REPO, "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2")).andExpect(content().bytes(utf8(LOOKUP)));
        mvc.perform(goGet(REPO, QUOTE + "/@v/v1.5.2.mod"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        mvc.perform(goGet(REPO, "sumdb/sum.golang.org/tile/8/0/x164/062.p/128"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, anyRequestedFor(anyUrl()));

        repositories.setOffline(REPO, false);
        stubHealthyUpstream();
        mvc.perform(goGet(REPO, QUOTE + "/@v/v1.5.2.mod")).andExpect(status().isOk());

        // the server-wide switch behaves the same way
        settings.setGlobalOffline(true);
        mvc.perform(goGet(REPO, QUOTE + "/@v/v1.5.2.mod")).andExpect(status().isOk());
        mvc.perform(goGet(REPO, QUOTE + "/@v/v1.5.2.info"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
    }

    @Test
    void theChecksumDatabaseIsPassedThroughAndCached() throws Exception {
        mvc.perform(goGet(REPO, "sumdb/sum.golang.org/supported"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", 0))
                .andExpect(content().bytes(new byte[0]));
        upstream.verify(0, anyRequestedFor(anyUrl()));

        for (int i = 0; i < 2; i++) {
            mvc.perform(goGet(REPO, "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(utf8(LOOKUP)));
            assertThat(bytes(mvc.perform(goGet(REPO, "sumdb/sum.golang.org/tile/8/0/x164/062.p/128"))
                    .andExpect(status().isOk()).andReturn())).isEqualTo(TILE);
        }
        // lookups and tiles never change: fetched once
        upstream.verify(1, getRequestedFor(urlEqualTo("/sumdb-upstream/lookup/rsc.io/quote@v1.5.2")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/sumdb-upstream/tile/8/0/x164/062.p/128")));
        assertThat(storage.find(repo, "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2").orElseThrow().asset().isMetadata()).isFalse();

        // the signed tree head moves: metadata
        mvc.perform(goGet(REPO, "sumdb/sum.golang.org/latest")).andExpect(status().isOk()).andExpect(content().bytes(utf8(TREE_HEAD)));
        assertThat(storage.find(repo, "sumdb/sum.golang.org/latest").orElseThrow().asset().isMetadata()).isTrue();

        // an unknown record is a remembered 404
        upstream.stubFor(WireMock.get(urlEqualTo("/sumdb-upstream/lookup/example.com/private@v1.0.0"))
                .willReturn(notFound().withBody("not found")));
        for (int i = 0; i < 2; i++) {
            mvc.perform(goGet(REPO, "sumdb/sum.golang.org/lookup/example.com/private@v1.0.0")).andExpect(status().isNotFound());
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/sumdb-upstream/lookup/example.com/private@v1.0.0")));
    }

    @Test
    void otherChecksumDatabasesAreNeverFetched() throws Exception {
        for (String name : new String[] {"sum.example.com", "localhost", "169.254.169.254", "internal.corp"}) {
            mvc.perform(goGet(REPO, "sumdb/" + name + "/supported")).andExpect(status().isNotFound());
            mvc.perform(goGet(REPO, "sumdb/" + name + "/latest")).andExpect(status().isNotFound());
            mvc.perform(goGet(REPO, "sumdb/" + name + "/lookup/rsc.io/quote@v1.5.2")).andExpect(status().isNotFound());
        }
        mvc.perform(goGet(REPO, "sumdb/sum.golang.org/../../etc/passwd")).andExpect(status().isBadRequest());
        upstream.verify(0, anyRequestedFor(anyUrl()));

        // a proxy can be told to pass another database through instead
        Repository custom = repositories.require(REPO);
        custom.setFormatSettings(Map.of("go.sumdbName", "sum.example.com", "go.sumdbUrl", upstream.baseUrl() + "/sumdb-upstream"));
        repositories.update(REPO, custom);
        mvc.perform(goGet(REPO, "sumdb/sum.example.com/supported")).andExpect(status().isOk());
        mvc.perform(goGet(REPO, "sumdb/sum.golang.org/supported")).andExpect(status().isNotFound());
        mvc.perform(goGet(REPO, "sumdb/sum.example.com/latest")).andExpect(status().isOk()).andExpect(content().bytes(utf8(TREE_HEAD)));
        upstream.verify(1, getRequestedFor(urlPathMatching("/sumdb-upstream/latest")));
    }

    @Test
    void upstreamCredentialsAreNeverSentToAChecksumDatabaseOnAnotherHost() throws Exception {
        // a private GOPROXY that needs a token, with sum.golang.org (here: the same WireMock under another host name)
        Repository r = new Repository();
        r.setName("go-private-upstream");
        r.setFormat("go");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(upstream.baseUrl() + "/");
        r.setRemoteToken("upstream-secret");
        r.setFormatSettings(Map.of("go.sumdbUrl", "http://127.0.0.1:" + upstream.port() + "/sumdb-upstream"));
        proxyService.invalidate(repositories.create(r));

        mvc.perform(goGet("go-private-upstream", QUOTE + "/@v/v1.5.2.mod")).andExpect(status().isOk());
        mvc.perform(goGet("go-private-upstream", "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2")).andExpect(content().bytes(utf8(LOOKUP)));
        mvc.perform(goGet("go-private-upstream", "sumdb/sum.golang.org/latest")).andExpect(status().isOk());
        upstream.verify(getRequestedFor(urlEqualTo("/rsc.io/quote/@v/v1.5.2.mod")).withHeader("Authorization", equalTo("Bearer upstream-secret")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/sumdb-upstream/lookup/rsc.io/quote@v1.5.2")).withHeader("Authorization", WireMock.absent()));
        upstream.verify(1, getRequestedFor(urlEqualTo("/sumdb-upstream/latest")).withHeader("Authorization", WireMock.absent()));
        // the cached answer belongs to the proxy like everything else it fetched
        assertThat(storage.find(repositories.require("go-private-upstream"), "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2")).isPresent();

        // a checksum database served by the upstream itself gets the credentials
        Repository sameHost = repositories.require("go-private-upstream");
        sameHost.setFormatSettings(Map.of("go.sumdbUrl", upstream.baseUrl() + "/sumdb-upstream"));
        repositories.update("go-private-upstream", sameHost);
        upstream.stubFor(WireMock.get(urlEqualTo("/sumdb-upstream/tile/8/0/x164/062.p/128"))
                .willReturn(aResponse().withStatus(200).withBody(TILE)));
        mvc.perform(goGet("go-private-upstream", "sumdb/sum.golang.org/tile/8/0/x164/062.p/128")).andExpect(content().bytes(TILE));
        upstream.verify(1, getRequestedFor(urlEqualTo("/sumdb-upstream/tile/8/0/x164/062.p/128"))
                .withHeader("Authorization", equalTo("Bearer upstream-secret")));
    }

    @Test
    void invalidSumdbSettingsAreRefused() throws Exception {
        Repository custom = repositories.require(REPO);
        custom.setFormatSettings(Map.of("go.sumdbUrl", "sum.golang.org"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repositories.update(REPO, custom))
                .hasMessageContaining("go.sumdbUrl");
        custom.setFormatSettings(Map.of("go.sumdbName", "https://sum.golang.org"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repositories.update(REPO, custom))
                .hasMessageContaining("go.sumdbName");
    }

    @Test
    void proxiesAreReadOnly() throws Exception {
        mvc.perform(curlPut(REPO, QUOTE + "/@v/v1.5.3.zip", moduleZip(QUOTE, "v1.5.3")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"))
                .andExpect(jsonPath("$.message").value(containsString("proxy repository")));
        mvc.perform(curlPut(REPO, QUOTE + "/@v/v1.5.2.info", utf8(INFO)).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/repository/" + REPO + "/" + QUOTE + "/@v/v1.5.2").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"));
        upstream.verify(0, anyRequestedFor(anyUrl()));
    }
}
