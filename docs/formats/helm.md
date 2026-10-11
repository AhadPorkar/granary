# Helm chart repositories

Format id: `helm`. Repository URL: `http://host:8081/repository/<name>` (what `helm repo add` takes).

Works with the helm CLI (3.x and 4.x: `helm repo add`, `repo update`, `search repo`, `pull`, `install`,
`upgrade`, `template`, `show`, `dependency update`, `pull --verify`), the ChartMuseum API that
`helm cm-push`, curl and CI scripts upload through, helmfile, Flux (`HelmRepository`), Argo CD and
Artifact Hub, because Granary serves a classic HTTP chart repository (`index.yaml` plus chart
archives) and the parts of the ChartMuseum API those tools use.

OCI-based charts (`oci://…`, `helm push`, `helm pull oci://…`) are not part of this format: they
are OCI artifacts and are stored in, and proxied by, Docker repositories.

## Endpoints

Every path is relative to the repository URL.

| Request | Answer | Cached by proxies as |
|---|---|---|
| `GET index.yaml` | the repository index, generated (hosted) or rewritten (proxy, group) | metadata |
| `GET charts/<name>-<version>.tgz` | a chart archive | immutable |
| `GET charts/<name>-<version>.tgz.prov` | its provenance file (`helm package --sign`) | immutable |
| `GET charts/<name>/<version>/<file>` | the same files, addressed by chart and version (see *Group*) | immutable |
| `GET <file>.tgz`, `<file>.tgz.prov` | the same as `charts/<file>…` (the layout some other repository managers use) | |
| `GET artifacthub-repo.yml` | Artifact Hub repository metadata, if uploaded | metadata |
| `GET api/charts` | ChartMuseum: every chart, `{"<name>":[<entry>, …]}` | |
| `GET api/charts/<name>` | ChartMuseum: the chart's versions, newest first | |
| `GET api/charts/<name>/<version>`, `…/latest` | ChartMuseum: one entry | |
| `POST api/charts` | upload a chart (and its provenance file); hosted only | |
| `POST api/prov` | upload a provenance file on its own; hosted only | |
| `DELETE api/charts/<name>/<version>` | delete a version; hosted only | |
| `PUT charts/<file>.tgz`, `PUT <file>.tgz` (and `.prov`) | upload with PUT; hosted only | |
| `DELETE charts/<file>.tgz` | delete the version the archive belongs to (`.prov`: only the provenance file) | |
| `PUT` / `DELETE artifacthub-repo.yml` | upload or remove the Artifact Hub metadata; hosted only | |
| `GET health` | `{"healthy":true}`, without authentication | |
| `GET` (repository root) | an HTML page with the `helm repo add` line | |

`index.yaml` is what `helm repo index` writes: `apiVersion: v1`, `entries` with every chart's
versions newest first (semantic-version order, pre-releases below their release), `generated`,
and `serverInfo.contextPath`, the path of the repository URL (`/repository/helm-hosted`), which
`helm cm-push` reads to find `api/charts`. Every entry's `urls` is an absolute URL on Granary
built from the URL the client used (or the configured base URL), so downloads never bypass
Granary; with `helm.relativeUrls` the URLs are relative to the index instead
(`charts/demo-1.0.0.tgz`), for repositories reached under several host names. The index carries an
`ETag` (SHA-256 of the rendered bytes) and `Cache-Control: no-cache`; `If-None-Match` gets a `304`.
Chart archives carry `Content-Length`, `ETag`, `Last-Modified` and answer `HEAD`.

Errors are ChartMuseum's error document, which `helm cm-push` prints as it is:
`{"error":"chart acme-web 1.0.0 already exists in 'helm-hosted' and redeployment is disabled"}`.
Paths with characters no chart file name contains are a `400`; unknown paths and missing charts a
`404`; a method the path does not support a `405` with an `Allow` header.

## Standard repositories

`POST /api/v1/repositories/standard` creates:

* `helm-hosted`: your own charts;
* `helm-group`: `helm-hosted`, the one chart repository URL to add everywhere.

