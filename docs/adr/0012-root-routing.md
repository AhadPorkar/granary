# 12. Root routing for registries that require the server root

Date: 2026-10-11 · Status: accepted

## Context

Every repository lives under `/repository/{name}/`, and most clients take that URL as it is. Two
protocols do not:

* **Docker / OCI.** docker, podman, containerd, kubelet, buildah and skopeo address a registry by
  host and port only and always call `http(s)://host:port/v2/…`. An image reference cannot carry a
  path prefix, so `host:8081/repository/docker-group` is not a registry they can use.
* **Terraform / OpenTofu.** A provider or module source names a host
  (`granary.example.com/hashicorp/aws`); the CLIs look up `/.well-known/terraform.json` on that host
  to find the registry services. The discovery document can point anywhere, but the lookup itself
  is fixed at the root.

The options weighed:

* **A reverse proxy rule per deployment** that rewrites `/v2/` and `/.well-known/terraform.json`.
  Works, but every installation would need it, the plain `java -jar` setup would not work with
  docker at all, and the rewrite would have to know repository names.
* **A dedicated port or virtual host per Docker repository**, as some repository managers do.
  More moving parts (ports to open, connectors to configure) for a single-binary server.
* **Hard-coding Docker and Terraform routes in the server module.** Puts format knowledge back into
  the core that ADR 0005 keeps format-agnostic.
* **Letting formats claim root paths through the SPI.**

## Decision

`FormatHandler.routeRoot(RootRequest)` (default: no claim) lets a format claim a request that
arrived outside `/repository/`, `/api/`, `/actuator/` and the UI's assets. `RootRequest` carries
the method, the decoded root-relative path and a read-only lookup of repositories by name and by
format. A claim is a `RootRoute(repositoryName, path)`.

`RootRouteFilter`, registered right after the Spring Security filter chain, asks the handlers in
turn; the first claim wins. The request is forwarded to `/repository/{name}/{path}` with the
attribute `RootRoute.ATTRIBUTE` set to the original root path, so it goes through the normal
dispatcher: the caller is already authenticated, privileges are checked per repository as for any
other request, and a handler can tell from the attribute that it must write root-relative URLs (for
example in Docker's `Location` headers). Unclaimed requests continue to the UI's single-page
application as before.

The two formats that need it use it narrowly:

* Docker claims `v2` and everything below it. `/v2/<repository>/<image>/…` goes to the named docker
  repository; any other `/v2/…` goes to the *root repository*: the one with `docker.rootRepository`
  set, else the first docker group, else the first docker repository, by name. The standard
  `docker-group` is flagged.
* Terraform claims only `GET`/`HEAD` of `.well-known/terraform.json` and forwards it to the
  repository with `terraform.rootRepository` set, else the first Terraform group, else the first
  Terraform repository. The document it returns names that repository's own `v1/providers/` and
  `v1/modules/` services under `/repository/<name>/`, so every later request uses the normal URL.

## Consequences

* `docker pull host:8081/alpine:3.20` and `source = "granary.example.com/hashicorp/aws"` work
  without a reverse proxy rule. A reverse proxy is still needed for TLS: Terraform and OpenTofu use
  a registry only over HTTPS, and Docker clients accept plain HTTP only for `localhost` or a
  configured insecure registry.
* One repository per format answers at the root. Other Docker repositories are reached with their
  name as the first path segment (`host:8081/docker-hosted/acme/app`), other Terraform repositories
  only through their `/repository/<name>/` URL (or as network mirrors).
* The root catalog (`/v2/_catalog`) lists the root repository's images without a prefix.
* Root paths are claimed only when a repository of the format exists; on a server without Docker
  repositories `/v2/` is left to the web UI like any other unknown path.
* A future format with fixed root URLs adds a `routeRoot` implementation instead of a server change.
  Two formats claiming the same path would be resolved by handler order, so claims must stay
  specific.
