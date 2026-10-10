#!/usr/bin/env bash
# End-to-end verification of Granary's hex format with the real clients, mix (Hex) and rebar3, run in the
# official hexpm/elixir image with host networking (so BASE may be a localhost URL).
#
# Covers: mix hex.publish to a hosted repository, the refused re-publish of an existing release, retire and
# unretire, revert, search and whoami; rebar3 hex publish (the rebar3_hex plugin) with edoc documentation,
# its refused re-publish, whoami, retire, documentation revert and revert; mix deps.get / compile from the
# hosted repository and from a group; the hex.pm proxy as HEX_MIRROR (mix) and HEX_CDN (rebar3), against the
# real hex.pm when Granary can reach it and always against a local stand-in for it (a hosted repository named
# "hexpm" behind a proxy, so documents pass through byte for byte and are verified with that repository's key);
# rebar3 builds from a hosted repository and through the proxy; and offline mode with empty client caches,
# including a group created while offline over a proxy that was only ever used as a mirror.
#
# Usage: verify-hex.sh BASE_URL            e.g. verify-hex.sh http://localhost:8081
# Environment:
#   GRANARY_USER, GRANARY_PASSWORD  an administrator (default admin / admin123)
#   ELIXIR_IMAGE   client image (default hexpm/elixir:1.18.4-erlang-27.3.4.2-debian-bookworm-20250908-slim)
#   HEX_ARCHIVE    a Hex archive (.ez) to install instead of running `mix local.hex` (which needs builds.hex.pm);
#                  without it and without builds.hex.pm, Hex HEX_VERSION (default 2.5.1) is built from its git tag
#   REBAR3         a rebar3 escript to use instead of running `mix local.rebar` (which needs builds.hex.pm);
#                  without it and without builds.hex.pm, rebar3 is downloaded from REBAR3_URL (s3.amazonaws.com)
#   REBAR3_HEX_VERSION, HEX_CORE_VERSION, VERL_VERSION
#                  the rebar3_hex plugin and its dependencies (default 7.3.0, 0.19.0, 1.1.1), cloned from their git
#                  tags into the publishing project's _checkouts, so publishing does not depend on hex.pm
#   WORK           scratch directory (default: a new directory under /tmp)
# Package names carry a per-run suffix, so the script can run repeatedly against the same server.
set -uo pipefail

