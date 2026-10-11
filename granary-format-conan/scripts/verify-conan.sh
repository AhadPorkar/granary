#!/usr/bin/env bash
# End-to-end verification of the Conan format with the real conan 2 client, including an offline run against the
# Granary cache.
#
# Publishes a header-only library (acme/1.0, also as acme/1.0@team/stable, with recipe metadata) and a compiled
# CMake library that depends on it (widget/1.0) to conan-hosted, then installs them through conan-group from empty
# caches and builds an application against them. Also checks a CI-style upload with credentials from environment
# variables, removal, a proxy whose upstream is this Granary's conan-hosted (chained proxy, logging in with the
# token exchange), a proxy of a private conan_server (the reference server from "pip install conan-server") when
# conan_server is installed and Granary runs on this machine, Conan 1.x when CONAN1 names a conan 1.x executable
# (for example from "pip install 'conan<2'" in a virtualenv), and zlib from ConanCenter through the group when the
# server can reach center2.conan.io. Finally the server is switched to offline mode and everything is installed
# again into empty caches.
#
# Needs curl, python3, conan 2, cmake and a C++ compiler on the PATH and a running Granary with admin/admin123.
# conan_server and Conan 1.x are taken from the PATH and CONAN1; when they are missing they are installed with pip
# into virtual environments inside the work directory (CONAN_VERIFY_INSTALL=0 skips those parts instead).
# Every conan command runs with its own CONAN_HOME inside the work directory, so the caller's configuration is
# never touched.
# Usage: [CONAN1=/path/to/conan1] [CONAN_VERIFY_INSTALL=0] verify-conan.sh http://localhost:8081
set -uo pipefail
BASE=${1:-http://localhost:8081}
BASE=${BASE%/}
HOSTPORT=${BASE#http://}; HOSTPORT=${HOSTPORT#https://}; HOSTPORT=${HOSTPORT%%/*}
HOSTNAME_ONLY=${HOSTPORT%%:*}
WORK=$(mktemp -d /tmp/granary-verify-conan.XXXX)
cd "$WORK"
PASS=0; FAIL=0; SKIP=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
skip() { echo "SKIP  $1"; SKIP=$((SKIP+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# refused <pattern> <command…>: the command must fail and say why (its output must match the pattern)
refused(){ local pattern=$1; shift
  if "$@" >"$WORK/last.log" 2>&1; then bad "$STEP (succeeded)"; elif grep -Eq "$pattern" "$WORK/last.log"; then ok "$STEP"
  else bad "$STEP"; tail -10 "$WORK/last.log"; fi; }
# checkin <dir> <command…>: check in another directory without a subshell, so the counters survive
checkin(){ local dir=$1; shift; pushd "$dir" >/dev/null || { bad "$STEP (no $dir)"; return; }; check "$@"; popd >/dev/null; }
status(){ curl -s -o /dev/null -w '%{http_code}' "$@"; }
for tool in curl python3 conan cmake; do command -v "$tool" >/dev/null || { echo "missing required tool: $tool"; exit 2; }; done
conan --version | grep -q 'version 2\.' || { echo "conan 2 is required (found: $(conan --version))"; exit 2; }

# nothing from the caller's environment may log in or point conan elsewhere
CONAN1=${CONAN1:-}
INSTALL=${CONAN_VERIFY_INSTALL:-1}
for v in $(env | sed -n 's/^\(CONAN_[A-Z0-9_]*\)=.*/\1/p'); do unset "$v"; done

# venv <dir> <pip requirement>: a virtual environment with one package, for the optional clients
venv() {
  python3 -m venv "$1" >/dev/null 2>&1 && "$1/bin/pip" install -q "$2" >"$1.log" 2>&1
}
if ! command -v conan_server >/dev/null && [ "$INSTALL" = 1 ]; then
  echo "== installing conan_server (pip install conan-server) into $WORK/venv-server"
  venv "$WORK/venv-server" conan-server && export PATH="$PATH:$WORK/venv-server/bin" || echo "   (failed, see $WORK/venv-server.log)"
fi
if [ -z "$CONAN1" ] && [ "$INSTALL" = 1 ]; then
  echo "== installing Conan 1.x (pip install 'conan<2') into $WORK/venv-conan1"
  venv "$WORK/venv-conan1" 'conan<2' && CONAN1="$WORK/venv-conan1/bin/conan" || echo "   (failed, see $WORK/venv-conan1.log)"
fi
export CONAN_NON_INTERACTIVE=1
# home <name>: a fresh conan home with a detected profile and none of the default remotes
home() {
  export CONAN_HOME="$WORK/home-$1"
  rm -rf "$CONAN_HOME"
  conan profile detect --force >/dev/null 2>&1 && conan remote remove conancenter >/dev/null 2>&1
  printf 'core:non_interactive=True\n' >> "$CONAN_HOME/global.conf"
}
listjson(){ python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)))'; }

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null \
  && ok "standard repositories (conancenter, conan-hosted, conan-group)" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify-conan"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
# the repositories and the user an earlier run on this server created: removed, so the run can be repeated (and the
# proxies start without the cache of an upstream that is gone)
for repo in conan-readonly conan-chained conan-upstream; do
  curl -fsS -o /dev/null -X DELETE "$BASE/api/v1/repositories/$repo" -H "Authorization: Bearer $TOKEN" 2>/dev/null
done
curl -fsS -o /dev/null -X DELETE "$BASE/api/v1/users/conan-chain" -H "Authorization: Bearer $TOKEN" 2>/dev/null
HOSTED="$BASE/repository/conan-hosted"
GROUP="$BASE/repository/conan-group"
STEP="v1/ping advertises revisions"
curl -fsS -D "$WORK/ping.headers" -o /dev/null "$GROUP/v1/ping" && grep -qi '^X-Conan-Server-Capabilities:.*revisions' "$WORK/ping.headers" \
  && ok "$STEP" || bad "$STEP"

echo "== Publish: conan create and conan upload to conan-hosted"
home publisher
STEP="conan remote add granary-hosted"; check conan remote add granary-hosted "$HOSTED"
STEP="conan remote add granary (the group)"; check conan remote add granary "$GROUP"
STEP="conan remote login with an API token as password"; check conan remote login granary-hosted admin -p "$APITOKEN"
STEP="conan remote auth (check_credentials)"; check sh -c 'conan remote auth granary-hosted 2>&1 | grep -q admin'
conan new header_lib -d name=acme -d version=1.0 -o acme >/dev/null 2>&1
STEP="conan create acme/1.0 (header-only)"; check conan create acme
STEP="conan create acme/1.0@team/stable"; check conan create acme --user=team --channel=stable
conan new cmake_lib -d name=widget -d version=1.0 -d requires=acme/1.0 -o widget >/dev/null 2>&1
STEP="conan create widget/1.0 (compiled, requires acme/1.0)"; check conan create widget
META=$(conan cache path acme/1.0 --folder=metadata 2>/dev/null)
mkdir -p "$META/logs" && echo "first build log" > "$META/logs/build.log"
STEP="conan upload everything to conan-hosted (with recipe metadata)"
check conan upload "*" -r granary-hosted --metadata="*" --confirm
STEP="conan upload again skips what the server already has"
check sh -c 'conan upload "*" -r granary-hosted --confirm 2>&1 | tee /dev/stderr | grep -q "already in server, skipping"'
echo "second build log" > "$META/logs/build.log"
STEP="changed metadata files can be uploaded again"; check conan upload acme/1.0 -r granary-hosted --metadata="*" --confirm
STEP="conan search through the group"; check sh -c 'conan search "*" -r granary | grep -q "widget/1.0" && conan search acme -r granary | grep -q "acme/1.0@team/stable"'
STEP="conan list shows recipe and package revisions with their settings"
conan list "widget/1.0#*:*#*" -r granary --format=json >"$WORK/list.json" 2>"$WORK/last.log" \
  && python3 - "$WORK/list.json" <<'PY' && ok "$STEP" || bad "$STEP"
import json, sys
remote = json.load(open(sys.argv[1]))["granary"]
revisions = remote["widget/1.0"]["revisions"]
assert revisions, "no recipe revision"
for rrev in revisions.values():
    packages = rrev["packages"]
    assert packages, "no package"
    for pkg in packages.values():
        assert pkg["revisions"], "no package revision"
        assert pkg["info"]["settings"]["os"], "no settings"
PY

echo "== Upload with credentials from the environment (CI style: the password login mints a token)"
home ci
conan remote add granary-hosted "$HOSTED" >/dev/null 2>&1
conan new header_lib -d name=gizmo -d version=0.1 -o gizmo >/dev/null 2>&1
STEP="conan create gizmo/0.1"; check conan create gizmo
STEP="parallel conan upload with CONAN_LOGIN_USERNAME_GRANARY_HOSTED and CONAN_PASSWORD_GRANARY_HOSTED"
check env CONAN_LOGIN_USERNAME_GRANARY_HOSTED=admin CONAN_PASSWORD_GRANARY_HOSTED=admin123 \
  conan upload "gizmo/0.1" -r granary-hosted --confirm -cc core.upload:parallel=4
STEP="gizmo/0.1 is on the server"; [ "$(status "$HOSTED/v2/conans/gizmo/0.1/_/_/latest")" = 200 ] && ok "$STEP" || bad "$STEP"
STEP="conan remove gizmo/0.1 from conan-hosted"; check conan remove "gizmo/0.1" -r granary-hosted -c
STEP="gizmo/0.1 is gone"; code=$(status "$HOSTED/v2/conans/gizmo/0.1/_/_/latest"); [ "$code" = 404 ] && ok "$STEP ($code)" || bad "$STEP ($code)"
STEP="without credentials an upload is refused"
home anonymous
conan remote add granary-hosted "$HOSTED" >/dev/null 2>&1
conan new header_lib -d name=gizmo -d version=0.2 -o gizmo2 >/dev/null 2>&1 && conan create gizmo2 >/dev/null 2>&1
refused 'needs authentication' conan upload "gizmo/0.2" -r granary-hosted --confirm

echo "== Groups are read-only, and odd requests are cheap"
# a group of conan-hosted alone: through conan-group the client would first need conancenter to answer
STEP="create conan-readonly (a group of conan-hosted)"
check curl -fsS -X POST "$BASE/api/v1/repositories" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"conan-readonly","format":"conan","type":"GROUP","members":["conan-hosted"]}'
home group-upload
conan remote add granary "$BASE/repository/conan-readonly" >/dev/null 2>&1
conan remote login granary admin -p "$APITOKEN" >/dev/null 2>&1
conan new header_lib -d name=grouptry -d version=1.0 -o grouptry >/dev/null 2>&1 && conan create grouptry >/dev/null 2>&1
STEP="conan upload through the group is refused and names the hosted member"
refused "upload to its hosted member 'conan-hosted'" conan upload "grouptry/1.0" -r granary --confirm
STEP="the refused upload stored nothing"
code=$(status "$HOSTED/v2/conans/grouptry/1.0/_/_/latest"); [ "$code" = 404 ] && ok "$STEP ($code)" || bad "$STEP ($code)"
STEP="a search pattern full of wildcards is answered at once"
t=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 20 \
  "$GROUP/v2/conans/search?q=$(printf '*%%3F%.0s' $(seq 1 16))X")
[ "${t%% *}" = 200 ] && awk -v s="${t#* }" 'BEGIN { exit !(s < 5) }' && ok "$STEP ($t s)" || bad "$STEP ($t)"
STEP="an encoded slash in a reference is a 400"
code=$(status -X PUT -H "Authorization: Bearer $APITOKEN" --data-binary x "$HOSTED/v2/conans/a%2Fb/1.0/_/revisions/0/files/conanfile.py")
[ "$code" = 400 ] && ok "$STEP ($code)" || bad "$STEP ($code)"
STEP="an overlong version is a 400, not a server error"
code=$(status -X PUT -H "Authorization: Bearer $APITOKEN" --data-binary x \
  "$HOSTED/v2/conans/zlib/$(printf 'v%.0s' $(seq 1 300))/_/_/revisions/0/files/conanfile.py")
[ "$code" = 400 ] && ok "$STEP ($code)" || bad "$STEP ($code)"

echo "== Install through conan-group into an empty cache"
home consumer
conan remote add granary "$GROUP" >/dev/null 2>&1
STEP="conan install --requires=widget/1.0 (widget and acme from conan-hosted)"; check conan install --requires=widget/1.0 -r granary
STEP="conan install of a version range (acme/[>=1.0 <2])"; check conan install --requires="acme/[>=1.0 <2]" -r granary
STEP="conan install --requires=acme/1.0@team/stable"; check conan install --requires=acme/1.0@team/stable -r granary
STEP="conan download of the recipe metadata"
check conan download acme/1.0 -r granary --metadata="*"
STEP="downloaded metadata is the latest upload"
grep -q "second build log" "$(conan cache path acme/1.0 --folder=metadata 2>/dev/null)/logs/build.log" 2>/dev/null && ok "$STEP" || bad "$STEP"
conan new cmake_exe -d name=app -d version=0.1 -d requires=widget/1.0 -o app >/dev/null 2>&1
STEP="an application builds and links against widget/1.0"; check conan build app -r granary
STEP="the application runs"; check sh -c 'exe=$(find app/build -type f -name app -perm -u+x | head -1); "$exe" | grep -q "widget/1.0"'

echo "== Conan 1.x (with revisions enabled it speaks the same v2 API)"
# c1home <name>: a fresh Conan 1.x home with no remotes
c1home() {
  export CONAN_USER_HOME="$WORK/c1-$1"
  rm -rf "$CONAN_USER_HOME"
  "$CONAN1" config init >/dev/null 2>&1 && "$CONAN1" remote clean >/dev/null 2>&1
  "$CONAN1" config set general.non_interactive=True >/dev/null 2>&1
}
C1=0
if [ -z "$CONAN1" ]; then
  skip "Conan 1.x: set CONAN1 to a conan 1.x executable"
elif ! "$CONAN1" --version 2>/dev/null | grep -q 'version 1\.'; then
  bad "Conan 1.x: $CONAN1 is not a conan 1.x executable"
else
  C1=1
  c1home publisher
  "$CONAN1" remote add granary "$GROUP" >/dev/null 2>&1
  STEP="conan 1.x without revisions is told to enable them (only_v2)"
  refused 'only works with revisions enabled' "$CONAN1" search "*" -r granary
  "$CONAN1" config set general.revisions_enabled=1 >/dev/null 2>&1
  "$CONAN1" remote add granary-hosted "$HOSTED" >/dev/null 2>&1
  STEP="conan 1.x: conan user with an API token"; check "$CONAN1" user -r granary-hosted -p "$APITOKEN" admin
  mkdir -p c1pkg && pushd c1pkg >/dev/null && "$CONAN1" new hello1/0.1 --header >/dev/null 2>&1; popd >/dev/null
  STEP="conan 1.x: conan create hello1/0.1"; checkin c1pkg "$CONAN1" create .
  STEP="conan 1.x: conan upload --all to conan-hosted"; check "$CONAN1" upload "hello1/0.1@" -r granary-hosted --all --confirm
  STEP="conan 1.x: upload again is skipped"
  check sh -c '"$1" upload "hello1/0.1@" -r granary-hosted --all --confirm 2>&1 | grep -q "Recipe is up to date, upload skipped"' sh "$CONAN1"
  c1home consumer
  "$CONAN1" config set general.revisions_enabled=1 >/dev/null 2>&1
  "$CONAN1" remote add granary "$GROUP" >/dev/null 2>&1
  STEP="conan 1.x: conan install hello1/0.1@ through the group"; check "$CONAN1" install hello1/0.1@ -r granary
  STEP="conan 1.x: conan search lists the binary as up to date with its recipe"
  check sh -c '"$1" search hello1/0.1@ -r granary 2>&1 | grep -q "Outdated from recipe: False"' sh "$CONAN1"
  unset CONAN_USER_HOME
fi

echo "== Chained proxy: a proxy whose upstream is this Granary's conan-hosted, with its own login"
curl -fsS -X POST "$BASE/api/v1/users" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"conan-chain","password":"chain-pass-123","roles":["reader"]}' >/dev/null && ok "create user conan-chain (reader)" || bad "create user conan-chain"
STEP="create conan-chained (remote credentials of conan-chain)"
check curl -fsS -X POST "$BASE/api/v1/repositories" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"conan-chained\",\"format\":\"conan\",\"type\":\"PROXY\",\"remoteUrl\":\"$HOSTED\",\"remoteUsername\":\"conan-chain\",\"remotePassword\":\"chain-pass-123\"}"
home chained
conan remote add granary-chained "$BASE/repository/conan-chained" >/dev/null 2>&1
STEP="conan install --requires=widget/1.0 through the chained proxy"; check conan install --requires=widget/1.0 -r granary-chained
STEP="conan search through the chained proxy"; check sh -c 'conan search widget -r granary-chained | grep -q "widget/1.0"'
STEP="the chained proxy logged in with the token exchange (conan-chain owns a 'conan' token)"
CHAIN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"conan-chain","password":"chain-pass-123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -fsS "$BASE/api/v1/tokens" -H "Authorization: Bearer $CHAIN" | grep -q '"name":"conan"' && ok "$STEP" || bad "$STEP"