There is no standard proxy: public charts are spread over many repositories (one per project), so
create a proxy per upstream you need and add it to `helm-group`.

## Hosted

Charts are uploaded through the ChartMuseum API, the way `helm cm-push` and curl send them:

* `POST api/charts` with the archive as the raw body (`curl --data-binary @demo-1.0.0.tgz`), or as
  a multipart form with the fields `chart` and, optionally, `prov` (`curl -F chart=@… -F prov=@…`,
  `helm cm-push`). A form with a single file under another field name is taken as the chart, or as
  the provenance file when its file name ends in `.prov`.
* `POST api/prov` with a provenance file alone, before or after its chart.
* `PUT charts/<name>-<version>.tgz` (or `PUT <name>-<version>.tgz`, which is what
  `curl --upload-file x.tgz <repo>/` sends) with the archive, and `PUT ….tgz.prov` with its
  provenance file.

The answer is `201` with `{"saved":true}`, and the version is in `index.yaml` at once. Granary reads
the chart's `Chart.yaml` (and `requirements.yaml` of `apiVersion: v1` charts) from the archive and
writes the index entry the way `helm repo index` does: the fields Helm knows (`apiVersion`, `name`,
`version`, `appVersion`, `kubeVersion`, `description`, `type`, `home`, `icon`, `sources`,
`keywords`, `maintainers`, `condition`, `tags`, `deprecated`, `annotations`, `dependencies`), plus
`created` (the upload time), `digest` (the SHA-256 of the stored archive, computed by Granary) and
`urls`. The archive is stored as `charts/<name>-<version>.tgz` whatever its upload file name was.

An upload is refused with `400` and the reason when:

* the body is not a gzip-compressed tar archive, is truncated or empty, unpacks to more than
  100 MiB, or has no `<chart>/Chart.yaml` at its top level (a tar entry with `..` or an absolute
  path is refused as well);
* `Chart.yaml` is not valid YAML, `name` is missing or contains anything but letters, digits,
  `.`, `_` and `-`, `version` is missing or not a semantic version (Helm's loose rules: `v1.2`
  is fine, `latest` is not), `type` is neither `application` nor `library`, or a field has the
  wrong type (a `description` that is a map, `keywords` that are not strings, a dependency alias
  with spaces, …);
* the provenance file is not a clear-signed provenance document, names another chart or version,
  or states a SHA-256 for the archive that differs from the archive's.

Uploads larger than `helm.maxChartSizeMb` (20 MiB by default) are a `413`.

An existing version is a `409` unless *allow redeploy* is set on the repository, in which case the
archive and its index entry are replaced (and a provenance file that does not match the new
archive is removed). ChartMuseum's `?force` parameter does not override the repository setting.

A chart version whose archive file name already belongs to another chart version is a `409` as
well, whatever the redeploy setting: `acme-1` 2.0.0 and `acme` 1-2.0.0 (a valid version by Helm's
rules) are both packaged as `acme-1-2.0.0.tgz`, and the second upload must not replace the first
chart's archive. Deleting a version never removes files that belong to another version.

* `DELETE api/charts/<name>/<version>` (or `DELETE charts/<name>-<version>.tgz`) removes the
  archive, its provenance file and its index entry; the answer is `{"deleted":true}`.
* Components are `("", <name>, <version>)`, so the UI's browser and search show every version, and
  downloads are counted per archive. Deleting a component (UI or REST API) takes the version out of
  the index.
* `artifacthub-repo.yml` can be uploaded with `PUT` for Artifact Hub's ownership claims and
  verified-publisher checks; it must be valid YAML of at most 1 MiB.

## Proxy

* `remoteUrl` is the URL you would give `helm repo add` for the upstream repository (with or
  without a trailing slash or `/index.yaml`). A query string, such as the signature of a storage
  URL, is kept the way helm keeps it: `https://host/charts?sig=…` fetches
  `https://host/charts/index.yaml?sig=…`, and relative chart URLs get the same query. Its
  `index.yaml` is fetched with `Accept: application/x-yaml`, cached as metadata, refreshed after
  *metadata max age* (conditionally, with the upstream `ETag` or `Last-Modified`) and served stale
  while the upstream is unreachable or the repository is offline. The rewritten index is computed once per upstream
  document, not once per request.
