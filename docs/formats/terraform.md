# Terraform / OpenTofu repositories

Format id: `terraform`. Registry host: `https://granary.example.com` (the CLIs discover the services
through `/.well-known/terraform.json`); repository URL: `https://granary.example.com/repository/<name>/`.

Works with `terraform` and `tofu` because Granary implements the four protocols the CLIs use: remote
service discovery, the provider registry protocol, the module registry protocol and the provider
network mirror protocol.

## HTTPS first

Both CLIs refuse to talk to a registry or a network mirror over plain HTTP, so Granary has to sit
behind TLS. Put a reverse proxy in front and tell Granary its public URL with `GRANARY_BASE_URL`
(or the *base URL* system setting), so that the absolute URLs it writes into discovery, download
and mirror documents point back at the TLS side:

```
# Caddyfile
granary.example.com {
    reverse_proxy localhost:8081
}
```

```nginx
server {
    listen 443 ssl;
    server_name granary.example.com;
    client_max_body_size 0;
    location / {
        proxy_pass http://localhost:8081;
        proxy_set_header Host $host;
        proxy_request_buffering off;
    }
}
```

```bash
GRANARY_BASE_URL=https://granary.example.com
```

Naming rules the CLIs enforce: a provider source may name a host with a port
(`localhost:8443/acme/null`), a module source must name a host that contains a dot
(`granary.example.com/acme/demo/local`; `localhost:8443/acme/demo/local` is rejected, an IP address
such as `127.0.0.1/acme/demo/local` is accepted), a registry hostname with a port cannot be used
through a network mirror, and `TF_TOKEN_<host>` cannot name a port. A proper hostname on port 443
avoids all of them.

## Endpoints

Every path is relative to the repository URL, except the discovery document, which the server also
answers at its root (see *Service discovery*).

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `.well-known/terraform.json` | Service discovery: `providers.v1` and `modules.v1` of this repository |
| `GET` | `v1/providers/<ns>/<type>/versions` | Provider versions and platforms |
| `GET` | `v1/providers/<ns>/<type>/<version>/download/<os>/<arch>` | Download document (URLs, digest, signing keys) |
| `GET`, `PUT` | `v1/providers/<ns>/<type>/<version>/<os>/<arch>/<file>.zip` | Provider archive; `PUT` uploads (hosted) |
| `GET`, `PUT` | `v1/providers/<ns>/<type>/<version>/SHA256SUMS`, `…/SHA256SUMS.sig` | Checksums and signature; `PUT` uploads (hosted) |
| `GET`, `PUT` | `v1/providers/<ns>/<type>/gpg-key` | Public key for the signatures (hosted) |
| `GET` | `v1/modules/<ns>/<name>/<system>/versions` | Module versions |
| `GET` | `v1/modules/<ns>/<name>/<system>/<version>/download` | `204` with `X-Terraform-Get` |
| `GET`, `PUT` | `v1/modules/<ns>/<name>/<system>/<version>.tgz` | Module archive; `PUT` uploads (hosted) |
| `GET` | `files/…` | Provider files and module archives a proxy caches (rewritten download URLs) |
| `GET` | `mirror/<hostname>/<ns>/<type>/index.json`, `…/<version>.json` | Provider network mirror |
| `DELETE` | `v1/providers/<ns>/<type>[/<version>]`, `v1/modules/<ns>/<name>/<system>[/<version>]`, a file path | Remove a provider, module, version or file (hosted) |

## Standard repositories

| Name | Type | Upstream / members | Settings |
|------|------|--------------------|----------|
| `terraform-registry` | proxy | `https://registry.terraform.io/` | |
| `opentofu-registry` | proxy | `https://registry.opentofu.org/` | |
| `terraform-hosted` | hosted | | |
| `terraform-group` | group | `terraform-hosted`, `terraform-registry` | `terraform.rootRepository=true` |

So `source = "granary.example.com/hashicorp/aws"` resolves through the group right away.

