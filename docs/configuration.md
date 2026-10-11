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
| `granary.storage.write-lock-timeout` | | `30s` | How long a write waits for a concurrent write of the same path or component before it gives up with `409 storage.conflict` |

## Blob store compaction

| Property | Env | Default | Meaning |
|----------|-----|---------|---------|
| `granary.storage.compaction.enabled` | `GRANARY_COMPACTION_ENABLED` | `true` | Whether the scheduled compaction runs. Compaction on demand is always available |
| `granary.storage.compaction.schedule` | `GRANARY_COMPACTION_SCHEDULE` | `0 30 3 * * *` | Spring cron expression (second, minute, hour, day, month, weekday) in the server's time zone; the default is daily at 03:30 |
| `granary.storage.compaction.grace-period` | `GRANARY_COMPACTION_GRACE_PERIOD` | `24h` | Unreferenced blobs written or re-used more recently than this are kept. Zero or positive; a negative value stops the server from starting |

See [Operations](#operations) below for what compaction does.

## Security

| Property | Env | Default | Meaning |
|----------|-----|---------|---------|
| `granary.security.admin-password` | `GRANARY_ADMIN_PASSWORD` | `admin123` | Password given to the built-in `admin` when the database is first created |
| `granary.security.anonymous-read-default` | `GRANARY_ANONYMOUS_READ` | `true` | Initial value of the *anonymous read* setting |
| `granary.security.jwt-secret` | `GRANARY_JWT_SECRET` | generated into `<data-dir>/jwt.secret` | HMAC key for web session tokens; set it when the data directory is not kept across restarts (Granary runs as a single node, see [Operations](#operations)) |
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
| `granary.proxy.single-flight-timeout` | `5m` | Concurrent requests for the same uncached file wait this long for the one request that fetches it from the upstream, then fetch on their own. Global only |

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

## Operations

### Single node

Granary runs as one process. Authentication is stateless, but the coordination of writes is
not: concurrent uploads of the same path or component, concurrent proxy fetches of the same file
and blob store compaction are serialised inside the JVM. Do not run two instances against the
same database and blob store directory. If it happens anyway, the database constraints still
keep the data consistent and a colliding write is answered with `409 storage.conflict`, which
clients retry, but compaction on one instance can then race an upload on the other.

### Blob store compaction

Blobs are stored once per content (SHA-256) and shared by every asset, in any repository, that
has the same bytes. Deleting an asset, a component or a whole repository therefore only removes
database rows; the files stay on disk until the blob store is **compacted**. Compaction walks the
store's directory and deletes each blob that

1. no asset of a repository using that blob store references any more, and
2. was last written longer ago than the grace period (`granary.storage.compaction.grace-period`,
   24 hours by default).

Uploads in progress are safe whatever the grace period. An upload streams into the store's `tmp/`
directory, which compaction never touches, and then moves its blob into place before its database
row is committed (it may wait for a concurrent upload of the same path in between, up to
`granary.storage.write-lock-timeout`); the server keeps that blob out of compaction's reach until
the row is committed or the upload has failed. Uploading bytes that are already stored also
refreshes the file's modification time. The grace period is the safety margin for anything the
server cannot track that way, so keep the default unless you need the space back sooner; a short
grace period (minutes) is fine for a one-off compaction.

Only the store's own layout is read: `content/<2 hex digits>/<2 hex digits>/<SHA-256>`. Other
files and directories under `content/` (for example `lost+found` when it is a mount point) are
left alone, and so are leftover `tmp/upload-*.part` files of uploads interrupted by a crash, which
you can delete while the server is stopped. Empty fan-out directories are removed along the way.
Compaction runs:

* **on a schedule**, daily at 03:30 by default (`granary.storage.compaction.schedule`), switched
  off with `GRANARY_COMPACTION_ENABLED=false`;
* **on demand**, from the dashboard (*Compact* next to each blob store, shown to users with the
  `system:admin` or `settings:manage` privilege) or through the API:

  ```bash
  curl -X POST -u admin:… http://localhost:8081/api/v1/blob-stores/default/compact
  ```

  ```json
  {"blobStore": "default", "startedAt": "2026-10-10T03:30:00Z", "durationMillis": 1840,
   "gracePeriod": "PT24H", "blobsScanned": 120734, "blobsDeleted": 312, "bytesReclaimed": 734003200}
  ```

  An unknown store answers `404 blobStore.notFound`. Only one compaction per store runs at a
  time; a second request while one is running answers `409 blobStore.compaction.running`.

The work is proportional to the number of blobs: the directory is streamed and the references
are checked in batches of 500 digests, so memory use does not grow with the store. Two configured
stores that point at the same directory, also through a symbolic link or a bind mount, are treated
as one store: a blob is kept while any repository of either references it, only one compaction of
the directory runs at a time, and the scheduled run compacts it once. Because the decision depends
on which repositories use a store, a repository's blob store can only be changed while the
repository is empty (`400 repository.blobstore.immutable`). A repository whose blob store is no
longer configured (renamed or removed in the configuration) cannot be served, but its files are
kept: compaction does not know which directory holds them, so their references count for every
store until the configuration names the store again (the server log warns on each compaction).

The request returns when compaction has finished. On a very large store that can take longer than
a reverse proxy in front of Granary waits for a response; the compaction then still completes on
the server (the server log has the report), and a new request meanwhile answers
`409 blobStore.compaction.running`.

### Limits on stored values

Repository paths are limited to 2,000 characters and, encoded as UTF-8, to 2,600 bytes (the most a
PostgreSQL index entry holds; only paths of mostly non-ASCII characters reach it); a longer path
is answered with `400 path.invalid` before anything is stored or fetched. A component's
namespace, name and version share the same 2,600-byte limit (`400 component.invalid`). Upstream response headers are cleaned
before they are cached: control characters are removed from `Content-Type` and, when the value
is still not a valid media type of at most 200 characters, its parameters are dropped or the
type is guessed from the file name. An `ETag` longer than 500 characters, a `Last-Modified`
longer than 100 characters, or either one with control characters is not stored at all (never
truncated, since a truncated validator would never match), so the next re-validation of that
file is unconditional.

