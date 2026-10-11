# Raw repositories

One module (`granary-format-raw`) provides eight format ids that share the same file-oriented
implementation and differ in their templates, metadata rules, index generation and client snippets:
`raw`, `cdn`, `cpan`, `cran`, `hackage`, `vcpkg`, `bower` and `elm`.

Repository URL: `http://host:8081/repository/<name>/<path>`. Every profile supports hosted, proxy and
group repositories (vcpkg: hosted only).

## Endpoints

Paths are free-form below the repository URL; the profiles add their own documents on top.

| Method | Path | Purpose |
|--------|------|---------|
| `GET`, `HEAD` | `<path>` | Download a file (with `Range`, `ETag`, `If-None-Match`) |
| `GET` | `<dir>/` (and the root `/`) | Directory listing, HTML or JSON |
| `PUT` | `<path>` | Upload a file, raw body or the first file part of a multipart form (hosted) |
| `POST` | `<dir>/` | Upload every file part of a multipart form (hosted) |
| `DELETE` | `<path>`, `<dir>/` | Delete a file or everything below a directory (hosted, and cached files of a proxy) |
| `POST` | `packages` (bower) | Register a package name |
| `POST` | `all-packages`, `all-packages/since/<n>`, `search.json` (elm) | The Elm compiler's registry reads |

Generated indexes: CPAN `modules/02packages.details.txt(.gz)`, CRAN `src/contrib/PACKAGES(.gz)`,
Bower `packages` and `packages/search/<q>`, Elm `all-packages` and `endpoint.json` (see the
profiles).

## Standard repositories

`POST /api/v1/repositories/standard` creates every repository listed here:

| Format id | Display name                               | Standard repositories (type, upstream)                                                                        |
|-----------|--------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| `raw`     | Raw (generic files)                        | `raw-hosted` (hosted)                                                                                         |
| `cdn`     | Browser CDN assets (cdnjs, jsDelivr, unpkg)| `cdnjs` → `https://cdnjs.cloudflare.com/ajax/libs/`, `jsdelivr` → `https://cdn.jsdelivr.net/`, `unpkg` → `https://unpkg.com/` |
| `cpan`    | CPAN (Perl)                                | `cpan-proxy` → `https://www.cpan.org/`                                                                        |
| `cran`    | CRAN (R)                                   | `cran-proxy` → `https://cloud.r-project.org/`                                                                 |
| `hackage` | Hackage / Stackage (Haskell)               | `hackage-proxy` → `https://hackage.haskell.org/`, `stackage-proxy` → `https://www.stackage.org/`, `stackage-snapshots` → `https://raw.githubusercontent.com/commercialhaskell/stackage-snapshots/master/` |
| `vcpkg`   | vcpkg (C/C++)                              | `vcpkg-cache` (hosted; the only type this profile supports)                                                   |
| `bower`   | Bower                                      | `bower-registry` → `https://registry.bower.io/`                                                               |
| `elm`     | Elm packages                               | `elm-proxy` → `https://package.elm-lang.org/`                                                                 |

Hosted repositories and groups of the other profiles (`cpan-hosted`, `cran-group`, …) are created by
hand; the examples below use such names.

## Shared behaviour

**Hosted**

* `PUT <path>` with the raw body stores a file (`201` with `{"path","size","sha256"}`). A multipart
  form works too: `PUT` takes the first file part, `POST` to a directory path (`uploads/`) stores
  every file part under its own file name, repeated field names included
  (`curl -F file=@a.zip -F file=@b.zip`), and answers `{"stored":[…]}`.
* Overwriting an existing path is refused with `409 repository.raw.exists` unless *allow redeploy*
  is set on the repository (vcpkg additionally accepts byte-identical re-uploads, see below).
  Uploads of the same path, and of files of the same component, are serialised: of two concurrent
  uploads of one path exactly one gets `201`, the other `409`.
* `GET`/`HEAD <path>` stream the file with `ETag` (the blob SHA-256), `Last-Modified`,
  `X-Checksum-Sha256`, `If-None-Match` (including `*`) and `Range` support. Immutable files get
  `Cache-Control: max-age=…`, metadata gets `no-cache`.
* Downloads name the file in `Content-Disposition` (`inline; filename="app-1.2.3.zip"`, or
  `attachment` with the setting below), so browsers keep the real file name.