echo "== Proxy of a private conan_server (anonymous reads refused, so the proxy has to log in)"
UPSTREAM=0; CS_PID=""
trap '[ -n "$CS_PID" ] && kill "$CS_PID" 2>/dev/null' EXIT
case "$HOSTNAME_ONLY" in localhost|127.*) LOCAL=1;; *) LOCAL=0;; esac
if ! command -v conan_server >/dev/null; then
  skip "proxy of conan_server: conan_server is not installed (pip install conan-server)"
elif [ "$LOCAL" = 0 ]; then
  skip "proxy of conan_server: Granary at $HOSTPORT cannot reach a conan_server started on this machine"
else
  UPSTREAM=1
  CS_PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
  CS="http://localhost:$CS_PORT"
  mkdir -p conan-server
  cat > conan-server/server.conf <<CONF
[server]
jwt_secret: $(python3 -c 'import secrets; print(secrets.token_hex(16))')
jwt_expire_minutes: 120
ssl_enabled: False
port: $CS_PORT
public_port:
host_name: localhost
authorize_timeout: 1800
disk_storage_path: ./data
disk_authorize_timeout: 1800
updown_secret: $(python3 -c 'import secrets; print(secrets.token_hex(16))')

[write_permissions]
*/*@*/*: upstream

[read_permissions]
*/*@*/*: ?