## Service discovery

`https://granary.example.com/.well-known/terraform.json` is answered by the Terraform repository
flagged *root repository* (`terraform.rootRepository`), else by the first Terraform group, else by
the first Terraform repository. Every repository also serves the document under its own path
(`/repository/<name>/.well-known/terraform.json`), and the document always names that repository's
own `v1/providers/` and `v1/modules/` services.


## Hosted

Providers are published file by file, the way HashiCorp releases them; the handler generates the
`versions` and `download` documents from what is stored.

```bash
H=https://granary.example.com/repository/terraform-hosted/v1/providers/acme/null
curl -u user:gry_… -X PUT --data-binary @terraform-provider-null_3.2.4_linux_amd64.zip \
     $H/3.2.4/linux/amd64/terraform-provider-null_3.2.4_linux_amd64.zip
curl -u user:gry_… -X PUT --data-binary @terraform-provider-null_3.2.4_SHA256SUMS     $H/3.2.4/SHA256SUMS
curl -u user:gry_… -X PUT --data-binary @terraform-provider-null_3.2.4_SHA256SUMS.sig $H/3.2.4/SHA256SUMS.sig
curl -u user:gry_… -X PUT -H 'Content-Type: application/json' \
     --data '{"key_id":"34365D9472D7468F","ascii_armor":"-----BEGIN PGP PUBLIC KEY BLOCK-----\n…"}' $H/gpg-key
```

* `PUT v1/providers/<ns>/<type>/<version>/<os>/<arch>/<file>.zip` stores a build; the body must be
  a zip. `PUT …/<version>/SHA256SUMS` and `…/SHA256SUMS.sig` store the checksum file and its
  detached signature, `PUT v1/providers/<ns>/<type>/gpg-key` the public key (`key_id` and
  `ascii_armor`, as the registry protocol expects) that the CLIs verify the signature with.
  Uploads may arrive in any order; the archive digest is checked against `SHA256SUMS` whichever
  comes second, and a mismatch is refused with `400 repository.terraform.checksumMismatch`.
* The generated download document carries the archive's digest from `SHA256SUMS`, the key, and
  the protocol versions from the *provider protocols* setting (`terraform.providerProtocols`,
  default `5.0,6.0`). The CLIs need all three files plus the key: an archive without its
  `SHA256SUMS` and signature is listed but `terraform init` refuses it.
* Uploading an archive, `SHA256SUMS` or signature that already exists answers `409` unless *allow
  redeploy* is set on the repository; the key may be replaced at any time. Versions are stored in
  their canonical form (`v1.2.3` in a path means `1.2.3`), so a release cannot be uploaded twice
  under two spellings, and concurrent uploads of the same release are serialised: one wins, the
  others get `409`.
* `DELETE v1/providers/<ns>/<type>` removes a provider, `DELETE …/<version>` a version, `DELETE`
  on a file path that one file. Components are `(<ns>, <type>, <version>)`.
* `terraform providers mirror -platform=linux_amd64 ./mirror` downloads a project's providers
  with their `index.json`/`<version>.json`; upload the zips it produces together with the matching
  `SHA256SUMS`, its signature and the public key from the origin registry (or a `SHA256SUMS` you
  sign with your own key, and that key) to seed a hosted repository. The hosted repository's
  *Connect* page has both commands ready.

Modules are published as tarballs:

```bash
tar -czf demo-1.0.0.tgz main.tf variables.tf outputs.tf
curl -u user:gry_… -X PUT --data-binary @demo-1.0.0.tgz \
     https://granary.example.com/repository/terraform-hosted/v1/modules/acme/demo/local/1.0.0.tgz
```

* `PUT v1/modules/<ns>/<name>/<system>/<version>.tgz` (gzip body, module files at the top level of
  the archive); the `versions` document is generated and the `download` endpoint answers `204` with
  `X-Terraform-Get` pointing at the stored tarball. `409` on an existing version unless *allow
  redeploy* is set; `DELETE v1/modules/<ns>/<name>/<system>[/<version>]` removes a module or a
  version. Components are `(<ns>, <name>/<system>, <version>)`.
