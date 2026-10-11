package dev.granary.server.raw;

import static dev.granary.server.UpstreamCredentialFixtures.PASSWORD;
import static dev.granary.server.UpstreamCredentialFixtures.USER;
import static dev.granary.server.UpstreamCredentialFixtures.authorisedRequests;
import static dev.granary.server.UpstreamCredentialFixtures.hostOf;
import static dev.granary.server.UpstreamCredentialFixtures.proxyWithCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withCredentials;
import static dev.granary.server.UpstreamCredentialFixtures.withoutCredentials;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.proxy.UpstreamClient;
import dev.granary.server.IntegrationTest;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A private Elm package server whose release zips are GitHub zipballs, as on package.elm-lang.org: the package
 * server gets the proxy's credentials, GitHub never does unless it is listed in {@code upstream.credentialHosts}.
 *
 * <p>The Elm profile always downloads zips from {@code https://github.com}; here a local server stands in for it.
 * The upstream client is wrapped so that a request for github.com goes to that server instead, after Granary chose
 * the URL and before the client decides about the credentials, which therefore sees the stand-in's own origin.
 */
class ElmUpstreamCredentialsIntegrationTest extends IntegrationTest {

    private static final WireMockServer packages = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer github = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final byte[] ZIP = zip("core-1.0.5/elm.json", "{\"type\":\"package\",\"name\":\"elm/core\"}");

    @MockitoSpyBean
    private UpstreamClient upstreamClient;

    @Autowired
    private GranaryProperties properties;

    @Autowired
    private ProxyService proxyService;

    @BeforeAll
    static void start() {
        packages.start();
        github.start();
    }

    @AfterAll
    static void stop() {
        packages.stop();
        github.stop();
    }

    @BeforeEach
    void stubServers() throws Exception {
        UpstreamClient real = new UpstreamClient(properties);
        doAnswer(invocation -> {
            URI uri = invocation.getArgument(1);
            URI routed = "github.com".equals(uri.getHost()) ? URI.create(github.baseUrl() + uri.getRawPath()) : uri;
            return real.getAbsolute(invocation.getArgument(0), routed, invocation.getArgument(2), invocation.getArgument(3));
        }).when(upstreamClient).getAbsolute(any(), any(), anyMap(), anyMap());

        packages.resetAll();
        github.resetAll();
        packages.stubFor(WireMock.any(WireMock.anyUrl()).atPriority(10).willReturn(WireMock.unauthorized()));
        packages.stubFor(WireMock.get(WireMock.urlEqualTo("/packages/elm/core/1.0.5/endpoint.json")).atPriority(1)
                .withBasicAuth(USER, PASSWORD)
                .willReturn(WireMock.okJson("{\"url\":\"https://github.com/elm/core/zipball/1.0.5/\",\"hash\":\"4a9a1f5b\"}")));
        github.stubFor(WireMock.get(WireMock.urlEqualTo("/elm/core/zipball/1.0.5"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(ZIP)));
    }

    private void createProxy(String name, String trustedHosts) {
        Repository r = proxyWithCredentials(name, "elm", packages.baseUrl() + "/", trustedHosts);
        proxyService.invalidate(repositories.create(r));
    }

    private void installCore(String repo) throws Exception {
        mvc.perform(get("/repository/" + repo + "/packages/elm/core/1.0.5/endpoint.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://localhost/repository/" + repo + "/github/elm/core/zip/1.0.5"));
        assertThat(mvc.perform(get("/repository/" + repo + "/github/elm/core/zip/1.0.5")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(ZIP);
    }

    @Test
    void githubNeverSeesThePackageServersCredentials() throws Exception {
        createProxy("elm-private", null);
        installCore("elm-private");

        packages.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlEqualTo("/packages/elm/core/1.0.5/endpoint.json"))));
        github.verify(1, withoutCredentials(WireMock.getRequestedFor(WireMock.urlEqualTo("/elm/core/zipball/1.0.5"))));
        assertThat(authorisedRequests(github)).isZero();
    }

    @Test
    void aZipHostListedInTheSettingReceivesThem() throws Exception {
        createProxy("elm-trusting", hostOf(github));
        installCore("elm-trusting");

        github.verify(1, withCredentials(WireMock.getRequestedFor(WireMock.urlEqualTo("/elm/core/zipball/1.0.5"))));
    }

    private static byte[] zip(String name, String content) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(name));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.finish();
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