* Every entry's first URL is resolved the way helm resolves it (relative URLs against the repository
  URL; absolute URLs, such as GitHub release downloads, as they are) and replaced by
  `charts/<file name>` on Granary; when entries with different upstream URLs share a file name, or
  a file name is not usable, each of them gets `charts/<name>/<version>/<file>` instead. The upstream document is cached as it
  came, so the mapping can change with the upstream.
* Archives and provenance files are fetched from that upstream location the first time they are
  requested and cached forever. Only files the index lists are fetched; anything else is a `404`
  without an upstream request. A response that is not a gzip stream (an error or login page served
  with status `200`) is discarded and answered with `502` instead of being cached. A digest that
  differs from the index is only logged, because helm does not check it either and some public
  repositories (helm/examples among them) list stale digests.
* An upstream `404` for `index.yaml` is remembered for *negative cache* minutes. An upstream that
  answers with something that is not a chart repository index is a `502`.
* An uncached index or chart while the upstream is down, or while the repository (or the whole
  server) is offline, is a `503` (`upstream.unavailable` / `upstream.offline`), which helm reports
  as a failed download rather than as a missing chart.
* Upstream credentials (*remote username/password* or *remote token*) are sent with every upstream
  request of the repository.
* Proxies are read-only: uploads and deletes answer `405`.

## Group

* `index.yaml` is the union of the members' indexes. Members are asked in their configured order
  and the first member that lists a chart version owns it: its entry (and digest) is served and its
  archive downloaded, so a hosted member listed first can never be shadowed by a public chart with
  the same name and version.
* Download URLs point at the group. When two members use the same file name for different chart
  versions, the later one is published under `charts/<name>/<version>/<file>`, so every version has
  its own URL.
* A member whose index is unavailable (an unreachable upstream with nothing cached, an upstream that
  serves garbage) is left out instead of failing the group. Its charts that are not cached are a
  `503` while its upstream is down.
* Groups of groups are flattened.
* Groups are read-only; the `405` names the hosted member to upload to.

## Client configuration

```bash
# a read token or a personal API token works as the password; create one on your account page
helm repo add granary http://granary.example.com:8081/repository/helm-group \
  --username alice --password gry_…
helm repo update
helm search repo granary/
helm install my-release granary/acme-web --version 1.2.0

# uploading: the ChartMuseum API on the hosted repository
helm package ./acme-web
curl -u alice:gry_… --data-binary @acme-web-1.2.0.tgz \
  http://granary.example.com:8081/repository/helm-hosted/api/charts
helm package ./acme-web --sign --key 'Release Signer' --keyring ~/.gnupg/secring.gpg
curl -u alice:gry_… -F chart=@acme-web-1.2.0.tgz -F prov=@acme-web-1.2.0.tgz.prov \
  http://granary.example.com:8081/repository/helm-hosted/api/charts
helm pull granary/acme-web --version 1.2.0 --verify --keyring ~/.gnupg/pubring.gpg

# or with the helm-push plugin (helm 3; helm 4 loads helm-push 0.10 as a getter plugin only)
helm plugin install https://github.com/chartmuseum/helm-push
helm repo add granary-hosted http://granary.example.com:8081/repository/helm-hosted \
  --username alice --password gry_…
helm cm-push ./acme-web granary-hosted

# delete a version
curl -u alice:gry_… -X DELETE \
  http://granary.example.com:8081/repository/helm-hosted/api/charts/acme-web/1.2.0
```

Chart dependencies can name the repository by URL or by the alias it was added under:

```yaml
dependencies:
  - name: acme-web
    version: "~1.2.0"
    repository: "@granary"   # or "http://granary.example.com:8081/repository/helm-group"
```

