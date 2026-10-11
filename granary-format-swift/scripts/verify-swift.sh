#!/usr/bin/env bash
# End-to-end verification of the Swift package registry format with the real SwiftPM (swift:6.1 Docker image),
# including a proxy of another registry and an offline run.
#
# Usage: verify-swift.sh http://localhost:8113      (a running Granary; admin/admin123 must be valid)
#
# What it does
#   * creates the standard repositories (swift-hosted, swift-group), an API token, and a second registry to
#     proxy: swift-origin (hosted, standing in for another company registry) behind swift-proxy, which joins
#     swift-group as its second member;
#   * SwiftPM logs in and publishes over HTTPS only (with credentials), so the script starts tls-forwarder.py
#     with a throw-away certificate on SWIFT_TLS_PORT (default: Granary's port + 1000) in front of Granary, points Granary's base
#     URL at it while publishing, and makes the swift container trust the certificate;
#   * logs in with `swift package-registry login --token`, publishes acme.demo 1.0.0 (with a Swift 6 manifest
#     variant and package-metadata.json) to swift-hosted and acme.other 1.0.0 to swift-origin, and checks that a
#     duplicate publish is refused;
#   * publishes acme.signed 1.0.0 signed with a throw-away code-signing certificate (--private-key-path,
#     --cert-chain-paths) and resolves it with SwiftPM's signature checks set to `error`: refused while the
#     test root is not trusted, built and run once it is;
#   * resolves, builds and runs a consumer through swift-group over plain HTTP (--allow-insecure-http), takes a
#     source-control dependency from the registry with --replace-scm-with-registry, checks that an anonymous
#     publish fails with a clear 401 instead of hanging;
#   * switches the server to offline mode, wipes every SwiftPM cache and resolves/builds/runs again from what
#     Granary has cached.
#
# Environment:
#   SWIFT_IMAGE      the SwiftPM image (default swift:6.1)
#   SWIFT_TLS_PORT   port of the TLS front (default: Granary's port + 1000, e.g. 9113 for 8113)
#   KEEP_WORK        set to 1 to keep the temporary work directory (it is kept anyway when a step fails)
#   ZIP_BIN          a zip binary to mount into the container (default: `command -v zip`); the swift image has
#                    unzip but no zip, which `swift package-registry publish` needs to create the source archive
# Needs docker, curl, jq, python3, openssl and zip on the host.
set -uo pipefail
GRANARY=${1:-http://localhost:8113}; GRANARY=${GRANARY%/}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
IMAGE=${SWIFT_IMAGE:-swift:6.1}
GRANARY_HOSTPORT=${GRANARY#http://}; GRANARY_HOSTPORT=${GRANARY_HOSTPORT%%/*}
GRANARY_PORT=${GRANARY_HOSTPORT##*:}; [[ "$GRANARY_PORT" =~ ^[0-9]+$ ]] || GRANARY_PORT=80
TLS_PORT=${SWIFT_TLS_PORT:-$((GRANARY_PORT + 1000))}
TLS="https://localhost:$TLS_PORT"
WORK=$(mktemp -d /tmp/granary-verify-swift.XXXX)
cd "$WORK"
PASS=0; FAIL=0; KNOWN=0
ok()    { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()   { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
known() { echo "KNOWN $1"; KNOWN=$((KNOWN+1)); }
die()   { echo "ERROR $1" >&2; FAIL=$((FAIL+1)); exit 2; }
for tool in docker curl jq python3 openssl; do command -v $tool >/dev/null || die "$tool is required"; done
ZIP=${ZIP_BIN:-$(command -v zip)}; [ -x "$ZIP" ] || die "zip is required (set ZIP_BIN)"
[[ "$GRANARY" == http://* ]] || die "give the plain-HTTP URL of Granary; the script puts its own TLS front in front of it"

# --- admin session -----------------------------------------------------------------------------------------------------
TOKEN=""
for _ in $(seq 1 30); do
  TOKEN=$(curl -sSf -X POST "$GRANARY/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' 2>/dev/null | jq -r .token 2>/dev/null)
  [ -n "$TOKEN" ] && [ "$TOKEN" != null ] && break; sleep 1
done
[ -n "$TOKEN" ] && [ "$TOKEN" != null ] || die "cannot log in to $GRANARY as admin"
AUTH=(-H "Authorization: Bearer $TOKEN")
api() { curl -sS -f "${AUTH[@]}" -H 'Content-Type: application/json' "$@"; }
settings() { api -X PUT "$GRANARY/api/v1/system/settings" -d "$1" >/dev/null; }
ORIGINAL_BASE_URL=$(api "$GRANARY/api/v1/system/settings" | jq -r '.baseUrl // ""')
ORIGINAL_ANONYMOUS_READ=$(api "$GRANARY/api/v1/system/settings" | jq -r 'if .anonymousRead == false then "false" else "true" end')

PIDS=()
cleanup() {
  settings "{\"offlineMode\":false,\"anonymousRead\":$ORIGINAL_ANONYMOUS_READ,\"baseUrl\":$(jq -Rn --arg u "$ORIGINAL_BASE_URL" '$u')}" || true
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null; done
  if [ "${FAIL:-1}" -eq 0 ] && [ "${KEEP_WORK:-0}" != 1 ]; then cd / && rm -rf "$WORK"; else echo "work directory kept: $WORK"; fi
}
trap cleanup EXIT

echo "== Repositories"
api -X POST "$GRANARY/api/v1/repositories/standard" >/dev/null && ok "standard repositories (swift-hosted, swift-group)" || bad "standard repositories"
APITOKEN=$(api -X POST "$GRANARY/api/v1/tokens" -d '{"name":"verify-swift"}' | jq -r .secret)
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
# what an earlier run on this server left behind is removed, so the run can be repeated: its releases in
# swift-hosted, and swift-origin and swift-proxy with their content and cache (the cached archive of an earlier
# acme.other would not match the one published now). swift-group is set back to swift-hosted first, because a
# group member cannot be deleted.
api -X PUT "$GRANARY/api/v1/repositories/swift-group" -d '{"name":"swift-group","format":"swift","type":"GROUP","members":["swift-hosted"]}' >/dev/null 2>&1
for repo in swift-proxy swift-origin; do api -X DELETE "$GRANARY/api/v1/repositories/$repo" >/dev/null 2>&1; done
for id in $(api "$GRANARY/api/v1/repositories/swift-hosted/components?q=acme&size=500" \
            | jq -r '.items[] | select(.namespace == "acme" and (.name == "demo" or .name == "signed")) | .id'); do
  api -X DELETE "$GRANARY/api/v1/repositories/swift-hosted/components/$id" >/dev/null || bad "remove an earlier run's release $id"
done
api -X POST "$GRANARY/api/v1/repositories" -d '{"name":"swift-origin","format":"swift","type":"HOSTED","allowRedeploy":false}' >/dev/null 2>&1 \
  || api "$GRANARY/api/v1/repositories/swift-origin" >/dev/null || bad "create swift-origin"
api -X POST "$GRANARY/api/v1/repositories" -d "{\"name\":\"swift-proxy\",\"format\":\"swift\",\"type\":\"PROXY\",\"remoteUrl\":\"$GRANARY/repository/swift-origin/\",\"metadataMaxAgeMinutes\":0}" >/dev/null 2>&1 \
  || api "$GRANARY/api/v1/repositories/swift-proxy" >/dev/null || bad "create swift-proxy"
api -X PUT "$GRANARY/api/v1/repositories/swift-group" -d '{"name":"swift-group","format":"swift","type":"GROUP","members":["swift-hosted","swift-proxy"]}' >/dev/null \
  && ok "swift-origin behind swift-proxy, swift-group = [swift-hosted, swift-proxy]" || bad "group members"

# --- TLS front ---------------------------------------------------------------------------------------------------------
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 2 -subj /CN=localhost \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1 || die "openssl could not create a certificate"
TARGET=${GRANARY#http://}; TARGET=${TARGET%%/*}
python3 "$HERE/tls-forwarder.py" --cert cert.pem --key key.pem --listen "$TLS_PORT" --target "$TARGET" >forwarder.log 2>&1 &
PIDS+=($!)
for _ in $(seq 1 20); do curl -s --cacert cert.pem -o /dev/null "$TLS/repository/swift-hosted/" && break; sleep 0.5; done
curl -sSf --cacert cert.pem -o /dev/null "$TLS/repository/swift-hosted/" && ok "TLS front $TLS -> $GRANARY" || die "TLS front did not come up"

# --- SwiftPM in a container --------------------------------------------------------------------------------------------
# /root is SwiftPM's home (it reads the passwd entry, not $HOME): registries.json, .netrc and every cache live in
# $WORK/home, so wiping the caches is a matter of deleting directories there.
mkdir -p home
swift_in() { # swift_in <directory below $WORK> <command...>
  local dir=$1; shift
  docker run --rm --network host -v "$WORK/home:/root" -v "$WORK:/w" -v "$ZIP:/usr/bin/zip:ro" -w "/w/$dir" "$IMAGE" \
    bash -c 'cp /w/cert.pem /usr/local/share/ca-certificates/granary-verify.crt && update-ca-certificates >/dev/null 2>&1; '"$*" </dev/null
}
check() { if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -25 "$WORK/last.log"; fi; }
expect_output() { # expect_output <pattern> <command...>: passes when the command's output matches
  local pattern=$1; shift
  "$@" >"$WORK/last.log" 2>&1
  if grep -qE "$pattern" "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -25 "$WORK/last.log"; fi
}

library() { # library <dir> <Product> <greeting>
  mkdir -p "$1/Sources/$2"
  cat > "$1/Package.swift" <<EOF
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "$2",
    products: [.library(name: "$2", targets: ["$2"])],
    targets: [.target(name: "$2")]
)
EOF
  echo "public enum $2 { public static func greeting() -> String { \"$3\" } }" > "$1/Sources/$2/$2.swift"
}
library demo Demo "hello from acme.demo"
cat > demo/Package@swift-6.swift <<'EOF'
// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "Demo",
    products: [.library(name: "Demo", targets: ["Demo"])],
    targets: [.target(name: "Demo")],
    swiftLanguageModes: [.v5]
)
EOF
cat > demo/package-metadata.json <<'EOF'
{
  "description": "A demo package",
  "author": { "name": "Jane Doe" },
  "licenseURL": "https://example.com/acme/demo/LICENSE",
  "repositoryURLs": ["https://github.com/acme/demo", "git@github.com:acme/demo.git"]
}
EOF
library other Other "hello from acme.other through the proxy"
library signed Signed "hello from a signed release"

# a throw-away root and an ECDSA P-256 code-signing leaf, DER-encoded as SwiftPM wants them
mkdir -p signing
(
  cd signing
  openssl ecparam -name prime256v1 -genkey -noout -out root.key &&
  openssl req -x509 -new -key root.key -sha256 -days 30 -subj "/CN=Granary Verify Root/O=Acme" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out root.pem &&
  openssl ecparam -name prime256v1 -genkey -noout -out leaf.key &&
  openssl req -new -key leaf.key -subj "/CN=Acme Release Signing/O=Acme" -out leaf.csr &&
  printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=critical,codeSigning\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid\n' > leaf.ext &&
  openssl x509 -req -in leaf.csr -CA root.pem -CAkey root.key -CAcreateserial -days 30 -sha256 -extfile leaf.ext -out leaf.pem &&
  openssl x509 -in leaf.pem -outform DER -out leaf.der &&
  openssl x509 -in root.pem -outform DER -out root.der &&
  openssl pkcs8 -topk8 -nocrypt -in leaf.key -outform DER -out leaf.p8.der &&
  mkdir -p trusted && cp root.der trusted/granary-verify-root.cer
) >"$WORK/signing.log" 2>&1 || die "openssl could not create the signing certificates (see $WORK/signing.log)"

echo "== Login and publish (HTTPS)"
settings "{\"baseUrl\":\"$TLS\"}"
STEP="swift package-registry login --token"
expect_output "Login successful" swift_in demo swift package-registry login "$TLS/repository/swift-hosted" --token "$APITOKEN" --no-confirm
grep -q "\"localhost:$TLS_PORT\"" home/.swiftpm/configuration/registries.json 2>/dev/null \
  && ok "login stored the authentication entry for localhost:$TLS_PORT" || bad "authentication entry after login"
STEP="swift package-registry publish acme.demo 1.0.0 to swift-hosted"
expect_output "successfully published" swift_in demo swift package-registry publish acme.demo 1.0.0 --url "$TLS/repository/swift-hosted"
STEP="swift package-registry publish acme.other 1.0.0 to swift-origin (the proxied registry)"
expect_output "successfully published" swift_in other swift package-registry publish acme.other 1.0.0 --url "$TLS/repository/swift-origin"
STEP="publishing acme.demo 1.0.0 again is refused (409)"
expect_output "409|already exists" swift_in demo swift package-registry publish acme.demo 1.0.0 --url "$TLS/repository/swift-hosted"
STEP="a wrong token is answered with 401"
CODE=$(curl -s -o /dev/null -w '%{http_code}' --cacert cert.pem -X POST -H "Authorization: Bearer gry_wrong" "$TLS/repository/swift-hosted")
[ "$CODE" = 401 ] && ok "$STEP" || bad "$STEP (got $CODE)"
STEP="swift package-registry login with a wrong token"
swift_in demo timeout 60 swift package-registry login "$TLS/repository/swift-hosted" --token gry_wrong --no-confirm >"$WORK/last.log" 2>&1
case $? in
  0) bad "$STEP succeeded" ;;
  124) known "$STEP: SwiftPM on Linux waits forever on the Basic challenge of the server's credential check (stopped after 60 s)" ;;
  *) ok "$STEP fails" ;;
esac
STEP="swift package-registry publish acme.signed 1.0.0, signed with a code-signing certificate"
expect_output "successfully published" swift_in signed swift package-registry publish acme.signed 1.0.0 --url "$TLS/repository/swift-hosted" \
  --private-key-path /w/signing/leaf.p8.der --cert-chain-paths /w/signing/leaf.der /w/signing/root.der
settings "{\"baseUrl\":$(jq -Rn --arg u "$ORIGINAL_BASE_URL" '$u')}"

STEP="the signature is in the release metadata and on the archive"
SIGNED=$(curl -sS -H 'Accept: application/vnd.swift.registry.v1+json' "$GRANARY/repository/swift-group/acme/signed/1.0.0")
SIGNED_HEADERS=$(curl -sS -D - -o /dev/null "$GRANARY/repository/swift-group/acme/signed/1.0.0.zip")
[ "$(echo "$SIGNED" | jq -r '.resources[0].signing.signatureFormat')" = cms-1.0.0 ] \
  && echo "$SIGNED_HEADERS" | grep -qi '^X-Swift-Package-Signature-Format: cms-1.0.0' \
  && [ "$(echo "$SIGNED_HEADERS" | grep -i '^X-Swift-Package-Signature:' | cut -d' ' -f2 | tr -d '\r')" = \
       "$(echo "$SIGNED" | jq -r '.resources[0].signing.signatureBase64Encoded')" ] && ok "$STEP" || bad "$STEP"

STEP="release metadata names the checksum of the archive"
RELEASE=$(curl -sS -H 'Accept: application/vnd.swift.registry.v1+json' "$GRANARY/repository/swift-group/acme/demo/1.0.0")
SUM=$(curl -sS "$GRANARY/repository/swift-group/acme/demo/1.0.0.zip" | sha256sum | cut -d' ' -f1)
[ "$(echo "$RELEASE" | jq -r '.resources[0].checksum')" = "$SUM" ] && ok "$STEP" || bad "$STEP"
STEP="Package.swift lists the Swift 6 variant"
curl -sS -D - -o /dev/null "$GRANARY/repository/swift-group/acme/demo/1.0.0/Package.swift" | grep -qi 'filename="Package@swift-6.swift"' \
  && ok "$STEP" || bad "$STEP"

echo "== Resolve and build through swift-group (HTTP)"
mkdir -p app/Sources/App
cat > app/Package.swift <<'EOF'
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "App",
    dependencies: [
        .package(id: "acme.demo", from: "1.0.0"),
        .package(id: "acme.other", from: "1.0.0"),
    ],
    targets: [
        .executableTarget(name: "App", dependencies: [
            .product(name: "Demo", package: "acme.demo"),
            .product(name: "Other", package: "acme.other"),
        ]),
    ]
)
EOF
cat > app/Sources/App/main.swift <<'EOF'
import Demo
import Other

print(Demo.greeting())
print(Other.greeting())
EOF
STEP="swift package-registry set swift-group --global --allow-insecure-http"
check swift_in app swift package-registry set "$GRANARY/repository/swift-group" --global --allow-insecure-http
STEP="swift package resolve (acme.demo from hosted, acme.other through the proxy)"
check swift_in app swift package resolve
STEP="swift build && swift run"
expect_output "hello from acme.other through the proxy" swift_in app 'swift build && swift run App'
grep -q "hello from acme.demo" "$WORK/last.log" && ok "the app prints the hosted package's greeting" || bad "hosted greeting"

mkdir -p scm/Sources/Scm
cat > scm/Package.swift <<'EOF'
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Scm",
    dependencies: [
        .package(url: "https://github.com/acme/demo", from: "1.0.0"),
    ],
    targets: [
        .executableTarget(name: "Scm", dependencies: [
            .product(name: "Demo", package: "acme.demo"),
        ]),
    ]
)
EOF
printf 'import Demo\n\nprint(Demo.greeting())\n' > scm/Sources/Scm/main.swift
STEP="swift package --replace-scm-with-registry resolve (identifiers?url= lookup)"
check swift_in scm swift package --replace-scm-with-registry resolve
STEP="swift build --replace-scm-with-registry && swift run"
expect_output "hello from acme.demo" swift_in scm 'swift build --replace-scm-with-registry && swift run --replace-scm-with-registry Scm'

# a consumer of the signed release whose registries.json turns every signature check into an error
signed_consumer() { # signed_consumer <trusted roots directory inside the container, or empty>
  local trusted=""
  [ -n "$1" ] && trusted="\"trustedRootCertificatesPath\": \"$1\","
  rm -rf signedapp && mkdir -p signedapp/Sources/App signedapp/.swiftpm/configuration
  cat > signedapp/Package.swift <<'EOF'
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "App",
    dependencies: [.package(id: "acme.signed", from: "1.0.0")],
    targets: [.executableTarget(name: "App", dependencies: [.product(name: "Signed", package: "acme.signed")])]
)
EOF
  printf 'import Signed\n\nprint(Signed.greeting())\n' > signedapp/Sources/App/main.swift
  cat > signedapp/.swiftpm/configuration/registries.json <<JSON
{
  "registries": { "[default]": { "supportsAvailability": false, "url": "$GRANARY/repository/swift-group" } },
  "security": { "default": { "signing": {
    "onUnsigned": "error", "onUntrustedCertificate": "error", $trusted
    "includeDefaultTrustedRootCertificates": false,
    "validationChecks": { "certificateExpiration": "enabled", "certificateRevocation": "disabled" } } } },
  "version": 1
}
JSON
  rm -rf "$WORK/home/.swiftpm/security" "$WORK/home/.swiftpm/cache" "$WORK/home/.cache/org.swift.swiftpm"
}
STEP="the signed release is refused while its root certificate is not trusted"
signed_consumer ""
expect_output "is not trusted" swift_in signedapp swift package resolve
STEP="the signed release resolves, builds and runs once its root is trusted (signature verified by SwiftPM)"
signed_consumer /w/signing/trusted
expect_output "hello from a signed release" swift_in signedapp 'swift package resolve && swift build && swift run App'
rm -rf signedapp/.build

STEP="an anonymous publish over HTTP fails with 401 instead of hanging"
mkdir -p anon && cp -r demo/Package.swift demo/Sources anon/
expect_output "401" timeout 300 docker run --rm --network host -v "$WORK/anon:/w" -v "$ZIP:/usr/bin/zip:ro" -w /w "$IMAGE" \
  timeout 120 swift package-registry publish acme.anon 1.0.0 --url "$GRANARY/repository/swift-hosted" --allow-insecure-http

echo "== Anonymous read off (credentials over HTTP)"
# login refuses HTTP URLs, so the authentication entry (host:port) and the netrc machine (host) are written by hand
HOSTPORT=${GRANARY#http://}; HOSTPORT=${HOSTPORT%%/*}; HOST=${HOSTPORT%%:*}
for h in authhome noauthhome; do mkdir -p "$WORK/$h/.swiftpm/configuration"; done
cat > authhome/.swiftpm/configuration/registries.json <<JSON
{
  "authentication": { "$HOSTPORT": { "type": "token" } },
  "registries": { "[default]": { "supportsAvailability": false, "url": "$GRANARY/repository/swift-group" } },
  "version": 1
}
JSON
printf 'machine %s\n  login token\n  password %s\n' "$HOST" "$APITOKEN" > authhome/.netrc
cat > noauthhome/.swiftpm/configuration/registries.json <<JSON
{
  "registries": { "[default]": { "supportsAvailability": false, "url": "$GRANARY/repository/swift-group" } },
  "version": 1
}
JSON
settings '{"anonymousRead":false}' && ok "anonymous read off" || bad "anonymous read off"
rm -rf "$WORK/app/.build"
STEP="swift package resolve with a token from ~/.netrc over HTTP"
check docker run --rm --network host -v "$WORK/authhome:/root" -v "$WORK:/w" -w /w/app "$IMAGE" swift package resolve
rm -rf "$WORK/app/.build"
STEP="swift package resolve without credentials fails with 401 instead of hanging"
expect_output "401" timeout 300 docker run --rm --network host -v "$WORK/noauthhome:/root" -v "$WORK:/w" -w /w/app "$IMAGE" \
  timeout 120 swift package resolve
settings "{\"anonymousRead\":$ORIGINAL_ANONYMOUS_READ}" && ok "anonymous read restored" || bad "anonymous read restored"

echo "== Offline"
downloads() { api "$GRANARY/api/v1/repositories/$1/assets?prefix=$2" | jq -r --arg p "$2" '[.items[] | select(.path == $p) | .downloadCount][0] // 0'; }
BEFORE=$(downloads swift-proxy acme/other/1.0.0.zip)
settings '{"offlineMode":true}' && ok "offline mode on" || bad "offline mode on"
rm -rf "$WORK/app/.build" "$WORK/scm/.build" "$WORK/home/.cache" "$WORK/home/.swiftpm/cache"
STEP="swift package resolve again from Granary's cache (offline, client caches wiped)"
check swift_in app swift package resolve
STEP="swift build && swift run (offline)"
expect_output "hello from acme.other through the proxy" swift_in app 'swift build && swift run App'
AFTER=$(downloads swift-proxy acme/other/1.0.0.zip)
[ "$AFTER" -gt "$BEFORE" ] && ok "the proxied archive came from Granary's cache (downloads $BEFORE -> $AFTER)" \
  || bad "proxied archive download count ($BEFORE -> $AFTER)"
STEP="an uncached release through the proxy is a 503 while offline"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H 'Accept: application/vnd.swift.registry.v1+json' "$GRANARY/repository/swift-group/acme/other/9.9.9.zip")
[ "$CODE" = 503 ] && ok "$STEP" || bad "$STEP (got $CODE)"
settings '{"offlineMode":false}' && ok "offline mode off" || bad "offline mode off"

echo
echo "passed: $PASS, failed: $FAIL, known client limitations: $KNOWN"
[ "$FAIL" -eq 0 ]
