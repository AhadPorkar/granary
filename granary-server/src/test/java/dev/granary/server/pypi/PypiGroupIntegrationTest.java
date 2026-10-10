package dev.granary.server.pypi;

import static dev.granary.server.pypi.PypiFixtures.JSON_V1;
import static dev.granary.server.pypi.PypiFixtures.PIP_ACCEPT;
import static dev.granary.server.pypi.PypiFixtures.filenames;
import static dev.granary.server.pypi.PypiFixtures.object;
import static dev.granary.server.pypi.PypiFixtures.repositoryUrl;
import static dev.granary.server.pypi.PypiFixtures.sdist;
import static dev.granary.server.pypi.PypiFixtures.sha256;
import static dev.granary.server.pypi.PypiFixtures.str;
import static dev.granary.server.pypi.PypiFixtures.text;
import static dev.granary.server.pypi.PypiFixtures.uploadWheel;
import static dev.granary.server.pypi.PypiFixtures.upstreamHtml;
import static dev.granary.server.pypi.PypiFixtures.upstreamJson;
import static dev.granary.server.pypi.PypiFixtures.upstreamJsonApi;
import static dev.granary.server.pypi.PypiFixtures.upstreamMetadata;
import static dev.granary.server.pypi.PypiFixtures.upstreamRootJson;
import static dev.granary.server.pypi.PypiFixtures.wheel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.server.IntegrationTest;
import dev.granary.server.pypi.PypiFixtures.UpstreamFile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** One index URL for everything: a group of a hosted repository and a proxy of a fake pypi.org. */
class PypiGroupIntegrationTest extends IntegrationTest {

    private static final String GROUP = "pypi-group";
    private static final String URL = "/repository/" + GROUP;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    private final byte[] hostedWheel = wheel("acme-demo", "1.0.0");
    private UpstreamFile upstreamWheel;
    private UpstreamFile upstreamOld;
    private UpstreamFile six;

