#!/usr/bin/env bash
# End-to-end verification of the Terraform format with the real terraform and tofu CLIs, including an offline run.
#
# Usage: verify-terraform.sh http://localhost:8109      (a running Granary; admin/admin123 must be valid)
#
# Both CLIs only talk to registries over HTTPS. Given a plain-HTTP Granary URL the script makes the run
# self-contained:
#   * it generates a self-signed certificate for 127.0.0.1 and starts tls-forwarder.py on port 443 in front of
#     Granary (443 keeps the registry host free of a port, which the network mirror protocol and TF_TOKEN_<host>
#     need; a module registry host must contain a dot, which the IP address does),
#   * it points Granary's base URL setting at https://127.0.0.1 for the run and restores it afterwards,
#   * it downloads terraform and tofu when TERRAFORM_BIN / TOFU_BIN are not set and they are not on the PATH,
#   * when registry.terraform.io is unreachable (and TF_UPSTREAM_URL is not set) it starts stand-in-registry.py,
#     a minimal registry for hashicorp/null whose download URLs still point at releases.hashicorp.com, so
#     Granary caches the real provider files and the CLIs verify HashiCorp's real signature.
#     The proxies terraform-registry and opentofu-registry are pointed at it for the run and restored afterwards
#     (to their previous settings, or to the public registries when the run created them).
# Given an https:// URL the TLS front is yours: Granary's base URL must already name it and GRANARY_CA may name
# the CA the CLIs and curl should trust.
#
# Environment:
#   TERRAFORM_BIN / TOFU_BIN   CLI binaries (default: on the PATH, else downloaded into TF_VERIFY_CACHE)
#   TF_VERSION / TOFU_VERSION  versions to download (default 1.13.4 / 1.10.7)
#   TF_VERIFY_CACHE            download cache (default ~/.cache/granary-verify-terraform)
#   TF_UPSTREAM_URL            registry the proxies point at instead of registry.terraform.io / registry.opentofu.org
#   TF_VERIFY_NAMESPACE        namespace of the uploaded provider and module (default acme-<epoch seconds>)
#   TF_TLS_PORT                port of the TLS front for a plain-HTTP Granary (default 443; anything else skips
#                              the network-mirror steps because the mirror protocol cannot carry a port)
#   CURL_CA_BUNDLE             CA bundle for downloads through an intercepting proxy
#   GRANARY_CA                 CA of an existing TLS front (https:// URL only)
# Needs curl, jq, python3, openssl, unzip, tar and gzip.
set -uo pipefail
GRANARY=${1:-http://localhost:8109}; GRANARY=${GRANARY%/}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROVIDER=hashicorp/null; PROVIDER_VERSION=3.2.4
PROVIDER_NS=${PROVIDER%%/*}; PROVIDER_TYPE=${PROVIDER#*/}
# namespace of the hosted provider and module: new on every run, so the run can be repeated on the same server
NS=${TF_VERIFY_NAMESPACE:-acme-$(date +%s)}
PLATFORM=linux_amd64
TF_VERSION=${TF_VERSION:-1.13.4}; TOFU_VERSION=${TOFU_VERSION:-1.10.7}
CACHE=${TF_VERIFY_CACHE:-$HOME/.cache/granary-verify-terraform}
WORK=$(mktemp -d /tmp/granary-verify-terraform.XXXX)
cd "$WORK"
PASS=0; FAIL=0; SKIP=0; KNOWN=0
ok()    { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()   { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
skip()  { echo "SKIP  $1"; SKIP=$((SKIP+1)); }
known() { echo "KNOWN $1"; KNOWN=$((KNOWN+1)); }
check() { if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -25 "$WORK/last.log"; fi; }
die()   { echo "ERROR $1" >&2; exit 2; }
DL=(curl -fsSL --retry 2); [ -n "${CURL_CA_BUNDLE:-}" ] && DL+=(--cacert "$CURL_CA_BUNDLE")
for tool in curl jq python3 openssl unzip tar gzip; do command -v $tool >/dev/null || die "$tool is required"; done

# --- admin session on the Granary itself (plain HTTP or the given TLS URL) ------------------------------------------
G=(curl -sS); [ -n "${GRANARY_CA:-}" ] && G+=(--cacert "$GRANARY_CA")
TOKEN=""
for _ in $(seq 1 30); do  # the admin user appears a moment after the port opens on a fresh data directory
  TOKEN=$("${G[@]}" -f -X POST "$GRANARY/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' 2>/dev/null | jq -r .token 2>/dev/null)
  [ -n "$TOKEN" ] && [ "$TOKEN" != null ] && break; sleep 1
done
[ -n "$TOKEN" ] && [ "$TOKEN" != null ] || die "cannot log in to $GRANARY as admin"
AUTH=(-H "Authorization: Bearer $TOKEN")
settings() { "${G[@]}" -f -X PUT "$GRANARY/api/v1/system/settings" "${AUTH[@]}" -H 'Content-Type: application/json' -d "$1" >/dev/null; }
ORIGINAL_BASE_URL=$("${G[@]}" -f "$GRANARY/api/v1/system/settings" "${AUTH[@]}" | jq -r '.baseUrl // ""')

PIDS=()
REPOINTED=()  # proxies pointed at TF_UPSTREAM_URL; their previous settings are in $WORK/<name>.before.json
restore_proxies() {
  local name body
  for name in "${REPOINTED[@]}"; do
    if [ -s "$WORK/$name.before.json" ]; then  # the fields of the update request, as they were before the run
      body=$(jq -c '{name, format, type, online, blobStore, description, allowRedeploy, versionPolicy, remoteUrl,
                     connectTimeoutSeconds, readTimeoutSeconds, metadataMaxAgeMinutes, negativeCacheMinutes,
                     offlineMode, members, formatSettings} | with_entries(select(.value != null))' "$WORK/$name.before.json")
    else  # created by this run: point it at the public registry like the standard repository
      local url=https://registry.terraform.io/; [ "$name" = opentofu-registry ] && url=https://registry.opentofu.org/
      body="{\"name\":\"$name\",\"format\":\"terraform\",\"type\":\"PROXY\",\"remoteUrl\":\"$url\"}"
    fi
    "${G[@]}" -f -X PUT "$GRANARY/api/v1/repositories/$name" "${AUTH[@]}" -H 'Content-Type: application/json' -d "$body" >/dev/null \
      && "${G[@]}" -f -X POST "$GRANARY/api/v1/repositories/$name/invalidate" "${AUTH[@]}" >/dev/null \
      || echo "WARN  could not restore the proxy $name; check its remote URL" >&2
  done
}
cleanup() {
  restore_proxies
  settings "{\"offlineMode\":false,\"anonymousRead\":true,\"baseUrl\":$(jq -Rn --arg u "$ORIGINAL_BASE_URL" '$u')}" || true
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null; done
}
trap cleanup EXIT

# --- TLS front ---------------------------------------------------------------------------------------------------------
port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
MIRROR_OK=1
if [[ "$GRANARY" == http://* ]]; then
  TLS_PORT=${TF_TLS_PORT:-443}
  if [ "$TLS_PORT" = 443 ] && { [ "$(id -u)" != 0 ] || ! port_free 443; }; then TLS_PORT=8443; fi
  port_free "$TLS_PORT" || die "port $TLS_PORT is taken; set TF_TLS_PORT"
  openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 2 -subj /CN=127.0.0.1 \
    -addext "subjectAltName=IP:127.0.0.1,DNS:localhost" >/dev/null 2>&1 || die "openssl could not create a certificate"
  TARGET=${GRANARY#http://}; TARGET=${TARGET%%/*}
  python3 "$HERE/tls-forwarder.py" --cert cert.pem --key key.pem --listen "$TLS_PORT" --target "$TARGET" >forwarder.log 2>&1 &
  PIDS+=($!)
  HOSTPORT=127.0.0.1; [ "$TLS_PORT" = 443 ] || { HOSTPORT=127.0.0.1:$TLS_PORT; MIRROR_OK=0; }
  BASE=https://$HOSTPORT
  export SSL_CERT_FILE="$WORK/cert.pem"
  settings "{\"baseUrl\":\"$BASE\"}" || die "cannot set the base URL"
  for _ in $(seq 1 20); do curl -fsS --cacert cert.pem -o /dev/null "$BASE/api/v1/system/info" 2>/dev/null && break; sleep 0.5; done
else
  BASE=$GRANARY; HOSTPORT=${BASE#https://}; HOSTPORT=${HOSTPORT%%/*}
  [ -n "${GRANARY_CA:-}" ] && export SSL_CERT_FILE="$GRANARY_CA"
  [[ "$HOSTPORT" == *:* ]] && MIRROR_OK=0
fi
CURL=(curl -sS); [ -n "${SSL_CERT_FILE:-}" ] && CURL+=(--cacert "$SSL_CERT_FILE")
api() { "${CURL[@]}" -f "$@"; }
code() { "${CURL[@]}" -o /dev/null -w "%{http_code}" "$@"; }
expect_code() { local want=$1; shift; local got; got=$(code "$@"); [ "$got" = "$want" ] && ok "$STEP ($got)" || bad "$STEP (expected $want, got $got)"; }
# the CLIs are Go programs: keep the TLS front out of any HTTPS proxy configured in the environment
export NO_PROXY="${NO_PROXY:+$NO_PROXY,}127.0.0.1,${HOSTPORT%%:*}" no_proxy="${no_proxy:+$no_proxy,}127.0.0.1,${HOSTPORT%%:*}"
echo "== Granary $GRANARY behind $BASE"
STEP="TLS front answers"; expect_code 200 "$BASE/api/v1/system/info"

# --- upstream: the public registries, or a stand-in where they are unreachable ------------------------------------------
if [ -z "${TF_UPSTREAM_URL:-}" ] && ! "${DL[@]}" -o /dev/null --max-time 20 https://registry.terraform.io/.well-known/terraform.json 2>/dev/null; then
  echo "registry.terraform.io is unreachable from here: using stand-in-registry.py for $PROVIDER $PROVIDER_VERSION"
  "${DL[@]}" -o sums "https://releases.hashicorp.com/terraform-provider-$PROVIDER_TYPE/$PROVIDER_VERSION/terraform-provider-${PROVIDER_TYPE}_${PROVIDER_VERSION}_SHA256SUMS" \
    && "${DL[@]}" -o hashicorp.asc https://www.hashicorp.com/.well-known/pgp-key.txt \
    || die "cannot download the provider's SHA256SUMS or HashiCorp's public key"
  STANDIN_PORT=8990; while ! port_free $STANDIN_PORT; do STANDIN_PORT=$((STANDIN_PORT+1)); done
  python3 "$HERE/stand-in-registry.py" --port $STANDIN_PORT --provider $PROVIDER --version $PROVIDER_VERSION \
    --sha256sums sums --signing-key hashicorp.asc >standin.log 2>&1 &
  PIDS+=($!)
  for _ in $(seq 1 20); do curl -fsS -o /dev/null "http://127.0.0.1:$STANDIN_PORT/.well-known/terraform.json" 2>/dev/null && break; sleep 0.5; done
  TF_UPSTREAM_URL=http://127.0.0.1:$STANDIN_PORT/
fi

echo "== API"
if [ -n "${TF_UPSTREAM_URL:-}" ]; then
  for name in terraform-registry opentofu-registry; do
    body="{\"name\":\"$name\",\"format\":\"terraform\",\"type\":\"PROXY\",\"remoteUrl\":\"$TF_UPSTREAM_URL\"}"
    api "$BASE/api/v1/repositories/$name" "${AUTH[@]}" >"$WORK/$name.before.json" 2>/dev/null || : >"$WORK/$name.before.json"
    REPOINTED+=("$name")
    # create it, or repoint an existing one (a second run on the same data directory) and drop its cache
    api -X POST "$BASE/api/v1/repositories" "${AUTH[@]}" -H 'Content-Type: application/json' -d "$body" >/dev/null 2>&1 \
      || { api -X PUT "$BASE/api/v1/repositories/$name" "${AUTH[@]}" -H 'Content-Type: application/json' -d "$body" >/dev/null \
           && api -X POST "$BASE/api/v1/repositories/$name/invalidate" "${AUTH[@]}" >/dev/null; }
    STEP="proxy $name -> $TF_UPSTREAM_URL"
    [ "$(api "$BASE/api/v1/repositories/$name" "${AUTH[@]}" | jq -r .remoteUrl)" = "$TF_UPSTREAM_URL" ] && ok "$STEP" || bad "$STEP"
  done
fi
api -X POST "$BASE/api/v1/repositories/standard" "${AUTH[@]}" >/dev/null && ok "standard repositories" || bad "standard repositories"
APITOKEN=$(api -X POST "$BASE/api/v1/tokens" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"name":"verify-terraform"}' | jq -r .secret)
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
STEP="root discovery document points at terraform-group"
api "$BASE/.well-known/terraform.json" | jq -e --arg u "$BASE/repository/terraform-group/v1/providers/" '."providers.v1" == $u' >/dev/null && ok "$STEP" || bad "$STEP"
GROUP="$BASE/repository/terraform-group"; HOSTED="$BASE/repository/terraform-hosted"

# --- CLI binaries ---------------------------------------------------------------------------------------------------------
fetch_cli() { # <name> <url> -> path of the binary
  local dir="$CACHE/$1"
  if [ ! -x "$dir/$1" ]; then
    mkdir -p "$dir" && "${DL[@]}" -o "$dir/$1.zip" "$2" && unzip -o -q "$dir/$1.zip" -d "$dir" && rm -f "$dir/$1.zip" || return 1
  fi
  echo "$dir/$1"
}
TERRAFORM_BIN=${TERRAFORM_BIN:-$(command -v terraform || fetch_cli terraform "https://releases.hashicorp.com/terraform/$TF_VERSION/terraform_${TF_VERSION}_linux_amd64.zip" || true)}
TOFU_BIN=${TOFU_BIN:-$(command -v tofu || fetch_cli tofu "https://github.com/opentofu/opentofu/releases/download/v$TOFU_VERSION/tofu_${TOFU_VERSION}_linux_amd64.zip" || true)}
CLIS=()
if [ -n "$TERRAFORM_BIN" ] && [ -x "$TERRAFORM_BIN" ]; then CLIS+=(terraform); echo "terraform: $("$TERRAFORM_BIN" version | head -1)"; else bad "terraform binary (TERRAFORM_BIN, PATH or download)"; fi
if [ -n "$TOFU_BIN" ] && [ -x "$TOFU_BIN" ]; then CLIS+=(tofu); echo "tofu: $("$TOFU_BIN" version | head -1)"; else bad "tofu binary (TOFU_BIN, PATH or download)"; fi
export CHECKPOINT_DISABLE=1 TF_IN_AUTOMATION=1 TF_INPUT=0
unset TF_PLUGIN_CACHE_DIR
bin_of() { if [ "$1" = terraform ]; then echo "$TERRAFORM_BIN"; else echo "$TOFU_BIN"; fi; }
# cli <name> <project-dir> <args...>: runs the CLI with an isolated HOME and the project's own CLI configuration
cli() {
  local name=$1 dir=$2; shift 2
  local cfg="$dir/cli.tfrc"; [ -f "$cfg" ] || : > "$cfg"
  mkdir -p "$WORK/$name/home"
  (cd "$dir" && env HOME="$WORK/$name/home" TF_CLI_CONFIG_FILE="$cfg" TOFU_CLI_CONFIG_FILE="$cfg" "$(bin_of "$name")" "$@")
}
# the network mirror is asked for the provider under a hostname the proxy answers for: the public registry's own
# (implicit in "hashicorp/null"), or Granary's host when the proxy points at the stand-in
if [ -n "${TF_UPSTREAM_URL:-}" ]; then MIRROR_SOURCE="$HOSTPORT/$PROVIDER"; else MIRROR_SOURCE="$PROVIDER"; fi
lock_address() { # how the lock file names the mirrored provider
  if [[ "$MIRROR_SOURCE" == */*/* ]]; then echo "$MIRROR_SOURCE"
  elif [ "$1" = tofu ]; then echo "registry.opentofu.org/$MIRROR_SOURCE"
  else echo "registry.terraform.io/$MIRROR_SOURCE"; fi
}
mirror_url() { if [ "$1" = tofu ] && [ -z "${TF_UPSTREAM_URL:-}" ]; then echo "$BASE/repository/opentofu-registry/mirror/"; else echo "$GROUP/mirror/"; fi; }
provider_project() { # <dir> <source>
  mkdir -p "$1"
  cat > "$1/main.tf" <<HCL
terraform {
  required_providers {
    ${PROVIDER_TYPE} = {
      source  = "$2"
      version = "= ${PROVIDER_VERSION}"
    }
  }
}

resource "${PROVIDER_TYPE}_resource" "verify" {}
HCL
}
module_project() { # <dir> <source> <version>
  mkdir -p "$1"
  cat > "$1/main.tf" <<HCL
module "x" {
  source  = "$2"
  version = "$3"
}

output "greeting" {
  value = module.x.greeting
}
HCL
}
clean() { rm -rf "${1:?}/.terraform" "${1:?}/.terraform.lock.hcl"; }
PROJECTS=(group-provider hosted-provider hosted-module)
[ "$MIRROR_OK" = 1 ] && PROJECTS+=(network-mirror)
[ -n "${TF_UPSTREAM_URL:-}" ] && PROJECTS+=(proxied-module)

# --- providers and modules through the proxy -------------------------------------------------------------------------------
for c in "${CLIS[@]}"; do
  echo "== $c: providers through terraform-group (proxy member)"
  P="$WORK/$c/group-provider"; provider_project "$P" "$HOSTPORT/$PROVIDER"
  STEP="$c init $PROVIDER $PROVIDER_VERSION from $HOSTPORT via group"; check cli "$c" "$P" init -no-color
  STEP="$c lock file records the provider from $HOSTPORT"; grep -q "provider \"$HOSTPORT/$PROVIDER\"" "$P/.terraform.lock.hcl" 2>/dev/null && ok "$STEP" || bad "$STEP"
  STEP="$c plan with the installed provider"; check cli "$c" "$P" plan -no-color
  STEP="$c providers mirror (the command that seeds a hosted repository)"; check cli "$c" "$P" providers mirror -platform=$PLATFORM "$WORK/$c/mirror-dir"
  STEP="$c mirror directory holds index.json, $PROVIDER_VERSION.json and the zip"
  [ -f "$WORK/$c/mirror-dir/$HOSTPORT/$PROVIDER/index.json" ] && [ -f "$WORK/$c/mirror-dir/$HOSTPORT/$PROVIDER/$PROVIDER_VERSION.json" ] \
    && ls "$WORK/$c/mirror-dir/$HOSTPORT/$PROVIDER/"*.zip >/dev/null 2>&1 && ok "$STEP" || bad "$STEP"

  echo "== $c: provider network mirror"
  if [ "$MIRROR_OK" = 1 ]; then
    M="$WORK/$c/network-mirror"; provider_project "$M" "$MIRROR_SOURCE"
    printf 'provider_installation {\n  network_mirror {\n    url = "%s"\n  }\n}\n' "$(mirror_url "$c")" > "$M/cli.tfrc"
    STEP="$c init through network_mirror $(mirror_url "$c") for $MIRROR_SOURCE"; check cli "$c" "$M" init -no-color
    STEP="$c lock file records $(lock_address "$c") with hashes"
    grep -q "provider \"$(lock_address "$c")\"" "$M/.terraform.lock.hcl" 2>/dev/null && grep -qE '"(h1|zh):' "$M/.terraform.lock.hcl" \
      && ok "$STEP" || bad "$STEP"
  else
    skip "$c network mirror: the registry host $HOSTPORT carries a port, which the mirror protocol cannot express (use TF_TLS_PORT=443 as root)"
  fi

  if [ -n "${TF_UPSTREAM_URL:-}" ]; then
    echo "== $c: module through terraform-group (proxy member, archive source cached by Granary)"
    Q="$WORK/$c/proxied-module"; module_project "$Q" "$HOSTPORT/$PROVIDER_NS/demo/aws" "1.0.0"
    STEP="$c init module from the upstream registry via group"; check cli "$c" "$Q" init -no-color
    STEP="$c proxied module files were unpacked"; [ -f "$Q/.terraform/modules/x/main.tf" ] && ok "$STEP" || bad "$STEP"
  fi
done
if [ -n "${TF_UPSTREAM_URL:-}" ]; then
  STEP="the proxied module's X-Terraform-Get points at Granary's cached archive"
  "${CURL[@]}" -D headers.txt -o /dev/null "$GROUP/v1/modules/$PROVIDER_NS/demo/aws/1.0.0/download" >/dev/null 2>&1
  grep -qi "^X-Terraform-Get: $GROUP/files/modules/$PROVIDER_NS/demo/aws/1.0.0.tgz" headers.txt && ok "$STEP" || { bad "$STEP"; cat headers.txt; }
fi

# --- hosted provider: re-upload what the proxy cached, signed by the real key ---------------------------------------------
echo "== hosted provider upload ($NS/$PROVIDER_TYPE $PROVIDER_VERSION, the files the proxy just cached)"
mkdir -p hosted && cd hosted
ZIP="terraform-provider-${PROVIDER_TYPE}_${PROVIDER_VERSION}_${PLATFORM}.zip"
SUMS="terraform-provider-${PROVIDER_TYPE}_${PROVIDER_VERSION}_SHA256SUMS"
FILES="$GROUP/files/$PROVIDER_NS/$PROVIDER_TYPE/$PROVIDER_VERSION"
STEP="download cached zip, SHA256SUMS and signature from the proxy"
check bash -c "$(printf '%q ' "${CURL[@]}") -f -o '$ZIP' '$FILES/$ZIP' && $(printf '%q ' "${CURL[@]}") -f -o SHA256SUMS '$FILES/$SUMS' && $(printf '%q ' "${CURL[@]}") -f -o SHA256SUMS.sig '$FILES/$SUMS.sig'"
STEP="signing key taken from the proxied download document"
api "$GROUP/v1/providers/$PROVIDER_NS/$PROVIDER_TYPE/$PROVIDER_VERSION/download/${PLATFORM%_*}/${PLATFORM#*_}" \
  | jq '.signing_keys.gpg_public_keys[0] | {key_id, ascii_armor}' > gpg-key.json && jq -e .key_id gpg-key.json >/dev/null && ok "$STEP" || bad "$STEP"
HP="$HOSTED/v1/providers/$NS/$PROVIDER_TYPE"
STEP="PUT provider zip"; expect_code 201 -u "admin:$APITOKEN" -X PUT --data-binary "@$ZIP" -H 'Content-Type: application/zip' "$HP/$PROVIDER_VERSION/${PLATFORM%_*}/${PLATFORM#*_}/$ZIP"
STEP="PUT SHA256SUMS"; expect_code 201 -u "admin:$APITOKEN" -X PUT --data-binary @SHA256SUMS "$HP/$PROVIDER_VERSION/SHA256SUMS"
STEP="PUT SHA256SUMS.sig"; expect_code 201 -u "admin:$APITOKEN" -X PUT --data-binary @SHA256SUMS.sig "$HP/$PROVIDER_VERSION/SHA256SUMS.sig"
STEP="PUT gpg-key"; expect_code 201 -u "admin:$APITOKEN" -X PUT --data-binary @gpg-key.json -H 'Content-Type: application/json' "$HP/gpg-key"
STEP="re-uploading the same zip is a 409"; expect_code 409 -u "admin:$APITOKEN" -X PUT --data-binary "@$ZIP" "$HP/$PROVIDER_VERSION/${PLATFORM%_*}/${PLATFORM#*_}/$ZIP"
STEP="re-uploading it as v$PROVIDER_VERSION is a 409 too"; expect_code 409 -u "admin:$APITOKEN" -X PUT --data-binary "@$ZIP" "$HP/v$PROVIDER_VERSION/${PLATFORM%_*}/${PLATFORM#*_}/$ZIP"
STEP="anonymous upload is a 401"; expect_code 401 -X PUT --data-binary "@$ZIP" "$HP/$PROVIDER_VERSION/${PLATFORM%_*}/${PLATFORM#*_}/$ZIP"
STEP="upload with a wrong token is a 401"; expect_code 401 -u "admin:gry_wrong" -X PUT --data-binary "@$ZIP" "$HP/$PROVIDER_VERSION/${PLATFORM%_*}/${PLATFORM#*_}/$ZIP"
STEP="uploads to the group are refused (405)"; expect_code 405 -u "admin:$APITOKEN" -X PUT --data-binary "@$ZIP" "$GROUP/v1/providers/$NS/$PROVIDER_TYPE/$PROVIDER_VERSION/${PLATFORM%_*}/${PLATFORM#*_}/$ZIP"
STEP="a traversal attempt is a 400"; expect_code 400 --path-as-is -u "admin:$APITOKEN" -X PUT --data-binary "@$ZIP" "$HP/$PROVIDER_VERSION/linux/amd64/..%2F..%2Fevil.zip"
STEP="generated versions document lists $PROVIDER_VERSION for $PLATFORM"
api "$GROUP/v1/providers/$NS/$PROVIDER_TYPE/versions" | jq -e --arg v "$PROVIDER_VERSION" '.versions[] | select(.version == $v) | .platforms[] | select(.os == "linux" and .arch == "amd64")' >/dev/null && ok "$STEP" || bad "$STEP"
STEP="generated download document carries the SHA256SUMS digest and the key"
api "$GROUP/v1/providers/$NS/$PROVIDER_TYPE/$PROVIDER_VERSION/download/linux/amd64" \
  | jq -e --arg s "$(sha256sum "$ZIP" | cut -c1-64)" '.shasum == $s and (.signing_keys.gpg_public_keys | length) == 1 and (.download_url | startswith("https://"))' >/dev/null && ok "$STEP" || bad "$STEP"
STEP="HEAD and If-None-Match on the archive"
ETAG=$("${CURL[@]}" -fI "$FILES/$ZIP" | tr -d '\r' | awk -F': ' 'tolower($1)=="etag" {print $2}')
[ -n "$ETAG" ] && [ "$(code -H "If-None-Match: $ETAG" "$FILES/$ZIP")" = 304 ] && ok "$STEP" || bad "$STEP"

echo "== hosted module upload ($NS/demo/local 1.0.0)"
mkdir -p module-src && cat > module-src/main.tf <<'HCL'
variable "name" {
  type    = string
  default = "world"
}

output "greeting" {
  value = "hello ${var.name} from the hosted demo module"
}
HCL
tar -C module-src -czf demo-1.0.0.tgz main.tf
STEP="PUT module tarball"; expect_code 201 -u "admin:$APITOKEN" -X PUT --data-binary @demo-1.0.0.tgz "$HOSTED/v1/modules/$NS/demo/local/1.0.0.tgz"
STEP="re-uploading the module version is a 409"; expect_code 409 -u "admin:$APITOKEN" -X PUT --data-binary @demo-1.0.0.tgz "$HOSTED/v1/modules/$NS/demo/local/1.0.0.tgz"
STEP="module versions document lists 1.0.0"; api "$GROUP/v1/modules/$NS/demo/local/versions" | jq -e '.modules[0].versions[0].version == "1.0.0"' >/dev/null && ok "$STEP" || bad "$STEP"
STEP="module download answers 204 with X-Terraform-Get"
"${CURL[@]}" -D headers.txt -o /dev/null "$GROUP/v1/modules/$NS/demo/local/1.0.0/download" && grep -qi "^X-Terraform-Get: $GROUP/files/modules/$NS/demo/local/1.0.0.tgz" headers.txt && ok "$STEP" || { bad "$STEP"; cat headers.txt; }
cd "$WORK"

for c in "${CLIS[@]}"; do
  echo "== $c: hosted provider and module"
  H="$WORK/$c/hosted-provider"; provider_project "$H" "$HOSTPORT/$NS/$PROVIDER_TYPE"
  STEP="$c init $NS/$PROVIDER_TYPE from the hosted repository (signature verified against the uploaded key)"; check cli "$c" "$H" init -no-color
  STEP="$c lock file records $HOSTPORT/$NS/$PROVIDER_TYPE"; grep -q "provider \"$HOSTPORT/$NS/$PROVIDER_TYPE\"" "$H/.terraform.lock.hcl" 2>/dev/null && ok "$STEP" || bad "$STEP"
  D="$WORK/$c/hosted-module"; module_project "$D" "$HOSTPORT/$NS/demo/local" "1.0.0"
  STEP="$c init module \"x\" { source = \"$HOSTPORT/$NS/demo/local\" }"; check cli "$c" "$D" init -no-color
  STEP="$c module files were unpacked"; [ -f "$D/.terraform/modules/x/main.tf" ] && ok "$STEP" || bad "$STEP"
  STEP="$c validate with the module"; check cli "$c" "$D" validate -no-color
done

# --- offline -----------------------------------------------------------------------------------------------------------------
echo "== Offline: everything again with upstream access disabled and empty local caches"
settings '{"offlineMode":true}' && ok "offline mode on" || bad "offline mode on"
for c in "${CLIS[@]}"; do
  rm -rf "${WORK:?}/${c:?}/home"
  for p in "${PROJECTS[@]}"; do
    clean "$WORK/$c/$p"
    STEP="offline $c init $p (from the Granary cache)"; check cli "$c" "$WORK/$c/$p" init -no-color
  done
done
STEP="offline uncached provider is a clean 503 on the proxy"; expect_code 503 "$BASE/repository/terraform-registry/v1/providers/$PROVIDER_NS/nothing-cached/versions"
STEP="offline uncached provider is a clean 503 through the group"; expect_code 503 "$GROUP/v1/providers/$PROVIDER_NS/nothing-cached/versions"
settings '{"offlineMode":false}' && ok "offline mode off" || bad "offline mode off"

# --- credentials: anonymous read off, the CLI configuration carries the token ---------------------------------------------
# The registry API calls (discovery, versions, download documents) are authenticated with the credentials block, but
# both CLIs fetch provider archives and SHA256SUMS files with a plain HTTP client that sends no token, so an
# installation cannot complete while anonymous read is off. The run records that as KNOWN rather than FAIL; see
# docs/formats/terraform.md.
echo "== Credentials: anonymous read off, token in the CLI configuration"
settings '{"anonymousRead":false}' && ok "anonymous read off" || bad "anonymous read off"
STEP="discovery without credentials is a 401"; expect_code 401 "$BASE/.well-known/terraform.json"
STEP="versions document with the token"; expect_code 200 -u "admin:$APITOKEN" "$GROUP/v1/providers/$NS/$PROVIDER_TYPE/versions"
STEP="versions document with a wrong token is a 401"; expect_code 401 -H "Authorization: Bearer gry_wrong" "$GROUP/v1/providers/$NS/$PROVIDER_TYPE/versions"
for c in "${CLIS[@]}"; do
  A="$WORK/$c/authenticated"; provider_project "$A" "$HOSTPORT/$NS/$PROVIDER_TYPE"
  printf 'credentials "%s" {\n  token = "%s"\n}\n' "$HOSTPORT" "$APITOKEN" > "$A/cli.tfrc"
  STEP="$c init with credentials \"$HOSTPORT\" { token = ... }"
  if cli "$c" "$A" init -no-color >"$WORK/last.log" 2>&1; then ok "$STEP"
  elif grep -q "Finding $HOSTPORT/$NS/$PROVIDER_TYPE versions" "$WORK/last.log" && grep -qi "401" "$WORK/last.log"; then
    known "$STEP: registry calls authenticated, the checksum download is sent without the token (401)"
  else bad "$STEP"; tail -25 "$WORK/last.log"; fi
done
settings '{"anonymousRead":true}' && ok "anonymous read on" || bad "anonymous read on"

echo; echo "passed=$PASS failed=$FAIL skipped=$SKIP known-client-limitations=$KNOWN  (work dir $WORK)"
[ "$FAIL" = 0 ]
