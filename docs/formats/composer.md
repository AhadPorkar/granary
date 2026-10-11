# Composer repositories

Format id: `composer`. Repository URL (what goes into `composer.json`):
`http://host:8081/repository/<name>`; Composer reads `<url>/packages.json` from there.

Works with Composer 2 (verified with Composer 2.8) because Granary implements the Composer 2
repository protocol: `packages.json` with `metadata-url`, minified `p2/<vendor>/<name>.json` and
`p2/<vendor>/<name>~dev.json` documents, dist archives, search, the package list, the providers
API, the security advisory API that `composer audit` uses, and install notifications. Composer 1
(the `providers-url` protocol) is not supported; packagist.org shut it down in 2025 as well.

## Endpoints

| Path (relative to the repository URL) | Method | What it is |
|---|---|---|
| `packages.json` | GET | root document: `metadata-url`, `search`, `list`, `providers-api`, `notify-batch`, `security-advisories` |
| `p2/<vendor>/<name>.json`, `p2/<vendor>/<name>~dev.json` | GET | package metadata (tagged releases, branches), minified (`composer/2.0`) |
| `dist/<vendor>/<name>/<version>/<file>` | GET | dist archive of one version |
| `search.json?q=…&type=…` | GET | search (`composer search`) |
| `list.json?filter=…&vendor=…&type=…` | GET | package names (`composer show -a` without a package name, `composer search '^vendor/'`) |
| `providers/<vendor>/<name>.json` | GET | packages that `provide` a virtual package |
| `api/security-advisories` | GET, POST | advisories for `packages[]=…` (`composer audit`, the audit after `require`/`update`) |
| `downloads` | POST | install notifications (`notify-batch`), answered `201` |
| `packages/<vendor>/<name>/<version>[.zip]` | PUT, POST | upload a version (hosted) |
| `packages` | PUT, POST | upload; name and version from composer.json or `?version=` |
| `packages/<vendor>/<name>/<version>`, `packages/<vendor>/<name>` | DELETE | delete a version or the whole package (hosted) |

Searching, listing, providers, advisories and notifications are read operations even when
Composer POSTs them; uploads and deletes need write access.

## Standard repositories

`packagist` (proxy of `https://repo.packagist.org/`), `composer-hosted` and `composer-group`
(`composer-hosted`, `packagist`).

## Hosted

* Upload the zip archive of a version with a raw `PUT` (`curl --upload-file`) to
  `packages/<vendor>/<name>/<version>`, or as the multipart field `package` (`curl -F`) to
  `packages`; the version then comes from `?version=`, a form field `version` or the `version` in
  composer.json. The Nexus Repository upload URL `packages/upload/<vendor>/<name>/<version>` is
  accepted as well, so existing CI scripts keep working.
* `composer.json` is read from the root of the archive or from its single top-level directory
  (the layout of `composer archive`, `git archive --prefix` and GitHub/GitLab archives). The
  name in composer.json must match the URL (Composer's own name rules; stored and served in lower
  case), and a version given in both places must be the same version. Everything Composer's
  loader would choke on (`require` that is not a map of strings, `autoload` that is not an
  object, …) is refused with `400` and a message saying what is wrong, so one bad upload cannot
  break every install.
* Versions are normalised exactly like Composer's `VersionParser`: `1.0`, `v1.0.0` and
  `1.0.0.0` are the same release (`version_normalized` `1.0.0.0`), `2.0.0-RC1` becomes
  `2.0.0.0-RC1`. Branches (`dev-main`, `2.x-dev`) go into the `~dev` document, which Composer
  only asks for when a constraint or the minimum stability allows them.
* The archive is stored once under `dist/<vendor>/<name>/<version>/<sha1>.zip`; the version
  entry in the metadata carries every package field of composer.json (`require`, `require-dev`,
  `autoload`, `bin`, `extra`, `license`, `authors`, `suggest`, …), `dist` with `type: zip`, the
  absolute URL, `reference` and `shasum` (the SHA-1 Composer verifies), and `time` (the upload
  time unless composer.json has one). The stored document keeps repository-relative URLs and is
  made absolute for whoever asks, so a new server address needs no rebuild.
* Re-uploading an existing version is refused with `409` (`composer.versionExists`) unless *allow
  redeploy* is set on the repository; a redeploy replaces the archive and its metadata entry.
* `DELETE packages/<vendor>/<name>/<version>` removes one version and its archive,
  `DELETE packages/<vendor>/<name>` the whole package. An archive deleted in the web UI takes
  its version out of the metadata as well.
* `packages.json` lists the stored packages as `available-packages`, so Composer does not ask a
  hosted-only repository about packages it does not have.
