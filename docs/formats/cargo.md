# Cargo registries

Format id: `cargo`. Index URL: `sparse+http://host:8081/repository/<name>/index/`.

Works with cargo (1.68 and later, which speak the sparse registry protocol) because Granary
implements the two halves of a cargo registry: the sparse index cargo reads to resolve
dependencies and the registry web API it writes through. `cargo build`, `fetch`, `add`, `info`,
`search`, `login`, `publish`, `yank` and `owner` all work against Granary like against crates.io.
The git index protocol is not served.

## Protocol

Every path is relative to the repository URL.

| Request | Answer | Cached by proxies as |
|---|---|---|
| `GET index/config.json` | `{"dl":"<repo>/api/v1/crates","api":"<repo>","auth-required":<bool>}`, generated | (never fetched upstream) |
| `GET index/<dir>/<name>` | the crate's index file: one JSON line per version | metadata |
| `GET api/v1/crates/<name>/<version>/download` | the `.crate` | immutable |
| `GET api/v1/crates?q=<query>&per_page=<n>&page=<p>` | `{"crates":[{"name","max_version","description"}],"meta":{"total":n}}` | (not cached) |
| `PUT api/v1/crates/new` | publish (hosted only) | |
| `DELETE api/v1/crates/<name>/<version>/yank`, `PUT …/unyank` | `{"ok":true}` (hosted only) | |
| `GET api/v1/crates/<name>/owners`, `PUT`, `DELETE` | owners (`{"users":[…]}`), add or remove (hosted only) | |
| `GET me` | an HTML page explaining how to get an API token (`cargo login` prints this URL) | |
| `GET` (repository root) | an HTML page with the `config.toml` lines for this repository | |

`<repo>` in `config.json` is the URL the client used (or the configured base URL), so a group's
`config.json` sends downloads and API calls to the group and every crate comes from Granary, never
from the public hosts.

