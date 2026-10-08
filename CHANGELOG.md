# Changelog

All notable changes to Granary are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/AhadPorkar/granary/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/AhadPorkar/granary/releases/tag/v0.1.0