BASE=${1:?usage: verify-hex.sh BASE_URL}
BASE=${BASE%/}
IMAGE=${ELIXIR_IMAGE:-hexpm/elixir:1.18.4-erlang-27.3.4.2-debian-bookworm-20250908-slim}
ADMIN_USER=${GRANARY_USER:-admin}
ADMIN_PASSWORD=${GRANARY_PASSWORD:-admin123}
WORK=${WORK:-$(mktemp -d /tmp/granary-verify-hex.XXXX)}
RUN=${RUN:-$(date +%s | tail -c 7)}
HEX_VERSION=${HEX_VERSION:-2.5.1}
REBAR3_URL=${REBAR3_URL:-https://s3.amazonaws.com/rebar3/rebar3}
REBAR3_HEX_VERSION=${REBAR3_HEX_VERSION:-7.3.0}
HEX_CORE_VERSION=${HEX_CORE_VERSION:-0.19.0}
VERL_VERSION=${VERL_VERSION:-1.1.1}
mkdir -p "$WORK/steps" "$WORK/home" "$WORK/src"
cd "$WORK" || exit 2

LIB=acme_lib_$RUN       # Elixir package, hosted
CODEC=acme_codec_$RUN   # Erlang sources (mix and rebar3), hosted
JSON=acme_json_$RUN     # Erlang sources (mix and rebar3), on the local stand-in for hex.pm
R3PKG=acme_r3_$RUN      # Erlang sources published with rebar3, hosted

PASS=0; FAIL=0; SKIP=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
skip() { echo "SKIP  $1"; SKIP=$((SKIP+1)); }

# step NAME HOME [docker options...] <<'SCRIPT'
# Runs the script (sh -e) in the client image with HOME=/w/home/HOME (Hex and rebar3 keep their config and
# caches there) and a shared MIX_HOME (the Hex archive); PASS when it exits 0.
step() {
  local name=$1 home=$2; shift 2
  local file
  file="steps/$(printf '%03d' $((PASS + FAIL + SKIP)))-$(echo "$name" | tr -c 'A-Za-z0-9' '-' | cut -c1-60).sh"
  cat > "$WORK/$file"
  mkdir -p "$WORK/home/$home"
  if docker run --rm --network host -v "$WORK:/w" -w /w -e HOME="/w/home/$home" -e MIX_HOME=/w/mix \
      -e LANG=C.UTF-8 "$@" "$IMAGE" sh -e "/w/$file" > "$WORK/last.log" 2>&1; then
    ok "$name"
  else
    bad "$name"
    tail -25 "$WORK/last.log"
  fi
}

http_code() { curl -s -o "$WORK/http.out" -w '%{http_code}' "$@"; }

camel() { echo "$1" | sed -E 's/(^|_)([a-z0-9])/\U\2/g'; }

# elixir_pkg NAME VERSION: a mix project with one Elixir module
elixir_pkg() {
  local dir="$WORK/src/$1-$2"
  mkdir -p "$dir/lib"
  cat > "$dir/mix.exs" <<EOF
defmodule $(camel "$1").MixProject do
  use Mix.Project

  def project do
    [app: :$1, version: "$2", elixir: "~> 1.14", description: "Acme demo library",
     package: [licenses: ["Apache-2.0"], links: %{}], deps: []]
  end

  def application, do: [extra_applications: [:logger]]
end
EOF
  cat > "$dir/lib/$1.ex" <<EOF
defmodule $(camel "$1") do
  def hello, do: "hello from $1 $2"
end
EOF
}

# erlang_pkg NAME VERSION: Erlang sources with both a mix.exs and a rebar.config, so mix and rebar3 can build it
erlang_pkg() {
  local dir="$WORK/src/$1-$2"
  mkdir -p "$dir/src"
  cat > "$dir/mix.exs" <<EOF
defmodule $(camel "$1").MixProject do
  use Mix.Project

  def project do
    [app: :$1, version: "$2", language: :erlang, description: "Acme demo codec",
     package: [licenses: ["Apache-2.0"], links: %{}, files: ["src", "mix.exs", "rebar.config"]], deps: []]
  end

  def application, do: []
end
EOF
  echo '{erl_opts, [debug_info]}.' > "$dir/rebar.config"
  cat > "$dir/src/$1.app.src" <<EOF
{application, $1, [{description, "Acme demo codec"}, {vsn, "$2"}, {registered, []},
                   {applications, [kernel, stdlib]}, {env, []}, {modules, []},
                   {licenses, ["Apache-2.0"]}, {links, []}]}.
EOF
  cat > "$dir/src/$1.erl" <<EOF
-module($1).
-export([hello/0]).
hello() -> <<"hello from $1 $2">>.
EOF
}

# mix_project DIR DEPS: a consumer mix project
mix_project() {
  mkdir -p "$WORK/$1/lib"
  cat > "$WORK/$1/mix.exs" <<EOF
defmodule Consumer.MixProject do
  use Mix.Project
  def project, do: [app: :consumer, version: "0.0.1", elixir: "~> 1.14", deps: [$2]]
end
EOF
}

# rebar3_project DIR DEPS: a consumer rebar3 project
rebar3_project() {
  mkdir -p "$WORK/$1/src"
  echo "{erl_opts, [debug_info]}. {deps, [$2]}." > "$WORK/$1/rebar.config"
  cat > "$WORK/$1/src/consumer.app.src" <<'EOF'
{application, consumer, [{description, "consumer"}, {vsn, "0.0.1"}, {applications, [kernel, stdlib]}]}.
EOF
  printf -- '-module(consumer).\n-export([start/0]).\nstart() -> ok.\n' > "$WORK/$1/src/consumer.erl"
}

# rebar3_config HOME REPOS: a global rebar3 config (~/.config/rebar3/rebar.config) holding the hex repositories
rebar3_config() {
  mkdir -p "$WORK/home/$1/.config/rebar3"
  echo "{hex, [$2]}." > "$WORK/home/$1/.config/rebar3/rebar.config"
}

echo "== Granary at $BASE (work dir $WORK, run $RUN)"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASSWORD\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/')
if [ -n "$TOKEN" ]; then ok "login"; else bad "login"; echo "passed=$PASS failed=$FAIL skipped=$SKIP"; exit 1; fi
api() { curl -sS -o "$WORK/api.out" -w '%{http_code}' -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }
offline() { api -X PUT "$BASE/api/v1/system/settings" -d "{\"offlineMode\":$1}" >/dev/null; }
trap 'offline false' EXIT

[ "$(api -X POST "$BASE/api/v1/repositories/standard")" = 200 ] && ok "standard repositories (hexpm, hex-hosted, hex-group)" \
  || bad "standard repositories"
create() { # create NAME JSON: created now (201) or by an earlier run (409)
  local code
  code=$(api -X POST "$BASE/api/v1/repositories" -d "$2")
  case $code in 201|409) ok "repository $1";; *) bad "repository $1 (HTTP $code)"; cat "$WORK/api.out"; echo;; esac
}
create hex-upstream '{"name":"hex-upstream","format":"hex","type":"HOSTED","description":"Local stand-in for hex.pm","formatSettings":{"hex.repositoryName":"hexpm"}}'
create hexpm-local "{\"name\":\"hexpm-local\",\"format\":\"hex\",\"type\":\"PROXY\",\"remoteUrl\":\"$BASE/repository/hex-upstream/\"}"
create hex-local-group '{"name":"hex-local-group","format":"hex","type":"GROUP","members":["hex-hosted","hexpm-local"]}'
create hexpm-mirror-only "{\"name\":\"hexpm-mirror-only\",\"format\":\"hex\",\"type\":\"PROXY\",\"remoteUrl\":\"$BASE/repository/hex-upstream/\"}"

APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify-hex"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api key" || bad "api key"

fingerprint() { curl -fsS "$BASE/repository/$1/" | sed -E 's/.*"public_key_fingerprint":"([^"]+)".*/\1/'; }
HOSTED_FP=$(fingerprint hex-hosted)
GROUP_FP=$(fingerprint hex-group)
LOCAL_GROUP_FP=$(fingerprint hex-local-group)
curl -fsS "$BASE/repository/hex-upstream/public_key" -o "$WORK/upstream_key.pem"
PEM=$(cat "$WORK/upstream_key.pem")
[[ "$HOSTED_FP" == SHA256:* && "$GROUP_FP" == SHA256:* && "$PEM" == "-----BEGIN PUBLIC KEY-----"* ]] \
  && ok "repository public keys and fingerprints" || bad "repository public keys and fingerprints"

echo "== Clients"
# attempt HOME SCRIPT: runs a script in the client image like step, but only reports whether it worked
attempt() {
  local home=$1 script=$2
  mkdir -p "$WORK/home/$home"
  docker run --rm --network host -v "$WORK:/w" -w /w -e HOME="/w/home/$home" -e MIX_HOME=/w/mix -e LANG=C.UTF-8 \
    "$IMAGE" sh -ec "$script" > "$WORK/attempt.log" 2>&1
}
if [ -n "${HEX_ARCHIVE:-}" ]; then
  cp "$HEX_ARCHIVE" "$WORK/hex.ez"
  step "install Hex from $HEX_ARCHIVE" setup <<'EOF'
mix archive.install /w/hex.ez --force
mix hex.info
EOF
elif attempt setup 'mix local.hex --force && mix hex.info'; then
  ok "install Hex (mix local.hex)"
else
  # builds.hex.pm is unreachable: build the same Hex release from its source tag and install that
  echo "mix local.hex failed ($(grep -m1 -i -E 'failed|error' "$WORK/attempt.log")); building Hex $HEX_VERSION from source"
  rm -rf "$WORK/hex-src"
  if git clone --quiet --depth 1 --branch "v$HEX_VERSION" https://github.com/hexpm/hex.git "$WORK/hex-src"; then
    step "build Hex $HEX_VERSION from source and install it" setup <<'EOF'
