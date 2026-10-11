package dev.granary.core.proxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.CoreServiceTest;
import dev.granary.core.dao.AssetDao;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProxyServiceTest extends CoreServiceTest {

    @Autowired
    ProxyService proxy;

    @Autowired
    StorageService storage;

    @Autowired
    AssetDao assets;

    @Autowired
    ComponentDao components;

    @Autowired
    UpstreamHealth health;

    @Autowired
    NegativeCache negativeCache;

    WireMockServer upstream;
    Repository repo;

    @BeforeEach
    void startUpstream() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        repo = proxy("p", upstream.baseUrl() + "/remote/");
        health.reset(repo);
        negativeCache.clear(repo.getId());
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop();
    }

    @Test
    void fetchesStoresAndServesArtifactsFromCacheAfterwards() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/a/lib-1.0.jar")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/java-archive; charset=binary")
                .withHeader("ETag", "\"abc\"")
                .withHeader("Last-Modified", "Wed, 01 Jan 2026 00:00:00 GMT")
                .withBody("JAR")));

        Optional<StoredContent> first = proxy.fetch(repo, "a/lib-1.0.jar", FetchOptions.artifact());

        assertThat(first).isPresent();
        assertThat(first.get().readUtf8()).isEqualTo("JAR");
        assertThat(first.get().asset().getContentType()).isEqualTo("application/java-archive");
        assertThat(first.get().asset().getOrigin()).isEqualTo(Asset.Origin.CACHED);
        assertThat(first.get().asset().getUpstreamEtag()).isEqualTo("\"abc\"");
        assertThat(first.get().asset().getUpstreamLastModified()).isEqualTo("Wed, 01 Jan 2026 00:00:00 GMT");

        upstream.resetAll(); // upstream now answers 404 for everything
        Optional<StoredContent> second = proxy.fetch(repo, "a/lib-1.0.jar", FetchOptions.artifact());
        assertThat(second).isPresent();
        assertThat(second.get().readUtf8()).isEqualTo("JAR");
        upstream.verify(0, getRequestedFor(urlEqualTo("/remote/a/lib-1.0.jar")));
    }

    @Test
    void sendsCredentialsAndUserAgentUpstream() {
        repo.setRemoteUsername("user");
        repo.setRemotePassword("pass");
        repo = repositories.update("p", repo);
        upstream.stubFor(get(urlEqualTo("/remote/f")).willReturn(aResponse().withStatus(200).withBody("x")));

        proxy.fetch(repo, "f", FetchOptions.artifact());

        upstream.verify(getRequestedFor(urlEqualTo("/remote/f"))
                .withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz"))
                .withHeader("User-Agent", com.github.tomakehurst.wiremock.client.WireMock.containing("Granary")));
    }

    @Test
    void bearerTokenWinsOverBasic() {
        repo.setRemoteToken("tok");
        repo.setRemoteUsername("user");
        repo.setRemotePassword("pass");
        repo = repositories.update("p", repo);
        upstream.stubFor(get(urlEqualTo("/remote/f")).willReturn(aResponse().withStatus(200).withBody("x")));

        proxy.fetch(repo, "f", FetchOptions.artifact());

        upstream.verify(getRequestedFor(urlEqualTo("/remote/f")).withHeader("Authorization", equalTo("Bearer tok")));
    }

    @Test
    void freshMetadataIsServedWithoutContactingUpstream() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200).withBody("{\"v\":1}")));
        proxy.fetch(repo, "index.json", FetchOptions.index());
        upstream.resetAll();
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200).withBody("{\"v\":2}")));

        Optional<StoredContent> cached = proxy.fetch(repo, "index.json", FetchOptions.index());

        assertThat(cached.orElseThrow().readUtf8()).isEqualTo("{\"v\":1}");
        upstream.verify(0, getRequestedFor(urlEqualTo("/remote/index.json")));
    }

    @Test
    void staleMetadataIsRevalidatedConditionallyAndRenewedOn304() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"e1\"").withBody("{\"v\":1}")));
        StoredContent first = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        age(first.asset(), Duration.ofHours(2));
        upstream.resetAll();
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).withHeader("If-None-Match", equalTo("\"e1\""))
                .willReturn(aResponse().withStatus(304)));

        StoredContent renewed = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();

        assertThat(renewed.readUtf8()).isEqualTo("{\"v\":1}");
        assertThat(renewed.asset().getLastVerifiedAt()).isAfter(Instant.now().minusSeconds(5));
        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/index.json")));
    }

    @Test
    void staleMetadataIsReplacedOn200() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200).withBody("{\"v\":1}")));
        StoredContent first = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        age(first.asset(), Duration.ofHours(2));
        upstream.resetAll();
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200).withBody("{\"v\":2}")));

        assertThat(proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow().readUtf8()).isEqualTo("{\"v\":2}");
        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(1);
    }

    @Test
    void explicitMaxAgeOverridesTheRepositoryDefault() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200).withBody("{\"v\":1}")));
        StoredContent first = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        age(first.asset(), Duration.ofMinutes(5));
        upstream.resetAll();
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200).withBody("{\"v\":2}")));

        assertThat(proxy.fetch(repo, "index.json", FetchOptions.index(Duration.ofMinutes(1))).orElseThrow().readUtf8())
                .isEqualTo("{\"v\":2}");
    }

    @Test
    void staleContentIsServedWhenUpstreamFails() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200).withBody("{\"v\":1}")));
        StoredContent first = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        age(first.asset(), Duration.ofHours(2));
        upstream.resetAll();
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(502)));

        assertThat(proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow().readUtf8()).isEqualTo("{\"v\":1}");
        assertThat(health.status(repo).consecutiveFailures()).isEqualTo(1);
        assertThat(health.status(repo).lastError()).contains("502");
    }

    @Test
    void staleContentIsServedWhenUpstreamIsDown() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200).withBody("{\"v\":1}")));
        StoredContent first = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        age(first.asset(), Duration.ofHours(2));
        upstream.stop(); // connection refused from now on

        assertThat(proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow().readUtf8()).isEqualTo("{\"v\":1}");
        assertThat(proxy.fetch(repo, "never-seen.jar", FetchOptions.artifact())).isEmpty();
        assertThat(health.status(repo).consecutiveFailures()).isEqualTo(2);
        assertThat(health.status(repo).reachable()).isFalse(); // threshold is 2 in the test profile
    }

    @Test
    void offlineModeNeverTouchesTheNetwork() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200).withBody("{\"v\":1}")));
        StoredContent first = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        age(first.asset(), Duration.ofHours(2));
        repo = repositories.setOffline("p", true);
        upstream.resetAll();

        assertThat(proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow().readUtf8()).isEqualTo("{\"v\":1}");
        assertThat(proxy.fetch(repo, "missing.jar", FetchOptions.artifact())).isEmpty();
        upstream.verify(0, getRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));
        assertThat(proxy.isOffline(repo)).isTrue();
    }

    @Test
    void globalOfflineSwitchAppliesToEveryProxy() {
        settings.setGlobalOffline(true);
        upstream.stubFor(get(urlEqualTo("/remote/f")).willReturn(aResponse().withStatus(200).withBody("x")));

        assertThat(proxy.fetch(repo, "f", FetchOptions.artifact())).isEmpty();
        upstream.verify(0, getRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));
    }

    @Test
    void upstream404IsRememberedInTheNegativeCache() {
        upstream.stubFor(get(urlEqualTo("/remote/missing.jar")).willReturn(aResponse().withStatus(404)));

        assertThat(proxy.fetch(repo, "missing.jar", FetchOptions.artifact())).isEmpty();
        assertThat(proxy.fetch(repo, "missing.jar", FetchOptions.artifact())).isEmpty();
        assertThat(proxy.fetch(repo, "missing.jar", FetchOptions.artifact())).isEmpty();

        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/missing.jar")));
        assertThat(health.status(repo).consecutiveFailures()).isZero(); // a 404 is a valid answer, not a failure
        proxy.invalidate(repo);
        assertThat(proxy.fetch(repo, "missing.jar", FetchOptions.artifact())).isEmpty();
        upstream.verify(2, getRequestedFor(urlEqualTo("/remote/missing.jar")));
    }

    @Test
    void openCircuitShortCircuitsToTheCache() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200).withBody("{\"v\":1}")));
        StoredContent first = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        age(first.asset(), Duration.ofHours(2));
        upstream.resetAll();
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(500)));
        upstream.stubFor(get(urlEqualTo("/remote/x")).willReturn(aResponse().withStatus(500)));

        proxy.fetch(repo, "x", FetchOptions.artifact());
        proxy.fetch(repo, "x", FetchOptions.artifact()); // second failure opens the circuit
        assertThat(health.allowRequest(repo)).isFalse();

        assertThat(proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow().readUtf8()).isEqualTo("{\"v\":1}");
        upstream.verify(0, getRequestedFor(urlEqualTo("/remote/index.json")));
    }

    @Test
    void absoluteUrlFetchCachesUnderTheGivenLocalPath() throws IOException {
        upstream.stubFor(get(urlEqualTo("/elsewhere/registration.json")).willReturn(aResponse().withStatus(200).withBody("{}")));

        StoredContent c = proxy.fetch(repo, "v3/registration/x/index.json",
                java.net.URI.create(upstream.baseUrl() + "/elsewhere/registration.json"),
                FetchOptions.index(), Map.of("Accept", "application/json")).orElseThrow();

        assertThat(c.asset().getPath()).isEqualTo("v3/registration/x/index.json");
        assertThat(c.readUtf8()).isEqualTo("{}");
        upstream.verify(getRequestedFor(urlEqualTo("/elsewhere/registration.json")).withHeader("Accept", equalTo("application/json")));
    }

    @Test
    void offlineRepositoriesAreNotFetchedFrom() {
        repo.setOnline(false);
        repo = repositories.update("p", repo);
        assertThat(proxy.isOffline(repo)).isTrue();
    }

    @Test
    void oversizedUpstreamHeadersStillLetTheArtifactBeCached() throws IOException {
        String contentType = "application/java-archive; x-meta=\"" + "m".repeat(300) + "\"";
        String bareContentType = "application/x-" + "k".repeat(300);
        String etag = "\"" + "e".repeat(700) + "\"";
        String lastModified = "Wed, 01 Jan 2026 00:00:00 GMT" + " ".repeat(10) + "x".repeat(100);
        upstream.stubFor(get(urlEqualTo("/remote/big/lib-1.0.jar")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", contentType)
                .withHeader("ETag", etag)
                .withHeader("Last-Modified", lastModified)
                .withBody("JAR")));
        upstream.stubFor(get(urlEqualTo("/remote/big/lib-1.0.tgz")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", bareContentType)
                .withBody("TGZ")));

        StoredContent jar = proxy.fetch(repo, "big/lib-1.0.jar", FetchOptions.artifact()).orElseThrow();
        StoredContent tgz = proxy.fetch(repo, "big/lib-1.0.tgz", FetchOptions.artifact()).orElseThrow();

        assertThat(jar.readUtf8()).isEqualTo("JAR");
        assertThat(jar.asset().getContentType()).isEqualTo("application/java-archive");
        assertThat(jar.asset().getUpstreamEtag()).isNull();
        assertThat(jar.asset().getUpstreamLastModified()).isNull();
        assertThat(tgz.asset().getContentType()).isEqualTo("application/gzip");
        Asset stored = assets.findById(jar.asset().getId()).orElseThrow();
        assertThat(stored.getUpstreamEtag()).isNull();

        // cached once, served from the cache afterwards
        assertThat(proxy.fetch(repo, "big/lib-1.0.jar", FetchOptions.artifact()).orElseThrow().readUtf8()).isEqualTo("JAR");
        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/big/lib-1.0.jar")));
    }

    @Test
    void droppedValidatorsMakeTheNextRevalidationUnconditional() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"short\"").withBody("{\"v\":1}")));
        StoredContent first = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        assertThat(first.asset().getUpstreamEtag()).isEqualTo("\"short\"");
        age(first.asset(), Duration.ofHours(2));
        upstream.resetAll();
        upstream.stubFor(get(urlEqualTo("/remote/index.json")).willReturn(aResponse().withStatus(200)
                .withHeader("ETag", "\"" + "l".repeat(600) + "\"").withBody("{\"v\":2}")));

        StoredContent second = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        assertThat(second.readUtf8()).isEqualTo("{\"v\":2}");
        // the old validator described the old bytes, so it must not survive the replacement either
        assertThat(second.asset().getUpstreamEtag()).isNull();
        age(second.asset(), Duration.ofHours(2));

        proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        upstream.verify(2, getRequestedFor(urlEqualTo("/remote/index.json")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/index.json")).withHeader("If-None-Match", equalTo("\"short\"")));
        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/index.json")).withoutHeader("If-None-Match"));
    }

    // --- components ---------------------------------------------------------------------------------------

    private static final StorageService.ComponentCoordinates LIB =
            new StorageService.ComponentCoordinates("org.example", "lib", "1.0");

    @Test
    void cachedArtifactIsLinkedToItsComponentOnce() {
        upstream.stubFor(get(urlEqualTo("/remote/org/example/lib/1.0/lib-1.0.jar")).willReturn(aResponse().withStatus(200).withBody("JAR")));
        upstream.stubFor(get(urlEqualTo("/remote/org/example/lib/1.0/lib-1.0.pom")).willReturn(aResponse().withStatus(200).withBody("POM")));

        StoredContent jar = proxy.fetch(repo, "org/example/lib/1.0/lib-1.0.jar", FetchOptions.artifact(LIB)).orElseThrow();
        StoredContent again = proxy.fetch(repo, "org/example/lib/1.0/lib-1.0.jar", FetchOptions.artifact(LIB)).orElseThrow();
        StoredContent pom = proxy.fetch(repo, "org/example/lib/1.0/lib-1.0.pom", FetchOptions.artifact(LIB)).orElseThrow();

        assertThat(jar.asset().getComponentId()).isNotNull()
                .isEqualTo(again.asset().getComponentId())
                .isEqualTo(pom.asset().getComponentId());
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        Component c = components.findById(jar.asset().getComponentId()).orElseThrow();
        assertThat(List.of(c.getNamespace(), c.getName(), c.getVersion())).containsExactly("org.example", "lib", "1.0");
        assertThat(assets.findByComponentId(c.getId())).extracting(Asset::getPath)
                .containsExactlyInAnyOrder("org/example/lib/1.0/lib-1.0.jar", "org/example/lib/1.0/lib-1.0.pom");
        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/org/example/lib/1.0/lib-1.0.jar")));
    }

    @Test
    void filesWithoutCoordinatesCreateNoComponent() {
        upstream.stubFor(get(urlEqualTo("/remote/org/example/lib/maven-metadata.xml")).willReturn(aResponse().withStatus(200).withBody("<metadata/>")));
        upstream.stubFor(get(urlEqualTo("/remote/notes.txt")).willReturn(aResponse().withStatus(200).withBody("n")));

        assertThat(proxy.fetch(repo, "org/example/lib/maven-metadata.xml", FetchOptions.index()).orElseThrow()
                .asset().getComponentId()).isNull();
        assertThat(proxy.fetch(repo, "notes.txt", FetchOptions.artifact()).orElseThrow().asset().getComponentId()).isNull();
        assertThat(components.countByRepositoryId(repo.getId())).isZero();
    }

    @Test
    void artifactCachedWithoutComponentIsLinkedWhenServedAgainEvenOffline() {
        // what a proxy of an earlier version left behind: cached, no component
        storage.store(repo, "org/example/lib/1.0/lib-1.0.jar", new java.io.ByteArrayInputStream("JAR".getBytes()),
                null, Asset.Origin.CACHED, false);
        repo = repositories.setOffline("p", true);

        StoredContent served = proxy.fetch(repo, "org/example/lib/1.0/lib-1.0.jar", FetchOptions.artifact(LIB)).orElseThrow();

        assertThat(served.asset().getComponentId()).isNotNull();
        assertThat(assets.findById(served.asset().getId()).orElseThrow().getComponentId()).isEqualTo(served.asset().getComponentId());
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        upstream.verify(0, getRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));
    }

    @Test
    void coordinatesTheTableCannotHoldStillCacheTheFile() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/odd.bin")).willReturn(aResponse().withStatus(200).withBody("ODD")));
        var tooLong = new StorageService.ComponentCoordinates("", "odd", "9".repeat(500));

        StoredContent stored = proxy.fetch(repo, "odd.bin", FetchOptions.artifact(tooLong)).orElseThrow();

        assertThat(stored.readUtf8()).isEqualTo("ODD");
        assertThat(stored.asset().getComponentId()).isNull();
        assertThat(proxy.fetch(repo, "odd.bin", FetchOptions.artifact(tooLong)).orElseThrow().readUtf8()).isEqualTo("ODD");
        assertThat(components.countByRepositoryId(repo.getId())).isZero();
        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/odd.bin")));
    }

    @Test
    void concurrentFirstFetchesOfOneComponentCreateOneRow() throws Exception {
        int files = 4;
        for (int i = 0; i < files; i++) {
            upstream.stubFor(get(urlEqualTo("/remote/org/example/lib/1.0/f" + i)).willReturn(aResponse().withStatus(200)
                    .withFixedDelay(100).withBody("F" + i)));
        }
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Optional<StoredContent>>> results = new java.util.ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(files * 2)) {
            for (int i = 0; i < files * 2; i++) {
                String path = "org/example/lib/1.0/f" + (i % files);
                results.add(pool.submit(() -> {
                    start.await();
                    return proxy.fetch(repo, path, FetchOptions.artifact(LIB));
                }));
            }
            start.countDown();
            java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
            for (var result : results) {
                ids.add(result.get(30, java.util.concurrent.TimeUnit.SECONDS).orElseThrow().asset().getComponentId());
            }
            assertThat(ids).hasSize(1).doesNotContainNull();
        }
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(files);
        for (int i = 0; i < files; i++) {
            upstream.verify(1, getRequestedFor(urlEqualTo("/remote/org/example/lib/1.0/f" + i)));
        }
    }

    @Test
    void deletedComponentIsFetchedAgainOnlineAndMissingOffline() throws IOException {
        upstream.stubFor(get(urlEqualTo("/remote/org/example/lib/1.0/lib-1.0.jar")).willReturn(aResponse().withStatus(200).withBody("JAR")));
        StoredContent jar = proxy.fetch(repo, "org/example/lib/1.0/lib-1.0.jar", FetchOptions.artifact(LIB)).orElseThrow();

        assertThat(storage.deleteComponent(jar.asset().getComponentId())).isEqualTo(1);
        assertThat(storage.find(repo, "org/example/lib/1.0/lib-1.0.jar")).isEmpty();
        repo = repositories.setOffline("p", true);
        assertThat(proxy.fetch(repo, "org/example/lib/1.0/lib-1.0.jar", FetchOptions.artifact(LIB))).isEmpty();
        assertThat(components.countByRepositoryId(repo.getId())).isZero();

        repo = repositories.setOffline("p", false);
        StoredContent refetched = proxy.fetch(repo, "org/example/lib/1.0/lib-1.0.jar", FetchOptions.artifact(LIB)).orElseThrow();
        assertThat(refetched.readUtf8()).isEqualTo("JAR");
        assertThat(refetched.asset().getComponentId()).isNotNull().isNotEqualTo(jar.asset().getComponentId());
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        upstream.verify(2, getRequestedFor(urlEqualTo("/remote/org/example/lib/1.0/lib-1.0.jar")));
    }

    @Test
    void artifactOptionsCarryTheirComponent() {
        assertThat(FetchOptions.artifact(null)).isEqualTo(FetchOptions.artifact());
        assertThat(FetchOptions.artifact(LIB).component()).isEqualTo(LIB);
        assertThat(FetchOptions.artifact(LIB).metadata()).isFalse();
        assertThat(FetchOptions.index().withComponent(LIB).metadata()).isTrue();
        assertThat(FetchOptions.index(Duration.ofMinutes(5)).withComponent(LIB).maxAge()).isEqualTo(Duration.ofMinutes(5));
        assertThat(new FetchOptions(true, null).component()).isNull();
    }

    private void age(Asset asset, Duration by) {
        Asset a = assets.findById(asset.getId()).orElseThrow();
        a.setLastVerifiedAt(Instant.now().minus(by));
        assets.save(a);
    }
}
