#!/usr/bin/env bash
# End-to-end verification of the Cargo format with the real cargo client, including an offline run against the
# Granary cache and a run with anonymous reads disabled.
#
# Publishes two crates to cargo-hosted (one with a plain crates.io dependency, one with a renamed optional
# dependency and a "dep:" feature), then builds a consumer through cargo-group with crates.io replaced by the
# group, so every crate comes through Granary. Also covers cargo login, search, owner, yank, add and info.
#
# Needs curl and cargo (1.74+) on the PATH and a running Granary with admin/admin123 whose cargo-proxy can reach
# index.crates.io and static.crates.io. CARGO_HOME is a fresh directory inside the work directory, so the
# caller's cargo configuration, credentials and caches are neither used nor changed.
# Usage: verify-cargo.sh http://localhost:8081
set -uo pipefail
BASE=${1:-http://localhost:8081}
BASE=${BASE%/}
WORK=$(mktemp -d /tmp/granary-verify-cargo.XXXX)
cd "$WORK"
PASS=0; FAIL=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# fails <pattern> <command…>: the command must fail and its output must match the pattern
fails(){ local pattern=$1; shift; if "$@" >"$WORK/last.log" 2>&1; then bad "$STEP (succeeded)"; tail -5 "$WORK/last.log";
         elif grep -q -E "$pattern" "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# checkin <dir> <command…>: check in another directory without a subshell, so the counters survive
checkin(){ local dir=$1; shift; pushd "$dir" >/dev/null || { bad "$STEP (no $dir)"; return; }; check "$@"; popd >/dev/null; }
failsin(){ local dir=$1; shift; pushd "$dir" >/dev/null || { bad "$STEP (no $dir)"; return; }; fails "$@"; popd >/dev/null; }
for tool in curl cargo; do command -v "$tool" >/dev/null || { echo "missing required tool: $tool"; exit 2; }; done

# nothing from the caller's environment may point cargo elsewhere
for v in $(env | sed -n -E 's/^(CARGO_REGISTRIES_[A-Z0-9_]+|CARGO_REGISTRY_[A-Z0-9_]+|CARGO_SOURCE_[A-Z0-9_]+|CARGO_NET_OFFLINE)=.*/\1/p'); do unset "$v"; done
export CARGO_TERM_COLOR=never CARGO_TERM_PROGRESS_WHEN=never CARGO_TARGET_DIR="$WORK/target" CARGO_INCREMENTAL=0
GROUP_INDEX="sparse+$BASE/repository/cargo-group/index/"
HOSTED_INDEX="sparse+$BASE/repository/cargo-hosted/index/"

# home <dir>: a CARGO_HOME using cargo-group for everything (crates.io replaced) and cargo-hosted for publishing,
# exactly as the connect dialogue's snippets configure it
home() {
  mkdir -p "$1"
  cat > "$1/config.toml" <<TOML
[registries.granary]
index = "$GROUP_INDEX"

[registries.granary-hosted]
index = "$HOSTED_INDEX"

[source.crates-io]
replace-with = "granary"

[source.granary]
registry = "$GROUP_INDEX"

[registry]
global-credential-providers = ["cargo:token"]

[net]
retry = 3
TOML
}

echo "== API"
# a server that has just started may need a moment before the built-in admin exists
for _ in $(seq 1 30); do
  TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' 2>/dev/null | sed -E 's/.*"token":"([^"]+)".*/\1/')
  [ -n "$TOKEN" ] && break; sleep 2
done
[ -n "$TOKEN" ] && ok "login" || bad "login"
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null && ok "standard repositories (cargo-proxy, cargo-hosted, cargo-group)" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify-cargo"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
# an earlier run on this server published the same demo crates: remove them through the component API, so the run
# can be repeated (a crate file removed this way takes its version out of the index the next moment)
removed=0; failed=0
for crate in acme-hello acme-extra; do
  for id in $(curl -fsS "$BASE/api/v1/repositories/cargo-hosted/components?q=$crate&size=500" -H "Authorization: Bearer $TOKEN" \
              | grep -o '{[^{}]*}' | grep "\"name\":\"$crate\"" | sed -E 's/.*"id":"([^"]+)".*/\1/'); do
    curl -fsS -o /dev/null -X DELETE "$BASE/api/v1/repositories/cargo-hosted/components/$id" -H "Authorization: Bearer $TOKEN" \
      && removed=$((removed + 1)) || failed=$((failed + 1))
  done
