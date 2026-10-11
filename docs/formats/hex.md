# Hex repositories

Format id: `hex`. Repository URL: `http://host:8081/repository/<name>` (what `mix hex.repo add`,
`HEX_MIRROR` and rebar3's `repo_url` take). Hex API URL: `http://host:8081/repository/<name>/api`
(what `HEX_API_URL` and rebar3's `api_url` take).

Works with mix (Hex 2.x, verified with Hex 2.5.1 on Elixir 1.18 / OTP 27) and rebar3 (verified with
3.27, publishing with the rebar3_hex 7.3 plugin), because Granary implements the Hex repository specification (signed, gzipped protobuf registry
documents and release tarballs) and the parts of the Hex HTTP API those clients call, in both of its
encodings (`application/vnd.hex+erlang` and JSON).

`GET /repository/<name>/` answers a small JSON description of the repository, including the
fingerprint of the key its documents are signed with.

Paths are checked before anything is looked up: `..`, backslashes, encoded slashes (`%2F`, `%5C`) and
names that are not Hex package names are answered with `400` in every repository type.

## Endpoints

Every path is relative to the repository URL.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `names`, `versions` | Signed registry documents: every package, every version |
| `GET` | `packages/<name>` | Signed registry document of one package (releases, checksums, dependencies, retirements) |
| `GET`, `HEAD` | `tarballs/<name>-<version>.tar` | Release tarball |
| `GET` | `public_key` | The key the registry documents are signed with |
| `GET` | `docs/<name>-<version>.tar.gz` | Documentation tarball |
| `GET` | `installs/…` | Hex and rebar installation files (`mix local.hex`, `mix local.rebar`) |
| `POST` | `api/publish`, `api/packages/<name>/releases` | Publish a release (hosted) |
| `GET`, `DELETE` | `api/packages/<name>/releases/<version>` | Release details, revert |
| `POST`, `PUT`, `DELETE` | `api/packages/<name>/releases/<version>/retire` | Retire, unretire |
| `POST`, `PUT`, `DELETE` | `api/packages/<name>/releases/<version>/docs` | Upload, remove documentation |
| `GET` | `api/packages`, `api/packages/<name>`, `api/packages/<name>/releases` | Search, package details, releases |
| `GET` | `api/users/me` | `mix hex.user whoami` |
| `GET`, `POST`, `DELETE` | `api/keys`, `api/keys/<name>` | API keys (`POST` with a password creates a Granary API token) |

`api/repos/<repository>/…` is accepted as well (hex.pm's prefix for organisation repositories); the
repository is the one in the URL.

## Standard repositories

| Name | Type | Upstream / members |
| --- | --- | --- |
| `hexpm` | proxy | `https://repo.hex.pm/` |
| `hex-hosted` | hosted | |
| `hex-group` | group | `hex-hosted`, `hexpm` |

## Signing

Clients verify the signature of every registry document. Hosted repositories and groups sign theirs
with one RSA key per server, created on first use under `<dataDir>/hex/` (`registry_signing_key.pem`,
`public_key.pem`) and served at `/repository/<name>/public_key`. Back the directory up with the rest of
the data directory: with a new key, clients have to add the repositories again with the new key.

Clients pin the key when the repository is added, either by fingerprint
(`--fetch-public-key SHA256:…`, shown on the repository's connect page and in the JSON above) or from
the PEM file (`--public-key`, rebar3's `repo_public_key`).

Proxies do not re-sign anything: they pass hex.pm's documents through unchanged, so hex.pm's own key
keeps verifying them.

## Hosted

* `mix hex.publish` (the release; `mix hex.publish package`), the legacy `POST api/publish`,
  `mix hex.publish --revert VERSION`, `mix hex.retire` / `--unretire`, `mix hex.info`,
  `mix hex.package search`, `mix hex.user whoami`, and documentation upload
  (`POST api/packages/<name>/releases/<version>/docs`, served at `docs/<name>-<version>.tar.gz`).
* rebar3 publishes with the rebar3_hex plugin: `rebar3 hex publish --repo NAME` (release and, with
  `{hex, [{doc, edoc}]}`, its documentation), `publish package`, `publish --revert VERSION`,
  `publish docs --revert VERSION` and `rebar3 hex retire`. The repository map needs `api_url`,
  `api_key` (a Granary API token: `rebar3 hex user auth` is hex.pm's OAuth device flow) and
  `api_organization => undefined, api_repository => undefined`, because rebar3 fills in defaults only
  for `hexpm` and rebar3_hex fails without them (see Client configuration).
* Release tarballs are checked on upload: tarball format version 3, the inner `CHECKSUM` recomputed,
  `metadata.config` parsed (name, version, requirements), and the name must match the URL. The outer
  checksum is computed by Granary and published in the registry. Anything else gets the Hex API's
  `422` validation error, which mix prints as is.
* Re-publishing an existing release is refused with `422 must include the --replace flag`, like
  hex.pm. With `--replace` (`?replace=true`) it is still refused unless *allow redeploy* is set on the
  repository.
* `mix hex.publish --revert` removes the release, its tarball and its documentation. Developers can
  revert within `hex.revertWindowMinutes` of publishing (60 by default; 0 means no limit).
  Administrators can revert at any time. Reverting the last release removes the package.
* Retirement reasons are the Hex ones (`other`, `invalid`, `security`, `deprecated`, `renamed`), and
  the message is limited to 140 characters. Retired releases are flagged in the registry, so
  `mix deps.get` prints its `RETIRED!` warning.
* After every change Granary regenerates and signs `names`, `versions` and `packages/<name>`. A
  document that disagrees with the stored tarballs is rebuilt when it is next served, for example
  after a release is deleted in the component browser.
* Authentication: the API key is a Granary API token, sent by mix as the bare value of `Authorization`
  for the repository (`--auth-key`) and for the API (`HEX_API_KEY`). Basic and Bearer work too.
  `POST api/keys` with a username and password (Basic) creates a token. Publishing, reverting and
  retiring need write access; reading needs read access, or nothing when anonymous read is on.

## Proxy

* `remoteUrl` is the repository root, `https://repo.hex.pm/` by default.
* `names`, `versions`, `packages/<name>` and `public_key` are documents: they are refreshed after
  *metadata max age* and served from the cache while the upstream is unreachable. Tarballs and
  documentation are immutable and cached forever, and download counts are kept per release.
* Documents are passed through byte for byte with hex.pm's signature, so the proxy is a drop-in
  `HEX_MIRROR` (mix) or `HEX_CDN` (rebar3). `installs/…` (what `mix local.hex` and
  `mix local.rebar` read) is passed through the same way.
* Offline use is described under [Offline behaviour](#offline-behaviour).
* The Hex API (`api/…`) is not proxied, and publishing to a proxy is refused with `405`.

## Group

* Members' documents are decoded, merged and signed with Granary's key under the group's repository
  name. The merged document has the union of packages and versions; when two members have the same
  version, the first member in group order wins. Retirements and advisories travel with the release.
* A proxy member's documents are verified against that proxy's `public_key` before they are merged
  (`hex.verifyUpstreamSignatures`, on by default). A document that fails verification is left out
  of the group and logged. A proxy caches its upstream's `public_key` along with the first document
  it serves, so a group can still verify what the proxy cached while the upstream is unreachable.
* Each tarball is served by the member whose registry lists the version.
* Groups are read-only: every write is refused with `405` and a message naming the hosted member to
  publish to. `mix hex.info` and package search against a group read its hosted members.
* `installs/…` comes from the first member that has it. A group without a proxy member answers an
  empty `installs/hex-1.x.csv`, like a hosted repository.
* A dependency keeps the repository named in its release metadata. A hosted package that depends on
  a hex.pm package (`repository: "hexpm"`) therefore resolves that dependency through the client's
  `hexpm` repository (point it at the proxy with `HEX_MIRROR`), not through the group.

## Client configuration

mix, hosted repository or group:

```bash
mix hex.repo add hex-group http://granary.example.com:8081/repository/hex-group \
  --fetch-public-key SHA256:… --auth-key gry_…        # --auth-key only without anonymous read
```

```elixir
# mix.exs
defp deps do
  [{:my_package, "~> 0.1", repo: "hex-group"}]
end
```

```bash
export HEX_API_URL=http://granary.example.com:8081/repository/hex-hosted/api
export HEX_API_KEY=gry_…
mix hex.publish package --yes
```

mix, hex.pm through the proxy:

```bash
export HEX_MIRROR=http://granary.example.com:8081/repository/hexpm
mix deps.get
```

rebar3 (`~/.config/rebar3/rebar.config`, or the project's `rebar.config`):

```erlang
{hex, [{repos, [#{name => <<"hex-group">>,
                  repo_url => <<"http://granary.example.com:8081/repository/hex-group">>,
                  repo_key => <<"gry_…">>,
                  repo_public_key => <<"-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----\n">>}]}]}.
```

and `export HEX_CDN=http://granary.example.com:8081/repository/hexpm` for hex.pm through the proxy.

Publishing with rebar3 (`{plugins, [rebar3_hex]}.` in the project): rebar3 merges repositories by
name, so the API key can stay in the global config while the project's `rebar.config` names the
repository for reads:

```erlang
%% ~/.config/rebar3/rebar.config
{hex, [{repos, [#{name => <<"hex-hosted">>,
                  api_url => <<"http://granary.example.com:8081/repository/hex-hosted/api">>,
                  api_key => <<"gry_…">>,
                  api_organization => undefined,
                  api_repository => undefined}]}]}.
```

```bash
rebar3 hex publish --repo hex-hosted --yes
```

The connect page of every Hex repository shows these snippets with the server's URL, key and
fingerprint filled in.

`granary-format-hex/scripts/verify-hex.sh http://host:8081` runs these flows end to end with the real
mix and rebar3 in the `hexpm/elixir` Docker image, including offline mode. When builds.hex.pm is not
reachable, it builds the Hex archive from its git tag (`HEX_VERSION`, 2.5.1 by default) and downloads
rebar3 from `REBAR3_URL`; a prepared Hex archive (`HEX_ARCHIVE`) or rebar3 escript (`REBAR3`) can be
passed instead. The rebar3_hex plugin and its dependencies (hex_core, verl) are cloned from their git
tags into the publishing project's `_checkouts`, so `rebar3 hex publish` is verified without hex.pm.

## Settings

| Key | Applies to | Default | Meaning |
| --- | --- | --- | --- |
| `hex.repositoryName` | hosted, group | the Granary repository name | The name clients give the repository (`mix hex.repo add NAME`, `repo: "NAME"`, rebar3 `name`). Signed documents carry it, and clients refuse a mismatch. Letters, digits, `_`, `-`, `.`. |
| `hex.revertWindowMinutes` | hosted | `60` | How long after publishing a developer can still revert a release. `0` means no limit. |
| `hex.verifyUpstreamSignatures` | group | `true` | Groups check each proxy member's documents against that proxy's `public_key` before merging. |

## Offline behaviour

* While a proxy is offline, or the server is in offline mode, only cached content is served; cached
  documents are also served stale while the upstream is unreachable. A document or tarball that is
  not cached is answered with `503 upstream.offline` (or `503 upstream.unavailable`), never `404`,
  so mix does not report a package that exists as missing.
* A proxy caches the upstream's `public_key` with the first document it serves, so a group keeps
  verifying, merging and re-signing what its proxy members cached while their upstream is
  unreachable.

## Limitations

* No organizations (`hexpm:org` repositories), package owners, or ownership transfer.
* `mix hex.user auth` uses hex.pm's OAuth device flow, which Granary does not implement. Use an API
  token (`HEX_API_KEY`, `--auth-key`) instead.
* Documentation is stored and served as the uploaded tarball. It is not unpacked or rendered as a
  hexdocs site, and `mix hex.search` (a hexdocs search) does not apply.
* The hex.pm API is not proxied, so `mix hex.info` for a hex.pm package needs hex.pm itself.
* `rebar3 hex user auth` (hex.pm's OAuth device flow) does not work; rebar3 publishes with an API
  token in the repository map instead.
