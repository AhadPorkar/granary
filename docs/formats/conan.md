# Conan repositories

Format id: `conan`. Remote URL: `http://host:8081/repository/<name>` (no trailing slash; the client
appends `/v1/ping`, `/v2/conans/...` itself).

Granary implements the Conan v2 REST API with revisions, the protocol of `conan_server` and
Artifactory. It works with Conan 2.x and with Conan 1.x once revisions are enabled
(`general.revisions_enabled=1`). The ping announces
`complex_search,checksum_deploy,revisions,only_v2`, so Conan 1.x without revisions stops with
"only works with revisions enabled" instead of trying the old v1 API.

## Endpoints

Every path is relative to the repository URL. `{ref}` is `{name}/{version}/{user}/{channel}`,
with `_/_` for a reference without user and channel.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `v1/ping` | Capabilities (`X-Conan-Server-Capabilities`) |
| `GET`, `POST` | `v2/users/authenticate` (also `v1/…`) | Login: Basic credentials in, token out |
| `GET` | `v2/users/check_credentials` (also `v1/…`) | Token check |
| `GET` | `v2/conans/search?q=…` | `conan search`, `conan list` of references |
| `GET` | `v2/conans/{ref}/latest`, `…/revisions` | Latest recipe revision, all recipe revisions |
| `GET`, `PUT` | `v2/conans/{ref}/revisions/{rrev}/files[/{file}]` | Recipe file list, recipe files |
| `GET` | `v2/conans/{ref}[/revisions/{rrev}]/search` | Binaries of a recipe with their settings and options |
| `GET` | `…/revisions/{rrev}/packages/{pid}/latest`, `…/revisions` | Latest and all package revisions |
| `GET`, `PUT` | `…/packages/{pid}/revisions/{prev}/files[/{file}]` | Package file list, package files |
| `DELETE` | `v2/conans/{ref}`, `…/revisions/{rrev}`, `…/packages`, `…/packages/{pid}`, `…/revisions/{prev}` | `conan remove` (hosted) |

Other `v1/` routes (the pre-revision API) are not served.

## Standard repositories

| Name           | Type   | What it is                                                    |
|----------------|--------|---------------------------------------------------------------|
| `conancenter`  | proxy  | ConanCenter, `https://center2.conan.io`                       |
| `conan-hosted` | hosted | Your own recipes and binaries                                 |
| `conan-group`  | group  | `conan-hosted` then `conancenter`: the one remote clients use |

## Hosted

* `conan upload` of recipes and binaries, with or without user and channel
  (`zlib/1.3.1`, `acme/1.0@team/stable`), including `--only-recipe`, recipe and package metadata
  (`--metadata`) and parallel uploads (`core.upload:parallel`).
* Every file is checked against the `X-Checksum-Sha1` the client sends; a mismatch is refused with
  `400` and nothing is stored. The checksum-deploy probe (an empty `PUT` with
  `X-Checksum-Deploy: true`) is answered `201` when the very same file is already stored, so
  `conan upload` skips it; otherwise the client sends the content.
* Revisions are content hashes. Uploading the same bytes again is accepted silently; different
  bytes under an existing revision are refused with `409` unless *allow redeploy* is set. Files
  below `metadata/` are the exception: Conan lets them change without a new revision.
* A revision becomes visible only once its `conanmanifest.txt` is stored. The client uploads that
  file last, so an interrupted upload never shows up as a broken revision.
* `conan install`, `conan download`, `conan list` (revisions, package ids, package revisions and
  the settings and options of each binary), `conan search`, version ranges and
  `conan remove` of a recipe, a recipe revision, its binaries or one package revision.
* The newest revision is the one uploaded last (`latest`, and the order of `revisions`).
* Files below `metadata/` are served with `Cache-Control: no-cache`; revision files with a
  one-year `max-age`. Every file and document has an `ETag`, answers `HEAD` and honours
  `If-None-Match` (including `*`).
* Search walks the stored files of the repository and needs no separate index, so its cost grows
  with the amount of content stored. Patterns use shell wildcards (`*`, `?`, `[a-z]`, `[!a]`) and
  match a reference when they cover it up to a `/` or `@`, as `conan_server` does (`zlib`, `zlib/*`
  and `zlib/1.*` all find `zlib/1.3.1`). Matching never backtracks, so a pattern full of wildcards
  costs no more than a plain one; patterns longer than 512 characters are refused with `400`.
* Request paths are checked before anything is stored: names, versions, users, channels,
  revisions and package ids are at most 128 characters of `A-Z a-z 0-9 _ . + -` (not starting
  with `.`, `+` or `-`), file names at most 1024 characters, and `..`, empty segments, backslashes
  and percent-encoded slashes are refused with `400`.

## Login and tokens

* `conan remote login <remote> <user> -p <password>` sends Basic credentials to
  `v2/users/authenticate` (Conan 1.x: `v1/users/authenticate`) and gets a token back, which the
  client then sends as `Authorization: Bearer`.
* With an **API token** as the password, that same token is handed back: nothing new is created.
  This is what CI should use.
* With the **account password**, Granary issues an API token named `conan` for that user. It
  expires after `conan.tokenTtlHours` hours (default 24; `0` never expires). Every such login
  creates one; expired ones stay in the user's token list until removed there.
* `CONAN_LOGIN_USERNAME_<REMOTE>` and `CONAN_PASSWORD_<REMOTE>` (remote name upper-cased, `-`
  becomes `_`) work as usual: the client logs in when the server first answers `401`.
