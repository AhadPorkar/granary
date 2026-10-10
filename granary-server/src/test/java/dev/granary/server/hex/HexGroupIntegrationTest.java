package dev.granary.server.hex;

import static dev.granary.server.hex.HexFixtures.ERLANG;
import static dev.granary.server.hex.HexFixtures.USER_AGENT;
import static dev.granary.server.hex.HexFixtures.names;
import static dev.granary.server.hex.HexFixtures.pkg;
import static dev.granary.server.hex.HexFixtures.release;
import static dev.granary.server.hex.HexFixtures.repositoryUrl;
import static dev.granary.server.hex.HexFixtures.signedDocument;
import static dev.granary.server.hex.HexFixtures.term;
import static dev.granary.server.hex.HexFixtures.versions;
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
import dev.granary.format.hex.HexRegistry;
import dev.granary.format.hex.HexSettings;
import dev.granary.format.hex.HexSigningKey;
import dev.granary.server.IntegrationTest;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A group of a hosted repository and a hex.pm proxy (a fake upstream): one repository for mix with
 * {@code mix hex.repo add hex-group …}, whose merged documents are verified upstream and re-signed with
 * Granary's key under the group's name.
 */
class HexGroupIntegrationTest extends IntegrationTest {

    private static final String URL = "/repository/hex-group";
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static HexSigningKey hexpmKey;
    private static HexSigningKey forgerKey;

    @Autowired
    private ProxyService proxyService;

    private Repository proxyRepo;
    private Repository groupRepo;
    private String key;

