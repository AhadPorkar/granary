# Granary

[![CI](https://github.com/AhadPorkar/granary/actions/workflows/ci.yml/badge.svg)](https://github.com/AhadPorkar/granary/actions/workflows/ci.yml)
[![License: BUSL-1.1](https://img.shields.io/badge/license-BUSL--1.1-blue.svg)](LICENSE)
[![Java 25](https://img.shields.io/badge/Java-25%20LTS-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f.svg)](https://spring.io/projects/spring-boot)

**Granary is an offline-first artifact repository manager**: a single server that hosts your
own packages, proxies and caches the public registries your teams depend on, and keeps serving
every cached artifact when the internet connection is slow, metered or gone.

It speaks the native protocols of the package managers, so `mvn`, `gradle`, `npm`, `pnpm`,
`yarn`, `dotnet` and `nuget` work unchanged. The web UI is available in English, German,
French, Turkish and Persian (right-to-left).

```
   developers ──▶  Granary  ──▶  Maven Central · npmjs.org · nuget.org · your own registries
                     │
                     └── every artifact ever fetched stays on disk and is served even when upstream is unreachable
```

## Why Granary

* **Works when the network does not.** Immutable artifacts are cached forever. Index documents
  (Maven metadata, npm packuments, NuGet search results) are refreshed when upstream answers and
  served from cache when it does not. A per-repository circuit breaker stops a dead upstream from
  slowing every build down, and a global *offline mode* switch turns the whole server into a
  pure cache for the duration of an outage.
* **No artificial limits.** Any number of repositories, formats, users, tokens and concurrent
  connections; the only limits are your disk and your CPU.
* **One binary, zero configuration.** `java -jar granary.jar` starts with an embedded
  database. `docker compose up` gives you PostgreSQL and persistent volumes.
* **Hosted, proxy and group repositories** for every format, exactly as teams expect from a
  repository manager: publish to *hosted*, cache from *proxy*, consume from one *group* URL.
* **Real security.** Users, roles, fine-grained privileges per repository, API tokens that work
  as Basic passwords, Bearer tokens or NuGet API keys, optional anonymous read.
* **Tested like infrastructure.** Unit tests for every module and end-to-end protocol tests that
  drive the server the way the real clients do (see [Testing](#testing)).

## Supported formats

| Format | Hosted | Proxy | Group | Clients verified |
|--------|:------:|:-----:|:-----:|------------------|
| Maven 2 (`maven2`) | ✔ releases / snapshots / mixed | ✔ | ✔ merged `maven-metadata.xml` | Maven 3.9, Gradle |
| npm (`npm`) | ✔ publish, dist-tags, deprecate, scoped packages | ✔ | ✔ merged packuments | npm 10, pnpm, yarn |
| NuGet V3 (`nuget`) | ✔ push, delete/unlist, symbols ignored safely | ✔ | ✔ merged search / registration | dotnet 8, nuget.exe, Visual Studio |

Planned next: PyPI, Docker/OCI, Go modules, Helm, raw (generic files), APT, Conda, Composer,
RubyGems, Cargo, Hugging Face. The format layer is a small SPI (see
[docs/architecture.md](docs/architecture.md)), so a new format is one module with no changes to
the server.

## Quick start

### Docker Compose (recommended)

```bash
git clone https://github.com/AhadPorkar/granary.git
cd granary
docker compose up -d
```

Open <http://localhost:8081> and sign in with `admin` / `admin123`. Change the password on the
first login (or set `GRANARY_ADMIN_PASSWORD` before the first start).

The Compose file builds the image locally. To use the published image instead:

```bash
GRANARY_IMAGE=ghcr.io/ahadporkar/granary:latest docker compose up -d --no-build
```

Behind a company proxy or with Docker Hub unreachable, point the base images at a mirror:

```bash
JRE_IMAGE=mirror.example.com/eclipse-temurin:25-jre-alpine \
MAVEN_IMAGE=mirror.example.com/maven:3.9-eclipse-temurin-25 \
NODE_IMAGE=mirror.example.com/node:22-alpine \
POSTGRES_IMAGE=mirror.example.com/postgres:17-alpine docker compose up -d --build
```

### Plain Java

Requires JDK 25 and Node 22 (only to build the UI).

```bash
mvn -B package -DskipTests
java -jar granary-server/target/granary.jar
```

State lives in `./data` (set `GRANARY_DATA_DIR` to move it). The embedded H2 database is fine
for a team; switch to PostgreSQL for larger installations with
`SPRING_PROFILES_ACTIVE=postgres` and `SPRING_DATASOURCE_URL`.

## First repositories

On a fresh server the UI offers a one-click **"Create standard repositories"** action, or do it
over the API:

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}' | jq -r .token)

api() { curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }

api -X POST localhost:8081/api/v1/repositories -d '{"name":"maven-central","format":"maven2","type":"PROXY","remoteUrl":"https://repo1.maven.org/maven2/"}'
api -X POST localhost:8081/api/v1/repositories -d '{"name":"maven-releases","format":"maven2","type":"HOSTED","versionPolicy":"RELEASE"}'
api -X POST localhost:8081/api/v1/repositories -d '{"name":"maven-snapshots","format":"maven2","type":"HOSTED","versionPolicy":"SNAPSHOT"}'
api -X POST localhost:8081/api/v1/repositories -d '{"name":"maven-public","format":"maven2","type":"GROUP","members":["maven-releases","maven-snapshots","maven-central"]}'

api -X POST localhost:8081/api/v1/repositories -d '{"name":"npm-proxy","format":"npm","type":"PROXY","remoteUrl":"https://registry.npmjs.org/"}'
api -X POST localhost:8081/api/v1/repositories -d '{"name":"npm-hosted","format":"npm","type":"HOSTED"}'
api -X POST localhost:8081/api/v1/repositories -d '{"name":"npm-group","format":"npm","type":"GROUP","members":["npm-hosted","npm-proxy"]}'

api -X POST localhost:8081/api/v1/repositories -d '{"name":"nuget.org","format":"nuget","type":"PROXY","remoteUrl":"https://api.nuget.org/v3/index.json"}'
api -X POST localhost:8081/api/v1/repositories -d '{"name":"nuget-hosted","format":"nuget","type":"HOSTED"}'
api -X POST localhost:8081/api/v1/repositories -d '{"name":"nuget-group","format":"nuget","type":"GROUP","members":["nuget-hosted","nuget.org"]}'
```

## Connecting the clients

Replace `granary.example.com:8081` with your server. Create an API token in the UI
(*Account → API tokens*) rather than using your password in config files.

### Maven

`~/.m2/settings.xml`:

```xml
<settings>
  <mirrors>
    <mirror>
      <id>granary</id>
      <mirrorOf>*</mirrorOf>
      <url>http://granary.example.com:8081/repository/maven-public/</url>
    </mirror>
  </mirrors>
  <servers>
    <server><id>granary</id><username>jane</username><password>gry_…</password></server>
    <server><id>granary-releases</id><username>jane</username><password>gry_…</password></server>
    <server><id>granary-snapshots</id><username>jane</username><password>gry_…</password></server>
  </servers>
  <!-- Maven only resolves snapshots from repositories that enable them; Central (which the mirror replaces) does not. -->
  <profiles>
    <profile>
      <id>granary</id>
      <repositories>
        <repository>
          <id>granary</id>
          <url>http://granary.example.com:8081/repository/maven-public/</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>granary</id>
          <url>http://granary.example.com:8081/repository/maven-public/</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>granary</activeProfile></activeProfiles>
</settings>
```

`pom.xml` for deploying:

```xml
<distributionManagement>
  <repository><id>granary-releases</id><url>http://granary.example.com:8081/repository/maven-releases/</url></repository>
  <snapshotRepository><id>granary-snapshots</id><url>http://granary.example.com:8081/repository/maven-snapshots/</url></snapshotRepository>
</distributionManagement>
```

Gradle: `maven { url = uri("http://granary.example.com:8081/repository/maven-public/"); isAllowInsecureProtocol = true }`.

### npm, pnpm, yarn

```bash
npm config set registry http://granary.example.com:8081/repository/npm-group/
npm login --registry=http://granary.example.com:8081/repository/npm-hosted/ --auth-type=legacy   # or put an API token in .npmrc:
echo "//granary.example.com:8081/repository/npm-hosted/:_authToken=gry_…" >> ~/.npmrc
npm publish --registry=http://granary.example.com:8081/repository/npm-hosted/
```

### NuGet / .NET

```bash
dotnet nuget add source http://granary.example.com:8081/repository/nuget-group/index.json -n granary
dotnet nuget push MyLib.1.0.0.nupkg -s http://granary.example.com:8081/repository/nuget-hosted/index.json -k gry_…
```

For Visual Studio, add the same `index.json` URL under *Tools → Options → NuGet Package Manager → Package Sources*.

## Offline behaviour

| Situation | What Granary does |
|-----------|-------------------|
| Artifact cached, upstream down | Served from cache immediately; nothing waits for the network. |
| Index document cached, older than *metadata max age*, upstream down | Served stale, with a warning in the logs; refreshed on the next successful contact. |
| Artifact not cached, upstream down | `503 Service Unavailable` with a JSON body naming the repository and the upstream failure. |
| Upstream failed `failureThreshold` times | Circuit breaker opens: requests go straight to cache; a single probe retries with exponential back-off (30 s → 10 min). |
| Upstream answered `404` | Remembered for *negative cache TTL* so a thousand builds do not hammer a dead URL. |
| *Offline mode* switched on (UI, API or `system.offlineMode`) | No upstream calls at all, globally or per repository. |

The whole cache is content addressed (SHA-256) and deduplicated, so the same jar proxied through
two repositories or uploaded twice is stored once.

## Configuration

Every setting is an environment variable, a JVM flag (`--granary.data-dir=…`) or a line in an
`application.yml` next to the jar. The most useful ones:

| Variable | Default | Meaning |
|----------|---------|---------|
| `GRANARY_DATA_DIR` | `./data` (`/data` in Docker) | Blobs, embedded DB, generated JWT secret |
| `GRANARY_ADMIN_PASSWORD` | `admin123` | Password of the built-in `admin` on first start |
| `GRANARY_ANONYMOUS_READ` | `true` | Whether unauthenticated clients may download |
| `GRANARY_BASE_URL` | request-derived | Public URL when behind a reverse proxy (affects npm tarball and NuGet links) |
| `GRANARY_JWT_SECRET` | generated | HMAC secret for web sessions; set it when running more than one instance |
| `SPRING_PROFILES_ACTIVE` | | `postgres` to use PostgreSQL |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | | JDBC settings for PostgreSQL |
| `SERVER_PORT` | `8081` | HTTP port |
| `GRANARY_HTTP_THREADS` / `GRANARY_HTTP_MAX_CONNECTIONS` | `200` / `20000` | Tomcat sizing |
| `GRANARY_JAVA_OPTS` | | Extra JVM flags in Docker Compose (e.g. `-Dhttps.proxyHost=…` for an outbound proxy) |

The full list, including proxy time-outs and circuit-breaker thresholds, is in
[docs/configuration.md](docs/configuration.md).

## Security model

* Privileges are strings: `system:admin`, `repositories:manage`, `users:manage`,
  `settings:manage`, `repository:read:<name|*>`, `repository:write:<name|*>`.
* Built-in roles: `admin`, `developer` (read/write everything), `reader`, `anonymous`.
* Users may carry any number of roles; roles are editable; API tokens inherit the user's
  privileges and can expire.
* Credentials are accepted as HTTP Basic (password or token), `Authorization: Bearer <token>`
  and `X-NuGet-ApiKey`, because that is what the clients send. Web sessions are short-lived JWTs.
* Passwords are stored with bcrypt, tokens as SHA-256 hashes; neither is recoverable from the database.

## Architecture

```mermaid
flowchart LR
    subgraph Clients
        mvn[Maven / Gradle]
        npm[npm / pnpm / yarn]
        dotnet[dotnet / nuget]
        browser[Web UI]
    end
    subgraph Granary
        dispatch[/repository/{name}/**\nRepositoryDispatchController/]
        api[/api/v1/**\nREST API/]
        maven[Maven handler]
        npmh[npm handler]
        nugeth[NuGet handler]
        resolver[ContentResolver\nhosted · proxy · group]
        proxy[ProxyService\ncache · stale · circuit breaker]
        storage[StorageService]
        blob[(Content-addressed\nblob store)]
        db[(H2 / PostgreSQL\nmetadata)]
    end
    upstream[(Upstream registries)]

    mvn & npm & dotnet --> dispatch
    browser --> api
    dispatch --> maven & npmh & nugeth --> resolver --> proxy --> upstream
    resolver --> storage --> blob & db
    api --> storage
```

Details, the format SPI and the reasoning behind the main decisions are in
[docs/architecture.md](docs/architecture.md) and [docs/adr](docs/adr).

## Testing

```bash
mvn -B verify          # everything, including the UI unit tests (needs Node) and JaCoCo coverage
mvn -B -Dui.skip=true verify   # server only
```

* `granary-core`: blob store, proxy cache (against WireMock), security and resolver unit tests.
* `granary-format-*`: protocol-level unit tests for path parsing, version ordering, metadata
  merging and the index documents each client expects.
* `granary-server`: MockMvc integration tests that run the full HTTP stack against an embedded
  database: deploy and resolve, checksums, snapshots, group merging, offline fallbacks,
  authentication in every credential shape, the admin API and the SPA fallback.
* `granary-ui`: Vitest unit tests for the stores, API client, locales and views.
* CI additionally starts the Docker Compose stack and performs a Maven round trip against it.

## Development

```
granary-core           domain model, storage, proxy cache, security, format SPI
granary-format-maven   Maven 2 protocol
granary-format-npm     npm registry protocol
granary-format-nuget   NuGet V3 protocol
granary-ui             Vue 3 + Vite single-page application (en, de, fr, tr, fa)
granary-server         Spring Boot application: HTTP, REST API, security filter, packaging
```

Requirements: JDK 25, Maven 3.9, Node 22, Docker (for the Compose smoke test). Commit messages
follow [Conventional Commits](https://www.conventionalcommits.org/).

## Roadmap

1. More formats: raw, PyPI, Docker/OCI, Helm, Go, APT, Conda, Composer, RubyGems, Cargo.
2. Cleanup policies and blob-store compaction.
3. Full-text search across formats and a browse tree with download statistics.
4. REST API parity with the UI for every setting, plus an OpenAPI document.
5. Scheduled backup/restore and S3-compatible blob stores.
6. LDAP / OIDC sign-in.

## Licence

Granary is source-available under the [Business Source License 1.1](LICENSE): free for
development, evaluation, education and open-source projects; a
[commercial licence](COMMERCIAL-LICENSE.md) is required for other production use. Each
version becomes available under the Mozilla Public License 2.0 four years after its release.