* `search.json` matches every word against name, description and keywords and orders exact name
  matches first, then by downloads; `list.json` supports `filter` (with `*`), `vendor` and `type`;
  `providers/` answers from the `provide` sections of the stored versions. Hosted packages have no
  security advisories on record, which is reported as an empty answer.
* Metadata responses carry `ETag` and `Last-Modified`. Composer stores `Last-Modified` and sends
  it back as `If-Modified-Since`, which is answered with `304`; `If-None-Match` works too. A
  document that changed less than a second ago is sent without `Last-Modified` (a time within
  the current second could not tell it apart from a change later in that second), so Composer
  fetches it in full once more next time.
  Archives are served with `Content-Length`, `ETag`, `Content-Disposition`, `HEAD` and
  conditional requests, and every download is counted.
* Every uploaded version is a component (namespace = vendor, name, version) in the component
  browser.

## Proxy

* `remoteUrl` is the root of a Composer 2 repository, by default `https://repo.packagist.org/`.
  A URL that names the root document itself (`…/packages.json`) works as well, and so does
  another Granary, Satis or Private Packagist repository.
* The upstream's `packages.json` is cached (re-validated after *metadata max age*) and tells where
  its `p2` documents, search, list, providers and advisory APIs are (root-relative,
  relative and absolute URLs are resolved the way Composer does). The proxy's own
  `packages.json` points every one of them at Granary and leaves out what would send clients
  past it: `providers-url`, `provider-includes` (Composer 1), `metadata-changes-url`,
  `available-package-patterns` and the `filter` lists. `warning` and `info` messages are passed on.
