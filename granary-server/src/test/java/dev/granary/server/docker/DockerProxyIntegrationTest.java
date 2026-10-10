package dev.granary.server.docker;

import static dev.granary.server.docker.DockerFixtures.ALL_MANIFESTS;
import static dev.granary.server.docker.DockerFixtures.DOCKER_MANIFEST;
import static dev.granary.server.docker.DockerFixtures.OCI_INDEX;
import static dev.granary.server.docker.DockerFixtures.OCI_MANIFEST;
import static dev.granary.server.docker.DockerFixtures.config;
import static dev.granary.server.docker.DockerFixtures.digest;
import static dev.granary.server.docker.DockerFixtures.index;
import static dev.granary.server.docker.DockerFixtures.layer;
import static dev.granary.server.docker.DockerFixtures.manifest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.model.RepositoryType;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.StorageService;
import dev.granary.format.docker.DockerUpstream;
import dev.granary.server.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * A proxy in front of a fake Docker Hub: the bearer token dance, the {@code library/} prefix, caching of
 * manifests (by tag and by digest) and blobs, Accept negotiation, tag listings and the offline-first fallbacks.
 */
class DockerProxyIntegrationTest extends IntegrationTest {

    private static final String REPO = "docker-hub";
    private static final String URL = "/repository/" + REPO;
    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired
    private StorageService storage;

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private DockerUpstream dockerUpstream;

    private Repository repo;

