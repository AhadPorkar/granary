package dev.granary.server.storage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.granary.core.dao.AssetDao;
import dev.granary.core.dao.ComponentDao;
import dev.granary.core.model.Asset;
import dev.granary.core.model.Repository;
import dev.granary.core.storage.StorageLocks;
import dev.granary.core.storage.StorageService;
import dev.granary.server.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A request must never hold one pooled database connection while it waits for a second one: with as many such
 * requests in flight as the pool has connections, every one of them waits for the others until the pool time-out
 * fails them all with a 500. The pool here has two connections, so a handful of concurrent requests is already
 * more than the pool, and every connection the application takes is counted per thread.
 */
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=5000"})
class ConnectionUsageIntegrationTest extends IntegrationTest {

    static final int REQUESTS = 16;

    @Autowired
    CountingDataSource dataSource;

    @Autowired
    StorageService storage;

    @Autowired
    StorageLocks locks;

    @Autowired
    AssetDao assets;

    @Autowired
    ComponentDao components;

    WireMockServer upstream;
    String token;

    @BeforeEach
    void setUp() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        token = tokens.issue(users.require(ADMIN), "ci", null).secret();
        dataSource.reset();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop();
    }

    @Test
    void everyWayOfPresentingAnApiTokenUsesOneConnectionAtATime() throws Exception {
        hosted("conn-raw", "raw");
        mvc.perform(put("/repository/conn-raw/docs/a.txt").header("Authorization", "Bearer " + token).content("a"))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/conn-raw/docs/a.txt").header("Authorization", basic(ADMIN, token)))
                .andExpect(status().isOk());
        mvc.perform(get("/repository/conn-raw/docs/a.txt").header("X-Api-Key", token))
                .andExpect(status().isOk());
        mvc.perform(get("/repository/conn-raw/docs/a.txt").header("Authorization", token))
                .andExpect(status().isOk());
        mvc.perform(get("/repository/conn-raw/docs/a.txt").header("Authorization", adminBasic()))
                .andExpect(status().isOk());

        assertThat(dataSource.maxHeldByOneThread()).isEqualTo(1);
    }

    @Test
    void uploadsDownloadsAndProxyFetchesUseOneConnectionAtATime() throws Exception {
        hosted("conn-maven", "maven2");
        upstream.stubFor(WireMock.get(urlEqualTo("/m2/org/acme/lib/1.0/lib-1.0.jar"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"v1\"").withBody("remote-jar")));
        proxy("conn-central", "maven2", upstream.baseUrl() + "/m2/");

        mvc.perform(put("/repository/conn-maven/com/acme/app/1.0/app-1.0.jar")
                        .header("Authorization", "Bearer " + token).content("jar"))
                .andExpect(status().isCreated());
        mvc.perform(get("/repository/conn-maven/com/acme/app/1.0/app-1.0.jar").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/repository/conn-central/org/acme/lib/1.0/lib-1.0.jar")).andExpect(status().isOk());
        mvc.perform(get("/repository/conn-central/org/acme/lib/1.0/lib-1.0.jar")).andExpect(status().isOk());

        assertThat(dataSource.maxHeldByOneThread()).isEqualTo(1);
    }

    @Test
    void moreConcurrentTokenRequestsThanConnectionsAllSucceed() throws Exception {
        hosted("conn-raw", "raw");
        mvc.perform(put("/repository/conn-raw/docs/a.txt").header("Authorization", "Bearer " + token).content("a"))
                .andExpect(status().isCreated());

        List<MvcResult> results = Race.run(REQUESTS, i -> () -> mvc.perform(get("/repository/conn-raw/docs/a.txt")
                .header("Authorization", i % 2 == 0 ? "Bearer " + token : basic(ADMIN, token))).andReturn());

        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
        assertThat(dataSource.maxHeldByOneThread()).isEqualTo(1);
    }

    /**
     * {@code store} takes the path's key before it opens its transaction. If the other writers of a path opened their
     * transaction first and then waited for the key, a few of them would hold every connection while the key holder
     * waits for one.
     */
    @RepeatedTest(3)
    void moreWritersOfOnePathThanConnectionsAllSucceed() throws Exception {
        Repository repo = hosted("conn-hosted", "raw");
        UUID id = storage.store(repo, "pkg/index.json", bytes("v0"), "application/json", Asset.Origin.CACHED, true)
                .asset().getId();

        List<Object> results = Race.run(REQUESTS, i -> () -> switch (i % 4) {
            case 0 -> storage.store(repo, "pkg/index.json", bytes("v" + i), "application/json", Asset.Origin.CACHED, true);
            case 1 -> storage.touchVerified(assets.findById(id).orElseThrow(), "\"e" + i + "\"", null);
            case 2 -> storage.recordUpstreamValidators(assets.findById(id).orElseThrow(), "\"e" + i + "\"", null);
            default -> storage.save(assets.findById(id).orElseThrow());
        });

        assertThat(results).hasSize(REQUESTS).doesNotContainNull();
        assertThat(assets.countByRepositoryId(repo.getId())).isEqualTo(1);
        assertThat(locks.heldKeys()).isZero();
    }

    @RepeatedTest(3)
    void moreDeletesAndStoresOfOnePathThanConnectionsAllSucceed() throws Exception {
        Repository repo = hosted("conn-hosted", "raw");
        var coordinates = new StorageService.ComponentCoordinates("", "pkg", "1.0");
        storage.store(repo, "pkg/1.0/pkg.tgz", bytes("v0"), null, Asset.Origin.UPLOADED, false, coordinates);

        List<Object> results = Race.run(REQUESTS, i -> () -> i % 2 == 0
                ? storage.delete(repo, "pkg/1.0/pkg.tgz")
                : storage.store(repo, "pkg/1.0/pkg.tgz", bytes("v" + i), null, Asset.Origin.UPLOADED, false, coordinates));

        assertThat(results).hasSize(REQUESTS);
        long remaining = assets.countByRepositoryId(repo.getId());
        assertThat(remaining).isLessThanOrEqualTo(1);
        // the component lives exactly as long as its file: the last delete removed both, a later store recreated both
        assertThat(components.countByRepositoryId(repo.getId())).isEqualTo(remaining);
        assertThat(locks.heldKeys()).isZero();
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @TestConfiguration
    static class CountingDataSourceConfig {

        @Bean
        static BeanPostProcessor countConnections() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource ds && !(bean instanceof CountingDataSource)
                            ? new CountingDataSource(ds) : bean;
                }
            };
        }
    }

    /** Counts how many connections each thread holds at the same time and remembers the highest count. */
    static final class CountingDataSource extends DelegatingDataSource {

        private final ThreadLocal<AtomicInteger> held = ThreadLocal.withInitial(AtomicInteger::new);
        private final AtomicInteger max = new AtomicInteger();

        CountingDataSource(DataSource target) {
            super(target);
        }

        int maxHeldByOneThread() {
            return max.get();
        }

        void reset() {
            max.set(0);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return counted(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return counted(super.getConnection(username, password));
        }

        private Connection counted(Connection connection) {
            AtomicInteger mine = held.get();
            max.accumulateAndGet(mine.incrementAndGet(), Math::max);
            AtomicBoolean closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close") && closed.compareAndSet(false, true)) {
                            mine.decrementAndGet();
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
