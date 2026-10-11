# npm repositories

Format id: `npm`. Registry URL: `http://host:8081/repository/<name>/`.

Works with npm, pnpm, Yarn (classic and berry) and Bun because Granary implements the registry
protocol the clients use, not a subset of it.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET`, `HEAD` | `<name>` (`@scope/name` or `@scope%2Fname`) | Packument: every version of a package |
| `PUT` | `<name>` | Publish, deprecate (hosted) |
| `GET` | `<name>/<version or dist-tag>` | One version object |
| `GET`, `HEAD` | `<name>/-/<file>.tgz` | Tarball |
| `PUT`, `DELETE` | `<name>/-rev/<rev>`, `<name>/-/<file>.tgz/-rev/<rev>` | `npm unpublish` of versions, the whole package or one tarball |
| `GET` | `-/package/<name>/dist-tags` | `npm dist-tag ls` |
| `PUT`, `DELETE` | `-/package/<name>/dist-tags/<tag>` | `npm dist-tag add` / `rm` |
| `PUT` | `-/user/org.couchdb.user:<username>` | `npm login` (legacy flow); returns an API token |
| `GET` | `-/whoami`, `-/ping` | Identity and health checks |
| `GET` | `-/v1/search?text=…&size=…&from=…` | `npm search` |

`-/v1/login` (web login) and `-/npm/v1/security/…` (`npm audit`) answer `404`: npm falls back to
the legacy login and treats the audit as "no data".

## Standard repositories

| Name | Type | Upstream / members |
|------|------|--------------------|
| `npm-proxy` | proxy | `https://registry.npmjs.org/` |
| `npm-hosted` | hosted | |
| `npm-group` | group | `npm-hosted`, `npm-proxy` |

## Hosted

* `npm publish` (scoped and unscoped packages, `--tag`), `npm unpublish` of a version or of the
  whole package, `npm dist-tag add|rm|ls`, `npm deprecate`.
* The tarball's `shasum` and `integrity` are recomputed on upload and must match what the client
  claims, so a corrupted upload is refused rather than stored.
* Re-publishing an existing version is refused with `409 cannot modify pre-existing version`
  unless *allow redeploy* is set on the repository (npmjs.org behaves the same way).
* `npm login` works with the classic flow (`npm login --auth-type=legacy`; current npm falls back
  to it automatically). The returned token is a Granary API token for that user. A token created
  in the web UI can be used directly in `.npmrc` as `_authToken`.
* `npm search` queries the stored packages; `npm audit` is answered with `404`, which npm treats
  as "no audit data" and continues silently.
* Every published version is a component `(<scope>, <name>, <version>)`, the scope without `@`
  and empty for unscoped packages, e.g. `(babel, core, 7.26.0)`; its asset is the tarball.

## Proxy

* Packuments (`/<name>`) are mutable documents: refreshed after *metadata max age*, served
  stale while upstream is unreachable. Tarballs are immutable and cached forever.
* Tarball URLs inside packuments are rewritten to the Granary repository (or the group the
  client asked through), so a lockfile never points at the public registry.
* A `dist.tarball` URL is followed only on the registry's own origin (or a host listed in
  [`upstream.credentialHosts`](../configuration.md#upstream-credentials)); anything else, such as
  the `http://` links of a registry behind a TLS proxy, is asked from `remoteUrl` under the
  conventional `<name>/-/<file>.tgz` path, which carries the proxy's upstream credentials.
* `npm search` is forwarded to the upstream and not cached (results are short-lived).
* `remoteUrl` is the registry root, e.g. `https://registry.npmjs.org/`.
* Every cached tarball is linked to the component a publish of that version records
  (`(<scope>, <name>, <version>)`), so what was installed through the proxy shows up in the
  component browser and in search. The version comes from the cached packument, or from the
  tarball's file name when the packument was never fetched. Packuments are not components.
  Installs through a group are recorded in the proxy member that cached the tarball.
* Deleting a cached component removes the tarball; the next install fetches it again, or gets
  `503` while the proxy is offline. Tarballs cached by an earlier Granary version, which
  recorded no components, are linked the first time they are served, even offline.

## Group

* Packuments are merged across members: the first member that has a version wins, `latest` is
  the highest semver across all members, `time` entries are unioned.
* Tarballs are served from the member that owns the version.
* Search shows the union of the members' results, the first member winning a name clash.
* Groups are read-only; publish to the hosted member.

## Client configuration

The *Connect* panel shows the same configuration with your server and user name, plus variants
for a single scope, pnpm, Yarn 1 (`.yarnrc`), Yarn 2+ (`.yarnrc.yml`) and Bun (`bunfig.toml`).

```ini
# ~/.npmrc or project .npmrc
registry=http://granary.example.com:8081/repository/npm-group/
//granary.example.com:8081/repository/npm-group/:_authToken=gry_…
//granary.example.com:8081/repository/npm-hosted/:_authToken=gry_…
```

```bash
npm publish --registry http://granary.example.com:8081/repository/npm-hosted/
```

Scoped packages are requested by npm as `/@scope%2Fname`; Granary accepts both the encoded and
the plain form.

## Settings

npm has no format settings.

## Offline behaviour

* Cached tarballs are served without contacting the upstream; cached packuments are re-validated
  after *metadata max age* and served stale while the upstream is unreachable or offline mode
  is on.
* A packument or tarball that is not cached answers `503 upstream.offline` or
  `503 upstream.unavailable` instead of `404`, so `npm install` fails with a retryable error
  rather than "package not found".
* `npm search` on a proxy returns an empty result while the upstream cannot be reached; hosted
  members of a group are still searched.
* Concurrent requests for the same uncached file make one upstream request.

## Limitations

* No web login (`npm login --auth-type=web`) and no audit endpoint (`npm audit`).
* Hosted search scans the stored components (up to 1,000 per query) rather than a full-text
  index.
* Organisations, teams and package access levels of npmjs.org are not modelled; access is
  governed by Granary's repository privileges.
