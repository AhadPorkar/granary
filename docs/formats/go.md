# Go module repositories

Format id: `go`. GOPROXY URL: `http://host:8081/repository/<name>` (no trailing slash needed).

Works with the `go` command because Granary implements the module proxy protocol it speaks
(`GOPROXY`) and the checksum-database proxy protocol it uses for `GOSUMDB` verification. There is
no Go-specific client to install: `go get`, `go mod download`, `go build`, `go list -m` and
`go mod verify` work against Granary like against proxy.golang.org.

## Endpoints

Every path is relative to the repository URL.

| Request | Answer | Cached by proxies as |
|---|---|---|
| `GET <module>/@v/list` | known versions, one per line, `text/plain` | metadata |
| `GET <module>/@v/<version>.info` | `{"Version":"v1.2.3","Time":"2024-05-01T10:00:00Z"}` | immutable for canonical versions; metadata for branch, commit or version-prefix queries (`master.info`, `v1.2.info`) |
| `GET <module>/@v/<version>.mod` | the version's `go.mod` | immutable |
| `GET <module>/@v/<version>.zip` | the module zip | immutable |
| `GET <module>/@latest` | `.info` of the latest version | metadata |
| `GET sumdb/<name>/supported` | `200` with an empty body when the repository passes that checksum database through, else `404` | (not cached) |
| `GET sumdb/<name>/lookup/…`, `…/tile/…` | the checksum database's answer | immutable |
| `GET sumdb/<name>/latest` | the database's signed tree head | metadata |
| `PUT <module>/@v/<version>.zip` / `.mod` / `.info` | publish (hosted only) | |
| `DELETE <module>/@v/<version>` | remove a version (hosted only) | |

Module paths and versions travel **case-encoded**, exactly as the go command sends them: every
upper-case letter `X` is written `!x` (`github.com/BurntSushi/toml` is requested as
`github.com/!burnt!sushi/toml`, `v1.0.0-RC1` as `v1.0.0-!r!c1`). Granary decodes them, checks them
with the go command's own rules (`module.CheckPath`, canonical semantic versions, major-version
suffixes such as `/v2`, `gopkg.in/x.v1`), stores them encoded and passes them upstream unchanged.
A malformed path, a raw upper-case letter, a bad `!` escape or `..` is answered with `400`, never
looked up. Unknown modules and versions are a plain `404`, which is what makes the go command move
on to the next path prefix or to the next entry of `GOPROXY`.

Every file response carries `Content-Type`, `Content-Length`, `ETag` and `Last-Modified`, and
answers `HEAD` and `If-None-Match` (`304`). Version lists and `@latest` are rendered per request
with an `ETag` over their content and `Cache-Control: no-cache`.

## Standard repositories

`POST /api/v1/repositories/standard` creates:

* `go-proxy`: proxy of `https://proxy.golang.org/`, with the `sum.golang.org` checksum database;
* `go-hosted`: your own modules;
* `go-group`: `go-hosted` + `go-proxy`, the single `GOPROXY` URL for public and private modules.

## Hosted

A version is published by uploading its module zip; the `.mod` is taken from the zip and the
`.info` is generated with the upload time.

```bash
# The zip holds every file under <module>@<version>/, with go.mod at that root.
mkdir -p example.com/acme/hello@v0.1.0
cp go.mod *.go example.com/acme/hello@v0.1.0/
zip -r hello-v0.1.0.zip example.com/acme/hello@v0.1.0
curl -u admin:gry_… -T hello-v0.1.0.zip \
     http://granary.example.com:8081/repository/go-hosted/example.com/acme/hello/@v/v0.1.0.zip
```

The answer is `201` with `{"module": …, "version": …, "files": [ … ]}`. The upload is checked the
way the go command checks a download (`golang.org/x/mod/zip`), and refused with
`400 repository.go.invalidZip` and the reason when:

* an entry is not under `<module>@<version>/`, its path is not clean (`..`, `.`, empty elements,
  a leading `/`), it uses characters the go command does not allow in file names, or two entries
  differ only in case;
