# Format guides

One guide per format module. Every guide has the same core sections: an overview (format id and
URL), the endpoints the clients use, the standard repositories, hosted / proxy / group behaviour,
client configuration, settings, offline behaviour and limitations. The *Connect* panel of each
repository in the web UI shows the same client configuration with the server's URL and the
signed-in user's name filled in.

`POST /api/v1/repositories/standard` (or *Create standard repositories* in the UI) creates the
standard repositories of every installed format; repositories whose name is taken are left alone.
Members are created before the groups that contain them.

| Format | Format id | Hosted | Proxy | Group | Standard repositories | Clients | Guide |
|--------|-----------|:------:|:-----:|:-----:|-----------------------|---------|-------|
| Maven | `maven2` | ✔ | ✔ | ✔ | `maven-central`, `maven-google`, `clojars`, `gradle-plugins`, `maven-releases`, `maven-snapshots`, `maven-public` | Maven, Gradle, sbt, Leiningen | [maven.md](maven.md) |
| npm | `npm` | ✔ | ✔ | ✔ | `npm-proxy`, `npm-hosted`, `npm-group` | npm, pnpm, Yarn, Bun | [npm.md](npm.md) |
| NuGet | `nuget` | ✔ | ✔ | ✔ | `nuget.org`, `nuget-hosted`, `nuget-group` | dotnet, nuget.exe, Visual Studio, Paket | [nuget.md](nuget.md) |
| PyPI | `pypi` | ✔ | ✔ | ✔ | `pypi-proxy`, `pypi-hosted`, `pypi-group` | pip, uv, Poetry, Pipenv, PDM, twine, Hatch, Flit | [pypi.md](pypi.md) |
| Docker / OCI | `docker` | ✔ | ✔ | ✔ | `docker-hub`, `ghcr`, `quay`, `docker-hosted`, `docker-group` | docker, podman, buildah, skopeo, containerd/nerdctl, kubelet, oras, crane, helm (`oci://`) | [docker.md](docker.md) |
| Go modules | `go` | ✔ | ✔ | ✔ | `go-proxy`, `go-hosted`, `go-group` | go | [go.md](go.md) |
| Cargo | `cargo` | ✔ | ✔ | ✔ | `cargo-proxy`, `cargo-hosted`, `cargo-group` | cargo 1.68+ | [cargo.md](cargo.md) |
| Helm | `helm` | ✔ | ✔ | ✔ | `helm-hosted`, `helm-group` | helm 3 and 4, helm-push, helmfile, Flux, Argo CD | [helm.md](helm.md) |
| Terraform / OpenTofu | `terraform` | ✔ | ✔ | ✔ | `terraform-registry`, `opentofu-registry`, `terraform-hosted`, `terraform-group` | terraform, tofu | [terraform.md](terraform.md) |
| Conan | `conan` | ✔ | ✔ | ✔ | `conancenter`, `conan-hosted`, `conan-group` | Conan 2, Conan 1.x with revisions | [conan.md](conan.md) |
| Hex | `hex` | ✔ | ✔ | ✔ | `hexpm`, `hex-hosted`, `hex-group` | mix, rebar3 | [hex.md](hex.md) |
| Composer | `composer` | ✔ | ✔ | ✔ | `packagist`, `composer-hosted`, `composer-group` | Composer 2 | [composer.md](composer.md) |
| pub | `pub` | ✔ | ✔ | ✔ | `pub-dev`, `pub-hosted`, `pub-group` | dart pub, flutter pub | [pub.md](pub.md) |
| Swift | `swift` | ✔ | ✔ (another Swift registry) | ✔ | `swift-hosted`, `swift-group` | SwiftPM 5.7+, Xcode | [swift.md](swift.md) |
| RubyGems | `rubygems` | ✔ | ✔ | ✔ | `rubygems-proxy`, `rubygems-hosted`, `rubygems-group` | gem, Bundler | [rubygems.md](rubygems.md) |
| CocoaPods | `cocoapods` | ✔ | ✔ | ✔ | `cocoapods-cdn`, `cocoapods-hosted`, `cocoapods-group` | pod 1.8+ | [cocoapods.md](cocoapods.md) |
| Raw (generic files) | `raw` | ✔ | ✔ | ✔ | `raw-hosted` | curl, wget, PowerShell | [raw.md](raw.md#raw--generic-files) |
| Browser CDN assets | `cdn` | ✔ | ✔ | ✔ | `cdnjs`, `jsdelivr`, `unpkg` | browsers, import maps, Vite, webpack | [raw.md](raw.md#cdn--browser-cdn-assets) |
| CPAN | `cpan` | ✔ | ✔ | ✔ | `cpan-proxy` | cpanm, cpan, Carton, cpm | [raw.md](raw.md#cpan--perl) |
| CRAN | `cran` | ✔ | ✔ | ✔ | `cran-proxy` | R, renv, pak | [raw.md](raw.md#cran--r) |
| Hackage / Stackage | `hackage` | ✔ | ✔ | ✔ | `hackage-proxy`, `stackage-proxy`, `stackage-snapshots` | cabal, stack | [raw.md](raw.md#hackage--haskell) |
| vcpkg | `vcpkg` | ✔ | – | – | `vcpkg-cache` | vcpkg (asset and binary caches) | [raw.md](raw.md#vcpkg--cc-asset-and-binary-caches) |
| Bower | `bower` | ✔ | ✔ | ✔ | `bower-registry` | bower | [raw.md](raw.md#bower--registry) |
| Elm | `elm` | ✔ | ✔ | ✔ | `elm-proxy` | elm 0.19.1 (behind a host override) | [raw.md](raw.md#elm--packages) |

Repository URLs are `http(s)://<host>/repository/<name>/…` for every format. Docker additionally
answers at the server root (`/v2/`) and Terraform's service discovery at
`/.well-known/terraform.json` (see [ADR 0012](../adr/0012-root-routing.md)).

Format-specific settings are stored in a repository's `formatSettings` object; each guide's
*Settings* section lists its keys, and [configuration.md](../configuration.md#format-settings)
has the complete index. Proxy repositories of every format also take `upstream.credentialHosts`,
the hosts besides the upstream's own origin that may receive its credentials (see
[Upstream credentials](../configuration.md#upstream-credentials)); the guides do not repeat it, so
"no format settings" in a guide means none of the format's own. How a format plugs into the server
is described in [architecture.md](../architecture.md#the-format-spi) and [ADR 0011](../adr/0011-format-spi-v2.md).
