package dev.granary.server.nuget;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static dev.granary.server.testsupport.ComponentApi.gav;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import dev.granary.server.testsupport.ComponentApi;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Packages a NuGet proxy caches are components named the way a push names them: the id as the manifest spells it
 * and the normalised version. Service index, version lists and registrations belong to no component.
 */
class NugetProxyComponentsTest extends IntegrationTest {

    private static final String REPO = "nuget-proxy";
    private static final String ID = "acme.remote";
    private static final String NUPKG = ID + "/2.0.0/" + ID + ".2.0.0.nupkg";
    private static final String NUSPEC = ID + "/2.0.0/" + ID + ".nuspec";
    private static final byte[] NUPKG_BYTES = NugetTestPackages.nupkg("Acme.Remote", "2.0.0", "remote package");

    private static WireMockServer upstream;

    @Autowired
    private StorageService storage;

    private Repository repo;
    private ComponentApi components;

    @BeforeAll
    static void startUpstream() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @BeforeEach
    void setUp() {
        upstream.resetAll();
        String base = upstream.baseUrl();
        upstream.stubFor(get(urlEqualTo("/v3/index.json")).willReturn(okJson(NugetTestPackages.serviceIndex(base))));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/" + ID + "/index.json"))
                .willReturn(okJson(NugetTestPackages.flatIndex("1.0.0", "2.0.0"))));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/" + NUPKG))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(NUPKG_BYTES)));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/" + NUSPEC))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                        .withBody(NugetTestPackages.nuspec("Acme.Remote", "2.0.0", "remote package"))));
        upstream.stubFor(get(urlEqualTo("/v3-flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.snupkg"))
                .willReturn(aResponse().withStatus(200).withBody(NugetTestPackages.snupkg("Acme.Remote", "2.0.0"))));
        repo = proxy(REPO, "nuget", base + "/v3/index.json");
        components = new ComponentApi(mvc, adminBearer());
    }

    private ResultActions fetch(String repository, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/repository/" + repository + "/" + path));
    }

    @Test
    void restoredPackagesAreComponentsAndIndexDocumentsAreNot() throws Exception {
        fetch(REPO, "v3/flatcontainer/" + ID + "/index.json").andExpect(status().isOk());
        fetch(REPO, "v3/flatcontainer/" + NUPKG).andExpect(status().isOk());
        fetch(REPO, "v3/flatcontainer/" + NUSPEC).andExpect(status().isOk());
        fetch(REPO, "v3/flatcontainer/" + ID + "/2.0.0/" + ID + ".2.0.0.snupkg").andExpect(status().isOk());

        var component = components.single(REPO);
        assertThat(gav(component)).isEqualTo(":Acme.Remote:2.0.0");
        assertThat(component.path("format").asString()).isEqualTo("nuget");
        assertThat(components.assetPaths(REPO, component))
                .containsExactly(NUPKG, ID + "/2.0.0/" + ID + ".2.0.0.snupkg", NUSPEC);
        assertThat(storage.find(repo, "index.json").orElseThrow().asset().getComponentId()).isNull();
        assertThat(storage.find(repo, ID + "/index.json").orElseThrow().asset().getComponentId()).isNull();
    }

    @Test
    void aManifestRestoredOnItsOwnNamesTheSameComponent() throws Exception {
        fetch(REPO, "v3/flatcontainer/" + NUSPEC).andExpect(status().isOk());
        assertThat(gav(components.single(REPO))).isEqualTo(":Acme.Remote:2.0.0");

        fetch(REPO, "v3/flatcontainer/" + NUPKG).andExpect(status().isOk());
        assertThat(components.assetPaths(REPO, components.single(REPO))).containsExactly(NUPKG, NUSPEC);
    }

    @Test
    void repeatedAndConcurrentRestoresKeepOneComponent() throws Exception {
        List<Integer> statuses = ComponentApi.concurrently(8, i -> () ->
                fetch(REPO, "v3/flatcontainer/" + (i % 2 == 0 ? NUPKG : NUSPEC)).andReturn().getResponse().getStatus());
        fetch(REPO, "v3/flatcontainer/" + NUPKG).andExpect(status().isOk());

        assertThat(statuses).containsOnly(200);
        assertThat(gav(components.single(REPO))).isEqualTo(":Acme.Remote:2.0.0");
        assertThat(components.assetPaths(REPO, components.single(REPO))).containsExactly(NUPKG, NUSPEC);
        upstream.verify(1, getRequestedFor(urlEqualTo("/v3-flatcontainer/" + NUPKG)));
    }

    @Test
    void searchFindsTheCachedPackageWhateverTheCase() throws Exception {
        fetch(REPO, "v3/flatcontainer/" + NUPKG).andExpect(status().isOk());

        assertThat(components.search("acme.remote")).extracting(ComponentApi::gav).containsExactly(":Acme.Remote:2.0.0");
        assertThat(components.search("Remote")).hasSize(1);
        assertThat(components.list(REPO, "other")).isEmpty();
    }

    @Test
    void deletingTheComponentRemovesThePackageWhichComesBackOnlineButNotOffline() throws Exception {
        fetch(REPO, "v3/flatcontainer/" + NUPKG).andExpect(status().isOk());
        fetch(REPO, "v3/flatcontainer/" + NUSPEC).andExpect(status().isOk());

        assertThat(components.delete(REPO, components.single(REPO))).isEqualTo(2);
        assertThat(storage.find(repo, NUPKG)).isEmpty();
        assertThat(storage.find(repo, NUSPEC)).isEmpty();

        repositories.setOffline(REPO, true);
        fetch(REPO, "v3/flatcontainer/" + NUPKG).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        assertThat(components.list(REPO)).isEmpty();

        repositories.setOffline(REPO, false);
        fetch(REPO, "v3/flatcontainer/" + NUPKG).andExpect(status().isOk());
        assertThat(gav(components.single(REPO))).isEqualTo(":Acme.Remote:2.0.0");
        upstream.verify(2, getRequestedFor(urlEqualTo("/v3-flatcontainer/" + NUPKG)));
    }

    @Test
    void packagesCachedBeforeComponentsWereRecordedAreLinkedWhenServed() throws Exception {
        // what a proxy of an earlier version holds: the upstream service index and the package, no component
        fetch(REPO, "v3/flatcontainer/" + ID + "/index.json").andExpect(status().isOk());
        storage.store(repo, NUPKG, new ByteArrayInputStream(NUPKG_BYTES), "application/octet-stream", Asset.Origin.CACHED, false);
        repositories.setOffline(REPO, true);
        upstream.resetRequests();

        fetch(REPO, "v3/flatcontainer/" + NUPKG).andExpect(status().isOk());

        assertThat(gav(components.single(REPO))).isEqualTo(":Acme.Remote:2.0.0");
        upstream.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void restoresThroughAGroupAreRecordedInTheProxyMemberWithTheHostedSpelling() throws Exception {
        hosted("nuget-hosted", "nuget");
        group("nuget-all", "nuget", "nuget-hosted", REPO);
        mvc.perform(multipart(HttpMethod.PUT, "/repository/nuget-hosted/v2/package/")
                        .file(new MockMultipartFile("package", "package.nupkg", "application/octet-stream",
                                NugetTestPackages.nupkg("Acme.Own", "1.0.0", "own package")))
                        .header("Authorization", adminBasic()))
                .andExpect(status().isCreated());

        fetch("nuget-all", "v3/flatcontainer/" + NUPKG).andExpect(status().isOk());
        fetch("nuget-all", "v3/flatcontainer/acme.own/1.0.0/acme.own.1.0.0.nupkg").andExpect(status().isOk());

        assertThat(gav(components.single(REPO))).isEqualTo(":Acme.Remote:2.0.0");
        assertThat(gav(components.single("nuget-hosted"))).isEqualTo(":Acme.Own:1.0.0");
        assertThat(components.list("nuget-all")).isEmpty();
    }
}