* there is a `go.mod` anywhere but in the module root, or spelled in another case;
* a file belongs to a vendored package (`vendor/<pkg>/…`; `vendor/modules.txt` is fine);
* the zip is larger than 500 MiB, uncompresses to more than 500 MiB, has a `go.mod` or `LICENSE`
  over 16 MiB, or an entry's real size differs from the size its header declares;
* the zip has no `go.mod` (only `+incompatible` versions may lack one; they get a synthetic
  `module <path>` go.mod, as on proxy.golang.org), or its `module` directive names another module
  (`400 repository.go.badRequest`).

The version in the URL must be canonical (`v1.2.3`, `v1.2.3-rc.1`, a pseudo-version, or
`v2.0.0+incompatible`; not `v1.2` or `1.2.3`) and agree with the path's major-version suffix: `v2+`
needs a `/v2` path unless it is `+incompatible` (`400 repository.go.invalidVersion`).

Directory entries, which `zip -r` adds and the go command never writes, are dropped before the zip
is stored. The go command hashes every entry of a downloaded zip but only the files of the
extracted module, so a zip with directory entries would make `go mod verify` report the module as
modified.

* Publishing a version that exists answers `409 repository.go.versionExists` unless *allow
  redeploy* is set on the repository. Writes to one version are serialised: of two uploads of the
  same new version that arrive at once, one gets `201` and the other `409`.
* `.info` and `.mod` can only be uploaded for a version whose zip is already published
  (`409 repository.go.versionMissing` otherwise), so there is never a half-published version.
* `PUT <module>/@v/<version>.info` replaces the generated `.info`, for example with the real tag
  time (the `Version` must match the URL and `Time` must be RFC 3339). An `.info` uploaded this way
  is kept from then on.
* `PUT <module>/@v/<version>.mod` is accepted when it is exactly the `go.mod` already served (`200`,
  nothing written). Consumers record the hash of the `go.mod` in their `go.sum` files next to the
  hash of the zip, so a different `go.mod` would break every build that already downloaded the
  version: it is refused with `409 repository.go.versionExists`.
* With *allow redeploy* set, a different `.info` or `.mod` replaces the stored one, and a
  redeployed zip keeps the `.info` and `.mod` a client uploaded.
* The files the go command keeps in its module cache can be published as they are:
  `go mod download -json example.com/acme/hello@v0.1.0` prints the paths of
  `$GOMODCACHE/cache/download/example.com/acme/hello/@v/v0.1.0.{zip,mod,info}`, and
  `curl -u admin:gry_… -T "{v0.1.0.zip,v0.1.0.mod,v0.1.0.info}" …/example.com/acme/hello/@v/`
  uploads all three (the zip first: curl sends them in the order given).
* `DELETE <module>/@v/<version>` removes the version's three files and its component.
* `@v/list` names tagged versions in semantic-version order (`golang.org/x/mod/semver` precedence:
  numeric pre-release identifiers compare numerically, build metadata is ignored). Pseudo-versions
  are left out, as on proxy.golang.org, but stay downloadable. `@latest` is the highest release,
  else the highest pre-release, else the newest pseudo-version.
* Components are `(<module path without its last element>, <last element>, <version>)`, e.g.
  `(example.com/acme, hello, v0.1.0)`, so the UI's browser and search show every version.

## Proxy

* `remoteUrl` is any GOPROXY, by default `https://proxy.golang.org/`.
* `@v/list` and `@latest` are mutable: refreshed after *metadata max age* (conditionally, with the
  upstream `ETag`), served stale while upstream is unreachable or the repository is offline. The
  list is served byte for byte as upstream sent it. `.info`, `.mod` and `.zip` of semantic versions
  are immutable and fetched once.
* The cached `.info`, `.mod` and `.zip` of a version are linked to the component a publish of
  that version records (`(<module path without its last element>, <last element>, <version>)`,
  with the module path decoded: `github.com/!burnt!sushi/toml` becomes
  `(github.com/BurntSushi, toml, …)`), so the modules builds downloaded show up in the
  component browser and in search. `@v/list`, `@latest` and branch or commit queries are not
  components. Downloads through a group are recorded in the proxy member that cached them.
