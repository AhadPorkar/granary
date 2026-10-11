# NuGet repositories

Format id: `nuget`. Package source URL: `http://host:8081/repository/<name>/index.json` (V3).

Verified with dotnet 8, nuget.exe and Visual Studio; Paket reads the same V3 feed.

## Endpoints

All paths are relative to the repository URL; the service index announces them to the clients.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `index.json` | Service index (`PackageBaseAddress`, `RegistrationsBaseUrl`, `SearchQueryService`, `PackagePublish`, `SymbolPackagePublish`) |
| `GET` | `v3/flatcontainer/{id}/index.json` | Versions of a package |
| `GET`, `HEAD` | `v3/flatcontainer/{id}/{version}/{id}.{version}.nupkg` | Package |
| `GET`, `HEAD` | `v3/flatcontainer/{id}/{version}/{id}.nuspec` | Manifest |
| `GET`, `HEAD` | `v3/flatcontainer/{id}/{version}/{id}.{version}.snupkg` | Symbol package |
| `GET` | `v3/registration/{id}/index.json`, `v3/registration/{id}/{version}.json` | Registration index and leaves |
| `GET` | `v3/query?q=…&skip=…&take=…&prerelease=…&semVerLevel=…` | Search |
| `PUT` | `v2/package` | Push a package (`dotnet nuget push`, `nuget push`) |
| `DELETE` | `v2/package/{id}/{version}` | Delete a version |
| `PUT` | `v2/symbolpackage` | Push a symbol package |

Ids are case-insensitive; storage paths use the lower-cased id and normalised version.

## Standard repositories

| Name | Type | Upstream / members |
|------|------|--------------------|
| `nuget.org` | proxy | `https://api.nuget.org/v3/index.json` |
| `nuget-hosted` | hosted | |
| `nuget-group` | group | `nuget-hosted`, `nuget.org` |

## Hosted

* `dotnet nuget push` / `nuget push` with an API key (`-k`): the key is a Granary API token,
  sent as `X-NuGet-ApiKey`. Basic authentication with a token or password also works.
* Symbol packages (`.snupkg`) are accepted and stored next to the package; they may be pushed
  again at any time.
* Pushing an existing version answers `409 Conflict` (so `--skip-duplicate` works) unless
  *allow redeploy* is set.
* `dotnet nuget delete` / `nuget delete` removes the version (package, manifest and symbols);
  the flat index, registration pages and search are derived from what is stored, so the version
  disappears from all of them. There is no separate unlist.
* Search (`v3/query`) supports free text, the `packageid:` (exact id) and `id:` (part of the id)
  prefixes, `prerelease`, `skip` and `take`, which covers Visual Studio's package manager and
  `dotnet package search`.
* Version comparison follows NuGet's rules (four-part versions, leading zeros, SemVer 2
  pre-release ordering, build metadata ignored).

## Proxy

* The upstream service index, flat container indexes (`/{id}/index.json`) and registration pages
  are mutable documents (re-validated after *metadata max age*, stale when offline); `.nupkg`
  and `.nuspec` files are immutable and cached forever.
* Every URL in registration and search responses is rewritten to Granary, including
  non-inlined registration pages that nuget.org serves gzip-compressed.
* Search is forwarded upstream while online and answers an empty result when offline.
* `remoteUrl` is the V3 service index, e.g. `https://api.nuget.org/v3/index.json`.

## Group

* Versions are unioned across members; for a version present in several members the first one
  wins (for the file, the manifest and the registration leaf).
* Search results and registration pages are merged under the group's URL.
* Groups and proxies are read-only: push and delete answer `405 repository.readOnly`; use the
  hosted member.

## Client configuration

The *Connect* panel shows these commands with your server and user name, plus `nuget.exe`,
Paket (`paket.dependencies`) and Visual Studio variants.

```bash
dotnet nuget add source http://granary.example.com:8081/repository/nuget-group/index.json -n granary
dotnet nuget push MyLib.1.0.0.nupkg -s http://granary.example.com:8081/repository/nuget-hosted/index.json -k gry_…
```

`nuget.config` for a repository checkout:

```xml
<configuration>
  <packageSources>
    <clear />
    <add key="granary" value="http://granary.example.com:8081/repository/nuget-group/index.json" />
  </packageSources>
</configuration>
```

## Settings

NuGet has no format settings.

## Offline behaviour

* Cached packages and manifests are served without contacting the upstream; cached indexes and
  registration pages are re-validated after *metadata max age* and served stale while the
  upstream is unreachable or offline mode is on.
* A package that is not cached answers `503 upstream.offline` or `503 upstream.unavailable`
  instead of `404`, so a restore fails with a retryable error.
* Search on a proxy answers an empty result while the upstream cannot be reached; a group still
  searches its hosted members.
* Concurrent requests for the same uncached file make one upstream request.

## Limitations

* No V2 (OData) feed: clients must use the V3 `index.json` URL. `v2/package` exists only for push
  and delete, as in the V3 protocol.
* Symbol packages are stored and downloadable, but there is no symbol server endpoint for
  debuggers.
* No unlist-only operation: a delete removes the version.
* A proxy sends its upstream credentials to every resource URL the upstream's service index
  names, including resources on other hosts (nuget.org's search service, for one).
