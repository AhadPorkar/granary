# NuGet repositories

Format id: `nuget`. Package source URL: `http://host:8081/repository/<name>/index.json` (V3).

## Hosted

* `dotnet nuget push` / `nuget push` with an API key (`-k`): the key is a Granary API token,
  sent as `X-NuGet-ApiKey`. Basic authentication with a token or password also works.
* Symbol packages (`.snupkg`) are accepted and stored next to the package.
* Pushing an existing version answers `409 Conflict` (so `--skip-duplicate` works) unless
  *allow redeploy* is set.
* `dotnet nuget delete` unlists/removes the version; the flat index and registration pages are
  regenerated from what is stored.
* Search (`/query`) supports `q`, `packageid:`, `prerelease`, `skip` and `take`, which covers
  Visual Studio's package manager and `dotnet package search`.
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
* Groups are read-only; push to the hosted member.

## Client configuration

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
