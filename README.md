# Granary

[![CI](https://github.com/AhadPorkar/granary/actions/workflows/ci.yml/badge.svg)](https://github.com/AhadPorkar/granary/actions/workflows/ci.yml)
[![License: BUSL-1.1](https://img.shields.io/badge/license-BUSL--1.1-blue.svg)](LICENSE)
[![Java 25](https://img.shields.io/badge/Java-25%20LTS-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f.svg)](https://spring.io/projects/spring-boot)

**Granary is an offline-first artifact repository manager**: a single server that hosts your
own packages, proxies and caches the public registries your teams depend on, and keeps serving
every cached artifact when the internet connection is slow, metered or gone.

It speaks the native protocols of 24 package formats, so the clients work unchanged: `mvn`,
`gradle`, `npm`, `pnpm`, `yarn`, `dotnet` and `nuget`; `pip`, `uv`, `poetry` and `twine`;
`docker`, `podman`, `containerd`, `oras` and `helm`; `go`, `cargo`, `conan`, `mix` and `rebar3`,
`composer`, `dart pub` and `flutter pub`, `swift package`, `gem` and `bundle`, `pod`;
`terraform` and `tofu`; `cpanm`, R, `cabal` and `stack`, `vcpkg`, `bower`, `elm`, and plain
`curl` for generic files. The web UI is available in English, German, French, Turkish and
Persian (right-to-left).

```
   developers ──▶  Granary  ──▶  Maven Central · npmjs.org · nuget.org · PyPI · Docker Hub · crates.io · … · your own registries
                     │
                     └── every artifact ever fetched stays on disk and is served even when upstream is unreachable
```

## Why Granary

* **Works when the network does not.** Immutable artifacts are cached forever. Index documents
  (Maven metadata, npm packuments, PyPI project pages, Cargo index files, Docker tags, …) are
  refreshed when upstream answers and served from cache when it does not. A per-repository
  circuit breaker stops a dead upstream from slowing every build down, and a global *offline
  mode* switch turns the whole server into a pure cache for the duration of an outage.
* **No artificial limits.** Any number of repositories, formats, users, tokens and concurrent
  connections; the only limits are your disk and your CPU.
* **One binary, zero configuration.** `java -jar granary.jar` starts with an embedded
  database. `docker compose up` gives you PostgreSQL and persistent volumes.
* **Hosted, proxy and group repositories** for every format (vcpkg's caches are hosted only),
  exactly as teams expect from a repository manager: publish to *hosted*, cache from *proxy*,
  consume from one *group* URL.
* **Real security.** Users, roles, fine-grained privileges per repository, API tokens that work
  in whatever shape a client sends them (Basic password, Bearer, `Token`, a bare token, an
  API-key header), optional anonymous read.
* **Ready-made client configuration.** Every repository's *Connect* panel shows the snippets for
  its clients with the server's URL and your user name filled in.
* **Tested like infrastructure.** Unit tests for every module and end-to-end protocol tests that
  drive the server the way the real clients do (see [Testing](#testing)).

## Supported formats

| Format | Hosted | Proxy | Group | Clients verified |
|--------|:------:|:-----:|:-----:|------------------|
| Maven 2 (`maven2`) | ✔ releases / snapshots / mixed | ✔ | ✔ merged `maven-metadata.xml` | Maven 3.9, Gradle 8.14 |
| npm (`npm`) | ✔ publish, dist-tags, deprecate, scoped packages | ✔ | ✔ merged packuments | npm 10, pnpm 10, Yarn 1.22, Bun 1.4 |
| NuGet V3 (`nuget`) | ✔ push, delete, symbol packages | ✔ | ✔ merged search / registration | dotnet 8 SDK |
| PyPI (`pypi`) | ✔ uploads from twine, uv, Hatch, Flit, Poetry; PEP 503/691 index, PEP 658 metadata, JSON API | ✔ | ✔ merged project pages and JSON API | pip 26, uv 0.11, Poetry 2.4, PDM 2.29, Pipenv 2026.8, twine 7, Hatch 1.18, Flit 4.1 |
| Docker / OCI (`docker`) | ✔ push/pull, chunked and mount uploads, OCI artifacts, referrers | ✔ Docker Hub, ghcr.io, quay.io and other token registries | ✔ merged tags and catalog, served at `/v2/` | docker 29, podman 4.9, buildah 1.33, skopeo 1.13, containerd 2.x, nerdctl 2.0, oras 1.2, crane 0.20, helm 3.16 |
| Go modules (`go`) | ✔ module zips by `PUT`, checked like the go command checks them | ✔ with `GOSUMDB` passthrough | ✔ merged `@v/list` and `@latest` | go 1.24, 1.25 |
| Cargo (`cargo`) | ✔ sparse index, publish, yank, owners, search, token login | ✔ | ✔ merged index files, hosted first | cargo 1.97, 1.70 |
| Helm (`helm`) | ✔ ChartMuseum API and `PUT` uploads, provenance files, generated `index.yaml` | ✔ | ✔ merged `index.yaml` | helm 3.16, 3.19, 4.2, helm-push 0.10.4, helmfile 1.8 |
| Terraform / OpenTofu (`terraform`) | ✔ providers and modules by `PUT`, network mirror | ✔ registry.terraform.io, registry.opentofu.org | ✔ merged versions, root discovery | terraform 1.13, tofu 1.10 |
| Conan (`conan`) | ✔ recipe and package revisions, metadata files, checksum deploy, token login | ✔ ConanCenter, conan_server, Artifactory | ✔ merged revisions and searches | conan 2.32, conan 1.66 (revisions) |
| Hex (`hex`) | ✔ publish, revert, retire, docs; signed registry | ✔ | ✔ merged and re-signed registry | mix 1.18 (Hex 2.5.1), rebar3 3.27 (rebar3_hex 7.3) |
| Composer (`composer`) | ✔ zip uploads, `p2` metadata, search, advisories | ✔ dist archives cached | ✔ merged `p2`, hosted first | composer 2.8 |
| pub (`pub`) | ✔ publish, retract, discontinue | ✔ | ✔ merged listings and advisories | dart pub 3.13, flutter pub 3.47 |
| Swift (`swift`) | ✔ publish (signed releases), manifest variants, identifier lookups | ✔ another Swift registry | ✔ merged release lists | SwiftPM 6.1 |
| RubyGems (`rubygems`) | ✔ `gem push`, yank, signin; compact and Marshal indexes | ✔ | ✔ merged compact index and `specs.4.8.gz` | gem 3.5, Bundler 4.0 and 2.5 |
| CocoaPods (`cocoapods`) | ✔ podspec uploads with source archives, generated shard indexes | ✔ cdn.cocoapods.org, `http` sources cached | ✔ merged indexes | pod 1.17 |
| Raw (`raw`) | ✔ uploads, listings, `Range` | ✔ | ✔ merged listings | curl 8.5, wget 1.21 |
| Browser CDN assets (`cdn`) | ✔ | ✔ cdnjs, jsDelivr, unpkg | ✔ | curl 8.5 (cache and offline paths) |
| CPAN (`cpan`) | ✔ generated `02packages` | ✔ | ✔ merged `02packages` | cpanm 1.7049 |
| CRAN (`cran`) | ✔ generated `PACKAGES` | ✔ | ✔ merged `PACKAGES` | R 4.5.1 |
| Hackage / Stackage (`hackage`) | ✔ files (no index generation) | ✔ TUF metadata passed through | ✔ | cabal 3.14, stack 3.3 |
| vcpkg (`vcpkg`) | ✔ asset and binary caches | – | – | vcpkg (tool built from source, 2026-10) |
| Bower (`bower`) | ✔ register, lookup, search | ✔ | ✔ merged packages and search | bower 1.8.14 |
| Elm (`elm`) | ✔ generated `all-packages` and `endpoint.json` | ✔ GitHub zips cached | ✔ append-only release list | elm 0.19.1 |

Each format has a guide in [docs/formats](docs/formats/README.md) with its endpoints, standard
repositories, client configuration, settings, offline behaviour and limitations.

## Registry coverage

Which public registry each format covers and which repository a client points at, after
`POST /api/v1/repositories/standard`. Repository URLs are `http(s)://<host>/repository/<name>/`.
"Verified" means the real client published to or installed through Granary for this release,
including an offline reinstall from an empty client cache; the network policy of the build
machine blocked some upstreams, which are named in the notes.

| Registry | Format | Point the client at | Verified with | Notes |
|----------|--------|---------------------|---------------|-------|
| npmjs.org (npm, Yarn, pnpm, Bun) | `npm` | `npm-group` | npm 10, pnpm 10, Yarn 1.22, Bun 1.4 | Yarn 2+ has a Connect snippet but was not run |
| cdnjs, jsDelivr, unpkg | `cdn` | `cdnjs`, `jsdelivr`, `unpkg` (one proxy each) | curl: cache, offline and `503` paths | All three CDNs were blocked here: no live fetch. Browsers send no tokens, so keep anonymous read on |
| Elm packages | `elm` | `elm-proxy`, behind a host override and TLS (the compiler hard-codes `package.elm-lang.org`) | elm 0.19.1 against a hosted repository | `package.elm-lang.org` blocked here: proxy not exercised live |
| Bower | `bower` | `bower-registry` | bower 1.8.14 against a hosted registry | `registry.bower.io` blocked here; packages are git clones, which Granary does not cache |
| PyPI (pip, Poetry, uv, Pipenv) | `pypi` | `pypi-group/simple/` | pip 26, uv 0.11, Poetry 2.4, Pipenv 2026.8, PDM, twine, Hatch, Flit | |
| Packagist | `composer` | `composer-group` | composer 2.8 | Dist archives on GitHub were blocked here: archive caching covered by WireMock tests only |
| RubyGems.org | `rubygems` | `rubygems-group` | gem 3.5, Bundler 4.0 and 2.5 | |
| crates.io | `cargo` | `sparse+…/cargo-group/` | cargo 1.97 and 1.70 | Sparse protocol only (cargo 1.68+) |
| Go module proxy | `go` | `go-group` as `GOPROXY` | go 1.24 and 1.25, `GOSUMDB` through Granary | |
| nuget.org | `nuget` | `nuget-group/index.json` | dotnet 8 SDK | nuget.exe, Visual Studio and Paket read the same V3 feed but were not run (Linux build machine); no V2 feed |
| Maven Central | `maven2` | `maven-public` | Maven 3.9, Gradle 8.14 | The Gradle Plugin Portal (`gradle-plugins`) is in the same group and was resolved by Gradle |
| Clojars | `maven2` | `maven-public` (proxy `clojars`) | – | `repo.clojars.org` blocked here: not exercised live |
| Google Maven | `maven2` | `maven-public` (proxy `maven-google`) | – | `maven.google.com` redirects to `dl.google.com`, blocked here: not exercised live |
| ConanCenter | `conan` | `conan-group` | conan 2.32 and 1.66, against hosted, a conan_server upstream and a chained proxy | `center2.conan.io` blocked here: ConanCenter itself not exercised live |
| vcpkg | `vcpkg` | `vcpkg-cache` as asset cache (`assets/`) and binary cache (`binaries/`) | vcpkg tool built from source | Port registries stay git repositories; Granary caches sources and binaries only |
| Hex.pm | `hex` | `hex-group`, or `hexpm` as `HEX_MIRROR` | mix 1.18, rebar3 3.27, against hosted, group and a local stand-in for hex.pm | `repo.hex.pm` blocked here: hex.pm itself not exercised live |
| Hackage / Stackage | `hackage` | `hackage-proxy` (cabal); `stackage-proxy` and `stackage-snapshots` (stack) | cabal 3.14 and stack 3.3 against Hackage through Granary | `www.stackage.org` blocked here: `stackage-proxy` not exercised live. Hosted Hackage repositories generate no index |
| CPAN | `cpan` | `cpan-proxy` | cpanm 1.7049 | |
| CRAN | `cran` | `cran-proxy` | R 4.5.1 against hosted and group repositories | `cloud.r-project.org` blocked here: proxy not exercised live |
| pub.dev | `pub` | `pub-group` as `PUB_HOSTED_URL` | dart pub 3.13, flutter pub 3.47 | |
| Swift package registry | `swift` | `swift-group` (`swift package-registry set`, HTTPS only) | SwiftPM 6.1 | No public Swift registry exists to proxy (the Swift Package Index is a catalogue); a proxy can mirror another Swift registry |
| CocoaPods trunk | `cocoapods` | `cocoapods-group` as Podfile `source` | pod 1.17 | Trunk podspecs come from `cdn.jsdelivr.net`, blocked here; git sources are not cached; no git spec repos or trunk API |
| Docker Hub | `docker` | the server itself: `host:8081/<image>` (`docker-group` answers `/v2/`) | docker 29, podman, buildah, skopeo, containerd/nerdctl, kubelet (kind), oras, crane, helm (OCI) | |
| Quay.io | `docker` | `host:8081/<image>` through the group, or `host:8081/quay/<image>` | – | `quay.io` blocked here: not exercised live |
| GHCR | `docker` | `host:8081/<image>` through the group, or `host:8081/ghcr/<image>` | manifests only | Layer blobs on GitHub's CDN were blocked here, so no full pull |
| Artifact Hub (Helm) | `helm` | `helm-group`, plus one Helm proxy per chart repository listed on Artifact Hub (created by hand); OCI charts through `docker-group` | helm 3.16, 3.19, 4.2 with proxies of two public chart repositories | Artifact Hub is a catalogue, not a registry: there is no Artifact Hub proxy. Listing a Granary repository on Artifact Hub was not tested |
| Terraform / OpenTofu registry | `terraform` | the Granary host over HTTPS (`terraform-group` answers `/.well-known/terraform.json`); `tofu` with unchanged sources: network mirror on `opentofu-registry` | terraform 1.13, tofu 1.10, against a local stand-in serving the real `hashicorp/null` releases | The public registries were blocked here. Keep anonymous read on: the CLIs send no token for archive downloads |
| Generic (universal) files | `raw` | `raw-hosted` (`PUT` / `GET` with curl) | curl 8.5, wget 1.21 | Proxies and groups of any HTTP file server are created by hand |

Not supported yet: APT and YUM/RPM repositories (their files can be kept in a raw repository, but
no indexes are generated), Conda, Hugging Face, git spec repositories and the trunk API of
CocoaPods, the git index of Cargo, Composer 1, the NuGet V2 (OData) feed and Conan without
revisions. The format layer is a small SPI (see [docs/architecture.md](docs/architecture.md)), so
a new format is one module with no changes to the server.

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

Requires JDK 25 and Maven 3.9. The build downloads its own Node 22 into `granary-ui/target` to
build the UI (`-Dui.skip=true` builds without the UI).

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

api -X POST localhost:8081/api/v1/repositories/standard | jq '.created | length'   # 65 on an empty server
```

That creates the standard repositories of every installed format, usually a proxy of the public
registry, a hosted repository for your own packages and a group that joins them (for example
`maven-central`, `maven-releases`, `maven-snapshots` and `maven-public`; `npm-proxy`, `npm-hosted`
and `npm-group`; `docker-hub`, `ghcr`, `quay`, `docker-hosted` and `docker-group`). Names that
already exist are left alone, so the call is safe to repeat. The full list is in
[docs/formats](docs/formats/README.md).

Any other repository is one call. A Helm proxy, for example (Helm has no standard proxy because
public charts live in one repository per project), added to the standard group:

```bash
api -X POST localhost:8081/api/v1/repositories -d '{"name":"ingress-nginx","format":"helm","type":"PROXY",
  "remoteUrl":"https://raw.githubusercontent.com/kubernetes/ingress-nginx/gh-pages"}'
# PUT replaces the whole definition: send every field you want to keep
api -X PUT localhost:8081/api/v1/repositories/helm-group -d '{"name":"helm-group","format":"helm","type":"GROUP",
  "members":["helm-hosted","ingress-nginx"]}'
```

The fields, including the format-specific `formatSettings`, are described in
[docs/configuration.md](docs/configuration.md#repositories).

## Connecting the clients

Every repository page in the web UI has a **Connect** panel: the configuration for that
repository's clients, rendered by the server with the repository URL and your user name filled in
(for example `settings.xml`, Gradle, sbt and Leiningen for Maven; `.npmrc`, pnpm, Yarn and Bun for
npm; `pip.conf`, uv, Poetry and `.pypirc` for PyPI; `docker login`, `registries.conf` and
containerd's `hosts.toml` for Docker). Replace `<api-token>` with an API token from
*My account → API tokens* rather than using your password in config files. The same snippets are
available from `GET /api/v1/repositories/{name}/connect`.

| Format | Guide |
|--------|-------|
| Maven, Gradle, sbt, Leiningen | [maven.md](docs/formats/maven.md) |
| npm, pnpm, Yarn, Bun | [npm.md](docs/formats/npm.md) |
| NuGet, dotnet, Visual Studio, Paket | [nuget.md](docs/formats/nuget.md) |
| PyPI | [pypi.md](docs/formats/pypi.md) |
| Docker / OCI | [docker.md](docs/formats/docker.md) |
| Go modules | [go.md](docs/formats/go.md) |
| Cargo | [cargo.md](docs/formats/cargo.md) |
| Helm | [helm.md](docs/formats/helm.md) |
| Terraform / OpenTofu | [terraform.md](docs/formats/terraform.md) |
| Conan | [conan.md](docs/formats/conan.md) |
| Hex (mix, rebar3) | [hex.md](docs/formats/hex.md) |
| Composer | [composer.md](docs/formats/composer.md) |
| pub (Dart, Flutter) | [pub.md](docs/formats/pub.md) |
| Swift | [swift.md](docs/formats/swift.md) |
| RubyGems, Bundler | [rubygems.md](docs/formats/rubygems.md) |
| CocoaPods | [cocoapods.md](docs/formats/cocoapods.md) |
| Raw, CDN, CPAN, CRAN, Hackage, vcpkg, Bower, Elm | [raw.md](docs/formats/raw.md) |

Replace `granary.example.com:8081` with your server in the examples below.

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
| Artifact not cached, upstream down | `503 upstream.unavailable` with a JSON body naming the repository and the reason, so clients retry later instead of remembering a `404`. Through a group, the same applies when no member has the file and a proxy member's upstream is down. |
| Artifact not cached, offline mode on | `503 upstream.offline`. |
| Many clients ask for the same uncached file at once | One upstream request (single flight); the others wait for it and are served from the cache. |
| Repository switched off (*online* unchecked) | `503 repository.offline` for every download and upload. |
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
| `GRANARY_ANONYMOUS_READ` | `true` | Whether unauthenticated clients may download, until the setting is changed in the UI or API |
| `GRANARY_BASE_URL` | request-derived | Public URL when behind a reverse proxy (used in every absolute link Granary writes: npm tarballs, NuGet and PyPI links, Docker `Location` headers, Terraform discovery, …) |
| `GRANARY_JWT_SECRET` | generated | HMAC secret for web sessions; set it when the data directory is not kept across restarts |
| `SPRING_PROFILES_ACTIVE` | | `postgres` to use PostgreSQL |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | | JDBC settings for PostgreSQL |
| `SERVER_PORT` | `8081` | HTTP port |
| `GRANARY_HTTP_THREADS` / `GRANARY_HTTP_MAX_CONNECTIONS` | `200` / `20000` | Tomcat sizing |
| `GRANARY_JAVA_OPTS` | | Extra JVM flags in Docker Compose (e.g. `-Dhttps.proxyHost=…` for an outbound proxy) |
| `GRANARY_COMPACTION_SCHEDULE` / `_GRACE_PERIOD` | `0 30 3 * * *` / `24h` | When blob store compaction deletes blobs nothing references any more, and how old they must be |

The full list, including proxy time-outs, circuit-breaker thresholds and every format setting,
is in [docs/configuration.md](docs/configuration.md).

A reverse proxy in front of Granary must pass `/v2/` (Docker) and `/.well-known/terraform.json`
(Terraform) through as well as `/repository/` and `/api/`: those clients only talk to the server
root (see [ADR 0012](docs/adr/0012-root-routing.md)).

## Security model

* Privileges are strings: `system:admin`, `repositories:manage`, `users:manage`,
  `settings:manage`, `repository:read:<name|*>`, `repository:write:<name|*>`.
* Built-in roles: `admin`, `developer` (read/write everything), `reader`, `anonymous`.
* Users may carry any number of roles; roles are editable; API tokens inherit the user's
  privileges and can expire.
* Credentials are accepted as HTTP Basic (password or token), `Authorization: Bearer <token>`,
  `Authorization: Token <token>`, a bare `gry_…` token as the whole `Authorization` value (as
  cargo, `gem` and `mix` send it), `X-NuGet-ApiKey` and `X-Api-Key`, because that is what the
  clients send. Invalid credentials are refused with `401` rather than treated as anonymous. Web
  sessions are short-lived JWTs.
* Snippets in the *Connect* panel never contain a real secret; tokens are created and shown once
  on the account page.
* Passwords are stored with bcrypt, tokens as SHA-256 hashes; neither is recoverable from the database.
* A proxy's upstream credentials go only to the origin (scheme, host and port) of its remote URL,
  in every format and across redirects; download hosts the upstream links to (GitHub, CDNs,
  `files.pythonhosted.org`, …) are asked without them, and plain HTTP never receives the
  credentials of an HTTPS upstream. The proxy setting `upstream.credentialHosts` lists further
  hosts that may receive them; a container registry's token service gets them because the
  registry protocol names it. See
  [docs/configuration.md](docs/configuration.md#upstream-credentials).

## Architecture

```mermaid
flowchart LR
    subgraph Clients
        pm[Package managers\nmvn · npm · dotnet · pip · docker · go · cargo · helm · …]
        browser[Web UI]
    end
    subgraph Granary
        root[RootRouteFilter\n/v2/ · /.well-known/terraform.json]
        dispatch[/repository/{name}/**\nRepositoryDispatchController/]
        api[/api/v1/**\nREST API/]
        handlers[Format handlers\n17 modules · 24 format ids]
        resolver[ContentResolver\nhosted · proxy · group]
        proxy[ProxyService\ncache · stale · single flight · circuit breaker]
        storage[StorageService\nwrite locks]
        blob[(Content-addressed\nblob store)]
        db[(H2 / PostgreSQL\nmetadata)]
    end
    upstream[(Upstream registries)]

    pm --> root --> dispatch
    pm --> dispatch
    browser --> api
    dispatch --> handlers --> resolver --> proxy --> upstream
    resolver --> storage --> blob & db
    api --> storage
    api -. templates · snippets · settings .-> handlers
```

Each format is one module implementing the `FormatHandler` SPI: its protocol, plus its standard
repositories (templates), the client snippets of the *Connect* panel, its typed repository
settings (`formatSettings`) and, for Docker and Terraform, the URLs it claims at the server root.
The REST API and the UI discover all of that through `GET /api/v1/formats`, so neither changes
when a format is added ([ADR 0011](docs/adr/0011-format-spi-v2.md),
[ADR 0012](docs/adr/0012-root-routing.md)). Details and the reasoning behind the main decisions
are in [docs/architecture.md](docs/architecture.md) and [docs/adr](docs/adr).

## Testing

```bash
mvn -B verify                    # everything, including the UI unit tests and JaCoCo coverage
mvn -B -Dui.skip=true verify     # Java only
mvn -B -Dui.skip=true -Ppostgres -pl granary-server -am verify   # the server suite on PostgreSQL 17 (needs Docker)
```

The Java suite has 4,396 tests, all passing for this release: 276 in `granary-core`, 3,260 in the
17 format modules and 860 in `granary-server`, most of them MockMvc integration tests; the 860
server tests also pass on PostgreSQL 17. The web UI adds 206 Vitest tests in 21 files.

* `granary-core`: blob store and compaction, proxy cache (against WireMock), single flight,
  write locks, security, resolver and stored-value limits.
* `granary-format-*`: protocol-level unit tests for path parsing, version ordering, metadata
  merging, the index documents each client expects and the connect snippets.
* `granary-server`: MockMvc integration tests that run the full HTTP stack against an embedded
  database for every format: publish and resolve, group merging, offline fallbacks, root routing,
  authentication in every credential shape, the admin API and the SPA fallback.
* PostgreSQL: with `-Ppostgres` every Spring test context of `granary-server` (the whole suite)
  gets its own empty database in one PostgreSQL 17 container (Testcontainers, default image
  `postgres:17-alpine`, another one with `-Dgranary.test.postgresImage=…`). To use an existing
  server instead, pass `-Dgranary.test.postgres.url=jdbc:postgresql://HOST:5432/postgres` plus
  `.username`/`.password` (both default to `postgres`); that user must be allowed to create
  databases, and the `granary_test_*` databases are dropped when the tests end. `granary-core`'s
  own tests always use H2. Without the profile, `PostgresIntegrationTest` and
  `StorageConcurrencyPostgresIntegrationTest` still check the migrations, the repository API and
  concurrent writes on PostgreSQL 17 (skipped when Docker is not available).
* `granary-ui`: Vitest unit tests for the stores, API client, locales, views, colour contrast of
  both themes and the right-to-left isolation of the Persian messages.
* CI runs the Java and UI build, the server suite on PostgreSQL (job *Server tests on
  PostgreSQL*) and a smoke test that starts the Docker Compose stack, performs a Maven round trip
  and runs `scripts/check-server.sh` against it.

Real clients, against a running server (`admin`/`admin123`; never two scripts at once against one
server, because they switch global settings):

* `scripts/check-server.sh http://localhost:8081` needs only curl and python3: it creates the
  standard repositories and checks every format's templates, the Connect snippets of every
  repository and the system pages.
* `scripts/verify-all.sh http://localhost:8081` runs `check-server.sh` and then the verification
  script of every format one after another, restores the global settings after each one and prints
  a summary table. `-f maven,npm` or `-x swift,hex` select formats, `-t` sets a time limit per
  format (default 3600 s), `-r` removes the Docker images a script pulled, `-l` lists the scripts
  and the tools each one needs. A format whose client is missing is reported as `SKIP`; steps a
  script could not run (an upstream the network blocks, a documented client limitation) are counted
  separately from passes and failures. The exit code is non-zero when a format failed.
* The scripts behind it: `scripts/verify-clients.sh` (Maven, Gradle, npm, pnpm, Yarn, Bun and
  dotnet; `CLIENTS="maven npm"` selects) and `granary-format-<format>/scripts/verify-<format>.sh`
  for every other format. Each publishes with the real client, installs through the group and the
  proxy, then installs again in offline mode with empty client caches, and can be run again
  against the same server. Clients that are not installed run from Docker images where the script
  supports it (helm, pub, hex, swift, cocoapods, and R and Haskell in raw); `swift:6.1`,
  `r-base:4.5.1` and `haskell:9.6` take several GB each, and
  `VERIFY_SKIP_IMAGES="haskell:9.6 r-base:4.5.1"` skips the last two.
* `RESTART_CMD="docker compose restart granary" scripts/offline-drill.sh http://localhost:8081`
  fills the caches through the groups with Maven, npm, NuGet, pip, Cargo, Go, Docker and RubyGems,
  switches global offline mode on, restarts the server, wipes every client cache, installs
  everything again from Granary alone, checks that an uncached artifact answers `503` and switches
  offline mode off again.

For 0.2.0 these ran against the Docker Compose stack on PostgreSQL 17: `check-server.sh` (106
checks), the script of every format with no failed step, and the offline drill (24 of 24, twice).
The Gradle, pnpm, Yarn and Bun steps were added afterwards and ran against a server on the
embedded database. Upstreams the network policy of the build machine blocked could not be
exercised live; they are named under [Registry coverage](#registry-coverage).

## Development

```
granary-core               domain model, storage, proxy cache, security, format SPI
granary-format-maven       Maven 2 protocol
granary-format-npm         npm registry protocol
granary-format-nuget       NuGet V3 protocol
granary-format-pypi        PyPI simple index, upload and JSON APIs
granary-format-docker      OCI Distribution (Docker registry API v2)
granary-format-go          Go module proxy and checksum database passthrough
granary-format-cargo       Cargo sparse registry and web API
granary-format-helm        Helm chart repositories and the ChartMuseum API
granary-format-terraform   Terraform / OpenTofu registries and network mirror
granary-format-conan       Conan v2 REST API
granary-format-hex         Hex repositories and API
granary-format-composer    Composer 2 repositories
granary-format-pub         pub (Dart / Flutter) repositories
granary-format-swift       Swift package registries
granary-format-rubygems    RubyGems compact and Marshal indexes
granary-format-cocoapods   CocoaPods CDN spec repositories
granary-format-raw         raw, cdn, cpan, cran, hackage, vcpkg, bower, elm
granary-ui                 Vue 3 + Vite single-page application (en, de, fr, tr, fa)
granary-server             Spring Boot application: HTTP, REST API, security filter, root routing, packaging
```

Requirements: JDK 25, Maven 3.9 and Docker (for the Compose smoke test and the PostgreSQL tests);
Node 22 only to run the UI on its own (`npm run dev` in `granary-ui`), since the Maven build
downloads its own. Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/).

A format setting that is added or reworded needs its label and description in all five locale files
(`granary-ui/src/locales/*.json`, under `formatText.settings.<key>`); `FormatTextCatalogueTest`
fails the build when the English copy differs from the server's text, and descriptions are full
sentences.

## Roadmap

Milestone 2 (0.2.0, see [CHANGELOG.md](CHANGELOG.md)) brought 21 new format ids, the format SPI
v2 and root routing. Candidates for Milestone 3:

1. Cleanup policies (deleting old snapshots, unused cached artifacts and superseded versions by
   rule); blob store compaction already reclaims what nothing references.
2. Full-text search across formats and a browse tree with download statistics.
3. REST API parity with the UI for every setting, plus an OpenAPI document.
4. Scheduled backup/restore and S3-compatible blob stores.
5. LDAP / OIDC sign-in.
6. More formats: APT, YUM/RPM, Conda, Hugging Face.

## Licence

Granary is source-available under the [Business Source License 1.1](LICENSE): free for
development, evaluation, education and open-source projects; a
[commercial licence](COMMERCIAL-LICENSE.md) is required for other production use. Each
version becomes available under the Mozilla Public License 2.0 four years after its release.
