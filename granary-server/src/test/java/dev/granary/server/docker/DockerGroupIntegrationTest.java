package dev.granary.server.docker;

import static dev.granary.server.docker.DockerFixtures.ALL_MANIFESTS;
import static dev.granary.server.docker.DockerFixtures.OCI_MANIFEST;
import static dev.granary.server.docker.DockerFixtures.config;
import static dev.granary.server.docker.DockerFixtures.digest;
import static dev.granary.server.docker.DockerFixtures.layer;
import static dev.granary.server.docker.DockerFixtures.manifest;
import static dev.granary.server.docker.DockerFixtures.pushImage;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.format.docker.DockerUpstream;
import dev.granary.server.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/** A group of a hosted repository and a proxy: reads fall through the members, listings are unioned, writes are refused. */
class DockerGroupIntegrationTest extends IntegrationTest {

    private static final String HOSTED = "docker-hosted";
    private static final String PROXY = "docker-hub";
    private static final String GROUP = "docker-group";
    private static final String URL = "/repository/" + GROUP;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private DockerUpstream dockerUpstream;

    private final byte[] alpineConfig = config("alpine");
    private final byte[] alpineLayer = layer("alpine");
    private final byte[] alpineManifest = manifest(OCI_MANIFEST, alpineConfig, alpineLayer);

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
        hosted(HOSTED, "docker");
        Repository proxy = new Repository();
        proxy.setName(PROXY);
        proxy.setFormat("docker");
        proxy.setType(dev.granary.core.model.RepositoryType.PROXY);
        proxy.setRemoteUrl(upstream.baseUrl());
        proxy.setFormatSettings(Map.of(DockerUpstream.LIBRARY_PREFIX_SETTING, "true"));
        proxy = repositories.create(proxy);
        proxyService.invalidate(proxy);
        dockerUpstream.forget(proxy);
        group(GROUP, "docker", HOSTED, PROXY);
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/")).willReturn(WireMock.okJson("{}")));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", OCI_MANIFEST).withBody(alpineManifest)));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/blobs/" + digest(alpineLayer)))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream").withBody(alpineLayer)));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/tags/list"))
                .willReturn(WireMock.okJson("{\"name\":\"library/alpine\",\"tags\":[\"3.19\",\"3.20\"]}")));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/acme/app/tags/list"))
                .willReturn(WireMock.okJson("{\"name\":\"acme/app\",\"tags\":[\"2.0\"]}")));
        upstream.stubFor(WireMock.get(WireMock.urlPathMatching("/v2/.+/manifests/.+")).atPriority(10)
                .willReturn(WireMock.aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"errors\":[{\"code\":\"MANIFEST_UNKNOWN\",\"message\":\"unknown\",\"detail\":null}]}")));
        upstream.stubFor(WireMock.get(WireMock.urlPathMatching("/v2/.+/blobs/.+")).atPriority(10)
                .willReturn(WireMock.aResponse().withStatus(404)));
    }

    @Test
    void readsFallThroughTheMembers() throws Exception {
        DockerFixtures.Image app = pushImage(mvc, "/repository/" + HOSTED, "acme/app", "1.0", adminBasic());

        mvc.perform(get(URL + "/v2/acme/app/manifests/1.0").accept(ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", app.digest()));
        mvc.perform(get(URL + "/v2/acme/app/blobs/" + digest(app.layer()))).andExpect(status().isOk());
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathMatching("/v2/acme/app/.*")));

        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", digest(alpineManifest)));
        mvc.perform(get(URL + "/v2/alpine/blobs/" + digest(alpineLayer))).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20")));

        // a blob already in the hosted member is never fetched upstream, whichever image name asks for it
        mvc.perform(get(URL + "/v2/alpine/blobs/" + digest(app.layer()))).andExpect(status().isOk());
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/blobs/" + digest(app.layer()))));

        mvc.perform(get(URL + "/v2/acme/nothing/manifests/1").accept(ALL_MANIFESTS))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("MANIFEST_UNKNOWN"));
    }

    @Test
    void listingsAreUnioned() throws Exception {
        pushImage(mvc, "/repository/" + HOSTED, "acme/app", "1.0", adminBasic());
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS)).andExpect(status().isOk());

        mvc.perform(get(URL + "/v2/acme/app/tags/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("acme/app"))
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.contains("1.0", "2.0")));
        mvc.perform(get(URL + "/v2/alpine/tags/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.contains("3.19", "3.20")));
        mvc.perform(get(URL + "/v2/_catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").value(org.hamcrest.Matchers.contains("acme/app", "library/alpine")));
        mvc.perform(get(URL + "/v2/nobody/tags/list")).andExpect(status().isNotFound());
    }

    @Test
    void writesNameTheHostedMember() throws Exception {
        mvc.perform(post(URL + "/v2/acme/app/blobs/uploads/").header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errors[0].code").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString(HOSTED)));
        mvc.perform(put(URL + "/v2/acme/app/manifests/1.0").header(HttpHeaders.AUTHORIZATION, adminBasic())
                        .contentType(OCI_MANIFEST).content(alpineManifest))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(URL + "/v2/acme/app/manifests/1.0").header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get(URL + "/v2/").header(HttpHeaders.AUTHORIZATION, adminBasic())).andExpect(status().isOk());
    }

    @Test
    void rootRequestsAreForwardedToTheRightRepository() throws Exception {
        // the group is the implicit root; a repository name as first segment wins
        mvc.perform(get("/v2/")).andExpect(forwardedUrl("/repository/" + GROUP + "/v2/"));
        mvc.perform(get("/v2/_catalog")).andExpect(forwardedUrl("/repository/" + GROUP + "/v2/_catalog"));
        mvc.perform(get("/v2/library/alpine/manifests/3.20")).andExpect(forwardedUrl("/repository/" + GROUP + "/v2/library/alpine/manifests/3.20"));
        mvc.perform(get("/v2/docker-hosted/acme/app/manifests/1.0")).andExpect(forwardedUrl("/repository/" + HOSTED + "/v2/acme/app/manifests/1.0"));
        mvc.perform(post("/v2/docker-hosted/acme/app/blobs/uploads/")).andExpect(forwardedUrl("/repository/" + HOSTED + "/v2/acme/app/blobs/uploads/"));
        mvc.perform(get("/v2/docker-hub/alpine/blobs/sha256:" + "a".repeat(64)))
                .andExpect(forwardedUrl("/repository/" + PROXY + "/v2/alpine/blobs/sha256:" + "a".repeat(64)));

        // flagging a repository makes it the root instead of the group
        Repository hostedRepo = repositories.find(HOSTED).orElseThrow();
        hostedRepo.setFormatSettings(Map.of("docker.rootRepository", "true"));
        repositories.update(HOSTED, hostedRepo);
        mvc.perform(get("/v2/")).andExpect(forwardedUrl("/repository/" + HOSTED + "/v2/"));
        mvc.perform(get("/v2/acme/app/manifests/1.0")).andExpect(forwardedUrl("/repository/" + HOSTED + "/v2/acme/app/manifests/1.0"));
    }
}
