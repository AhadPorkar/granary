package dev.granary.server.nuget;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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

/** A group of a hosted and a proxy member: one {@code index.json} for everything a build needs. */
class NugetGroupTest extends IntegrationTest {

    private static final String GROUP = "nuget-all";
    private static final String BASE = "http://localhost/repository/" + GROUP;
    private static final String ID = "acme.lib";
    private static final byte[] HOSTED_NUPKG = NugetTestPackages.nupkg("Acme.Lib", "1.0.0", "hosted copy");
    private static final byte[] UPSTREAM_NUPKG = NugetTestPackages.nupkg("Acme.Lib", "2.0.0", "upstream copy");

    private static WireMockServer upstream;
    private static String upstreamBase;

    private String apiKey;

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
    void setUp() throws Exception {
        upstream.resetAll();
        upstream.stubFor(get(urlEqualTo("/v3/index.json")).willReturn(okJson(NugetTestPackages.serviceIndex(upstreamBase))));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/" + ID + "/index.json"))
                .willReturn(okJson(NugetTestPackages.flatIndex("2.0.0", "1.0.0"))));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.nupkg"))
                .willReturn(aResponse().withStatus(200).withBody(UPSTREAM_NUPKG)));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/" + ID + "/1.0.0/" + ID + ".1.0.0.nupkg"))
                .willReturn(aResponse().withStatus(200).withBody("upstream 1.0.0 must never be served".getBytes())));
        upstream.stubFor(get(urlEqualTo("/v3/registration5-gz-semver2/" + ID + "/index.json"))
                .willReturn(okJson(NugetTestPackages.registrationIndexInlined(upstreamBase, ID, "1.0.0", "2.0.0"))));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/upstream.only/index.json"))
                .willReturn(okJson(NugetTestPackages.flatIndex("0.5.0"))));
        upstream.stubFor(get(urlPathEqualTo("/query"))
                .willReturn(okJson(NugetTestPackages.searchResult(upstreamBase, "Upstream.Only", "0.5.0"))));

        hosted("nuget-hosted", "nuget");
        proxy("nuget-proxy", "nuget", upstreamBase + "/v3/index.json");
        group(GROUP, "nuget", "nuget-hosted", "nuget-proxy");

        apiKey = tokens.issue(users.require("admin"), "nuget-group-test", null).secret();
        mvc.perform(multipart(HttpMethod.PUT, "/repository/nuget-hosted/v2/package")
                        .file(new MockMultipartFile("package", "x.nupkg", null, HOSTED_NUPKG))
                        .header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isCreated());
    }

    private ResultActions fetch(String path, String... params) throws Exception {
        var request = MockMvcRequestBuilders.get("/repository/" + GROUP + "/" + path);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    @Test
    void serviceIndexPointsAtTheGroup() throws Exception {
        fetch("index.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resources[?(@['@type']=='PackageBaseAddress/3.0.0')]['@id']").value(BASE + "/v3/flatcontainer/"))
                .andExpect(jsonPath("$.resources[?(@['@type']=='PackagePublish/2.0.0')]['@id']").value(BASE + "/v2/package"));
    }

    @Test
    void flatIndexIsTheUnionOfTheMembersInVersionOrder() throws Exception {
        fetch("v3/flatcontainer/Acme.Lib/index.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions").value(org.hamcrest.Matchers.contains("1.0.0", "2.0.0")));
        fetch("v3/flatcontainer/upstream.only/index.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions").value(org.hamcrest.Matchers.contains("0.5.0")));
        fetch("v3/flatcontainer/nowhere/index.json").andExpect(status().isNotFound());
    }

    @Test
    void filesComeFromTheFirstMemberThatHasThem() throws Exception {
        MvcResult hosted = fetch("v3/flatcontainer/" + ID + "/1.0.0/" + ID + ".1.0.0.nupkg").andExpect(status().isOk()).andReturn();
        assertThat(hosted.getResponse().getContentAsByteArray()).isEqualTo(HOSTED_NUPKG);
        MvcResult proxied = fetch("v3/flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.nupkg").andExpect(status().isOk()).andReturn();
        assertThat(proxied.getResponse().getContentAsByteArray()).isEqualTo(UPSTREAM_NUPKG);
        fetch("v3/flatcontainer/" + ID + "/1.0.0/" + ID + ".nuspec")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hosted copy")));
        fetch("v3/flatcontainer/" + ID + "/3.0.0/" + ID + ".3.0.0.nupkg").andExpect(status().isNotFound());
    }

    @Test
    void registrationMergesTheMembersUnderTheGroupUrl() throws Exception {
        fetch("v3/registration/" + ID + "/index.json")
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(upstreamBase))))
                .andExpect(content().string(not(containsString("/repository/nuget-hosted/"))))
                .andExpect(content().string(not(containsString("/repository/nuget-proxy/"))))
                .andExpect(jsonPath("$['@id']").value(BASE + "/v3/registration/" + ID + "/index.json"))
                .andExpect(jsonPath("$.items[0].items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].lower").value("1.0.0"))
                .andExpect(jsonPath("$.items[0].upper").value("2.0.0"))
                // 1.0.0 exists in both members; the hosted member comes first in the group, so its manifest wins
                .andExpect(jsonPath("$.items[0].items[0].catalogEntry.description").value("hosted copy"))
                .andExpect(jsonPath("$.items[0].items[0].packageContent").value(BASE + "/v3/flatcontainer/" + ID + "/1.0.0/" + ID + ".1.0.0.nupkg"))
                .andExpect(jsonPath("$.items[0].items[1].catalogEntry.description").value("upstream acme.lib 2.0.0"))
                .andExpect(jsonPath("$.items[0].items[1]['@id']").value(BASE + "/v3/registration/" + ID + "/2.0.0.json"));

        fetch("v3/registration/" + ID + "/2.0.0.json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration").value(BASE + "/v3/registration/" + ID + "/index.json"));
    }

    @Test
    void searchUnionsTheMembers() throws Exception {
        fetch("v3/query", "q", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(2))
                .andExpect(jsonPath("$.data[0].id").value("Acme.Lib"))
                .andExpect(jsonPath("$.data[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[0].registration").value(BASE + "/v3/registration/" + ID + "/index.json"))
                .andExpect(jsonPath("$.data[1].id").value("Upstream.Only"))
                .andExpect(jsonPath("$.data[1].registration").value(BASE + "/v3/registration/upstream.only/index.json"));
        fetch("v3/query", "q", "", "skip", "1", "take", "1")
                .andExpect(jsonPath("$.totalHits").value(2))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value("Upstream.Only"));
    }

    @Test
    void groupsAreReadOnly() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/repository/" + GROUP + "/v2/package")
                        .file(new MockMultipartFile("package", "x.nupkg", null, HOSTED_NUPKG))
                        .header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("repository.readOnly"));
        mvc.perform(delete("/repository/" + GROUP + "/v2/package/Acme.Lib/1.0.0").header("X-NuGet-ApiKey", apiKey))
                .andExpect(status().isMethodNotAllowed());
    }
}