* `p2` documents are cached exactly as upstream sent them and rewritten on the way out: every
  archive `dist.url` becomes `<repository>/dist/<vendor>/<name>/<version>/<reference>.<ext>`
  (the commit the upstream names; the extension of the original URL, else of the dist type), the
  dist `mirrors` and per-package `notification-url` are removed, and `source` (git) is left as it
  is; Composer prefers the dist. They are re-validated after *metadata max age* with
  `If-Modified-Since`/`If-None-Match` and served stale while upstream is unreachable. The
  `Last-Modified` Granary sends for them is when its own copy changed or was re-validated (not
  the upstream's), so the `If-Modified-Since` Composer sends back can never hide a change that
  reached the cache after an earlier answer.
* An archive is downloaded the first time a client asks for it, from the URL in the cached (or,
  for a lock-file install, freshly fetched) metadata, and kept forever. GitHub's zipball
  redirect to codeload is followed. Archives on hosts other than the upstream (GitHub, GitLab,
  Bitbucket) are fetched without the repository's upstream credentials, like Composer itself
  does (a host listed in [`upstream.credentialHosts`](../configuration.md#upstream-credentials) gets them), and behind a circuit breaker per host, so a rate-limited GitHub cannot make the
  metadata upstream look down. Archives on the upstream's own host (another Granary, a private
  Packagist) are fetched with the credentials. Every cached archive is linked to a component.
* `composer.distRewrite` (default `true`) can be turned off to leave the upstream's dist URLs in
  the metadata; clients then download archives directly and nothing is cached for offline use.
* `search.json`, `list.json` and `providers/` are passed through and cached per query. Security
  advisories are fetched from the upstream's API for all requested packages at once, cached per
  package and re-validated after *metadata max age*; offline the cached answers are used.
* An upstream `404` for a `p2` document is remembered (the repository's negative cache, and on
  disk), so offline the proxy still says "no such package" (an empty document) instead of
  "cannot tell". Since a Composer 2 repository serves the `~dev` document of every package it
  has, a package upstream denied knowing has no branches there either; `composer show -a` and
  dev-stability resolutions of private packages therefore work offline as well.
* Nothing cached and upstream unreachable or offline: `503` with `upstream.unavailable` or
  `upstream.offline`. Composer reports it ("could not be downloaded (HTTP/1.1 503)") and retries
  instead of treating the package as non-existent.

## Group

* `p2` documents are merged across the leaf members: hosted members first, then the configured
  order (nested groups flattened); the first member that has a version (by
  `version_normalized`) supplies it, so a public package can never shadow a version published
  privately under the same name. The result is sorted newest first and minified again;
  security advisories embedded by the members are merged.
* Every `dist.url` points at the group; the archive is served by the member that owns the
  version (hosted storage, or the proxy's cache or upstream).
* `packages.json` is generated for the group (no `available-packages`, since a group with a
  proxy cannot list everything); search, list, providers and advisories are merged.
* Groups are read-only: an upload or delete is refused with `405` (`repository.readOnly`) and
  the message names the hosted member to use.

## Settings

| Key | Type | Default | Applies to | Meaning |
|---|---|---|---|---|
| `composer.distRewrite` | boolean | `true` | proxy | Serve dist archives through Granary (and cache them); when off, clients download them from GitHub and others directly |

## Client configuration

All projects of a user (`~/.config/composer/config.json`, or `$COMPOSER_HOME`):

```bash
composer config -g repositories.granary composer http://granary.example.com:8081/repository/composer-group
# use Granary instead of packagist.org, not in addition to it
composer config -g repo.packagist false
```

One project:

```json
{
    "repositories": [
        {"type": "composer", "url": "http://granary.example.com:8081/repository/composer-group"},
        {"packagist.org": false}
    ]
}
```

Composer refuses plain `http` repositories unless `secure-http` is off. That sends credentials
and downloads in clear text; put Granary behind a TLS-terminating reverse proxy wherever you
can, otherwise:

```bash
composer config secure-http false      # this project
composer config -g secure-http false   # every project
```

Credentials (needed to publish, and to install when anonymous read access is off). Composer looks
them up by host, plus the port when it is not the scheme's default:

```bash
composer config -g http-basic.granary.example.com:8081 admin gry_…   # auth.json
composer config -g bearer.granary.example.com:8081 gry_…             # or as a bearer token
```

```json
{
    "http-basic": {
        "granary.example.com:8081": {"username": "admin", "password": "gry_…"}
    }
}
```

In CI, pass the same JSON in the environment instead of a file:

```bash
export COMPOSER_AUTH='{"http-basic":{"granary.example.com:8081":{"username":"ci","password":"gry_…"}}}'
composer install --no-interaction --prefer-dist
```

Publishing (curl; Composer itself has no publish command):

```bash
composer archive --format=zip --dir=build --file=acme-lib-1.0.0   # or: git archive --format=zip v1.0.0
curl -u admin:gry_… --upload-file build/acme-lib-1.0.0.zip \
     http://granary.example.com:8081/repository/composer-hosted/packages/acme/lib/1.0.0
curl -u admin:gry_… -F package=@build/acme-lib-1.0.0.zip \
     "http://granary.example.com:8081/repository/composer-hosted/packages?version=1.0.0"
curl -u admin:gry_… -X DELETE http://granary.example.com:8081/repository/composer-hosted/packages/acme/lib/1.0.0
```

The upload answers `201` with the stored name, version, normalised version and dist URL.

Coming from Satis: a hosted repository replaces the Satis build; there is nothing to rebuild
after a release, and archives are served for every version (Satis' archive mirroring). Coming
from Private Packagist or Nexus Repository: the proxy mirrors packagist.org metadata and every
archive on first use and keeps serving both while the internet is down, and the group is the
single URL for private and public packages, with private versions taking precedence.

## Offline behaviour

* Cached `p2` documents, `packages.json`, searches, lists, providers and advisories are
  re-validated after *metadata max age* and served stale while the upstream is unreachable or
  offline mode is on; cached dist archives are served without contacting anyone.
* Remembered upstream `404`s keep answering "no such package" offline, so dev-stability
  resolutions and `composer show -a` of private packages still work.
* An uncached document or archive is answered with `503 upstream.offline` or
  `503 upstream.unavailable`; Composer reports the `503` and retries instead of treating the
  package as missing.
* With `composer.distRewrite` off, archives are downloaded from their original hosts and are not
  available offline.

## Verification

`granary-format-composer/scripts/verify-composer.sh <base url>` drives a running Granary with the
real Composer: curl uploads (raw and form, 409 for a duplicate, 405 through the group, 401 without
credentials), `composer require` of a public package with dependencies through the group and
through the proxy, of the hosted package (which depends on a proxied one), `composer update`
(304s), `composer search`, `composer show -a`, `composer audit`, the three ways of passing
credentials with anonymous read off, and then, with Granary in offline mode, empty Composer
caches and no internet on the client, `composer install` from the lock file and from scratch,
`composer show -a`, and the error for an uncached package.

## Limitations

* Composer 1 and the `providers-url`/`provider-includes` protocol are not served.
* Hosted repositories take zip archives only (what `composer archive`, `git archive` and the
  forges produce); VCS repositories (`type: vcs`, a git URL) are not something Granary builds
  archives from.
* Dist types that are not archives (`path`, `file`) and dist URLs whose version cannot be a URL
  path are passed on untouched by a proxy, so such packages are downloaded directly.
* Proxied archives are cached when a client first downloads them; metadata alone (for example
  `composer update --no-install`) does not make a package installable offline.
* The proxy does not pass on packagist.org's `filter` lists (malware lists that Composer releases
  newer than 2.8 can apply) or the `metadata-changes-url` feed.
* Install notifications (`notify-batch`) are acknowledged but not forwarded upstream; download
  counts come from the archive downloads Granary serves.
