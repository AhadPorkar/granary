# CocoaPods repositories

Format id: `cocoapods`. Source URL: `http://host:8081/repository/<name>/` (with the trailing slash).

Works with `pod` (CocoaPods 1.8 and later; verified with 1.17) as a *CDN source*: the spec
repository layout of `https://cdn.cocoapods.org/`, which `pod` reads over plain HTTPS instead of
cloning a git repository. A Podfile line `source 'http://…/repository/cocoapods-group/'` or
`pod repo add-cdn` is all a client needs; `pod install`, `pod update`, `pod outdated`,
`pod search`, `pod spec cat` and `pod spec which` work unchanged.

## Endpoints

Every repository type answers the same paths, all relative to the repository URL:

| Path | What | Kind |
| --- | --- | --- |
| `CocoaPods-version.yml` | supported CocoaPods range (`min`, `last`) and the shard `prefix_lengths` | metadata |
| `all_pods_versions_<a>_<b>_<c>.txt` | one line `Name/1.0.0/1.1.0` per pod whose `md5(name)` starts with `abc` | metadata |
| `all_pods.txt` | every pod name, one per line (`pod search` greps it) | metadata |
| `deprecated_podspecs.txt` | podspec paths of deprecated versions (`pod repo update` refreshes them) | metadata |
| `Specs/<a>/<b>/<c>/<Name>/<version>/<Name>.podspec.json` | a JSON podspec | immutable |
| `sources/<Name>/<version>/<file>` | a source archive (Granary extension, see below) | immutable |

The shard of a pod is the first three hex digits of the MD5 of its name, one directory per digit
(`AFNetworking` lives in `Specs/a/7/5/`). Hosted repositories and groups always use the standard
`[1, 1, 1]` sharding. Index documents carry an `ETag` (the SHA-256 of the body) and
`Cache-Control: no-cache`; `pod repo update` sends the saved ETag back and gets a `304` when
nothing changed. Pod names with spaces, `+`, `!` or non-ASCII characters (all exist on trunk) are
handled percent-encoded in both directions. The repository root itself answers a short HTML page
with the `source` line to use.

