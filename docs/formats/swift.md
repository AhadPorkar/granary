# Swift package registries

Format id: `swift`. Registry URL: `https://host/repository/<name>` (no trailing slash needed).

Granary implements the Swift Package Registry Service protocol (SE-0292, publishing per SE-0321) that
SwiftPM 5.7 and later speak: `swift package-registry set|login|publish`, `.package(id: "scope.name", …)`
dependencies, `swift package resolve` and `--replace-scm-with-registry`. Xcode reads the same
configuration files. Verified end to end with SwiftPM 6.1.3 (`swift:6.1` image) by
`granary-format-swift/scripts/verify-swift.sh`.

There is no public Swift registry to mirror (the Swift Package Index is a catalogue, not a registry),
so the standard repositories are a hosted registry and a group. A proxy repository fronts *another*
Swift registry (another Granary, Artifactory, a company registry) with the same paths.

| Endpoint | |
|---|---|
| `GET <scope>/<name>` | release list (`Link: latest-version`, `canonical`/`alternate` source repositories) |
| `GET <scope>/<name>/<version>` (also `.json`) | release metadata with the archive checksum (`Link: latest-version`, `successor-version`, `predecessor-version`) |
| `GET <scope>/<name>/<version>/Package.swift[?swift-version=]` | manifest; `Link` lists `Package@swift-*.swift` variants; `303` to the default manifest when there is no variant for that Swift version |
| `GET <scope>/<name>/<version>.zip` | source archive (`Digest: sha-256=…`, `Content-Disposition`, signature headers when signed) |
| `GET identifiers?url=<source repository URL>` | package identities registered for a repository URL |
| `PUT <scope>/<name>/<version>` | publish (multipart `source-archive`, `metadata`, signatures) |
| `POST` on the repository URL (or `…/login`) | login check (`swift package-registry login`) |
| `DELETE <scope>/<name>[/<version>]` | Granary extension: delete a release or a whole package |

Every response of the registry carries `Content-Version: 1`; its errors are `application/problem+json`
with a `detail` member, which SwiftPM prints. (Answers the server gives before the registry is reached,
such as `403` for a user without the privilege or `503` while an upstream is unreachable, are Granary's
plain JSON errors.) Identities are case-insensitive (`Acme.Demo` and `acme.demo` are the same package).
Requests that ask for another API version in `Accept` (`application/vnd.swift.registry.v2+json`) get
`415`, a malformed version `400`; requests without a registry media type are served as version 1.
Malformed paths (`..` segments, invalid scopes, names or versions, encoded slashes `%2F` or
backslashes) are refused with `400`.

## Hosted

* `swift package-registry publish <scope>.<name> <version>` uploads the source archive SwiftPM builds
  (the package directory zipped below one top-level directory) together with `package-metadata.json`.
  Granary checks what SwiftPM needs to consume the release: a valid zip with exactly one top-level
  directory containing `Package.swift`, a `// swift-tools-version:` line in every manifest, no entry
  escaping the extraction directory. `Package.swift` and its `Package@swift-<version>.swift` variants
  are extracted and served from the manifest endpoint, so resolution never downloads archives it does
  not need. The checksum in the release metadata is the SHA-256 of the archive.
* The metadata is validated against the protocol's schema for the members SwiftPM decodes (`author`,
  `description`, `licenseURL`, `readmeURL`, `repositoryURLs`, `originalPublicationTime`); other members
  are kept as published. `repositoryURLs` feed `identifiers?url=` lookups, so
  `swift package --replace-scm-with-registry resolve` takes `.package(url: "https://github.com/acme/demo", …)`
  from the registry. URL spellings are normalised (`https://…`, `….git`, `git@host:path`, `ssh://…`).
* Signed releases (`--signing-identity`, or `--private-key-path` with `--cert-chain-paths`) keep the
  signature and its format; both are returned in the release metadata (`signing`) and as
  `X-Swift-Package-Signature(-Format)` headers on the archive. The manifests carry their own signature
  comment, added by SwiftPM before the archive is built, and are served as published. SwiftPM's signature
  checks (`security.default.signing` in `registries.json`, e.g. `onUntrustedCertificate: error` with a
  `trustedRootCertificatesPath`) work against Granary: the verify script publishes a release signed with
  a test certificate and resolves it with those checks on.
* Re-publishing an existing version is refused with `409` (`a release with version … already exists`)
  unless *allow redeploy* is set on the repository.
* Versions are Semantic Versioning 2.0; versions whose text ends in `.zip` or `.json` are refused
  (`422`) because their URLs cannot be told apart from the archive and `.json` URLs of another version.
* Publishing answers `201 Created` with `Location` synchronously (the `Prefer: respond-async` SwiftPM
  sends is allowed to be ignored).

Repository settings (hosted):

