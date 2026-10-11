# Changelog

All notable changes to Granary are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-10-11

Second milestone: Granary now speaks 24 package formats from 17 format modules, each verified with
its real clients, on a core hardened for concurrent writes and long-running operation.

### Added

- Format SPI v2 (ADR 0011): every format declares its standard repositories, ready-to-paste
  client configuration and typed repository settings. `GET /api/v1/formats` lists formats with
  their supported repository types, settings and templates; `GET /api/v1/repositories/{name}/connect`
  renders the snippets for one repository; repositories carry a `formatSettings` object
  (migration `V2__format_settings`).
- Root routing (ADR 0012): formats whose clients need fixed URLs at the server root claim them,
  so Docker clients reach the registry at `/v2/` and Terraform finds `/.well-known/terraform.json`.
- Web UI: the *Connect* panel shows the server-rendered snippets with the signed-in user's name;
  the repository form offers only the types a format supports and an editor for its settings;
  the repository list filters by text and format and groups rows per format; the dashboard counts
  repositories by format.
- Maven: standard proxies of Google's Maven repository (`maven-google`), Clojars (`clojars`) and the
  Gradle Plugin Portal (`gradle-plugins`) join `maven-public`; snippets for Gradle (Kotlin and
  Groovy DSL), Android, sbt and Leiningen. npm snippets for pnpm, Yarn 1 and 2+ and Bun; NuGet
  snippets for nuget.exe, Paket and Visual Studio.
- PyPI (`pypi`): PEP 503/691 simple index with PEP 658/714 metadata sidecars, PEP 592 yanked
  pass-through and the `pypi/<project>/json` API; uploads from twine, uv, Hatch, Flit and Poetry;
  proxies of pypi.org or any other index; groups merge project pages, project lists and JSON
  documents, hosted members first.
- Docker / OCI (`docker`): OCI Distribution 1.1 registries at the `/v2/` root and under
  `/repository/<name>/v2/`; monolithic, chunked and cross-repository mount uploads, manifests,
  indexes, OCI artifacts and the referrers API; proxies of Docker Hub, ghcr.io, quay.io and other
  token-based registries with digest-verified, cache-first and offline pulls; groups merge tags,
  catalog and referrers.
- Go modules (`go`): GOPROXY protocol with case-encoded paths; module zips published with an
  HTTP `PUT` and checked like the go command checks downloads; proxies pass the checksum database
  through (`go.sumdbName`, `go.sumdbUrl`) so `GOSUMDB` verification keeps working offline; groups
  merge `@v/list` and `@latest`.
- Cargo (`cargo`): sparse index registries with publish, yank/unyank, owners, search and token
  login; proxies of crates.io that verify crate checksums against the index; groups that merge
  index files with hosted versions first and keep every download on Granary.
- Helm (`helm`): chart repositories with a generated `index.yaml`, the ChartMuseum API
  (`helm cm-push`, curl uploads with provenance files, delete), `PUT` uploads and Artifact Hub
  metadata; proxies that rewrite chart URLs to Granary; groups with a merged `index.yaml`.
- Terraform / OpenTofu (`terraform`): service discovery, provider and module registry protocols
  and the provider network mirror; hosted providers and modules uploaded with `PUT`; proxies of
  registry.terraform.io and registry.opentofu.org that rewrite and cache download URLs and archive
  module sources; groups that merge versions, the first member owning a version.
- Conan (`conan`): Conan v2 REST API with recipe and package revisions for Conan 2 and for Conan 1.x
  with revisions; checksum deploy, metadata files, search, removal and API-token login; proxies of
  ConanCenter or a private conan_server/Artifactory with token login; groups merge revisions,
  `latest` and searches.
- Hex (`hex`): hosted repositories signed with a per-server RSA key for `mix hex.publish` and
  `rebar3 hex publish` (revert, retire, documentation); a hex.pm proxy that passes signed documents
  through for `HEX_MIRROR`/`HEX_CDN`; groups that merge and re-sign `names`, `versions` and
  `packages/<name>`.
- Composer (`composer`): Composer 2 repositories with `p2` and `~dev` metadata, dist archives,
  search, list, providers and security advisories; zip uploads with curl or a Nexus-compatible URL;
  a packagist.org proxy that caches dist archives from GitHub, GitLab and Bitbucket; groups that
  merge metadata with hosted versions first.
- pub (`pub`): Hosted Pub Repository Specification v2 for `dart pub` and `flutter pub`, including
  the three-step publish, retraction and discontinuation; pub.dev proxies that check archives
  against `archive_sha256`; groups that merge listings.