* HTML, XHTML, SVG, XML and XSLT files are served with
  `Content-Security-Policy: sandbox allow-downloads allow-forms allow-modals allow-popups allow-presentation allow-scripts`:
  an uploaded page still renders and runs its scripts, but in an opaque origin, so it cannot read
  the session of the Granary UI served from the same host.
* Methods a repository does not support answer `405` with an `Allow` header.
* `DELETE <path>` removes a file, `DELETE <dir>/` removes everything below it (`204`, or `404`).
  Deleting the repository root is refused.
* Directory listings: `GET <dir>/` (and the root `/`) answer an Apache-style HTML table, or JSON
  (`{"repository","path","entries":[{"name","type","href","size","lastModified"}]}`) when the
  `Accept` header prefers `application/json`. Names are HTML-escaped; links are URL-encoded.
* Uploads need write access; reads follow the anonymous-read setting like every other format.

**Proxy**

* `<remoteUrl>/<path>` is fetched verbatim and cached with the upstream `Content-Type`. Redirects are
  followed and the final bytes are stored under the requested path. Immutable paths are cached
  forever, metadata paths are re-validated after *metadata max age* and served stale when the
  upstream is down. Upstream `404`s are remembered for the negative-cache TTL.
* An uncached path behind an unreachable upstream is `503 upstream.unavailable`; in offline mode it
  is `503 upstream.offline`. Proxies take no uploads (`405 repository.readOnly`); `DELETE` removes
  cached files.

**Group**

* Reads take the first member that has the path (members in order, hosted usually first).
  Listings are merged (first member wins for files, directories are unioned).
* Registry indexes are merged across the members instead: CPAN `modules/02packages.details.txt(.gz)`,
  CRAN `src/contrib/PACKAGES(.gz)`, Bower `packages` and `packages/search/<q>`, Elm `all-packages`
  and `all-packages/since/<n>` (details in the profiles below). Members whose upstream cannot be
  reached are left out; when no member answers, the group answers `503`.
* Writes are refused with `405` naming the hosted member to use instead.

**Paths and metadata**

* Paths may not contain `..` or `.` segments, backslashes, control characters, a leading slash or
  empty segments; they are limited to 1024 characters. Illegal paths are `400 path.invalid`.
  An encoded slash or backslash (`%2F`, `%5C`) in the request URI is refused the same way, so
  one file cannot be stored or read under two spellings.
* `Content-Type` is the value the client declared on upload (form encodings and
  `application/octet-stream` are replaced by a guess from the file name) or the upstream's.
* A component is recorded for every upload: profile-specific coordinates where the profile can
  derive them (CRAN `<pkg>_<ver>.tar.gz`, CPAN `<Dist>-<ver>.tar.gz`, Hackage `<pkg>-<ver>`, vcpkg
  `binaries/<name>/<version>/…`, cdn `npm/<pkg>@<ver>/…`, Elm `packages/<author>/<project>/<ver>/…`),
  otherwise namespace `""`, name = directory, version = file name.

## Settings

All eight profiles share these keys (`formatSettings` of the repository; every repository type):

| Key                      | Type    | Default              | Meaning                                                                                           |
|--------------------------|---------|----------------------|---------------------------------------------------------------------------------------------------|
| `raw.metadataPatterns`   | string  | per profile (below)  | Comma-separated globs (`**/PACKAGES*,modules/*.gz`). Matching paths are mutable metadata, everything else is immutable. Setting it replaces the profile default; profile-specific rules (cdn version pins) still apply. |
| `raw.contentDisposition` | string  | `inline`             | `attachment` makes downloads `Content-Disposition: attachment; filename=…` instead of `inline; filename=…`. |
| `raw.listingEnabled`     | boolean | `true`               | `false` answers `404` for directory paths instead of a listing.                                   |

Default metadata patterns per profile are listed in the profiles below; vcpkg has none.

## Client configuration

Each profile below shows its client configuration (curl, wget and PowerShell for `raw`, browsers and
bundlers for `cdn`, cpanm, R, cabal and stack, vcpkg, bower and the Elm compiler). The *Connect*
panel of a repository renders the same snippets with the repository URL and your user name.

## Profiles

### `raw` – generic files

Installers, archives, build outputs, Debian/RPM files as plain files, `latest/` links. Default
metadata: `**/latest/**`, `**/index.html`, `**/index.json`, `**/Release`, `**/InRelease`,
`**/Release.gpg`, `**/Packages`, `**/Packages.*`, `**/Sources`, `**/Sources.*`, `**/repodata/**`.

