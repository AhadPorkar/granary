# RubyGems repositories

Format id: `rubygems`. Source URL: `http://host:8081/repository/<name>/`.

Works with `gem` (RubyGems 3.x) and Bundler (2.x and 4.x). Granary serves both index protocols
the clients use: the compact index (`versions`, `info/<gem>`, `names`), which Bundler and
`gem install` prefer, and the older Marshal indexes (`specs.4.8.gz`, `latest_specs.4.8.gz`,
`prerelease_specs.4.8.gz`, `quick/Marshal.4.8/<gem>.gemspec.rz`), which `gem search`,
`gem list --remote`, `gem fetch` and `gem sources --add` still read.

## Endpoints

Every path is relative to the source URL.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `versions`, `info/<gem>`, `names` | Compact index |
| `GET` | `specs.4.8.gz`, `latest_specs.4.8.gz`, `prerelease_specs.4.8.gz` | Marshal indexes |
| `GET` | `quick/Marshal.4.8/<gem>-<version>[-<platform>].gemspec.rz` | Quick spec |
| `GET`, `HEAD` | `gems/<gem>-<version>[-<platform>].gem` | Gem file |
| `GET` | `api/v1/gems/<gem>.json`, `api/v1/versions/<gem>.json`, `api/v1/versions/<gem>/latest.json` | JSON API (read only) |
| `POST` | `api/v1/gems` | `gem push` (hosted) |
| `DELETE`, `PUT` | `api/v1/gems/yank`, `api/v1/gems/unyank` | Yank, unyank (hosted) |
| `GET`, `POST` | `api/v1/api_key` | `gem signin` with Basic credentials |

## Standard repositories

| Name | Type | Upstream / members |
| --- | --- | --- |
| `rubygems-proxy` | proxy | `https://rubygems.org/` |
| `rubygems-hosted` | hosted | |
| `rubygems-group` | group | `rubygems-hosted`, `rubygems-proxy` |

## Hosted

* `gem push` uploads a `.gem`. Granary reads the gem the way RubyGems does (the tar archive, its
  `metadata.gz` gemspec and the `checksums.yaml.gz` digests, which must match) and refuses
  anything else with `422 RubyGems cannot process this gem.` and the reason. The gemspec YAML is
  read without instantiating any Ruby or Java class.
* Pushing a version that exists is refused with `409 Repushing of gem versions is not allowed.`
  unless *allow redeploy* is set. A gem whose name differs from an existing one only in case is
  refused as well, as on rubygems.org.
* `gem yank` (and `--platform`) removes a version from every index; `PUT api/v1/gems/unyank`
  brings it back. The `.gem` file itself stays downloadable, so lockfiles that already pin a
  yanked version keep installing (rubygems.org deletes the file; see *Limitations*).
* `gem signin` exchanges a Granary username and password for a new API token, stored by `gem` in
  its credentials file. The repository setting `rubygems.signin` turns this off.
* Every index is derived from small per-version records (`meta/<gem>/<full name>.json`) and is
  rebuilt on the first read after a push, yank or deletion in the web UI, so the indexes can
  never drift from what is stored. A gem, its quick spec and its record form one component.
* The JSON API used by tooling (`api/v1/gems/<gem>.json`, `api/v1/versions/<gem>.json`,
  `api/v1/versions/<gem>/latest.json`) is answered from the same records.

## Proxy

* `remoteUrl` is the server root, e.g. `https://rubygems.org/`.
* `versions`, `info/<gem>`, `names`, the specs indexes and the JSON API are mutable documents:
  refreshed after *metadata max age*, served stale while upstream is unreachable. `.gem` files
  and quick specs are immutable and cached forever; cached gems are linked to components so they
  show up in the UI like pushed ones.
* Documents are cached exactly as upstream sent them, so the ETag (the MD5 Bundler compares with
  the checksum in `versions`) stays valid. `gem_uri` in the JSON API is rewritten to Granary.
* rubygems.org answers `403 AccessDenied` (its gems live in S3 behind a CDN), not `404`, for a
  `.gem` or quick spec it does not have. For those two kinds of files Granary takes a 403 as a
  plain miss: it is remembered in the negative cache like a 404 and does not count as an outage,
  so requests for a gem that does not exist cannot cut the proxy off. A 403 for an index document
  still means upstream refuses Granary and is reported as `503 upstream.unavailable`.
* Offline use is described under [Offline behaviour](#offline-behaviour).

## Group

* `versions` is upstream's file (rubygems.org's is tens of megabytes) copied through unchanged, with a
  line appended for every gem or version the other members add. The checksum on those lines is
  the MD5 of the merged `info/<gem>` the group serves, so Bundler fetches exactly the info files
  that changed.
* `info/<gem>` lists the first member's versions first (hosted before the proxy in the
  template), then the versions only later members have; a version present in several members
  is described by the first one. `names` is the union.
* `specs.4.8.gz` and `prerelease_specs.4.8.gz` are the largest member's index copied byte for
  byte, followed by the tuples the other members add; `latest_specs.4.8.gz` is recomputed from
  all members' tuples. Merged documents are stored in the group and rebuilt only when a member's
  copy changes; a member whose document is unreadable is left out instead of failing the group.
