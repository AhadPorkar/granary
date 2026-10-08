# 7. Stateless authentication with privilege strings

Date: 2026-10-08 · Status: accepted

## Context

Package managers send credentials in several shapes (Basic, Bearer, NuGet API key header) and
never hold a session. The web UI wants short-lived sessions. Authorisation must be per
repository and per read/write.

## Decision

* Every request is authenticated by a single filter that understands all credential shapes and
  produces a principal with a flattened set of privilege strings
  (`system:admin`, `repositories:manage`, `repository:read:<name|*>`, `repository:write:<name|*>`, …).
* API tokens are random, prefixed `gry_`, stored hashed and usable in any credential shape.
* Web sessions are HS256 JWTs signed with a generated per-installation secret.
* No server-side session state; no CSRF surface because nothing is cookie-based.

## Consequences

* Instances can be added behind a load balancer with no session affinity.
* Revoking a token is immediate; revoking a web session is bounded by its 12-hour lifetime.
