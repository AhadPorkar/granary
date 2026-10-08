# 4. Offline-first proxy policy with a circuit breaker

Date: 2026-10-08 · Status: accepted

## Context

The primary deployment has an internet connection that drops for hours at a time. Builds must
keep working on whatever has been cached, and a dead upstream must not add minutes of
time-outs to every build.

## Decision

One policy in `ProxyService` for all formats:

* immutable content is served from cache without any network call, forever;
* mutable index documents are re-validated conditionally after a max age and served stale
  when upstream fails;
* consecutive failures open a per-repository circuit breaker with exponential back-off and a
  single probe request;
* `404`s are negatively cached;
* a per-repository switch and a global *offline mode* disable upstream access entirely.

Format handlers only say which paths are mutable.

## Consequences

* Behaviour during an outage is predictable and the same for Maven, npm and NuGet.
* Operators can see breaker state and last errors per repository in the UI and the API.
* Serving stale metadata means a new upstream version can appear with a delay of *max age*
  after connectivity returns; the UI's *invalidate* action forces a refresh.