cd /w/hex-src
MIX_ENV=prod mix archive.build -o /w/hex.ez
mix archive.install /w/hex.ez --force
mix hex.info
EOF
  else
    bad "install Hex: neither builds.hex.pm nor github.com/hexpm/hex is reachable (set HEX_ARCHIVE)"
  fi
fi
REBAR3_OK=true
if [ -n "${REBAR3:-}" ]; then
  cp "$REBAR3" "$WORK/rebar3"
elif attempt setup 'mix local.rebar --force && cp "$MIX_HOME"/elixir/*/rebar3 /w/rebar3'; then
  ok "install rebar3 (mix local.rebar)"
elif curl -fsSL "$REBAR3_URL" -o "$WORK/rebar3"; then
  ok "install rebar3 (mix local.rebar failed; downloaded $REBAR3_URL)"
else
  bad "install rebar3: neither builds.hex.pm nor $REBAR3_URL is reachable (set REBAR3)"
fi
if [ -f "$WORK/rebar3" ]; then
  step "rebar3 runs" setup <<'EOF'
escript /w/rebar3 version
EOF
else
  REBAR3_OK=false
fi

echo "== Hosted: mix hex.publish and the Hex API"
elixir_pkg "$LIB" 0.1.0
elixir_pkg "$LIB" 0.1.1
erlang_pkg "$CODEC" 1.0.0
erlang_pkg "$JSON" 1.0.0
PUBLISH=(-e HEX_API_KEY="$APITOKEN" -e HEX_API_URL="$BASE/repository/hex-hosted/api")
step "mix hex.publish $LIB 0.1.0 to hex-hosted" publisher "${PUBLISH[@]}" <<EOF
cd /w/src/$LIB-0.1.0
mix hex.publish package --yes 2>&1 | tee /w/publish.out
grep -q "Package published to $BASE/repository/hex-hosted/api/packages/$LIB/releases/0.1.0" /w/publish.out
EOF
step "mix hex.publish $CODEC 1.0.0 (Erlang, mix + rebar3) to hex-hosted" publisher "${PUBLISH[@]}" <<EOF
cd /w/src/$CODEC-1.0.0
mix hex.publish package --yes 2>&1 | tee /w/publish.out
grep -q "Package published" /w/publish.out
EOF
step "mix hex.publish of an existing release is refused (needs --replace)" publisher "${PUBLISH[@]}" <<EOF
cd /w/src/$LIB-0.1.0
mix hex.publish package --yes > /w/publish.out 2>&1 || true
cat /w/publish.out
grep -q -- "--replace" /w/publish.out && ! grep -q "Package published" /w/publish.out
EOF
step "mix hex.user whoami" publisher "${PUBLISH[@]}" <<EOF
mix hex.user whoami | tee /w/whoami.out
grep -qx "$ADMIN_USER" /w/whoami.out
EOF
step "mix hex.package search $LIB" publisher "${PUBLISH[@]}" <<EOF
mix hex.package search $LIB | tee /w/search.out
grep -q "$LIB" /w/search.out
EOF

echo "== Hosted and group: mix deps.get"
mix_project mix-hosted "{:$LIB, \"~> 0.1\", repo: \"hex-hosted\"}"
step "mix deps.get + compile from hex-hosted (auth key, --fetch-public-key)" consumer <<EOF
mix hex.repo add hex-hosted $BASE/repository/hex-hosted --fetch-public-key $HOSTED_FP --auth-key $APITOKEN
cd /w/mix-hosted
mix deps.get
mix run -e 'IO.puts($(camel "$LIB").hello())' | tee /w/run.out
grep -q "hello from $LIB 0.1.0" /w/run.out
grep -q '"hex-hosted"' mix.lock
EOF
mix_project mix-group "{:$LIB, \"~> 0.1\", repo: \"hex-group\"}, {:$CODEC, \"~> 1.0\", repo: \"hex-group\"}"
step "mix deps.get + compile from hex-group (anonymous)" consumer <<EOF
mix hex.repo add hex-group $BASE/repository/hex-group --fetch-public-key $GROUP_FP
cd /w/mix-group
mix deps.get
mix run -e 'IO.puts($(camel "$LIB").hello()); IO.puts(:$CODEC.hello())' | tee /w/run.out
grep -q "hello from $LIB 0.1.0" /w/run.out
grep -q "hello from $CODEC 1.0.0" /w/run.out
test "\$(grep -c '"hex-group"' mix.lock)" = 2
EOF