- Swift (`swift`): Swift package registries (SE-0292/SE-0321) for SwiftPM 5.7 and later with
  `swift package-registry set|login|publish`, signed releases, `Package@swift-*` manifest variants
  and `identifiers?url=` lookups for `--replace-scm-with-registry`; proxies of other Swift
  registries; groups that merge release lists.
- RubyGems (`rubygems`): `gem push`, yank/unyank and `gem signin`; the compact index (`versions`,
  `info`, `names`) with ETag, `Repr-Digest` and Range, the Marshal indexes, quick specs and a
  read-only JSON API; a rubygems.org proxy and groups that merge the compact index and
  `specs.4.8.gz`.
- CocoaPods (`cocoapods`): CDN-style spec repositories for `pod`; podspec JSON uploads with
  optional source archives and generated shard indexes; proxies of `cdn.cocoapods.org` that rewrite
  `http` source archives to Granary and cache them; groups that merge the indexes.
- Raw family (`raw`, `cdn`, `cpan`, `cran`, `hackage`, `vcpkg`, `bower`, `elm`): generic files with
  listings and Range support; proxies of cdnjs, jsDelivr and unpkg; CPAN and CRAN repositories that
  regenerate `02packages` and `PACKAGES`; Hackage and Stackage proxies with TUF metadata
  pass-through; vcpkg asset and binary caches; Bower and Elm registries.
- Blob store compaction (ADR 0010): blobs no asset references any more and older than a grace
  period (24 h by default) are deleted daily (`granary.storage.compaction.*`) or on demand with
  `POST /api/v1/blob-stores/{name}/compact` and the *Compact* button on the dashboard. Uploads in
  progress are protected whatever the grace period, and stores that share a directory are
  compacted as one.
- API tokens are also accepted as the bare `Authorization` value (`gry_…`, as cargo, `gem` and
  `mix` send them), with the `Token` scheme and in an `X-Api-Key` header.
- A `scripts/verify-<format>.sh` in every new format module drives a running server with the real
  clients, including an offline run with empty client caches. `scripts/verify-clients.sh` also
  drives Gradle, pnpm, Yarn 1 and Bun (`CLIENTS="maven gradle npm pnpm yarn bun"`).
- `scripts/verify-all.sh` runs the real-client verification of every format against one server,
  with format selection, a time limit per format, optional removal of the Docker images a script
  pulled, and a summary table; `scripts/check-server.sh` checks a running server without any
  client (the standard repositories of every format, their Connect snippets and the system pages);
  `scripts/offline-drill.sh` proves offline operation across Maven, npm, NuGet, PyPI, Cargo, Go,
  Docker and RubyGems, including a server restart.
- Maven profile `-Ppostgres` runs the whole server test suite on PostgreSQL 17, in a
  Testcontainers container or on an existing server; CI runs it on every push, and the Compose
  smoke test now runs `check-server.sh`.

### Changed

- `POST /api/v1/repositories/standard` creates the templates every installed format declares
  (65 repositories on an empty server) instead of a fixed Maven, npm and NuGet set; existing
  repositories are left untouched.
- Granary is documented as a single-node server: write coordination, proxy single flight and
  compaction run inside one JVM (ADR 0010).
- A repository's blob store can only be changed while the repository is empty
  (`400 repository.blobstore.immutable`).
- The upstream `User-Agent` is `Granary/0.2`.
- Web UI: repository, component, asset, token and user tables become stacked lists on phones with
  no sideways page scrolling; the dashboard's upstream list shows failures first, collapses long
  lists and counts reachable, unreachable and offline proxies; snippet copy buttons moved out of
  the code and every icon button names what it acts on; better right-to-left layout in Persian;
  higher text and border contrast in both themes.
- Web UI: format names and every format setting are translated into German, French, Turkish and
  Persian, and the repository form explains which repository types a format supports. Setting
  descriptions are full sentences and size limits are labelled in MiB.
- Every verification script can be run again against the same server: demo packages get new
  versions or namespaces, script-owned repositories and users are recreated,
  `verify-terraform.sh` restores the proxies it repoints, and `verify-clients.sh` keeps all client
  caches in its work directory.

### Fixed

- Concurrent uploads of the same path or component no longer fail with duplicate-key errors: the
  last writer of a path wins and every asset links to one component row, on H2 and PostgreSQL.
  Write conflicts that still reach the web layer answer `409 storage.conflict` instead of `500`.
