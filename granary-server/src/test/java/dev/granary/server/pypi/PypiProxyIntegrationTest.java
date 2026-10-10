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
import static dev.granary.server.pypi.PypiFixtures.upstreamRootHtml;
import static dev.granary.server.pypi.PypiFixtures.upstreamRootJson;
import static dev.granary.server.pypi.PypiFixtures.wheel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
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

/** A proxy in front of a fake pypi.org: caching, link rewriting, both index flavours and the offline-first fallbacks. */
class PypiProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "pypi-proxy";
    private static final String URL = "/repository/" + REPO;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private ComponentDao components;

    private Repository repo;
    private UpstreamFile sixSdist;
    private UpstreamFile sixWheel;

    @BeforeAll
    static void startUpstream() {
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void createRepository() {
        upstream.resetAll();
        repo = createProxy(REPO, null);
        // the sdist lives under /packages/ like on files.pythonhosted.org, the wheel somewhere else entirely
        sixSdist = new UpstreamFile("six-1.16.0.tar.gz", upstream.baseUrl() + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz", sdist("six", "1.16.0"));
        sixWheel = new UpstreamFile("six-1.16.0-py2.py3-none-any.whl", upstream.baseUrl() + "/files/six-1.16.0-py2.py3-none-any.whl",
                wheel("six", "1.16.0"));
        stubProject("six", List.of(sixSdist, sixWheel));
        stubFile(sixSdist);
        stubFile(sixWheel);
    }

    /** A proxy whose files host is the fake upstream too (pypi.org keeps files on files.pythonhosted.org). */
    private Repository createProxy(String name, Integer metadataMaxAgeMinutes) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat("pypi");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(upstream.baseUrl() + "/");
        r.setMetadataMaxAgeMinutes(metadataMaxAgeMinutes);
        r.setFormatSettings(Map.of("pypi.filesUrl", upstream.baseUrl() + "/"));
        Repository created = repositories.create(r);
        proxyService.invalidate(created);
        return created;
    }

    @Test
    void jsonPageIsCachedAndRewrittenForPip() throws Exception {
        ObjectNode page = object(mvc.perform(get(URL + "/simple/six/").accept(PIP_ACCEPT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(JSON_V1))
                .andReturn());
        assertThat(str(page, "name")).isEqualTo("six");
        assertThat(str(page, "meta", "api-version")).isEqualTo("1.3");
        assertThat(filenames(page)).containsExactly("six-1.16.0.tar.gz", "six-1.16.0-py2.py3-none-any.whl");
        JsonNode sdist = page.path("files").get(0);
        assertThat(str(sdist, "url")).isEqualTo(repositoryUrl(REPO) + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz");
        assertThat(str(sdist, "hashes", "sha256")).isEqualTo(sixSdist.sha256());
        assertThat(str(sdist, "requires-python")).isEqualTo(">=3.8");
        assertThat(str(sdist, "core-metadata", "sha256")).isEqualTo(sha256(upstreamMetadata(sixSdist)));
        assertThat(sdist.path("size").longValue()).isEqualTo(sixSdist.bytes().length);
        assertThat(str(sdist, "upload-time")).isEqualTo("2024-05-01T12:00:00.000000Z");
        assertThat(page.path("versions").valueStream().map(JsonNode::asString)).containsExactly("1.16.0");
        // a link that is not under /packages/ is addressed by project and file name
        assertThat(str(page.path("files").get(1), "url")).isEqualTo(repositoryUrl(REPO) + "/packages/-/six/six-1.16.0-py2.py3-none-any.whl");

        // the client's preference is forwarded upstream in canonical form
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/simple/six/"))
                .withHeader("Accept", WireMock.equalTo(JSON_V1 + ", application/vnd.pypi.simple.v1+html;q=0.2, text/html;q=0.01")));

        // the raw upstream page is what is cached; the rewrite happens on the way out
        String cached = storage.find(repo, "simple/six/index.json").orElseThrow().readUtf8();
        assertThat(cached).contains(upstream.baseUrl() + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz");
        assertThat(storage.find(repo, "simple/six/index.json").orElseThrow().asset().isMetadata()).isTrue();

        // fresh metadata is served from the cache without another round trip
        mvc.perform(get(URL + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/simple/six/")));
    }

    @Test
    void htmlPageIsCachedSeparatelyForHtmlOnlyClients() throws Exception {
        String html = text(mvc.perform(get(URL + "/simple/six/").accept("text/html"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andReturn());
        assertThat(html)
                .contains("<a href=\"" + repositoryUrl(REPO) + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz#sha256=" + sixSdist.sha256() + "\"")
                .contains("<a href=\"" + repositoryUrl(REPO) + "/packages/-/six/six-1.16.0-py2.py3-none-any.whl#sha256=" + sixWheel.sha256() + "\"")
                .contains("data-requires-python=\"&gt;=3.8\"")
                .contains("data-core-metadata=\"sha256=" + sha256(upstreamMetadata(sixSdist)) + "\"")
                .contains("data-dist-info-metadata=\"sha256=" + sha256(upstreamMetadata(sixSdist)) + "\"")
                .contains("<meta name=\"pypi:repository-version\" content=\"1.3\">")
                .doesNotContain(upstream.baseUrl());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/simple/six/"))
                .withHeader("Accept", WireMock.equalTo("application/vnd.pypi.simple.v1+html, text/html;q=0.9, */*;q=0.1")));
        assertThat(storage.find(repo, "simple/six/index.html")).isPresent();
        assertThat(storage.find(repo, "simple/six/index.json")).isEmpty();

        // an upstream that only speaks HTML still satisfies a JSON client
        upstream.resetAll();
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/simple/legacy/"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody(upstreamHtml("legacy", List.of(sixSdist)))));
        ObjectNode page = object(mvc.perform(get(URL + "/simple/legacy/").accept(PIP_ACCEPT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(JSON_V1))
                .andReturn());
        assertThat(filenames(page)).containsExactly("six-1.16.0.tar.gz");
        assertThat(str(page.path("files").get(0), "hashes", "sha256")).isEqualTo(sixSdist.sha256());
    }

    @Test
    void filesAreFetchedOnceAndCachedForever() throws Exception {
        mvc.perform(get(URL + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            assertThat(mvc.perform(get(URL + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"six-1.16.0.tar.gz\""))
                    .andReturn().getResponse().getContentAsByteArray()).isEqualTo(sixSdist.bytes());
            assertThat(mvc.perform(get(URL + "/packages/-/six/six-1.16.0-py2.py3-none-any.whl"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray()).isEqualTo(sixWheel.bytes());
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/files/six-1.16.0-py2.py3-none-any.whl")));
        assertThat(storage.find(repo, "packages/71/39/171f1c67cd00/six-1.16.0.tar.gz")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isFalse();
            assertThat(c.asset().getDownloadCount()).isEqualTo(2);
        });
        assertThat(storage.find(repo, "packages/-/six/six-1.16.0-py2.py3-none-any.whl")).isPresent();

        // the PEP 658 sidecars are fetched the same way
        assertThat(mvc.perform(get(URL + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz.metadata")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(upstreamMetadata(sixSdist));
        assertThat(mvc.perform(get(URL + "/packages/-/six/six-1.16.0-py2.py3-none-any.whl.metadata")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(upstreamMetadata(sixWheel));

        // a file that is not on the upstream is a 404 that is remembered
        mvc.perform(get(URL + "/packages/71/39/171f1c67cd00/six-9.9.9.tar.gz")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/packages/-/six/six-9.9.9.tar.gz")).andExpect(status().isNotFound());
        mvc.perform(get(URL + "/packages/-/unknown/unknown-1.0.tar.gz")).andExpect(status().isNotFound());
    }

    @Test
    void staleCacheIsServedWhenUpstreamBreaks() throws Exception {
        Repository impatient = createProxy("pypi-proxy-stale", 0); // re-validate every page on every request
        String url = "/repository/pypi-proxy-stale";
        mvc.perform(get(url + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk());
        mvc.perform(get(url + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk());
        upstream.verify(2, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/simple/six/")));
        mvc.perform(get(url + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz")).andExpect(status().isOk());

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));

        ObjectNode page = object(mvc.perform(get(url + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk()).andReturn());
        assertThat(str(page.path("files").get(0), "url")).isEqualTo(repositoryUrl("pypi-proxy-stale") + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz");
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/simple/six/"))); // it did try
        assertThat(mvc.perform(get(url + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(sixSdist.bytes());
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz")));

        // something never cached while upstream is broken is a temporary 503, not a 404 pip would believe
        mvc.perform(get(url + "/simple/requests/").accept(PIP_ACCEPT)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(get(url + "/packages/-/six/six-1.16.0-py2.py3-none-any.whl")).andExpect(status().isServiceUnavailable());
        assertThat(impatient.getMetadataMaxAgeMinutes()).isZero();
    }

    @Test
    void offlineModeNeverTouchesUpstream() throws Exception {
        mvc.perform(get(URL + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk());
        mvc.perform(get(URL + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz")).andExpect(status().isOk());

        repositories.setOffline(REPO, true);
        upstream.resetAll();
        stubProject("requests", List.of(sixSdist));

        mvc.perform(get(URL + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].filename").value("six-1.16.0.tar.gz"));
        mvc.perform(get(URL + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz")).andExpect(status().isOk());
        mvc.perform(get(URL + "/simple/requests/").accept(PIP_ACCEPT)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        mvc.perform(get(URL + "/packages/-/six/six-1.16.0-py2.py3-none-any.whl")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        repositories.setOffline(REPO, false);
        mvc.perform(get(URL + "/simple/requests/").accept(PIP_ACCEPT)).andExpect(status().isOk());

        // the global switch behaves the same way
        settings.setGlobalOffline(true);
        mvc.perform(get(URL + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk());
        mvc.perform(get(URL + "/simple/flask/").accept(PIP_ACCEPT)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
    }

    @Test
    void upstream404IsRememberedByTheNegativeCache() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/simple/nope/"))
                .willReturn(WireMock.aResponse().withStatus(404).withHeader("Content-Type", "text/html").withBody("<h1>Not Found</h1>")));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(URL + "/simple/nope/").accept(PIP_ACCEPT)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("content.notFound"));
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/simple/nope/")));
        mvc.perform(get(URL + "/pypi/nope/json")).andExpect(status().isNotFound());
    }

    @Test
    void projectListComesFromUpstream() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/simple/"))
                .withHeader("Accept", WireMock.containing(JSON_V1))
                .atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", JSON_V1)
                        .withBody(upstreamRootJson(List.of("six", "requests")))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/simple/"))
                .atPriority(5)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody(upstreamRootHtml(List.of("six", "requests")))));

        mvc.perform(get(URL + "/simple/").accept(PIP_ACCEPT)).andExpect(status().isOk())
                .andExpect(content().contentType(JSON_V1))
                .andExpect(jsonPath("$.projects[0].name").value("six"))
                .andExpect(jsonPath("$.projects[1].name").value("requests"));
        assertThat(text(mvc.perform(get(URL + "/simple/").accept("text/html")).andExpect(status().isOk()).andReturn()))
                .contains("<a href=\"six/\">six</a>")
                .contains("<a href=\"requests/\">requests</a>");
        assertThat(storage.find(repo, "simple/index.json")).isPresent();
        assertThat(storage.find(repo, "simple/index.html")).isPresent();
    }

    @Test
    void jsonApiIsProxiedWithRewrittenUrls() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/pypi/six/json"))
                .willReturn(WireMock.okJson(upstreamJsonApi("six", "1.16.0", sixSdist))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/pypi/six/1.16.0/json"))
                .willReturn(WireMock.okJson(upstreamJsonApi("six", "1.16.0", sixSdist))));

        ObjectNode doc = object(mvc.perform(get(URL + "/pypi/six/json")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn());
        assertThat(str(doc, "info", "summary")).isEqualTo("upstream six");
        assertThat(str(doc.path("urls").get(0), "url")).isEqualTo(repositoryUrl(REPO) + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz");
        assertThat(str(doc.path("releases").path("1.16.0").get(0), "url")).isEqualTo(repositoryUrl(REPO) + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz");
        mvc.perform(get(URL + "/pypi/six/1.16.0/json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.urls[0].url").value(repositoryUrl(REPO) + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz"));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/pypi/six/json"))
                .withHeader("Accept", WireMock.equalTo("application/json")));
        mvc.perform(get(URL + "/pypi/six/json")).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/pypi/six/json")));
        // the JSON API document is the source of the file for a client that found it there
        assertThat(mvc.perform(get(URL + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(sixSdist.bytes());
    }

    @Test
    void nonCanonicalNamesAreRedirectedBeforeAskingUpstream() throws Exception {
        mvc.perform(get(URL + "/simple/Six").accept(PIP_ACCEPT)).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", repositoryUrl(REPO) + "/simple/six/"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void writesAreRefusedWith405() throws Exception {
        mvc.perform(uploadWheel(URL + "/", "acme-demo", "1.0.0", wheel("acme-demo", "1.0.0")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("proxy repository")));
        mvc.perform(delete(URL + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void theRemoteUrlMayBeTheSimpleIndexItselfTheWayPipUsersWriteIt() throws Exception {
        Repository r = new Repository();
        r.setName("pypi-simple-url");
        r.setFormat("pypi");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(upstream.baseUrl() + "/simple");
        r.setFormatSettings(Map.of("pypi.filesUrl", upstream.baseUrl() + "/"));
        proxyService.invalidate(repositories.create(r));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/pypi/six/json"))
                .willReturn(WireMock.okJson(upstreamJsonApi("six", "1.16.0", sixSdist))));

        ObjectNode page = object(mvc.perform(get("/repository/pypi-simple-url/simple/six/").accept(PIP_ACCEPT))
                .andExpect(status().isOk()).andReturn());
        assertThat(filenames(page)).containsExactly("six-1.16.0.tar.gz", "six-1.16.0-py2.py3-none-any.whl");
        mvc.perform(get("/repository/pypi-simple-url/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz")).andExpect(status().isOk());
        mvc.perform(get("/repository/pypi-simple-url/pypi/six/json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.summary").value("upstream six"));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/simple/six/")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/pypi/six/json")));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathMatching("/simple/simple/.*")));
    }

    @Test
    void anotherIndexKeepsItsFilesUnderItsOwnPackagesPath() throws Exception {
        // another Granary (or Nexus) as upstream: its links live under <remote>/packages/…, not on files.pythonhosted.org,
        // and the proxy is left with the default files host
        Repository r = new Repository();
        r.setName("pypi-chained");
        r.setFormat("pypi");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(upstream.baseUrl() + "/repository/pypi-hosted/simple/");
        proxyService.invalidate(repositories.create(r));
        String url = "/repository/pypi-chained";
        UpstreamFile acme = new UpstreamFile("acme_demo-1.0.0.tar.gz",
                upstream.baseUrl() + "/repository/pypi-hosted/packages/acme-demo/1.0.0/acme_demo-1.0.0.tar.gz", sdist("acme-demo", "1.0.0"));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/repository/pypi-hosted/simple/acme-demo/"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", JSON_V1)
                        .withBody(upstreamJson("acme-demo", List.of(acme)))));
        stubFile(acme);

        ObjectNode page = object(mvc.perform(get(url + "/simple/acme-demo/").accept(PIP_ACCEPT)).andExpect(status().isOk()).andReturn());
        String link = str(page.path("files").get(0), "url");
        assertThat(link).isEqualTo(repositoryUrl("pypi-chained") + "/packages/-/acme-demo/acme_demo-1.0.0.tar.gz");
        assertThat(mvc.perform(get(url + "/packages/-/acme-demo/acme_demo-1.0.0.tar.gz")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(acme.bytes());
        assertThat(mvc.perform(get(url + "/packages/-/acme-demo/acme_demo-1.0.0.tar.gz.metadata")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(upstreamMetadata(acme));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/repository/pypi-hosted/packages/acme-demo/1.0.0/acme_demo-1.0.0.tar.gz")));
    }

    @Test
    void cachedFilesShowUpAsComponents() throws Exception {
        mvc.perform(get(URL + "/simple/six/").accept(PIP_ACCEPT)).andExpect(status().isOk());
        mvc.perform(get(URL + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz")).andExpect(status().isOk());
        mvc.perform(get(URL + "/packages/71/39/171f1c67cd00/six-1.16.0.tar.gz.metadata")).andExpect(status().isOk());
        mvc.perform(get(URL + "/packages/-/six/six-1.16.0-py2.py3-none-any.whl")).andExpect(status().isOk());
        var six = components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "six", "1.16.0");
        assertThat(six).isPresent();
        for (String path : List.of("packages/71/39/171f1c67cd00/six-1.16.0.tar.gz", "packages/71/39/171f1c67cd00/six-1.16.0.tar.gz.metadata",
                "packages/-/six/six-1.16.0-py2.py3-none-any.whl")) {
            assertThat(storage.find(repo, path)).as(path).hasValueSatisfying(c -> assertThat(c.asset().getComponentId()).isEqualTo(six.get().getId()));
        }
        // index documents are not part of any component
        assertThat(storage.find(repo, "simple/six/index.json")).hasValueSatisfying(c -> assertThat(c.asset().getComponentId()).isNull());
    }

    private void stubProject(String name, List<UpstreamFile> files) {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/simple/" + name + "/"))
                .withHeader("Accept", WireMock.containing(JSON_V1))
                .atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", JSON_V1)
                        .withBody(upstreamJson(name, files))));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/simple/" + name + "/"))
                .atPriority(5)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody(upstreamHtml(name, files))));
    }

    private static void stubFile(UpstreamFile file) {
        String path = java.net.URI.create(file.url()).getPath();
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(file.bytes())));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo(path + ".metadata"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "binary/octet-stream").withBody(upstreamMetadata(file))));
    }
}