echo "== Hosted: retire, unretire, revert"
step "mix hex.retire $LIB 0.1.0" publisher "${PUBLISH[@]}" <<EOF
mix hex.retire $LIB 0.1.0 security --message "use 0.1.1"
mix hex.info $LIB 0.1.0 | tee /w/info.out
grep -q "retired: (security) use 0.1.1" /w/info.out
EOF
mix_project mix-retired "{:$LIB, \"~> 0.1\", repo: \"hex-hosted\"}"
step "mix deps.get warns about the retired release (registry)" consumer <<EOF
cd /w/mix-retired
mix deps.get 2>&1 | tee /w/deps.out
grep -q "RETIRED" /w/deps.out
EOF
step "mix hex.retire --unretire" publisher "${PUBLISH[@]}" <<EOF
mix hex.retire $LIB 0.1.0 --unretire
mix hex.info $LIB 0.1.0 | tee /w/info.out
! grep -q "retired" /w/info.out
EOF
step "mix hex.publish 0.1.1, then mix hex.publish --revert 0.1.1" publisher "${PUBLISH[@]}" <<EOF
cd /w/src/$LIB-0.1.1
mix hex.publish package --yes 2>&1 | tee /w/publish.out
grep -q "Package published" /w/publish.out
mix hex.publish --revert 0.1.1 | tee /w/revert.out
grep -q "Reverted $LIB 0.1.1" /w/revert.out
mix hex.info $LIB | tee /w/info.out
grep -q "0.1.0" /w/info.out && ! grep -q "0.1.1" /w/info.out
EOF
mix_project mix-reverted "{:$LIB, \"~> 0.1.1\", repo: \"hex-hosted\"}"
step "the reverted release is gone from the registry (mix deps.get fails)" consumer <<EOF
cd /w/mix-reverted
if mix deps.get > /w/deps.out 2>&1; then cat /w/deps.out; exit 1; fi
cat /w/deps.out
grep -q "$LIB" /w/deps.out
EOF

echo "== Proxy of a local stand-in for hex.pm (hexpm-local -> hex-upstream, named hexpm)"
step "mix hex.publish $JSON 1.0.0 to hex-upstream" publisher -e HEX_API_KEY="$APITOKEN" \
    -e HEX_API_URL="$BASE/repository/hex-upstream/api" <<EOF
cd /w/src/$JSON-1.0.0
mix hex.publish package --yes 2>&1 | tee /w/publish.out
grep -q "Package published" /w/publish.out
EOF
mix_project mix-mirror "{:$JSON, \"~> 1.0\"}"
step "mix deps.get + compile with HEX_MIRROR=hexpm-local" mirror -e HEX_MIRROR="$BASE/repository/hexpm-local" <<EOF
mix hex.repo set hexpm --public-key /w/upstream_key.pem
cd /w/mix-mirror
mix deps.get
mix run -e 'IO.puts(:$JSON.hello())' | tee /w/run.out
grep -q "hello from $JSON 1.0.0" /w/run.out
grep -q '"hexpm"' mix.lock
EOF
mix_project mix-mirror-only "{:$JSON, \"~> 1.0\"}"
step "mix deps.get with HEX_MIRROR=hexpm-mirror-only (a proxy only ever used as a mirror)" mirror-only \
    -e HEX_MIRROR="$BASE/repository/hexpm-mirror-only" <<EOF
