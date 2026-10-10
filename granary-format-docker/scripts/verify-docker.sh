#!/usr/bin/env bash
# End-to-end verification of the Docker / OCI registry with the real container clients, including an offline run.
# Needs a running Granary with admin/admin123 and a docker CLI with a daemon; podman, skopeo, buildah, oras, crane,
# ctr (containerd), nerdctl and helm are exercised when available (oras, crane and nerdctl are fetched from their
# GitHub releases and helm runs from the alpine/helm image when they are not installed), the rest is skipped.
# VERIFY_KUBELET=1 also boots a kind node from an image pulled through Granary and pulls through its containerd CRI
# image service with crictl, configured by the hosts.toml of the connect page (the kubelet's path).
# Usage: verify-docker.sh http://localhost:8102
# ALPINE_TAG and BUSYBOX_TAG pick the Docker Hub images to pull (default 3.20 and 1.36); choose tags the local docker
# image store does not hold yet, otherwise docker reuses local layers and only the manifests travel through Granary.
set -uo pipefail
BASE=${1:-http://localhost:8102}
HOSTPORT=${BASE#http://}
ALPINE=${ALPINE_TAG:-3.20}
BUSYBOX=${BUSYBOX_TAG:-1.36}
WORK=$(mktemp -d /tmp/granary-verify-docker.XXXX)
export DOCKER_CONFIG="$WORK/docker-config"
export REGISTRY_AUTH_FILE="$WORK/auth.json"
export XDG_RUNTIME_DIR="$WORK/xdg"
mkdir -p "$DOCKER_CONFIG" "$XDG_RUNTIME_DIR"
cd "$WORK"
PASS=0; FAIL=0; SKIP=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
skip() { echo "SKIP  $1"; SKIP=$((SKIP+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# the command must fail, and its output must mention what we expect (a clean error, not a hang or a stack trace)
expectfail(){ local want=$1; shift; if "$@" >"$WORK/last.log" 2>&1; then bad "$STEP (unexpectedly succeeded)"; else
  if grep -q -- "$want" "$WORK/last.log"; then ok "$STEP ($(grep -m1 -- "$want" "$WORK/last.log" | sed 's/^ *//' | cut -c1-110))"; else bad "$STEP (failed for another reason)"; tail -5 "$WORK/last.log"; fi; fi; }
api(){ curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }
# whether this machine may open a connection to an upstream at all (the egress proxy answers CONNECT with 403 otherwise)
reachable(){ curl -sS -o /dev/null --max-time 20 "$1" 2>"$WORK/reach.log"; }
blocked(){ tr -d '\r' < "$WORK/reach.log" | tail -1; }
# oras and crane are single binaries: when they are not installed, fetch the release into the work dir
TOOLS="$WORK/bin"; mkdir -p "$TOOLS"; export PATH="$PATH:$TOOLS"
fetch_tool(){ command -v "$1" >/dev/null 2>&1 && return 0
  if curl -fsSL --max-time 180 "$2" -o "$WORK/$1.tgz" && tar -xzf "$WORK/$1.tgz" -C "$TOOLS" "$1" 2>/dev/null; then
    echo "      fetched $1 from $2"; else echo "      could not fetch $1 from $2"; fi; }
ACCEPT_ALL='application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'
# the manifests, config and layers of an image (the platform docker pulls) must all be served by repository $1 itself
repo_has_image(){ local url="$BASE/repository/$1/v2/$2" doc digest blob
  doc=$(curl -fsS -u "admin:$APITOKEN" -H "Accept: $ACCEPT_ALL" "$url/manifests/$3") || return 1
  digest=$(printf '%s' "$doc" | python3 -I -c 'import json,sys; d=json.load(sys.stdin); a=sys.argv[1]
m=[x["digest"] for x in d.get("manifests", []) if x.get("platform", {}).get("os") == "linux" and x.get("platform", {}).get("architecture") == a]
print(m[0] if m else "")' "$ARCH") || return 1
  if [ -n "$digest" ]; then doc=$(curl -fsS -u "admin:$APITOKEN" -H "Accept: $ACCEPT_ALL" "$url/manifests/$digest") || return 1; fi
  for blob in $(printf '%s' "$doc" | python3 -I -c 'import json,sys; d=json.load(sys.stdin); print(d["config"]["digest"]); [print(l["digest"]) for l in d["layers"]]'); do
    curl -fsS -o /dev/null -I -u "admin:$APITOKEN" "$url/blobs/$blob" || { echo "missing $blob"; return 1; }
  done; }
ARCH=$(docker version --format '{{.Server.Arch}}' 2>/dev/null || echo amd64)

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
api -X POST "$BASE/api/v1/repositories/standard" >/dev/null && ok "standard repositories (docker-hub, ghcr, quay, docker-hosted, docker-group)" || bad "standard repositories"
APITOKEN=$(api -X POST "$BASE/api/v1/tokens" -d '{"name":"verify-docker"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
api -X PUT "$BASE/api/v1/system/settings" -d '{"offlineMode":false,"anonymousRead":true}' >/dev/null
HOSTED_JSON='{"name":"docker-hosted","format":"docker","type":"HOSTED","description":"Your own images, charts and OCI artifacts"'
GROUP_JSON='{"name":"docker-group","format":"docker","type":"GROUP","members":["docker-hosted","docker-hub","ghcr","quay"]'
api -X PUT "$BASE/api/v1/repositories/docker-hosted" -d "$HOSTED_JSON,\"allowRedeploy\":false}" >/dev/null && ok "docker-hosted starts with allowRedeploy=false" || bad "reset docker-hosted"
api -X PUT "$BASE/api/v1/repositories/docker-group" -d "$GROUP_JSON,\"formatSettings\":{\"docker.rootRepository\":\"true\"}}" >/dev/null && ok "docker-group is the root with the default ping policy" || bad "reset docker-group"

# tags a previous run left behind (untagging is idempotent; the 404 of a missing tag is fine)
for t in acme/alpine:1 acme/alpine:anonymous acme/alpine:podman acme/alpine:buildah acme/alpine:crane acme/alpine:ctr \
         acme/alpine:ctr-hosts acme/busybox:nerdctl mirror/alpine:$ALPINE mirror/busybox:$BUSYBOX acme/artifacts:1 charts/mychart:0.1.0; do
  curl -s -o /dev/null -u "admin:$APITOKEN" -X DELETE "$BASE/repository/docker-hosted/v2/${t%%:*}/manifests/${t##*:}"
done

echo "== registry API with curl"
STEP="GET /v2/ anonymous is 401 with a Basic challenge"
check sh -c "curl -sS -D - -o /dev/null '$BASE/v2/' | grep -qi 'WWW-Authenticate: Basic' && curl -sS -o /dev/null -w '%{http_code}' '$BASE/v2/' | grep -q 401"
STEP="GET /v2/ with the api token is 200"
check sh -c "curl -fsS -u admin:$APITOKEN '$BASE/v2/' | grep -q '{}'"
STEP="GET /v2/ carries Docker-Distribution-API-Version"
check sh -c "curl -sS -D - -o /dev/null -u admin:$APITOKEN '$BASE/v2/' | grep -qi 'Docker-Distribution-API-Version: registry/2.0'"

echo "== docker"
if docker info >/dev/null 2>&1; then
  STEP="docker login"; check sh -c "echo '$APITOKEN' | docker login '$HOSTPORT' -u admin --password-stdin"
  docker rmi -f "$HOSTPORT/library/alpine:$ALPINE" "$HOSTPORT/docker-hosted/acme/alpine:1" "$HOSTPORT/acme/alpine:1" "$HOSTPORT/library/busybox:$BUSYBOX" >/dev/null 2>&1
  STEP="docker pull library/alpine:$ALPINE through the root (group -> docker-hub proxy)"; check docker pull "$HOSTPORT/library/alpine:$ALPINE"
  STEP="docker tag"; check docker tag "$HOSTPORT/library/alpine:$ALPINE" "$HOSTPORT/docker-hosted/acme/alpine:1"
  STEP="docker push to docker-hosted"; check docker push "$HOSTPORT/docker-hosted/acme/alpine:1"
  STEP="docker push the same tag again (same digest, idempotent)"; check docker push "$HOSTPORT/docker-hosted/acme/alpine:1"
  STEP="docker pull alpine:$ALPINE without library/ (served from the cache under library/alpine)"; check docker pull "$HOSTPORT/alpine:$ALPINE"
  STEP="ghcr.io manifest through the root (docker-hub answers 401 for it, the group falls through to ghcr)"
  check sh -c "curl -fsS -u admin:$APITOKEN -H 'Accept: application/vnd.oci.image.index.v1+json' '$BASE/v2/oras-project/oras/manifests/v1.2.0' | grep -q manifests"
  if reachable https://pkg-containers.githubusercontent.com/; then
    STEP="docker pull the ghcr.io image through the root"; check docker pull "$HOSTPORT/oras-project/oras:v1.2.0"
  else
    skip "docker pull of the ghcr.io image: its blob CDN pkg-containers.githubusercontent.com is not reachable from here ($(blocked))"
  fi
  if reachable https://quay.io/v2/; then
    STEP="docker pull a quay.io image through the quay proxy"; check docker pull "$HOSTPORT/quay/prometheus/node-exporter:v1.8.2"
    docker rmi "$HOSTPORT/quay/prometheus/node-exporter:v1.8.2" >/dev/null 2>&1
  else
    skip "docker pull through the quay proxy: quay.io is not reachable from here ($(blocked))"
  fi
  docker rmi "$HOSTPORT/alpine:$ALPINE" "$HOSTPORT/oras-project/oras:v1.2.0" >/dev/null 2>&1
  STEP="docker rmi"; check docker rmi "$HOSTPORT/library/alpine:$ALPINE" "$HOSTPORT/docker-hosted/acme/alpine:1"
  STEP="docker pull from docker-hosted"; check docker pull "$HOSTPORT/docker-hosted/acme/alpine:1"
  STEP="docker pull acme/alpine:1 through the root (group -> docker-hosted)"; check docker pull "$HOSTPORT/acme/alpine:1"
  STEP="tags/list of the pushed image"
  check sh -c "curl -fsS -u admin:$APITOKEN '$BASE/v2/docker-hosted/acme/alpine/tags/list' | grep -q '\"1\"'"
  STEP="_catalog lists it"
  check sh -c "curl -fsS -u admin:$APITOKEN '$BASE/v2/_catalog' | grep -q 'acme/alpine'"

  echo "== docker, offline"
  api -X PUT "$BASE/api/v1/system/settings" -d '{"offlineMode":true}' >/dev/null && ok "offline mode on" || bad "offline mode on"
  STEP="the docker-hub proxy itself serves every manifest and blob of library/alpine:$ALPINE ($ARCH) while offline"
  check repo_has_image docker-hub library/alpine "$ALPINE"
  STEP="docker-hosted serves every manifest and blob of acme/alpine:1"; check repo_has_image docker-hosted acme/alpine 1
  docker rmi "$HOSTPORT/library/alpine:$ALPINE" "$HOSTPORT/acme/alpine:1" "$HOSTPORT/docker-hosted/acme/alpine:1" >/dev/null 2>&1
  STEP="docker pull library/alpine:$ALPINE from the cache while offline"; check docker pull "$HOSTPORT/library/alpine:$ALPINE"
  STEP="docker pull of the hosted image while offline"; check docker pull "$HOSTPORT/acme/alpine:1"
  STEP="docker pull of an uncached image while offline fails cleanly"; expectfail "503" docker pull "$HOSTPORT/library/nginx:1.27-alpine"
  api -X PUT "$BASE/api/v1/system/settings" -d '{"offlineMode":false}' >/dev/null && ok "offline mode off" || bad "offline mode off"

  echo "== docker, multi-arch"
  STEP="docker pull library/busybox:$BUSYBOX (manifest list, then the platform manifest)"; check docker pull "$HOSTPORT/library/busybox:$BUSYBOX"
  STEP="docker manifest inspect --insecure busybox:$BUSYBOX shows the index"
  check sh -c "docker manifest inspect --insecure '$HOSTPORT/library/busybox:$BUSYBOX' | grep -q 'manifests'"

  echo "== docker, redeploy policy"
  docker tag "$HOSTPORT/library/busybox:$BUSYBOX" "$HOSTPORT/docker-hosted/acme/alpine:1"
  STEP="moving an existing tag to another image is refused (409 DENIED) while allowRedeploy is off"
  expectfail "already points at" docker push "$HOSTPORT/docker-hosted/acme/alpine:1"
  api -X PUT "$BASE/api/v1/repositories/docker-hosted" -d "$HOSTED_JSON,\"allowRedeploy\":true}" >/dev/null && ok "allowRedeploy=true on docker-hosted" || bad "allowRedeploy=true on docker-hosted"
  STEP="the same push succeeds with allowRedeploy"; check docker push "$HOSTPORT/docker-hosted/acme/alpine:1"
  docker tag "$HOSTPORT/library/alpine:$ALPINE" "$HOSTPORT/docker-hosted/acme/alpine:1"
  STEP="move the tag back"; check docker push "$HOSTPORT/docker-hosted/acme/alpine:1"

  echo "== docker, anonymous"
  docker logout "$HOSTPORT" >/dev/null 2>&1
  docker rmi "$HOSTPORT/acme/alpine:1" >/dev/null 2>&1
  STEP="anonymous docker pull with the default ping challenge (anonymous read is on)"; check docker pull "$HOSTPORT/acme/alpine:1"
  docker tag "$HOSTPORT/acme/alpine:1" "$HOSTPORT/docker-hosted/acme/alpine:anonymous"
  STEP="anonymous docker push of a new tag is refused"; expectfail "no basic auth credentials" docker push "$HOSTPORT/docker-hosted/acme/alpine:anonymous"
  docker rmi "$HOSTPORT/docker-hosted/acme/alpine:anonymous" >/dev/null 2>&1
  api -X PUT "$BASE/api/v1/repositories/docker-group" -d "$GROUP_JSON,\"formatSettings\":{\"docker.rootRepository\":\"true\",\"docker.challengeAnonymous\":\"false\"}}" >/dev/null \
    && ok "docker.challengeAnonymous=false on the root (docker-group)" || bad "docker.challengeAnonymous=false on docker-group"
  STEP="GET /v2/ anonymous is now 200"; check sh -c "curl -fsS '$BASE/v2/' | grep -q '{}'"
  docker rmi "$HOSTPORT/acme/alpine:1" >/dev/null 2>&1
  STEP="anonymous docker pull with the ping answering 200"; check docker pull "$HOSTPORT/acme/alpine:1"
  STEP="docker login with the ping answering 200"; check sh -c "echo '$APITOKEN' | docker login '$HOSTPORT' -u admin --password-stdin"
  STEP="docker push with the ping answering 200 (docker re-authenticates on the 401 of the upload)"; check docker push "$HOSTPORT/docker-hosted/acme/alpine:1"
  if command -v skopeo >/dev/null 2>&1; then
    STEP="anonymous skopeo inspect with the ping answering 200"; check skopeo inspect --tls-verify=false "docker://$HOSTPORT/acme/alpine:1"
  fi
  api -X PUT "$BASE/api/v1/repositories/docker-group" -d "$GROUP_JSON,\"formatSettings\":{\"docker.rootRepository\":\"true\"}}" >/dev/null && ok "ping challenge restored" || bad "ping challenge restored"
  if command -v skopeo >/dev/null 2>&1; then
    STEP="anonymous skopeo inspect with the ping challenge fails (containers/image needs a login then)"
    expectfail "authentication required" skopeo inspect --tls-verify=false "docker://$HOSTPORT/acme/alpine:1"
  fi
else
  skip "docker (no daemon)"
fi

echo "== skopeo"
if command -v skopeo >/dev/null 2>&1; then
  STEP="skopeo login"; check sh -c "echo '$APITOKEN' | skopeo login --tls-verify=false '$HOSTPORT' -u admin --password-stdin"
  STEP="skopeo inspect through the root"; check skopeo inspect --tls-verify=false "docker://$HOSTPORT/acme/alpine:1"
  STEP="skopeo list-tags"; check sh -c "skopeo list-tags --tls-verify=false 'docker://$HOSTPORT/docker-hosted/acme/alpine' | grep -q '\"1\"'"
  STEP="skopeo copy registry to registry (mirror into docker-hosted)"
  check skopeo copy --src-tls-verify=false --dest-tls-verify=false "docker://$HOSTPORT/library/alpine:$ALPINE" "docker://$HOSTPORT/docker-hosted/mirror/alpine:$ALPINE"
  STEP="skopeo copy to a local dir (pull)"; check skopeo copy --src-tls-verify=false "docker://$HOSTPORT/docker-hosted/mirror/alpine:$ALPINE" "dir:$WORK/skopeo-alpine"
  STEP="skopeo copy of the multi-arch busybox index (--all)"
  check skopeo copy --all --src-tls-verify=false --dest-tls-verify=false "docker://$HOSTPORT/library/busybox:$BUSYBOX" "docker://$HOSTPORT/docker-hosted/mirror/busybox:$BUSYBOX"
else
  skip "skopeo (not installed)"
fi

echo "== podman"
if command -v podman >/dev/null 2>&1 && podman --storage-driver=vfs info >/dev/null 2>&1; then
  P="podman --storage-driver=vfs"
  STEP="podman login"; check sh -c "echo '$APITOKEN' | $P login --tls-verify=false '$HOSTPORT' -u admin --password-stdin"
  STEP="podman pull through the root"; check $P pull --tls-verify=false "$HOSTPORT/acme/alpine:1"
  STEP="podman push to docker-hosted"; check $P push --tls-verify=false "$HOSTPORT/acme/alpine:1" "docker://$HOSTPORT/docker-hosted/acme/alpine:podman"
  STEP="podman pull what podman pushed"; check $P pull --tls-verify=false "$HOSTPORT/docker-hosted/acme/alpine:podman"
else
  skip "podman (not installed or cannot run here)"
fi

echo "== buildah"
if command -v buildah >/dev/null 2>&1 && buildah --storage-driver=vfs info >/dev/null 2>&1; then
  B="buildah --storage-driver=vfs"
  STEP="buildah login"; check sh -c "echo '$APITOKEN' | $B login --tls-verify=false '$HOSTPORT' -u admin --password-stdin"
  STEP="buildah pull through the root"; check $B pull --tls-verify=false "$HOSTPORT/acme/alpine:1"
  STEP="buildah push to docker-hosted"; check $B push --tls-verify=false "$HOSTPORT/acme/alpine:1" "docker://$HOSTPORT/docker-hosted/acme/alpine:buildah"
else
  skip "buildah (not installed or cannot run here)"
fi

fetch_tool oras https://github.com/oras-project/oras/releases/download/v1.2.0/oras_1.2.0_linux_amd64.tar.gz
fetch_tool crane https://github.com/google/go-containerregistry/releases/download/v0.20.2/go-containerregistry_Linux_x86_64.tar.gz

echo "== oras"
if command -v oras >/dev/null 2>&1; then
  mkdir -p oras && cd oras
  echo "hello from oras" > artifact.txt
  echo '{"bomFormat":"CycloneDX","components":[]}' > sbom.json
  STEP="oras login"; check sh -c "echo '$APITOKEN' | oras login --plain-http '$HOSTPORT' -u admin --password-stdin"
  STEP="oras push an artifact"; check oras push --plain-http "$HOSTPORT/docker-hosted/acme/artifacts:1" artifact.txt:text/plain
  STEP="oras attach an SBOM (referrers API)"
  check oras attach --plain-http --artifact-type application/vnd.example.sbom "$HOSTPORT/docker-hosted/acme/artifacts:1" sbom.json:application/json
  STEP="oras discover lists the referrer"
  check sh -c "oras discover --plain-http --format json '$HOSTPORT/docker-hosted/acme/artifacts:1' | grep -q 'application/vnd.example.sbom'"
  STEP="oras pull through the root"; check sh -c "mkdir -p pulled && cd pulled && oras pull --plain-http '$HOSTPORT/acme/artifacts:1' && grep -q 'hello from oras' artifact.txt"
  STEP="oras repo tags"; check sh -c "oras repo tags --plain-http '$HOSTPORT/docker-hosted/acme/artifacts' | grep -q '^1$'"
  STEP="oras repo ls at the root (catalog of the root repository)"; check sh -c "oras repo ls --plain-http '$HOSTPORT' | grep -q 'acme/artifacts'"
  STEP="oras manifest fetch by tag"; check sh -c "oras manifest fetch --plain-http '$HOSTPORT/docker-hosted/acme/artifacts:1' | grep -q 'layers'"
  STEP="oras manifest delete"; check oras manifest delete --plain-http --force "$HOSTPORT/docker-hosted/acme/artifacts:1"
  cd "$WORK"
else
  skip "oras (not installed)"
fi

echo "== crane"
if command -v crane >/dev/null 2>&1; then
  STEP="crane auth login"; check crane auth login "$HOSTPORT" -u admin -p "$APITOKEN"
  STEP="crane manifest through the root"; check sh -c "crane --insecure manifest '$HOSTPORT/acme/alpine:1' | grep -q 'layers'"
  STEP="crane copy within the registry"; check crane --insecure copy "$HOSTPORT/acme/alpine:1" "$HOSTPORT/docker-hosted/acme/alpine:crane"
  STEP="crane ls tags"; check sh -c "crane --insecure ls '$HOSTPORT/docker-hosted/acme/alpine' | grep -q crane"
  STEP="crane catalog"; check sh -c "crane --insecure catalog '$HOSTPORT' | grep -q 'acme/alpine'"
  STEP="crane digest"; check crane --insecure digest "$HOSTPORT/docker-hosted/acme/alpine:crane"
else
  skip "crane (not installed)"
fi

echo "== containerd (ctr)"
CTR_SOCK=""
for s in /run/containerd/containerd.sock /var/run/docker/containerd/containerd.sock; do [ -S "$s" ] && CTR_SOCK=$s && break; done
if command -v ctr >/dev/null 2>&1 && [ -n "$CTR_SOCK" ] && ctr -a "$CTR_SOCK" version >/dev/null 2>&1; then
  C="ctr -a $CTR_SOCK -n granary-verify"
  STEP="ctr images pull (containerd resolver, plain http, basic auth)"
  check $C images pull --plain-http --user "admin:$APITOKEN" "$HOSTPORT/acme/alpine:1"
  STEP="ctr images tag + push"
  check sh -c "$C images tag '$HOSTPORT/acme/alpine:1' '$HOSTPORT/docker-hosted/acme/alpine:ctr' && $C images push --plain-http --user 'admin:$APITOKEN' '$HOSTPORT/docker-hosted/acme/alpine:ctr'"
  STEP="ctr images pull the multi-arch busybox"
  check $C images pull --plain-http --user "admin:$APITOKEN" "$HOSTPORT/library/busybox:$BUSYBOX"
  $C images rm "$HOSTPORT/acme/alpine:1" "$HOSTPORT/docker-hosted/acme/alpine:ctr" "$HOSTPORT/library/busybox:$BUSYBOX" >/dev/null 2>&1
  # the hosts.toml of the connect page is how containerd, nerdctl and the kubelet learn that the registry speaks http
  mkdir -p "$WORK/certs.d/$HOSTPORT"
  api "$BASE/api/v1/repositories/docker-hosted/connect" | python3 -I -c 'import json,sys
print([s["code"] for s in json.load(sys.stdin) if s["id"] == "containerd-hosts"][0], end="")' > "$WORK/certs.d/$HOSTPORT/hosts.toml"
  STEP="ctr images pull and push configured only by the connect page's hosts.toml (no --plain-http)"
  check sh -c "grep -q 'http://$HOSTPORT' '$WORK/certs.d/$HOSTPORT/hosts.toml' \
    && $C images pull --hosts-dir '$WORK/certs.d' --user 'admin:$APITOKEN' '$HOSTPORT/acme/alpine:1' \
    && $C images tag '$HOSTPORT/acme/alpine:1' '$HOSTPORT/docker-hosted/acme/alpine:ctr-hosts' \
    && $C images push --hosts-dir '$WORK/certs.d' --user 'admin:$APITOKEN' '$HOSTPORT/docker-hosted/acme/alpine:ctr-hosts'"
  $C images rm "$HOSTPORT/acme/alpine:1" "$HOSTPORT/docker-hosted/acme/alpine:ctr-hosts" >/dev/null 2>&1
else
  skip "containerd ctr (no containerd socket)"
fi

echo "== nerdctl"
fetch_tool nerdctl https://github.com/containerd/nerdctl/releases/download/v2.0.3/nerdctl-2.0.3-linux-amd64.tar.gz
if command -v nerdctl >/dev/null 2>&1 && [ -n "$CTR_SOCK" ]; then
  N="nerdctl --address $CTR_SOCK --namespace granary-verify"
  STEP="nerdctl login"; check sh -c "echo '$APITOKEN' | $N login '$HOSTPORT' -u admin --password-stdin --insecure-registry"
  STEP="nerdctl pull the multi-arch busybox through the root"; check $N pull --insecure-registry "$HOSTPORT/library/busybox:$BUSYBOX"
  STEP="nerdctl tag + push to docker-hosted"
  check sh -c "$N tag '$HOSTPORT/library/busybox:$BUSYBOX' '$HOSTPORT/docker-hosted/acme/busybox:nerdctl' && $N push --insecure-registry '$HOSTPORT/docker-hosted/acme/busybox:nerdctl'"
  $N rmi -f "$HOSTPORT/library/busybox:$BUSYBOX" "$HOSTPORT/docker-hosted/acme/busybox:nerdctl" >/dev/null 2>&1
  STEP="nerdctl pull what nerdctl pushed, through the root"; check $N pull --insecure-registry "$HOSTPORT/acme/busybox:nerdctl"
  $N rmi -f "$HOSTPORT/acme/busybox:nerdctl" >/dev/null 2>&1
else
  skip "nerdctl (not installed and not downloadable, or no containerd socket)"
fi

echo "== helm"
HELM=""
mkdir -p helm/pulled; chmod -R 777 helm
if command -v helm >/dev/null 2>&1; then HELM="helm";
elif docker info >/dev/null 2>&1 && { docker image inspect alpine/helm:3.16.2 >/dev/null 2>&1 || docker pull -q alpine/helm:3.16.2 >/dev/null 2>&1; }; then
  HELM="docker run --rm --network host -v $WORK/helm:/work -w /work -e HELM_REGISTRY_CONFIG=/work/registry.json -e HELM_CACHE_HOME=/work/cache -e HELM_CONFIG_HOME=/work/config alpine/helm:3.16.2"
fi
if [ -n "$HELM" ]; then
  cd helm
  HELMHOST=${HOSTPORT/localhost/127.0.0.1}
  STEP="helm registry login"; check $HELM registry login "$HELMHOST" --insecure -u admin -p "$APITOKEN"
  STEP="helm create + package"; check sh -c "$HELM create mychart >/dev/null && $HELM package mychart"
  STEP="helm push oci:// to docker-hosted/charts"; check $HELM push mychart-0.1.0.tgz "oci://$HELMHOST/docker-hosted/charts" --plain-http
  STEP="helm pull oci:// through the root"; check $HELM pull "oci://$HELMHOST/charts/mychart" --version 0.1.0 --plain-http -d pulled
  STEP="helm show chart from the registry"; check sh -c "$HELM show chart 'oci://$HELMHOST/docker-hosted/charts/mychart' --version 0.1.0 --plain-http | grep -q 'name: mychart'"
  STEP="the pulled chart is on disk"; check test -s pulled/mychart-0.1.0.tgz
  cd "$WORK"
else
  skip "helm (not installed, no alpine/helm image)"
fi

echo "== kubelet: the containerd CRI image service in a kind node (VERIFY_KUBELET=1)"
if [ "${VERIFY_KUBELET:-0}" = 1 ] && docker info >/dev/null 2>&1; then
  command -v kind >/dev/null 2>&1 || { curl -fsSL --max-time 180 -o "$TOOLS/kind" \
    https://github.com/kubernetes-sigs/kind/releases/download/v0.24.0/kind-linux-amd64 && chmod +x "$TOOLS/kind"; }
  PORT=${HOSTPORT##*:}
  STEP="docker pull the kind node image through Granary"; check docker pull "$HOSTPORT/kindest/node:v1.31.0"
  mkdir -p "$WORK/kind/certs.d"
  printf 'kind: Cluster\napiVersion: kind.x-k8s.io/v1alpha4\ncontainerdConfigPatches:\n- |-\n  [plugins."io.containerd.grpc.v1.cri".registry]\n    config_path = "/etc/containerd/certs.d"\nnodes:\n- role: control-plane\n  extraMounts:\n  - hostPath: %s\n    containerPath: /etc/containerd/certs.d\n' \
    "$WORK/kind/certs.d" > "$WORK/kind/kind.yaml"
  # where nested containers cannot start, kubeadm fails but the node's kubelet and containerd keep running
  kind create cluster --retain --name granary-verify --image "$HOSTPORT/kindest/node:v1.31.0" --config "$WORK/kind/kind.yaml" \
    --kubeconfig "$WORK/kind/kubeconfig" --wait 60s >"$WORK/kind/create.log" 2>&1 || echo "      kind create cluster failed (see $WORK/kind/create.log); using the node's containerd"
  if docker exec granary-verify-control-plane crictl version >/dev/null 2>&1; then
    GW=$(docker network inspect kind --format '{{range .IPAM.Config}}{{.Gateway}} {{end}}' | awk '{print $1}')
    REG="$GW:$PORT"; NODE="docker exec granary-verify-control-plane"
    mkdir -p "$WORK/kind/certs.d/$REG"
    curl -fsS -H "Authorization: Bearer $TOKEN" "http://$REG/api/v1/repositories/docker-hosted/connect" | python3 -I -c 'import json,sys
print([s["code"] for s in json.load(sys.stdin) if s["id"] == "containerd-hosts"][0], end="")' > "$WORK/kind/certs.d/$REG/hosts.toml"
    api -X PUT "$BASE/api/v1/system/settings" -d '{"anonymousRead":false}' >/dev/null
    STEP="CRI pull without the pull secret is refused when anonymous read is off"
    expectfail "pull access denied" $NODE crictl pull "$REG/acme/alpine:1"
    STEP="CRI pull with wrong credentials is refused"; expectfail "401" $NODE crictl pull --creds admin:wrong "$REG/acme/alpine:1"
    STEP="CRI pull with the pull secret's credentials through the root (hosts.toml from the connect page)"
    check $NODE crictl pull --creds "admin:$APITOKEN" "$REG/acme/alpine:1"
    STEP="CRI pull of a proxied multi-arch image"; check $NODE crictl pull --creds "admin:$APITOKEN" "$REG/library/busybox:$BUSYBOX"
    api -X PUT "$BASE/api/v1/system/settings" -d '{"anonymousRead":true}' >/dev/null
  else
    skip "kubelet: the kind node did not come up (see $WORK/kind/create.log)"
  fi
  kind delete cluster --name granary-verify >/dev/null 2>&1
else
  skip "kubelet (set VERIFY_KUBELET=1 to pull through the containerd CRI of a kind node)"
fi

# leave no images of this registry behind in the local stores
docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep "^$HOSTPORT/" | xargs -r docker rmi -f >/dev/null 2>&1
if command -v podman >/dev/null 2>&1; then
  podman images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep "^$HOSTPORT/" | xargs -r podman rmi -f >/dev/null 2>&1
fi
docker logout "$HOSTPORT" >/dev/null 2>&1

echo
echo "passed=$PASS failed=$FAIL skipped=$SKIP  (work dir $WORK)"
[ "$FAIL" -eq 0 ]