[users]
upstream: upstream-pass
CONF
  conan_server -d "$WORK/conan-server" >"$WORK/conan-server.log" 2>&1 &
  CS_PID=$!
  for _ in $(seq 1 30); do curl -fsS -o /dev/null "$CS/v1/ping" 2>/dev/null && break; sleep 1; done
  STEP="conan_server is up on port $CS_PORT"; check curl -fsS "$CS/v1/ping"
  home upstream-publisher
  conan remote add upstream "$CS" >/dev/null 2>&1
  conan new header_lib -d name=upstreamlib -d version=2.0 -o upstreamlib >/dev/null 2>&1
  STEP="conan create upstreamlib/2.0"; check conan create upstreamlib
  STEP="conan upload upstreamlib/2.0 to conan_server"
  check env CONAN_LOGIN_USERNAME_UPSTREAM=upstream CONAN_PASSWORD_UPSTREAM=upstream-pass conan upload "upstreamlib/2.0" -r upstream --confirm
  STEP="conan_server refuses anonymous reads"
  code=$(status "$CS/v2/conans/upstreamlib/2.0/_/_/latest"); [ "$code" = 401 ] && ok "$STEP ($code)" || bad "$STEP ($code)"
  STEP="create conan-upstream (proxy of conan_server with its credentials)"
  check curl -fsS -X POST "$BASE/api/v1/repositories" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"name\":\"conan-upstream\",\"format\":\"conan\",\"type\":\"PROXY\",\"remoteUrl\":\"$CS\",\"remoteUsername\":\"upstream\",\"remotePassword\":\"upstream-pass\"}"
  home upstream-consumer
  conan remote add granary-upstream "$BASE/repository/conan-upstream" >/dev/null 2>&1
  STEP="conan install upstreamlib/2.0 through the proxy (token login at conan_server)"
  check conan install --requires=upstreamlib/2.0 -r granary-upstream
  STEP="conan search and conan list through the proxy"
  check sh -c 'conan search upstreamlib -r granary-upstream | grep -q "upstreamlib/2.0" && conan list "upstreamlib/2.0#*:*#*" -r granary-upstream | grep -q revisions'
  kill "$CS_PID" 2>/dev/null; wait "$CS_PID" 2>/dev/null; CS_PID=""
  home upstream-down
  conan remote add granary-upstream "$BASE/repository/conan-upstream" >/dev/null 2>&1
  STEP="conan_server stopped: conan install upstreamlib/2.0 from the Granary cache"
  check conan install --requires=upstreamlib/2.0 -r granary-upstream
  # a new secret invalidates every token conan_server issued, including the one Granary holds
  sed -i "s/^jwt_secret: .*/jwt_secret: $(python3 -c 'import secrets; print(secrets.token_hex(16))')/" conan-server/server.conf
  conan_server -d "$WORK/conan-server" >>"$WORK/conan-server.log" 2>&1 &
  CS_PID=$!
  for _ in $(seq 1 30); do curl -fsS -o /dev/null "$CS/v1/ping" 2>/dev/null && break; sleep 1; done
  export CONAN_HOME="$WORK/home-upstream-publisher"
  conan new header_lib -d name=upstreamlib -d version=2.1 -o upstreamlib21 >/dev/null 2>&1
  STEP="conan_server restarted with a new secret: upload upstreamlib/2.1"
  check sh -c 'conan create upstreamlib21 && env CONAN_LOGIN_USERNAME_UPSTREAM=upstream CONAN_PASSWORD_UPSTREAM=upstream-pass conan upload "upstreamlib/2.1" -r upstream --confirm'
  home upstream-relogin
  conan remote add granary-upstream "$BASE/repository/conan-upstream" >/dev/null 2>&1
  STEP="the proxy logs in again after its token was rejected: conan install upstreamlib/2.1"
  check conan install --requires=upstreamlib/2.1 -r granary-upstream
  kill "$CS_PID" 2>/dev/null; wait "$CS_PID" 2>/dev/null; CS_PID=""