`<dir>` is what cargo derives from the lower-cased name: `1/<name>` and `2/<name>` for one- and
two-character names, `3/<first character>/<name>` for three, otherwise
`<first two>/<next two>/<name>` (`index/se/rd/serde`). A file under any other directories is a
`404`, so one crate has exactly one URL; the name itself may be spelled in any case. Crate names
and versions are checked before anything is looked up (`400` for anything cargo could not have
sent, including a percent-encoded `/` or `\` anywhere in the path); unknown crates and versions are
a `404`.

Index files and `config.json` are rendered per request with an `ETag` over their content and
`Cache-Control: no-cache`; cargo's conditional requests (`If-None-Match`) get a `304`. Crate
files carry `Content-Length`, `ETag` (the SHA-256), `Last-Modified` and a one-year cache lifetime,
and answer `HEAD`.

Errors use the registry web API's error document, which cargo prints as it is:
`{"errors":[{"detail":"crate `acme` not found"}]}`.

## Standard repositories

`POST /api/v1/repositories/standard` creates:

* `cargo-proxy`: proxy of the crates.io sparse index `https://index.crates.io/`;
* `cargo-hosted`: your own crates;
* `cargo-group`: `cargo-hosted` + `cargo-proxy`, the one registry URL that replaces crates.io.

## Hosted

`cargo publish --registry <name>` uploads the package metadata and the `.crate` in one request.
The answer is `200` with `{"warnings":{"invalid_categories":[],"invalid_badges":[],"other":[]}}`,
and the version is in the index file at once. Granary writes the index line the way crates.io
does:

* `version_req` becomes `req`; a dependency renamed in the manifest
  (`json = { package = "serde_json", … }`) is listed under the name the manifest uses with the real
  crate in `package`; dependencies are sorted by name;
* `cksum` is the SHA-256 of the stored `.crate`, computed by Granary, never taken from the client;
* features that use `dep:` or `?/` syntax move to `features2` with `"v":2`, so older cargos skip
  the version instead of misreading it;
* `links`, `rust_version` and a `pubtime` (the upload time) are added when present.

The publish is refused with `400` and the reason when:

* the body is not the binary publish format (length-prefixed JSON metadata, length-prefixed crate),
  is truncated, has trailing bytes, or the metadata is over 16 MiB or not a JSON object;
* the name is not a valid crate name (an ASCII letter, then letters, digits, `-`, `_`, at most 64
  characters) or the version is not a semantic version;
* a dependency or feature entry is malformed (missing name or requirement, unknown kind, features
  that are not lists of strings);
* the `.crate` is not a gzip-compressed tar archive, an entry is outside `<name>-<version>/`, uses
  `..`, a backslash or a NUL, is anything but a regular file or a directory (links, devices and
  FIFOs are refused, as on crates.io), a header checksum is wrong, it unpacks to more than 4 GiB,
  or there is no `<name>-<version>/Cargo.toml`.

Names follow crates.io's identity rules: names that differ only in case or in `-` versus `_` are
the same crate. Publishing `Acme-Hello` when `acme-hello` exists is refused (`409`, "publish it
under that exact name"), and so is `acme_hello` ("too similar to the existing crate").

Publishing a version that exists (build metadata does not make a new version: `1.0.0+linux` is
`1.0.0`) is refused with `409` and
`{"errors":[{"detail":"crate version `1.0.0` is already uploaded"}]}`, which cargo prints after
"the remote server responded with an error (status 409 Conflict)", unless *allow redeploy* is set
on the repository, in which case the crate file and index line are replaced. cargo itself checks
the index before it uploads, so it usually reports the existing version without sending anything.

* `cargo yank --version <v> <crate>` / `cargo yank --undo` set `yanked` in the index line. A yanked
  version stays downloadable, so lock files that pin it keep working; cargo only stops choosing it
  for new resolutions.
* The publishing user becomes the crate's first owner. `cargo owner --list | --add | --remove`
  read and change the list; an unknown login is `404` ("could not find user with login `x`") and
  removing the last owner is refused. Owners are informational: who may publish is decided by the
  repository write privilege, as for every format.
* `cargo search` finds the crates in the repository by name, description and keywords; exact name
  matches come first, then prefix matches. `max_version` is the highest version that is not yanked
  (a release before a pre-release).
* Components are `("", <name>, <version>)`, so the UI's browser and search show every version, and
  the crate's download count is recorded per version. Deleting a component (UI or REST API) takes
  the version out of the index; it can then be published again.
* Index lines are kept in publish order, as on index.crates.io.

## Proxy

* `remoteUrl` is the root of a sparse index, by default `https://index.crates.io/`. Index files are
  fetched from `<remoteUrl><dir>/<name>`, cached under `index/`, refreshed after *metadata max age*
  (conditionally, with the upstream `ETag`) and served stale while the upstream is unreachable or
  the repository is offline. They are passed through line by line as upstream wrote them.
* The upstream `config.json` is never read or passed on: downloads go to *Upstream download URL*
  (`cargo.downloadUrl`, by default static.crates.io). For another upstream registry, set it to the
  `dl` value of that registry's `config.json`; the crates.io markers `{crate}`, `{version}`,
  `{prefix}`, `{lowerprefix}` and `{sha256-checksum}` are expanded, and a URL without markers gets
  `/{crate}/{version}/download` appended, as cargo does.
* Only versions the index lists are downloaded, and a downloaded `.crate` whose SHA-256 differs from
  the index's `cksum` is discarded and answered with `502` instead of being cached. Crate files are
  immutable and fetched once, also when several clients ask for the same uncached file at the same
  time (the later requests wait for the first download and are served from the cache).
* Upstream `404` for an index file (an unknown crate) is remembered for *negative cache* minutes and
  answered with `404`, which cargo reports as "no matching package".
* An uncached index file or crate while the upstream is down, or while the repository (or the whole
  server) is offline, is a `503` (`upstream.unavailable` / `upstream.offline`): cargo retries it and
  then fails with that status rather than concluding the crate does not exist.
* `cargo search` is passed to the upstream web API (*Upstream web API*, `cargo.apiUrl`, by default
  `https://crates.io`). While that API is unreachable, the repository or server is offline, or the
  setting is `none`, the crates in the proxy's cache are searched instead.
* Proxies are read-only: publish, yank and owner changes answer `405` with cargo's error document;
  owner lists are `404` (crates.io's owners are not mirrored).

## Group

* An index file is the union of the members' lines for that crate, in semantic-version order.
* Hosted members come first, then the others in their configured order, and the first member that
  lists a version owns it: its index line is served and its `.crate` downloaded. A version you
  publish can therefore never be shadowed by a public crate with the same name and version, and the
  `cksum` cargo checks always belongs to the bytes it gets.
* Versions are merged per crate name, so when crates.io has a crate with the same name as one you
  publish, its other versions are in the group's index file too and cargo may pick a newer public
  one for a requirement like `"1"`. Give private crates names that are not taken on crates.io, or
  depend on them through the hosted repository (`registry = "granary-hosted"`), which only lists
  your own versions.
* A member that is switched off (not *online*) contributes nothing. While a proxy member is in
  offline mode or its upstream is down, hosted and already cached crates are still served, and
  anything else is a `503`.
* `cargo search` merges the members, hosted first, one hit per crate.
* `cargo owner --list` answers from the first hosted member that has the crate.
* Groups are read-only; the error names the hosted member to publish to.

## Client configuration

`~/.cargo/config.toml` (or `.cargo/config.toml` in a project), using `cargo-group` for everything
and `cargo-hosted` for publishing:

```toml
# every crates.io dependency comes through Granary
[source.crates-io]
replace-with = "granary"

[source.granary]
registry = "sparse+http://granary.example.com:8081/repository/cargo-group/index/"

# the same index as a registry, for `registry = "granary"` dependencies and `cargo login`
[registries.granary]
index = "sparse+http://granary.example.com:8081/repository/cargo-group/index/"

# where crates are published (a group is read-only)
[registries.granary-hosted]
index = "sparse+http://granary.example.com:8081/repository/cargo-hosted/index/"

[registry]
global-credential-providers = ["cargo:token"]

[net]
retry = 3
```

```bash
# cargo stores the token in $CARGO_HOME/credentials.toml; create one on your Granary account page
cargo login --registry granary-hosted
echo gry_… | cargo login --registry granary          # non-interactive

cargo publish --registry granary-hosted
cargo yank --version 0.1.0 --registry granary-hosted acme-hello
cargo owner --list --registry granary-hosted acme-hello

cargo add acme-hello --registry granary
cargo search acme --registry granary
cargo fetch && cargo build --offline
```

```toml
# Cargo.toml of a crate that must only ever be published to Granary
[package]
publish = ["granary-hosted"]

[dependencies]
acme-hello = { version = "0.1", registry = "granary" }
itoa = "1"   # crates.io, through the source replacement
```

In CI, the same configuration as environment variables:

```bash
export CARGO_REGISTRIES_GRANARY_INDEX=sparse+http://granary.example.com:8081/repository/cargo-group/index/
export CARGO_REGISTRIES_GRANARY_TOKEN=gry_…
export CARGO_REGISTRY_GLOBAL_CREDENTIAL_PROVIDERS=cargo:token
```

### Credentials

cargo sends the token as the bare `Authorization` header value; Granary also accepts it as
`Bearer` or as the password of HTTP Basic. `global-credential-providers = ["cargo:token"]`
(cargo 1.74 and later) selects cargo's built-in provider, which keeps the token in
`$CARGO_HOME/credentials.toml` where `cargo login` writes it; `CARGO_REGISTRIES_<NAME>_TOKEN`
overrides it. cargo only sends the token for publish, yank and owner calls, unless
`config.json` says `"auth-required": true`; then it sends it with every index and download request.
Granary sets `auth-required` when anonymous users may not read the repository (anonymous reads off,
or the anonymous role lacks the privilege) or when *Require a token for downloads*
(`cargo.authRequired`) is set. A cargo without credentials for such a registry gets a `401` on
`config.json` and reports that it needs a token.

Behind TLS with a private certificate authority, point cargo at the CA:

```toml
[http]
cainfo = "/path/to/ca.pem"   # or export CARGO_HTTP_CAINFO=/path/to/ca.pem
```

## Format settings

| Key | Applies to | Default | Meaning |
|---|---|---|---|
| `cargo.authRequired` | all | `false` | advertise `auth-required` in `config.json`, so cargo sends the token on every request |
| `cargo.downloadUrl` | proxy | `https://static.crates.io/crates/{crate}/{crate}-{version}.crate` | where crate files are downloaded from (the upstream's `dl`, with crates.io's markers) |
| `cargo.apiUrl` | proxy | `https://crates.io` | the upstream web API used for `cargo search`; `none` searches the cache only |

Invalid values are refused when the repository is saved (`400 repository.cargo.<key>.invalid`).

## Verification

`granary-format-cargo/scripts/verify-cargo.sh <base-url>` drives a running Granary with the real
cargo client and a throw-away `CARGO_HOME`: `cargo login`, publishing `acme-hello` (whose verify
build resolves `itoa` through the group) and `acme-extra` (a renamed optional dependency and a
`dep:` feature) to `cargo-hosted`, the refused duplicate, `cargo search` on the hosted repository
and on the group (crates.io's API), `cargo owner`, `cargo info`, `cargo yank`/`--undo`,
`cargo add`, `cargo fetch` and `cargo build --offline` of a consumer through `cargo-group` with
crates.io replaced, the same with anonymous reads off, and finally in offline mode with an empty
`CARGO_HOME`: everything is served from the Granary cache and an uncached crate fails with `503`.
Along the way it checks the HTTP surface with curl: `HEAD` and `If-None-Match`, `400` for encoded
slashes and traversal, `401` for anonymous and wrong-token publishes, `405` for a publish through
the group. A run leaves offline mode off and anonymous reads on, also when a step fails.

## Limitations

* Only the sparse protocol is served; cargo versions before 1.68 (git index only) cannot use
  Granary, and a proxy needs a sparse upstream (index.crates.io or another sparse registry), not a
  git index.
* Crate owners are recorded and listed but not enforced; the repository write privilege decides who
  may publish, yank and change owners.
* There is no deletion endpoint in the registry web API (cargo has no command for it). A hosted
  version is removed by deleting its component in the UI or the REST API, which takes it out of the
  index and frees the version number; lock files that pinned it no longer resolve.
* A proxy's search depends on the upstream web API (`cargo.apiUrl`); without it, only cached crates
  are found. A group's search pages beyond the first 100 merged hits are empty.
* For an upstream other than crates.io, `cargo.downloadUrl` must be set by hand to the `dl` of its
  `config.json`; Granary does not read it.
* A proxy authenticates to its upstream with the repository's remote token (sent as `Bearer`) or
  user name and password; a private upstream that only accepts cargo's bare token header cannot be
  proxied.
* Index responses are not compressed.
