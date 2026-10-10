#!/usr/bin/env bash
# End-to-end verification of the Composer format with the real Composer 2 client, including an offline run against
# the Granary cache.
#
# Publishes acme/lib (with curl, as a raw PUT and as a form upload) to composer-hosted, installs it, psr/log and
# haydenpierce/class-finder through composer-group (hosted + packagist proxy) and through the packagist proxy alone,
# checks search, show, audit and the three ways of passing credentials (auth.json http-basic, bearer, COMPOSER_AUTH),
# then switches Granary to offline mode and installs everything again with empty Composer caches on a client that
# cannot reach the internet at all (dead HTTP(S)_PROXY for Composer and git, only Granary excluded).
#
# psr/log's archive is a GitHub zipball. Where GitHub archive downloads are not allowed (some CI sandboxes answer
# them with HTTP 403) the step is reported as BLOCKED instead of PASS or FAIL, Composer falls back to a git clone, and
# the offline part leaves psr/log out; haydenpierce/class-finder (archives on GitLab) covers the proxied-archive path.
#
# Needs curl, php and composer on the PATH and a running Granary with admin/admin123.
# Usage: verify-composer.sh http://localhost:8081
set -uo pipefail
BASE=${1:-http://localhost:8081}
BASE=${BASE%/}
HOSTPORT=${BASE#http://}; HOSTPORT=${HOSTPORT#https://}; HOSTPORT=${HOSTPORT%%/*}
HOSTNAME_ONLY=${HOSTPORT%%:*}
WORK=$(mktemp -d /tmp/granary-verify-composer.XXXX)
cd "$WORK"
PASS=0; FAIL=0; BLOCKED=0
ok()      { echo "PASS     $1"; PASS=$((PASS+1)); }
bad()     { echo "FAIL     $1"; FAIL=$((FAIL+1)); }
blocked() { echo "BLOCKED  $1"; BLOCKED=$((BLOCKED+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# checkin <dir> <command…>: check in another directory without a subshell, so the counters survive
checkin(){ local dir=$1; shift; pushd "$dir" >/dev/null || { bad "$STEP (no $dir)"; return; }; check "$@"; popd >/dev/null; }
# expect_fail <grep pattern> <command…>: the command must fail and its output must explain why
expect_fail(){ local pattern=$1; shift; if "$@" >"$WORK/last.log" 2>&1; then bad "$STEP (succeeded)"; tail -5 "$WORK/last.log";
  elif grep -q -E "$pattern" "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
for tool in curl php composer; do command -v "$tool" >/dev/null || { echo "missing required tool: $tool"; exit 2; }; done

# nothing from the caller's environment may point Composer elsewhere
unset COMPOSER COMPOSER_AUTH COMPOSER_MIRROR_PATH_REPOS COMPOSER_NO_DEV COMPOSER_ROOT_VERSION
export COMPOSER_HOME="$WORK/composer-home" COMPOSER_CACHE_DIR="$WORK/composer-cache" COMPOSER_NO_INTERACTION=1
export COMPOSER_ROOT_VERSION=1.0.0 COMPOSER_DISABLE_XDEBUG_WARN=1
SECURE_HTTP=true
case "$BASE" in http://*) SECURE_HTTP=false;; esac

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null \
  && ok "standard repositories (packagist, composer-hosted, composer-group)" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify-composer"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
system(){ curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" >/dev/null; }
# whatever happens, leave the server online and readable the way the run found it
trap 'system "{\"offlineMode\":false}" 2>/dev/null; system "{\"anonymousRead\":true}" 2>/dev/null' EXIT
HOSTED="$BASE/repository/composer-hosted"
GROUP="$BASE/repository/composer-group"
PROXY="$BASE/repository/packagist"
# a repeated run starts from the same state: the packages this script publishes are removed first
for pkg in acme/lib acme/util; do curl -s -o /dev/null -u "admin:$APITOKEN" -X DELETE "$HOSTED/packages/$pkg"; done

# project <dir> <repository url>: a Composer project that uses only the given Granary repository
project() {
  mkdir -p "$1"
  printf '{\n    "name": "acme/%s",\n    "type": "project",\n    "require": {}\n}\n' "$(basename "$1")" > "$1/composer.json"
  (cd "$1" && composer config repositories.granary composer "$2" && composer config repo.packagist false \
     && composer config secure-http "$SECURE_HTTP" && composer config http-basic."$HOSTPORT" admin "$APITOKEN") >/dev/null 2>&1
}

echo "== Publish acme/lib 1.0.0 and acme/util 0.1.0 to composer-hosted"
mkdir -p lib/src util/src build
cat > lib/composer.json <<'JSON'
{
    "name": "acme/lib",
    "description": "Acme demo library for the Granary Composer verification",
    "type": "library",
    "license": "MIT",
    "keywords": ["acme", "granary", "demo"],
    "authors": [{"name": "Acme Developers", "email": "dev@acme.example"}],
    "require": {"php": ">=8.1", "haydenpierce/class-finder": "^0.5"},
    "autoload": {"psr-4": {"Acme\\Lib\\": "src/"}}
}
JSON
printf '<?php\nnamespace Acme\\Lib;\n\nfinal class Greeter\n{\n    public static function hello(string $who): string\n    {\n        return "Hello, $who";\n    }\n}\n' > lib/src/Greeter.php
cat > util/composer.json <<'JSON'
{
    "name": "acme/util",
    "version": "0.1.0",
    "description": "Acme demo utilities, published as a form upload",
    "type": "library",
    "license": "MIT",
    "autoload": {"psr-4": {"Acme\\Util\\": "src/"}}
}
JSON
printf '<?php\nnamespace Acme\\Util;\n\nfinal class Strings\n{\n    public static function shout(string $s): string\n    {\n        return strtoupper($s);\n    }\n}\n' > util/src/Strings.php
STEP="composer archive acme/lib"; checkin lib composer archive --format=zip --dir=../build --file=acme-lib-1.0.0
STEP="composer archive acme/util"; checkin util composer archive --format=zip --dir=../build --file=acme-util-0.1.0
STEP="curl PUT acme/lib 1.0.0 (201)"
code=$(curl -s -o "$WORK/upload.json" -w '%{http_code}' -u "admin:$APITOKEN" --upload-file build/acme-lib-1.0.0.zip "$HOSTED/packages/acme/lib/1.0.0")
[ "$code" = 201 ] && ok "$STEP" || { bad "$STEP ($code)"; cat "$WORK/upload.json"; }
STEP="curl PUT the same version again is refused (409)"
code=$(curl -s -o "$WORK/upload.json" -w '%{http_code}' -u "admin:$APITOKEN" --upload-file build/acme-lib-1.0.0.zip "$HOSTED/packages/acme/lib/1.0.0")
[ "$code" = 409 ] && grep -q composer.versionExists "$WORK/upload.json" && ok "$STEP" || bad "$STEP ($code)"
STEP="curl PUT to the group is refused and names the hosted member (405)"
code=$(curl -s -o "$WORK/upload.json" -w '%{http_code}' -u "admin:$APITOKEN" --upload-file build/acme-lib-1.0.0.zip "$GROUP/packages/acme/lib/1.0.1")
[ "$code" = 405 ] && grep -q "composer-hosted" "$WORK/upload.json" && ok "$STEP" || bad "$STEP ($code)"
STEP="curl PUT without credentials is refused (401)"
code=$(curl -s -o /dev/null -w '%{http_code}' --upload-file build/acme-lib-1.0.0.zip "$HOSTED/packages/acme/lib/1.0.2")
[ "$code" = 401 ] && ok "$STEP" || bad "$STEP ($code)"
STEP="curl -F form upload of acme/util (version from composer.json)"
code=$(curl -s -o "$WORK/upload.json" -w '%{http_code}' -u "admin:$APITOKEN" -F "package=@build/acme-util-0.1.0.zip" "$HOSTED/packages")
[ "$code" = 201 ] && ok "$STEP" || { bad "$STEP ($code)"; cat "$WORK/upload.json"; }
STEP="hosted p2 metadata lists acme/lib 1.0.0 with a dist on Granary"
curl -fsS "$HOSTED/p2/acme/lib.json" > "$WORK/p2.json" 2>&1 && grep -q '"version":"1.0.0"' "$WORK/p2.json" \
  && grep -q "\"url\":\"$HOSTED/dist/acme/lib/1.0.0/" "$WORK/p2.json" && ok "$STEP" || bad "$STEP"

echo "== Install through composer-group (hosted + packagist proxy)"
project app "$GROUP"
STEP="composer require psr/log:^3.0"; checkin app composer require -vvv psr/log:^3.0
cp "$WORK/last.log" "$WORK/require-psr-log.log"
STEP="psr/log metadata through the packagist proxy"
grep -q "\[200\] $GROUP/p2/psr/log.json" "$WORK/require-psr-log.log" && ok "$STEP" || bad "$STEP"
PSR_ARCHIVE=no
if grep -q "\[200\] $GROUP/dist/psr/log/" "$WORK/require-psr-log.log"; then
  PSR_ARCHIVE=yes; ok "psr/log archive (GitHub zipball) downloaded through Granary"
elif grep -q -E "api\.github\.com.*HTTP 403" "$WORK/require-psr-log.log"; then
  blocked "psr/log archive: GitHub refused the zipball download from this network (HTTP 403); Granary answered 503 upstream.unavailable and Composer fell back to git"
else
  bad "psr/log archive through Granary"; grep -E "dist/psr/log|upstream" "$WORK/require-psr-log.log" | tail -5
fi
STEP="composer require haydenpierce/class-finder:^0.5 (archive on GitLab)"; checkin app composer require -vvv haydenpierce/class-finder:^0.5
STEP="class-finder archive downloaded through Granary"
grep -q "\[200\] $GROUP/dist/haydenpierce/class-finder/" "$WORK/last.log" && ok "$STEP" || bad "$STEP"
STEP="composer require acme/lib:^1.0 (hosted, depends on a proxied package)"; checkin app composer require -vvv acme/lib:^1.0
STEP="acme/lib archive downloaded through the group"
grep -q "\[200\] $GROUP/dist/acme/lib/1.0.0/" "$WORK/last.log" && ok "$STEP" || bad "$STEP"
STEP="composer require acme/util (form upload)"; checkin app composer require acme/util:^0.1
STEP="installed code runs"
checkin app php -r 'require "vendor/autoload.php"; echo Acme\Lib\Greeter::hello("Granary"), " ", Acme\Util\Strings::shout("ok"), PHP_EOL;
  if (!class_exists(HaydenPierce\ClassFinder\ClassFinder::class) || !interface_exists(Psr\Log\LoggerInterface::class)) { exit(1); }'
STEP="composer update answers from Granary with 304s"; checkin app composer update -vvv
grep -q "\[304\] $GROUP/p2/" "$WORK/last.log" && ok "conditional metadata requests (If-Modified-Since -> 304)" || bad "conditional metadata requests"
STEP="composer search acme finds acme/lib"; checkin app sh -c 'composer search acme | grep -q "^acme/lib "'
STEP="composer show -a acme/lib lists 1.0.0"; checkin app sh -c 'composer show -a acme/lib | grep -E "^versions *: .*1\.0\.0"'
STEP="composer audit through the group's advisory API"; checkin app composer audit

echo "== Install through the packagist proxy alone"
project proxied "$PROXY"
STEP="composer require haydenpierce/class-finder via packagist proxy"
checkin proxied env COMPOSER_CACHE_DIR="$WORK/cache-proxied" composer require -vvv haydenpierce/class-finder:^0.5
grep -q "\[200\] $PROXY/dist/haydenpierce/class-finder/" "$WORK/last.log" && ok "class-finder archive from the proxy" || bad "class-finder archive from the proxy"

echo "== Credentials: anonymous read off"
system '{"anonymousRead":false}' && ok "anonymous read off" || bad "anonymous read off"
project auth-basic "$GROUP"
STEP="http-basic (auth.json) with an API token"; checkin auth-basic env COMPOSER_CACHE_DIR="$WORK/cache-auth1" composer require acme/lib:^1.0
mkdir -p auth-bearer && cp auth-basic/composer.json auth-bearer/ && rm -f auth-bearer/composer.lock
(cd auth-bearer && composer config bearer."$HOSTPORT" "$APITOKEN") >/dev/null 2>&1
STEP="bearer token (auth.json)"; checkin auth-bearer env COMPOSER_CACHE_DIR="$WORK/cache-auth2" composer install
mkdir -p auth-env && cp auth-basic/composer.json auth-env/
STEP="COMPOSER_AUTH environment variable"
checkin auth-env env COMPOSER_CACHE_DIR="$WORK/cache-auth3" COMPOSER_AUTH="{\"http-basic\":{\"$HOSTPORT\":{\"username\":\"admin\",\"password\":\"$APITOKEN\"}}}" composer install
mkdir -p auth-none && cp auth-basic/composer.json auth-none/
STEP="no credentials is refused"
pushd auth-none >/dev/null; expect_fail "401|[Aa]uthentication required|credentials" env COMPOSER_CACHE_DIR="$WORK/cache-auth4" composer install; popd >/dev/null
system '{"anonymousRead":true}' && ok "anonymous read on again" || bad "anonymous read on again"

echo "== Offline: install again with Granary in offline mode, empty Composer caches and no internet on the client"
system '{"offlineMode":true}' && ok "offline mode on" || bad "offline mode on"
export COMPOSER_HOME="$WORK/composer-home-offline" COMPOSER_CACHE_DIR="$WORK/composer-cache-offline"
DEAD=http://127.0.0.1:9
export HTTP_PROXY=$DEAD HTTPS_PROXY=$DEAD http_proxy=$DEAD https_proxy=$DEAD
export NO_PROXY="$HOSTNAME_ONLY,localhost,127.0.0.1" no_proxy="$HOSTNAME_ONLY,localhost,127.0.0.1"
mkdir -p offline && cp app/composer.json app/composer.lock app/auth.json offline/
if [ "$PSR_ARCHIVE" = no ]; then
  # psr/log's archive never reached the cache here, so it cannot be installed offline; the lock file is dropped too
  (cd offline && composer remove --no-update psr/log && rm -f composer.lock) >/dev/null 2>&1
  echo "note: psr/log left out of the offline project (its archive could not be cached on this network)"
else
  STEP="offline composer install from the lock file"; checkin offline composer install
  rm -rf offline/vendor
fi
STEP="offline composer install (resolves from the cached metadata)"; rm -rf offline/vendor offline/composer.lock; checkin offline composer install -vvv
STEP="offline archives all came from Granary"
! grep -q -E "Cloning|Failed to download" "$WORK/last.log" && grep -q "\[200\] $GROUP/dist/acme/lib/" "$WORK/last.log" \
  && grep -q "\[200\] $GROUP/dist/haydenpierce/class-finder/" "$WORK/last.log" && ok "$STEP" || bad "$STEP"
STEP="offline installed code runs"
checkin offline php -r 'require "vendor/autoload.php"; echo Acme\Lib\Greeter::hello("offline"), PHP_EOL; exit(class_exists(HaydenPierce\ClassFinder\ClassFinder::class) ? 0 : 1);'
STEP="offline composer install again from its own lock file, empty cache"
rm -rf offline/vendor; checkin offline env COMPOSER_CACHE_DIR="$WORK/composer-cache-offline2" composer install
STEP="offline composer show -a acme/lib (asks for the ~dev document too)"; checkin offline composer show -a acme/lib
# the failing requires run in a copy: Composer keeps a requirement whose resolution succeeded even when the download fails
cp -r offline offline-errors
if [ "$PSR_ARCHIVE" = no ]; then
  STEP="offline require psr/log: metadata cached, archive not, clean error"
  pushd offline-errors >/dev/null; expect_fail "upstream\.offline" composer require psr/log:^3.0; popd >/dev/null
fi
STEP="offline require of an uncached package is a clean 503 error"
pushd offline-errors >/dev/null; expect_fail "upstream\.offline" composer require monolog/monolog:^3.0; popd >/dev/null
STEP="offline uncached metadata is 503 upstream.offline"
code=$(curl -s -o "$WORK/offline.json" -w '%{http_code}' --noproxy '*' "$GROUP/p2/symfony/console.json")
[ "$code" = 503 ] && grep -q upstream.offline "$WORK/offline.json" && ok "$STEP ($code)" || bad "$STEP ($code)"
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy NO_PROXY no_proxy
system '{"offlineMode":false}' && ok "offline mode off" || bad "offline mode off"

echo; echo "passed=$PASS failed=$FAIL blocked=$BLOCKED  (work dir $WORK)"
[ "$FAIL" = 0 ]