fi

echo "== ConanCenter through the group (conancenter proxy)"
CENTER=0
code=$(status "$BASE/repository/conancenter/v2/conans/zlib/1.3.1/_/_/latest")
if [ "$code" = 200 ]; then
  CENTER=1
  export CONAN_HOME="$WORK/home-consumer"
  STEP="conan install --requires=zlib/1.3.1 from ConanCenter (prebuilt or --build=missing)"
  check conan install --requires=zlib/1.3.1 -r granary --build=missing
else
  skip "ConanCenter: the server cannot reach center2.conan.io (conancenter answered HTTP $code)"
fi

echo "== Offline: install everything again with upstream access disabled and empty conan caches"
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"offlineMode":true}' >/dev/null && ok "offline mode on" || bad "offline mode on"
home offline-group
conan remote add granary "$GROUP" >/dev/null 2>&1
STEP="offline conan install widget/1.0 through the group"; check conan install --requires=widget/1.0 -r granary
STEP="offline conan install of a version range"; check conan install --requires="acme/[>=1.0 <2]" -r granary
if [ "$CENTER" = 1 ]; then
  STEP="offline conan install zlib/1.3.1 from the conancenter cache"; check conan install --requires=zlib/1.3.1 -r granary --build=missing
fi
if [ "$UPSTREAM" = 1 ]; then
  home offline-upstream
  conan remote add granary-upstream "$BASE/repository/conan-upstream" >/dev/null 2>&1
  STEP="offline conan install upstreamlib/2.0 through the conan_server proxy (from its cache)"
  check conan install --requires=upstreamlib/2.0 -r granary-upstream