* A document only one member has is served as that member's copy, unchanged.
* `.gem` files and quick specs come from the first member that has them. Groups are read-only:
  `gem push` and `gem yank` against a group are refused with `405` naming the hosted member.

## Compact index details

Responses for `versions`, `info/<gem>` and `names` carry what Bundler's incremental updater
needs: `ETag` (the MD5 of the body, as on rubygems.org), `Repr-Digest: sha-256=:…:` over the full
representation, `Accept-Ranges: bytes`, `304` on a matching `If-None-Match` and `206 Partial
Content` for `Range: bytes=<n>-`. A range past the end is `416`, after which Bundler downloads the
whole file.

## Settings

| Key | Default | Applies to | Meaning |
| --- | --- | --- | --- |
| `rubygems.signin` | `true` | all | Let `gem signin` (`POST api/v1/api_key` with Basic credentials) issue API tokens. |
| `rubygems.maxGemSizeMb` | `1024` | hosted | The largest `.gem` `gem push` may upload (`413` above it). |

## Client configuration

```bash
# gem: one source for everything
gem sources --add http://admin:gry_…@granary.example.com:8081/repository/rubygems-group/ --remove https://rubygems.org/
gem install --clear-sources --source http://granary.example.com:8081/repository/rubygems-group/ rails
```

```yaml
# ~/.gem/credentials (chmod 0600), for gem push and gem yank
---
:granary: gry_…
http://granary.example.com:8081/repository/rubygems-hosted: gry_…
```

```bash
gem push --host http://granary.example.com:8081/repository/rubygems-hosted --key granary acme-1.0.0.gem
gem yank --host http://granary.example.com:8081/repository/rubygems-hosted --key granary acme --version 1.0.0

# or obtain a key with your username and password; set RUBYGEMS_HOST rather than passing --host,
# because with --host alone gem first sends the credentials to rubygems.org
RUBYGEMS_HOST=http://granary.example.com:8081/repository/rubygems-hosted gem signin
```

```ruby
# Gemfile
source "http://granary.example.com:8081/repository/rubygems-group/"
```

```bash
# Bundler credentials (or BUNDLE_GRANARY__EXAMPLE__COM=admin:gry_… in the environment)
bundle config set --global http://granary.example.com:8081/repository/rubygems-group/ admin:gry_…

# keep source "https://rubygems.org" in every Gemfile and fetch through Granary instead
bundle config set --global mirror.https://rubygems.org http://granary.example.com:8081/repository/rubygems-group/
```

A gemspec can pin pushes to the hosted repository with
`spec.metadata["allowed_push_host"] = "http://granary.example.com:8081/repository/rubygems-hosted"`.

## Offline behaviour

* Once something was installed through the proxy it stays installable offline: with the global
  offline mode or the repository's offline flag, cached documents and gems are served (documents
  also stale while the upstream is unreachable), and anything else is a `503 upstream.offline`
  (`503 upstream.unavailable` while the upstream is down), which `gem` and Bundler report as a
  failed fetch rather than remembering the gem as missing.
* A group keeps serving hosted gems and everything its proxy member has cached; a member whose
  document is unreadable is left out of the merged indexes.

## Limitations

* The dependency API (`api/v1/dependencies`), which rubygems.org retired, answers `404`;
  RubyGems 3 and Bundler 2+ use the compact index instead.
* Yanked versions stay downloadable by their exact file name (they disappear from every index).
* A group's `versions` file is rebuilt as a whole when a member changes, so it is not
  append-only across rebuilds the way rubygems.org's is; Bundler detects that through
  `Repr-Digest` and downloads the file in full instead of only the new tail. In a group that
  contains a rubygems.org proxy this happens after every refresh of rubygems.org's file (at most
  once per *metadata max age*), so each Bundler client then downloads about 25 MB again. Using
  the proxy directly, or as a Bundler mirror, keeps Bundler's incremental updates.
* A group merges the versions of a gem name across its members. A private gem whose name is also
  taken on rubygems.org therefore gets the public versions too, and Bundler picks the highest
  version that satisfies the Gemfile. Give private gems names that are not on rubygems.org, or
  pin them to the hosted repository with a `source "…/rubygems-hosted/" do … end` block.
* rubygems.org's `versions` file is about 25 MB and changes constantly, so a busy proxy, and a
  group that merges it, store a new copy at most once per *metadata max age*. Superseded copies
  are deleted by blob store compaction (daily, or on demand) once they are older than its grace
  period (24 hours by default), so the disk needs room for about a day of copies; raise the
  metadata max age on the proxy if disk space matters more than freshness.
* The JSON API covers the read endpoints clients and tooling use (gem, versions, latest);
  owners, web hooks, search and the activity feeds are not provided.
* `gem signin --host <url>` sends the credentials to rubygems.org's profile endpoint first (a
  RubyGems behaviour); use `RUBYGEMS_HOST=<url> gem signin` as shown above.
* Multi-factor authentication and API key scopes are not modelled: a key is a Granary API token
  with the privileges of its user.

## Verification

`granary-format-rubygems/scripts/verify-rubygems.sh http://localhost:8081` builds and pushes demo
gems, signs in, installs through the group with `gem` and Bundler (own gems plus rake and rack
from rubygems.org), yanks and unyanks, and repeats the installs in offline mode with empty local
caches.