```bash
curl -u admin:gry_… -T ./app-1.2.3.zip http://granary:8081/repository/raw-hosted/releases/app/1.2.3/app-1.2.3.zip
curl -u admin:gry_… -F file=@app-1.2.3.zip http://granary:8081/repository/raw-hosted/releases/app/1.2.3/app-1.2.3.zip
curl -fsSLO http://granary:8081/repository/raw-hosted/releases/app/1.2.3/app-1.2.3.zip
wget http://granary:8081/repository/raw-hosted/releases/app/1.2.3/app-1.2.3.zip
curl -H 'Accept: application/json' http://granary:8081/repository/raw-hosted/releases/app/
curl -u admin:gry_… -X DELETE http://granary:8081/repository/raw-hosted/releases/app/1.2.3/app-1.2.3.zip
```

```powershell
Invoke-WebRequest -Uri http://granary:8081/repository/raw-hosted/releases/app/1.2.3/app-1.2.3.zip -OutFile app-1.2.3.zip
$cred = Get-Credential -UserName "admin" -Message "API token"
Invoke-RestMethod -Method Put -Uri http://granary:8081/repository/raw-hosted/releases/app/1.2.3/app-1.2.3.zip -InFile .\app-1.2.3.zip -Credential $cred
```

The connect panel also shows Gradle/Maven download hints and the vcpkg `x-azurl`/`http` lines.

### `cdn` – browser CDN assets

Default metadata: `libraries/**`, `api/**`, `libraries`, plus every path that does not pin a version.

Proxies of cdnjs, jsDelivr and unpkg (a hosted repository works as well, for example for a
`vite build` output). A path is immutable when it pins an exact version (`npm/lodash@4.17.21/…`,
`lodash@4.17.21/…`, cdnjs `jquery/3.7.1/…`); unpinned paths (`npm/lodash/…`, `npm/lodash@latest/…`,
`npm/lodash@^4/…`, `lodash/lodash.js`) and the cdnjs `libraries/**` / `api/**` metadata are
re-validated. unpkg and jsDelivr answer redirects for unpinned URLs; the final bytes are cached
under the requested path.

```html
<script src="http://granary:8081/repository/jsdelivr/npm/lodash@4.17.21/lodash.min.js"></script>
<script type="importmap">{"imports":{"lodash-es":"http://granary:8081/repository/jsdelivr/npm/lodash-es@4.17.21/+esm"}}</script>
```

Browsers cannot send API tokens, so CDN repositories are meant to be read anonymously. Vite
(`base`) and webpack (`output.publicPath`) hints point at a hosted directory holding a build.

### `cpan` – Perl

Default metadata: `modules/02packages.details.txt.gz`, `modules/02packages.details.txt`,
`modules/03modlist.data.gz`, `modules/03modlist.data`, `modules/06perms.txt.gz`,
`modules/06perms.txt`, `modules/02STAMP`, `modules/01modules.index.html`,
`modules/01modules.mtime.html`, `authors/01mailrc.txt.gz`, `authors/01mailrc.txt`, `indices/**`,
`modules/by-module/**`, `modules/by-authors/**`, `modules/by-category/**`,
`authors/id/**/CHECKSUMS`, `authors/00whois.xml`, `MIRRORED.BY`, `RECENT*`, `**/RECENT*`.

Hosted: upload distributions to `authors/id/<A>/<AB>/<ABC>/<Dist>-<ver>.tar.gz` (`.tgz`, `.zip`,
`.tar.bz2`, `.tar.xz` are accepted too). `modules/02packages.details.txt` and `.txt.gz` are
regenerated after every upload or delete, listing the highest version of every module the
distributions provide (from `META.json` `provides`, otherwise from the `package` and `$VERSION`
lines of `lib/**/*.pm`). Both index files are also generated on first request.

Group: `modules/02packages.details.txt` and `.txt.gz` are merged from every member's index (the
first member that lists a module wins), so one `--mirror` URL serves hosted distributions and the
proxied CPAN together.

```bash
cpanm --mirror http://granary:8081/repository/cpan-proxy/ --mirror-only Acme::Meow
export PERL_CPANM_OPT="--mirror http://granary:8081/repository/cpan-proxy/ --mirror-only"
# cpan shell
o conf urllist http://granary:8081/repository/cpan-proxy/
o conf commit
# carton
export PERL_CARTON_MIRROR=http://granary:8081/repository/cpan-proxy/
# credentials go into the URL when anonymous read is off: http://user:gry_…@granary:8081/…
```

### `cran` – R