mix hex.repo set hexpm --public-key /w/upstream_key.pem
cd /w/mix-mirror-only
mix deps.get
EOF
mix_project mix-local-group "{:$LIB, \"~> 0.1\", repo: \"hex-local-group\"}, {:$JSON, \"~> 1.0\", repo: \"hex-local-group\"}"
step "mix deps.get + compile from hex-local-group (hosted + proxy, re-signed)" consumer <<EOF
mix hex.repo add hex-local-group $BASE/repository/hex-local-group --fetch-public-key $LOCAL_GROUP_FP
cd /w/mix-local-group
mix deps.get
mix run -e 'IO.puts($(camel "$LIB").hello()); IO.puts(:$JSON.hello())' | tee /w/run.out
grep -q "hello from $LIB 0.1.0" /w/run.out
grep -q "hello from $JSON 1.0.0" /w/run.out
EOF

echo "== rebar3"
if $REBAR3_OK; then
  rebar3_config r3-hosted "{repos, replace, [#{name => <<\"hex-hosted\">>, repo_url => <<\"$BASE/repository/hex-hosted\">>,
    repo_key => <<\"$APITOKEN\">>, repo_public_key => <<\"$PEM
\">>}]}"
  rebar3_project r3-hosted "$CODEC"
  step "rebar3 compile $CODEC from hex-hosted (repo_key, repo_public_key)" r3-hosted <<EOF
