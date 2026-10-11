# Docker / OCI repositories

Format id: `docker`. Registry URL: `http://host:8081/` (the root) or
`http://host:8081/repository/<name>/` (curl, scripts, tests).

Granary implements the OCI Distribution Spec v1.1 (the Docker Registry HTTP API V2), so
docker, podman, nerdctl and containerd, buildah and skopeo, oras, crane, helm (`oci://`) and
kubelet all talk to it with their normal registry support. Manifests, image indexes, OCI
artifacts (charts, SBOMs, signatures) and referrers are stored and served as the client sent
them.

## Endpoints

The OCI Distribution API, at `/v2/` of the server (see [Root routing](#root-routing)) and under
`/repository/<name>/v2/`. `<image>` may contain slashes (`library/nginx`, `acme/team/app`).

| Method | Path | Purpose |
| --- | --- | --- |
| `GET`, `HEAD` | `v2/` | Ping; answers `401` with a Basic challenge when `docker.challengeAnonymous` is on |
| `GET` | `v2/_catalog?n=…&last=…` | Image names |
| `GET` | `v2/<image>/tags/list?n=…&last=…` | Tags of an image |
| `GET`, `HEAD`, `PUT`, `DELETE` | `v2/<image>/manifests/<tag or digest>` | Manifests and indexes |
| `GET`, `HEAD`, `DELETE` | `v2/<image>/blobs/<digest>` | Layers and configs (with `Range`) |
| `POST` | `v2/<image>/blobs/uploads/` | Start an upload; `?digest=…` uploads in one request, `?mount=…&from=…` mounts a blob |
| `GET`, `PATCH`, `PUT`, `DELETE` | `v2/<image>/blobs/uploads/<uuid>` | Upload status, chunk, completion, cancellation |
| `GET` | `v2/<image>/referrers/<digest>?artifactType=…` | Referrers index |

Writes and deletes are for hosted repositories; errors use the OCI error document
(`{"errors":[{"code":"MANIFEST_UNKNOWN",…}]}`).

## Root routing

Container clients cannot be pointed at a path: they always call `http(s)://host:port/v2/...`.
Granary therefore answers the registry API at the root of the server as well:

* `host:8081/<repository>/<image>:<tag>` reaches that docker repository, e.g.
  `docker pull host:8081/docker-group/library/nginx:1` or
  `docker push host:8081/docker-hosted/acme/app:1.0`.
* `host:8081/<image>:<tag>` (no repository prefix) reaches the *root repository*: the docker
  repository with `docker.rootRepository` enabled, else the first docker group, else the first
  docker repository by name. The standard set flags `docker-group`, so `docker pull
  host:8081/alpine:3.20` works out of the box.
* `/v2/_catalog` at the root lists the root repository.
* `host:8081/repository/<name>/v2/...` is the same API for tools that take a URL (curl, the
  tests).

Image names are stored without the repository prefix, so an image pushed as
`host:8081/docker-hosted/acme/app:1.0` is also `host:8081/acme/app:1.0` when `docker-hosted`
is a member of the root group.

## Standard repositories

| Name | Type | Upstream / members | Settings |
| --- | --- | --- | --- |
| `docker-hub` | proxy | `https://registry-1.docker.io` | `docker.libraryPrefix=true` |
| `ghcr` | proxy | `https://ghcr.io` | |
| `quay` | proxy | `https://quay.io` | |
| `docker-hosted` | hosted | | |
| `docker-group` | group | `docker-hosted`, `docker-hub`, `ghcr`, `quay` | `docker.rootRepository=true` |

## Hosted

* `docker push` and `pull`, chunked, monolithic and `POST`-with-digest uploads, cross-repository
  blob mounts (`mount=…&from=…`; the blob is shared inside the repository, so a mount succeeds
  whenever the blob is already there), `HEAD` and `Range` requests for blobs.
* Docker schema 2 manifests and manifest lists, OCI manifests and indexes, OCI artifacts with
  `artifactType` and `subject`; the referrers API (`/v2/<name>/referrers/<digest>`, with the
  `artifactType` filter and `OCI-Filters-Applied`) is served from the stored manifests, and a
  push with `subject` answers `OCI-Subject` so clients do not fall back to tag schemas.
* A manifest is refused with `404 MANIFEST_BLOB_UNKNOWN` when a layer, config or child
  manifest it references has not been uploaded, so a repository never holds a dangling image.
* Moving an existing tag to a different digest is refused with `409 DENIED` ("tag 1.0 of
  acme/app already points at sha256:…; enable redeploys on repository docker-hosted to move
  it") unless *allow redeploy* is set on the repository. Pushing the same bytes again is always
  fine (every client does it to re-tag), and new tags are never affected.
* `DELETE` of a manifest by digest removes it and every tag pointing at it; `DELETE` of a tag
  removes only the tag. `DELETE` of a blob removes the blob.
* Tag listing (`/v2/<name>/tags/list`) and the catalog support `n` and `last` with an absolute
  `Link: <…>; rel="next"` header.

## Proxy

* `remoteUrl` is the registry root, e.g. `https://registry-1.docker.io`, `https://ghcr.io`,
  `https://quay.io` or any other registry. The upstream's auth scheme is discovered from its
  `/v2/` ping: anonymous, Basic, or Bearer token exchange (the `realm`/`service` challenge of
  Docker Hub, ghcr.io, quay.io, Harbor, Artifactory and friends). Tokens are requested with
  `scope=repository:<name>:pull` and cached until shortly before they expire. Repository
  credentials, when configured, are sent to the token endpoint, so private upstream images
  work with a Docker Hub account or a GitHub token.
* `docker.libraryPrefix` (on by default for Docker Hub hosts) requests single-segment names
  upstream as `library/<name>`: `host:8081/alpine:3.20` and `host:8081/library/alpine:3.20`
  are the same image and share one cache entry.
* Blobs and manifests fetched by digest are immutable and cached forever; the upstream's
  digest is verified against the bytes before anything is stored. Tags are mutable: they are
  refreshed after the repository's *metadata max age*, served stale while the upstream is
  unreachable, and a tag resolved for a client that does not accept multi-platform indexes is
  cached separately from the index. docker and containerd send `Accept` as one list, podman,
  skopeo and buildah as one header line per type; both forms select the same cached index.
* Blob redirects (Docker Hub's and ghcr.io's CDNs answer `307`) are followed; the registry
  token is not replayed to the CDN host.
* Docker Hub answers `401` instead of `404` for images that do not exist (or are private).
  A `401`/`403` for a manifest or blob of an image the proxy could ping is treated as a miss
  for that image, not as an outage, so a group keeps falling through to the next member and
  the circuit breaker is not tripped. When the upstream had issued a token for the image the
  miss is negative-cached for the repository's negative TTL.
* Cached blobs and manifests, tags younger than the metadata max age and remembered misses are
  answered from the cache without probing the upstream or asking its token service, so a pull of
  cached images never waits for a registry that went away after Granary started.
* Two clients pulling the same uncached layer at the same moment both get it; the second one
  reads the copy the first one cached.
* The referrers API of a proxy answers from the manifests it has cached; it does not query the
  upstream's referrers endpoint.
* Offline use is described under [Offline behaviour](#offline-behaviour).

## Group

* A manifest or blob is served from the first member that has it: hosted members first (they
  are the only ones that can be written), then the proxies in order. Docker Hub's `401` for an
  unknown image is a miss, so `host:8081/oras-project/oras:v1.2.0` falls through to `ghcr`.
* Tag lists and the referrers index are merged across members; the catalog is the union of the
  members' images.
* Groups are read-only. A push answers `405 UNSUPPORTED` naming the hosted member to push to
  instead (`docker push host:8081/docker-hosted/acme/app:1.0`).

## Settings

| key | type | default | meaning |
| --- | --- | --- | --- |
| `docker.rootRepository` | boolean | `false` | Serve this repository at `/v2/` of the server so images need no repository prefix. |
| `docker.libraryPrefix` | boolean | `true` for Docker Hub hosts, `false` elsewhere | Proxy only. Request `alpine` upstream as `library/alpine`. |
| `docker.challengeAnonymous` | boolean | `true` | Answer an anonymous `GET /v2/` with `401` and a Basic challenge (see below). |

## Authentication

Granary uses Basic authentication with a user name and a Granary API token (or password) and
does not run a token service of its own. `docker login host:8081 -u <user>` with the token as
password is all a client needs; the registry answers `WWW-Authenticate: Basic realm="Granary"`
and every client retries with the stored credentials.

The ping (`GET /v2/`) decides whether a client sends credentials at all, and the clients
disagree about it:

* docker (both the containerd image store and the classic graph driver) sends the credentials
  of `docker login` only after the ping answered `401`. With `docker.challengeAnonymous` on
  (the default) every logged-in pull and push carries credentials, and an anonymous docker
  with the containerd image store still pulls public images when *anonymous read* is enabled.
  Older docker daemons with the classic graph driver abort an anonymous pull with
  `no basic auth credentials` when the ping is challenged.
* podman, buildah, skopeo and other containers/image tools send credentials whenever they
  have some, but an anonymous one answers a challenged ping with empty credentials and gives
  up ("authentication required").
* containerd (ctr, nerdctl, kubelet), oras, crane and helm try without credentials first and
  retry with them after any `401`, so both settings work for them.

So: keep the default when every client logs in (recommended) or when docker users must be
able to push; switch `docker.challengeAnonymous` off when anonymous read is enabled and
anonymous podman or skopeo pulls matter more than anonymous pulls from old docker daemons.
Logged-in docker clients push with either setting because a `401` on the upload itself
triggers the same retry.

## Client configuration

The *connect* panel of every docker repository generates these for the repository's URL,
user and token; all examples use the standard `docker-hosted`/`docker-group` set on a plain
`http` server. With TLS, drop the insecure flags.

```bash
# docker
echo 'gry_…' | docker login granary.example.com:8081 -u alice --password-stdin
docker pull granary.example.com:8081/library/alpine:3.20          # root -> docker-group
docker tag acme/app:1.0 granary.example.com:8081/docker-hosted/acme/app:1.0
docker push granary.example.com:8081/docker-hosted/acme/app:1.0
```

```json
// /etc/docker/daemon.json, only for a plain-http server
{ "insecure-registries": ["granary.example.com:8081"] }
```

```toml
# /etc/containers/registries.conf.d/granary.conf (podman, buildah, skopeo)
[[registry]]
location = "granary.example.com:8081"
insecure = true
```

```bash
# podman / buildah / skopeo
echo 'gry_…' | podman login granary.example.com:8081 -u alice --password-stdin --tls-verify=false
podman pull --tls-verify=false granary.example.com:8081/library/alpine:3.20
buildah bud -t granary.example.com:8081/docker-hosted/acme/app:1.0 .
buildah push --tls-verify=false granary.example.com:8081/docker-hosted/acme/app:1.0
skopeo copy --dest-tls-verify=false docker://docker.io/library/alpine:3.20 \
  docker://granary.example.com:8081/docker-hosted/mirror/alpine:3.20
```

```toml
# /etc/containerd/certs.d/granary.example.com:8081/hosts.toml (containerd, nerdctl, kubelet)
server = "http://granary.example.com:8081"

[host."http://granary.example.com:8081"]
  capabilities = ["pull", "resolve", "push"]
  skip_verify = true
```

```bash
# nerdctl / ctr
echo 'gry_…' | nerdctl login granary.example.com:8081 -u alice --password-stdin --insecure-registry
nerdctl pull --insecure-registry granary.example.com:8081/library/alpine:3.20
ctr images pull --plain-http --user alice:gry_… granary.example.com:8081/library/alpine:3.20

# oras (any OCI artifact, with referrers)
echo 'gry_…' | oras login granary.example.com:8081 -u alice --password-stdin --plain-http
oras push --plain-http granary.example.com:8081/docker-hosted/acme/artifacts:1.0 ./artifact.txt
oras attach --plain-http --artifact-type application/vnd.example.sbom \
  granary.example.com:8081/docker-hosted/acme/artifacts:1.0 ./sbom.json
oras discover --plain-http granary.example.com:8081/docker-hosted/acme/artifacts:1.0

# helm
echo 'gry_…' | helm registry login granary.example.com:8081 -u alice --password-stdin --insecure
helm push mychart-0.1.0.tgz oci://granary.example.com:8081/docker-hosted/charts --plain-http
helm pull oci://granary.example.com:8081/charts/mychart --version 0.1.0 --plain-http

# Kubernetes
kubectl create secret docker-registry granary \
  --docker-server=granary.example.com:8081 --docker-username=alice --docker-password='gry_…'
```

```bash
# curl against a repository directly
curl -u alice:gry_… http://granary.example.com:8081/repository/docker-hosted/v2/_catalog
curl -u alice:gry_… http://granary.example.com:8081/repository/docker-hosted/v2/acme/app/tags/list
```

`granary-format-docker/scripts/verify-docker.sh http://localhost:8081` runs all of the above
against a live server (docker, podman, skopeo, buildah, oras, crane, ctr with and without the
connect page's `hosts.toml`, nerdctl, helm; oras, crane and nerdctl are fetched from their GitHub
releases and helm runs from the `alpine/helm` image when they are not installed; the ghcr.io and
quay.io pulls are reported as skipped when those hosts are unreachable) including the offline,
multi-arch, redeploy and anonymous cases. With `VERIFY_KUBELET=1` it also starts a kind node from
an image pulled through Granary and pulls through that node's containerd CRI image service with
`crictl`, configured by the connect page's `hosts.toml`, which is the path the kubelet takes.
`ALPINE_TAG` and `BUSYBOX_TAG` choose the Docker Hub images it pulls; pick tags the local docker
image store does not already hold, so the layers really travel through Granary.

## Offline behaviour

* In offline mode (the repository's or the whole server's) everything in the cache is served,
  including tag lookups; an image that was never pulled answers `503 upstream.offline` and
  docker prints `failed to resolve reference … 503` instead of hanging.
* While an upstream is unreachable, cached blobs, manifests and remembered misses are answered
  without probing it, cached tags are served stale, and anything uncached is a
  `503 upstream.unavailable`.
* A group keeps serving hosted images and everything its proxies have cached.

## Storage layout

```
blobs/sha256/<hex>              layers and configs, shared by every image of the repository
manifests/<name>/sha256/<hex>   manifests and indexes, immutable, verbatim bytes
tags/<name>/<tag>               hosted: the digest the tag points at; proxy: the upstream's manifest (metadata)
tags/<name>/<tag>@<variant>     a proxy's copy of a tag for clients without index support
tags/<name>/.list               a proxy's cached upstream tag listing
```

Upload sessions live under `<data-dir>/tmp/docker/` and are swept after 24 hours.

## Limitations

* The root catalog lists the root repository's images without a prefix. Clients that filter the
  catalog by their own name (`oras repo ls host:8081/docker-hosted`) see nothing there; use the
  root (`oras repo ls host:8081`) or the repository URL (`…/repository/docker-hosted/v2/_catalog`).
* Granary does not issue Bearer tokens; clients that insist on a token service (none of the
  ones above do) cannot log in. Upstream Bearer registries are fully supported.
* A proxy's tag list is one upstream page of up to 1000 tags, merged with what was already
  pulled; longer upstream tag lists are truncated in `tags/list`, pulls are unaffected.
* Deleting a blob is immediate and not reference counted: a manifest that still references it
  is then broken until the blob is pushed again.
* A `401`/`403` from a token-issuing upstream for an image is remembered as a miss for the
  negative TTL; making an upstream image public shows up after that TTL (or after the proxy's
  negative cache is cleared).
* Anonymous pulls: see *Authentication*; no single ping policy satisfies both old docker
  daemons and anonymous containers/image tools.