Default metadata: `src/contrib/PACKAGES`, `src/contrib/PACKAGES.gz`, `src/contrib/PACKAGES.rds`,
`src/contrib/PACKAGES*`, `bin/**/PACKAGES*`, `src/contrib/Meta/**`, `bin/**/Meta/**`, `web/**`.

Hosted: upload source packages to `src/contrib/<pkg>_<ver>.tar.gz`. `src/contrib/PACKAGES` and
`PACKAGES.gz` are regenerated from each tarball's `<pkg>/DESCRIPTION` (`Package`, `Version`,
`Depends`, `Imports`, `LinkingTo`, `Suggests`, `Enhances`, `License`, `License_is_FOSS`,
`License_restricts_use`, `OS_type`, `Archs`, `MD5sum`, `NeedsCompilation`, `Path`), with the newest
version of every package. Older versions stay downloadable through
`src/contrib/Archive/<pkg>/<pkg>_<ver>.tar.gz`. `PACKAGES.rds` is not generated; R falls back to
`PACKAGES.gz`/`PACKAGES`. Binary packages under `bin/**` are stored but not indexed.

Group: `src/contrib/PACKAGES` and `PACKAGES.gz` are merged from every member (one entry per package
and version, first member wins). `src/contrib/PACKAGES.rds` answers `404` in a group (`503` while a
proxy member's upstream is unreachable), so R reads the merged `PACKAGES.gz` rather than one member's
`.rds`.

```r
# ~/.Rprofile
options(repos = c(CRAN = "http://granary:8081/repository/cran-proxy/"))
install.packages("praise", repos = "http://granary:8081/repository/cran-proxy/")
# renv.lock: "Repositories": [{"Name": "CRAN", "URL": "http://granary:8081/repository/cran-proxy/"}]
```

```bash
R CMD build mypkg
curl -u admin:gry_… -T mypkg_0.1.0.tar.gz http://granary:8081/repository/cran-hosted/src/contrib/mypkg_0.1.0.tar.gz
```

### `hackage` – Haskell

Default metadata: `01-index.tar.gz`, `01-index.tar`, `01-index.tar.idx`, `00-index.tar.gz`,
`00-index.tar`, `root.json`, `timestamp.json`, `snapshot.json`, `mirrors.json`,
`packages/index.tar.gz`, `package/*/preferred`, `package/*/preferred-versions`,
`preferred-versions`, `*/cabal.config`, `snapshots.json`, `snapshots/**`, `lts*`, `nightly*`,
`lts-*/**`, `nightly-*/**`, `lts/**`, `nightly/**`. `package/<pkg>-<ver>.tar.gz`,
`package/<pkg>-<ver>/<pkg>.cabal` and `package/<pkg>-<ver>/revision/<n>.cabal` are immutable.

```text
-- ~/.cabal/config (or ~/.config/cabal/config): change the url of the existing entry
repository hackage.haskell.org
  url: http://granary:8081/repository/hackage-proxy/
```

The TUF metadata (`root.json`, `timestamp.json`, `snapshot.json`, `mirrors.json`) passes through
unchanged, so cabal keeps verifying the index with the Hackage root keys it ships with. A secure
repository under a name of its own (`repository hackage-proxy`) needs `secure: True`, the root key
ids of Hackage's `root.json` (`signed.roles.root.keyids`; the `cabal-secure-named` snippet lists
them) and `key-threshold: 3`. cabal never sends credentials for secure repositories, not even the
ones in the URL, so secure use needs anonymous read. `secure: False` reads `00-index.tar.gz`
without signature checks and sends the credentials of the URL
(`url: http://admin:gry_…@granary:8081/repository/hackage-proxy/`).

Stack (pantry) takes the repository root as `download-prefix` and checks the signatures with its
own copy of the root keys; it does send the credentials of the URL:

```yaml
# ~/.stack/config.yaml
package-index:
  download-prefix: http://granary:8081/repository/hackage-proxy/
# stack.yaml: snapshot definitions through a proxy of the stackage-snapshots GitHub repository
snapshot-location-base: http://granary:8081/repository/stackage-snapshots/
```

A hosted hackage repository stores files but does not generate an index.

### `vcpkg` – C/C++ asset and binary caches