cd /w/r3-hosted
escript /w/rebar3 compile
erl -noshell -pa _build/default/lib/*/ebin -eval 'io:format("~s~n", [$CODEC:hello()]), halt().' | tee /w/run.out
grep -q "hello from $CODEC 1.0.0" /w/run.out
grep -q "$CODEC" rebar.lock
EOF
  rebar3_config r3-cdn "{repos, [#{name => <<\"hexpm\">>, repo_public_key => <<\"$PEM
\">>}]}"
  rebar3_project r3-cdn "$JSON"
  step "rebar3 compile $JSON with HEX_CDN=hexpm-local" r3-cdn -e HEX_CDN="$BASE/repository/hexpm-local" <<EOF
cd /w/r3-cdn
escript /w/rebar3 compile
erl -noshell -pa _build/default/lib/*/ebin -eval 'io:format("~s~n", [$JSON:hello()]), halt().' | tee /w/run.out
grep -q "hello from $JSON 1.0.0" /w/run.out
EOF

  echo "== rebar3 hex publish (rebar3_hex $REBAR3_HEX_VERSION)"
  R3DIR="$WORK/r3-publish"
  rm -rf "$R3DIR"; mkdir -p "$R3DIR/src" "$R3DIR/_checkouts"
  CLONED=true
  for spec in "rebar3_hex https://github.com/erlef/rebar3_hex.git v$REBAR3_HEX_VERSION" \
              "hex_core https://github.com/hexpm/hex_core.git v$HEX_CORE_VERSION" \
              "verl https://github.com/jelly-beam/verl.git v$VERL_VERSION"; do
    read -r app url tag <<< "$spec"
    git clone --quiet --depth 1 --branch "$tag" "$url" "$R3DIR/_checkouts/$app" 2>/dev/null || CLONED=false
  done
  if $CLONED; then
    # reads come from the project's repository map; the API key stays in the global config, which rebar3 merges
    # into it by name. rebar3 gives repositories other than hexpm no defaults, so api_organization and
    # api_repository are spelled out (rebar3_hex fails without them).
    cat > "$R3DIR/rebar.config" <<EOF
{erl_opts, [debug_info]}.
{deps, []}.
{plugins, [rebar3_hex]}.
{hex, [{doc, edoc},
       {repos, [#{name => <<"hex-hosted">>, repo_url => <<"$BASE/repository/hex-hosted">>,
                  repo_key => <<"$APITOKEN">>, repo_public_key => <<"$PEM
">>}]}]}.
EOF
    rebar3_config r3-publish "{repos, [#{name => <<\"hex-hosted\">>, api_url => <<\"$BASE/repository/hex-hosted/api\">>,
    api_key => <<\"$APITOKEN\">>, api_organization => undefined, api_repository => undefined}]}"
    cat > "$R3DIR/src/$R3PKG.app.src" <<EOF
{application, $R3PKG, [{description, "Acme demo published with rebar3"}, {vsn, "1.0.0"}, {registered, []},
                       {applications, [kernel, stdlib]}, {env, []}, {modules, []},
                       {licenses, ["Apache-2.0"]}, {links, []}]}.
EOF
    cat > "$R3DIR/src/$R3PKG.erl" <<EOF
%% @doc Says hello.
-module($R3PKG).
-export([hello/0]).
hello() -> <<"hello from $R3PKG 1.0.0">>.
EOF
    step "rebar3 hex publish $R3PKG 1.0.0 with edoc documentation" r3-publish <<EOF
cd /w/r3-publish
escript /w/rebar3 hex publish --repo hex-hosted --yes 2>&1 | tee /w/publish.out
grep -q "Published $R3PKG 1.0.0" /w/publish.out
grep -q "Published docs for $R3PKG 1.0.0" /w/publish.out
EOF
    step "rebar3 hex user whoami" r3-publish <<EOF
cd /w/r3-publish
escript /w/rebar3 hex user whoami --repo hex-hosted | tee /w/whoami.out
grep -q "$ADMIN_USER" /w/whoami.out
EOF
    CODE=$(http_code "$BASE/repository/hex-hosted/docs/$R3PKG-1.0.0.tar.gz")
    [ "$CODE" = 200 ] && tar tzf "$WORK/http.out" | grep -q '^index.html$' \
      && ok "documentation uploaded by rebar3 is served" || bad "documentation uploaded by rebar3 is served (HTTP $CODE)"
    step "rebar3 hex publish of an existing release is refused" r3-publish <<EOF
cd /w/r3-publish
if escript /w/rebar3 hex publish package --repo hex-hosted --yes > /w/publish.out 2>&1; then exit 1; fi
grep -q "must include the --replace flag" /w/publish.out
EOF
    step "rebar3 hex retire $R3PKG 1.0.0" r3-publish <<EOF
cd /w/r3-publish
escript /w/rebar3 hex retire $R3PKG 1.0.0 deprecated --message "use something else" --repo hex-hosted
EOF
    curl -fsS -H 'Accept: application/json' "$BASE/repository/hex-hosted/api/packages/$R3PKG" | grep -q '"reason":"deprecated"' \
      && ok "the retirement is recorded" || bad "the retirement is recorded"
    rebar3_project r3-consume "$R3PKG"
    step "rebar3 compile $R3PKG (published with rebar3) from hex-hosted" r3-hosted <<EOF
cd /w/r3-consume
escript /w/rebar3 compile
erl -noshell -pa _build/default/lib/*/ebin -eval 'io:format("~s~n", [$R3PKG:hello()]), halt().' | tee /w/run.out
grep -q "hello from $R3PKG 1.0.0" /w/run.out
EOF
    step "rebar3 hex publish docs --revert and --revert $R3PKG 1.0.0" r3-publish <<EOF
cd /w/r3-publish
escript /w/rebar3 hex publish docs --revert 1.0.0 --repo hex-hosted --yes
escript /w/rebar3 hex publish --revert 1.0.0 --repo hex-hosted --yes
EOF
    [ "$(http_code "$BASE/repository/hex-hosted/docs/$R3PKG-1.0.0.tar.gz")" = 404 ] \
      && [ "$(http_code "$BASE/repository/hex-hosted/tarballs/$R3PKG-1.0.0.tar")" = 404 ] \
      && [ "$(http_code "$BASE/repository/hex-hosted/packages/$R3PKG")" = 404 ] \
      && ok "the reverted release and its documentation are gone" || bad "the reverted release and its documentation are gone"
  else
    skip "rebar3 hex publish: the rebar3_hex plugin sources cannot be cloned from github.com"
  fi
else
  skip "rebar3 (no rebar3: set REBAR3 to a rebar3 escript)"
fi

echo "== Real hex.pm through the hexpm proxy"
CODE=$(http_code "$BASE/repository/hexpm/packages/jason")
if [ "$CODE" = 200 ]; then
  mix_project mix-hexpm '{:jason, "~> 1.4"}'
  step "mix deps.get + compile jason with HEX_MIRROR=hexpm" hexpm -e HEX_MIRROR="$BASE/repository/hexpm" <<EOF
cd /w/mix-hexpm
mix deps.get
mix run -e 'IO.puts(Jason.encode!(%{ok: true}))' | tee /w/run.out
grep -q '{"ok":true}' /w/run.out
EOF
  mix_project mix-hex-group "{:$LIB, \"~> 0.1\", repo: \"hex-group\"}, {:jason, \"~> 1.4\", repo: \"hex-group\"}"
  step "mix deps.get jason and $LIB from hex-group" consumer <<EOF
cd /w/mix-hex-group
mix deps.get
mix compile
EOF
  if $REBAR3_OK; then
    rebar3_project r3-hexpm 'jsx'
    step "rebar3 compile jsx with HEX_CDN=hexpm" r3-hexpm -e HEX_CDN="$BASE/repository/hexpm" <<EOF
cd /w/r3-hexpm
escript /w/rebar3 compile
EOF
  fi
else
  skip "real hex.pm: Granary cannot reach it (HTTP $CODE: $(head -c 300 "$WORK/http.out"))"
fi

echo "== Offline mode (empty client caches)"
offline true && ok "offline mode on"
CODE=$(http_code "$BASE/repository/hexpm-local/packages/never_cached_$RUN")
grep -q upstream.offline "$WORK/http.out" && [ "$CODE" = 503 ] && ok "uncached document: 503 upstream.offline" \
  || bad "uncached document: 503 upstream.offline (HTTP $CODE)"
mix_project mix-offline-mirror "{:$JSON, \"~> 1.0\"}"
step "offline: mix deps.get + compile with HEX_MIRROR=hexpm-local" offline-mirror -e HEX_MIRROR="$BASE/repository/hexpm-local" <<EOF
mix hex.repo set hexpm --public-key /w/upstream_key.pem
cd /w/mix-offline-mirror
mix deps.get
mix run -e 'IO.puts(:$JSON.hello())' | grep -q "hello from $JSON 1.0.0"
EOF
mix_project mix-offline-group "{:$LIB, \"~> 0.1\", repo: \"hex-local-group\"}, {:$JSON, \"~> 1.0\", repo: \"hex-local-group\"}"
step "offline: mix deps.get + compile from hex-local-group" offline-group <<EOF
mix hex.repo add hex-local-group $BASE/repository/hex-local-group --fetch-public-key $LOCAL_GROUP_FP
cd /w/mix-offline-group
mix deps.get
mix run -e 'IO.puts(:$JSON.hello())' | grep -q "hello from $JSON 1.0.0"
EOF
create hex-mirror-group '{"name":"hex-mirror-group","format":"hex","type":"GROUP","members":["hex-hosted","hexpm-mirror-only"]}'
mix_project mix-offline-new-group "{:$JSON, \"~> 1.0\", repo: \"hex-mirror-group\"}"
step "offline: mix deps.get from a group created offline over the mirror-only proxy" offline-new-group <<EOF
mix hex.repo add hex-mirror-group $BASE/repository/hex-mirror-group --fetch-public-key $GROUP_FP
cd /w/mix-offline-new-group
mix deps.get
mix run -e 'IO.puts(:$JSON.hello())' | grep -q "hello from $JSON 1.0.0"
EOF
if $REBAR3_OK; then
  rebar3_config r3-offline "{repos, [#{name => <<\"hexpm\">>, repo_public_key => <<\"$PEM
\">>}]}"
  rebar3_project r3-offline "$JSON"
  step "offline: rebar3 compile $JSON with HEX_CDN=hexpm-local" r3-offline -e HEX_CDN="$BASE/repository/hexpm-local" <<EOF
cd /w/r3-offline
escript /w/rebar3 compile
test -f _build/default/lib/$JSON/ebin/$JSON.beam
EOF
fi
step "offline: hosted publishing still works" publisher "${PUBLISH[@]}" <<EOF
cd /w/src/$LIB-0.1.1
sed -i 's/version: "0.1.1"/version: "0.2.0"/' mix.exs
mix hex.publish package --yes 2>&1 | tee /w/publish.out
grep -q "Package published" /w/publish.out
EOF
offline false && ok "offline mode off"

echo
echo "passed=$PASS failed=$FAIL skipped=$SKIP  (work dir $WORK)"
[ "$FAIL" = 0 ]
