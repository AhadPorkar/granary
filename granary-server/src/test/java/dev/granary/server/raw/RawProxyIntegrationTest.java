package dev.granary.server.raw;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.server.IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Proxies in front of a WireMock upstream: the CDN redirect-and-cache flow, CRAN/Hackage/CPAN index refresh
 * versus immutable archives, a group over hosted and proxy, and what happens when the upstream is gone.
 */
class RawProxyIntegrationTest extends IntegrationTest {

    private static final byte[] LODASH = "/* lodash 4.17.21 */".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PRAISE = RawServerFixtures.tarGz("praise/DESCRIPTION", "Package: praise\nVersion: 1.0.0\n");

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
    void stubUpstream() {
        upstream.resetAll();
        // cdn: unpinned URLs redirect to the pinned file
        upstream.stubFor(WireMock.get(urlEqualTo("/npm/lodash/lodash.min.js"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", base + "/npm/lodash@4.17.21/lodash.min.js")));
        upstream.stubFor(WireMock.get(urlEqualTo("/npm/lodash@4.17.21/lodash.min.js"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/javascript; charset=utf-8").withBody(LODASH)));
        // cran
        upstream.stubFor(WireMock.get(urlEqualTo("/src/contrib/PACKAGES"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody("Package: praise\nVersion: 1.0.0\n")));
        upstream.stubFor(WireMock.get(urlEqualTo("/src/contrib/praise_1.0.0.tar.gz"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody(PRAISE)));
        // hackage
        upstream.stubFor(WireMock.get(urlEqualTo("/01-index.tar.gz"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody("index-v1")));
        upstream.stubFor(WireMock.get(urlEqualTo("/package/aeson-2.1.0.0.tar.gz"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody("aeson-v1")));
        // cpan
        upstream.stubFor(WireMock.get(urlEqualTo("/modules/02packages.details.txt.gz"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody("packages-v1")));
        upstream.stubFor(WireMock.get(urlEqualTo("/authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip").withBody("acme-hello")));
        // raw files for the group test
        upstream.stubFor(WireMock.get(urlEqualTo("/shared/from-proxy.txt"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody("proxy")));
    }

    /** A proxy that re-validates metadata on every request, so the tests can observe the refresh. */
    private Repository alwaysRefresh(String name, String format) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat(format);
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(base + "/");
        r.setMetadataMaxAgeMinutes(0);
        return repositories.create(r);
    }

    private ResultActions fetch(String repo, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/repository/" + repo + "/" + path));
    }

    private static void breakUpstream() {
        upstream.resetAll();
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500).withBody("upstream is on fire")));
    }

    @Test
    void cdnFollowsRedirectsCachesPinnedFilesForeverAndRevalidatesUnpinnedOnes() throws Exception {
        alwaysRefresh("jsdelivr", "cdn");
        for (int i = 0; i < 2; i++) {
            MvcResult pinned = fetch("jsdelivr", "npm/lodash@4.17.21/lodash.min.js")
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", containsString("text/javascript")))
                    .andExpect(header().string("Cache-Control", containsString("max-age")))
                    .andReturn();
            assertThat(pinned.getResponse().getContentAsByteArray()).isEqualTo(LODASH);
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/npm/lodash@4.17.21/lodash.min.js")));

        for (int i = 0; i < 2; i++) {
            MvcResult unpinned = fetch("jsdelivr", "npm/lodash/lodash.min.js")
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("no-cache")))
                    .andReturn();
            assertThat(unpinned.getResponse().getContentAsByteArray()).isEqualTo(LODASH);
        }
        upstream.verify(2, getRequestedFor(urlEqualTo("/npm/lodash/lodash.min.js")));

        // the final bytes were cached under the requested (unpinned) path: they survive the upstream going away
        breakUpstream();
        MvcResult cached = fetch("jsdelivr", "npm/lodash/lodash.min.js").andExpect(status().isOk()).andReturn();
        assertThat(cached.getResponse().getContentAsByteArray()).isEqualTo(LODASH);
        fetch("jsdelivr", "npm/lodash@4.17.21/lodash.min.js").andExpect(status().isOk());
        fetch("jsdelivr", "npm/never-seen@1.0.0/index.js")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void cranRefreshesPackagesButKeepsTarballsImmutable() throws Exception {
        alwaysRefresh("cran-proxy", "cran");
        fetch("cran-proxy", "src/contrib/PACKAGES")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(content().string("Package: praise\nVersion: 1.0.0\n"));
        MvcResult tarball = fetch("cran-proxy", "src/contrib/praise_1.0.0.tar.gz")
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/x-gzip"))
                .andReturn();
        assertThat(tarball.getResponse().getContentAsByteArray()).isEqualTo(PRAISE);

        upstream.stubFor(WireMock.get(urlEqualTo("/src/contrib/PACKAGES"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody("Package: praise\nVersion: 1.1.0\n")));
        upstream.stubFor(WireMock.get(urlEqualTo("/src/contrib/praise_1.0.0.tar.gz"))
                .willReturn(aResponse().withStatus(200).withBody("tampered")));
        fetch("cran-proxy", "src/contrib/PACKAGES").andExpect(content().string("Package: praise\nVersion: 1.1.0\n"));
        MvcResult again = fetch("cran-proxy", "src/contrib/praise_1.0.0.tar.gz").andExpect(status().isOk()).andReturn();
        assertThat(again.getResponse().getContentAsByteArray()).isEqualTo(PRAISE);
        upstream.verify(2, getRequestedFor(urlEqualTo("/src/contrib/PACKAGES")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/src/contrib/praise_1.0.0.tar.gz")));
    }

    @Test
    void hackageIndexIsMetadataAndPackagesAreImmutable() throws Exception {
        alwaysRefresh("hackage-proxy", "hackage");
        fetch("hackage-proxy", "01-index.tar.gz").andExpect(status().isOk()).andExpect(content().string("index-v1"));
        fetch("hackage-proxy", "package/aeson-2.1.0.0.tar.gz").andExpect(status().isOk()).andExpect(content().string("aeson-v1"));
        upstream.stubFor(WireMock.get(urlEqualTo("/01-index.tar.gz")).willReturn(aResponse().withStatus(200).withBody("index-v2")));
        upstream.stubFor(WireMock.get(urlEqualTo("/package/aeson-2.1.0.0.tar.gz")).willReturn(aResponse().withStatus(200).withBody("aeson-v2")));
        fetch("hackage-proxy", "01-index.tar.gz").andExpect(content().string("index-v2"));
        fetch("hackage-proxy", "package/aeson-2.1.0.0.tar.gz").andExpect(content().string("aeson-v1"));
        upstream.verify(1, getRequestedFor(urlEqualTo("/package/aeson-2.1.0.0.tar.gz")));
    }

    @Test
    void cpanIndexIsMetadataAndDistributionsAreImmutable() throws Exception {
        alwaysRefresh("cpan-proxy", "cpan");
        fetch("cpan-proxy", "modules/02packages.details.txt.gz").andExpect(status().isOk()).andExpect(content().string("packages-v1"));
        fetch("cpan-proxy", "authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz").andExpect(status().isOk()).andExpect(content().string("acme-hello"));
        upstream.stubFor(WireMock.get(urlEqualTo("/modules/02packages.details.txt.gz")).willReturn(aResponse().withStatus(200).withBody("packages-v2")));
        fetch("cpan-proxy", "modules/02packages.details.txt.gz").andExpect(content().string("packages-v2"));
        fetch("cpan-proxy", "authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz").andExpect(content().string("acme-hello"));
        upstream.verify(1, getRequestedFor(urlEqualTo("/authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz")));
        mvc.perform(MockMvcRequestBuilders.put("/repository/cpan-proxy/authors/id/A/AB/ABC/Evil-1.00.tar.gz")
                        .header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"));
    }

    @Test
    void upstream404sAreRememberedAndUnreachableUpstreamsAre503() throws Exception {
        alwaysRefresh("raw-proxy", "raw");
        upstream.stubFor(WireMock.get(urlEqualTo("/missing.txt")).willReturn(aResponse().withStatus(404)));
        for (int i = 0; i < 2; i++) {
            fetch("raw-proxy", "missing.txt")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("content.notFound"));
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/missing.txt")));

        proxy("raw-dead", "raw", "http://127.0.0.1:1/");
        fetch("raw-dead", "anything.txt")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void offlineModeServesTheCacheAndNeverTouchesUpstream() throws Exception {
        alwaysRefresh("cran-proxy", "cran");
        fetch("cran-proxy", "src/contrib/PACKAGES").andExpect(status().isOk());
        fetch("cran-proxy", "src/contrib/praise_1.0.0.tar.gz").andExpect(status().isOk());
        upstream.resetAll();
        repositories.setOffline("cran-proxy", true);

        fetch("cran-proxy", "src/contrib/PACKAGES").andExpect(status().isOk()).andExpect(content().string("Package: praise\nVersion: 1.0.0\n"));
        fetch("cran-proxy", "src/contrib/praise_1.0.0.tar.gz").andExpect(status().isOk());
        fetch("cran-proxy", "src/contrib/other_1.0.0.tar.gz")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, getRequestedFor(anyUrl()));

        repositories.setOffline("cran-proxy", false);
        settings.setGlobalOffline(true);
        fetch("cran-proxy", "src/contrib/other_1.0.0.tar.gz").andExpect(status().isServiceUnavailable());
        upstream.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void groupReadsHostedFirstThenProxyAndMergesListings() throws Exception {
        hosted("raw-hosted", "raw");
        alwaysRefresh("raw-proxy", "raw");
        group("raw-all", "raw", "raw-hosted", "raw-proxy");
        mvc.perform(MockMvcRequestBuilders.put("/repository/raw-hosted/shared/from-hosted.txt").header("Authorization", adminBasic()).content("hosted"))
                .andExpect(status().isCreated());

        fetch("raw-all", "shared/from-hosted.txt").andExpect(status().isOk()).andExpect(content().string("hosted"));
        fetch("raw-all", "shared/from-proxy.txt").andExpect(status().isOk()).andExpect(content().string("proxy"));
        upstream.verify(0, getRequestedFor(urlEqualTo("/shared/from-hosted.txt")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/shared/from-proxy.txt")));

        mvc.perform(MockMvcRequestBuilders.get("/repository/raw-all/shared/").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].name").value("from-hosted.txt"))
                .andExpect(jsonPath("$.entries[1].name").value("from-proxy.txt"));
        mvc.perform(MockMvcRequestBuilders.get("/repository/raw-all/shared/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("from-proxy.txt")));

        mvc.perform(MockMvcRequestBuilders.put("/repository/raw-all/shared/new.txt").header("Authorization", adminBasic()).content("x"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"))
                .andExpect(jsonPath("$.message").value(containsString("raw-hosted")));
        mvc.perform(MockMvcRequestBuilders.delete("/repository/raw-all/shared/from-hosted.txt").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());

        upstream.stubFor(WireMock.get(urlEqualTo("/shared/upstream-only.txt")).willReturn(aResponse().withStatus(404)));
        fetch("raw-all", "shared/upstream-only.txt").andExpect(status().isNotFound());
        breakUpstream();
        fetch("raw-all", "shared/from-proxy.txt").andExpect(status().isOk());
        fetch("raw-all", "shared/never.txt")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void cpanGroupMergesTheIndexesOfHostedAndProxy() throws Exception {
        hosted("cpan-hosted", "cpan");
        alwaysRefresh("cpan-proxy", "cpan");
        group("cpan-all", "cpan", "cpan-hosted", "cpan-proxy");
        String upstreamIndex = "File:         02packages.details.txt\nLine-Count:   2\n\n"
                + "Acme::Meow        0.01  F/FO/FOO/Acme-Meow-0.01.tar.gz\n"
                + "Acme::Internal    99.0  E/EV/EVIL/Acme-Internal-99.0.tar.gz\n";
        upstream.stubFor(WireMock.get(urlEqualTo("/modules/02packages.details.txt.gz"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip")
                        .withBody(RawServerFixtures.gzip(upstreamIndex.getBytes(StandardCharsets.UTF_8)))));
        byte[] dist = RawServerFixtures.tarGz("Acme-Internal-1.00/lib/Acme/Internal.pm", "package Acme::Internal;\nour $VERSION = '1.00';\n1;\n");
        mvc.perform(MockMvcRequestBuilders.put("/repository/cpan-hosted/authors/id/C/CO/CORP/Acme-Internal-1.00.tar.gz")
                        .header("Authorization", adminBasic()).content(dist))
                .andExpect(status().isCreated());

        MvcResult gz = fetch("cpan-all", "modules/02packages.details.txt.gz")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().exists("ETag"))
                .andReturn();
        String merged = RawServerFixtures.gunzip(gz.getResponse().getContentAsByteArray());
        assertThat(merged).contains("Line-Count:   2\n")
                .containsPattern("Acme::Internal +1\\.00 +C/CO/CORP/Acme-Internal-1\\.00\\.tar\\.gz\n")
                .containsPattern("Acme::Meow +0\\.01 +F/FO/FOO/Acme-Meow-0\\.01\\.tar\\.gz\n")
                .doesNotContain("EVIL");
        fetch("cpan-all", "modules/02packages.details.txt").andExpect(status().isOk()).andExpect(content().string(merged));
        String etag = gz.getResponse().getHeader("ETag");
        mvc.perform(MockMvcRequestBuilders.get("/repository/cpan-all/modules/02packages.details.txt.gz").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
        mvc.perform(MockMvcRequestBuilders.head("/repository/cpan-all/modules/02packages.details.txt.gz"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", String.valueOf(gz.getResponse().getContentAsByteArray().length)));
        // the distributions resolve through the group from whichever member has them
        fetch("cpan-all", "authors/id/C/CO/CORP/Acme-Internal-1.00.tar.gz").andExpect(status().isOk());
        fetch("cpan-all", "authors/id/A/AB/ABC/Acme-Hello-1.00.tar.gz").andExpect(status().isOk()).andExpect(content().string("acme-hello"));

        // upstream down: the cached upstream index is still merged in
        breakUpstream();
        String again = RawServerFixtures.gunzip(fetch("cpan-all", "modules/02packages.details.txt.gz")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(again).contains("Acme::Meow").contains("Acme::Internal");
    }

    @Test
    void cranGroupMergesPackagesAndHidesTheProxyRds() throws Exception {
        hosted("cran-hosted", "cran");
        alwaysRefresh("cran-proxy", "cran");
        group("cran-all", "cran", "cran-hosted", "cran-proxy");
        upstream.stubFor(WireMock.get(urlEqualTo("/src/contrib/PACKAGES.gz"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-gzip")
                        .withBody(RawServerFixtures.gzip("Package: praise\nVersion: 1.0.0\nMD5sum: up\n\nPackage: cli\nVersion: 3.6.3\nRepository: CRAN\n"
                                .getBytes(StandardCharsets.UTF_8)))));
        upstream.stubFor(WireMock.get(urlEqualTo("/src/contrib/PACKAGES.rds"))
                .willReturn(aResponse().withStatus(200).withBody("rds-of-upstream-only")));
        byte[] tgz = RawServerFixtures.tarGz("internal/DESCRIPTION", "Package: internal\nVersion: 0.1.0\nLicense: MIT\n");
        mvc.perform(MockMvcRequestBuilders.put("/repository/cran-hosted/src/contrib/internal_0.1.0.tar.gz")
                        .header("Authorization", adminBasic()).content(tgz))
                .andExpect(status().isCreated());

        fetch("cran-all", "src/contrib/PACKAGES.rds").andExpect(status().isNotFound());
        fetch("cran-proxy", "src/contrib/PACKAGES.rds").andExpect(status().isOk());
        MvcResult gz = fetch("cran-all", "src/contrib/PACKAGES.gz").andExpect(status().isOk()).andReturn();
        String merged = RawServerFixtures.gunzip(gz.getResponse().getContentAsByteArray());
        assertThat(merged).startsWith("Package: internal\nVersion: 0.1.0\n")
                .contains("Package: praise\nVersion: 1.0.0\nMD5sum: up\n")
                .contains("Package: cli\nVersion: 3.6.3\nRepository: CRAN\n");
        fetch("cran-all", "src/contrib/PACKAGES").andExpect(status().isOk()).andExpect(content().string(merged));
        fetch("cran-all", "src/contrib/internal_0.1.0.tar.gz").andExpect(status().isOk());
        fetch("cran-all", "src/contrib/praise_1.0.0.tar.gz").andExpect(status().isOk());

        // nothing cached upstream and the upstream gone: the group still lists the hosted packages
        upstream.resetAll();
        proxy("cran-dead", "cran", "http://127.0.0.1:1/");
        group("cran-dead-all", "cran", "cran-hosted", "cran-dead");
        String degraded = fetch("cran-dead-all", "src/contrib/PACKAGES").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(degraded).contains("Package: internal").doesNotContain("praise");
        // a group whose only member is unreachable has nothing to merge: 503, never an empty index
        group("cran-only-dead", "cran", "cran-dead");
        fetch("cran-only-dead", "src/contrib/PACKAGES.gz")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }
}