* `module "x" { source = "granary.example.com/acme/demo/local" version = "1.0.0" }`.

## Proxy

* `remoteUrl` is the registry root, e.g. `https://registry.terraform.io/` or
  `https://registry.opentofu.org/`. The upstream's own `.well-known/terraform.json` is read once
  (and refreshed like any metadata) to find its provider and module services.
* `versions` and `download` documents are mutable: refreshed after *metadata max age*, served stale
  while upstream is unreachable or the repository is offline. Provider archives, `SHA256SUMS` files
  and signatures are immutable and cached forever.
* The `download_url`, `shasums_url` and `shasums_signature_url` of every download document are
  rewritten to `files/<ns>/<type>/<version>/<file>` on the repository (or the group) the client
  asked through; the originals (`releases.hashicorp.com`, GitHub, …) are fetched on first use.
  `signing_keys` are passed through untouched, so the CLIs verify the registry's signatures.
* Module `download` answers are cached too. When the upstream points at an archive
  (`https://…/tarball/v1.0.0//*?archive=tar.gz`, `…/demo-1.0.0.tgz`), the archive is cached and
  `X-Terraform-Get` is rewritten to `files/modules/<ns>/<name>/<system>/<version>.<ext>` on
  Granary. A source relative to the upstream's download endpoint (`/archives/x.tgz`, `./x.tgz`) is
  resolved against it first, as the CLIs do. A `git::` or other non-archive source is passed through
  unchanged and the CLI fetches it itself (the public registry answers `git::https://github.com/…`
  for most modules).
* Cached provider files and module archives are recorded as components with the same coordinates
  as hosted uploads (`(<ns>, <type>, <version>)` and `(<ns>, <name>/<system>, <version>)`), so they
  can be browsed, searched and deleted like hosted content.
* Upstream `404`s are remembered for *negative cache* minutes; for offline use see
  [Offline behaviour](#offline-behaviour).

### Network mirror

Every repository also exposes the provider network mirror protocol, which is what the CLIs use in
air-gapped setups:

```hcl
# ~/.terraformrc or ~/.tofurc
provider_installation {
  network_mirror {
    url = "https://granary.example.com/repository/terraform-group/mirror/"
  }
}
```

`mirror/<hostname>/<ns>/<type>/index.json` and `…/<version>.json` are built on request from the
upstream's `versions` document and one download document per platform (each cached as metadata
like any other registry document, so the mirror keeps working offline for what was seen once) and
carry `zh:` hashes. A proxy answers for its upstream's hostname (`registry.terraform.io` for
`terraform-registry`, `registry.opentofu.org` for `opentofu-registry`) and for Granary's own
hostname; a hosted repository answers for any hostname with the providers it stores. A version
whose platform documents cannot be fetched is a `404` (`503` while the upstream is unreachable),
never an empty archive list.

## Group

* Provider `versions` documents are the union of the members' versions, members in order (hosted
  first in the standard group). The first member that has a version owns it: its entry (protocols
  and platforms) is the one listed, and the version's download documents, archives, `SHA256SUMS`,
  signature and network-mirror archives all come from that member. A hosted rebuild of
  `hashicorp/null 3.2.4` therefore hides the upstream's `3.2.4` entirely (including platforms the
  hosted copy lacks) instead of mixing its checksums with HashiCorp's signature.
* Module version lists are merged the same way; a module version's download and archive come from
  the first member that has it. The mirror index is the union of the members' versions.
* A member whose upstream is unreachable does not hide the others; a miss that only that member
  could have answered is a `503`.
* Groups are read-only; publish to the hosted member (the error names it, `405 repository.readOnly`).

## Client configuration

```hcl
terraform {
  required_providers {
    aws = {
      source = "granary.example.com/hashicorp/aws"
    }
  }
}

module "network" {
  source  = "granary.example.com/acme/network/aws"
  version = "~> 1.0"
}
```