- Concurrent proxy requests for the same uncached or stale file make one upstream request
  (`granary.proxy.single-flight-timeout`).
- API-token authentication holds one database connection at a time, so many concurrent CI jobs
  with tokens can no longer exhaust the connection pool.
- Upstream responses with oversized or malformed `Content-Type`, `ETag` or `Last-Modified` headers
  are cached: the content type is cleaned or guessed from the path and unusable validators are
  dropped.
- Paths longer than 2,000 characters or 2,600 UTF-8 bytes, and NUL characters in paths or
  component coordinates, are answered with `400 path.invalid` / `400 component.invalid` instead of
  a database error.
- A `multipart/form-data` request without a boundary is answered with
  `400 request.multipart.malformed` instead of `500`.
- Conditional downloads use the weak comparison of RFC 9110, so `If-None-Match: *` and `W/` tags
  match stored content.
- Text PostgreSQL cannot store, such as a NUL character in a login or Basic-auth user name, a
  search term, a repository description or remote credential, a user or role field or a token
  name, is refused with `400` (`validation` naming the field, `token.name.invalid` for a token
  name) or treated as not found, instead of `409` or `500` on PostgreSQL only.
- An over-long remote URL, remote user name, password or token, or base URL answers
  `400 validation` naming the field instead of `409 storage.conflict`; other database data
  exceptions answer `400 request.invalid`.
- Component search treats `%`, `_` and `\` in the search term literally, the same way on H2 and
  PostgreSQL.
- `mvn package` on a clean checkout produces a server jar that contains the web UI (the UI used to
  be built after its files had been copied into the jar).
- Web UI: closed confirmation dialogs no longer leave an empty box on the page, and the menu
  button no longer shows on desktop.

### Security

- Proxies that follow download links to other hosts send the repository's upstream credentials
  only to the upstream's own origin (CocoaPods source archives, Composer dist archives, the Go
  checksum database, pub archives). The Helm, Terraform, Cargo, PyPI, Elm and NuGet proxies do
  not yet: they send them to every host their index, service index or download documents point at
  (and to the configured `cargo.downloadUrl`, `cargo.apiUrl` and `pypi.filesUrl` hosts, and
  `github.com` for Elm packages), so configure upstream credentials there only for upstreams whose
  download hosts you trust with them.
- HTML, SVG and XML files in raw repositories are served with a sandboxing
  `Content-Security-Policy`, so an uploaded page cannot act with the Granary UI's session.
- Every new format checks its own path grammar before storage is touched, on top of the
  dispatcher's refusal of `..` segments, backslashes and NUL characters: malformed names are
  answered with `400`, and a percent-encoded slash is either refused or resolves to the same file
  as the plain path, never to another one.

## [0.1.0] - 2026-10-08

First milestone: a runnable, offline-first repository manager for the three formats most teams
use every day.

### Added

- Hosted, proxy and group repositories with a content-addressed, deduplicating blob store.
- Offline-first proxy cache: immutable artifacts cached forever, index documents re-validated
  conditionally and served stale during outages, per-repository circuit breaker with
  exponential back-off, negative caching, per-repository and global offline switches.
- Maven 2 format: deploy and resolve, release/snapshot policies, redeploy protection,
  generated and merged `maven-metadata.xml`, on-demand checksum sidecars, directory listings.
- npm format: publish, unpublish, dist-tags, deprecate, `npm login`, search, scoped packages,
  tarball URL rewriting, group merging.
- NuGet V3 format: service index, flat container, registration pages, search, push with API
  key, delete/unlist, symbol packages, URL rewriting for proxied registrations, group merging.
- Users, roles, fine-grained privileges per repository, API tokens usable as Basic password,
  Bearer token or `X-NuGet-ApiKey`, optional anonymous read, JWT web sessions.
- REST API (`/api/v1`) for repositories, components, assets, users, roles, tokens, settings,
  search and system status, including one-click creation of the standard repositories.
- Web UI in English, German, French, Turkish and Persian (right-to-left).
- Embedded H2 database by default, PostgreSQL profile, Flyway migrations.
- Docker image, Docker Compose stack with PostgreSQL, GitHub Actions CI with a Compose smoke
  test and GHCR publishing, Dependabot.
- Test suites for every module, MockMvc integration tests over the real HTTP stack, a
  Testcontainers PostgreSQL test and Vitest UI tests.

[Unreleased]: https://github.com/AhadPorkar/granary/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/AhadPorkar/granary/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/AhadPorkar/granary/releases/tag/v0.1.0
