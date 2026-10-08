# Configuration

Granary is configured like any Spring Boot application: environment variables, JVM system
properties (`-Dgranary.data-dir=/srv/granary`), command-line flags (`--server.port=9000`) or an
`application.yml` placed next to the jar (or under `config/`). Environment variables use the
relaxed binding, so `granary.proxy.metadata-max-age` is `GRANARY_PROXY_METADATAMAXAGE` or
`GRANARY_PROXY_METADATA_MAX_AGE`.

## Core

| Property | Env | Default | Meaning |
|----------|-----|---------|---------|
| `server.port` | `SERVER_PORT` | `8081` | HTTP port |
| `granary.data-dir` | `GRANARY_DATA_DIR` | `./data` | Root for blobs, H2 database, generated JWT secret |
| `granary.base-url` | `GRANARY_BASE_URL` | derived from the request | Public URL when behind a reverse proxy. Overridden at runtime by the `system.baseUrl` setting |
| `granary.storage.blob-stores.<name>` | | | Additional blob store directories, selectable per repository |

## Security

| Property | Env | Default | Meaning |
|----------|-----|---------|---------|
| `granary.security.admin-password` | `GRANARY_ADMIN_PASSWORD` | `admin123` | Password given to the built-in `admin` when the database is first created |
| `granary.security.anonymous-read-default` | `GRANARY_ANONYMOUS_READ` | `true` | Initial value of the *anonymous read* setting |
| `granary.security.jwt-secret` | `GRANARY_JWT_SECRET` | generated into `<data-dir>/jwt.secret` | HMAC key for web session tokens; share it between instances |
| `granary.security.session-token-ttl` | | `12h` | Lifetime of a web session |

## Proxy defaults

Each proxy repository can override these; blank repository values fall back to the globals.

| Property | Default | Meaning |
|----------|---------|---------|
| `granary.proxy.connect-timeout` | `10s` | TCP/TLS connect time-out to the upstream |
| `granary.proxy.read-timeout` | `60s` | Time-out waiting for upstream bytes |
| `granary.proxy.metadata-max-age` | `30m` | How long an index document is served without re-validation |
| `granary.proxy.negative-cache-ttl` | `10m` | How long an upstream `404` is remembered |
| `granary.proxy.failure-threshold` | `3` | Consecutive failures that open the circuit breaker |
| `granary.proxy.circuit-open-min` / `-max` | `30s` / `10m` | Back-off window while the breaker is open |
| `granary.proxy.user-agent` | `Granary/<version>` | Sent to upstreams |

Outbound HTTP proxies are configured with the standard JVM flags (`-Dhttps.proxyHost`,
`-Dhttps.proxyPort`, `-Dhttp.nonProxyHosts`), e.g. through `GRANARY_JAVA_OPTS` in Docker Compose.

## Database

| Property | Env | Default |
|----------|-----|---------|
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | (none) → embedded H2 under the data dir |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/granary` with the `postgres` profile |
| `spring.datasource.username` / `password` | `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `granary` / `granary` |
| `spring.datasource.hikari.maximum-pool-size` | `GRANARY_DB_POOL_SIZE` | `20` |

Schema migrations run automatically at start-up (Flyway).

## HTTP server

| Env | Default | Meaning |
|-----|---------|---------|
| `GRANARY_HTTP_THREADS` | `200` | Tomcat worker threads (virtual threads are enabled, so this is rarely the limit) |
| `GRANARY_HTTP_MAX_CONNECTIONS` | `20000` | Maximum concurrent connections |

Upload size is unlimited; request bodies are streamed to the blob store and never buffered in
memory.

## Runtime settings (UI → Settings, or `PUT /api/v1/system/settings`)

| Key | Meaning |
|-----|---------|
| `anonymousRead` | Whether unauthenticated clients may download from repositories |
| `offlineMode` | Global switch: no upstream requests at all |
| `baseUrl` | Public URL used in generated links |

## Observability

`/actuator/health` (with `liveness` and `readiness` groups) is public; `/actuator/info`,
`/actuator/metrics` and `/actuator/prometheus` require the `system:admin` privilege. Logs go to
stdout in a single-line format suitable for Docker.