```hcl
# ~/.terraformrc (Terraform) or ~/.tofurc (OpenTofu); TF_CLI_CONFIG_FILE / TOFU_CLI_CONFIG_FILE override the location
credentials "granary.example.com" {
  token = "gry_…"
}
```

```bash
export TF_TOKEN_granary_example_com=gry_…      # . becomes _, - becomes __; no port possible
export TF_REGISTRY_DISCOVERY_RETRY=3 TF_REGISTRY_CLIENT_TIMEOUT=30
```

OpenTofu reads the same configuration keys. Its default registry is `registry.opentofu.org`, so a
`tofu` project that keeps `source = "hashicorp/aws"` goes through Granary only with a
`network_mirror` pointing at `opentofu-registry` (or at a group that contains it); naming the
Granary host in the source works the same for both CLIs. The repository's *Connect* page offers
all of these snippets with the right host filled in.

## Settings

| Key | Applies to | Default | Meaning |
|-----|------------|---------|---------|
| `terraform.rootRepository` | all | `false` | Answer `/.well-known/terraform.json` at the server root (see *Service discovery*); flag one repository |
| `terraform.providerProtocols` | hosted | `5.0,6.0` | Provider protocol versions announced in the generated `versions` and download documents |

## Offline behaviour

* Provider archives, `SHA256SUMS` files, signatures and module archives a proxy has cached are
  served without contacting the upstream; `versions`, download documents and network-mirror
  indexes are re-validated after *metadata max age* and served stale while the upstream is
  unreachable or offline mode is on. `terraform init` and `tofu init` with empty plugin caches
  therefore keep working for everything installed before.
* An uncached document or file is a clean `503` (`upstream.offline` / `upstream.unavailable`).
* `git::` module sources are fetched by the CLI from their host and are not available offline.
* A group keeps serving hosted providers and modules and everything its proxy members cached.

## Limitations

* **Keep anonymous read enabled on Terraform repositories.** Both CLIs send the configured token
  with the registry calls (discovery, versions, download documents, mirror indexes) but fetch
  provider archives, `SHA256SUMS` files, signatures and module archives with a plain HTTP client
  that carries no credentials. With anonymous read off an installation stops at the first `401`.
  Restrict access at the reverse proxy if the registry must not be public.
* `git::` and other non-archive module sources are not cached; the CLI fetches them from the
  source host, which therefore has to be reachable.
* Hosted providers need the full HashiCorp-style set (zip, `SHA256SUMS`, `SHA256SUMS.sig`, key);
  Granary does not sign anything itself.
* `TF_TOKEN_<host>` variables cannot name a port; use a `credentials` block for `host:port`.
* A proxy sends its upstream credentials (*remote username/password* or *remote token*) with every
  request it makes for the repository, including the provider and module downloads on other hosts
  that the upstream's download documents name (`releases.hashicorp.com`, GitHub, …). Configure
  credentials only for upstreams whose download hosts you trust with them; the public registries
  need none.

## Verification

`granary-format-terraform/scripts/verify-terraform.sh http://localhost:8081` runs the real
`terraform` and `tofu` binaries against a running Granary through all of the above: providers and a
module through the group's proxy member, `terraform providers mirror`, the network mirror, a hosted
provider (re-signed files, signature verified by the CLI) and module, the redeploy, traversal and
authentication rules, every installation again in offline mode with empty client caches, and the
credentials block. For a plain-HTTP URL it puts `tls-forwarder.py` with a self-signed certificate
in front of Granary on `https://127.0.0.1` (port 443, as root), sets the base URL for the run and
restores it, downloads the CLIs when they are not installed and, where `registry.terraform.io`
cannot be reached, starts `stand-in-registry.py`: a minimal registry for `hashicorp/null` whose
download URLs still point at `releases.hashicorp.com`. The script's header lists its environment
variables.