| Key | Default | |
|---|---|---|
| `swift.requireSignature` | `false` | refuse unsigned releases (`422`) |
| `swift.maxArchiveSizeMb` | none | refuse larger source archives (`413`) |

## Proxy (of another registry)

* `remoteUrl` is the other registry's base URL, e.g. `https://registry.example.com/swift/`. Paths map
  one to one.
* Release lists, release metadata and identifier lookups are metadata: re-validated after *metadata
  max age* and served stale while the upstream is unreachable. Archives and manifests (with their
  variants and the `Link` header that lists them) are immutable and cached forever. Release URLs in
  lists are rewritten to the Granary repository (or the group the client asked through).
* An upstream `404` is remembered by the negative cache; something never cached while the upstream is
  down or the repository is offline is a `503` (`upstream.unavailable` / `upstream.offline`).

## Group

* Release lists are merged across members: the first member (in member order) that lists a version
  owns it; release metadata, manifests and the archive of that version come from the owner. Hosted
  members should come first.
* `identifiers?url=` returns the union of the members' answers.
* Groups are read-only; a publish names the hosted member to use. Logging in through a group works.

## Client configuration

SwiftPM logs in (`swift package-registry login`) only over HTTPS and refuses to publish with
credentials over plain HTTP, even with `--allow-insecure-http`. Run Granary behind a TLS reverse proxy
(Caddy, nginx, Traefik) and set its base URL to the `https://` address. Resolving over plain HTTP
works with `--allow-insecure-http`.

```bash
# resolve every registry dependency through the group (writes ~/.swiftpm/configuration/registries.json)
swift package-registry set https://granary.example.com/repository/swift-group --global
# or only one scope
swift package-registry set https://granary.example.com/repository/swift-group --scope acme

# log in with an API token (stored in ~/.netrc on Linux, the keychain on macOS)
swift package-registry login https://granary.example.com/repository/swift-hosted --token gry_… --no-confirm
# or username and token as password
swift package-registry login https://granary.example.com/repository/swift-hosted --username jane --password gry_… --no-confirm

# publish (in the package directory; package-metadata.json is picked up when present)
swift package-registry publish acme.demo 1.0.0 --url https://granary.example.com/repository/swift-hosted
```

```swift
// Package.swift
dependencies: [
    .package(id: "acme.demo", from: "1.0.0"),
],
targets: [
    .executableTarget(name: "App", dependencies: [.product(name: "Demo", package: "acme.demo")]),
]
```

```bash
swift package resolve && swift build
# take source-control dependencies from the registry where a release lists that repository URL
swift package --replace-scm-with-registry resolve
```

`.swiftpm/configuration/registries.json` (project) or `~/.swiftpm/configuration/registries.json`
(user). Authentication entries are keyed by host and port, `~/.netrc` machines by host alone:

```json
{
  "authentication": { "granary.example.com": { "type": "token" } },
  "registries": { "[default]": { "supportsAvailability": false, "url": "https://granary.example.com/repository/swift-group" } },
  "version": 1
}
```

```text
machine granary.example.com
  login token
  password gry_…
```

Publishing with curl (the archive must hold the package below one top-level directory, as
`swift package archive-source` creates it):

```bash
curl -u jane:gry_… -X PUT -H "Accept: application/vnd.swift.registry.v1+json" \
  -F "source-archive=@demo-1.0.0.zip;type=application/zip" \
  -F "metadata=@package-metadata.json;type=application/json" \
  https://granary.example.com/repository/swift-hosted/acme/demo/1.0.0
```

## Authentication

Reads follow the server's anonymous-read setting; publishing and deleting need write access to the
repository. API tokens arrive as `Authorization: Bearer` (`login --token`) or Basic
(`login --username/--password`). An anonymous caller without the needed privilege gets a `401`
problem document *without* a `WWW-Authenticate` challenge: SwiftPM on Linux (swift-corelibs-foundation)
never completes a request answered with a Basic challenge and would hang instead of reporting the
missing login.

## Limitations

* Plain HTTP: SwiftPM cannot log in or publish (client-side rule); use a TLS front.
* A wrong or expired token is rejected by Granary's credential check before the Swift handler runs, and
  that `401` carries a Basic challenge; SwiftPM on Linux then waits forever (`login` and `publish`
  included). Check a token with `curl -H "Authorization: Bearer gry_…" -X POST <repository URL>`.
* Publishing is synchronous; there is no `202 Accepted` status polling.
* Release lists are not paginated (no `Link: rel="next"`).
* No package collections or search endpoint (not part of the registry protocol).
* Signatures are stored and returned but not verified by Granary; SwiftPM verifies them on download.
* `--allow-insecure-http` only matters for resolving; registries with anonymous read off need the
  `authentication` entry and `~/.netrc` machine written by hand when used over HTTP, because
  `swift package-registry login` refuses HTTP URLs.
