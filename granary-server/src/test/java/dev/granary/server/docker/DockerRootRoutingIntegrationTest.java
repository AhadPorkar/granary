package dev.granary.server.docker;

import static dev.granary.server.docker.DockerFixtures.ALL_MANIFESTS;
import static dev.granary.server.docker.DockerFixtures.MAPPER;
import static dev.granary.server.docker.DockerFixtures.OCI_MANIFEST;
import static dev.granary.server.docker.DockerFixtures.config;
import static dev.granary.server.docker.DockerFixtures.digest;
import static dev.granary.server.docker.DockerFixtures.layer;
import static dev.granary.server.docker.DockerFixtures.manifest;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.ProxyService;
import dev.granary.format.docker.DockerUpstream;
import dev.granary.server.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * Container runtimes talk to {@code host:port/v2/...}, never to a repository path. This test drives the real
 * servlet container (MockMvc does not follow forwards) the way {@code docker push host:port/docker-hosted/acme/app}
 * and {@code docker pull host:port/acme/app} do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DockerRootRoutingIntegrationTest extends IntegrationTest {

    private static final WireMockServer upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @LocalServerPort
    private int port;

    @Autowired
    private ProxyService proxyService;

    @Autowired
    private DockerUpstream dockerUpstream;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final byte[] alpineManifest = manifest(OCI_MANIFEST, config("alpine"), layer("alpine"));

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
        hosted("docker-hosted", "docker");
        Repository hub = new Repository();
        hub.setName("docker-hub");
        hub.setFormat("docker");
        hub.setType(dev.granary.core.model.RepositoryType.PROXY);
        hub.setRemoteUrl(upstream.baseUrl());
        hub.setFormatSettings(java.util.Map.of(DockerUpstream.LIBRARY_PREFIX_SETTING, "true"));
        hub = repositories.create(hub);
        proxyService.invalidate(hub);
        dockerUpstream.forget(hub);
        group("docker-group", "docker", "docker-hosted", "docker-hub");
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/")).willReturn(WireMock.okJson("{}")));
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", OCI_MANIFEST).withBody(alpineManifest)));
        upstream.stubFor(WireMock.get(WireMock.urlPathMatching("/v2/.+")).atPriority(10).willReturn(WireMock.aResponse().withStatus(404)));
    }

    private String root() {
        return "http://localhost:" + port;
    }

    private HttpResponse<byte[]> send(String method, String path, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    @Test
    void pingAtTheServerRoot() throws Exception {
        HttpResponse<byte[]> anonymous = send("GET", "/v2/", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(header(anonymous, "WWW-Authenticate")).isEqualTo("Basic realm=\"Granary\"");
        assertThat(header(anonymous, "Docker-Distribution-API-Version")).isEqualTo("registry/2.0");
        assertThat(MAPPER.readTree(anonymous.body()).path("errors").path(0).path("code").stringValue()).isEqualTo("UNAUTHORIZED");

        HttpResponse<byte[]> authenticated = send("GET", "/v2/", null, "Authorization", adminBasic());
        assertThat(authenticated.statusCode()).isEqualTo(200);
        assertThat(new String(authenticated.body())).isEqualTo("{}");
        assertThat(header(authenticated, "Docker-Distribution-API-Version")).isEqualTo("registry/2.0");

        HttpResponse<byte[]> wrong = send("GET", "/v2/", null, "Authorization", basic("admin", "nope"));
        assertThat(wrong.statusCode()).isEqualTo(401);
    }

    @Test
    void pushWithRepositoryPrefixThenPullThroughTheRoot() throws Exception {
        byte[] config = config("root");
        byte[] layer = layer("root");

        // docker push localhost:port/docker-hosted/acme/app:1.0
        HttpResponse<byte[]> start = send("POST", "/v2/docker-hosted/acme/app/blobs/uploads/", null, "Authorization", adminBasic());
        assertThat(start.statusCode()).isEqualTo(202);
        String location = header(start, "Location");
        String uuid = header(start, "Docker-Upload-UUID");
        assertThat(location).isEqualTo(root() + "/v2/docker-hosted/acme/app/blobs/uploads/" + uuid);
        assertThat(header(start, "Range")).isEqualTo("0-0");

        int half = layer.length / 2;
        HttpResponse<byte[]> patch1 = send("PATCH", location.substring(root().length()), Arrays.copyOfRange(layer, 0, half),
                "Authorization", adminBasic(), "Content-Type", "application/octet-stream", "Content-Range", "0-" + (half - 1));
        assertThat(patch1.statusCode()).isEqualTo(202);
        assertThat(header(patch1, "Location")).isEqualTo(location);
        assertThat(header(patch1, "Range")).isEqualTo("0-" + (half - 1));
        HttpResponse<byte[]> patch2 = send("PATCH", location.substring(root().length()), Arrays.copyOfRange(layer, half, layer.length),
                "Authorization", adminBasic(), "Content-Type", "application/octet-stream");
        assertThat(patch2.statusCode()).isEqualTo(202);
        assertThat(header(patch2, "Range")).isEqualTo("0-" + (layer.length - 1));
        HttpResponse<byte[]> done = send("PUT", location.substring(root().length()) + "?digest=" + digest(layer), null,
                "Authorization", adminBasic());
        assertThat(done.statusCode()).isEqualTo(201);
        assertThat(header(done, "Location")).isEqualTo(root() + "/v2/docker-hosted/acme/app/blobs/" + digest(layer));
        assertThat(header(done, "Docker-Content-Digest")).isEqualTo(digest(layer));

        HttpResponse<byte[]> mono = send("POST", "/v2/docker-hosted/acme/app/blobs/uploads/?digest=" + digest(config), config,
                "Authorization", adminBasic(), "Content-Type", "application/octet-stream");
        assertThat(mono.statusCode()).isEqualTo(201);

        byte[] manifest = manifest(OCI_MANIFEST, config, layer);
        HttpResponse<byte[]> put = send("PUT", "/v2/docker-hosted/acme/app/manifests/1.0", manifest,
                "Authorization", adminBasic(), "Content-Type", OCI_MANIFEST);
        assertThat(put.statusCode()).isEqualTo(201);
        assertThat(header(put, "Location")).isEqualTo(root() + "/v2/docker-hosted/acme/app/manifests/" + digest(manifest));
        assertThat(header(put, "Docker-Content-Digest")).isEqualTo(digest(manifest));
        assertThat(send("PUT", "/v2/docker-hosted/acme/app/manifests/2.0", manifest,
                "Authorization", adminBasic(), "Content-Type", OCI_MANIFEST).statusCode()).isEqualTo(201);

        // docker pull localhost:port/acme/app:1.0 (the group is the root) and localhost:port/docker-hosted/acme/app:1.0
        for (String path : new String[] {"/v2/acme/app/manifests/1.0", "/v2/docker-hosted/acme/app/manifests/1.0",
                "/v2/docker-group/acme/app/manifests/1.0"}) {
            HttpResponse<byte[]> pulled = send("GET", path, null, "Accept", ALL_MANIFESTS);
            assertThat(pulled.statusCode()).as(path).isEqualTo(200);
            assertThat(pulled.body()).isEqualTo(manifest);
            assertThat(header(pulled, "Docker-Content-Digest")).isEqualTo(digest(manifest));
            assertThat(header(pulled, "Content-Type")).isEqualTo(OCI_MANIFEST);
        }
        HttpResponse<byte[]> head = send("HEAD", "/v2/acme/app/manifests/" + digest(manifest), null, "Accept", ALL_MANIFESTS);
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().firstValueAsLong("Content-Length").orElse(-1)).isEqualTo(manifest.length);
        HttpResponse<byte[]> blob = send("GET", "/v2/acme/app/blobs/" + digest(layer), null);
        assertThat(blob.statusCode()).isEqualTo(200);
        assertThat(blob.body()).isEqualTo(layer);
        assertThat(header(blob, "Docker-Content-Digest")).isEqualTo(digest(layer));

        // listings: the name reflects how the client addressed the image, links are absolute
        JsonNode tags = MAPPER.readTree(send("GET", "/v2/acme/app/tags/list", null).body());
        assertThat(tags.path("name").stringValue()).isEqualTo("acme/app");
        assertThat(tags.path("tags").path(0).stringValue()).isEqualTo("1.0");
        HttpResponse<byte[]> prefixedTags = send("GET", "/v2/docker-hosted/acme/app/tags/list?n=1", null);
        assertThat(MAPPER.readTree(prefixedTags.body()).path("name").stringValue()).isEqualTo("docker-hosted/acme/app");
        assertThat(MAPPER.readTree(prefixedTags.body()).path("tags").size()).isEqualTo(1);
        assertThat(header(prefixedTags, "Link")).isEqualTo("<" + root() + "/v2/docker-hosted/acme/app/tags/list?n=1&last=1.0>; rel=\"next\"");
        JsonNode catalog = MAPPER.readTree(send("GET", "/v2/_catalog", null).body());
        assertThat(catalog.path("repositories").path(0).stringValue()).isEqualTo("acme/app");

        // the proxy member answers for images the hosted member does not have
        HttpResponse<byte[]> alpine = send("GET", "/v2/library/alpine/manifests/3.20", null, "Accept", ALL_MANIFESTS);
        assertThat(alpine.statusCode()).isEqualTo(200);
        assertThat(alpine.body()).isEqualTo(alpineManifest);
        HttpResponse<byte[]> alpineShort = send("GET", "/v2/alpine/manifests/3.20", null, "Accept", ALL_MANIFESTS);
        assertThat(alpineShort.statusCode()).isEqualTo(200);
        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v2/library/alpine/manifests/3.20")));

        HttpResponse<byte[]> missing = send("GET", "/v2/nobody/manifests/1.0", null, "Accept", ALL_MANIFESTS);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(MAPPER.readTree(missing.body()).path("errors").path(0).path("code").stringValue()).isEqualTo("MANIFEST_UNKNOWN");

        // pushing through the group is refused and points at the hosted member
        HttpResponse<byte[]> viaGroup = send("POST", "/v2/acme/app/blobs/uploads/", null, "Authorization", adminBasic());
        assertThat(viaGroup.statusCode()).isEqualTo(405);
        assertThat(new String(viaGroup.body())).contains("docker-hosted");
        HttpResponse<byte[]> anonymousPush = send("POST", "/v2/docker-hosted/acme/app/blobs/uploads/", null);
        assertThat(anonymousPush.statusCode()).isEqualTo(401);
        assertThat(header(anonymousPush, "WWW-Authenticate")).startsWith("Basic");
    }

    @Test
    void concurrentPushesOfTheSameLayerAndManifestAllSucceed() throws Exception {
        // CI jobs pushing images built on the same base upload the same layers and manifests at the same moment
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            for (int round = 0; round < 5; round++) {
                byte[] config = config("race-" + round);
                byte[] layer = layer("race-" + round);
                byte[] manifest = manifest(OCI_MANIFEST, config, layer);
                java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    byte[] blob = i % 2 == 0 ? layer : config;
                    results.add(pool.submit(() -> send("POST", "/v2/docker-hosted/race/app/blobs/uploads/?digest=" + digest(blob),
                            blob, "Authorization", adminBasic(), "Content-Type", "application/octet-stream").statusCode()));
                }
                for (java.util.concurrent.Future<Integer> result : results) {
                    assertThat(result.get()).as("blob upload in round " + round).isEqualTo(201);
                }
                results.clear();
                for (int i = 0; i < 16; i++) {
                    results.add(pool.submit(() -> send("PUT", "/v2/docker-hosted/race/app/manifests/1.0", manifest,
                            "Authorization", adminBasic(), "Content-Type", OCI_MANIFEST).statusCode()));
                }
                int created = 0;
                for (java.util.concurrent.Future<Integer> result : results) {
                    int status = result.get();
                    assertThat(status).as("manifest push in round " + round).isIn(201, 409);
                    created += status == 201 ? 1 : 0;
                }
                // round 0 creates the tag; later rounds may only move it where redeploys are allowed
                assertThat(created).isEqualTo(round == 0 ? 16 : 0);
            }
        } finally {
            pool.shutdownNow();
        }
        HttpResponse<byte[]> pulled = send("GET", "/v2/docker-hosted/race/app/manifests/1.0", null, "Accept", ALL_MANIFESTS);
        assertThat(pulled.statusCode()).isEqualTo(200);
        assertThat(pulled.body()).isEqualTo(manifest(OCI_MANIFEST, config("race-0"), layer("race-0")));
    }

    @Test
    void repositoryPathsStillWorkDirectly() throws Exception {
        HttpResponse<byte[]> direct = send("GET", "/repository/docker-hosted/v2/", null, "Authorization", adminBasic());
        assertThat(direct.statusCode()).isEqualTo(200);
        HttpResponse<byte[]> start = send("POST", "/repository/docker-hosted/v2/acme/app/blobs/uploads/", null, "Authorization", adminBasic());
        assertThat(start.statusCode()).isEqualTo(202);
        assertThat(header(start, "Location")).startsWith(root() + "/repository/docker-hosted/v2/acme/app/blobs/uploads/");
        HttpResponse<byte[]> other = send("GET", "/v2ish/anything", null);
        assertThat(other.statusCode()).isNotEqualTo(500);
    }
}