* `v1/ping`, `v2/users/authenticate` and `v2/users/check_credentials` never need credentials, so
  `conan remote login` works even with anonymous read access switched off. Everything else follows
  the repository's permissions: reading needs read access, uploads and removals need write access.

## Proxy

* `remoteUrl` is the server root, e.g. `https://center2.conan.io`, an Artifactory Conan remote or
  a `conan_server`.
* Documents (`latest`, `revisions`, file lists, package search, `conan search`) are re-validated
  after *metadata max age* and served stale while the upstream is unreachable. Recipe and package
  files are immutable and cached forever; files below `metadata/` are re-validated like documents.
  Files are always served with Granary's own content type (`conan_server` sends
  `Content-Type: auto`).
* Files are cached under the same layout as hosted content and linked to a component per
  reference, so the UI lists proxied packages like hosted ones.
* Private upstreams: with *remote username* and *remote password* set, Granary logs in at the
  upstream's `v2/users/authenticate` once and sends the returned bearer token (kept for 30
  minutes; a failed login falls back to Basic for 5 minutes). Set `conan.upstreamAuth=basic` for
  upstreams that want Basic on every request. A *remote token* is sent as a bearer token as is.
* When the upstream has no `latest` endpoint, the newest entry of `revisions` is used.
* When the upstream answers `401` to the bearer token (it was restarted with a new secret, or its
  tokens live shorter than 30 minutes), the next request logs in again.
* Offline use is described under [Offline behaviour](#offline-behaviour). An upstream that
  answers a search with `404` means "no results".
* Proxies are read-only (`403`, see the group section).

## Group

* Revision lists are merged across members, newest first; when two members have the same
  revision the hosted member's entry wins. `latest` is the newest revision across all members.
* Files and file lists come from the member that has that revision, hosted members first, so a
  revision you published is never shadowed by an upstream copy.
* `conan search` and package searches are merged.
* While the upstream is down, hosted content and everything already cached is still served.
* Groups are read-only: an upload or removal is refused with `403` and an error document whose
  message names the hosted member to use. Conan prints it and stops:
  `ERROR: Permission denied for user: 'admin': 403: Repository 'conan-group' is a group and
  read-only; upload to its hosted member 'conan-hosted' instead`. (For a `405` the client would
  only print "405 Client Error" for every file and retry each one.) Proxies answer the same way.

## Settings

| Key                   | Type    | Default | Applies to | Meaning                                                    |
|-----------------------|---------|---------|------------|------------------------------------------------------------|
| `conan.tokenTtlHours` | integer | `24`    | all        | Lifetime of the token a password login creates; `0` = none |
| `conan.upstreamAuth`  | string  | `token` | proxy      | `token` (log in, then bearer) or `basic` (Basic each time) |

## Client configuration

Conan 2:

```bash
conan remote add granary http://granary.example.com:8081/repository/conan-group --index 0
conan remote remove conancenter            # ConanCenter is reached through the group
conan remote login granary admin -p gry_…  # an API token; needed for uploads or without anonymous read

# groups are read-only: upload to the hosted member
conan remote add granary-hosted http://granary.example.com:8081/repository/conan-hosted
conan remote login granary-hosted admin -p gry_…
conan upload "*" -r granary-hosted --confirm

conan install --requires=zlib/1.3.1 -r granary --build=missing
```

CI without a stored login:

```bash
export CONAN_LOGIN_USERNAME_GRANARY_HOSTED=ci
export CONAN_PASSWORD_GRANARY_HOSTED=gry_…
```

HTTPS with a private CA: `core.net.http:cacert_path = /path/to/ca.pem` in `global.conf`, or
`-cc core.net.http:cacert_path=...` on a single command.

Conan 1.x:

```bash
conan config set general.revisions_enabled=1
conan remote add granary http://granary.example.com:8081/repository/conan-group
conan user -r granary -p gry_… admin
conan install zlib/1.3.1@ -r granary --build=missing
```

The *Connect* page of each repository shows these commands with the right URLs.

## Offline behaviour

* In offline mode (the repository's or the whole server's) only the cache is used and the
  upstream is never contacted, not even to log in. Cached documents are also served stale while
  the upstream is unreachable.
* Anything not cached is a `503` (`upstream.offline`, or `upstream.unavailable` when the upstream
  is down), which Conan reports as an error rather than "not found". That includes a
  `conan search` (or a version range) that was never asked through the proxy before; searches
  asked before are served from the cache.
* A group keeps serving hosted content and everything its proxy members have cached.

## Limitations

* Only the v2 API with revisions. Conan 1.x without revisions, and Artifactory-only extras
  (artifact properties as matrix parameters, `users/token` OAuth) are not supported.
* Every password login creates a new API token (see above); use an API token as the password to
  avoid that.
* The checksum-deploy probe matches only the same path in the same repository; identical files in
  other revisions or repositories are uploaded again (they are deduplicated by the blob store).

## Verification

`granary-format-conan/scripts/verify-conan.sh <base-url>` drives the real clients against a
running server: Conan 2 uploads, installs through the group into empty caches (including a
compiled library and an application built against it), metadata, removal, parallel uploads, the
refusal of an upload through a group, a chained proxy, a proxy of a private `conan_server`
(including a restart of that server with a new secret), Conan 1.x, ConanCenter through the group
(when the server can reach it), and the same installs again with the server in offline mode.
`conan_server` and Conan 1.x are taken from the `PATH` and `CONAN1`, or installed with pip into
virtual environments inside the work directory when missing (set `CONAN_VERIFY_INSTALL=0` to skip
those parts instead).
