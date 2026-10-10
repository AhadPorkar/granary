package dev.granary.server.pub;

import static dev.granary.server.pub.PubFixtures.archive;
import static dev.granary.server.pub.PubFixtures.json;
import static dev.granary.server.pub.PubFixtures.repositoryUrl;
import static dev.granary.server.pub.PubFixtures.sha256;
import static dev.granary.server.pub.PubFixtures.str;
import static dev.granary.server.pub.PubFixtures.upstreamListing;
import static dev.granary.server.pub.PubFixtures.versions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.proxy.ProxyService;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** One PUB_HOSTED_URL for everything: a group of a hosted repository and a pub.dev proxy. */
class PubGroupIntegrationTest extends PubClientSupport {

    private static final String GROUP = repositoryUrl("pub-group");
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    private String token;

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
        hosted("pub-hosted", "pub");
        proxyService.invalidate(proxy("pub-dev", "pub", upstream.baseUrl() + "/"));
        group("pub-group", "pub", "pub-hosted", "pub-dev");
        token = adminToken();
    }

    private static void stubListing(String name, String advisoriesUpdated, Map<String, byte[]> archives) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/packages/" + name))
                .willReturn(WireMock.okJson(upstreamListing(upstream.baseUrl(), name, advisoriesUpdated, archives))));
        archives.forEach((version, bytes) -> upstream.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/api/archives/" + name + "-" + version + ".tar.gz"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody(bytes))));
    }

    private static Map<String, byte[]> ordered(Object... versionsAndArchives) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i < versionsAndArchives.length; i += 2) {
            map.put((String) versionsAndArchives[i], (byte[]) versionsAndArchives[i + 1]);
        }
        return map;
    }

    @Test
    void ownAndPublicPackagesComeThroughOneUrl() throws Exception {
        publish("pub-hosted", archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        byte[] path = archive("path", "1.9.1");
        stubListing("path", null, ordered("1.9.1", path));

        JsonNode own = json(mvc.perform(api(GROUP + "/api/packages/granary_demo")).andExpect(status().isOk()).andReturn());
        assertThat(str(own, "latest", "archive_url")).isEqualTo(GROUP + "/packages/granary_demo/versions/1.0.0.tar.gz");
        JsonNode pub = json(mvc.perform(api(GROUP + "/api/packages/path")).andExpect(status().isOk()).andReturn());
        assertThat(str(pub, "latest", "archive_url")).isEqualTo(GROUP + "/packages/path/versions/1.9.1.tar.gz");

        assertThat(mvc.perform(download(GROUP + "/packages/granary_demo/versions/1.0.0.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(archive("granary_demo", "1.0.0"));
        assertThat(mvc.perform(download(GROUP + "/packages/path/versions/1.9.1.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(path);
        // a hosted package is never looked up upstream once the hosted member answered
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/archives/granary_demo-1.0.0.tar.gz")));

        mvc.perform(api(GROUP + "/api/packages/path/versions/1.9.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archive_url").value(GROUP + "/packages/path/versions/1.9.1.tar.gz"));
        mvc.perform(api(GROUP + "/api/packages/nothing_anywhere")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NotFound"));
    }

    @Test
    void listingsAreMergedWithTheHostedMemberWinning() throws Exception {
        // the same package name exists here and upstream (a fork, or a name taken over internally)
        byte[] own = archive("shared", "1.0.0");
        publish("pub-hosted", own, token).andExpect(status().isOk());
        byte[] upstream100 = archive("shared", "1.0.0", PubFixtures.pubspec("shared", "1.0.0") + "# upstream\n");
        byte[] upstream200 = archive("shared", "2.0.0");
        byte[] upstream300dev = archive("shared", "3.0.0-dev.1");
        stubListing("shared", null, ordered("1.0.0", upstream100, "2.0.0", upstream200, "3.0.0-dev.1", upstream300dev));

        JsonNode merged = json(mvc.perform(api(GROUP + "/api/packages/shared")).andExpect(status().isOk()).andReturn());
        assertThat(versions(merged)).containsExactly("1.0.0", "2.0.0", "3.0.0-dev.1");
        assertThat(str(merged.path("versions").get(0), "archive_sha256")).isEqualTo(sha256(own));
        assertThat(str(merged.path("versions").get(1), "archive_sha256")).isEqualTo(sha256(upstream200));
        // latest is the newest release, not the newer pre-release
        assertThat(str(merged, "latest", "version")).isEqualTo("2.0.0");
        for (JsonNode v : merged.path("versions")) {
            assertThat(str(v, "archive_url")).startsWith(GROUP + "/packages/shared/versions/");
        }

        // the archive comes from the member that owns the version, so it matches the promised sha256
        assertThat(mvc.perform(download(GROUP + "/packages/shared/versions/1.0.0.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(own);
        assertThat(mvc.perform(download(GROUP + "/packages/shared/versions/2.0.0.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(upstream200);
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/archives/shared-1.0.0.tar.gz")));
        mvc.perform(api(GROUP + "/api/packages/shared/versions/1.0.0"))
                .andExpect(jsonPath("$.archive_sha256").value(sha256(own)));
    }

    @Test
    void memberOrderDecidesPrecedence() throws Exception {
        // the proxy first: its version wins
        group("pub-upstream-first", "pub", "pub-dev", "pub-hosted");
        byte[] own = archive("shared", "1.0.0");
        publish("pub-hosted", own, token).andExpect(status().isOk());
        byte[] theirs = archive("shared", "1.0.0", PubFixtures.pubspec("shared", "1.0.0") + "# upstream\n");
        stubListing("shared", null, ordered("1.0.0", theirs));
        String url = repositoryUrl("pub-upstream-first");
        mvc.perform(api(url + "/api/packages/shared")).andExpect(jsonPath("$.latest.archive_sha256").value(sha256(theirs)));
        assertThat(mvc.perform(download(url + "/packages/shared/versions/1.0.0.tar.gz")).andReturn().getResponse()
                .getContentAsByteArray()).isEqualTo(theirs);
    }

    @Test
    void nestedGroupsAreFlattened() throws Exception {
        group("pub-outer", "pub", "pub-group");
        publish("pub-hosted", archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        String outer = repositoryUrl("pub-outer");
        mvc.perform(api(outer + "/api/packages/granary_demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latest.archive_url").value(outer + "/packages/granary_demo/versions/1.0.0.tar.gz"));
        mvc.perform(download(outer + "/packages/granary_demo/versions/1.0.0.tar.gz")).andExpect(status().isOk());
    }

    @Test
    void advisoriesOfTheProxyReachTheGroup() throws Exception {
        stubListing("http", "2026-05-04T16:03:47.124153Z", ordered("1.6.0", archive("http", "1.6.0")));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/packages/http/advisories"))
                .willReturn(WireMock.okJson(PubFixtures.upstreamAdvisories("http", "GHSA-4rgh-jx4f-qfcq"))));
        mvc.perform(api(GROUP + "/api/packages/http")).andExpect(jsonPath("$.advisoriesUpdated").value("2026-05-04T16:03:47.124153Z"));
        mvc.perform(api(GROUP + "/api/packages/http/advisories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advisories.length()").value(1))
                .andExpect(jsonPath("$.advisories[0].id").value("GHSA-4rgh-jx4f-qfcq"));
    }

    @Test
    void cachedContentIsServedThroughTheGroupWhileOffline() throws Exception {
        publish("pub-hosted", archive("granary_demo", "1.0.0"), token).andExpect(status().isOk());
        stubListing("path", null, ordered("1.9.1", archive("path", "1.9.1")));
        mvc.perform(api(GROUP + "/api/packages/path")).andExpect(status().isOk());
        mvc.perform(download(GROUP + "/packages/path/versions/1.9.1.tar.gz")).andExpect(status().isOk());

        settings.setGlobalOffline(true);
        upstream.resetAll();
        mvc.perform(api(GROUP + "/api/packages/path")).andExpect(status().isOk());
        mvc.perform(download(GROUP + "/packages/path/versions/1.9.1.tar.gz")).andExpect(status().isOk());
        mvc.perform(api(GROUP + "/api/packages/granary_demo")).andExpect(status().isOk());
        mvc.perform(download(GROUP + "/packages/granary_demo/versions/1.0.0.tar.gz")).andExpect(status().isOk());
        mvc.perform(api(GROUP + "/api/packages/collection")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void writesToTheGroupAreRefusedNamingTheHostedMember() throws Exception {
        mvc.perform(withToken(api(GROUP + "/api/packages/versions/new"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("InvalidInput"))
                .andExpect(jsonPath("$.error.message").value(containsString(
                        "'pub-group' is a group repository and cannot be changed; publish to its hosted member instead: "
                                + "publish_to: http://localhost/repository/pub-hosted")));
        mvc.perform(withToken(multipart(HttpMethod.POST, URI.create(GROUP + "/api/packages/versions/newUpload"))
                        .file(new MockMultipartFile("file", "package.tar.gz", "application/octet-stream", archive("x", "1.0.0"))), token))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(withToken(post(GROUP + "/api/packages/x/versions/1.0.0/retract"), token)).andExpect(status().isMethodNotAllowed());
        mvc.perform(withToken(delete(GROUP + "/api/packages/x"), token)).andExpect(status().isMethodNotAllowed());
    }
}
