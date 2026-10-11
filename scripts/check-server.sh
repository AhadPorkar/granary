#!/usr/bin/env bash
# Checks a freshly started Granary (for example the Docker Compose stack) without any package manager: readiness,
# login, the standard repositories of every installed format (POST /api/v1/repositories/standard creates them and is
# safe to repeat), each repository's detail and Connect snippets, and the endpoints behind the dashboard and the
# system pages. Use it after `docker compose up -d` and before the client scripts (scripts/verify-all.sh).
#
# Needs curl and python3, and a running Granary whose administrator is GRANARY_USER / GRANARY_PASSWORD (default
# admin / admin123). Creates the standard repositories; changes nothing else.
# Usage: scripts/check-server.sh http://localhost:8081
set -uo pipefail
BASE=${1:-http://localhost:8081}
BASE=${BASE%/}
USER_NAME=${GRANARY_USER:-admin}
USER_PASSWORD=${GRANARY_PASSWORD:-admin123}
for tool in curl python3; do command -v "$tool" >/dev/null || { echo "missing required tool: $tool"; exit 2; }; done
WORK=$(mktemp -d /tmp/granary-check-server.XXXX)
PASS=0; FAIL=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
# json <file> <python expression over the parsed document "d">: prints the result
json() { python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); r=eval(sys.argv[2]); print(r if isinstance(r,str) else json.dumps(r))' "$1" "$2"; }
# get <name> <path> [curl options…]: GET with the session token into $WORK/<name>.json, prints the status
get() { local name=$1 path=$2; shift 2; curl -sS -o "$WORK/$name.json" -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$@" "$BASE$path"; }

code=$(curl -sS -o "$WORK/ready.json" -w '%{http_code}' "$BASE/actuator/health/readiness")
[ "$code" = 200 ] && grep -q '"UP"' "$WORK/ready.json" && ok "readiness is UP" || bad "readiness is UP (HTTP $code)"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASSWORD\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login as $USER_NAME" || { bad "login as $USER_NAME"; echo "passed=$PASS failed=$FAIL"; exit 1; }

[ "$(get info /api/v1/system/info)" = 200 ] && ok "system info: Granary $(json "$WORK/info.json" 'd["version"]'), formats $(json "$WORK/info.json" '" ".join(d["formats"])')" || bad "system info"
[ "$(get formats /api/v1/formats)" = 200 ] && ok "formats: $(json "$WORK/formats.json" 'len(d)')" || bad "formats"
NO_TEMPLATES=$(json "$WORK/formats.json" '" ".join(f["id"] for f in d if not f["templates"])')
[ -z "$NO_TEMPLATES" ] && ok "every format declares standard repositories" || bad "formats without standard repositories: $NO_TEMPLATES"

code=$(curl -sS -o "$WORK/standard.json" -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/repositories/standard")
[ "$code" = 200 ] && ok "standard repositories: $(json "$WORK/standard.json" 'len(d["created"])') created of $(json "$WORK/standard.json" 'len(d["templates"])') templates" \
  || bad "POST /api/v1/repositories/standard (HTTP $code)"
code=$(curl -sS -o "$WORK/standard2.json" -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/repositories/standard")
[ "$code" = 200 ] && [ "$(json "$WORK/standard2.json" 'len(d["created"])')" = 0 ] && ok "standard repositories again: nothing new (safe to repeat)" \
  || bad "standard repositories again (HTTP $code, created $(json "$WORK/standard2.json" 'd.get("created")' 2>/dev/null))"

[ "$(get repos /api/v1/repositories)" = 200 ] && ok "repository list: $(json "$WORK/repos.json" 'len(d)') repositories" || bad "repository list"
# every template of every format exists, with the template's format and type
python3 -I - "$WORK/formats.json" "$WORK/repos.json" "$WORK/standard.json" >"$WORK/templates.txt" <<'PY'
import json, sys
formats = json.load(open(sys.argv[1])); repos = {r["name"]: r for r in json.load(open(sys.argv[2]))}
for f in formats:
    missing = [t for t in f["templates"] if t not in repos or repos[t]["format"] != f["id"]]
    types = sorted({repos[t]["type"] for t in f["templates"] if t in repos})
    print(("FAIL" if missing else "PASS") + "  %-10s %-60s %s" % (f["id"], " ".join(f["templates"]),
          ("missing " + " ".join(missing)) if missing else "/".join(types)))
PY
while read -r line; do case "$line" in PASS*) ok "templates ${line#PASS  }";; *) bad "templates ${line#FAIL  }";; esac; done <"$WORK/templates.txt"

# each standard repository: detail with statistics, and Connect snippets for the UI's Connect panel
for name in $(json "$WORK/standard.json" '" ".join(d["templates"])'); do
  c1=$(get "repo-$name" "/api/v1/repositories/$name")
  c2=$(get "connect-$name" "/api/v1/repositories/$name/connect")
  if [ "$c1" = 200 ] && [ "$c2" = 200 ] && [ "$(json "$WORK/repo-$name.json" '"statistics" in d')" = true ]; then
    n=$(json "$WORK/connect-$name.json" 'len(d)')
    [ "$n" -gt 0 ] && ok "$name: detail and $n connect snippets" || bad "$name: no connect snippets"
  else
    bad "$name: detail HTTP $c1, connect HTTP $c2"
  fi
done

# dashboard and system pages
[ "$(get status /api/v1/system/status)" = 200 ] && ok "system status: $(json "$WORK/status.json" 'd["repositories"]') repositories, $(json "$WORK/status.json" 'len(d["upstreams"])') upstreams, blob stores $(json "$WORK/status.json" '" ".join(s["name"] for s in d["blobStores"])')" || bad "system status"
proxies=$(json "$WORK/repos.json" 'sum(1 for r in d if r["type"] == "PROXY")')
[ "$(json "$WORK/status.json" 'len(d["upstreams"])')" = "$proxies" ] && ok "status lists every proxy upstream ($proxies)" || bad "status upstreams vs $proxies proxies"
[ "$(get settings /api/v1/system/settings)" = 200 ] && ok "settings: $(cat "$WORK/settings.json")" || bad "settings"
[ "$(get search '/api/v1/search?q=a')" = 200 ] && ok "component search" || bad "component search"
[ "$(get users /api/v1/users)" = 200 ] && ok "users" || bad "users"
[ "$(get roles /api/v1/roles)" = 200 ] && ok "roles" || bad "roles"
[ "$(get tokens /api/v1/tokens)" = 200 ] && ok "tokens" || bad "tokens"
[ "$(get me /api/v1/auth/me)" = 200 ] && ok "current user" || bad "current user"
code=$(curl -sS -o "$WORK/index.html" -w '%{http_code}' "$BASE/")
[ "$code" = 200 ] && grep -qi '<html' "$WORK/index.html" && ok "web UI index" || bad "web UI index (HTTP $code)"

echo; echo "passed=$PASS failed=$FAIL  (work dir $WORK)"
[ "$FAIL" = 0 ]
