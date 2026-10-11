#!/usr/bin/env bash
# End-to-end verification of the Helm format with the real helm CLI (and the helm-push plugin), including an
# offline run against the Granary cache and a run with anonymous reads disabled.
#
# Creates helm-hosted and helm-group (the standard repositories) plus proxies of two public chart repositories
# whose indexes are served from raw.githubusercontent.com (helm/examples and kubernetes/ingress-nginx, both with
# archives on GitHub release downloads), puts them in helm-group, and then:
#   - adds helm-group with `helm repo add`, searches, pulls and templates a proxied chart;
#   - packages charts and uploads them with curl (raw body and multipart with a provenance file) and helm cm-push;
#   - pulls a signed chart with --verify, builds a chart with `helm dependency update` against the group;
#   - renders a hosted and a proxied chart with helmfile;
#   - deletes a version through the ChartMuseum API;
#   - switches Granary to offline mode, clears helm's cache and checks what still works;
#   - disables anonymous reads and checks that helm needs its credentials.
#
# Needs curl, gpg (for the signing steps, skipped without it) and either helm 3 or 4 on the PATH or docker, in which
# case the alpine/helm image is used with host networking (override with HELM_IMAGE, e.g. alpine/helm:4.2.0). helm's
# configuration, cache and plugins live in a fresh work directory, so the caller's helm setup is neither used nor
# changed. When the helm-push plugin cannot be installed (it is downloaded from github.com) or cannot run as a command
# (helm 4 loads helm-push 0.10.x as a getter plugin only), its steps are skipped. Set CA_BUNDLE to a
# PEM file to have the docker helm trust it (for a TLS-inspecting egress proxy); HTTPS_PROXY/HTTP_PROXY/NO_PROXY
# are passed through.
# Usage: verify-helm.sh http://localhost:8081   (admin/admin123 must be valid)
set -uo pipefail
BASE=${1:-http://localhost:8081}
BASE=${BASE%/}
EXAMPLES_URL=${EXAMPLES_URL:-https://raw.githubusercontent.com/helm/examples/gh-pages}
INGRESS_URL=${INGRESS_URL:-https://raw.githubusercontent.com/kubernetes/ingress-nginx/gh-pages}
HELM_IMAGE=${HELM_IMAGE:-alpine/helm:3.19.0}
WORK=$(mktemp -d /tmp/granary-verify-helm.XXXX)
cd "$WORK"
PASS=0; FAIL=0; SKIP=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
skip() { echo "SKIP  $1"; SKIP=$((SKIP+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# fails <pattern> <command…>: the command must fail and its output must match the pattern
fails(){ local pattern=$1; shift; if "$@" >"$WORK/last.log" 2>&1; then bad "$STEP (succeeded)"; tail -5 "$WORK/last.log";
         elif grep -q -E "$pattern" "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# outputs <pattern> <command…>: the command must succeed and its output must match the pattern
outputs(){ local pattern=$1; shift; if ! "$@" >"$WORK/last.log" 2>&1; then bad "$STEP"; tail -20 "$WORK/last.log";
           elif grep -q -E "$pattern" "$WORK/last.log"; then ok "$STEP"; else bad "$STEP (no match for $pattern)"; tail -20 "$WORK/last.log"; fi; }
command -v curl >/dev/null || { echo "missing required tool: curl"; exit 2; }

export HELM_CONFIG_HOME="$WORK/helm/config" HELM_CACHE_HOME="$WORK/helm/cache" HELM_DATA_HOME="$WORK/helm/data"
mkdir -p "$HELM_CONFIG_HOME" "$HELM_CACHE_HOME" "$HELM_DATA_HOME"
if command -v helm >/dev/null; then
  H() { helm "$@"; }
elif command -v docker >/dev/null; then
  DOCKER_ARGS=(--rm --network host -v "$WORK:$WORK" -w "$WORK"
               -e HELM_CONFIG_HOME -e HELM_CACHE_HOME -e HELM_DATA_HOME -e HTTPS_PROXY -e HTTP_PROXY -e NO_PROXY)
  [ -n "${CA_BUNDLE:-}" ] && [ -f "$CA_BUNDLE" ] && DOCKER_ARGS+=(-v "$CA_BUNDLE:/etc/ssl/certs/ca-certificates.crt:ro")
  H() { docker run "${DOCKER_ARGS[@]}" "$HELM_IMAGE" "$@"; }
else
  echo "missing required tool: helm (or docker to run $HELM_IMAGE)"; exit 2
fi
echo "helm: $(H version --short 2>&1)"

api() { curl -sS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }
status() { curl -sS -o "$WORK/body.out" -w '%{http_code}' "$@"; }

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
api -f -X PUT "$BASE/api/v1/system/settings" -d '{"offlineMode":false,"anonymousRead":true}' >/dev/null
api -f -X POST "$BASE/api/v1/repositories/standard" >/dev/null && ok "standard repositories" || bad "standard repositories"
APITOKEN=$(api -f -X POST "$BASE/api/v1/tokens" -d '{"name":"verify-helm"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
for proxy in "helm-examples $EXAMPLES_URL" "helm-ingress-nginx $INGRESS_URL"; do
  set -- $proxy
  body="{\"name\":\"$1\",\"format\":\"helm\",\"type\":\"PROXY\",\"remoteUrl\":\"$2\"}"
  code=$(status -X POST "$BASE/api/v1/repositories" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body")
  [ "$code" = 409 ] && code=$(status -X PUT "$BASE/api/v1/repositories/$1" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body")
  [[ "$code" =~ ^20 ]] && ok "proxy $1 -> $2" || { bad "proxy $1 ($code)"; cat "$WORK/body.out"; echo; }
done
code=$(status -X PUT "$BASE/api/v1/repositories/helm-group" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"helm-group","format":"helm","type":"GROUP","members":["helm-hosted","helm-examples","helm-ingress-nginx"]}')
[ "$code" = 200 ] && ok "helm-group = helm-hosted + helm-examples + helm-ingress-nginx" || { bad "group members ($code)"; cat "$WORK/body.out"; echo; }
# leftovers of an earlier run would make the uploads below conflicts
for v in 1.0.0 1.1.0 1.2.0 1.3.0; do curl -sS -o /dev/null -u "admin:$APITOKEN" -X DELETE "$BASE/repository/helm-hosted/api/charts/acme-web/$v"; done
curl -sS -o /dev/null -u "admin:$APITOKEN" -X DELETE "$BASE/repository/helm-hosted/api/charts/acme-app/0.1.0"

echo "== helm repo add / search / pull through the group"
STEP="helm repo add granary (helm-group, token as password)"
check H repo add granary "$BASE/repository/helm-group" --username admin --password "$APITOKEN"
STEP="helm repo add granary-hosted"
check H repo add granary-hosted "$BASE/repository/helm-hosted" --username admin --password "$APITOKEN"
STEP="helm search repo finds the proxied hello-world"; outputs 'granary/hello-world[[:space:]]+0\.1\.0' H search repo granary/hello-world
STEP="helm search repo finds the proxied ingress-nginx"; outputs 'granary/ingress-nginx' H search repo granary/ingress-nginx
mkdir -p "$WORK/pulled"
STEP="helm pull granary/hello-world (GitHub release download via Granary)"
check H pull granary/hello-world --version 0.1.0 -d "$WORK/pulled"
STEP="helm template the pulled chart"; outputs 'kind: Deployment' H template demo "$WORK/pulled/hello-world-0.1.0.tgz"
STEP="helm template straight from the repository"; outputs 'name: demo-hello-world' H template demo granary/hello-world
INGRESS_VERSIONS=$(H search repo granary/ingress-nginx --versions 2>/dev/null | awk 'NR>1 && $1=="granary/ingress-nginx" {print $2}')
INGRESS_LATEST=$(echo "$INGRESS_VERSIONS" | sed -n 1p)
INGRESS_OLD=$(echo "$INGRESS_VERSIONS" | sed -n 5p)
STEP="helm pull granary/ingress-nginx $INGRESS_LATEST"; check H pull granary/ingress-nginx --version "$INGRESS_LATEST" -d "$WORK/pulled"
STEP="every chart URL in the group index points at Granary"
curl -fsS -o "$WORK/group-index.yaml" "$BASE/repository/helm-group/index.yaml"
grep -E '\.tgz' "$WORK/group-index.yaml" > "$WORK/group-urls.txt"
grep -v -F "$BASE/repository/helm-group/charts/" "$WORK/group-urls.txt" > "$WORK/foreign-urls.txt"
if grep -q -F "$BASE/repository/helm-group/charts/hello-world-0.1.0.tgz" "$WORK/group-urls.txt" && [ ! -s "$WORK/foreign-urls.txt" ]; then ok "$STEP"
else bad "$STEP"; head -5 "$WORK/foreign-urls.txt"; fi
STEP="index.yaml publishes serverInfo.contextPath for helm cm-push"
outputs 'contextPath: /repository/helm-hosted' curl -fsS "$BASE/repository/helm-hosted/index.yaml"

echo "== uploads"
mkdir -p "$WORK/src" "$WORK/dist"
H create "$WORK/src/acme-web" >/dev/null 2>&1
setver() { sed -i.bak "s/^version: .*/version: $1/" "$WORK/src/acme-web/Chart.yaml"; }
setver 1.0.0; H package "$WORK/src/acme-web" -d "$WORK/dist" >/dev/null 2>&1
STEP="curl --data-binary upload to helm-hosted"
outputs '"saved":true' curl -fsS -u "admin:$APITOKEN" --data-binary "@$WORK/dist/acme-web-1.0.0.tgz" "$BASE/repository/helm-hosted/api/charts"
STEP="uploading the same version again is a 409"
[ "$(status -u "admin:$APITOKEN" --data-binary "@$WORK/dist/acme-web-1.0.0.tgz" "$BASE/repository/helm-hosted/api/charts")" = 409 ] \
  && ok "$STEP" || bad "$STEP"
STEP="anonymous upload is a 401"
[ "$(status --data-binary "@$WORK/dist/acme-web-1.0.0.tgz" "$BASE/repository/helm-hosted/api/charts")" = 401 ] && ok "$STEP" || bad "$STEP"
STEP="uploads to the group are refused with a 405"
[ "$(status -u "admin:$APITOKEN" --data-binary "@$WORK/dist/acme-web-1.0.0.tgz" "$BASE/repository/helm-group/api/charts")" = 405 ] \
  && ok "$STEP" || bad "$STEP"

SIGNED=""
if command -v gpg >/dev/null; then
  export GNUPGHOME="$WORK/g"; mkdir -p "$GNUPGHOME"; chmod 700 "$GNUPGHOME"
  # gpg starts an agent for this home directory; it would outlive the script
  trap 'gpgconf --kill gpg-agent >/dev/null 2>&1' EXIT
  if gpg --batch --pinentry-mode loopback --passphrase '' --quick-gen-key "Chart Signer <signer@example.com>" rsa2048 sign never >/dev/null 2>&1 \
     && gpg --batch --pinentry-mode loopback --passphrase '' --export-secret-keys > "$WORK/secring.gpg" 2>/dev/null \
     && gpg --batch --export > "$WORK/pubring.gpg" 2>/dev/null; then
    setver 1.1.0
    STEP="helm package --sign"; check H package "$WORK/src/acme-web" -d "$WORK/dist" --sign --key "Chart Signer" --keyring "$WORK/secring.gpg"
    STEP="curl -F chart=@… -F prov=@… (multipart with provenance)"
    outputs '"saved":true' curl -fsS -u "admin:$APITOKEN" -F "chart=@$WORK/dist/acme-web-1.1.0.tgz" \
      -F "prov=@$WORK/dist/acme-web-1.1.0.tgz.prov" "$BASE/repository/helm-hosted/api/charts"
    SIGNED=1
  else
    skip "gpg could not create a signing key; signing steps"
  fi
else
  skip "gpg not installed; signing steps"
fi

PUSH=""
PUSH_PLUGIN=(plugin install https://github.com/chartmuseum/helm-push --version v0.10.4)
# helm 4 refuses plugins without a provenance signature unless told otherwise
H "${PUSH_PLUGIN[@]}" >"$WORK/plugin.log" 2>&1 \
  || { grep -q -- '--verify=false' "$WORK/plugin.log" && H "${PUSH_PLUGIN[@]}" --verify=false >>"$WORK/plugin.log" 2>&1; }
if H cm-push --help >/dev/null 2>&1; then
  PUSH=1
  setver 1.2.0
  STEP="helm cm-push <chart dir> granary-hosted"; outputs 'Done' H cm-push "$WORK/src/acme-web" granary-hosted
  setver 1.3.0; H package "$WORK/src/acme-web" -d "$WORK/dist" >/dev/null 2>&1
  STEP="helm cm-push <tgz> <url> --username --password"
  outputs 'Done' H cm-push "$WORK/dist/acme-web-1.3.0.tgz" "$BASE/repository/helm-hosted" --username admin --password "$APITOKEN"
  STEP="helm cm-push of an existing version reports the 409"
  fails '409' H cm-push "$WORK/dist/acme-web-1.3.0.tgz" granary-hosted
  STEP="helm cm-push to the group reports the 405 and the hosted member"
  fails "405.*use its hosted member 'helm-hosted'" H cm-push "$WORK/dist/acme-web-1.3.0.tgz" granary
else
  # helm 4 loads helm-push 0.10.x as a getter plugin only, so there is no cm-push command to run
  skip "helm cm-push is not available (plugin download failed, or this helm does not run helm-push as a command); helm cm-push steps"
  tail -3 "$WORK/plugin.log"; H plugin list 2>&1 | tail -2
fi

echo "== reading back through the group"
STEP="helm repo update"; check H repo update
EXPECTED='1\.0\.0'; [ -n "$SIGNED" ] && EXPECTED='1\.1\.0'; [ -n "$PUSH" ] && EXPECTED='1\.3\.0'
STEP="helm search repo --versions lists the uploads"; outputs "granary/acme-web[[:space:]]+$EXPECTED" H search repo granary/acme-web --versions
mkdir -p "$WORK/pulled2"
STEP="helm pull granary/acme-web 1.0.0 is byte-identical"
if H pull granary/acme-web --version 1.0.0 -d "$WORK/pulled2" >"$WORK/last.log" 2>&1 \
   && cmp -s "$WORK/pulled2/acme-web-1.0.0.tgz" "$WORK/dist/acme-web-1.0.0.tgz"; then ok "$STEP"; else bad "$STEP"; tail -5 "$WORK/last.log"; fi
if [ -n "$SIGNED" ]; then
  mkdir -p "$WORK/pulled3" "$WORK/pulled4"
  STEP="helm pull --verify of the signed chart"
  outputs 'Chart Hash Verified' H pull granary/acme-web --version 1.1.0 --verify --keyring "$WORK/pubring.gpg" -d "$WORK/pulled3"
  STEP="helm verify of the pulled chart"; outputs 'Signed by: Chart Signer' H verify "$WORK/pulled3/acme-web-1.1.0.tgz" --keyring "$WORK/pubring.gpg"
  STEP="helm pull --verify of an unsigned chart fails on the missing provenance file"
  fails 'provenance' H pull granary/acme-web --version 1.0.0 --verify --keyring "$WORK/pubring.gpg" -d "$WORK/pulled4"
fi

H create "$WORK/src/acme-app" >/dev/null 2>&1
sed -i.bak "s/^version: .*/version: 0.1.0/" "$WORK/src/acme-app/Chart.yaml"
cat >> "$WORK/src/acme-app/Chart.yaml" <<EOF
dependencies:
  - name: acme-web
    version: "~1.0.0"
    repository: "@granary"
  - name: hello-world
    version: "0.1.0"
    repository: "$BASE/repository/helm-group"
EOF
STEP="helm dependency update (by repository alias and by URL)"; check H dependency update "$WORK/src/acme-app"
STEP="dependencies were downloaded"
if [ -f "$WORK/src/acme-app/charts/acme-web-1.0.0.tgz" ] && [ -f "$WORK/src/acme-app/charts/hello-world-0.1.0.tgz" ]; then ok "$STEP"; else bad "$STEP"; ls "$WORK/src/acme-app/charts"; fi
H package "$WORK/src/acme-app" -d "$WORK/dist" >/dev/null 2>&1
STEP="an umbrella chart with its dependencies uploads"
outputs '"saved":true' curl -fsS -u "admin:$APITOKEN" --data-binary "@$WORK/dist/acme-app-0.1.0.tgz" "$BASE/repository/helm-hosted/api/charts"

echo "== helmfile"
# helmfile from the PATH, or the chatwork/helmfile image (helmfile with its own helm) through docker
HELMFILE_IMAGE=${HELMFILE_IMAGE:-chatwork/helmfile:1.8.0-4.3.0}
HF=""
if command -v helmfile >/dev/null; then
  HF() { helmfile "$@"; }; HF=1
elif command -v docker >/dev/null && { docker image inspect "$HELMFILE_IMAGE" >/dev/null 2>&1 || docker pull -q "$HELMFILE_IMAGE" >/dev/null 2>&1; }; then
  HF() { docker run --rm --network host -v "$WORK:$WORK" -w "$WORK/helmfile" -e HELM_CONFIG_HOME="$WORK/helmfile/config" \
           -e HELM_CACHE_HOME="$WORK/helmfile/cache" -e HELM_DATA_HOME="$WORK/helmfile/data" "$HELMFILE_IMAGE" "$@"; }; HF=1
fi
if [ -n "$HF" ]; then
  mkdir -p "$WORK/helmfile/config" "$WORK/helmfile/cache" "$WORK/helmfile/data"
  cat > "$WORK/helmfile/helmfile.yaml" <<HELMFILE
repositories:
  - name: granary
    url: $BASE/repository/helm-group
    username: admin
    password: $APITOKEN

releases:
  - name: web
    namespace: demo
    chart: granary/acme-web
    version: 1.0.0
  - name: hello
    namespace: demo
    chart: granary/hello-world
    version: 0.1.0
HELMFILE
  STEP="helmfile template (repository with credentials, a proxied chart)"
  outputs 'Source: hello-world/templates/deployment.yaml' HF template
  if grep -q 'Source: acme-web/templates/deployment.yaml' "$WORK/last.log"; then ok "helmfile template renders the hosted chart"
  else bad "helmfile template renders the hosted chart"; fi
else
  skip "neither helmfile nor docker with $HELMFILE_IMAGE; helmfile steps"
fi

echo "== ChartMuseum API"
STEP="GET api/charts/acme-web/latest"; outputs '"version"' curl -fsS -u "admin:$APITOKEN" "$BASE/repository/helm-hosted/api/charts/acme-web/latest"
STEP="GET health"; outputs '"healthy":true' curl -fsS "$BASE/repository/helm-hosted/health"
STEP="DELETE api/charts/acme-web/1.0.0"; outputs '"deleted":true' curl -fsS -u "admin:$APITOKEN" -X DELETE "$BASE/repository/helm-hosted/api/charts/acme-web/1.0.0"
H repo update >/dev/null 2>&1
STEP="the deleted version is gone after helm repo update"
H search repo granary/acme-web --versions > "$WORK/search.txt" 2>&1
if { [ -z "$SIGNED$PUSH" ] || grep -q -E 'granary/acme-web[[:space:]]+1\.[13]\.0' "$WORK/search.txt"; } \
   && ! grep -q -E 'granary/acme-web[[:space:]]+1\.0\.0[[:space:]]' "$WORK/search.txt"; then ok "$STEP"; else bad "$STEP"; cat "$WORK/search.txt"; fi

echo "== offline mode"
api -f -X PUT "$BASE/api/v1/system/settings" -d '{"offlineMode":true}' >/dev/null && ok "offline mode on" || bad "offline mode on"
rm -rf "$HELM_CACHE_HOME" && mkdir -p "$HELM_CACHE_HOME"
STEP="helm repo update works from the cached indexes"; check H repo update
mkdir -p "$WORK/pulled5"
STEP="helm pull of a cached proxied chart"; check H pull granary/hello-world --version 0.1.0 -d "$WORK/pulled5"
STEP="helm pull of a cached ingress-nginx"; check H pull granary/ingress-nginx --version "$INGRESS_LATEST" -d "$WORK/pulled5"
STEP="helm pull of a hosted chart"; check H pull granary/acme-app --version 0.1.0 -d "$WORK/pulled5"
if [ -n "$INGRESS_OLD" ]; then
  STEP="helm pull of a chart that was never cached fails with the 503"
  fails '503' H pull granary/ingress-nginx --version "$INGRESS_OLD" -d "$WORK/pulled5"
else
  skip "ingress-nginx lists fewer than five versions; uncached pull"
fi
api -f -X PUT "$BASE/api/v1/system/settings" -d '{"offlineMode":false}' >/dev/null && ok "offline mode off" || bad "offline mode off"

echo "== anonymous reads disabled"
api -f -X PUT "$BASE/api/v1/system/settings" -d '{"anonymousRead":false}' >/dev/null && ok "anonymous read off" || bad "anonymous read off"
STEP="helm repo add without credentials is refused"
fails '401' H repo add granary-anon "$BASE/repository/helm-group"
STEP="helm repo update with credentials works"; check H repo update granary
mkdir -p "$WORK/pulled6"
STEP="helm pull with credentials works"; check H pull granary/acme-app --version 0.1.0 -d "$WORK/pulled6"
STEP="the health check stays open"; outputs '"healthy":true' curl -fsS "$BASE/repository/helm-group/health"
api -f -X PUT "$BASE/api/v1/system/settings" -d '{"anonymousRead":true}' >/dev/null && ok "anonymous read on" || bad "anonymous read on"

echo
echo "passed: $PASS  failed: $FAIL  skipped: $SKIP  (work dir $WORK)"
[ "$FAIL" -eq 0 ]
