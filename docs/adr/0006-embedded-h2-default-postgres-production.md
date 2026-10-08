# 6. Embedded H2 by default, PostgreSQL for production

Date: 2026-10-08 · Status: accepted

## Context

The project must be runnable immediately (`java -jar`) and still scale to a company-wide
installation.

## Decision

Default to a file-based H2 database in PostgreSQL compatibility mode under the data directory.
Offer PostgreSQL through the `postgres` profile and ship it in `docker-compose.yml`. Migrations
are written in portable SQL and run on both through Flyway; JPA is used with `ddl-auto: none`.

## Consequences

* Zero configuration for evaluation and small teams; one profile switch for production.
* Every migration must stay portable; tests run on H2, the Compose smoke test on PostgreSQL.
* Moving from H2 to PostgreSQL is a documented export/import rather than an automatic path.
