package dev.granary.core.proxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two servers on different origins: the upstream named by the repository's remote URL, and a foreign host its
 * documents and redirects point at. Only the upstream may ever see the repository's credentials.
 */
class UpstreamClientTest {

    private static final String BASIC = "Basic bWlycm9yOnMzY3JldA=="; // mirror:s3cret
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer foreign = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private final UpstreamClient client = new UpstreamClient(new GranaryProperties());
    private Repository repo;

    @BeforeAll
    static void start() {
        upstream.start();
        foreign.start();
    }

    @AfterAll
    static void stop() {
        upstream.stop();
        foreign.stop();
    }

    @BeforeEach
    void reset() {
        upstream.resetAll();
        foreign.resetAll();
        repo = new Repository();
        repo.setId(UUID.randomUUID());
        repo.setName("p");
        repo.setFormat("raw");
        repo.setType(RepositoryType.PROXY);
        repo.setRemoteUrl(upstream.baseUrl() + "/repo/");
        repo.setRemoteUsername("mirror");
        repo.setRemotePassword("s3cret");
        upstream.stubFor(get(urlEqualTo("/repo/file")).willReturn(aResponse().withStatus(200).withBody("upstream")));
        foreign.stubFor(get(urlEqualTo("/file")).willReturn(aResponse().withStatus(200).withBody("foreign")));
    }

    private String body(UpstreamClient.Response response) throws IOException {
        try (response) {
            return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static URI at(WireMockServer server, String path) {
        return URI.create(server.baseUrl() + path);
    }

    private void redirect(WireMockServer server, String from, int status, String location) {
        server.stubFor(get(urlEqualTo(from)).willReturn(aResponse().withStatus(status).withHeader("Location", location)));
    }

    @Test
    void theUpstreamOriginGetsTheCredentialsAndAForeignHostDoesNot() throws Exception {
        assertThat(body(client.get(repo, "file"))).isEqualTo("upstream");
        assertThat(body(client.getAbsolute(repo, at(foreign, "/file"), Map.of(), Map.of()))).isEqualTo("foreign");

        upstream.verify(getRequestedFor(urlEqualTo("/repo/file")).withHeader("Authorization", equalTo(BASIC)));
        foreign.verify(1, getRequestedFor(urlEqualTo("/file")).withoutHeader("Authorization"));
    }

    @Test
    void aBearerTokenIsScopedTheSameWay() throws Exception {
        repo.setRemoteToken("tok");
        body(client.get(repo, "file"));
        body(client.getAbsolute(repo, at(foreign, "/file"), Map.of(), Map.of()));
        upstream.verify(getRequestedFor(urlEqualTo("/repo/file")).withHeader("Authorization", equalTo("Bearer tok")));
        foreign.verify(1, getRequestedFor(urlEqualTo("/file")).withoutHeader("Authorization"));
    }

    @Test
    void aRedirectThatLeavesTheOriginIsFollowedWithoutTheCredentials() throws Exception {
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            redirect(upstream, "/repo/moved-" + status, status, foreign.baseUrl() + "/file");
            UpstreamClient.Response response = client.get(repo, "moved-" + status, Map.of("If-None-Match", "\"v1\""), Map.of());
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.uri()).isEqualTo(at(foreign, "/file"));
            assertThat(body(response)).isEqualTo("foreign");
            upstream.verify(getRequestedFor(urlEqualTo("/repo/moved-" + status)).withHeader("Authorization", equalTo(BASIC)));
        }
        foreign.verify(5, getRequestedFor(urlEqualTo("/file")).withoutHeader("Authorization")
                .withHeader("If-None-Match", equalTo("\"v1\"")));
    }

    @Test
    void aRedirectWithinTheOriginKeepsThemAndOneBackToItGetsThemAgain() throws Exception {
        redirect(upstream, "/repo/old", 302, "/repo/file");
        assertThat(body(client.get(repo, "old"))).isEqualTo("upstream");
        upstream.verify(1, getRequestedFor(urlEqualTo("/repo/old")).withHeader("Authorization", equalTo(BASIC)));
        upstream.verify(1, getRequestedFor(urlEqualTo("/repo/file")).withHeader("Authorization", equalTo(BASIC)));

        upstream.resetRequests();
        redirect(foreign, "/bounce", 302, upstream.baseUrl() + "/repo/file");
        assertThat(body(client.getAbsolute(repo, at(foreign, "/bounce"), Map.of(), Map.of()))).isEqualTo("upstream");
        foreign.verify(1, getRequestedFor(urlEqualTo("/bounce")).withoutHeader("Authorization"));
        upstream.verify(1, getRequestedFor(urlEqualTo("/repo/file")).withHeader("Authorization", equalTo(BASIC)));
    }

    @Test
    void aCallersOwnAuthorizationHeaderReplacesTheCredentialsAndStaysOnItsOrigin() throws Exception {
        redirect(upstream, "/repo/blob", 307, foreign.baseUrl() + "/file");
        assertThat(body(client.get(repo, "blob", Map.of(), Map.of("Authorization", "Bearer registry-token",
                "Accept", "application/octet-stream")))).isEqualTo("foreign");

        upstream.verify(getRequestedFor(urlEqualTo("/repo/blob")).withHeader("Authorization", equalTo("Bearer registry-token")));
        assertThat(upstream.findAll(getRequestedFor(urlEqualTo("/repo/blob"))).getFirst().getHeaders()
                .getHeader("Authorization").values()).containsExactly("Bearer registry-token");
        foreign.verify(1, getRequestedFor(urlEqualTo("/file")).withoutHeader("Authorization")
                .withHeader("Accept", equalTo("application/octet-stream")));
    }

