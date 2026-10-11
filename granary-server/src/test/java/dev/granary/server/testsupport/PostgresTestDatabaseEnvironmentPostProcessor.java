package dev.granary.server.testsupport;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Points every Spring test context at a fresh PostgreSQL database instead of its in-memory H2 database when the
 * build runs with {@code -Dgranary.test.database=postgres} (Maven profile {@code postgres}). Without that property
 * nothing changes. Runs after the configuration files are loaded so its values win over {@code application-test.yml}.
 */
public class PostgresTestDatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE = "granaryTestPostgres";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String requested = environment.getProperty(PostgresTestDatabase.SWITCH, System.getProperty(PostgresTestDatabase.SWITCH));
        if (!PostgresTestDatabase.requested(requested) || environment.getPropertySources().contains(PROPERTY_SOURCE)) {
            return;
        }
        PostgresTestDatabase server = PostgresTestDatabase.server();
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, Map.of(
                "spring.datasource.url", server.createDatabase(),
                "spring.datasource.username", server.username(),
                "spring.datasource.password", server.password())));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
