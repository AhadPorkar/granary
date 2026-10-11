# pub repositories (Dart / Flutter)

Format id: `pub`. Repository URL (what `PUB_HOSTED_URL`, `hosted:` and `publish_to:` take):
`http://host:8081/repository/<name>` (with or without a trailing slash).

Works with `dart pub` and `flutter pub` (the same client) because Granary implements the
[Hosted Pub Repository Specification v2](https://github.com/dart-lang/pub/blob/master/doc/repository-spec-v2.md):
package listings, version documents, security advisories, archive downloads and the three-step
publish, plus pub.dev's endpoints for retracting versions and discontinuing packages.

## Endpoints

All paths are relative to the repository URL. The API answers with
`Content-Type: application/vnd.pub.v2+json; charset=utf-8`, an `ETag` (so `If-None-Match` gets a `304`)
and supports `HEAD`.

| Path | Method | What |
|------|--------|------|
| `api/packages/<name>` | GET | listing: `name`, `latest`, `versions[]` (`version`, `pubspec`, `archive_url`, `archive_sha256`, `published`, `retracted`), `isDiscontinued`, `replacedBy`, `advisoriesUpdated` |
| `api/packages/<name>/versions/<version>` | GET | one version object |
| `api/packages/<name>/advisories` | GET | `{"advisories":[…],"advisoriesUpdated":…}` |
| `packages/<name>/versions/<version>.tar.gz` | GET | the archive (`application/gzip`, immutable) |
| `api/packages/versions/new` | GET | publish step 1: `{"url":"…/api/packages/versions/newUpload","fields":{}}` |
| `api/packages/versions/newUpload` | POST | publish step 2: multipart field `file`; `204` with `Location` |
| `api/packages/versions/newUploadFinish?id=…` | GET | publish step 3: `{"success":{"message":…}}` or `{"error":{"code","message"}}` |
| `api/packages/<name>/versions/<version>/retract` | POST / DELETE | retract / restore a version (hosted) |
| `api/packages/<name>/versions/<version>/options` | GET / PUT | `{"isRetracted": true\|false}` (pub.dev's admin API) |
| `api/packages/<name>/options` | GET / PUT | `{"isDiscontinued": true\|false, "replacedBy": "<package>"}` |
| `api/packages/<name>/versions/<version>` | DELETE | delete one version (hosted, Granary extension) |
| `api/packages/<name>` | DELETE | delete a package with all versions (hosted, Granary extension) |

Every `archive_url` points at the repository the client asked: a listing fetched through
`pub-group` names `…/repository/pub-group/packages/<name>/versions/<version>.tar.gz`, so a lock file
never points at pub.dev or at a member. When Granary runs behind a reverse proxy, set its public URL
(`GRANARY_BASE_URL` or the *base URL* system setting) so these links use it.

Errors use pub's shape, `{"error":{"code":"NotFound","message":"…"}}`, which the client prints as
"Message from server: …". Malformed package names or versions, and paths with an encoded slash
(`%2F`), are a `400`; unknown packages are a `404`.

## Standard repositories

The standard repositories (`POST /api/v1/repositories/standard`, or the button in the web UI) are
`pub-dev` (proxy of `https://pub.dev/`), `pub-hosted` and `pub-group` (`pub-hosted` + `pub-dev`), so a
single `PUB_HOSTED_URL=http://host:8081/repository/pub-group` serves your own packages and everything
from pub.dev.

## Hosted

* `dart pub publish` (and `flutter pub publish`) to `publish_to: <repository URL>`. Granary reads the
  uploaded archive (a gzip-compressed tar file with `pubspec.yaml` at its root) and refuses, with the
  reason shown by the client, archives without a root `pubspec.yaml`, entries or links that would
  escape the package directory (`..`, absolute paths), corrupt or truncated archives, archives that
  expand beyond 1 GiB or contain more than 100 000 entries, pubspecs that are not YAML mappings,
  package names pub.dev would refuse (not lower-case identifiers, Dart reserved words, more than 64
  characters) and versions that are not `major.minor.patch[-pre][+build]`.
* The whole pubspec (dependencies, environment, homepage, repository, topics, …) is converted to JSON
  and served as `pubspec` in every version object; `archive_sha256` is computed by Granary;
  `published` is the upload time.
* Re-publishing an existing version is refused (`409`, "Version 1.0.0 of package foo already exists")
  unless *allow redeploy* is set on the repository. Versions the pub client considers equal
  (`1.0.0+1` and `1.0.0+01`) count as the same version.
* `latest` follows pub.dev: the highest release that is not retracted; a pre-release only when the
  package has nothing else.
* Retracting (`POST …/retract`) keeps the archive downloadable, so lock files that pin the version
  keep working, while `dart pub get` and `dart pub upgrade` avoid it for new resolutions.
  Discontinuing (`PUT …/options`) makes the client print "discontinued replaced by …". The pub CLI
  has no commands for either (pub.dev uses its web UI), so call the endpoints with an API token:

  ```bash
  curl -X POST -H "Authorization: Bearer gry_…" \
       http://host:8081/repository/pub-hosted/api/packages/foo/versions/1.1.0/retract
  curl -X PUT -H "Authorization: Bearer gry_…" -H 'Content-Type: application/json' \
       -d '{"isDiscontinued":true,"replacedBy":"foo2"}' \
       http://host:8081/repository/pub-hosted/api/packages/foo/options
  ```
* Every version is a component `(name, version)` in the web UI; deleting the component there removes
  the version from the listing as well.

## Proxy

* `remoteUrl` is the repository root, e.g. `https://pub.dev/` (any v2 repository works, including
  another Granary).
* Listings and advisories are fetched with `Accept: application/vnd.pub.v2+json`, cached as metadata,
  refreshed after *metadata max age* and served stale while the upstream is unreachable.
* Archives are downloaded from the `archive_url` the upstream listing names (pub.dev:
  `https://pub.dev/api/archives/<name>-<version>.tar.gz`), checked against its `archive_sha256` (a
  mismatch is a `502` and nothing is cached) and then cached forever. With upstream credentials
  configured, archives are only fetched from the upstream's own host, because the credentials go with
  every request.
* Advisories are passed through. While an advisories document cannot be served (offline and never
  fetched) the listing leaves out `advisoriesUpdated`, so the client does not ask for it.
* The upstream's `latest`, `retracted`, `isDiscontinued` and `replacedBy` are kept.
* Offline use is described under [Offline behaviour](#offline-behaviour).

## Group

* Listings are merged across the members in order (nested groups are flattened): the first member
  that lists a version owns it, the union of versions is served, `latest` is computed over the union
  with pub's rules, `isDiscontinued` comes from the first member that knows the package and
  `advisoriesUpdated` is the most recent of all.
* Archives come from the member that owns the version, so the bytes always match the
  `archive_sha256` the client was given.
* Groups are read-only: `dart pub publish` to a group is answered with "publish to its hosted member
  instead: publish_to: …/repository/pub-hosted".

## Authentication

`dart pub token add <repository URL>` stores a Granary API token (create one in the web UI under
*API tokens*); the client sends it as `Authorization: Bearer gry_…` to every URL below the one it was
added for. Publishing and every change need write access to the repository; downloads are anonymous
unless anonymous read is switched off.

A refusal comes as `401` (no token) or `403` (a token without the privilege) with
`WWW-Authenticate: Bearer realm="pub", message="…"`, which the client prints under its own hint:

```
http://host:8081/repository/pub-group/ package repository requested authentication!
You can provide credentials using:
    dart pub token add http://host:8081/repository/pub-group/
Granary repository 'pub-group' needs an API token to read it.
```

`dart pub token add` only accepts `https://` URLs, except for `localhost`; put Granary behind TLS for
anything else. HTTP Basic with the token as password and web-session tokens work too, for scripts.

## Settings

| Key | Applies to | Default | Meaning |
|-----|------------|---------|---------|
| `pub.maxArchiveSizeMb` | hosted | `100` | largest archive `dart pub publish` may upload (1 to 10240 MiB; pub.dev allows 100) |
| `pub.advisories` | proxy | `true` | pass the upstream's security advisories through; when off, listings carry no `advisoriesUpdated` and the advisories endpoint answers an empty list |

## Client configuration

Every package through Granary:

```bash
export PUB_HOSTED_URL=http://granary.example.com:8081/repository/pub-group
dart pub get          # or: flutter pub get
```

```powershell
$env:PUB_HOSTED_URL = "http://granary.example.com:8081/repository/pub-group"
```

One dependency from a specific repository (`pubspec.yaml`):

```yaml
dependencies:
  my_package:
    hosted: https://granary.example.com/repository/pub-hosted
    version: ^1.0.0
```

Publishing (`pubspec.yaml` of the package, then the CLI):

```yaml
name: my_package
version: 1.0.0
publish_to: https://granary.example.com/repository/pub-hosted
```

```bash
dart pub token add https://granary.example.com/repository/pub-hosted   # paste the gry_… token
echo "$GRANARY_TOKEN" | dart pub token add https://granary.example.com/repository/pub-hosted
dart pub publish            # --force skips the confirmation in scripts
```

CI (token from a secret, never written to disk):

```bash
export PUB_HOSTED_URL=https://granary.example.com/repository/pub-group
dart pub token add https://granary.example.com/repository/pub-group --env-var GRANARY_PUB_TOKEN
dart pub get --enforce-lockfile
```

Docker build:

```dockerfile
FROM dart:stable
ENV PUB_HOSTED_URL=https://granary.example.com/repository/pub-group
WORKDIR /app
COPY pubspec.* ./
RUN dart pub get
COPY . .
RUN dart compile exe bin/main.dart -o bin/server
```

`FLUTTER_STORAGE_BASE_URL` is about Flutter SDK downloads (engine artifacts, the bundled Dart SDK), not
packages. To cache those as well, create a raw proxy of `https://storage.googleapis.com/` and point
`FLUTTER_STORAGE_BASE_URL` at it.

The connect dialogue of each repository shows these snippets with the right URLs filled in.

## Offline behaviour

Offline (the repository's offline mode, or the server's: `PUT /api/v1/system/settings
{"offlineMode":true}`), everything cached is served; anything else is a `503` (`upstream.offline`, or
`upstream.unavailable` while the upstream is down) rather than a `404`, so the client does not
conclude that the package does not exist. `dart pub get` with an empty `PUB_CACHE` resolves
everything that was fetched through Granary before: listings from the cache, archives from the
cache, hosted packages as always. A package that was never fetched fails with `503` after the
client's retries; set `PUB_MAX_HTTP_RETRIES=1` to fail fast.

## Limitations

* `dart pub login` (Google sign-in) is pub.dev only; Granary uses API tokens via `dart pub token add`.
* No search endpoint (the pub client does not use one), no package scores, documentation pages or
  uploader management; unlisting is not supported (`isUnlisted` is always `false`).
* Hosted packages have no security advisories (the endpoint answers an empty list).
* An invalid or expired token is refused by Granary's authentication filter before the repository is
  asked, with a plain `401` without the pub challenge; the client then prints its generic
  "authentication failed" hint instead of a server message.
* The `503` for an uncached package while offline carries Granary's own JSON body, which the client
  does not print; it reports "503 trying to find package …".
* With a lock file, `dart pub get` may reuse the version listing cached in `PUB_CACHE`; a retraction
  or discontinuation shows up there after `dart pub upgrade` or with a fresh cache.
