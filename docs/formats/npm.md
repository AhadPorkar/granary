# npm repositories

Format id: `npm`. Registry URL: `http://host:8081/repository/<name>/`.

Works with npm, pnpm and yarn (classic and berry) because Granary implements the registry
protocol the clients use, not a subset of it.

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

## Proxy

* Packuments (`/<name>`) are mutable documents: refreshed after *metadata max age*, served
  stale while upstream is unreachable. Tarballs are immutable and cached forever.
* Tarball URLs inside packuments are rewritten to the Granary repository (or the group the
  client asked through), so a lockfile never points at the public registry.
* `remoteUrl` is the registry root, e.g. `https://registry.npmjs.org/`.

## Group

* Packuments are merged across members: the first member that has a version wins, `latest` is
  the highest semver across all members, `time` entries are unioned.
* Tarballs are served from the member that owns the version.
* Groups are read-only; publish to the hosted member.

## Client configuration

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