    private final byte[] amdConfig = config("alpine-amd64");
    private final byte[] amdLayer = layer("alpine-amd64");
    private final byte[] amdManifest = manifest(OCI_MANIFEST, amdConfig, amdLayer);
    private final byte[] armManifest = manifest(OCI_MANIFEST, config("alpine-arm64"), layer("alpine-arm64"));
    private final byte[] alpineIndex = index(OCI_INDEX, Map.of("amd64", amdManifest, "arm64", armManifest));

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
        repo = hubLike(REPO, upstream.baseUrl());
        proxyService.invalidate(repo);
        dockerUpstream.forget(repo);
        stubTokenAuth();
        stubAlpine();
    }

    /** A proxy configured like the docker-hub template: single-segment names are requested as {@code library/<name>}. */
    private Repository hubLike(String name, String remoteUrl) {
        Repository r = new Repository();
        r.setName(name);
        r.setFormat("docker");
        r.setType(RepositoryType.PROXY);
        r.setRemoteUrl(remoteUrl);
        r.setFormatSettings(Map.of(DockerUpstream.LIBRARY_PREFIX_SETTING, "true"));
        return repositories.create(r);
    }

    private void stubTokenAuth() {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/"))
                .willReturn(WireMock.aResponse().withStatus(401)
                        .withHeader("WWW-Authenticate", "Bearer realm=\"" + upstream.baseUrl() + "/token\",service=\"test-registry\"")
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"errors\":[{\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\",\"detail\":null}]}")));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/token"))
                .withQueryParam("service", WireMock.equalTo("test-registry"))
                .withQueryParam("scope", WireMock.matching("repository:.+:pull"))
                .willReturn(WireMock.okJson("{\"token\":\"tok-123\",\"expires_in\":300}")));
        // anything under /v2/ without the token is refused
        upstream.stubFor(WireMock.any(WireMock.urlPathMatching("/v2/.+"))
                .atPriority(10)
                .willReturn(WireMock.aResponse().withStatus(401)
                        .withHeader("WWW-Authenticate", "Bearer realm=\"" + upstream.baseUrl() + "/token\",service=\"test-registry\"")));
    }

    private void stubAlpine() {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-123"))
                .withHeader("Accept", WireMock.containing(OCI_INDEX))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", OCI_INDEX)
                        .withHeader("Docker-Content-Digest", digest(alpineIndex))
                        .withHeader("ETag", "\"" + digest(alpineIndex) + "\"")
                        .withBody(alpineIndex)));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-123"))
                .withHeader("Accept", WireMock.notMatching(".*index.*"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", OCI_MANIFEST)
                        .withHeader("Docker-Content-Digest", digest(amdManifest))
                        .withBody(amdManifest)));
        for (byte[] m : new byte[][] {amdManifest, armManifest}) {
            upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/" + digest(m)))
                    .withHeader("Authorization", WireMock.equalTo("Bearer tok-123"))
                    .willReturn(WireMock.aResponse().withStatus(200)
                            .withHeader("Content-Type", OCI_MANIFEST)
                            .withHeader("Docker-Content-Digest", digest(m))
                            .withBody(m)));
        }
        for (byte[] blob : new byte[][] {amdConfig, amdLayer}) {
            upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/blobs/" + digest(blob)))
                    .withHeader("Authorization", WireMock.equalTo("Bearer tok-123"))
                    .willReturn(WireMock.aResponse().withStatus(307)
                            .withHeader("Location", upstream.baseUrl() + "/cdn/" + digest(blob))));
            upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/cdn/" + digest(blob)))
                    .willReturn(WireMock.aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/octet-stream")
                            .withBody(blob)));
        }
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/tags/list"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-123"))
                .willReturn(WireMock.okJson("{\"name\":\"library/alpine\",\"tags\":[\"3.19\",\"3.20\",\"latest\"]}")));
    }

    private void pullAlpine() throws Exception {
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS)).andExpect(status().isOk());
        mvc.perform(get(URL + "/v2/alpine/manifests/" + digest(amdManifest)).accept(ALL_MANIFESTS)).andExpect(status().isOk());
        mvc.perform(get(URL + "/v2/alpine/blobs/" + digest(amdConfig))).andExpect(status().isOk());
        mvc.perform(get(URL + "/v2/alpine/blobs/" + digest(amdLayer))).andExpect(status().isOk());
    }

    @Test
    void pullsAnOfficialImageThroughTokenAuthAndCachesEverything() throws Exception {
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, OCI_INDEX))
                .andExpect(header().string("Docker-Content-Digest", digest(alpineIndex)))
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + digest(alpineIndex) + "\""))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, alpineIndex.length))
                .andExpect(header().string("Docker-Distribution-API-Version", "registry/2.0"))
                .andExpect(jsonPath("$.manifests.length()").value(2));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/token"))
                .withQueryParam("scope", WireMock.equalTo("repository:library/alpine:pull")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-123")));

        // platform manifest by digest, blobs via the CDN redirect
        byte[] pulled = mvc.perform(get(URL + "/v2/alpine/manifests/" + digest(amdManifest)).accept(ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, OCI_MANIFEST))
                .andExpect(header().string("Docker-Content-Digest", digest(amdManifest)))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pulled).isEqualTo(amdManifest);
        byte[] blob = mvc.perform(get(URL + "/v2/alpine/blobs/" + digest(amdLayer)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"))
                .andExpect(header().string("Docker-Content-Digest", digest(amdLayer)))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, amdLayer.length))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(blob).isEqualTo(amdLayer);
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/cdn/" + digest(amdLayer))));
        mvc.perform(get(URL + "/v2/alpine/blobs/" + digest(amdConfig))).andExpect(status().isOk());

        // everything is cached: a second pull makes no upstream request at all
        upstream.resetRequests();
        pullAlpine();
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        assertThat(storage.find(repo, "tags/library/alpine/3.20")).hasValueSatisfying(c -> {
            assertThat(c.asset().isMetadata()).isTrue();
            assertThat(c.asset().getContentType()).isEqualTo(OCI_INDEX);
        });
        assertThat(storage.find(repo, "manifests/library/alpine/sha256/" + digest(alpineIndex).substring(7)))
                .hasValueSatisfying(c -> assertThat(c.asset().isMetadata()).isFalse());
        assertThat(storage.find(repo, "manifests/library/alpine/sha256/" + digest(amdManifest).substring(7))).isPresent();
        assertThat(storage.find(repo, "blobs/sha256/" + digest(amdLayer).substring(7))).isPresent();
        assertThat(storage.find(repo, "blobs/sha256/" + digest(amdConfig).substring(7))).isPresent();
        mvc.perform(get(URL + "/v2/_catalog")).andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories").value(org.hamcrest.Matchers.contains("library/alpine")));
    }

    @Test
    void cachedImagesNeedNoTokenAfterARestartEvenWhenTheRegistryIsDown() throws Exception {
        pullAlpine();
        // a restart (or an expired token) forgets the challenge and the token; the registry is now unreachable
        dockerUpstream.forget(repo);
        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl())
                .willReturn(WireMock.aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        pullAlpine();
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", digest(alpineIndex)));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
        assertThat(proxyService.status(repo).consecutiveFailures()).isZero();
    }

    @Test
    void theLibraryPrefixAppliesOnlyToSingleSegmentNames() throws Exception {
        mvc.perform(get(URL + "/v2/library/alpine/manifests/3.20").accept(ALL_MANIFESTS)).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20")));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/acme/app/manifests/1"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-123"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", OCI_MANIFEST).withBody(amdManifest)));
        mvc.perform(get(URL + "/v2/acme/app/manifests/1").accept(ALL_MANIFESTS)).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/token"))
                .withQueryParam("scope", WireMock.equalTo("repository:acme/app:pull")));
    }

    @Test
    void acceptHeaderPicksTheVariantToCache() throws Exception {
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").accept(DOCKER_MANIFEST + ", " + OCI_MANIFEST))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, OCI_MANIFEST))
                .andExpect(header().string("Docker-Content-Digest", digest(amdManifest)));
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, OCI_INDEX))
                .andExpect(header().string("Docker-Content-Digest", digest(alpineIndex)));
        // a client that sends no Accept at all gets the full index too
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20"))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", digest(alpineIndex)));
        upstream.verify(2, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20")));
        assertThat(storage.list(repo, "tags/library/alpine/")).extracting(a -> a.getPath())
                .contains("tags/library/alpine/3.20")
                .anyMatch(p -> p.startsWith("tags/library/alpine/3.20@"));
        mvc.perform(get(URL + "/v2/alpine/tags/list")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.contains("3.19", "3.20", "latest")));
    }

    @Test
    void clientsSendingOneAcceptHeaderPerTypeShareTheCachedIndex() throws Exception {
        // containers/image (podman, skopeo, buildah) sends every manifest type as an Accept header of its own,
        // single manifests first; docker and containerd send one comma-separated header
        String[] containersImage = {OCI_MANIFEST, DOCKER_MANIFEST, "application/vnd.docker.distribution.manifest.v1+prettyjws",
                "application/vnd.docker.distribution.manifest.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json",
                OCI_INDEX};
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").header(HttpHeaders.ACCEPT, (Object[]) containersImage))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, OCI_INDEX))
                .andExpect(header().string("Docker-Content-Digest", digest(alpineIndex)));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20"))
                .withHeader("Accept", WireMock.containing(OCI_INDEX)));

        repositories.setOffline(REPO, true);
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", digest(alpineIndex)));
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").header(HttpHeaders.ACCEPT, (Object[]) containersImage))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", digest(alpineIndex)));
        assertThat(storage.list(repo, "tags/library/alpine/")).extracting(a -> a.getPath())
                .noneMatch(p -> p.startsWith("tags/library/alpine/3.20@"));
    }

    @Test
    void tagListMergesTheCacheWithUpstream() throws Exception {
        mvc.perform(get(URL + "/v2/alpine/tags/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("alpine"))
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.contains("3.19", "3.20", "latest")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/tags/list"))
                .withQueryParam("n", WireMock.equalTo("1000")));
        mvc.perform(get(URL + "/v2/nobody/tags/list")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("NAME_UNKNOWN"));

        pullAlpine();
        repositories.setOffline(REPO, true);
        mvc.perform(get(URL + "/v2/alpine/tags/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags").value(org.hamcrest.Matchers.contains("3.19", "3.20", "latest")));
    }

    @Test
    void offlineModeServesTheCacheAndRefusesTheRest() throws Exception {
        pullAlpine();
        repositories.setOffline(REPO, true);
        upstream.resetRequests();

        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS)).andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", digest(alpineIndex)));
        mvc.perform(get(URL + "/v2/alpine/manifests/" + digest(amdManifest))).andExpect(status().isOk());
        mvc.perform(get(URL + "/v2/alpine/blobs/" + digest(amdLayer))).andExpect(status().isOk());
        mvc.perform(get(URL + "/v2/alpine/manifests/3.19").accept(ALL_MANIFESTS))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        mvc.perform(get(URL + "/v2/alpine/blobs/" + digest(config("never"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.offline"));
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));

        settings.setGlobalOffline(true);
        repositories.setOffline(REPO, false);
        mvc.perform(get(URL + "/v2/alpine/manifests/3.19")).andExpect(status().isServiceUnavailable());
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void staleManifestIsServedWhenUpstreamBreaks() throws Exception {
        Repository impatient = new Repository();
        impatient.setName("docker-hub-stale");
        impatient.setFormat("docker");
        impatient.setType(RepositoryType.PROXY);
        impatient.setRemoteUrl(upstream.baseUrl());
        impatient.setMetadataMaxAgeMinutes(0);
        impatient.setFormatSettings(Map.of(DockerUpstream.LIBRARY_PREFIX_SETTING, "true"));
        repositories.create(impatient);
        dockerUpstream.forget(impatient);
        String url = "/repository/docker-hub-stale";

        mvc.perform(get(url + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS)).andExpect(status().isOk());
        mvc.perform(get(url + "/v2/alpine/blobs/" + digest(amdLayer))).andExpect(status().isOk());
        // a re-validation is answered 304 by a well-behaved registry
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20"))
                .withHeader("If-None-Match", WireMock.equalTo("\"" + digest(alpineIndex) + "\""))
                .atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(304)));
        mvc.perform(get(url + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS)).andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", digest(alpineIndex)));
        upstream.verify(2, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20")));

        upstream.resetAll();
        upstream.stubFor(WireMock.any(WireMock.anyUrl()).willReturn(WireMock.serverError()));
        mvc.perform(get(url + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS)).andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", digest(alpineIndex)));
        mvc.perform(get(url + "/v2/alpine/blobs/" + digest(amdLayer))).andExpect(status().isOk());
        mvc.perform(get(url + "/v2/alpine/manifests/3.19").accept(ALL_MANIFESTS))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("upstream.unavailable"));
    }

    @Test
    void upstream404IsRememberedByTheNegativeCache() throws Exception {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/nope"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-123"))
                .willReturn(WireMock.aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"errors\":[{\"code\":\"MANIFEST_UNKNOWN\",\"message\":\"manifest unknown\",\"detail\":null}]}")));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(URL + "/v2/alpine/manifests/nope").accept(ALL_MANIFESTS))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errors[0].code").value("MANIFEST_UNKNOWN"));
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/nope")));
    }

    @Test
    void imagesTheRegistryDeniesAre404NotOutages() throws Exception {
        // Docker Hub answers 401 for images that do not exist; the catch-all stub does the same
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(URL + "/v2/ghost/manifests/latest").accept(ALL_MANIFESTS))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errors[0].code").value("MANIFEST_UNKNOWN"));
        }
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/ghost/manifests/latest")));
        // the proxy is still healthy: a real image pulls fine right after
        mvc.perform(get(URL + "/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS)).andExpect(status().isOk());
        assertThat(proxyService.status(repo).consecutiveFailures()).isZero();
    }

    @Test
    void corruptUpstreamContentIsNotCached() throws Exception {
        String wanted = digest(config("wanted"));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/blobs/" + wanted))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-123"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/octet-stream")
                        .withBody(layer("something else"))));
        mvc.perform(get(URL + "/v2/alpine/blobs/" + wanted)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code").value("BLOB_UNKNOWN"));
        assertThat(storage.find(repo, "blobs/sha256/" + wanted.substring(7))).isEmpty();
        String wantedManifest = digest(config("manifest"));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/" + wantedManifest))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-123"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", OCI_MANIFEST).withBody(amdManifest)));
        mvc.perform(get(URL + "/v2/alpine/manifests/" + wantedManifest)).andExpect(status().isNotFound());
        assertThat(storage.find(repo, "manifests/library/alpine/sha256/" + wantedManifest.substring(7))).isEmpty();
    }

    @Test
    void remoteCredentialsGoToTheTokenEndpointOnly() throws Exception {
        Repository authed = new Repository();
        authed.setName("docker-hub-authed");
        authed.setFormat("docker");
        authed.setType(RepositoryType.PROXY);
        authed.setRemoteUrl(upstream.baseUrl());
        authed.setRemoteUsername("hubuser");
        authed.setRemotePassword("hubsecret");
        authed.setFormatSettings(Map.of(DockerUpstream.LIBRARY_PREFIX_SETTING, "true"));
        repositories.create(authed);
        dockerUpstream.forget(authed);
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/token"))
                .withBasicAuth("hubuser", "hubsecret")
                .atPriority(1)
                .willReturn(WireMock.okJson("{\"token\":\"tok-123\",\"expires_in\":300}")));

        mvc.perform(get("/repository/docker-hub-authed/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS)).andExpect(status().isOk());
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/token")).withBasicAuth(
                new com.github.tomakehurst.wiremock.client.BasicCredentials("hubuser", "hubsecret")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-123")));
    }

    @Test
    void registryWithoutTokenAuthAndWithoutLibraryPrefix() throws Exception {
        upstream.resetAll();
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/")).willReturn(WireMock.okJson("{}")));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/alpine/manifests/3.20"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", OCI_MANIFEST).withBody(amdManifest)));
        Repository plain = new Repository();
        plain.setName("docker-internal");
        plain.setFormat("docker");
        plain.setType(RepositoryType.PROXY);
        plain.setRemoteUrl(upstream.baseUrl() + "/");
        plain.setRemoteUsername("svc");
        plain.setRemotePassword("pw");
        repositories.create(plain);
        dockerUpstream.forget(plain);

        mvc.perform(get("/repository/docker-internal/v2/alpine/manifests/3.20").accept(ALL_MANIFESTS))
                .andExpect(status().isOk())
                .andExpect(header().string("Docker-Content-Digest", digest(amdManifest)));
        upstream.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/token")));
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/alpine/manifests/3.20"))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials("svc", "pw")));
    }

    @Test
    void writesAreRefusedWith405() throws Exception {
        mvc.perform(put(URL + "/v2/alpine/manifests/3.20").header(HttpHeaders.AUTHORIZATION, adminBasic())
                        .contentType(OCI_MANIFEST).content(amdManifest))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errors[0].code").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("read-only")));
        mvc.perform(post(URL + "/v2/alpine/blobs/uploads/").header(HttpHeaders.AUTHORIZATION, adminBasic()))
                .andExpect(status().isMethodNotAllowed());
        upstream.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void settingsAreValidatedOnCreate() throws Exception {
        Repository bad = new Repository();
        bad.setName("docker-bad");
        bad.setFormat("docker");
        bad.setType(RepositoryType.PROXY);
        bad.setRemoteUrl(upstream.baseUrl());
        bad.setFormatSettings(Map.of("docker.libraryPrefix", "sometimes"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repositories.create(bad))
                .isInstanceOf(dev.granary.core.GranaryException.class)
                .hasMessageContaining("docker.libraryPrefix");
    }
}