fi
if [ "$C1" = 1 ]; then
  c1home offline
  "$CONAN1" config set general.revisions_enabled=1 >/dev/null 2>&1
  "$CONAN1" remote add granary "$GROUP" >/dev/null 2>&1
  STEP="offline conan 1.x install hello1/0.1@ through the group"; check "$CONAN1" install hello1/0.1@ -r granary
  unset CONAN_USER_HOME
fi
home offline-chained
conan remote add granary-chained "$BASE/repository/conan-chained" >/dev/null 2>&1
STEP="offline conan install widget/1.0 through the chained proxy (from its cache)"; check conan install --requires=widget/1.0 -r granary-chained
STEP="offline uncached recipe through the chained proxy is a clean 503"
code=$(status "$BASE/repository/conan-chained/v2/conans/nosuch/1.0/_/_/latest"); [ "$code" = 503 ] && ok "$STEP ($code)" || bad "$STEP ($code)"
STEP="offline uncached recipe through the group is a clean 503"
code=$(status "$GROUP/v2/conans/nosuch/1.0/_/_/latest"); [ "$code" = 503 ] && ok "$STEP ($code)" || bad "$STEP ($code)"
STEP="offline conan search through the chained proxy, asked before, is served from its cache"
check sh -c 'conan search widget -r granary-chained | grep -q "widget/1.0"'
STEP="offline search never asked through the chained proxy is a 503, not an empty result"
code=$(status "$BASE/repository/conan-chained/v2/conans/search?q=nosuch"); [ "$code" = 503 ] && ok "$STEP ($code)" || bad "$STEP ($code)"
STEP="offline conan install of an uncached recipe fails and names the offline upstream"
refused 'is in offline mode' conan install --requires=nosuch/1.0 -r granary-chained -cc core.net.http:max_retries=0
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"offlineMode":false}' >/dev/null && ok "offline mode off" || bad "offline mode off"

echo; echo "passed=$PASS failed=$FAIL skipped=$SKIP  (work dir $WORK)"
[ "$FAIL" = 0 ]
