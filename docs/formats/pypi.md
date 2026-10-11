# PyPI repositories

Format id: `pypi`. Index URL: `http://host:8081/repository/<name>/simple`, upload URL:
`http://host:8081/repository/<name>/`.

Works with pip, uv, Poetry, Pipenv, PDM, twine, Hatch and Flit because Granary implements the
Simple Repository API (PEP 503 HTML and PEP 691 JSON, with the PEP 658 metadata files, PEP 592
yanking, PEP 629 repository version and PEP 700 version lists), the legacy upload API that
twine and the other publishers use, and the `pypi/<project>/json` API.

## Endpoints

Every path is relative to the repository URL.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `simple/` | Project list (HTML, or JSON with `Accept: application/vnd.pypi.simple.v1+json`) |
| `GET` | `simple/<project>/` | Project page: every file with its hashes, `requires-python`, yanked flag and metadata link |
| `GET`, `HEAD` | `packages/…/<file>`, `packages/…/<file>.metadata` | Distribution files and their PEP 658 metadata |
| `GET` | `pypi/<project>/json`, `pypi/<project>/<version>/json` | JSON API |
| `POST` | `/` (the repository root) or `legacy/` | Upload (hosted) |
| `DELETE` | `packages/<project>/<version>/<file>`, `packages/<project>/<version>` | Delete a file or a release (hosted) |

Project names in other spellings (`simple/Acme.Demo`) are redirected to the normalised URL.

## Standard repositories

| Name | Type | Upstream / members |
|------|------|--------------------|
| `pypi-proxy` | proxy | `https://pypi.org/` |
| `pypi-hosted` | hosted | |
| `pypi-group` | group | `pypi-hosted`, `pypi-proxy` |

## Hosted

* `twine upload`, `uv publish`, `hatch publish`, `flit publish` and `poetry publish` all post the
  same multipart form to the repository root (or `legacy/`). The project name, version and
  file type must agree with the distribution file name and with the core metadata inside the
  archive, and the SHA-256, MD5 and BLAKE2b-256 digests the client sends must match the bytes.
* Wheels and sdists (`.tar.gz`, `.zip`, `.tar.bz2`, `.tar.xz`) are stored under
  `packages/<project>/<version>/<file>`. The core metadata (`METADATA` or `PKG-INFO`) is
  extracted on upload and served as the PEP 658 `<file>.metadata` sidecar, so pip and uv resolve
  dependencies without downloading the whole wheel.
* Re-uploading an existing file is refused with `400 File already exists` (the response pypi.org
  gives) unless *allow redeploy* is set on the repository. A file name is unique within a project
  whatever version it was uploaded under, a version spelled differently from a stored one (`1.0`
  next to `1.0.0`) joins that release, and concurrent uploads of the same file cannot both win.
* `DELETE packages/<project>/<version>/<file>` removes one file, `DELETE packages/<project>/<version>`
  a whole release (any PEP 440 spelling of the version). Both need write access.
* Index pages carry an `ETag` (conditional requests are answered with `304`) and `Vary: Accept`,
  since the same URL answers HTML or JSON depending on what the client asks for.
* Every uploaded release is a component (project and version) in the component browser.
* Project names are normalised (PEP 503): `simple/Acme.Demo` redirects to `simple/acme-demo/`,
  exactly like pypi.org.

## Proxy

* `remoteUrl` is the root of an index such as `https://pypi.org/` (project pages are fetched from
  `<remoteUrl>simple/<project>/`) or, the way pip users write it, the simple index itself
  (`https://pypi.org/simple/`, `https://nexus.example/repository/pypi-all/simple/`, devpi's
  `…/+simple/`). The JSON and HTML flavours are cached separately (`simple/<project>/index.json`
  and `index.html`), each for the clients that ask for it, and an upstream that only speaks HTML
  still satisfies JSON clients.
* Index pages are mutable documents: refreshed after *metadata max age*, served stale while
  upstream is unreachable. Distribution files and their `.metadata` sidecars are immutable and
  cached forever.
* Every file link in a page (and in the `pypi/<project>/json` documents) is rewritten to the
  Granary repository (or the group the client asked through), so a lockfile never points at
  `files.pythonhosted.org`. Links on the files host keep their path (`packages/ab/cd/…/<file>`);
  any other link, for example to another Granary, Nexus, devpi or a mirror, becomes
  `packages/-/<project>/<file>` and is resolved again from the cached project page when the file is
  requested. Proxying another index therefore needs no extra setting.
* `pypi.filesUrl` (default `https://files.pythonhosted.org/`) names the files host whose
  `<filesUrl>packages/…` links are cached under their own path. Set it to a mirror's files root
  only if you want that mirror's layout kept in the cache.
* Every cached distribution (and its `.metadata` sidecar) is linked to a component, so what was
  installed through the proxy shows up in the component browser and in search.
