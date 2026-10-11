package dev.granary.server.storage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.config.GranaryProperties;
import dev.granary.core.dao.AssetDao;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Component;
import dev.granary.core.model.Repository;
import dev.granary.core.proxy.FetchOptions;
import dev.granary.core.proxy.ProxyService;
import dev.granary.core.storage.Checksums;
import dev.granary.core.storage.StorageLocks;
import dev.granary.core.storage.StorageService;
import dev.granary.core.storage.StoredContent;
import dev.granary.server.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Races that used to end in a 500 (duplicate asset or component rows) and the proxy's single-flight fetch. Every
 * scenario starts its threads behind one start gate and is repeated, because a race that passes once proves
 * little. Subclasses run the same scenarios on H2 and on PostgreSQL.
 */
abstract class StorageConcurrencyScenarios extends IntegrationTest {

    static final int THREADS = 16;

    @Autowired
    StorageService storage;

    @Autowired
    ProxyService proxy;

    @Autowired
    StorageLocks locks;

    @Autowired
    AssetDao assets;

    @Autowired
    ComponentDao components;

    @Autowired
    GranaryProperties properties;

    @Autowired
    PlatformTransactionManager transactionManager;

    WireMockServer upstream;
    Duration originalSingleFlightTimeout;

    @BeforeEach
    void startUpstream() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort().containerThreads(64));
        upstream.start();
        originalSingleFlightTimeout = properties.getProxy().getSingleFlightTimeout();
    }

    @AfterEach
    void stopUpstream() {
        properties.getProxy().setSingleFlightTimeout(originalSingleFlightTimeout);
        upstream.stop();
    }

    // --- assets and components ---

    @RepeatedTest(5)
    void sameNewPathStoredConcurrentlyEndsWithOneAssetHoldingOneOfTheWrittenBlobs() throws Exception {
        Repository repo = hosted("race-hosted", "raw");

        List<StoredContent> results = Race.run(THREADS, i -> () -> storage.store(repo, "same/new.bin",
                bytes("payload " + i), "application/octet-stream", Asset.Origin.UPLOADED, false));

        Set<String> written = new HashSet<>();
        for (int i = 0; i < THREADS; i++) {
            written.add(Checksums.hex("SHA-256", ("payload " + i).getBytes(StandardCharsets.UTF_8)));
        }
        assertThat(results).hasSize(THREADS).allSatisfy(r -> assertThat(r.asset().getPath()).isEqualTo("same/new.bin"));
        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(1);
        Asset asset = assets.findByRepositoryIdAndPath(repo.getId(), "same/new.bin").orElseThrow();
        assertThat(written).contains(asset.getBlobSha256());
        StoredContent stored = storage.find(repo, "same/new.bin").orElseThrow();
        String body = stored.readUtf8();
        assertThat(body).startsWith("payload ");
        assertThat(Checksums.hex("SHA-256", body.getBytes(StandardCharsets.UTF_8))).isEqualTo(asset.getBlobSha256());
        assertThat(asset.getSize()).isEqualTo(body.length());
        assertThat(locks.heldKeys()).isZero();
    }

    @RepeatedTest(5)
    void sameNewComponentFromDifferentPathsEndsWithOneComponentRow() throws Exception {
        Repository repo = hosted("race-hosted", "raw");
        var coordinates = new StorageService.ComponentCoordinates("acme", "wheel", "1.0.0");

        List<StoredContent> results = Race.run(THREADS, i -> () -> storage.store(repo, "acme/wheel/1.0.0/wheel-" + i + ".whl",
                bytes("wheel " + i), null, Asset.Origin.UPLOADED, false, coordinates));

        List<Component> rows = components.findByRepositoryIdAndNamespaceAndNameOrderByVersionAsc(repo.getId(), "acme", "wheel");
        assertThat(rows).hasSize(1);
        UUID componentId = rows.get(0).getId();
        assertThat(results).extracting(r -> r.asset().getComponentId()).containsOnly(componentId);
        assertThat(assets.findByComponentId(componentId)).hasSize(THREADS);
        assertThat(locks.heldKeys()).isZero();
    }

    @RepeatedTest(3)
    void samePathAndSameNewComponentAtOnce() throws Exception {
        Repository repo = hosted("race-hosted", "raw");
        var coordinates = new StorageService.ComponentCoordinates("", "lib", "2.0");

        Race.run(THREADS, i -> () -> storage.store(repo, "lib/2.0/lib-2.0.tgz", bytes("tgz " + i), null,
                Asset.Origin.UPLOADED, false, coordinates));

        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(1);
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        Asset asset = assets.findByRepositoryIdAndPath(repo.getId(), "lib/2.0/lib-2.0.tgz").orElseThrow();
        assertThat(asset.getComponentId())
                .isEqualTo(components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "lib", "2.0")
                        .orElseThrow().getId());
    }

    @RepeatedTest(3)
    void ensureComponentHandsEveryCallerTheSameRow() throws Exception {
        Repository repo = hosted("race-hosted", "raw");
        var coordinates = new StorageService.ComponentCoordinates("org.acme", "core", "3.1");

        List<UUID> ids = Race.run(THREADS, i -> () -> storage.ensureComponent(repo, coordinates).getId());

        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
    }

    @RepeatedTest(3)
    void deletingTheLastAssetWhileOthersJoinTheComponentLeavesNoDanglingLink() throws Exception {
        Repository repo = hosted("race-hosted", "raw");
        var coordinates = new StorageService.ComponentCoordinates("", "pkg", "1.0");
        storage.store(repo, "pkg/1.0/first", bytes("first"), null, Asset.Origin.UPLOADED, false, coordinates);

        List<Boolean> outcomes = Race.run(THREADS, i -> () -> i % 2 == 0
                ? storage.delete(repo, "pkg/1.0/first")
                : storage.store(repo, "pkg/1.0/other-" + i, bytes("other " + i), null, Asset.Origin.UPLOADED, false,
                        coordinates) != null);

        assertThat(outcomes.stream().filter(b -> b).count()).isEqualTo(THREADS / 2 + 1); // one delete won, every store
        assertThat(storage.exists(repo, "pkg/1.0/first")).isFalse();
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(1);
        UUID componentId = components.findByRepositoryIdAndNamespaceAndNameAndVersion(repo.getId(), "", "pkg", "1.0")
                .orElseThrow().getId();
        assertThat(storage.list(repo, "pkg/1.0/")).hasSize(THREADS / 2)
                .allSatisfy(a -> assertThat(a.getComponentId()).isEqualTo(componentId));
    }

    // --- proxy single flight ---

    @RepeatedTest(3)
    void concurrentMissesOfTheSameArtifactCauseOneUpstreamRequest() throws Exception {
        upstream.stubFor(WireMock.get(urlEqualTo("/remote/lib/x-1.0.jar"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(400).withBody("JAR-BYTES")));
        Repository repo = proxy("race-proxy", "raw", upstream.baseUrl() + "/remote/");

        List<Optional<StoredContent>> results = Race.run(THREADS,
                i -> () -> proxy.fetch(repo, "lib/x-1.0.jar", FetchOptions.artifact()));

        for (Optional<StoredContent> r : results) {
            assertThat(r).isPresent();
            assertThat(r.get().readUtf8()).isEqualTo("JAR-BYTES");
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/lib/x-1.0.jar")));
        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(1);
        assertThat(proxy.inFlightFetches()).isZero();
        assertThat(locks.heldKeys()).isZero();
    }

    @RepeatedTest(3)
    void concurrentRevalidationsOfStaleMetadataCauseOneUpstreamRequest() throws Exception {
        upstream.stubFor(WireMock.get(urlEqualTo("/remote/index.json"))
                .willReturn(aResponse().withStatus(200).withBody("{\"v\":1}")));
        Repository repo = proxy("race-proxy", "raw", upstream.baseUrl() + "/remote/");
        StoredContent first = proxy.fetch(repo, "index.json", FetchOptions.index()).orElseThrow();
        Asset aged = assets.findById(first.asset().getId()).orElseThrow();
        aged.setLastVerifiedAt(Instant.now().minus(Duration.ofHours(3)));
        assets.save(aged);
        upstream.resetAll();
        upstream.stubFor(WireMock.get(urlEqualTo("/remote/index.json"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(400).withBody("{\"v\":2}")));

        List<Optional<StoredContent>> results = Race.run(THREADS, i -> () -> proxy.fetch(repo, "index.json", FetchOptions.index()));

        for (Optional<StoredContent> r : results) {
            assertThat(r.orElseThrow().readUtf8()).isEqualTo("{\"v\":2}");
        }
        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/index.json")));
        assertThat(proxy.inFlightFetches()).isZero();
    }

    @RepeatedTest(3)
    void waitersGetTheLeadersOutcomeWhenNothingCouldBeCached() throws Exception {
        upstream.stubFor(WireMock.get(urlEqualTo("/remote/missing.jar"))
                .willReturn(aResponse().withStatus(404).withFixedDelay(300)));
        Repository repo = proxy("race-proxy", "raw", upstream.baseUrl() + "/remote/");

        List<Optional<StoredContent>> results = Race.run(THREADS, i -> () -> proxy.fetch(repo, "missing.jar", FetchOptions.artifact()));

        assertThat(results).allSatisfy(r -> assertThat(r).isEmpty());
        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/missing.jar")));
        assertThat(proxy.inFlightFetches()).isZero();
    }

    @Test
    void waitersThatTimeOutFetchOnTheirOwnAndStillSucceed() throws Exception {
        properties.getProxy().setSingleFlightTimeout(Duration.ofMillis(100));
        upstream.stubFor(WireMock.get(urlEqualTo("/remote/slow.bin"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(1500).withBody("SLOW")));
        Repository repo = proxy("race-proxy", "raw", upstream.baseUrl() + "/remote/");

        List<Optional<StoredContent>> results = Race.run(8, i -> () -> proxy.fetch(repo, "slow.bin", FetchOptions.artifact()));

        assertThat(results).allSatisfy(r -> assertThat(r.orElseThrow().readUtf8()).isEqualTo("SLOW"));
        // nobody waited longer than the time-out: the waiters went upstream themselves
        assertThat(upstream.countRequestsMatching(getRequestedFor(urlEqualTo("/remote/slow.bin")).build()).getCount())
                .isGreaterThan(1);
        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(1);
        assertThat(proxy.inFlightFetches()).isZero();
        assertThat(locks.heldKeys()).isZero();
    }

    @Test
    void anInterruptedWaiterStopsWaitingAndKeepsItsInterruptFlag() throws Exception {
        upstream.stubFor(WireMock.get(urlEqualTo("/remote/slow.bin"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(1500).withBody("SLOW")));
        Repository repo = proxy("race-proxy", "raw", upstream.baseUrl() + "/remote/");
        CompletableFuture<Optional<StoredContent>> leader =
                CompletableFuture.supplyAsync(() -> proxy.fetch(repo, "slow.bin", FetchOptions.artifact()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (proxy.inFlightFetches() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(proxy.inFlightFetches()).isEqualTo(1);

        AtomicReference<Optional<StoredContent>> waiterResult = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            waiterResult.set(proxy.fetch(repo, "slow.bin", FetchOptions.artifact()));
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        Thread.sleep(200);
        waiter.interrupt();
        waiter.join(1000);

        assertThat(waiter.isAlive()).isFalse();
        assertThat(waiterResult.get()).isEmpty();
        assertThat(stillInterrupted).isTrue();
        assertThat(leader.get(10, TimeUnit.SECONDS).orElseThrow().readUtf8()).isEqualTo("SLOW");
        upstream.verify(1, getRequestedFor(urlEqualTo("/remote/slow.bin")));
        assertThat(proxy.inFlightFetches()).isZero();
    }

    /**
     * A fetch inside a caller's transaction cannot publish its outcome before that transaction commits, so it must not
     * take part in single flight: the second fetch of the same file in the same transaction would otherwise wait for
     * its own flight until the time-out.
     */
    @Test
    void aFetchRepeatedInsideTheCallersTransactionDoesNotWaitForItself() throws Exception {
        properties.getProxy().setSingleFlightTimeout(Duration.ofSeconds(30));
        upstream.stubFor(WireMock.get(urlEqualTo("/remote/live.json"))
                .willReturn(aResponse().withStatus(200).withBody("{\"live\":true}")));
        Repository repo = proxy("race-proxy", "raw", upstream.baseUrl() + "/remote/");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Optional<StoredContent> second = CompletableFuture.supplyAsync(() -> transaction.execute(status -> {
            proxy.fetch(repo, "live.json", FetchOptions.index(Duration.ZERO));
            return proxy.fetch(repo, "live.json", FetchOptions.index(Duration.ZERO));
        })).get(15, TimeUnit.SECONDS);

        assertThat(second.orElseThrow().readUtf8()).isEqualTo("{\"live\":true}");
        upstream.verify(2, getRequestedFor(urlEqualTo("/remote/live.json")));
        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(1);
        assertThat(proxy.inFlightFetches()).isZero();
        assertThat(locks.heldKeys()).isZero();
    }

    @Test
    void concurrentClientsThroughTheHttpSurfaceShareOneUpstreamRequest() throws Exception {
        String jar = "com/acme/race/1.0/race-1.0.jar";
        upstream.stubFor(WireMock.get(urlEqualTo("/maven2/" + jar))
                .willReturn(aResponse().withStatus(200).withFixedDelay(400)
                        .withHeader("Content-Type", "application/java-archive").withBody("remote-jar")));
        upstream.stubFor(WireMock.get(urlEqualTo("/maven2/" + jar + ".sha1")).willReturn(aResponse().withStatus(404)));
        proxy("central", "maven2", upstream.baseUrl() + "/maven2/");

        List<MvcResult> results = Race.run(THREADS, i -> () -> mvc.perform(get("/repository/central/" + jar)).andReturn());

        assertThat(results).allSatisfy(r -> {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).isEqualTo("remote-jar");
        });
        upstream.verify(1, getRequestedFor(urlEqualTo("/maven2/" + jar)));
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
