package dev.granary.server.nuget;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.server.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A proxy in front of a WireMock fake of nuget.org: absolute resource URLs in the upstream service index,
 * a registration index whose page is not inlined, and an upstream that then breaks.
 */
class NugetProxyTest extends IntegrationTest {

    private static final String REPO = "nuget-proxy";
    private static final String BASE = "http://localhost/repository/" + REPO;
    private static final String ID = "acme.remote";
    private static final String NUPKG_PATH = "/v3/flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.nupkg";
    private static final byte[] NUPKG = NugetTestPackages.nupkg("Acme.Remote", "2.0.0", "remote package");

    private static WireMockServer upstream;
    private static String upstreamBase;

    @BeforeAll
    static void startUpstream() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        upstreamBase = upstream.baseUrl();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void setUp() {
        upstream.resetAll();
        stubHealthyUpstream();
        Repository r = new Repository();
        r.setName(REPO);
        r.setFormat("nuget");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(upstreamBase + "/v3/index.json");
        // re-validate index documents on every request so the tests exercise the upstream-failure path
        r.setMetadataMaxAgeMinutes(0);
        repositories.create(r);
    }

    private static void stubHealthyUpstream() {
        upstream.stubFor(get(urlEqualTo("/v3/index.json")).willReturn(okJson(NugetTestPackages.serviceIndex(upstreamBase))));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/" + ID + "/index.json"))
                .willReturn(okJson(NugetTestPackages.flatIndex("2.0.0", "1.0.0", "2.0.0-beta.1"))));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.nupkg"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(NUPKG)));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/" + ID + "/2.0.0/" + ID + ".nuspec"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                        .withBody(NugetTestPackages.nuspec("Acme.Remote", "2.0.0", "remote package"))));
        upstream.stubFor(get(urlEqualTo("/v3/registration5-gz-semver2/" + ID + "/index.json"))
                .willReturn(okJson(NugetTestPackages.registrationIndexWithRemotePage(upstreamBase, ID, "1.0.0", "2.0.0"))));
        // nuget.org's CDN hands registration pages out gzip-encoded whether or not the client asked
        upstream.stubFor(get(urlEqualTo("/v3/registration5-gz-semver2/" + ID + "/page/1.0.0/2.0.0.json"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(NugetTestPackages.gzip(NugetTestPackages.registrationPage(upstreamBase, ID, "1.0.0", "2.0.0-beta.1", "2.0.0")))));
        upstream.stubFor(get(urlPathEqualTo("/query"))
                .willReturn(okJson(NugetTestPackages.searchResult(upstreamBase, "Acme.Remote", "2.0.0"))));
    }

    private static void breakUpstream() {
        upstream.resetAll();
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500).withBody("upstream is on fire")));
    }

    /** GET of a repository-relative path; named to stay clear of WireMock's static {@code get}. */
    private ResultActions fetch(String path, String... params) throws Exception {
        var request = MockMvcRequestBuilders.get("/repository/" + REPO + "/" + path);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    @Test
    void serviceIndexNamesTheProxyNotTheUpstream() throws Exception {
        fetch("index.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[?(@['@type']=='PackageBaseAddress/3.0.0')]['@id']").value(BASE + "/v3/flatcontainer/"))
                .andExpect(jsonPath("$.resources[?(@['@type']=='RegistrationsBaseUrl/3.6.0')]['@id']").value(BASE + "/v3/registration/"))
                .andExpect(content().string(not(containsString(upstreamBase))));
        upstream.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void flatContainerIsResolvedThroughTheUpstreamServiceIndexAndCached() throws Exception {
        fetch("v3/flatcontainer/Acme.Remote/index.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions").value(org.hamcrest.Matchers.contains("1.0.0", "2.0.0-beta.1", "2.0.0")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/v3/index.json")));

        for (int i = 0; i < 2; i++) {
            MvcResult result = fetch(NUPKG_PATH.substring(1)).andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(NUPKG);
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/v3-flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.nupkg")));

        fetch("v3/flatcontainer/" + ID + "/2.0.0/" + ID + ".nuspec")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<id>Acme.Remote</id>")));
    }

    @Test
    void registrationIsInlinedAndRewrittenToTheProxy() throws Exception {
        fetch("v3/registration/" + ID + "/index.json")
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(upstreamBase))))
                .andExpect(jsonPath("$['@id']").value(BASE + "/v3/registration/" + ID + "/index.json"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].lower").value("1.0.0"))
                .andExpect(jsonPath("$.items[0].upper").value("2.0.0"))
                .andExpect(jsonPath("$.items[0].parent").value(BASE + "/v3/registration/" + ID + "/index.json"))
                .andExpect(jsonPath("$.items[0].items[2]['@id']").value(BASE + "/v3/registration/" + ID + "/2.0.0.json"))
                .andExpect(jsonPath("$.items[0].items[2].registration").value(BASE + "/v3/registration/" + ID + "/index.json"))
                .andExpect(jsonPath("$.items[0].items[2].packageContent").value(BASE + NUPKG_PATH))
                .andExpect(jsonPath("$.items[0].items[2].catalogEntry.packageContent").value(BASE + NUPKG_PATH))
                .andExpect(jsonPath("$.items[0].items[2].catalogEntry.description").value("upstream acme.remote 2.0.0"))
                .andExpect(jsonPath("$.items[0].items[2].catalogEntry.dependencyGroups[0].dependencies[0].registration")
                        .value(BASE + "/v3/registration/newtonsoft.json/index.json"));
        upstream.verify(1, getRequestedFor(urlEqualTo("/v3/registration5-gz-semver2/" + ID + "/page/1.0.0/2.0.0.json")));

        fetch("v3/registration/" + ID + "/2.0.0-beta.1.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['@id']").value(BASE + "/v3/registration/" + ID + "/2.0.0-beta.1.json"))
                .andExpect(jsonPath("$.packageContent").value(BASE + "/v3/flatcontainer/" + ID + "/2.0.0-beta.1/" + ID + ".2.0.0-beta.1.nupkg"));
    }

    @Test
    void searchIsForwardedUpstreamAndRewritten() throws Exception {
        fetch("v3/query", "q", "acme", "prerelease", "true", "take", "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(1))
                .andExpect(jsonPath("$.data[0].id").value("Acme.Remote"))
                .andExpect(jsonPath("$.data[0].registration").value(BASE + "/v3/registration/" + ID + "/index.json"))
                .andExpect(jsonPath("$.data[0].versions[0]['@id']").value(BASE + "/v3/registration/" + ID + "/2.0.0.json"))
                .andExpect(content().string(not(containsString(upstreamBase))));
        upstream.verify(1, getRequestedFor(urlEqualTo("/query?q=acme&skip=0&take=5&prerelease=true&semVerLevel=2.0.0")));
    }

    @Test
    void everythingCachedIsStillServedWhenUpstreamBreaks() throws Exception {
        fetch("v3/flatcontainer/" + ID + "/index.json").andExpect(status().isOk());
        fetch(NUPKG_PATH.substring(1)).andExpect(status().isOk());
        fetch("v3/flatcontainer/" + ID + "/2.0.0/" + ID + ".nuspec").andExpect(status().isOk());
        fetch("v3/registration/" + ID + "/index.json").andExpect(status().isOk());

        breakUpstream();

        fetch("v3/flatcontainer/" + ID + "/index.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(3)));
        MvcResult download = fetch(NUPKG_PATH.substring(1)).andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(NUPKG);
        fetch("v3/flatcontainer/" + ID + "/2.0.0/" + ID + ".nuspec").andExpect(status().isOk());
        fetch("v3/registration/" + ID + "/index.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].items", hasSize(3)))
                .andExpect(content().string(not(containsString(upstreamBase))));
        fetch("v3/registration/" + ID + "/1.0.0.json").andExpect(status().isOk());
        // search has nothing to fall back on, so it answers "no hits" instead of failing the client
        fetch("v3/query", "q", "acme")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(0));
        // and what was never cached is reported as a temporary upstream failure, not as missing
        fetch("v3/flatcontainer/never.seen/index.json").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        upstream.verify(getRequestedFor(urlEqualTo("/v3-flatcontainer/" + ID + "/index.json")));
    }

    @Test
    void offlineModeNeverTouchesUpstream() throws Exception {
        fetch("v3/flatcontainer/" + ID + "/index.json").andExpect(status().isOk());
        fetch("v3/registration/" + ID + "/index.json").andExpect(status().isOk());
        upstream.resetAll();
        repositories.setOffline(REPO, true);

        fetch("v3/flatcontainer/" + ID + "/index.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(3)));
        fetch("v3/registration/" + ID + "/index.json").andExpect(status().isOk());
        fetch("v3/query", "q", "acme")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(0));
        upstream.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void unknownUpstreamPackagesAre404() throws Exception {
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/nope/index.json")).willReturn(aResponse().withStatus(404)));
        upstream.stubFor(get(urlEqualTo("/v3/registration5-gz-semver2/nope/index.json")).willReturn(aResponse().withStatus(404)));
        fetch("v3/flatcontainer/nope/index.json")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("content.notFound"));
        fetch("v3/registration/nope/index.json").andExpect(status().isNotFound());
        fetch("v3/registration/nope/1.0.0.json").andExpect(status().isNotFound());
    }

    @Test
    void anUnreachableUpstreamWithNothingCachedIs503AndTheServiceIndexStillWorks() throws Exception {
        Repository r = new Repository();
        r.setName("nuget-dead");
        r.setFormat("nuget");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl("http://127.0.0.1:1/v3/index.json");
        repositories.create(r);
        mvc.perform(MockMvcRequestBuilders.get("/repository/nuget-dead/v3/flatcontainer/" + ID + "/index.json"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
        mvc.perform(MockMvcRequestBuilders.get("/repository/nuget-dead/index.json"))
                .andExpect(status().isOk());
    }

    @Test
    void proxiesAreReadOnly() throws Exception {
        String apiKey = tokens.issue(users.require("admin"), "nuget-proxy-test", null).secret();
        mvc.perform(multipart(HttpMethod.PUT, "/repository/" + REPO + "/v2/package")
                        .file(new MockMultipartFile("package", "x.nupkg", null, NUPKG))
                        .header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"));
        mvc.perform(delete("/repository/" + REPO + "/v2/package/Acme.Remote/2.0.0").header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isMethodNotAllowed());
    }
}