* The project list (`simple/`) is fetched from upstream on demand; for pypi.org that is a
  document of tens of megabytes (about 45 MB of HTML or 23 MB of JSON), cached as metadata and
  parsed again for every request, which takes seconds. pip, uv and the other installers never ask
  for it.
* Upstream 404s are remembered for the repository's *negative cache* period.

## Group

* Project pages are merged across members: files are unioned by file name (the first member that
  has a file wins), version lists are unioned, and the `pypi/<project>/json` document takes its
  `info` from the member that owns the highest release.
* The project list is the union of the members' lists.
* Groups are read-only; uploads are refused with a message naming the hosted member.
* Merging means a group that contains a proxy of pypi.org also offers pypi.org's releases of a
  project you host yourself (the same behaviour as other repository managers). Give private
  projects names that are not taken on pypi.org, or install them from the hosted repository.

## Client configuration

The connect dialogue of every PyPI repository shows these snippets with the right URL and the
caller's own token. `http://` repositories on a host other than `localhost` additionally need
pip's `--trusted-host` (also `PIP_TRUSTED_HOST` in a Docker build), `verify_ssl = false` in a
Pipfile and `pdm config pypi.verify_ssl false`; uv and Poetry accept plain http as it is.

```ini
# ~/.config/pip/pip.conf
[global]
index-url = http://granary.example.com:8081/repository/pypi-group/simple
# with credentials: index-url = http://alice:gry_…@granary.example.com:8081/repository/pypi-group/simple
```

```bash
pip install --index-url http://granary.example.com:8081/repository/pypi-group/simple <package>

export UV_DEFAULT_INDEX=http://granary.example.com:8081/repository/pypi-group/simple   # UV_INDEX_URL before uv 0.4.23
uv pip install <package>

poetry source add --priority=primary granary http://granary.example.com:8081/repository/pypi-group/simple
poetry config http-basic.granary alice gry_…

pdm config pypi.url http://granary.example.com:8081/repository/pypi-group/simple
```

```toml
# pyproject.toml (uv)
[[tool.uv.index]]
name = "granary"
url = "http://granary.example.com:8081/repository/pypi-group/simple"
default = true

# Pipfile
[[source]]
url = "http://granary.example.com:8081/repository/pypi-group/simple"
verify_ssl = false   # true for https
name = "granary"
```

```ini
# ~/.pypirc
[distutils]
index-servers =
    granary

[granary]
repository = http://granary.example.com:8081/repository/pypi-hosted/
username = __token__
password = gry_…
```

```bash
python -m build && twine upload -r granary dist/*
uv publish --publish-url http://granary.example.com:8081/repository/pypi-hosted/ \
  --check-url http://granary.example.com:8081/repository/pypi-hosted/simple --username __token__ --password gry_…
hatch publish -r http://granary.example.com:8081/repository/pypi-hosted/ -u __token__ -a gry_…
flit publish --repository granary
poetry config repositories.granary http://granary.example.com:8081/repository/pypi-hosted/ && poetry publish --build -r granary
```

Credentials are HTTP Basic: either a username with its password or an API token, or the
literal user `__token__` with an API token as the password.

## Settings

| Key | Applies to | Default | Meaning |
|-----|------------|---------|---------|
| `pypi.filesUrl` | proxy | `https://files.pythonhosted.org/` | Files host whose `<filesUrl>packages/…` links are cached under their own path (see *Proxy*); must be an absolute `http(s)` URL |

## Offline behaviour

* Cached distributions and `.metadata` sidecars are served without contacting the upstream. Cached
  project pages and JSON documents are re-validated after *metadata max age* and served stale while
  the upstream is unreachable or the repository (or the whole server) is offline, so installers
  with empty caches still resolve everything that was installed through Granary before.
* A page or file that is not cached is a `503` (`upstream.offline` / `upstream.unavailable`)
  rather than a `404`, so the installer reports a server error instead of "no matching
  distribution".
* A group keeps serving hosted projects and everything its proxy members have cached.
* Concurrent requests for the same uncached file make one upstream request.

## Limitations

* twine 7 refuses `--skip-existing` for any repository other than pypi.org on the client side;
  a repeated upload is refused by Granary with `400 File already exists`, which older twine
  versions recognise. `uv publish --check-url <index>/simple` skips files that are already
  there.
* Hosted repositories do not support yanking; the `.metadata` sidecar and the simple page carry
  no `gpg-sig`.
* The hosted `pypi/<project>/json` document carries SHA-256 digests only (no MD5 or BLAKE2).
* The upload API accepts distribution files; `:action=submit` (registering a project without a
  file) is not supported, which no current client uses.
* A proxy sends its upstream credentials (*remote username/password* or *remote token*) to the
  `pypi.filesUrl` host and to any other host a project page links files to, not only to the
  index origin.