* Deleting a cached component removes its files; the next download fetches them again, or gets
  `503` while the proxy is offline. Files cached by an earlier Granary version, which recorded no
  components, are linked the first time they are served, even offline.
* Upstream `404` and `410 Gone` (what proxy.golang.org answers for excluded or invalid versions)
  are both remembered for *negative cache* minutes and answered with `404`.
* An uncached file while upstream is down or the repository (or the whole server) is offline is a
  `503` (`upstream.unavailable` / `upstream.offline`), which the go command reports instead of
  silently trying the next path.
* Proxies are read-only (`405 repository.readOnly`).

### Checksum database

With the default `GOSUMDB=sum.golang.org`, the go command first asks the proxy for
`sumdb/sum.golang.org/supported`. A proxy (or a group with a proxy member) answers `200`, and from
then on the go command fetches every checksum-database request through Granary
(`sumdb/sum.golang.org/lookup/<module>@<version>`, `…/tile/…`, `…/latest`). The answers are
signed by the database, so the go command still verifies them end to end; Granary only caches them:
lookups and tiles forever, the tree head as metadata. Machines that cannot reach sum.golang.org
keep verifying, and so does an offline Granary for everything that was looked up before.

* *Checksum database name* (`go.sumdbName`, default `sum.golang.org`) is the one database a proxy
  passes through. Requests for any other name answer `404`, which makes the go command contact that
  database directly; a proxy is never a way to fetch arbitrary hosts.
* *Checksum database URL* (`go.sumdbUrl`, default `https://<checksum database name>`) is where
  those requests go, for example the mirror `https://sum.golang.google.cn`.