    @BeforeAll
    static void startUpstream() {
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void createRepositories() throws Exception {
        upstream.resetAll();
        hosted("pypi-hosted", "pypi");
        Repository proxy = new Repository();
        proxy.setName("pypi-proxy");
        proxy.setFormat("pypi");
        proxy.setType(RepositoryType.PROXY);
        proxy.setRemoteUrl(upstream.baseUrl() + "/");
        proxy.setFormatSettings(Map.of("pypi.filesUrl", upstream.baseUrl() + "/"));
        proxyService.invalidate(repositories.create(proxy));
        group(GROUP, "pypi", "pypi-hosted", "pypi-proxy");

        // upstream knows acme-demo too: an older release and a file with the very same name as the hosted one
        upstreamOld = new UpstreamFile("acme_demo-0.9.0.tar.gz", upstream.baseUrl() + "/packages/aa/bb/acme_demo-0.9.0.tar.gz", sdist("acme-demo", "0.9.0"));
        upstreamWheel = new UpstreamFile("acme_demo-1.0.0-py3-none-any.whl", upstream.baseUrl() + "/packages/cc/dd/acme_demo-1.0.0-py3-none-any.whl",
                wheel("acme-demo", "1.0.0", "Metadata-Version: 2.1\nName: acme-demo\nVersion: 1.0.0\nSummary: impostor\n"));
        six = new UpstreamFile("six-1.16.0.tar.gz", upstream.baseUrl() + "/packages/71/39/six-1.16.0.tar.gz", sdist("six", "1.16.0"));
        stubProject("acme-demo", List.of(upstreamOld, upstreamWheel));
        stubProject("six", List.of(six));
        for (UpstreamFile f : List.of(upstreamOld, upstreamWheel, six)) {
            String path = java.net.URI.create(f.url()).getPath();
            upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path))
                    .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(f.bytes())));
        }
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/simple/"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", JSON_V1)
                        .withBody(upstreamRootJson(List.of("acme-demo", "six", "requests")))));

        mvc.perform(uploadWheel("/repository/pypi-hosted/", "acme-demo", "1.0.0", hostedWheel).header("Authorization", adminBasic()))
                .andExpect(status().isOk());
    }

    @Test
    void mergesHostedAndProxiedFilesOfTheSameProject() throws Exception {
        ObjectNode page = object(mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(JSON_V1))
                .andReturn());
        assertThat(filenames(page)).containsExactly("acme_demo-1.0.0-py3-none-any.whl", "acme_demo-0.9.0.tar.gz");
        assertThat(page.path("versions").valueStream().map(JsonNode::asString)).containsExactly("0.9.0", "1.0.0");
        // every link points at the group, and the hosted member wins the file both have
        JsonNode wheel = page.path("files").get(0);
        assertThat(str(wheel, "url")).isEqualTo(repositoryUrl(GROUP) + "/packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl");
        assertThat(str(wheel, "hashes", "sha256")).isEqualTo(sha256(hostedWheel));
        assertThat(str(page.path("files").get(1), "url")).isEqualTo(repositoryUrl(GROUP) + "/packages/aa/bb/acme_demo-0.9.0.tar.gz");
        assertThat(str(page, "meta", "api-version")).isEqualTo("1.3");

        String html = text(mvc.perform(get(URL + "/simple/acme-demo/").accept("text/html")).andExpect(status().isOk()).andReturn());
        assertThat(html).contains("<a href=\"" + repositoryUrl(GROUP) + "/packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl#sha256=" + sha256(hostedWheel) + "\"")
                .contains("<a href=\"" + repositoryUrl(GROUP) + "/packages/aa/bb/acme_demo-0.9.0.tar.gz#sha256=" + upstreamOld.sha256() + "\"")
                .doesNotContain(upstream.baseUrl());

        // and the files download through the group from whichever member has them
        assertThat(mvc.perform(get(URL + "/packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(hostedWheel);
        assertThat(text(mvc.perform(get(URL + "/packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl.metadata")).andExpect(status().isOk())
                .andReturn())).contains("Summary: Demo package acme-demo");
        assertThat(mvc.perform(get(URL + "/packages/aa/bb/acme_demo-0.9.0.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(upstreamOld.bytes());
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/packages/cc/dd/acme_demo-1.0.0-py3-none-any.whl")));
    }

    @Test
    void proxiedOnlyProjectsAndTheProjectListAreUnioned() throws Exception {
        ObjectNode page = object(mvc.perform(get(URL + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk()).andReturn());
        assertThat(filenames(page)).containsExactly("six-1.16.0.tar.gz");
        assertThat(str(page.path("files").get(0), "url")).isEqualTo(repositoryUrl(GROUP) + "/packages/71/39/six-1.16.0.tar.gz");
        assertThat(mvc.perform(get(URL + "/packages/71/39/six-1.16.0.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(six.bytes());

        ObjectNode list = object(mvc.perform(get(URL + "/simple/").accept(PIP_ACCEPT)).andExpect(status().isOk()).andReturn());
        assertThat(list.path("projects").valueStream().map(p -> p.path("name").asString())).containsExactly("acme-demo", "requests", "six");

        mvc.perform(get(URL + "/simple/Six").accept(PIP_ACCEPT)).andExpect(status().isMovedPermanently());
        mvc.perform(get(URL + "/simple/nothing-here/").accept(PIP_ACCEPT)).andExpect(status().isNotFound());
    }

    @Test
    void jsonApiIsMergedAcrossMembers() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/pypi/acme-demo/json"))
                .willReturn(WireMock.okJson(upstreamJsonApi("acme-demo", "0.9.0", upstreamOld))));
        ObjectNode doc = object(mvc.perform(get(URL + "/pypi/acme-demo/json")).andExpect(status().isOk()).andReturn());
        assertThat(str(doc, "info", "version")).isEqualTo("1.0.0");
        assertThat(str(doc, "info", "summary")).isEqualTo("Demo package acme-demo");
        assertThat(doc.path("releases").propertyNames()).containsExactlyInAnyOrder("0.9.0", "1.0.0");
        assertThat(str(doc.path("releases").path("0.9.0").get(0), "url")).isEqualTo(repositoryUrl(GROUP) + "/packages/aa/bb/acme_demo-0.9.0.tar.gz");
        assertThat(str(doc.path("releases").path("1.0.0").get(0), "url")).isEqualTo(repositoryUrl(GROUP) + "/packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl");
    }

    @Test
    void writesToAGroupAreRefusedAndNameTheHostedMember() throws Exception {
        mvc.perform(uploadWheel(URL + "/", "acme-demo", "2.0.0", wheel("acme-demo", "2.0.0")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("pypi-hosted")));
        mvc.perform(delete(URL + "/packages/acme-demo/1.0.0").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"));
        mvc.perform(get("/repository/pypi-hosted/simple/acme-demo/").accept(PIP_ACCEPT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0]").value("1.0.0"));
    }

    @Test
    void hostedFilesStayAvailableWhileUpstreamIsDown() throws Exception {
        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        ObjectNode page = object(mvc.perform(get(URL + "/simple/acme-demo/").accept(PIP_ACCEPT)).andExpect(status().isOk()).andReturn());
        assertThat(filenames(page)).containsExactly("acme_demo-1.0.0-py3-none-any.whl");
        mvc.perform(get(URL + "/packages/acme-demo/1.0.0/acme_demo-1.0.0-py3-none-any.whl")).andExpect(status().isOk());
        mvc.perform(get(URL + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(get(URL + "/packages/71/39/six-1.16.0.tar.gz")).andExpect(status().isServiceUnavailable());
        // the hosted member alone still lists its projects
        mvc.perform(get(URL + "/simple/").accept(PIP_ACCEPT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].name").value("acme-demo"));
    }

    private void stubProject(String name, List<UpstreamFile> files) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/simple/" + name + "/"))
                .withHeader("Accept", WireMock.containing(JSON_V1))
                .atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", JSON_V1).withBody(upstreamJson(name, files))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/simple/" + name + "/"))
                .atPriority(5)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(upstreamHtml(name, files))));
        for (UpstreamFile f : files) {
            upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(java.net.URI.create(f.url()).getPath() + ".metadata"))
                    .willReturn(WireMock.aResponse().withStatus(200).withBody(upstreamMetadata(f))));
        }
    }
}
