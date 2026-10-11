package dev.granary.server.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The PostgreSQL server the test suite runs on when {@code granary.test.database=postgres} is set (the Maven
 * profile {@code -Ppostgres} sets it). Every Spring test context gets a database of its own, created empty, so
 * contexts never see each other's rows, just as each context gets its own in-memory H2 database by default.
 *
 * <p>The server is either one PostgreSQL container shared by the whole test JVM (image
 * {@code granary.test.postgresImage}, default {@code postgres:17-alpine}), or, when
 * {@code granary.test.postgres.url} names a JDBC URL, an existing server (for example
 * {@code docker run --network host postgres:17-alpine}); the per-context databases are then created on it and
 * dropped again when the JVM exits.
 */
public final class PostgresTestDatabase {

    /** System property switching the test suite from H2 to PostgreSQL. */
    public static final String SWITCH = "granary.test.database";

    private static final Pattern JDBC_URL = Pattern.compile("^(jdbc:postgresql://[^/?]+/)([^?]*)(\\?.*)?$");

    private static volatile PostgresTestDatabase instance;

    private final String urlPrefix;
    private final String urlSuffix;
    private final String adminUrl;
    private final String username;
    private final String password;
    private final boolean external;
    private final AtomicInteger counter = new AtomicInteger();
    private final List<String> created = new ArrayList<>();

    private PostgresTestDatabase(String adminUrl, String username, String password, boolean external) {
        Matcher m = JDBC_URL.matcher(adminUrl);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a PostgreSQL JDBC URL: " + adminUrl);
        }
        this.urlPrefix = m.group(1);
        this.urlSuffix = m.group(3) == null ? "" : m.group(3);
        this.adminUrl = adminUrl;
        this.username = username;
        this.password = password;
        this.external = external;
        if (external) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::dropCreated, "granary-test-postgres-cleanup"));
        }
    }

    /** True when the build asked for the PostgreSQL test database. */
    public static boolean requested(String value) {
        return value != null && value.trim().equalsIgnoreCase("postgres");
    }

    /** The shared server, started on first use. */
    public static PostgresTestDatabase server() {
        PostgresTestDatabase current = instance;
        if (current == null) {
            synchronized (PostgresTestDatabase.class) {
                current = instance;
                if (current == null) {
                    current = start();
                    instance = current;
                }
            }
        }
        return current;
    }

    private static PostgresTestDatabase start() {
        String url = System.getProperty("granary.test.postgres.url", "").trim();
        if (!url.isEmpty()) {
            return new PostgresTestDatabase(url,
                    System.getProperty("granary.test.postgres.username", "postgres"),
                    System.getProperty("granary.test.postgres.password", "postgres"), true);
        }
        PostgreSQLContainer container = new PostgreSQLContainer(
                System.getProperty("granary.test.postgresImage", "postgres:17-alpine"));
        // a handful of contexts with their own connection pools stay alive in the test context cache
        container.setCommand("postgres", "-c", "max_connections=400", "-c", "fsync=off",
                "-c", "synchronous_commit=off", "-c", "full_page_writes=off");
        container.start();
        return new PostgresTestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword(), false);
    }

    /** Creates a new empty database and returns its JDBC URL. */
    public synchronized String createDatabase() {
        String name = "granary_test_" + ProcessHandle.current().pid() + "_" + counter.incrementAndGet();
        execute("CREATE DATABASE " + name);
        created.add(name);
        return urlPrefix + name + urlSuffix;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    private synchronized void dropCreated() {
        for (String name : created) {
            try {
                execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            } catch (RuntimeException ignored) {
                // best effort while the JVM shuts down
            }
        }
        created.clear();
    }

    private void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL test database: " + sql + " failed" + (external ? " on " + adminUrl : ""), e);
        }
    }
}
