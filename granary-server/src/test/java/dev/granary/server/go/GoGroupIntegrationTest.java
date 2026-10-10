package dev.granary.server.go;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static dev.granary.server.go.GoFixtures.MODULE;
import static dev.granary.server.go.GoFixtures.bytes;
import static dev.granary.server.go.GoFixtures.curlPut;
import static dev.granary.server.go.GoFixtures.goGet;
import static dev.granary.server.go.GoFixtures.moduleZip;
import static dev.granary.server.go.GoFixtures.sumdbLookup;
import static dev.granary.server.go.GoFixtures.text;
import static dev.granary.server.go.GoFixtures.upstreamInfo;
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
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** One GOPROXY URL for private and public modules: a group of a hosted repository and a proxy of a fake proxy.golang.org. */
class GoGroupIntegrationTest extends IntegrationTest {

    private static final String GROUP = "go-group";
    private static final String HOSTED = "go-hosted";
    private static final String PROXY = "go-proxy";
    private static final String SHARED = "example.com/acme/shared";
    private static final byte[] UPSTREAM_SHARED_ZIP = moduleZip(SHARED, "v1.0.0", Map.of("go.mod", "module " + SHARED + "\n", "up.go", "package shared\n"));

    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

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
        stubUpstream();
        hosted(HOSTED, "go");
        Repository r = new Repository();
        r.setName(PROXY);
        r.setFormat("go");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(upstream.baseUrl() + "/");
        r.setFormatSettings(Map.of("go.sumdbUrl", upstream.baseUrl() + "/sumdb-upstream"));
        proxyService.invalidate(repositories.create(r));
        group(GROUP, "go", HOSTED, PROXY);
    }

    private static void stubUpstream() {
        // a module that exists both upstream and in the hosted repository, with an overlapping version
        upstream.stubFor(WireMock.get(urlEqualTo("/" + SHARED + "/@v/list")).willReturn(aResponse().withStatus(200).withBody("v0.9.0\nv1.0.0\nv1.1.0-rc.1\n")));
        upstream.stubFor(WireMock.get(urlEqualTo("/" + SHARED + "/@v/v1.0.0.info"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(upstreamInfo("v1.0.0", "2020-01-01T00:00:00Z"))));
        upstream.stubFor(WireMock.get(urlEqualTo("/" + SHARED + "/@v/v0.9.0.info"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(upstreamInfo("v0.9.0", "2019-01-01T00:00:00Z"))));
        upstream.stubFor(WireMock.get(urlEqualTo("/" + SHARED + "/@v/v1.0.0.zip"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/zip").withBody(UPSTREAM_SHARED_ZIP)));
        // a public module
        upstream.stubFor(WireMock.get(urlEqualTo("/rsc.io/quote/@v/list")).willReturn(aResponse().withStatus(200).withBody("v1.5.1\nv1.5.2\n")));
        upstream.stubFor(WireMock.get(urlEqualTo("/rsc.io/quote/@v/v1.5.2.info"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(upstreamInfo("v1.5.2", "2018-02-14T15:44:20Z"))));
        upstream.stubFor(WireMock.get(urlEqualTo("/rsc.io/quote/@v/v1.5.2.mod")).willReturn(aResponse().withStatus(200).withBody("module rsc.io/quote\n")));
        // a module without tags: its list is empty and @latest names a pseudo-version
        upstream.stubFor(WireMock.get(urlEqualTo("/golang.org/x/exp/@v/list")).willReturn(aResponse().withStatus(200).withBody("")));
        upstream.stubFor(WireMock.get(urlEqualTo("/golang.org/x/exp/@latest"))
                .willReturn(aResponse().withStatus(200).withBody(upstreamInfo("v0.0.0-20240613232115-7f521ea00fb8", "2024-06-13T23:21:15Z"))));
        // what proxy.golang.org answers for private paths and for the shorter prefixes the go command probes
        upstream.stubFor(WireMock.get(WireMock.urlMatching("/example\\.com/(acme/)?(hello/)?@v/.*")).atPriority(10)
                .willReturn(notFound().withBody("not found: module example.com/acme/hello: unrecognized import path")));
        upstream.stubFor(WireMock.get(WireMock.urlMatching("/example\\.com/(acme/)?@latest")).atPriority(10).willReturn(notFound()));
        upstream.stubFor(WireMock.get(urlEqualTo("/sumdb-upstream/lookup/rsc.io/quote@v1.5.2"))
                .willReturn(aResponse().withStatus(200).withBody(sumdbLookup("rsc.io/quote", "v1.5.2"))));
    }

    private void publish(String module, String version) throws Exception {
        mvc.perform(curlPut(HOSTED, module + "/@v/" + version + ".zip", moduleZip(module, version)).header("Authorization", adminBasic()))
                .andExpect(status().isCreated());
    }

    @Test
    void privateModulesComeFromTheHostedMember() throws Exception {
        publish(MODULE, "v0.1.0");
        mvc.perform(goGet(GROUP, MODULE + "/@v/v0.1.0.info")).andExpect(status().isOk()).andExpect(jsonPath("$.Version").value("v0.1.0"));
        mvc.perform(goGet(GROUP, MODULE + "/@v/v0.1.0.mod")).andExpect(status().isOk()).andExpect(content().string(containsString("module " + MODULE)));
        assertThat(bytes(mvc.perform(goGet(GROUP, MODULE + "/@v/v0.1.0.zip")).andExpect(status().isOk()).andReturn()))
                .isEqualTo(moduleZip(MODULE, "v0.1.0"));
        assertThat(text(mvc.perform(goGet(GROUP, MODULE + "/@v/list")).andExpect(status().isOk()).andReturn())).isEqualTo("v0.1.0\n");
        mvc.perform(goGet(GROUP, MODULE + "/@latest")).andExpect(jsonPath("$.Version").value("v0.1.0"));
        // the hosted member answered first, so upstream was never asked for the files
        upstream.verify(0, getRequestedFor(urlEqualTo("/" + MODULE + "/@v/v0.1.0.zip")));
        upstream.verify(0, getRequestedFor(urlEqualTo("/" + MODULE + "/@v/v0.1.0.info")));

        // the shorter prefixes the go command probes are clean 404s, so it settles on the real module
        mvc.perform(goGet(GROUP, "example.com/acme/@v/v0.1.0.info")).andExpect(status().isNotFound());
        mvc.perform(goGet(GROUP, "example.com/@v/v0.1.0.info")).andExpect(status().isNotFound());
    }

    @Test
    void publicModulesComeThroughTheProxyMember() throws Exception {
        mvc.perform(goGet(GROUP, "rsc.io/quote/@v/list")).andExpect(status().isOk()).andExpect(content().string("v1.5.1\nv1.5.2\n"));
        mvc.perform(goGet(GROUP, "rsc.io/quote/@v/v1.5.2.info")).andExpect(jsonPath("$.Version").value("v1.5.2"))
                .andExpect(jsonPath("$.Origin.VCS").value("git"));
        mvc.perform(goGet(GROUP, "rsc.io/quote/@v/v1.5.2.mod")).andExpect(content().string("module rsc.io/quote\n"));
        mvc.perform(goGet(GROUP, "rsc.io/quote/@latest")).andExpect(jsonPath("$.Version").value("v1.5.2"))
                .andExpect(jsonPath("$.Time").value("2018-02-14T15:44:20Z"));
        mvc.perform(goGet(GROUP, "rsc.io/quote/@v/v9.9.9.info")).andExpect(status().isNotFound());
    }

    @Test
    void versionListsAreMergedInSemverOrderAndTheFirstMemberWinsForFiles() throws Exception {
        publish(SHARED, "v1.0.0");
        publish(SHARED, "v0.10.0");
        assertThat(text(mvc.perform(goGet(GROUP, SHARED + "/@v/list")).andExpect(status().isOk()).andReturn()))
                .isEqualTo("v0.9.0\nv0.10.0\nv1.0.0\nv1.1.0-rc.1\n");
        // v1.0.0 exists in both members: the hosted copy is served and upstream's is never fetched
        assertThat(bytes(mvc.perform(goGet(GROUP, SHARED + "/@v/v1.0.0.zip")).andExpect(status().isOk()).andReturn()))
                .isEqualTo(moduleZip(SHARED, "v1.0.0"));
        upstream.verify(0, getRequestedFor(urlEqualTo("/" + SHARED + "/@v/v1.0.0.zip")));
        // v0.9.0 only exists upstream
        mvc.perform(goGet(GROUP, SHARED + "/@v/v0.9.0.info")).andExpect(jsonPath("$.Time").value("2019-01-01T00:00:00Z"));
        // @latest is the highest release of all members, described by the member that has it first
        String hostedInfo = text(mvc.perform(goGet(HOSTED, SHARED + "/@v/v1.0.0.info")).andReturn());
        assertThat(text(mvc.perform(goGet(GROUP, SHARED + "/@latest")).andExpect(status().isOk()).andReturn())).isEqualTo(hostedInfo);

        // a higher hosted release wins over the upstream pre-release
        publish(SHARED, "v1.2.0");
        mvc.perform(goGet(GROUP, SHARED + "/@latest")).andExpect(jsonPath("$.Version").value("v1.2.0"));
    }

    @Test
    void latestOfAnUntaggedModuleComesFromUpstream() throws Exception {
        mvc.perform(goGet(GROUP, "golang.org/x/exp/@v/list")).andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(goGet(GROUP, "golang.org/x/exp/@latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Version").value("v0.0.0-20240613232115-7f521ea00fb8"));
    }

    @Test
    void theChecksumDatabaseIsReachableThroughTheGroup() throws Exception {
        mvc.perform(goGet(GROUP, "sumdb/sum.golang.org/supported")).andExpect(status().isOk());
        mvc.perform(goGet(GROUP, "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rsc.io/quote v1.5.2 h1:")));
        mvc.perform(goGet(GROUP, "sumdb/sum.example.com/supported")).andExpect(status().isNotFound());

        // a group of hosted repositories only has no checksum database to offer
        group("go-internal", "go", HOSTED);
        mvc.perform(goGet("go-internal", "sumdb/sum.golang.org/supported")).andExpect(status().isNotFound());
    }

    @Test
    void writesToTheGroupAreRefusedAndNameTheHostedMember() throws Exception {
        mvc.perform(curlPut(GROUP, MODULE + "/@v/v0.1.0.zip", moduleZip(MODULE, "v0.1.0")).header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"))
                .andExpect(jsonPath("$.message").value(containsString("'" + HOSTED + "'")));
        mvc.perform(delete("/repository/" + GROUP + "/" + MODULE + "/@v/v0.1.0").header("Authorization", adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(goGet(HOSTED, MODULE + "/@v/list")).andExpect(status().isNotFound());
    }

    @Test
    void offlineGroupsKeepServingHostedAndCachedModules() throws Exception {
        publish(MODULE, "v0.1.0");
        mvc.perform(goGet(GROUP, "rsc.io/quote/@v/v1.5.2.info")).andExpect(status().isOk());
        mvc.perform(goGet(GROUP, "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2")).andExpect(status().isOk());
        repositories.setOffline(PROXY, true);
        upstream.resetAll();

        mvc.perform(goGet(GROUP, MODULE + "/@v/v0.1.0.zip")).andExpect(status().isOk());
        mvc.perform(goGet(GROUP, MODULE + "/@v/list")).andExpect(content().string("v0.1.0\n"));
        mvc.perform(goGet(GROUP, "rsc.io/quote/@v/v1.5.2.info")).andExpect(status().isOk());
        mvc.perform(goGet(GROUP, "sumdb/sum.golang.org/supported")).andExpect(status().isOk());
        mvc.perform(goGet(GROUP, "sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2")).andExpect(status().isOk());
        mvc.perform(goGet(GROUP, "golang.org/x/text/@v/list"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void readsNeedCredentialsWhenAnonymousAccessIsOff() throws Exception {
        publish(MODULE, "v0.1.0");
        settings.setAnonymousReadEnabled(false);
        mvc.perform(goGet(GROUP, MODULE + "/@v/list")).andExpect(status().isUnauthorized());
        mvc.perform(goGet(GROUP, "sumdb/sum.golang.org/supported")).andExpect(status().isUnauthorized());
        // .netrc or credentials in the GOPROXY URL: HTTP Basic with the password or an API token
        String token = tokens.issue(users.require(ADMIN), "go", null).secret();
        mvc.perform(goGet(GROUP, MODULE + "/@v/list").header("Authorization", basic(ADMIN, token))).andExpect(status().isOk());
        mvc.perform(goGet(GROUP, MODULE + "/@v/v0.1.0.zip").header("Authorization", adminBasic())).andExpect(status().isOk());
    }

    @Test
    void standardTemplatesFormatsAndConnectSnippets() throws Exception {
        // the standard set uses the conventional names, so make room for it
        repositories.delete(GROUP);
        repositories.delete(PROXY);
        repositories.delete(HOSTED);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/repositories/standard")
                        .header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created[?(@ == 'go-proxy')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'go-hosted')]").exists())
                .andExpect(jsonPath("$.created[?(@ == 'go-group')]").exists());
        mvc.perform(get("/api/v1/repositories/go-proxy").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.type").value("PROXY"))
                .andExpect(jsonPath("$.remoteUrl").value("https://proxy.golang.org/"));
        mvc.perform(get("/api/v1/repositories/go-group").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.members[0]").value("go-hosted"))
                .andExpect(jsonPath("$.members[1]").value("go-proxy"));
        mvc.perform(get("/api/v1/formats").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$[?(@.id == 'go')].displayName").value("Go modules"))
                .andExpect(jsonPath("$[?(@.id == 'go')].templates[*]").value(org.hamcrest.Matchers.contains("go-proxy", "go-hosted", "go-group")))
                .andExpect(jsonPath("$[?(@.id == 'go')].settings[*].key").value(org.hamcrest.Matchers.contains("go.sumdbName", "go.sumdbUrl")));

        String snippets = text(mvc.perform(get("/api/v1/repositories/go-group/connect").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.contains("go-env", "go-private", "netrc", "dockerfile")))
                .andReturn());
        assertThat(snippets)
                .contains("go env -w GOPROXY=http://localhost/repository/go-group,direct")
                .contains("GONOSUMDB=")
                .contains("machine localhost login admin password <api-token>")
                .contains("ENV GOPROXY=http://localhost/repository/go-group,direct")
                .contains("the hosted member go-hosted");
        mvc.perform(get("/api/v1/repositories/go-hosted/connect").header("Authorization", adminBearer()))
                .andExpect(jsonPath("$[?(@.id == 'curl-publish')].code").value(org.hamcrest.Matchers.contains(
                        containsString("curl -u admin:<api-token> -T hello-v0.1.0.zip http://localhost/repository/go-hosted/example.com/acme/hello/@v/v0.1.0.zip"))));
    }
}