Hosted only, no default metadata patterns. vcpkg's asset cache does `GET`/`PUT <url>/assets/<sha512>` with `x-ms-blob-type:
BlockBlob`; the binary cache does `GET`/`HEAD`/`PUT <url>/binaries/<name>/<version>/<sha>` of zip
archives. The Azure headers are accepted and ignored, `PUT` answers `201` without a body (like Azure
blob storage; vcpkg copies upload response bodies into its console output), and re-uploading
byte-identical content is accepted (vcpkg uploads without checking first); different content for an
existing path is `409`. The registry itself (ports) stays a git checkout; only the caches go through
Granary.

Uploads need credentials. The `http` binary source takes a header, which vcpkg keeps out of its
output; the `x-azurl` asset source only takes credentials in the URL, and vcpkg prints asset cache
URLs (credentials included) while it downloads, so keep the logs of builds that use them private, or
let only a CI job with its own token write to the asset cache:

```bash
export X_VCPKG_ASSET_SOURCES="x-azurl,http://admin:gry_…@granary:8081/repository/vcpkg-cache/assets/,,readwrite"
export VCPKG_BINARY_SOURCES="clear;http,http://granary:8081/repository/vcpkg-cache/binaries/{name}/{version}/{sha},readwrite,Authorization: Bearer gry_…"
# read-only, while anonymous read is on:
export X_VCPKG_ASSET_SOURCES="x-azurl,http://granary:8081/repository/vcpkg-cache/assets/,,read"
export VCPKG_BINARY_SOURCES="clear;http,http://granary:8081/repository/vcpkg-cache/binaries/{name}/{version}/{sha},read"
```

Add `;x-block-origin` to `X_VCPKG_ASSET_SOURCES` to make vcpkg take sources only from the cache.

### `bower` – registry

Default metadata: `packages`, `packages/*`, `packages/search/*`, `packages/search/**`. The registry only maps names to git
URLs; bower clones that URL itself, so Granary caches the registry answers, not the repositories.

Hosted registry: `POST packages` (form fields `name` and `url`, or a JSON body) stores
`{"name":…,"url":…}` under `packages/<name>` (`201`; `409 bower.nameTaken` when the name is already
taken with a different URL), `GET packages` lists, `GET packages/<name>` looks one up,
`GET packages/search/<q>` searches by substring, `DELETE packages/<name>` unregisters.
Registrations of one name are serialised, so two concurrent `POST`s cannot both claim it.

Proxy and group: `packages` and `packages/search/<q>` of a group merge the members' answers. A proxy
whose upstream cannot be reached and has nothing cached answers `503` rather than an empty list.

```json
// .bowerrc
{ "registry": "http://granary:8081/repository/bower-registry/",
  "storage": { "packages": ".bower-cache/packages", "registry": ".bower-cache/registry" } }
```

```bash
npm install -g bower
bower install jquery --config.registry=http://granary:8081/repository/bower-registry/
bower register my-widget https://git.example.com/ui/my-widget.git --config.registry=http://granary:8081/repository/bower-hosted/
```

### `elm` – packages

Default metadata: `all-packages`, `all-packages/since/*`, `search.json`,
`packages/*/*/releases.json`, `packages/*/*/*/endpoint.json`. The compiler `POST`s the registry
documents, so `POST` of `all-packages`, `all-packages/since/<n>` and `search.json` only needs read
access.

Proxy: `endpoint.json` has its `url` rewritten from GitHub's zipball to
`<repository>/github/<author>/<project>/zip/<version>`; that route proxies
`https://github.com/<author>/<project>/zipball/<version>` and is immutable. The `hash` stays.

Hosted: upload `packages/<author>/<project>/<version>/{elm.json,docs.json,README.md}` and the
release zip into the same directory. `all-packages` and `all-packages/since/<n>` are computed from
the uploaded releases; `endpoint.json` is generated from the zip (its URL and SHA-1) when it was not
uploaded.

The zip route only accepts `github/<author>/<project>/zip/<major>.<minor>.<patch>` with GitHub-style
names (letters, digits, `-`, `_`, `.`); other `github/…` paths are proxied from the package site
like any other path (where they do not exist), and `endpoint.json` URLs that do not fit that shape
are left unchanged. A proxy whose upstream cannot be reached and has nothing cached answers `503`.

Group: the compiler keeps a count of the releases it knows and asks for
`all-packages/since/<count>`, so a group keeps one release list per group that only grows at its end:
a release any member gains later (a hosted upload, a new upstream release) is appended and handed out
exactly once. `all-packages` of a group is built from the same list. The list is kept in memory; after
a restart it is rebuilt from the members, and a compiler that had not caught up before the restart may
need its registry cache (`~/.elm/0.19.1/packages/registry.dat`) deleted to see the releases it missed.

