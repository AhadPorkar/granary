package dev.granary.server.rubygems;

import static dev.granary.server.rubygems.RubygemsFixtures.gem;
import static dev.granary.server.rubygems.RubygemsFixtures.lines;
import static dev.granary.server.rubygems.RubygemsFixtures.md5;
import static dev.granary.server.rubygems.RubygemsFixtures.repositoryUrl;
import static dev.granary.server.rubygems.RubygemsFixtures.specs;
import static dev.granary.server.rubygems.RubygemsFixtures.versions;
import static dev.granary.server.rubygems.RubygemsProxyIntegrationTest.RAKE_INFO;
import static dev.granary.server.rubygems.RubygemsProxyIntegrationTest.VERSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/** One source URL for gem and Bundler: a group of a hosted repository and a proxy of a fake rubygems.org. */
class RubygemsGroupIntegrationTest extends IntegrationTest {

    private static final String URL = "/repository/gems";
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /**
     * {@code specs.4.8.gz} as Ruby writes it: alpha and beta 1.0, 1.1 and 2.0, gamma 0.1 for x86_64-linux and
     * delta 3.0 for ruby and java, with Ruby's own object links.
     */
    private static final byte[] RUBY_SPECS = HexFormat.of().parseHex("1f8b08000434ca6a000363e188e68be6f054e24acc29c84864b"
            + "362730d09b512744fcdb5b20a4b2d2acecccf8b66f354e230d43360b36608f154e22c2a4daa0431a3391cd843add9a0b2862021076e"
            + "64412388169020505b526a492298cb015625eac00ba505210ab8d2137373c12a60fa0d20867a2a09565898c59b99e8e664e6955640"
            + "ac06aa4f49cd2941516f8c6c1f42da4101687b5662199803001076ad52ee000000");

    @Autowired
    private ProxyService proxyService;

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
        hosted("gems-hosted", "rubygems");
        Repository proxy = proxy("gems-proxy", "rubygems", upstream.baseUrl() + "/");
        proxyService.invalidate(proxy);
        group("gems", "rubygems", "gems-hosted", "gems-proxy");

        stub("/versions", VERSIONS.getBytes(StandardCharsets.UTF_8));
        stub("/info/rake", RAKE_INFO.getBytes(StandardCharsets.UTF_8));
        stub("/names", "---\nminitest\nrake\n".getBytes(StandardCharsets.UTF_8));
        stub("/specs.4.8.gz", RUBY_SPECS);
        stub("/gems/rake-13.4.2.gem", gem("rake", "13.4.2"));
    }

    private void stub(String path, byte[] body) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path)).willReturn(WireMock.aResponse()
                .withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(body)));
    }

    private ResultActions push(String repository, byte[] gem) throws Exception {
        return mvc.perform(post("/repository/" + repository + "/api/v1/gems").header("Authorization", adminBasic())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(gem));
    }

    private MockHttpServletResponse fetch(String path) throws Exception {
        return mvc.perform(get(URL + "/" + path)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private String text(String path) throws Exception {
        return fetch(path).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void versionsAppendHostedGemsToUpstreamsFileWithMatchingChecksums() throws Exception {
        push("gems-hosted", gem("acme_hello", "0.1.0", "rake:>= 12.0")).andExpect(status().isOk());
        push("gems-hosted", gem("rake", "99.0")).andExpect(status().isOk());

        String merged = text("versions");
        assertThat(merged).startsWith(VERSIONS);
        String rakeInfo = text("info/rake");
        String acmeInfo = text("info/acme_hello");
        assertThat(merged.substring(VERSIONS.length())).isEqualTo(
                "acme_hello 0.1.0 " + md5(acmeInfo) + "\n" + "rake 99.0 " + md5(rakeInfo) + "\n");

        // what Bundler ends up with: every version, and checksums that match the info files the group serves
        assertThat(versions(merged).get("rake")).containsExactly("13.4.1", "13.4.2", "99.0", md5(rakeInfo));
        assertThat(lines(rakeInfo)).extracting(l -> l.substring(0, l.indexOf(' '))).containsExactly("99.0", "13.4.1", "13.4.2");
        assertThat(text("names")).isEqualTo("---\nacme_hello\nminitest\nrake\n");
    }

    @Test
    void aHostedVersionShadowsTheSameUpstreamVersion() throws Exception {
        byte[] ours = gem("rake", "13.4.2");
        push("gems-hosted", ours).andExpect(status().isOk());
        assertThat(lines(text("info/rake")).getFirst()).startsWith("13.4.2 |checksum:" + RubygemsFixtures.sha256(ours));
        assertThat(fetch("gems/rake-13.4.2.gem").getContentAsByteArray()).isEqualTo(ours);
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/gems/rake-13.4.2.gem")));
    }

    @Test
    void specsAppendHostedTuplesToRubysIndex() throws Exception {
        push("gems-hosted", gem("acme_hello", "0.1.0")).andExpect(status().isOk());
        push("gems-hosted", gem("alpha", "2.0")).andExpect(status().isOk());

        assertThat(specs(fetch("specs.4.8.gz").getContentAsByteArray())).containsExactly(
                "alpha 1.0 ruby", "alpha 1.1 ruby", "alpha 2.0 ruby", "beta 1.0 ruby", "beta 1.1 ruby", "beta 2.0 ruby",
                "gamma 0.1 x86_64-linux", "delta 3.0 ruby", "delta 3.0 java", "acme_hello 0.1.0 ruby");
        // upstream has no latest_specs here: the hosted member's copy is served alone
        assertThat(specs(fetch("latest_specs.4.8.gz").getContentAsByteArray()))
                .containsExactly("acme_hello 0.1.0 ruby", "alpha 2.0 ruby");
    }

    @Test
    void filesComeFromTheFirstMemberThatHasThem() throws Exception {
        assertThat(fetch("gems/rake-13.4.2.gem").getContentAsByteArray()).isEqualTo(gem("rake", "13.4.2"));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/gems/rake-13.4.2.gem")));
        mvc.perform(get(URL + "/gems/missing-1.0.gem")).andExpect(status().isNotFound());
    }

    @Test
    void hostedGemsStayInstallableWhileUpstreamIsDown() throws Exception {
        push("gems-hosted", gem("acme_hello", "0.1.0")).andExpect(status().isOk());
        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        mvc.perform(get(URL + "/info/acme_hello")).andExpect(status().isOk());
        mvc.perform(get(URL + "/gems/acme_hello-0.1.0.gem")).andExpect(status().isOk());
        assertThat(versions(text("versions"))).containsKey("acme_hello");
        mvc.perform(get(URL + "/info/rails")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void writesNameTheHostedMember() throws Exception {
        push("gems", gem("acme_hello", "0.1.0")).andExpect(status().isMethodNotAllowed())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("use its hosted member 'gems-hosted'")));
    }

    @Test
    void theJsonApiPrefersHostedGems() throws Exception {
        push("gems-hosted", gem("rake", "99.0")).andExpect(status().isOk());
        mvc.perform(get(URL + "/api/v1/gems/rake.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("99.0"))
                .andExpect(jsonPath("$.gem_uri").value(repositoryUrl("gems") + "/gems/rake-99.0.gem"));
    }
}