done
[ "$failed" = 0 ] && ok "demo crates of an earlier run removed from cargo-hosted ($removed versions)" || bad "removing the demo crates of an earlier run"
settings() { curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" >/dev/null; }
# whatever happens below, leave the server online and readable
trap 'settings "{\"offlineMode\":false,\"anonymousRead\":true}" 2>/dev/null' EXIT
STEP="config.json of cargo-group names the group"
curl -fsS "$BASE/repository/cargo-group/index/config.json" >"$WORK/config.json" 2>&1 \
  && grep -q "\"dl\":\"$BASE/repository/cargo-group/api/v1/crates\"" "$WORK/config.json" && ok "$STEP" || { bad "$STEP"; cat "$WORK/config.json"; }

echo "== cargo login"
export CARGO_HOME="$WORK/home"
home "$CARGO_HOME"
STEP="cargo login --registry granary-hosted (token on stdin)"; check sh -c 'echo "$1" | cargo login --registry granary-hosted' sh "$APITOKEN"
STEP="cargo login --registry granary"; check sh -c 'echo "$1" | cargo login --registry granary' sh "$APITOKEN"
grep -q "$APITOKEN" "$CARGO_HOME/credentials.toml" 2>/dev/null && ok "credentials.toml holds the token" || bad "credentials.toml holds the token"

echo "== cargo publish to cargo-hosted"
STEP="cargo new --lib acme-hello"; check cargo new --lib --vcs none acme-hello
cat > acme-hello/Cargo.toml <<'TOML'
[package]
name = "acme-hello"
version = "0.1.0"
edition = "2021"
description = "Says hello, for the Granary cargo verification"
license = "MIT"
keywords = ["greeting"]
publish = ["granary-hosted"]

[dependencies]
itoa = "1"
TOML
cat > acme-hello/src/lib.rs <<'RUST'
pub fn hello(n: u32) -> String {
    format!("hello {}", itoa::Buffer::new().format(n))
}
RUST
# publish builds the packaged crate, which resolves itoa through cargo-group and cargo-proxy
STEP="cargo publish acme-hello 0.1.0 (verify build pulls itoa through the group)"; checkin acme-hello cargo publish --registry granary-hosted --allow-dirty
# cargo itself looks the version up in the index first; the server refuses it too (older cargos, races)
STEP="cargo publish again is refused"; failsin acme-hello "already (exists|uploaded)" cargo publish --registry granary-hosted --allow-dirty --no-verify
STEP="cargo package acme-hello"; checkin acme-hello cargo package --allow-dirty --no-verify
STEP="a raw publish of the same version is refused with cargo's error document"
META='{"name":"acme-hello","vers":"0.1.0","deps":[],"features":{},"authors":[],"description":"again","license":"MIT"}'
CRATE="$CARGO_TARGET_DIR/package/acme-hello-0.1.0.crate"
le32() { printf '%b' "$(printf '\\x%02x\\x%02x\\x%02x\\x%02x' $(($1 & 255)) $(($1 >> 8 & 255)) $(($1 >> 16 & 255)) $(($1 >> 24 & 255)))"; }
{ le32 ${#META}; printf '%s' "$META"; le32 "$(wc -c < "$CRATE")"; cat "$CRATE"; } > "$WORK/again.bin" 2>/dev/null
code=$(curl -s -o "$WORK/again.json" -w "%{http_code}" -X PUT -H "Authorization: $APITOKEN" -H 'Content-Type: application/octet-stream' \
  --data-binary @"$WORK/again.bin" "$BASE/repository/cargo-hosted/api/v1/crates/new")
[ "$code" = "409" ] && grep -q 'crate version `0.1.0` is already uploaded' "$WORK/again.json" && ok "$STEP" || { bad "$STEP ($code)"; cat "$WORK/again.json"; }
STEP="cargo new --lib acme-extra"; check cargo new --lib --vcs none acme-extra
cat > acme-extra/Cargo.toml <<'TOML'
[package]
name = "acme-extra"
version = "0.1.0"
edition = "2021"
description = "Optional JSON support behind a feature"
license = "MIT"
publish = ["granary-hosted"]

[dependencies]
json = { package = "serde_json", version = "1", optional = true }

[features]
fast = ["dep:json"]
plain = []
TOML
cat > acme-extra/src/lib.rs <<'RUST'
#[cfg(feature = "fast")]
pub fn encode(s: &str) -> String {
    json::to_string(s).unwrap()
}
#[cfg(not(feature = "fast"))]
pub fn encode(s: &str) -> String {
    format!("\"{}\"", s)
}
RUST
STEP="cargo publish acme-extra 0.1.0 (renamed optional dependency, dep: feature)"; checkin acme-extra cargo publish --registry granary-hosted --allow-dirty --no-verify
STEP="hosted index line has features2 and the renamed dependency"
curl -fsS "$BASE/repository/cargo-hosted/index/ac/me/acme-extra" >"$WORK/extra.idx" 2>&1 \
  && grep -q '"features2":{"fast":\["dep:json"\]}' "$WORK/extra.idx" && grep -q '"name":"json"' "$WORK/extra.idx" \
  && grep -q '"package":"serde_json"' "$WORK/extra.idx" && ok "$STEP" || { bad "$STEP"; cat "$WORK/extra.idx"; }

echo "== HTTP surface (what cargo relies on, and what it must never get)"
H="$BASE/repository/cargo-hosted"
STEP="HEAD of a crate download has Content-Length and ETag"
size=$(curl -fsS "$H/api/v1/crates/acme-hello/0.1.0/download" | wc -c | tr -d ' ')
curl -fsS -I "$H/api/v1/crates/acme-hello/0.1.0/download" >"$WORK/head.txt" 2>&1 \
  && grep -qi "^content-length: $size" "$WORK/head.txt" && grep -qi '^etag: "' "$WORK/head.txt" && ok "$STEP" || { bad "$STEP"; cat "$WORK/head.txt"; }
STEP="index file revalidation with If-None-Match is a 304"
etag=$(curl -fsS -I "$H/index/ac/me/acme-hello" | tr -d '\r' | sed -n -E 's/^[Ee][Tt][Aa][Gg]: (.*)$/\1/p')
code=$(curl -s -o /dev/null -w "%{http_code}" -H "If-None-Match: $etag" "$H/index/ac/me/acme-hello"); [ "$code" = "304" ] && ok "$STEP" || bad "$STEP ($code)"
STEP="percent-encoded slash in an index path is a 400"
code=$(curl -s -o /dev/null -w "%{http_code}" "$H/index/ac%2Fme/acme-hello"); [ "$code" = "400" ] && ok "$STEP" || bad "$STEP ($code)"
STEP="traversal in a download path is a 400"
code=$(curl -s -o /dev/null -w "%{http_code}" "$H/api/v1/crates/..%2F..%2Fx/0.1.0/download"); [ "$code" = "400" ] && ok "$STEP" || bad "$STEP ($code)"
STEP="anonymous publish is a 401 with a challenge"
code=$(curl -s -D "$WORK/anon.hdr" -o /dev/null -w "%{http_code}" -X PUT --data-binary @"$WORK/again.bin" "$H/api/v1/crates/new")
[ "$code" = "401" ] && grep -qi '^www-authenticate:' "$WORK/anon.hdr" && ok "$STEP" || bad "$STEP ($code)"
STEP="a wrong token is a 401"
code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT -H "Authorization: gry_not_a_real_token_value_at_all" --data-binary @"$WORK/again.bin" "$H/api/v1/crates/new")
[ "$code" = "401" ] && ok "$STEP" || bad "$STEP ($code)"
STEP="publishing through the group is refused and names the hosted member"
code=$(curl -s -o "$WORK/group.json" -w "%{http_code}" -X PUT -H "Authorization: $APITOKEN" --data-binary @"$WORK/again.bin" "$BASE/repository/cargo-group/api/v1/crates/new")
[ "$code" = "405" ] && grep -q 'cargo-hosted' "$WORK/group.json" && grep -q '"errors"' "$WORK/group.json" && ok "$STEP" || { bad "$STEP ($code)"; cat "$WORK/group.json"; }

echo "== cargo search, owner, info, yank"
STEP="cargo search acme --registry granary-hosted"
if cargo search acme --registry granary-hosted --limit 10 >"$WORK/last.log" 2>&1 && grep -q '^acme-hello = "0.1.0"' "$WORK/last.log" \
   && grep -q '^acme-extra = "0.1.0"' "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -10 "$WORK/last.log"; fi
STEP="cargo search itoa --registry granary (group: hosted, then crates.io)"
if cargo search itoa --registry granary --limit 5 >"$WORK/last.log" 2>&1 && grep -q '^itoa = ' "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -10 "$WORK/last.log"; fi
STEP="cargo owner --list"
if cargo owner --list acme-hello --registry granary-hosted >"$WORK/last.log" 2>&1 && grep -q '^admin' "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -10 "$WORK/last.log"; fi
STEP="cargo owner --add of an unknown user is refused"; fails "could not find user" cargo owner --add no-such-user-here acme-hello --registry granary-hosted
STEP="cargo info acme-hello --registry granary"
if cargo info acme-hello --registry granary >"$WORK/last.log" 2>&1 && grep -q 'Says hello' "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -10 "$WORK/last.log"; fi
STEP="cargo yank acme-hello@0.1.0"; check cargo yank acme-hello@0.1.0 --registry granary-hosted
STEP="index shows the version yanked"; curl -fsS "$BASE/repository/cargo-group/index/ac/me/acme-hello" 2>&1 | grep -q '"yanked":true' && ok "$STEP" || bad "$STEP"
STEP="cargo yank --undo acme-hello@0.1.0"; check cargo yank --undo acme-hello@0.1.0 --registry granary-hosted

echo "== consumer through cargo-group"
STEP="cargo new consumer"; check cargo new --bin --vcs none consumer
cat > consumer/Cargo.toml <<'TOML'
[package]
name = "consumer"
version = "0.1.0"
edition = "2021"
publish = false

[dependencies]
itoa = "1"
acme-hello = { version = "0.1.0", registry = "granary" }
TOML
cat > consumer/src/main.rs <<'RUST'
fn main() {
    println!("{} {} {}", acme_hello::hello(7), itoa::Buffer::new().format(42), acme_extra::encode("x"));
}
RUST
STEP="cargo add acme-extra --registry granary --features fast"; checkin consumer cargo add acme-extra --registry granary --features fast
grep -q 'acme-extra = { version = "0.1.0", registry = "granary", features = \["fast"\] }' consumer/Cargo.toml \
  && ok "Cargo.toml has acme-extra from granary" || { bad "Cargo.toml has acme-extra from granary"; cat consumer/Cargo.toml; }
STEP="cargo fetch (own crates + itoa + serde_json and its dependencies)"; checkin consumer cargo fetch
STEP="cargo build --offline"; checkin consumer cargo build --offline
STEP="consumer runs"
if "$CARGO_TARGET_DIR/debug/consumer" >"$WORK/last.log" 2>&1 && grep -q '^hello 7 42 "x"$' "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; cat "$WORK/last.log"; fi
grep -q 'name = "serde_json"' consumer/Cargo.lock && ok "Cargo.lock pins serde_json" || bad "Cargo.lock pins serde_json"

echo "== Anonymous reads disabled: config.json says auth-required, cargo sends the token on every request"
settings '{"anonymousRead":false}' && ok "anonymous reads off" || bad "anonymous reads off"
STEP="config.json is 401 without a token"; code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/repository/cargo-group/index/config.json"); [ "$code" = "401" ] && ok "$STEP ($code)" || bad "$STEP ($code)"
STEP="config.json says auth-required with a token"
curl -fsS -H "Authorization: $APITOKEN" "$BASE/repository/cargo-group/index/config.json" 2>&1 | grep -q '"auth-required":true' && ok "$STEP" || bad "$STEP"
export CARGO_HOME="$WORK/home-auth"; home "$CARGO_HOME"; rm -rf "$CARGO_TARGET_DIR"
STEP="cargo fetch without a token fails"; failsin consumer "401|token|unauthorized|authenticat" cargo fetch
cp "$WORK/home/credentials.toml" "$CARGO_HOME/credentials.toml"
STEP="cargo fetch with the token (fresh CARGO_HOME)"; checkin consumer cargo fetch
STEP="cargo build with the token"; checkin consumer cargo build
settings '{"anonymousRead":true}' && ok "anonymous reads on again" || bad "anonymous reads on again"

echo "== Offline: everything again with upstream access disabled and empty local caches"
settings '{"offlineMode":true}' && ok "offline mode on" || bad "offline mode on"
export CARGO_HOME="$WORK/home-offline"; home "$CARGO_HOME"; cp "$WORK/home/credentials.toml" "$CARGO_HOME/"; rm -rf "$CARGO_TARGET_DIR"
STEP="offline cargo fetch --locked (fresh CARGO_HOME, served from the Granary cache)"; checkin consumer cargo fetch --locked
STEP="offline cargo build --locked"; checkin consumer cargo build --locked
STEP="offline consumer runs"
if "$CARGO_TARGET_DIR/debug/consumer" >"$WORK/last.log" 2>&1 && grep -q '^hello 7 42 "x"$' "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; cat "$WORK/last.log"; fi
STEP="offline cargo publish to hosted still works"
sed -i 's/^version = "0.1.0"/version = "0.1.1"/' acme-hello/Cargo.toml; checkin acme-hello cargo publish --registry granary-hosted --allow-dirty
STEP="offline uncached crate fails cleanly (503, not 404)"
STEP_SAVED=$STEP; mkdir -p uncached/src && printf '[package]\nname = "uncached"\nversion = "0.1.0"\nedition = "2021"\n\n[dependencies]\nsemver = "=1.0.0"\n' > uncached/Cargo.toml && echo 'fn main() {}' > uncached/src/main.rs
STEP="$STEP_SAVED: cargo generate-lockfile"; failsin uncached "503" cargo generate-lockfile
STEP="$STEP_SAVED: index file"; code=$(curl -s -o "$WORK/uncached.json" -w "%{http_code}" "$BASE/repository/cargo-group/index/se/mv/semver")
[ "$code" = "503" ] && grep -q 'upstream.offline' "$WORK/uncached.json" && ok "$STEP ($code)" || bad "$STEP ($code)"
settings '{"offlineMode":false}' && ok "offline mode off" || bad "offline mode off"

echo; echo "passed=$PASS failed=$FAIL  (work dir $WORK)"
[ "$FAIL" = 0 ]
