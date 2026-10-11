#!/usr/bin/env bash
# Offline drill: proves that a Granary which once served the dependencies keeps serving them with no internet.
#
#   1. fill:    installs public packages through the standard groups with the real clients, from empty client caches:
#               Maven (commons-text and its parent POMs), npm (chalk 4 and its dependencies), NuGet (Newtonsoft.Json),
#               PyPI (requests and its dependencies), Cargo (itoa), Go (rsc.io/quote and its dependencies), Docker
#               (library/hello-world through the root) and RubyGems (rack, gem install);
#   2. offline: switches the global offline mode on (every proxy repository stops talking to its upstream) and, when
#               RESTART_CMD is set, restarts the server and waits for it, to show that the cache and the offline
#               switch survive a restart (e.g. RESTART_CMD="docker compose restart granary");
#   3. check:   wipes every client cache (local Maven repository, npm cache and node_modules, NuGet packages, pip cache
#               and virtualenv, CARGO_HOME, Go module cache, the Docker image, GEM_HOME) and installs everything again,
#               now only from Granary's cache; an uncached artifact must fail with a clean 503;
#   4. switches offline mode off again (also when the drill fails half-way).
#
# Needs curl and a running Granary whose proxies can reach the public registries during the fill, with admin/admin123
# (GRANARY_USER/GRANARY_PASSWORD). A client that is not installed (mvn, npm, dotnet, python3, cargo, go, docker, gem)
# is reported as SKIP. Every client uses its own caches and configuration inside the work directory, so the caller's
# setup is neither used nor changed (Docker: the image is pulled into the local daemon and removed again).
# Usage: [CLIENTS="maven npm nuget pypi cargo go docker rubygems"] [RESTART_CMD=...] offline-drill.sh http://localhost:8081
set -uo pipefail
BASE=${1:-http://localhost:8081}
BASE=${BASE%/}
HOSTPORT=${BASE#http://}; HOSTPORT=${HOSTPORT#https://}; HOSTPORT=${HOSTPORT%%/*}
CLIENTS=${CLIENTS:-maven npm nuget pypi cargo go docker rubygems}
USER_NAME=${GRANARY_USER:-admin}
USER_PASSWORD=${GRANARY_PASSWORD:-admin123}
WORK=$(mktemp -d /tmp/granary-offline-drill.XXXX)
cd "$WORK" || exit 2
PASS=0; FAIL=0; SKIP=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
skip() { echo "SKIP  $1"; SKIP=$((SKIP+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# want <client> <tool…>: the client was selected and its tools are installed
want() {
  local client=$1 tool; shift
  case " ${CLIENTS//,/ } " in *" $client "*) ;; *) return 1 ;; esac
  for tool in "$@"; do command -v "$tool" >/dev/null || { skip "$client: $tool is not installed"; return 1; }; done
}
command -v curl >/dev/null || { echo "missing required tool: curl"; exit 2; }

login() {
  curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASSWORD\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/'
}
offline() {
  curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $(login)" -H 'Content-Type: application/json' \
    -d "{\"offlineMode\":$1}" >/dev/null
}
TOKEN=$(login)
[ -n "$TOKEN" ] && ok "login" || { bad "login"; echo "passed=$PASS failed=$FAIL skipped=$SKIP"; exit 1; }
trap 'offline false' EXIT
offline false
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null && ok "standard repositories" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"offline-drill"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"

# ---------------------------------------------------------------------------------------------- client setups
M=0; N=0; G=0; P=0; C=0; GO=0; D=0; R=0
if want maven mvn java; then M=1
  cat > settings.xml <<EOF
<settings><mirrors><mirror><id>granary</id><mirrorOf>*</mirrorOf><url>$BASE/repository/maven-public/</url></mirror></mirrors></settings>
EOF
  maven() { mvn -B -q -s "$WORK/settings.xml" -Dmaven.repo.local="$WORK/m2" dependency:get -Dartifact=org.apache.commons:commons-text:1.12.0; }
fi
if want npm npm node; then N=1
  mkdir -p npm && printf '{ "name": "drill", "version": "1.0.0", "private": true }\n' > npm/package.json
  printf 'registry=%s/repository/npm-group/\n' "$BASE" > npm/.npmrc
  npmi() { (cd npm && npm_config_cache="$WORK/npm-cache" npm install --no-audit --no-fund --loglevel=error chalk@4.1.2 && test -f node_modules/supports-color/package.json); }
fi
if want nuget dotnet; then G=1
  export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 NUGET_PACKAGES="$WORK/nuget-packages" \
    NUGET_HTTP_CACHE_PATH="$WORK/nuget-http-cache"
  mkdir -p nuget && cat > nuget/nuget.config <<EOF
<?xml version="1.0" encoding="utf-8"?>
<configuration><packageSources><clear /><add key="granary" value="$BASE/repository/nuget-group/index.json" /></packageSources></configuration>
EOF
  cat > nuget/Drill.csproj <<'EOF'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup><TargetFramework>netstandard2.0</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include="Newtonsoft.Json" Version="13.0.3" /></ItemGroup>
</Project>
EOF
  nugeti() { dotnet restore "$WORK/nuget/Drill.csproj" --force && test -d "$NUGET_PACKAGES/newtonsoft.json/13.0.3"; }
fi
if want pypi python3; then P=1
  export PIP_CONFIG_FILE=/dev/null PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_INPUT=1 PIP_CACHE_DIR="$WORK/pip-cache"
  pipi() {
    rm -rf "$WORK/venv" && python3 -m venv "$WORK/venv" \
      && "$WORK/venv/bin/python" -m pip install --index-url "$BASE/repository/pypi-group/simple/" requests==2.32.3 \
      && "$WORK/venv/bin/python" -c 'import requests, urllib3, idna, certifi, charset_normalizer'
  }
fi
if want cargo cargo; then C=1
  export CARGO_HOME="$WORK/cargo-home" CARGO_TARGET_DIR="$WORK/cargo-target" CARGO_TERM_PROGRESS_WHEN=never
  for v in $(env | sed -n -E 's/^(CARGO_REGISTRIES_[A-Z0-9_]+|CARGO_REGISTRY_[A-Z0-9_]+|CARGO_SOURCE_[A-Z0-9_]+|CARGO_NET_OFFLINE)=.*/\1/p'); do unset "$v"; done
  cargo_home() {
    mkdir -p "$CARGO_HOME" && cat > "$CARGO_HOME/config.toml" <<EOF
[source.crates-io]
replace-with = "granary"
[source.granary]
registry = "sparse+$BASE/repository/cargo-group/index/"
EOF
  }
  mkdir -p cargo/src && printf 'fn main() { let mut b = itoa::Buffer::new(); println!("{}", b.format(42)); }\n' > cargo/src/main.rs
  printf '[package]\nname = "drill"\nversion = "0.1.0"\nedition = "2021"\n\n[dependencies]\nitoa = "=1.0.11"\n' > cargo/Cargo.toml
  cargoi() { cargo_home && cargo run --quiet --manifest-path "$WORK/cargo/Cargo.toml" | grep -qx 42; }
fi
if want go go; then GO=1
  unset GOPRIVATE GONOPROXY GONOSUMDB GONOSUMCHECK GOINSECURE GOSUMDB GOFLAGS
  export GOENV=off GOTOOLCHAIN=local GOTELEMETRY=off GOFLAGS=-mod=mod GOPROXY="$BASE/repository/go-group" \
    GOMODCACHE="$WORK/gomodcache" GOCACHE="$WORK/gocache" GOPATH="$WORK/gopath"
  mkdir -p gomod && printf 'module example.com/drill\n\ngo 1.21\n\nrequire rsc.io/quote v1.5.2\n' > gomod/go.mod
  printf 'package main\n\nimport (\n\t"fmt"\n\n\t"rsc.io/quote"\n)\n\nfunc main() { fmt.Println(quote.Hello()) }\n' > gomod/main.go
  # go.sum is recorded online (checked against sum.golang.org through Granary) and kept for the offline run
  goi() { (cd "$WORK/gomod" && go run . | grep -q .); }
fi
if want docker docker; then D=1
  export DOCKER_CONFIG="$WORK/docker-config"; mkdir -p "$DOCKER_CONFIG"
  IMAGE="$HOSTPORT/library/hello-world:latest"
  dockeri() { docker pull -q "$IMAGE" && docker run --rm "$IMAGE" | grep -q 'Hello from Docker'; }
fi
if want rubygems gem ruby; then R=1
  export GEM_HOME="$WORK/gems" GEM_PATH="$WORK/gems" GEM_SPEC_CACHE="$WORK/gem-specs"; mkdir -p "$WORK/home"
  # HOME only for gem (~/.gemrc, ~/.gem): the other clients keep theirs (rustup finds its toolchains under it)
  gemi() { HOME="$WORK/home" gem install --clear-sources --source "$BASE/repository/rubygems-group/" --no-document rack:3.1.8 \
             && HOME="$WORK/home" ruby -e 'gem "rack", "3.1.8"; require "rack"'; }
fi
[ -n "${D:-}" ] && [ "$D" = 1 ] && docker rmi -f "$IMAGE" >/dev/null 2>&1

# -------------------------------------------------------------------------------------------- 1. fill (online)
echo "== 1. fill the caches through the groups (online)"
[ $M = 1 ] && { STEP="maven: commons-text through maven-public"; check maven; }
[ $N = 1 ] && { STEP="npm: chalk@4 and its dependencies through npm-group"; check npmi; }
[ $G = 1 ] && { STEP="nuget: Newtonsoft.Json through nuget-group"; check nugeti; }
[ $P = 1 ] && { STEP="pypi: requests and its dependencies through pypi-group"; check pipi; }
[ $C = 1 ] && { STEP="cargo: itoa through cargo-group"; check cargoi; }
[ $GO = 1 ] && { STEP="go: rsc.io/quote and its dependencies through go-group"; check goi; }
[ $D = 1 ] && { STEP="docker: library/hello-world through the root (docker-group)"; check dockeri; }
[ $R = 1 ] && { STEP="rubygems: rack through rubygems-group"; check gemi; }

# ------------------------------------------------------------------------------------------- 2. offline switch
echo "== 2. global offline mode on"
offline true && ok "offline mode on" || bad "offline mode on"
if [ -n "${RESTART_CMD:-}" ]; then
  STEP="restart the server ($RESTART_CMD)"; check sh -c "$RESTART_CMD"
  for _ in $(seq 1 90); do curl -fsS -o /dev/null "$BASE/actuator/health/readiness" 2>/dev/null && break; sleep 2; done
  curl -fsS "$BASE/api/v1/system/info" | grep -q '"offlineMode":true' && ok "offline mode survives the restart" || bad "offline mode survives the restart"
fi

# ------------------------------------------------------------------------------- 3. empty client caches, again
echo "== 3. wipe every client cache and install again, only from the Granary cache"
rm -rf "$WORK/m2" "$WORK/npm-cache" "$WORK/npm/node_modules" "$WORK/npm/package-lock.json" "$WORK/nuget-packages" \
  "$WORK/nuget-http-cache" "$WORK/nuget/obj" "$WORK/pip-cache" "$WORK/venv" "$WORK/cargo-home" "$WORK/cargo-target" "$WORK/gems" "$WORK/gem-specs" "$WORK/home/.gem" "$WORK/home/.local"
[ $GO = 1 ] && { go clean -modcache >/dev/null 2>&1; rm -rf "$WORK/gocache"; }
[ $D = 1 ] && docker rmi -f "$IMAGE" >/dev/null 2>&1
[ $M = 1 ] && { STEP="offline maven: commons-text"; check maven; }
[ $N = 1 ] && { STEP="offline npm: chalk@4"; check npmi; }
[ $G = 1 ] && { STEP="offline nuget: Newtonsoft.Json"; check nugeti; }
[ $P = 1 ] && { STEP="offline pypi: requests"; check pipi; }
[ $C = 1 ] && { STEP="offline cargo: itoa"; check cargoi; }
[ $GO = 1 ] && { STEP="offline go: rsc.io/quote"; check goi; }
[ $D = 1 ] && { STEP="offline docker: library/hello-world"; check dockeri; }
[ $R = 1 ] && { STEP="offline rubygems: rack"; check gemi; }
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/repository/maven-central/org/apache/commons/commons-csv/1.14.0/commons-csv-1.14.0.pom")
[ "$code" = 503 ] && ok "an uncached artifact is a clean 503 while offline" || bad "an uncached artifact is a clean 503 while offline (got $code)"
[ $D = 1 ] && docker rmi -f "$IMAGE" >/dev/null 2>&1

offline false && ok "offline mode off" || bad "offline mode off"
echo; echo "passed=$PASS failed=$FAIL skipped=$SKIP  (work dir $WORK)"
[ "$FAIL" = 0 ]
