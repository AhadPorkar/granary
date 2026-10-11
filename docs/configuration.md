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
| `granary.storage.blob-stores.<name>.path` | | `<data-dir>/blobs-<name>` | Additional blob store directories, selectable per repository (the `default` store is `<data-dir>/blobs`) |
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
| `granary.security.jwt-secret` | `GRANARY_JWT_SECRET` | generated into `<data-dir>/jwt.secret` | HMAC key for web session tokens, at least 32 bytes; set it when the data directory is not kept across restarts (Granary runs as a single node, see [Operations](#operations)) |
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
| `granary.proxy.user-agent` | `Granary/0.2 (+https://github.com/AhadPorkar/granary)` | Sent to upstreams |
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
| `baseUrl` | Public URL used in generated links, at most 2,000 characters |

## Repositories

Repositories are created and changed in the UI or with `POST /api/v1/repositories` and
`PUT /api/v1/repositories/{name}` (privilege `repositories:manage`). The JSON body:

| Field | Applies to | Meaning |
|-------|------------|---------|
| `name` | all | Unique across all formats; letters, digits, `.`, `_`, `-`, starting with a letter or digit, at most 100 characters. Part of the URL `/repository/<name>/` |
| `format` | all | A format id from `GET /api/v1/formats` (`maven2`, `npm`, `docker`, …) |
| `type` | all | `HOSTED`, `PROXY` or `GROUP`; a format may support fewer (vcpkg: hosted only) |
| `online` | all | `false` answers every request with `503 repository.offline` |
| `blobStore` | all | Named blob store (default `default`); fixed once the repository holds content |
| `description` | all | Free text shown in the UI, at most 500 characters |
| `allowRedeploy` | hosted | Allow replacing an existing version or file (otherwise `409` or the protocol's own refusal) |
| `versionPolicy` | Maven | `RELEASE`, `SNAPSHOT` or `MIXED` |
| `remoteUrl` | proxy | Upstream URL (absolute `http(s)`), at most 1,000 characters |
| `remoteUsername` / `remotePassword` / `remoteToken` | proxy | Upstream credentials: Basic, or a token sent as `Bearer` (at most 200 / 500 / 1,000 characters); sent only to the origin of `remoteUrl`, see [Upstream credentials](#upstream-credentials) |
| `connectTimeoutSeconds`, `readTimeoutSeconds`, `metadataMaxAgeMinutes`, `negativeCacheMinutes` | proxy | Override the [proxy defaults](#proxy-defaults) |
| `offlineMode` | proxy | Serve this proxy from its cache only |
| `members` | group | Member names in resolution order; same format, nested groups allowed, no cycles |
| `formatSettings` | all | Format-specific settings, an object of string values (see below) |

```bash
curl -u admin:… -H 'Content-Type: application/json' -X POST http://localhost:8081/api/v1/repositories -d '{
  "name": "crates-mirror", "format": "cargo", "type": "PROXY",
  "remoteUrl": "https://index.crates.io/",
  "formatSettings": {"cargo.apiUrl": "none"}
}'
```

`POST /api/v1/repositories/standard` creates the standard repositories every installed format
declares (proxies of the public registries, a hosted repository and a group per format; see
[docs/formats](formats/README.md)) and skips names that already exist.

### Format settings

Each format declares its settings (`GET /api/v1/formats` lists them with type, default and the
repository types they apply to); the UI shows them as fields of the repository form. Values are
strings, a blank value means the default, and invalid values are refused with `400` when the
repository is saved. The format guides explain each one in context.

| Key | Format | Applies to | Default | Meaning |
|-----|--------|------------|---------|---------|
| `cargo.authRequired` | [cargo](formats/cargo.md#settings) | all | `false` | Advertise `auth-required` so cargo sends its token with every request |
| `cargo.downloadUrl` | [cargo](formats/cargo.md#settings) | proxy | `https://static.crates.io/crates/{crate}/{crate}-{version}.crate` | Where the upstream keeps `.crate` files |
| `cargo.apiUrl` | [cargo](formats/cargo.md#settings) | proxy | `https://crates.io` | Upstream web API for `cargo search`; `none` searches the cache |
| `cocoapods.rewriteHttpSources` | [cocoapods](formats/cocoapods.md#settings) | proxy, group | `true` | Serve `http` source archives through Granary and cache them |
| `composer.distRewrite` | [composer](formats/composer.md#settings) | proxy | `true` | Serve dist archives through Granary and cache them |
| `conan.tokenTtlHours` | [conan](formats/conan.md#settings) | all | `24` | Lifetime of the token a password login creates; `0` never expires |
| `conan.upstreamAuth` | [conan](formats/conan.md#settings) | proxy | `token` | `token` (log in once, then Bearer) or `basic` towards the upstream |
| `docker.rootRepository` | [docker](formats/docker.md#settings) | all | `false` | Serve this repository at `/v2/` of the server (see [Root routing](#root-routing)) |
| `docker.libraryPrefix` | [docker](formats/docker.md#settings) | proxy | `true` for Docker Hub, else `false` | Request single-segment names upstream as `library/<name>` |
| `docker.challengeAnonymous` | [docker](formats/docker.md#settings) | all | `true` | Answer an anonymous `GET /v2/` with a Basic challenge |
| `go.sumdbName` | [go](formats/go.md#settings) | proxy | `sum.golang.org` | Checksum database passed through `sumdb/<name>/…` |
| `go.sumdbUrl` | [go](formats/go.md#settings) | proxy | `https://sum.golang.org` | Where checksum-database requests are forwarded |
| `helm.relativeUrls` | [helm](formats/helm.md#settings) | all | `false` | Chart URLs in `index.yaml` relative to the index |
| `helm.maxChartSizeMb` | [helm](formats/helm.md#settings) | hosted | `20` | Largest chart upload in MiB; `0` disables the limit |
| `hex.repositoryName` | [hex](formats/hex.md#settings) | hosted, group | the repository name | Repository name inside the signed registry documents |
| `hex.revertWindowMinutes` | [hex](formats/hex.md#settings) | hosted | `60` | How long a release may be reverted; `0` means always |
| `hex.verifyUpstreamSignatures` | [hex](formats/hex.md#settings) | group | `true` | Check proxy members' signatures before merging and re-signing |
| `pub.maxArchiveSizeMb` | [pub](formats/pub.md#settings) | hosted | `100` | Largest archive `dart pub publish` may upload, in MiB |
| `pub.advisories` | [pub](formats/pub.md#settings) | proxy | `true` | Pass the upstream's security advisories through |
| `pypi.filesUrl` | [pypi](formats/pypi.md#settings) | proxy | `https://files.pythonhosted.org/` | Files host whose links are cached under their own path |
| `raw.metadataPatterns` | [raw family](formats/raw.md#settings) | all | per format | Globs of paths that are mutable metadata |
| `raw.contentDisposition` | [raw family](formats/raw.md#settings) | all | `inline` | `inline` or `attachment` downloads |
| `raw.listingEnabled` | [raw family](formats/raw.md#settings) | all | `true` | Directory listings for paths ending with `/` |
| `rubygems.signin` | [rubygems](formats/rubygems.md#settings) | all | `true` | Let `gem signin` exchange a password for an API token |
| `rubygems.maxGemSizeMb` | [rubygems](formats/rubygems.md#settings) | hosted | `1024` | Largest `.gem` `gem push` may upload, in MiB |
| `swift.requireSignature` | [swift](formats/swift.md#settings) | hosted | `false` | Refuse unsigned releases |
| `swift.maxArchiveSizeMb` | [swift](formats/swift.md#settings) | hosted | no limit | Largest source archive `swift package-registry publish` may upload, in MiB |
| `terraform.rootRepository` | [terraform](formats/terraform.md#settings) | all | `false` | Answer `/.well-known/terraform.json` of the server (see [Root routing](#root-routing)) |
| `terraform.providerProtocols` | [terraform](formats/terraform.md#settings) | hosted | `5.0,6.0` | Provider protocol versions announced for hosted providers |
| `upstream.credentialHosts` | every format | proxy | empty | Further hosts that receive the proxy's upstream credentials (see [Upstream credentials](#upstream-credentials)) |

The `raw.*` settings apply to all eight formats of the raw module (`raw`, `cdn`, `cpan`, `cran`,
`hackage`, `vcpkg`, `bower`, `elm`). `upstream.credentialHosts` is not a format's own setting: Granary
adds it to the proxy repositories of every format. Maven, npm and NuGet have no other format settings.

### Upstream credentials

A proxy's upstream credentials (`remoteUsername`/`remotePassword` or `remoteToken`) are sent only to
the *origin* of its `remoteUrl`: the same scheme, host and port. Upstream documents often name files
on other hosts (chart and provider downloads on GitHub or `releases.hashicorp.com`, crates on
`static.crates.io`, wheels on `files.pythonhosted.org`, nuget.org's search service, Elm zips on
`github.com`); those requests go without credentials. The rule is enforced for every format and every
upstream request:

* Origins are compared in a normalised form: scheme and host ignore case, an omitted port is the
  scheme's default (`https://host` and `https://host:443` are one origin, `http://host` is another),
  internationalised host names are compared in their punycode form, the trailing dot of a fully
  qualified name is ignored, user information in a URL plays no part, and IPv6 addresses are compared
  in canonical form. An IPv4 address only matches in its dotted-quad form, and a URL whose host
  cannot be read that way never receives credentials.
* Granary follows redirects itself (at most five) and decides again for every hop: a redirect to
  another origin is followed without the credentials (unless that host is listed below), and
  without any `Authorization` header a format added for the first request (a registry token, say).
  A redirect from `https` to `http` is not followed.
* A plain-`http` request never carries the credentials of a proxy whose `remoteUrl` is `https`, not
  even to a host listed below.

Some deployments need more: an internal mirror whose downloads live on a second internal host. The
repository setting `upstream.credentialHosts`, offered for the proxy repositories of every format, lists
further hosts that receive the credentials, separated by commas:

| Entry | Receives the credentials |
|-------|--------------------------|
| `files.example.com` | that host on the default port of the request's scheme (443, or 80 for `http`) |
| `files.example.com:8443` | that host on that port |
| `*.example.com` | every subdomain of `example.com` (`a.example.com`, `a.b.example.com`) on the default port, not `example.com` itself |
| `*.example.com:8443` | every subdomain on that port |
| `[fd00::5]`, `[fd00::5]:8443`, `10.0.0.5` | an IP address, on the default or the given port |

Entries name hosts only: a scheme, a path, user information or a `*` anywhere but the first label is
refused with `400 repository.upstream.credentialHosts.invalid` when the repository is saved. An
internationalised name may be written in Unicode only when it has one unambiguous ASCII form; a name
that the older and the current IDNA rules read as different hosts (with `ß`, a final `ς` or a
zero-width joiner, for example `faß.de`, which would otherwise mean `fass.de`) is refused and must be
written in its `xn--` form. Leave
the setting empty unless the upstream really needs it; the public registries never do.

The one deliberate exception is the token service of a container registry: when a registry answers
with a `WWW-Authenticate: Bearer realm="…"` challenge, the Docker proxy sends its credentials to that
realm, because that is how the registry protocol logs in (Docker Hub's realm is `auth.docker.io`, not
`registry-1.docker.io`). The registry itself then only sees the token, and a CDN its blob downloads
redirect to sees neither. A token is only sent back to the registry whose realm issued it: when the
remote URL or the credentials change, a new one is requested. Only the registry itself names the
realm: when its `/v2/` redirects to a host that may not receive the credentials, the challenge that
host answers is ignored. The Conan proxy's login (`v2/users/authenticate`) is an ordinary request to
the upstream's own origin, and the token it obtains follows the same rule as the credentials.

### Root routing

Docker and Terraform clients need URLs at the server root (see
[ADR 0012](adr/0012-root-routing.md)):

* `/v2/…` is answered by the docker repository with `docker.rootRepository` set, else the first
  docker group, else the first docker repository (by name); `/v2/<repository>/<image>` reaches any
  docker repository by name. The standard `docker-group` is flagged.
* `/.well-known/terraform.json` is answered by the Terraform repository with
  `terraform.rootRepository` set, else the first Terraform group, else the first Terraform
  repository. The standard `terraform-group` is flagged.

Flag at most one repository per format. A reverse proxy in front of Granary must pass `/v2/` and
`/.well-known/terraform.json` through to it, not only `/repository/` and `/api/`, and Granary must
know its public URL (`GRANARY_BASE_URL` or the `baseUrl` runtime setting) because both protocols
write absolute URLs.

## Observability

`/actuator/health` (with `liveness` and `readiness` groups) and `/actuator/info` are public;
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
namespace, name and version share the same 2,600-byte limit (`400 component.invalid`).

Text stored through the REST API (repository descriptions and remote credentials, user and role
fields, token names, the base URL) is checked against its column length and must not contain a NUL
character, which PostgreSQL cannot store: a violation answers `400 validation` with the field
named in `details` (`400 token.name.invalid` for a token name), the same on H2 and PostgreSQL. A user name, search term or other lookup key
with a NUL character simply matches nothing (`401` for a login).

Upstream response headers are cleaned
before they are cached: control characters are removed from `Content-Type` and, when the value
is still not a valid media type of at most 200 characters, its parameters are dropped or the
type is guessed from the file name. An `ETag` longer than 500 characters, a `Last-Modified`
longer than 100 characters, or either one with control characters is not stored at all (never
truncated, since a truncated validator would never match), so the next re-validation of that
file is unconditional.