    @Test
    void listedHostsReceiveTheCredentialsDirectlyAndAfterARedirect() throws Exception {
        repo.setFormatSettings(Map.of(UpstreamCredentials.HOSTS_SETTING, "localhost:" + foreign.port()));
        body(client.getAbsolute(repo, at(foreign, "/file"), Map.of(), Map.of()));
        redirect(upstream, "/repo/moved", 302, foreign.baseUrl() + "/file");
        body(client.get(repo, "moved"));
        foreign.verify(2, getRequestedFor(urlEqualTo("/file")).withHeader("Authorization", equalTo(BASIC)));

        // a bare host means the default port, which the foreign server does not listen on
        foreign.resetRequests();
        repo.setFormatSettings(Map.of(UpstreamCredentials.HOSTS_SETTING, "localhost"));
        body(client.getAbsolute(repo, at(foreign, "/file"), Map.of(), Map.of()));
        foreign.verify(1, getRequestedFor(urlEqualTo("/file")).withoutHeader("Authorization"));
    }

    @Test
    void anonymousRequestsNeverCarryTheCredentials() throws Exception {
        body(client.getAnonymous(repo, at(upstream, "/repo/file"), Map.of(), Map.of()));
        upstream.verify(1, getRequestedFor(urlEqualTo("/repo/file")).withoutHeader("Authorization"));
    }

    @Test
    void aHostTheProtocolNamesGetsTheCredentialsButNotWhereItRedirects() throws Exception {
        foreign.stubFor(get(urlEqualTo("/token")).willReturn(aResponse().withStatus(200).withBody("{\"token\":\"t\"}")));
        assertThat(body(client.getWithCredentials(repo, at(foreign, "/token"), Map.of(), Map.of()))).contains("\"t\"");
        foreign.verify(1, getRequestedFor(urlEqualTo("/token")).withHeader("Authorization", equalTo(BASIC)));

        // the realm sends the client on to yet another origin, here the upstream's: not where the realm's credentials go
        redirect(foreign, "/token-moved", 302, upstream.baseUrl() + "/elsewhere");
        upstream.stubFor(get(urlEqualTo("/elsewhere")).willReturn(aResponse().withStatus(200).withBody("x")));
        body(client.getWithCredentials(repo, at(foreign, "/token-moved"), Map.of(), Map.of()));
        upstream.verify(1, getRequestedFor(urlEqualTo("/elsewhere")).withoutHeader("Authorization"));
    }

    @Test
    void redirectLoopsEndWithAnError() {
        redirect(upstream, "/repo/loop", 302, "/repo/loop");
        assertThatThrownBy(() -> client.get(repo, "loop")).isInstanceOf(IOException.class).hasMessageContaining("redirects");
        upstream.verify(UpstreamClient.MAX_REDIRECTS + 1, getRequestedFor(urlEqualTo("/repo/loop")));
    }

    @Test
    void redirectsWithoutAUsableLocationAreAnsweredAsTheyAre() throws Exception {
        upstream.stubFor(get(urlEqualTo("/repo/nowhere")).willReturn(aResponse().withStatus(302)));
        try (UpstreamClient.Response response = client.get(repo, "nowhere")) {
            assertThat(response.status()).isEqualTo(302);
        }
        redirect(upstream, "/repo/ftp", 302, "ftp://localhost/file");
        try (UpstreamClient.Response response = client.get(repo, "ftp")) {
            assertThat(response.status()).isEqualTo(302);
        }
        upstream.stubFor(get(urlEqualTo("/repo/not-modified")).willReturn(aResponse().withStatus(304)));
        try (UpstreamClient.Response response = client.get(repo, "not-modified")) {
            assertThat(response.status()).isEqualTo(304);
        }
    }

    @Test
    void redirectTargetsAreResolvedAndNeverStepDownFromHttps() {
        URI https = URI.create("https://repo.example.com/a/b");
        assertThat(UpstreamClient.redirectTarget(https, 302, Optional.of("c"))).isEqualTo(URI.create("https://repo.example.com/a/c"));
        assertThat(UpstreamClient.redirectTarget(https, 301, Optional.of("//cdn.example.com/x")))
                .isEqualTo(URI.create("https://cdn.example.com/x"));
        assertThat(UpstreamClient.redirectTarget(https, 307, Optional.of("https://cdn.example.com/x?sig=1")))
                .isEqualTo(URI.create("https://cdn.example.com/x?sig=1"));
        assertThat(UpstreamClient.redirectTarget(https, 302, Optional.of("http://repo.example.com/a/b"))).isNull();
        assertThat(UpstreamClient.redirectTarget(https, 302, Optional.of("HTTP://cdn.example.com/x"))).isNull();
        assertThat(UpstreamClient.redirectTarget(URI.create("http://mirror.internal/a"), 302, Optional.of("https://cdn.example.com/x")))
                .isEqualTo(URI.create("https://cdn.example.com/x"));
        assertThat(UpstreamClient.redirectTarget(https, 200, Optional.of("https://cdn.example.com/x"))).isNull();
        assertThat(UpstreamClient.redirectTarget(https, 300, Optional.of("https://cdn.example.com/x"))).isNull();
        assertThat(UpstreamClient.redirectTarget(https, 304, Optional.of("https://cdn.example.com/x"))).isNull();
        assertThat(UpstreamClient.redirectTarget(https, 302, Optional.empty())).isNull();
        assertThat(UpstreamClient.redirectTarget(https, 302, Optional.of(" "))).isNull();
        assertThat(UpstreamClient.redirectTarget(https, 302, Optional.of("https://bad host/x"))).isNull();
        assertThat(UpstreamClient.redirectTarget(https, 302, Optional.of("javascript:alert(1)"))).isNull();
    }
}
