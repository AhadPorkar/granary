package dev.granary.server.hex;

import static dev.granary.server.hex.HexFixtures.ERLANG;
import static dev.granary.server.hex.HexFixtures.USER_AGENT;
import static dev.granary.server.hex.HexFixtures.pkg;
import static dev.granary.server.hex.HexFixtures.release;
import static dev.granary.server.hex.HexFixtures.signedDocument;
import static dev.granary.server.hex.HexFixtures.term;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.format.hex.HexRegistry;
import dev.granary.format.hex.HexSigningKey;
import dev.granary.server.IntegrationTest;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A proxy of a fake repo.hex.pm (the real one is not reachable from the build environment): documents and
 * tarballs pass through byte for byte, so clients keep verifying hex.pm's signature with hex.pm's key
 * ({@code HEX_MIRROR}), and everything cached keeps working when the upstream breaks or the proxy is offline.
 */
class HexProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "hexpm";
    private static final String URL = "/repository/" + REPO;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static HexSigningKey hexpmKey;

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    private Repository repo;

    @BeforeAll
    static void startUpstream() throws Exception {
        upstream.start();
        hexpmKey = new HexSigningKey(Files.createTempDirectory("hexpm-key"));
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void createRepository() {
        upstream.resetAll();
        repo = proxy(REPO, "hex", upstream.baseUrl() + "/");
        proxyService.invalidate(repo);
    }

    static byte[] jasonDocument() {
        HexRegistry.Package jason = new HexRegistry.Package("jason", "hexpm", List.of(
                new HexRegistry.Release("1.4.3", new byte[32], List.of(), null, HexFixtures.sha256Hex(release("jason", "1.4.3"))
                        .getBytes()),
                new HexRegistry.Release("1.4.4", new byte[32], List.of(
                        new HexRegistry.Dependency("decimal", "~> 1.0 or ~> 2.0", true, "", "")), null, new byte[32])));
        return signedDocument(HexRegistry.encodePackage(jason), hexpmKey);
    }

    static void stub(String path, byte[] body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/octet-stream").withBody(body)));
    }

    private byte[] fetch(String path) throws Exception {
        return mvc.perform(get(URL + "/" + path).header("User-Agent", USER_AGENT)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void documentsPassThroughWithHexpmsSignature() throws Exception {
        byte[] doc = jasonDocument();
        stub("/packages/jason", doc);
        stub("/public_key", hexpmKey.publicKeyPem().getBytes());

        byte[] served = fetch("packages/jason");
        assertThat(served).isEqualTo(doc);
        String pem = new String(fetch("public_key"));
        assertThat(pem).isEqualTo(hexpmKey.publicKeyPem());
        HexRegistry.Package jason = pkg(served, pem);
        assertThat(jason.repository()).isEqualTo("hexpm");
        assertThat(jason.releases()).extracting(HexRegistry.Release::version).containsExactly("1.4.3", "1.4.4");

        fetch("packages/jason");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/packages/jason")));
        assertThat(storage.find(repo, "packages/jason").orElseThrow().asset().isMetadata()).isTrue();
    }

    @Test
    void namesAndVersionsForMixDepsGetAndHexSearch() throws Exception {
        byte[] names = signedDocument(HexRegistry.encodeNames(new HexRegistry.Names("hexpm", List.of(
                new HexRegistry.NamesEntry("jason", new HexRegistry.Timestamp(1_700_000_000L, 0))))), hexpmKey);
        byte[] versions = signedDocument(HexRegistry.encodeVersions(new HexRegistry.Versions("hexpm", List.of(
                new HexRegistry.VersionsEntry("jason", List.of("1.4.3", "1.4.4"), List.of())))), hexpmKey);
        stub("/names", names);
        stub("/versions", versions);
        assertThat(fetch("names")).isEqualTo(names);
        assertThat(fetch("versions")).isEqualTo(versions);
    }

    @Test
    void tarballsAreFetchedOnceAndCountedAsDownloads() throws Exception {
        byte[] tarball = release("jason", "1.4.3");
        stub("/tarballs/jason-1.4.3.tar", tarball);
        for (int i = 0; i < 2; i++) {
            assertThat(fetch("tarballs/jason-1.4.3.tar")).isEqualTo(tarball);
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/tarballs/jason-1.4.3.tar")));
        assertThat(storage.find(repo, "tarballs/jason-1.4.3.tar")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(2);
            assertThat(c.asset().getComponentId()).isNotNull();
        });
    }

    @Test
    void cachedContentSurvivesABrokenUpstream() throws Exception {
        Repository impatient = new Repository();
        impatient.setName("hexpm-stale");
        impatient.setFormat("hex");
        impatient.setType(RepositoryType.PROXY);
        impatient.setRemoteUrl(upstream.baseUrl() + "/");
        impatient.setMetadataMaxAgeMinutes(0); // re-validate documents on every request
        repositories.create(impatient);
        String url = "/repository/hexpm-stale";

        byte[] doc = jasonDocument();
        byte[] tarball = release("jason", "1.4.3");
        stub("/packages/jason", doc);
        stub("/tarballs/jason-1.4.3.tar", tarball);
        mvc.perform(get(url + "/packages/jason")).andExpect(status().isOk());
        mvc.perform(get(url + "/tarballs/jason-1.4.3.tar")).andExpect(status().isOk());

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));
        assertThat(mvc.perform(get(url + "/packages/jason")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(doc);
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/packages/jason"))); // it did try
        assertThat(mvc.perform(get(url + "/tarballs/jason-1.4.3.tar")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(tarball);

        // never cached while upstream is broken: a temporary 503, not a 404 mix would report as "no package"
        mvc.perform(get(url + "/packages/plug")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void offlineModeServesTheCacheAndNeverTouchesUpstream() throws Exception {
        stub("/packages/jason", jasonDocument());
        stub("/tarballs/jason-1.4.3.tar", release("jason", "1.4.3"));
        fetch("packages/jason");
        fetch("tarballs/jason-1.4.3.tar");

        repositories.setOffline(REPO, true);
        upstream.resetAll();
        fetch("packages/jason");
        fetch("tarballs/jason-1.4.3.tar");
        mvc.perform(get(URL + "/packages/plug")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void upstreamMissesAreRemembered() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/packages/nope")).willReturn(WireMock.aResponse().withStatus(404)));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(URL + "/packages/nope")).andExpect(status().isNotFound());
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/packages/nope")));
    }

    @Test
    void clientInstallListsArePassedThrough() throws Exception {
        stub("/installs/hex-1.x.csv", "2.1.1,abcdef,1.18.0\n".getBytes());
        assertThat(new String(fetch("installs/hex-1.x.csv"))).isEqualTo("2.1.1,abcdef,1.18.0\n");
        assertThat(storage.find(repo, "installs/hex-1.x.csv").orElseThrow().asset().isMetadata()).isTrue();
    }

    @Test
    void writesAndTheApiAreNotProxied() throws Exception {
        Map<String, Object> refused = term(mvc.perform(post(URL + "/api/packages/acme/releases")
                        .header("Authorization", adminBasic()).header("Accept", ERLANG)
                        .contentType("application/octet-stream").content(release("acme", "1.0.0")))
                .andExpect(status().isMethodNotAllowed()).andReturn());
        assertThat((String) refused.get("message")).contains("proxy repository");
        Map<String, Object> notProxied = term(mvc.perform(get(URL + "/api/packages/jason").header("Accept", ERLANG))
                .andExpect(status().isNotFound()).andReturn());
        assertThat((String) notProxied.get("message")).contains("not proxied");
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void connectSnippetsDescribeAMirror() throws Exception {
        mvc.perform(get("/api/v1/repositories/" + REPO + "/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'mix-hex-mirror')].code").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("export HEX_MIRROR=" + HexFixtures.repositoryUrl(REPO)))))
                .andExpect(jsonPath("$[?(@.id == 'rebar3-hex-cdn')].code").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("export HEX_CDN=" + HexFixtures.repositoryUrl(REPO)))));
    }
}
