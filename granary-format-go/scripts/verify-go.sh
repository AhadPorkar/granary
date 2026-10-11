#!/usr/bin/env bash
# End-to-end verification of the Go modules format with the real go command: modules from proxy.golang.org
# through go-proxy, checksum verification against sum.golang.org through the proxy's sumdb passthrough,
# publishing a private module to go-hosted with curl, consuming it through go-group, credentials in the GOPROXY
# URL when anonymous reads are off, and an offline run with empty module caches.
# Needs go (1.21+), curl, zip, openssl and python3 on the PATH, and a running Granary with admin/admin123.
# The go command sends credentials over HTTPS only, so for the authenticated part the script starts a throw-away
# TLS front (self-signed, port GO_TLS_PORT, default 18103) unless the base URL is already https.
# Optionally, GO_DOCKER_IMAGE=golang:1.25-alpine (any image with the go command) repeats the consumer build with that
# go version in a container on the host network, online and offline.
# Usage: verify-go.sh http://localhost:8103
set -uo pipefail
BASE=${1:-http://localhost:8081}
HOSTPORT=${BASE#http://}; HOSTPORT=${HOSTPORT#https://}; HOSTPORT=${HOSTPORT%%/*}
SCHEME=${BASE%%://*}
WORK=$(mktemp -d /tmp/granary-verify-go.XXXX)
cd "$WORK"
PASS=0; FAIL=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
fails(){ if "$@" >"$WORK/last.log" 2>&1; then bad "$STEP"; tail -5 "$WORK/last.log"; else ok "$STEP"; fi; }

# A clean, isolated go environment: no user go env file, nothing private, the default checksum database.
unset GOPRIVATE GONOPROXY GONOSUMDB GONOSUMCHECK GOINSECURE GOSUMDB GOFLAGS GOPROXY
export GOENV=off GOTOOLCHAIN=local GOTELEMETRY=off GOFLAGS=-mod=mod GOCACHE="$WORK/gocache"
# Make sure sum.golang.org can only be reached through Granary: any direct HTTPS connection goes to a dead proxy.
export HTTPS_PROXY=http://127.0.0.1:9 https_proxy=http://127.0.0.1:9
export NO_PROXY="localhost,127.0.0.1,${HOSTPORT%%:*}" no_proxy="localhost,127.0.0.1,${HOSTPORT%%:*}"
GROUP="$BASE/repository/go-group"
fresh(){ export GOMODCACHE="$WORK/$1/modcache" GOPATH="$WORK/$1/gopath"; mkdir -p "$GOMODCACHE" "$GOPATH"; }

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null && ok "standard repositories (go-proxy, go-hosted, go-group)" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify-go"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
# an earlier run on this server published the same demo module and created go-copy: remove both through the API,
# so the run can be repeated
removed=0; failed=0
for id in $(curl -fsS "$BASE/api/v1/repositories/go-hosted/components?q=hello&size=500" -H "Authorization: Bearer $TOKEN" \
            | grep -o '{[^{}]*}' | grep '"namespace":"example.com/acme","name":"hello"' | sed -E 's/.*"id":"([^"]+)".*/\1/'); do
  curl -fsS -o /dev/null -X DELETE "$BASE/api/v1/repositories/go-hosted/components/$id" -H "Authorization: Bearer $TOKEN" \
    && removed=$((removed + 1)) || failed=$((failed + 1))
done
curl -fsS -o /dev/null -X DELETE "$BASE/api/v1/repositories/go-copy" -H "Authorization: Bearer $TOKEN" 2>/dev/null
[ "$failed" = 0 ] && ok "demo module of an earlier run removed from go-hosted ($removed versions)" || bad "removing the demo module of an earlier run"
STEP="connect snippets for go-group"; check sh -c "curl -fsS -H 'Authorization: Bearer $TOKEN' '$BASE/api/v1/repositories/go-group/connect' | grep -q 'go env -w GOPROXY=$GROUP,direct'"

echo "== Public modules through go-proxy, checksums verified via sumdb/sum.golang.org on Granary"
fresh online
export GOPROXY="$GROUP"
STEP="sumdb/sum.golang.org/supported answers 200"; check curl -fsS "$GROUP/sumdb/sum.golang.org/supported"
STEP="go mod download rsc.io/quote@v1.5.2"; check go mod download -json rsc.io/quote@v1.5.2
cp "$WORK/last.log" "$WORK/quote.json"
STEP="the downloaded zip has the go.sum hash of sum.golang.org"; check grep -q '"Sum": "h1:w5fcysjrx7yqtD/aO+QwRjYZOKnaM9Uh2b40tElTs3Y="' "$WORK/quote.json"
STEP="go verified it against the checksum database (sumdb lookup cached by the go command)"
check test -f "$GOMODCACHE/cache/download/sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2"
STEP="Granary cached the sumdb lookup in go-proxy"
check sh -c "curl -fsS -H 'Authorization: Bearer $TOKEN' '$BASE/api/v1/repositories/go-proxy/assets?prefix=sumdb/sum.golang.org/lookup/rsc.io/' | grep -q 'quote@v1.5.2'"
STEP="go mod download github.com/BurntSushi/toml@v1.3.2 (case-encoded path)"; check go mod download -json github.com/BurntSushi/toml@v1.3.2
STEP="Granary stored it case-encoded"
check sh -c "curl -fsS -H 'Authorization: Bearer $TOKEN' '$BASE/api/v1/repositories/go-proxy/assets?prefix=github.com/!burnt!sushi/toml/@v/' | grep -q 'v1.3.2.zip'"
STEP="go list -m -versions rsc.io/quote via go-proxy"
check sh -c "GOPROXY='$BASE/repository/go-proxy' go list -m -versions rsc.io/quote | grep -q 'v1.5.2'"

echo "== Publish example.com/acme/hello v0.1.0 to go-hosted with curl"
mkdir -p src/example.com/acme/hello@v0.1.0
cat > src/example.com/acme/hello@v0.1.0/go.mod <<'GOMOD'
module example.com/acme/hello

go 1.21
GOMOD
cat > src/example.com/acme/hello@v0.1.0/hello.go <<'GOSRC'
// Package hello greets from a private module served by Granary.
package hello

// Greeting returns the greeting.
func Greeting() string { return "hello from granary" }
GOSRC
(cd src && zip -qr "$WORK/hello-v0.1.0.zip" example.com/acme/hello@v0.1.0)
STEP="curl -T hello-v0.1.0.zip (201)"
check curl -fsS -u "admin:$APITOKEN" -T "$WORK/hello-v0.1.0.zip" "$BASE/repository/go-hosted/example.com/acme/hello/@v/v0.1.0.zip"
STEP="publishing the same version again is refused (409)"
code=$(curl -s -o /dev/null -w "%{http_code}" -u "admin:$APITOKEN" -T "$WORK/hello-v0.1.0.zip" "$BASE/repository/go-hosted/example.com/acme/hello/@v/v0.1.0.zip")
[ "$code" = "409" ] && ok "$STEP ($code)" || bad "$STEP ($code)"
STEP="anonymous publishing is refused (401)"
code=$(curl -s -o /dev/null -w "%{http_code}" -T "$WORK/hello-v0.1.0.zip" "$BASE/repository/go-hosted/example.com/acme/hello/@v/v0.2.0.zip")
[ "$code" = "401" ] && ok "$STEP ($code)" || bad "$STEP ($code)"
STEP="go list -m -versions example.com/acme/hello via go-group"
check sh -c "GONOSUMDB=example.com go list -m -versions example.com/acme/hello | grep -q 'example.com/acme/hello v0.1.0'"

echo "== Consume it through go-group"
mkdir -p consumer && cd consumer
cat > go.mod <<'GOMOD'
module example.com/consumer

go 1.21
GOMOD
cat > main.go <<'GOSRC'
package main

import (
	"fmt"

	"example.com/acme/hello"
	"rsc.io/quote"
)

func main() {
	fmt.Println(hello.Greeting())
	fmt.Println(quote.Go())
}
GOSRC
export GONOSUMDB=example.com
STEP="go get example.com/acme/hello@v0.1.0"; check go get example.com/acme/hello@v0.1.0
STEP="go get rsc.io/quote@v1.5.2"; check go get rsc.io/quote@v1.5.2
STEP="go get example.com/acme/hello@latest"; check go get example.com/acme/hello@latest
STEP="go build"; check go build -o app .
STEP="the program runs"; check sh -c "./app | grep -q 'hello from granary'"
STEP="go.sum has the private module and the verified public ones"
check sh -c "grep -q '^example.com/acme/hello v0.1.0 h1:' go.sum && grep -q '^rsc.io/quote v1.5.2 h1:w5fcysjrx7yqtD' go.sum"
STEP="go mod verify"; check go mod verify
HELLO_SUM=$(grep '^example.com/acme/hello v0.1.0 h1:' go.sum | awk '{print $3}')
cd "$WORK"

# The same consumer built by another go version in a container (GO_DOCKER_IMAGE), with an empty module cache.
in_container(){
  docker run --rm --network host -v "$WORK/docker-consumer:/src" -w /src \
    -e GOPROXY="$1" -e GOFLAGS=-mod=mod -e GOTOOLCHAIN=local -e GONOSUMDB=example.com \
    -e HTTPS_PROXY=http://127.0.0.1:9 -e NO_PROXY="localhost,127.0.0.1,${HOSTPORT%%:*}" \
    "$GO_DOCKER_IMAGE" sh -c "$2"
}
if [ -n "${GO_DOCKER_IMAGE:-}" ]; then
  echo "== The consumer again with $GO_DOCKER_IMAGE"
  mkdir -p docker-consumer && cp consumer/main.go docker-consumer/ && printf 'module example.com/consumer\n\ngo 1.21\n' > docker-consumer/go.mod
  STEP="$GO_DOCKER_IMAGE: go get, go build and go mod verify through go-group"
  check in_container "$GROUP" "go version && go get example.com/acme/hello@v0.1.0 rsc.io/quote@v1.5.2 && go build -o app . && ./app | grep -q 'hello from granary' && go mod verify"
  STEP="$GO_DOCKER_IMAGE: go.sum has the hash sum.golang.org signed for rsc.io/quote"; check grep -q '^rsc.io/quote v1.5.2 h1:w5fcysjrx7yqtD' docker-consumer/go.sum
fi

echo "== Publish the files the go command keeps in its module cache to another hosted repository"
curl -fsS -X POST "$BASE/api/v1/repositories" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"go-copy","format":"go","type":"HOSTED"}' >/dev/null && ok "hosted repository go-copy" || bad "hosted repository go-copy"
CACHED="$GOMODCACHE/cache/download/example.com/acme/hello/@v"
STEP="curl -T {v0.1.0.zip,v0.1.0.mod,v0.1.0.info} from GOMODCACHE"
check curl -fsS -u "admin:$APITOKEN" -T "$CACHED/{v0.1.0.zip,v0.1.0.mod,v0.1.0.info}" "$BASE/repository/go-copy/example.com/acme/hello/@v/"
CACHED_TIME=$(sed -E 's/.*"Time":"([^"]+)".*/\1/' "$CACHED/v0.1.0.info")
STEP="go-copy serves the .info from the module cache (Time $CACHED_TIME)"
check sh -c "curl -fsS '$BASE/repository/go-copy/example.com/acme/hello/@v/v0.1.0.info' | grep -q '\"Time\":\"$CACHED_TIME\"'"
fresh copy
STEP="go mod download from go-copy"; check env GOPROXY="$BASE/repository/go-copy" go mod download -json example.com/acme/hello@v0.1.0
cp "$WORK/last.log" "$WORK/copy.json"
STEP="the module hashes the same as when it came from go-hosted ($HELLO_SUM)"; check grep -q "\"Sum\": \"$HELLO_SUM\"" "$WORK/copy.json"
STEP="a different go.mod for the published version is refused (409), consumers' go.sum pins it"
printf 'module example.com/acme/hello\n\ngo 1.22\n' > "$WORK/changed.mod"
code=$(curl -s -o /dev/null -w "%{http_code}" -u "admin:$APITOKEN" -T "$WORK/changed.mod" "$BASE/repository/go-copy/example.com/acme/hello/@v/v0.1.0.mod")
[ "$code" = "409" ] && ok "$STEP ($code)" || bad "$STEP ($code)"

echo "== Credentials when anonymous reads are off (the go command sends them over HTTPS only)"
# Unless Granary itself is already served over HTTPS, put a throw-away TLS front with a self-signed certificate
# before it; production deployments use a real reverse proxy instead.
TLS_PID=""
if [ "$SCHEME" = https ]; then
  TLS_BASE="$BASE"
else
  TLS_PORT=${GO_TLS_PORT:-18103}
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$WORK/tls.key" -out "$WORK/tls.crt" -days 2 -subj /CN=localhost \
    -addext subjectAltName=DNS:localhost,IP:127.0.0.1 >/dev/null 2>&1
  cat > "$WORK/tls-front.py" <<'PY'
import asyncio, ssl, sys

cert, key, listen, target = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
host, port = target.rsplit(":", 1)

async def pipe(reader, writer):
    try:
        while chunk := await reader.read(65536):
            writer.write(chunk)
            await writer.drain()
    except (ConnectionError, ssl.SSLError):
        pass
    finally:
        writer.close()

async def handle(client_reader, client_writer):
    upstream_reader, upstream_writer = await asyncio.open_connection(host, int(port))
    await asyncio.gather(pipe(client_reader, upstream_writer), pipe(upstream_reader, client_writer))

async def main():
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    server = await asyncio.start_server(handle, "127.0.0.1", listen, ssl=context)
    async with server:
        await server.serve_forever()

asyncio.run(main())
PY
  python3 -I "$WORK/tls-front.py" "$WORK/tls.crt" "$WORK/tls.key" "$TLS_PORT" "$HOSTPORT" >"$WORK/tls-front.log" 2>&1 &
  TLS_PID=$!
  for _ in $(seq 1 50); do curl -fsS --cacert "$WORK/tls.crt" -o /dev/null "https://localhost:$TLS_PORT/api/v1/system/info" 2>/dev/null && break; sleep 0.2; done
  TLS_BASE="https://localhost:$TLS_PORT"
  export SSL_CERT_FILE="$WORK/tls.crt"
fi
TLS_HOSTPORT=${TLS_BASE#https://}; TLS_HOSTPORT=${TLS_HOSTPORT%%/*}; TLS_HOST=${TLS_HOSTPORT%%:*}
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"anonymousRead":false}' >/dev/null && ok "anonymous read off" || bad "anonymous read off"
fresh auth
export GONOSUMDB=example.com
# the go command matches the machine against host:port, curl against the host name alone
{ [ "$TLS_HOSTPORT" != "$TLS_HOST" ] && printf 'machine %s login admin password %s\n' "$TLS_HOSTPORT" "$APITOKEN"
  printf 'machine %s login admin password %s\n' "$TLS_HOST" "$APITOKEN"; } > "$WORK/netrc"; chmod 600 "$WORK/netrc"
STEP="without credentials go is refused (401)"; fails env GOPROXY="$TLS_BASE/repository/go-group" NETRC=/dev/null go mod download example.com/acme/hello@v0.1.0
grep -q "401" "$WORK/last.log" && ok "the go command reports the 401" || bad "the go command reports the 401"
STEP="credentials in a plain-HTTP GOPROXY URL are refused by the go command itself"
fails env GOPROXY="http://admin:$APITOKEN@$HOSTPORT/repository/go-group" go mod download example.com/acme/hello@v0.1.0
STEP="with ~/.netrc over HTTPS go downloads the private module"
check env GOPROXY="$TLS_BASE/repository/go-group" NETRC="$WORK/netrc" go mod download -json example.com/acme/hello@v0.1.0
STEP="with ~/.netrc over HTTPS go downloads and verifies rsc.io/quote through the sumdb passthrough"
check env -u GONOSUMDB GOPROXY="$TLS_BASE/repository/go-group" NETRC="$WORK/netrc" go mod download -json rsc.io/quote@v1.5.2
fresh auth-url
STEP="with user:token@ in an HTTPS GOPROXY URL go downloads too"
check env GOPROXY="https://admin:$APITOKEN@${TLS_BASE#https://}/repository/go-group" NETRC=/dev/null go mod download -json example.com/acme/hello@v0.1.0
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"anonymousRead":true}' >/dev/null && ok "anonymous read on" || bad "anonymous read on"
[ -n "$TLS_PID" ] && { kill "$TLS_PID" 2>/dev/null; unset SSL_CERT_FILE; }

echo "== Offline: everything again with upstream access disabled and empty module caches"
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":true}' >/dev/null && ok "offline mode on" || bad "offline mode on"
fresh offline
unset GONOSUMDB
STEP="offline go mod download rsc.io/quote@v1.5.2 (checksum verified from the cached sumdb)"; check go mod download -json rsc.io/quote@v1.5.2
cp "$WORK/last.log" "$WORK/quote-offline.json"
STEP="offline download has the same hash"; check grep -q '"Sum": "h1:w5fcysjrx7yqtD/aO+QwRjYZOKnaM9Uh2b40tElTs3Y="' "$WORK/quote-offline.json"
STEP="offline the go command verified it against the cached checksum database"
check test -f "$GOMODCACHE/cache/download/sumdb/sum.golang.org/lookup/rsc.io/quote@v1.5.2"
STEP="offline go mod download github.com/BurntSushi/toml@v1.3.2"; check go mod download github.com/BurntSushi/toml@v1.3.2
export GONOSUMDB=example.com
# no subshells here: check counts into PASS/FAIL of this shell
cd "$WORK/consumer"; rm -f app
STEP="offline go build of the consumer from an empty module cache"; check go build -o app .
STEP="offline program runs"; check sh -c "./app | grep -q 'hello from granary'"
STEP="offline go get example.com/acme/hello@v0.1.0"; check go get example.com/acme/hello@v0.1.0
STEP="offline go mod verify"; check go mod verify
cd "$WORK"
STEP="offline an uncached module fails cleanly (503)"; fails go mod download golang.org/x/mod@v0.17.0
grep -q "503" "$WORK/last.log" && ok "offline the go command reports the 503" || bad "offline the go command reports the 503"
if [ -n "${GO_DOCKER_IMAGE:-}" ]; then
  STEP="$GO_DOCKER_IMAGE: offline go build and go mod verify from an empty module cache"
  check in_container "$GROUP" "rm -f app && go build -o app . && ./app | grep -q 'hello from granary' && go mod verify"
fi
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":false}' >/dev/null && ok "offline mode off" || bad "offline mode off"

echo; echo "passed=$PASS failed=$FAIL  (work dir $WORK)"
[ "$FAIL" = 0 ]
