package dev.granary.server.storage;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The concurrency scenarios on PostgreSQL, which aborts a whole transaction after a failed statement and so would
 * expose any "insert, catch the duplicate, re-read" shortcut. Skipped automatically without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("postgres")
class StorageConcurrencyPostgresIntegrationTest extends StorageConcurrencyScenarios {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            System.getProperty("granary.test.postgresImage", "postgres:17-alpine"));
}