The Elm compiler has no registry setting (it hard-codes `https://package.elm-lang.org`), so Granary
is used with a hosts/DNS override plus a TLS reverse proxy, or with a tool such as
`elm-package-proxy`:

```nginx
server {
  listen 443 ssl;
  server_name package.elm-lang.org;
  location / { proxy_pass http://granary:8081/repository/elm-proxy/; }
}
```

## Offline behaviour

* Immutable files a proxy has cached are served without contacting the upstream; metadata paths
  (the profile's patterns, or `raw.metadataPatterns`) are re-validated after *metadata max age* and
  served stale while the upstream is down or offline mode is on.
* An uncached path is `503 upstream.offline` in offline mode and `503 upstream.unavailable` while the
  upstream is unreachable, never a `404`.
* Merged indexes (CPAN, CRAN, Bower, Elm) leave out members whose upstream cannot be reached; a
  group answers `503` only when no member answers.
* The verification script re-runs cpanm, cabal, raw and cdn downloads offline against the cache.

## Verification

`granary-format-raw/scripts/verify-raw.sh <base-url>` creates the repositories it needs and drives
the real clients against a running server (with names unique to each run, so it can run again against
the same server; set `TMPDIR` to put its work directory, which briefly holds the 1 GB Hackage index,
somewhere roomy):

* curl/wget: uploads (raw, multipart, 32 MB), `409`/`401`/`405`, listings, `HEAD`, ETag and
  `If-None-Match`, ranges, encoded slashes and `..` (`400`), sandboxed HTML, eight concurrent uploads
  of one path (one `201`, seven `409`) and deletes;
* `cpanm --mirror … --mirror-only` through `cpan-proxy` (App::cpanminus, Acme::Meow), from
  `cpan-hosted` with its regenerated `02packages` index, and through `cpan-group` (both at once);
* `R CMD build` plus `install.packages` from `cran-hosted` and through `cran-group`, and
  `install.packages("praise")` through `cran-proxy` (`docker run r-base:4.5.1`);
* cabal (`docker run haskell:9.6`) with each Hackage snippet: secure under Hackage's own name,
  secure under a name of its own with the listed root keys, and `secure: False` with credentials in
  the URL while anonymous read is off; then `cabal get` of a proxied tarball, and `stack update`
  with the `package-index` snippet;
* `npx bower install` from a registry entry in `bower-hosted` (and through `bower-registry` when
  registry.bower.io is reachable);
* vcpkg asset and binary cache calls with the Azure headers, and, when `VCPKG_ROOT` names a vcpkg
  checkout with a vcpkg binary, the real vcpkg with an overlay port: a build that uploads the source
  and the package with the snippet's lines, a restore from the binary cache and a build from the
  asset cache with `x-block-origin`;
* the Elm hosted registry documents, and the real Elm compiler (from npm, in docker) behind a local
  TLS reverse proxy for package.elm-lang.org pointing at `elm-group`: a build with an empty package
  cache, then a release uploaded afterwards that the compiler finds through `all-packages/since/<n>`;
* the jsDelivr SHA-256 comparison with the direct download, and an offline re-run of the cached
  content (cpanm, cabal, raw, cdn).

Steps whose public upstream cannot be reached from the host are reported as `SKIP` together with a
check that the proxy answers a clean `503` instead. In a CRAN group, `PACKAGES.rds` is `404` (or `503`
while a proxy member's upstream is unreachable); R falls back to `PACKAGES.gz` in both cases.

## Limitations

* Hosted hackage repositories do not generate `01-index.tar.gz`; use them for mirrored files only.
* CRAN: `PACKAGES.rds` is not generated and `bin/**` binary packages are not indexed. The
  `DESCRIPTION` fields listed above are copied as they are (no dependency resolution).
* CPAN: `CHECKSUMS`, `03modlist.data.gz` and `06perms.txt.gz` are not generated for hosted
  repositories; `cpanm --mirror-only` does not need them (`cpanm --verify` would).
* cdn: credentials cannot be sent by browsers; keep anonymous read on or use a hosted copy.
* Elm: the zip route follows the package site's convention and needs `github.com` reachable from
  the server; `search.json` is proxied as is. The proxy's upstream credentials are sent to
  `github.com` with those downloads.
* Identical-overwrite detection (vcpkg) spools the upload to `<data-dir>/tmp/vcpkg` to compare
  digests before deciding.