Hosted repositories also take writes: `PUT Specs/…/<Name>.podspec.json`, `POST api/pods`,
`PUT sources/<Name>/<version>/<file>`, `DELETE Specs/<Name>/<version>` and
`DELETE sources/<Name>/<version>/<file>` (see [Hosted](#hosted)).

## Standard repositories

| Name | Type | Upstream / members |
| --- | --- | --- |
| `cocoapods-cdn` | proxy | `https://cdn.cocoapods.org/` |
| `cocoapods-hosted` | hosted | |
| `cocoapods-group` | group | `cocoapods-hosted`, `cocoapods-cdn` |

## Hosted

* Publish a JSON podspec with `PUT Specs/<Name>/<version>/<Name>.podspec.json`; the sharded path
  (`Specs/5/6/0/<Name>/…`) is accepted as well and must be the right shard. The body is the
  output of `pod ipc spec <Name>.podspec`; Ruby podspecs are refused with `400` and that hint.
  The name and version in the podspec must match the URL, the version must be one CocoaPods
  accepts, the name must pass the CocoaPods linter rules (no whitespace, slash or leading period;
  Granary also refuses `% ? # ;`), and the podspec needs a `source`. At most 10 MB.
* `POST api/pods` takes `multipart/form-data` with a `podspec` part (file or field) and an optional
  `source` archive (zip, tar, tar.gz/tgz, tar.bz2/tbz, tar.xz/txz or dmg). The archive is stored
  under `sources/<Name>/<version>/<file>` and the stored podspec's `source` becomes
  `{"http": "<repository>/sources/…", "sha256": "…"}` (plus `type` when the file name does not
  imply it, and the original `flatten`). The archive's magic bytes must match its type, and a
  `sha256` or `sha1` the podspec already states must match the upload. Without a `source` part the
  body may also be the podspec JSON alone.
* `PUT sources/<Name>/<version>/<file>` uploads an archive on its own, so a podspec pushed
  afterwards can point its `source.http` at it.
* `PUT Specs/…` and `POST api/pods` answer `201` with `{"name", "version", "podspec", "source"}`
  (absolute URLs, `source` only when an archive was stored); `PUT sources/…` answers `201` with
  `{"source"}`.
* Pushing a version that exists (or an archive that exists) is refused with `409
  repository.cocoapods.versionExists` unless *allow redeploy* is set on the repository.
* `DELETE Specs/<Name>/<version>` removes the podspec and the version's archives (the
  equivalent of `pod trunk delete`); `DELETE sources/<Name>/<version>/<file>` removes one archive.
* A podspec with `"deprecated": true` or `"deprecated_in_favor_of": "Other"` is listed in
  `deprecated_podspecs.txt`; to deprecate a published version, push it again with that flag
  (needs *allow redeploy*). `pod search` then shows `[DEPRECATED in favor of Other]`.
* The shard indexes, `all_pods.txt` and the version file are derived from the stored podspecs on
  every request, so they never disagree with what is stored, also after a version was deleted in
  the web UI. A podspec and its archives form one component (name, version).
* An archive stored in the repository is announced under whatever URL the client used: through a
  group, `pod spec cat` shows `<group>/sources/…`, and `pod install` downloads it from there.
* Pushes need write access (`401` with a Basic challenge for anonymous requests, `403` for users
  without the privilege); an API token is the password.

## Proxy

* `remoteUrl` is the root of a CDN-style spec repository, e.g. `https://cdn.cocoapods.org/`.
* `CocoaPods-version.yml`, the shard indexes, `all_pods.txt` and `deprecated_podspecs.txt` are
  cached as metadata: refreshed after *metadata max age* (conditionally, with the upstream's
  ETag), served stale while the upstream is unreachable.
* Podspecs are immutable and cached forever, exactly as the upstream served them. The trunk CDN
  answers every podspec with a redirect to jsDelivr; Granary follows it. Podspecs listed in the
  upstream's `deprecated_podspecs.txt` are the exception: `pod trunk deprecate` rewrites them, so
  they are revalidated like metadata.
* Where `cdn.jsdelivr.net` is blocked but GitHub is not, a proxy of the Specs repository's raw files
  (`https://raw.githubusercontent.com/CocoaPods/Specs/master/`) serves the same trunk podspecs.
  That repository has no `all_pods.txt` or shard indexes, so put it in a group in front of the
  `cdn.cocoapods.org` proxy: the group takes the indexes from the CDN and the podspecs from the
  first member that has them.
* The upstream's sharding is read from its `CocoaPods-version.yml`; an upstream with other
  `prefix_lengths` (or none) is proxied with its own layout.
* Source archives: when a podspec's `source` is an `http` URL, the podspec is served with
  `source.http` pointing at `<repository or group URL>/sources/<Name>/<version>/<file>`
  (`type`, `flatten`, `sha256` and `sha1` are kept, `headers` are dropped). When `pod install`
  asks for that URL, Granary reads the original URL from the cached podspec, downloads the archive
  (sending the podspec's `source.headers`, except hop-by-hop headers and those Granary's HTTP
  client sets itself, such as `Host`, `User-Agent` and the conditional headers) and caches it
  forever. Only the file the podspec names can be fetched that way. Git
  sources (`git`, `tag`, `commit`) are passed through untouched. The setting
  `cocoapods.rewriteHttpSources` switches the rewrite off.
* Archives usually live on other hosts than the spec repository (GitHub releases, S3, a vendor's
  site). The proxy's upstream credentials are sent only when the archive is on the upstream's own
  origin (same scheme, host and port as `remoteUrl`) or on a host listed in
  [`upstream.credentialHosts`](../configuration.md#upstream-credentials); everywhere else the archive is requested without them. A failing archive host is answered with `502 upstream.unavailable` naming the URL,
  and does not count against the health of the proxy's upstream, so a dead archive link never
  makes the spec repository look down; an archive host answering `404` gives a `404`, remembered
  in the negative cache. An archive that does not match the podspec's `source.sha256` is not
  cached (`502 upstream.checksumMismatch`), so a corrupt download is retried instead of being
  served forever.
* Cached podspecs and archives are linked to components, so they show up in the UI like pushed
  ones.
* Offline use is described under [Offline behaviour](#offline-behaviour).

## Group

* `CocoaPods-version.yml` is the group's own (standard sharding).
* A shard index is the union of the members' shards: a pod lists every version any member has,
  sorted the way CocoaPods sorts them. Members that shard more coarsely are filtered by hash
  prefix; a member that shards more finely than three hex digits is left out (and logged).
* `all_pods.txt` and `deprecated_podspecs.txt` are unions.
* A podspec comes from the first member that has the version (hosted before the proxy in the
  template), with its archive URL rewritten to the group; `sources/…` is answered by the first
  member that has, or can fetch, the archive. If one member's upstream is down, the indexes of
  the other members are still served.
* Groups are read-only: pushes are refused with `405 repository.readOnly` naming the hosted
  member to use.

## Settings

| Key | Default | Applies to | Meaning |
| --- | --- | --- | --- |
| `cocoapods.rewriteHttpSources` | `true` | proxy, group | Point `source.http` of proxied podspecs at Granary, so zip and tar sources are downloaded through it and cached. A group rewrites only when both the group and the proxy member allow it. Git sources are never changed. |

## Client configuration

```ruby
# Podfile: the group serves your pods and trunk; CDN sources need the trailing slash
source 'http://granary.example.com:8081/repository/cocoapods-group/'

platform :ios, '13.0'

target 'MyApp' do
  use_frameworks!
  pod 'AFNetworking', '~> 4.0'
end
```

```bash
pod repo add-cdn granary http://granary.example.com:8081/repository/cocoapods-group/
pod repo update granary
pod install            # or pod install --repo-update
pod search AFNetworking --simple
pod spec cat AFNetworking
```

`pod` also creates the source by itself from the Podfile's `source` URL (it recognises a CDN by
its `CocoaPods-version.yml`), so `pod repo add-cdn` is optional.

Credentials: with anonymous reads switched off, `pod` (its CDN source and the `curl` it runs for
archive downloads) takes them from `~/.netrc`; the password is an API token.

```text
# ~/.netrc (chmod 600)
machine granary.example.com
  login admin
  password gry_…
```

```bash
# CocoaPods with the cocoapods-stats plugin reports installs to stats.cocoapods.org; switch that
# off where only Granary is reachable
export COCOAPODS_DISABLE_STATS=true
# fewer parallel podspec downloads (default 200) for a small server
export COCOAPODS_CDN_MAX_CONCURRENCY=50
```

Publishing (there is no `pod trunk` API; `curl` does the same in one request):

```bash
pod spec lint MyPod.podspec --allow-warnings     # optional; needs Xcode for iOS pods
pod ipc spec MyPod.podspec > MyPod.podspec.json
curl -u admin:gry_… -X PUT -H 'Content-Type: application/json' --data-binary @MyPod.podspec.json \
  http://granary.example.com:8081/repository/cocoapods-hosted/Specs/MyPod/1.0.0/MyPod.podspec.json

# with the source archive, stored next to the podspec
curl -u admin:gry_… -F podspec=@MyPod.podspec.json -F source=@MyPod-1.0.0.zip \
  http://granary.example.com:8081/repository/cocoapods-hosted/api/pods

# pod trunk delete
curl -u admin:gry_… -X DELETE http://granary.example.com:8081/repository/cocoapods-hosted/Specs/MyPod/1.0.0
```

## Offline behaviour

* In offline mode (global or the repository's own), or while the upstream is down, whatever is
  cached is served, including the source archives, so `pod install` keeps working. Cached
  indexes are served stale.
* Anything never fetched is a `503 upstream.offline` (or `503 upstream.unavailable`), which `pod`
  retries and then reports, instead of a `404` it would take as "no such pod". Upstream `404`s
  are remembered in the negative cache.
* A group keeps serving the indexes of its other members while one member's upstream is down.

## Errors

Failures are JSON, `{"code": "…", "message": "…"}`, which `pod` prints verbatim:
`400 repository.cocoapods.invalidPodspec` and `400 repository.cocoapods.invalid` (bad podspec,
archive or name), `400 path.invalid` (dot segments, backslashes, encoded slashes, control
characters), `405 repository.readOnly`, `405 method.notAllowed`, `409
repository.cocoapods.versionExists`, `413 repository.cocoapods.tooLarge`, and for proxied
archives `502 upstream.unavailable` (the archive's host failed) and `502
upstream.checksumMismatch` (the download does not match `source.sha256`).

## Limitations

* Only CDN-style spec repositories. Git spec repositories (`pod repo add <name> <git url>`,
  `pod repo push`) need a git server and are not served; neither is the trunk API
  (`pod trunk register/push/deprecate`): publish with `curl` as shown above.
* Podspecs are JSON only (`pod ipc spec` converts Ruby podspecs; it evaluates the Ruby file, so
  Granary does not).
* Only `http` source archives are cached. Pods with `git` sources (most of trunk, AFNetworking
  among them) still clone from their git host during `pod install`.
* `pod search --full` downloads the podspec of every pod in the source; against a trunk proxy
  that means hundreds of thousands of requests.
* `pod install` on Linux resolves, downloads and generates the Pods project (verified); building
  the app needs Xcode on macOS, as always.

## Verification

`granary-format-cocoapods/scripts/verify-cocoapods.sh http://localhost:8114` drives the real `pod`
client (from the PATH, or the cocoapods gem installed into the work directory, or a Ruby Docker
image): `pod repo add-cdn`, `pod repo update`, publishing with `pod ipc spec` and `curl` (both
upload styles), `pod search`, `pod spec cat`, `pod spec which`, `pod install` of an Xcode project
generated with the xcodeproj gem (one hosted pod and two proxied pods with zip and tar.gz
archives), anonymous read off with `~/.netrc`, and the same `pod repo add-cdn`, `pod search`,
`pod spec cat` and `pod install` again in offline mode with the upstream stopped and every local
CocoaPods cache removed. The proxied pods come from `stand-in-cdn.py`, a stand-in for
`cdn.cocoapods.org` that redirects podspecs like the real CDN does; the script also reads the real
CDN's index files through `cocoapods-cdn` and, where `cdn.jsdelivr.net` is reachable, AFNetworking's
podspecs; where it is not, it runs `pod search`, `pod spec cat` and `pod spec which` for AFNetworking
against a group of a raw Specs repository proxy and `cocoapods-cdn` instead (`SPECS_MIRROR`
overrides the raw URL). `pod` itself needs `git` and libcurl where it runs, `pod install` also
`rsync`, `curl`, `unzip` and `tar`.
Do not name a spec repo `trunk` when adding it with `pod repo add-cdn`: `pod` reads a repo of that
name from `cdn.cocoapods.org`, whatever URL it was added with.