    @BeforeAll
    static void startUpstream() throws Exception {
        upstream.start();
        hexpmKey = new HexSigningKey(Files.createTempDirectory("hexpm-key"));
        forgerKey = new HexSigningKey(Files.createTempDirectory("forger-key"));
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void setUp() {
        upstream.resetAll();
        hosted("hex-hosted", "hex");
        proxyRepo = proxy("hexpm", "hex", upstream.baseUrl() + "/");
        proxyService.invalidate(proxyRepo);
        groupRepo = group("hex-group", "hex", "hex-hosted", "hexpm");
        key = tokens.issue(users.require(ADMIN), "mix", null).secret();
        stub("/public_key", hexpmKey.publicKeyPem().getBytes());
    }

    private static void stub(String path, byte[] body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/octet-stream").withBody(body)));
    }

    private static void upstreamPackage(String name, HexSigningKey signer, String... versions) {
        List<HexRegistry.Release> releases = java.util.Arrays.stream(versions)
                .map(v -> new HexRegistry.Release(v, new byte[32], List.of(), null,
                        HexFormat.of().parseHex(HexFixtures.sha256Hex(release(name, v)))))
                .toList();
        stub("/packages/" + name, signedDocument(HexRegistry.encodePackage(new HexRegistry.Package(name, "hexpm", releases)), signer));
        for (String v : versions) {
            stub("/tarballs/" + name + "-" + v + ".tar", release(name, v));
        }
    }

    private void publish(String name, String version) throws Exception {
        publishTarball(name, release(name, version));
    }

    private void publishTarball(String name, byte[] tarball) throws Exception {
        mvc.perform(post("/repository/hex-hosted/api/packages/" + name + "/releases").queryParam("replace", "false")
                        .header("Authorization", key).header("Accept", ERLANG)
                        .contentType("application/octet-stream").content(tarball))
                .andExpect(status().isCreated());
    }

    private byte[] fetch(String path) throws Exception {
        return mvc.perform(get(URL + "/" + path).header("Authorization", key).header("User-Agent", USER_AGENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private String granaryKey() throws Exception {
        return mvc.perform(get(URL + "/public_key")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void ownAndUpstreamPackagesResolveThroughOneRepository() throws Exception {
        publish("acme_lib", "0.1.0");
        upstreamPackage("jason", hexpmKey, "1.4.4");

        String pem = granaryKey();
        assertThat(pem).isEqualTo(mvc.perform(get("/repository/hex-hosted/public_key")).andReturn().getResponse()
                .getContentAsString());
        HexRegistry.Package own = pkg(fetch("packages/acme_lib"), pem);
        assertThat(own.repository()).isEqualTo("hex-group");
        HexRegistry.Package jason = pkg(fetch("packages/jason"), pem); // hex.pm's document, verified and re-signed
        assertThat(jason.repository()).isEqualTo("hex-group");
        assertThat(jason.releases()).extracting(HexRegistry.Release::version).containsExactly("1.4.4");

        assertThat(fetch("tarballs/acme_lib-0.1.0.tar")).isEqualTo(release("acme_lib", "0.1.0"));
        byte[] jasonTarball = fetch("tarballs/jason-1.4.4.tar");
        assertThat(HexFixtures.sha256Hex(jasonTarball))
                .isEqualTo(HexFormat.of().formatHex(jason.release("1.4.4").orElseThrow().outerChecksum()));
    }

    @Test
    void indexesAreMergedAcrossMembers() throws Exception {
        publish("acme_lib", "0.1.0");
        stub("/names", signedDocument(HexRegistry.encodeNames(new HexRegistry.Names("hexpm", List.of(
                new HexRegistry.NamesEntry("jason", new HexRegistry.Timestamp(5, 0))))), hexpmKey));
        stub("/versions", signedDocument(HexRegistry.encodeVersions(new HexRegistry.Versions("hexpm", List.of(
                new HexRegistry.VersionsEntry("jason", List.of("1.4.3", "1.4.4"), List.of(0))))), hexpmKey));
        String pem = granaryKey();
        HexRegistry.Names names = names(fetch("names"), pem);
        assertThat(names.repository()).isEqualTo("hex-group");
        assertThat(names.names()).containsExactly("acme_lib", "jason");
        HexRegistry.Versions versions = versions(fetch("versions"), pem);
        assertThat(versions.packages()).containsExactly(
                new HexRegistry.VersionsEntry("acme_lib", List.of("0.1.0"), List.of()),
                new HexRegistry.VersionsEntry("jason", List.of("1.4.3", "1.4.4"), List.of(0)));
    }

    @Test
    void theHostedReleaseOfAVersionWins() throws Exception {
        byte[] fork = HexFixtures.releaseWith(HexFixtures.metadata("jason", "1.4.4").replace("package", "internal fork"));
        publishTarball("jason", fork); // an internal fork with the same name and version
        upstreamPackage("jason", hexpmKey, "1.4.3", "1.4.4");
        HexRegistry.Package jason = pkg(fetch("packages/jason"), granaryKey());
        assertThat(jason.releases()).extracting(HexRegistry.Release::version).containsExactly("1.4.3", "1.4.4");
        byte[] hostedTarball = mvc.perform(get("/repository/hex-hosted/tarballs/jason-1.4.4.tar")).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(hostedTarball).isEqualTo(fork).isNotEqualTo(release("jason", "1.4.4"));
        assertThat(HexFormat.of().formatHex(jason.release("1.4.4").orElseThrow().outerChecksum()))
                .isEqualTo(HexFixtures.sha256Hex(hostedTarball));
        assertThat(fetch("tarballs/jason-1.4.4.tar")).isEqualTo(hostedTarball);
        assertThat(fetch("tarballs/jason-1.4.3.tar")).isEqualTo(release("jason", "1.4.3"));
    }

    @Test
    void forgedUpstreamDocumentsAreNotPassedOnUnlessVerificationIsOff() throws Exception {
        upstreamPackage("jason", forgerKey, "1.4.4");
        mvc.perform(get(URL + "/packages/jason")).andExpect(status().isNotFound());

        groupRepo.setFormatSettings(Map.of(HexSettings.VERIFY_UPSTREAM, "false"));
        repositories.update("hex-group", groupRepo);
        assertThat(pkg(fetch("packages/jason"), granaryKey()).releases()).hasSize(1);
    }

    @Test
    void theGroupNameClientsUseIsConfigurable() throws Exception {
        groupRepo.setFormatSettings(Map.of(HexSettings.REPOSITORY_NAME, "acme"));
        repositories.update("hex-group", groupRepo);
        publish("acme_lib", "0.1.0");
        assertThat(pkg(fetch("packages/acme_lib"), granaryKey()).repository()).isEqualTo("acme");
        mvc.perform(get(URL + "/")).andExpect(jsonPath("$.repository").value("acme"));
    }

    @Test
    void mergedDocumentsSupportConditionalRequestsAndHead() throws Exception {
        publish("acme_lib", "0.1.0");
        var first = mvc.perform(get(URL + "/packages/acme_lib")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache")).andReturn().getResponse();
        String etag = first.getHeader("ETag");
        assertThat(etag).isNotBlank();
        mvc.perform(get(URL + "/packages/acme_lib").header("If-None-Match", etag)).andExpect(status().isNotModified());
        mvc.perform(head(URL + "/packages/acme_lib")).andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", first.getContentAsByteArray().length));
        assertThat(fetch("packages/acme_lib")).isEqualTo(first.getContentAsByteArray()); // stable until a member changes
        publish("acme_lib", "0.2.0");
        mvc.perform(get(URL + "/packages/acme_lib").header("If-None-Match", etag)).andExpect(status().isOk());
    }

    @Test
    void anOfflineProxyMemberLeavesTheHostedPartWorking() throws Exception {
        publish("acme_lib", "0.1.0");
        repositories.setOffline("hexpm", true);
        assertThat(pkg(fetch("packages/acme_lib"), granaryKey()).name()).isEqualTo("acme_lib");
        mvc.perform(get(URL + "/packages/jason")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/packages/jason")));
    }

    @Test
    void whatThePublicProxyCachedServesTheGroupOfflineToo() throws Exception {
        upstreamPackage("jason", hexpmKey, "1.4.4");
        // only ever used as HEX_MIRROR: mix never asks the proxy for public_key, Granary caches it on the side
        mvc.perform(get("/repository/hexpm/packages/jason")).andExpect(status().isOk());
        mvc.perform(get("/repository/hexpm/tarballs/jason-1.4.4.tar")).andExpect(status().isOk());

        repositories.setOffline("hexpm", true);
        upstream.resetAll();
        HexRegistry.Package jason = pkg(fetch("packages/jason"), granaryKey());
        assertThat(jason.repository()).isEqualTo("hex-group");
        assertThat(jason.releases()).extracting(HexRegistry.Release::version).containsExactly("1.4.4");
        assertThat(fetch("tarballs/jason-1.4.4.tar")).isEqualTo(release("jason", "1.4.4"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void publishingGoesToTheHostedMemberAndTheApiReadsFromIt() throws Exception {
        Map<String, Object> refused = term(mvc.perform(post(URL + "/api/packages/acme_lib/releases")
                        .header("Authorization", key).header("Accept", ERLANG)
                        .contentType("application/octet-stream").content(release("acme_lib", "0.1.0")))
                .andExpect(status().isMethodNotAllowed()).andReturn());
        assertThat((String) refused.get("message")).contains("publish to the hosted repository 'hex-hosted'");
        Map<String, Object> putRefused = term(mvc.perform(put(URL + "/tarballs/acme_lib-0.1.0.tar")
                        .header("Authorization", key).header("Accept", ERLANG)
                        .contentType("application/octet-stream").content(release("acme_lib", "0.1.0")))
                .andExpect(status().isMethodNotAllowed()).andReturn());
        assertThat((String) putRefused.get("message")).contains("publish to the hosted repository 'hex-hosted'");

        publish("acme_lib", "0.1.0");
        Map<String, Object> info = term(mvc.perform(get(URL + "/api/packages/acme_lib").header("Accept", ERLANG))
                .andExpect(status().isOk()).andReturn());
        assertThat(info).containsEntry("repository", "hex-group");
        assertThat(HexFixtures.map(info.get("configs"))).containsEntry("mix.exs", "{:acme_lib, \"~> 0.1.0\", repo: \"hex-group\"}");

        mvc.perform(get("/api/v1/repositories/hex-group/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'mix-publish')].code").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("HEX_API_URL=" + repositoryUrl("hex-hosted") + "/api"))));
    }
}