helmfile, Flux and Argo CD take the same URL and credentials; the connect dialogue of each
repository has ready-to-paste `helmfile.yaml`, `HelmRepository` (with its secret) and Argo CD
repository secret snippets. Artifact Hub reads repositories anonymously, so a repository listed
there must be reachable from the internet with anonymous read access enabled.

### Credentials

helm, helm-push, helmfile, Flux and Argo CD send HTTP Basic; an API token works as the password,
and `Authorization: Bearer <token>` is accepted as well (`helm cm-push --access-token`). helm sends
the credentials of `helm repo add` only to the repository's host, which is why every download URL
in the index is on Granary. Reads need no credentials while anonymous reads are enabled; uploads
and deletes always need the write privilege for the repository (`401` with a `Basic` challenge
without credentials, `403` without the privilege). `GET health` is always open.

## Settings

| Key | Applies to | Default | Meaning |
|---|---|---|---|
| `helm.relativeUrls` | all | `false` | publish download URLs relative to `index.yaml` instead of absolute URLs |
| `helm.maxChartSizeMb` | hosted | `20` | largest chart archive accepted on upload, in MiB (`0`: no limit, at most `10240`) |

Invalid values are refused when the repository is saved (`400 repository.helm.<key>.invalid`).

## Offline behaviour

* Cached chart archives and provenance files are served without contacting the upstream. A cached
  `index.yaml` is re-validated after *metadata max age* and served stale while the upstream is
  unreachable or the repository (or the whole server) is offline.
* An uncached index or chart is a `503` (`upstream.offline` / `upstream.unavailable`), which helm
  reports as a failed download rather than a missing chart.
* A group leaves out a member whose index is unavailable and still serves the others, including
  hosted charts.

## Verification

`granary-format-helm/scripts/verify-helm.sh <base-url>` drives a running Granary with the real helm
CLI (from the PATH, or the `alpine/helm` image through docker; `HELM_IMAGE=alpine/helm:4.2.0` runs it
with helm 4) and a throw-away helm configuration:
it creates proxies of helm/examples and kubernetes/ingress-nginx (indexes on raw.githubusercontent.com,
archives on GitHub release downloads) in `helm-group`, then runs `helm repo add`, `search`, `pull`
and `template` through the group, uploads with curl (raw and multipart with a provenance file,
duplicate `409`, anonymous `401`, group `405`), signs with `helm package --sign` and checks
`helm pull --verify` and `helm verify`, pushes with `helm cm-push` (to a repository alias and a URL,
duplicate and group errors), builds an umbrella chart with `helm dependency update`, renders a
hosted and a proxied chart with `helmfile template` (from the PATH, or the `chatwork/helmfile` image;
override with `HELMFILE_IMAGE`), deletes a version through the API, and finally switches to offline mode with an empty helm cache (cached
charts still pull, an uncached one fails with `503`) and disables anonymous reads (helm needs its
credentials, `health` stays open).

## Limitations

* OCI charts are served by Docker repositories, not by this format.
* Granary does not check provenance signatures; it stores and serves `.prov` files, checks that they
  belong to their archive, and leaves signature verification to `helm pull --verify`.
* A proxy uses the first URL of each index entry; mirrors listed after it are not tried.
* A proxy sends its upstream credentials to every host its index links archives to (for example a
  release download host). The CocoaPods, Composer, Go and pub proxies limit them to the upstream's
  own origin; the Helm proxy does not yet, so configure credentials only for upstreams whose
  archives stay on hosts you trust with them.
* `helm cm-push` needs helm 3: helm 4 installs the helm-push plugin (0.10.4, with `--verify=false`)
  as a getter plugin and offers no `cm-push` command, so upload with curl or `PUT` from helm 4.
* ChartMuseum's `api/charts/<name>/<version>/templates` and `…/values` endpoints and its
  multi-tenant paths are not implemented; `?force` needs *allow redeploy*.
* A group's index is built from its members' indexes at request time (and cached until one of them
  changes), so a group of many large proxies costs memory proportional to their indexes.