* The proxy's upstream credentials (remote token, user name and password of a private GOPROXY) are
  sent to the checksum database only when it is on the same origin (scheme, host and port) as the
  remote URL or its host is listed in [`upstream.credentialHosts`](../configuration.md#upstream-credentials); sum.golang.org or a
  mirror on another host never sees them.
* A hosted repository answers `404` for `sumdb/…/supported`, so the go command talks to
  sum.golang.org itself.

## Group

* `@v/list` is the union of all members' versions in semantic-version order.
* `@latest` is the highest release across all members (else the highest pre-release, else the
  newest pseudo-version), described by the `.info` of the member that has that version; a module
  that no member lists is asked of the proxy members' `@latest` (untagged modules).
* `.info`, `.mod` and `.zip` come from the first member that has them, hosted members first in the
  standard group, so a hosted version shadows an upstream one with the same number.
* `sumdb/…` is passed through the first proxy member that serves the requested database.
* Groups are read-only; the error names the hosted member to publish to.
* A group asks every member, so the proxy member forwards `@v/list` and `@latest` requests for
  private module paths to its upstream (proxy.golang.org answers `404`/`410`, which is remembered
  in the negative cache). `GONOSUMDB`/`GOPRIVATE` keep private paths away from the checksum
  database but not from the proxy's upstream. If the module paths themselves are confidential,
  resolve them through a repository without a proxy member of a public GOPROXY.

## Client configuration

```bash
# Everything through Granary. ",direct" lets the go command fetch a module from its version
# control system itself when Granary answers 404/410; leave it out to make Granary the only source.
go env -w GOPROXY=http://granary.example.com:8081/repository/go-group,direct
# The checksum database is consulted through Granary (sumdb/sum.golang.org/…): keep the default.
go env -w GOSUMDB=sum.golang.org

# Private modules published to go-hosted are unknown to sum.golang.org: exclude their prefix from
# checksum-database lookups (go.sum still pins them after the first download).
go env -w GONOSUMDB=example.com/acme
# GOPRIVATE sets GONOSUMDB and GONOPROXY at once; GONOPROXY would bypass Granary, so reset it:
go env -w GOPRIVATE=example.com/acme GONOPROXY=none
# Let builds add new requirements to go.mod/go.sum on their own:
go env -w GOFLAGS=-mod=mod
```

```dockerfile
FROM golang:1.24 AS build
ENV GOPROXY=http://granary.example.com:8081/repository/go-group,direct
ENV GOSUMDB=sum.golang.org
# ENV GONOSUMDB=example.com/acme
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /out/app .
```

### Credentials

Downloads are anonymous unless *anonymous read* is off. The go command authenticates with HTTP
Basic from `~/.netrc` (or the file named by `NETRC`; `GOAUTH=netrc` is the default) or from
credentials in the `GOPROXY` URL, with an API token as the password:

```
# ~/.netrc (chmod 600). The go command matches the machine against host:port, curl against the
# host name alone, so a server on a non-default port needs both lines.
machine granary.example.com:8443 login admin password gry_…
machine granary.example.com login admin password gry_…
```

```bash
go env -w GOPROXY=https://admin:gry_…@granary.example.com:8443/repository/go-group,direct
```

The go command sends credentials **only over HTTPS**: it ignores `.netrc` for `http://` URLs and
refuses (`refusing to pass credentials to insecure URL`) a plain-HTTP `GOPROXY` with credentials in
it. With anonymous reads off, put Granary behind TLS (any reverse proxy; Granary writes no absolute
URLs into Go responses, so no base-URL setting is needed). Publishing with curl works over either.

## Settings

| Key | Applies to | Default | Meaning |
|---|---|---|---|
| `go.sumdbName` | proxy | `sum.golang.org` | the checksum database (the name in `GOSUMDB`) passed through `sumdb/<name>/…` |
| `go.sumdbUrl` | proxy | `https://<go.sumdbName>` | where checksum-database requests are forwarded, e.g. a mirror |

## Offline behaviour

* Cached `.info`, `.mod` and `.zip` files of semantic versions are served without contacting the
  upstream. `@v/list` and `@latest` are re-validated after *metadata max age* and served stale
  while the upstream is unreachable or the repository (or the whole server) is offline.
* Checksum-database lookups and tiles that were asked before are cached forever, so `GOSUMDB`
  verification keeps working offline for every module version fetched before.
* An uncached file is a `503` (`upstream.offline` / `upstream.unavailable`), which the go command
  reports instead of silently trying the next path prefix. Remembered upstream `404`/`410`
  answers stay `404`.
* A group keeps serving hosted modules and everything its proxy members have cached.

## Verification

`granary-format-go/scripts/verify-go.sh <base-url>` drives a running Granary with the real go
command: public modules through `go-proxy` with checksums verified against sum.golang.org through
the passthrough (direct HTTPS is pointed at a dead proxy, so verification can only succeed through
Granary), a case-encoded module path, publishing `example.com/acme/hello` with `curl -T`, a refused
duplicate and anonymous upload, `go get`/`go build`/`go mod verify` of a consumer through
`go-group`, `.netrc` and URL credentials over a throw-away TLS front with anonymous reads off, and
the whole download again in offline mode with empty module caches. With
`GO_DOCKER_IMAGE=golang:1.25-alpine` (any image with the go command) the consumer is built once
more, online and offline, by that go version in a container on the host network.

## Limitations

* There is no `go publish`; versions are published with an HTTP `PUT` (curl, CI scripts).
* Granary does not run a checksum database of its own, so hosted modules must be excluded from
  `GOSUMDB` with `GONOSUMDB`/`GOPRIVATE`; their integrity rests on `go.sum` after the first
  download and on hosted versions being immutable.
* Proxies only talk to an upstream GOPROXY; Granary never fetches modules from version control,
  so private modules on a VCS host must be published to a hosted repository (or fetched with
  `,direct` by the go command itself).
* The `.info` `Time` of a hosted version is the upload time unless a `.info` is uploaded with it;
  `Origin` information is not generated.
* A proxy passes one checksum database through; others are left to the go command (`404` on
  `supported`).
* Private module paths requested through a group reach the proxy member's upstream (see *Group*).
* Writes to one version are serialised inside one Granary process; several Granary nodes sharing a
  database do not coordinate uploads of the same version.
* The go command cannot authenticate over plain HTTP (see *Credentials*).
* Error bodies are JSON like everywhere in Granary; the go command shows only `text/plain` bodies,
  so it reports a failed download with its status alone (for example `503` for a module that is
  not cached while offline). curl and CI logs show the message.
