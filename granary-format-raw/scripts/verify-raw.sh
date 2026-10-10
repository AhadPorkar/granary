#!/usr/bin/env bash
# End-to-end verification of the raw format family (raw, cdn, cpan, cran, hackage, vcpkg, bower, elm) with the
# real clients: curl/wget, cpanm, R (docker r-base:4.5.1), cabal and stack (docker haskell:9.6), bower (npx),
# vcpkg (when VCPKG_ROOT names a checkout with a vcpkg binary), the Elm compiler (from npm, run in docker behind
# a local TLS reverse proxy on 127.0.0.1:443) and an offline re-run. Needs curl, wget, perl, python3, sha256sum,
# git, zip and optionally docker, npx, node and openssl; tools that are missing are reported as SKIP, and so are
# public upstreams this host cannot reach (each such skip also checks that the proxy answers a clean 503). The
# cabal and stack steps download the full Hackage index (about 140 MB, 1 GB unpacked) several times and delete
# each copy right after its step; the work directory goes to $TMPDIR (default /tmp), e.g. TMPDIR=/dev/shm.
# Usage: verify-raw.sh http://localhost:8108   (GRANARY_USER/GRANARY_PASSWORD, default admin/admin123)
set -uo pipefail
BASE=${1:-http://localhost:8108}
USER_NAME=${GRANARY_USER:-admin}
USER_PASSWORD=${GRANARY_PASSWORD:-admin123}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/granary-verify-raw.XXXX")
cd "$WORK"
# names and paths unique to this run, so the script can run again against the same server
RUN=$(date +%s)$$
RUNL=$(printf '%s' "$RUN" | tr 0-9 a-j)
PASS=0; FAIL=0; SKIP=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
skip() { echo "SKIP  $1"; SKIP=$((SKIP+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
expect_code() { local want=$1; shift; local got; got=$(code "$@"); [ "$got" = "$want" ] && ok "$STEP ($got)" || bad "$STEP (expected $want, got $got)"; }
have() { command -v "$1" >/dev/null 2>&1; }
header() { curl -s -o /dev/null -D - "$@" | tr -d '\r'; }
image() { docker image inspect "$1" >/dev/null 2>&1 || docker pull -q "$1" >/dev/null 2>&1; }
settings() { curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" >/dev/null; }
# the connect snippet the server shows for a repository, with the API token filled in
snippet() {
  curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/repositories/$1/connect" \
    | python3 -I -c 'import json,sys; print(next(s["code"] for s in json.load(sys.stdin) if s["id"]==sys.argv[1]))' "$2" \
    | sed "s/<api-token>/$APITOKEN/g"
}
TOKEN=""
# whatever happens, leave the server online and readable the way it was found
restore() { [ -n "$TOKEN" ] && settings '{"offlineMode":false,"anonymousRead":true}' 2>/dev/null; }
trap restore EXIT

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASSWORD\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null && ok "standard repositories" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify-raw"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
AUTH=(-u "$USER_NAME:$APITOKEN")
# the standard set has no hosted cpan/cran/bower/elm repositories; create the ones the hosted checks need
for spec in cpan-hosted:cpan cran-hosted:cran bower-hosted:bower elm-hosted:elm; do
  curl -fsS -X POST "$BASE/api/v1/repositories" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"name\":\"${spec%%:*}\",\"format\":\"${spec##*:}\",\"type\":\"HOSTED\"}" >/dev/null 2>&1 || true
done
# groups over hosted + proxy, whose registry indexes are merged
for spec in cpan-group:cpan:cpan-hosted:cpan-proxy cran-group:cran:cran-hosted:cran-proxy; do
  IFS=: read -r gname gformat gfirst gsecond <<<"$spec"
  curl -fsS -X POST "$BASE/api/v1/repositories" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$gname\",\"format\":\"$gformat\",\"type\":\"GROUP\",\"members\":[\"$gfirst\",\"$gsecond\"]}" >/dev/null 2>&1 || true
done
for repo in raw-hosted cdnjs jsdelivr unpkg cpan-proxy cran-proxy hackage-proxy stackage-proxy vcpkg-cache bower-registry elm-proxy cpan-hosted cran-hosted bower-hosted elm-hosted cpan-group cran-group; do
  STEP="repository $repo exists"; expect_code 200 -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/repositories/$repo"
done

echo "== raw (curl, wget)"
head -c 65536 /dev/urandom > blob.bin
curl -fsS "${AUTH[@]}" -T blob.bin "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin" >/dev/null && ok "curl -T upload" || bad "curl -T upload"
STEP="re-upload is 409"; expect_code 409 "${AUTH[@]}" -T blob.bin "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin"
STEP="anonymous upload is 401"; expect_code 401 -T blob.bin "$BASE/repository/raw-hosted/releases/app/$RUN/other.bin"
curl -fsS -o down.bin "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin" && cmp -s blob.bin down.bin && ok "curl download matches" || bad "curl download matches"
if have wget; then
  wget -q -O wget.bin "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin" && cmp -s blob.bin wget.bin && ok "wget download matches" || bad "wget download matches"
else skip "wget"; fi
curl -fsS -F "file=@blob.bin;filename=form.bin" "${AUTH[@]}" "$BASE/repository/raw-hosted/releases/app/$RUN/form.bin" >/dev/null && ok "curl -F multipart upload" || bad "curl -F multipart upload"
curl -fsS "$BASE/repository/raw-hosted/releases/app/$RUN/" | grep -q 'href="blob.bin"' && ok "HTML listing" || bad "HTML listing"
curl -fsS -H 'Accept: application/json' "$BASE/repository/raw-hosted/releases/app/$RUN/" | grep -q '"name":"form.bin"' && ok "JSON listing" || bad "JSON listing"
STEP="HEAD"; expect_code 200 -I "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin"
header "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin" | grep -qi '^content-disposition: inline; filename="blob.bin"$' && ok "Content-Disposition names the file" || bad "Content-Disposition names the file"
ETAG=$(header "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin" | sed -n 's/^[Ee][Tt][Aa][Gg]: //p')
STEP="If-None-Match with the ETag is 304"; expect_code 304 -H "If-None-Match: $ETAG" "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin"
STEP="If-None-Match: * is 304"; expect_code 304 -H 'If-None-Match: *' "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin"
STEP="Range request is 206"; expect_code 206 -r 0-99 "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin"
STEP="encoded slash in the path is 400"; expect_code 400 "${AUTH[@]}" -T blob.bin "$BASE/repository/raw-hosted/releases/app%2F1.0.0/x.bin"
STEP="dot-dot segment is 400"; expect_code 400 --path-as-is "$BASE/repository/raw-hosted/releases/../app/1.0.0/blob.bin"
STEP="wrong password is 401"; expect_code 401 -u "$USER_NAME:wrong" -T blob.bin "$BASE/repository/raw-hosted/releases/app/$RUN/wrong.bin"
STEP="unsupported method is 405"; expect_code 405 -X PATCH "${AUTH[@]}" "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin"
printf '<html><body><script>document.title=localStorage.length</script></body></html>' > page.html
curl -fsS "${AUTH[@]}" -H 'Content-Type: text/html' -T page.html "$BASE/repository/raw-hosted/site/$RUN/page.html" >/dev/null
header "$BASE/repository/raw-hosted/site/$RUN/page.html" | grep -qi '^content-security-policy: sandbox ' && ok "uploaded HTML is served sandboxed" || bad "uploaded HTML is served sandboxed"
# eight parallel uploads of one new path: exactly one may win
for i in 1 2 3 4 5 6 7 8; do (code "${AUTH[@]}" -T blob.bin "$BASE/repository/raw-hosted/race/$RUN/blob.bin"; echo) > "race.$i" & done; wait
[ "$(cat race.* | grep -c '^201$')" = 1 ] && [ "$(cat race.* | grep -c '^409$')" = 7 ] && ok "concurrent uploads of one path: one 201, seven 409" || bad "concurrent uploads of one path: $(cat race.* | tr '\n' ' ')"
head -c 33554432 /dev/urandom > big.bin
curl -fsS "${AUTH[@]}" -T big.bin "$BASE/repository/raw-hosted/big/$RUN/big.bin" >/dev/null && curl -fsS "$BASE/repository/raw-hosted/big/$RUN/big.bin" | cmp -s - big.bin && ok "32 MB upload and download match" || bad "32 MB upload and download match"
rm -f big.bin
STEP="DELETE"; expect_code 204 "${AUTH[@]}" -X DELETE "$BASE/repository/raw-hosted/releases/app/$RUN/form.bin"
STEP="deleted file is 404"; expect_code 404 "$BASE/repository/raw-hosted/releases/app/$RUN/form.bin"

echo "== cdn (jsDelivr proxy, sha256 against the direct download)"
LODASH="npm/lodash@4.17.21/lodash.min.js"
if curl -fsS -o lodash.direct.js "https://cdn.jsdelivr.net/$LODASH" 2>"$WORK/cdn-direct.log"; then
  curl -fsS -o lodash.proxy.js "$BASE/repository/jsdelivr/$LODASH" \
    && [ "$(sha256sum < lodash.direct.js)" = "$(sha256sum < lodash.proxy.js)" ] && ok "jsdelivr lodash sha256 matches direct download" || bad "jsdelivr lodash sha256 matches direct download"
  STEP="jsdelivr cached copy served again"; expect_code 200 "$BASE/repository/jsdelivr/$LODASH"
  curl -fsS -o lodash.unpinned.js "$BASE/repository/jsdelivr/npm/lodash/lodash.min.js" && ok "jsdelivr unpinned (redirected) path" || bad "jsdelivr unpinned (redirected) path"
  curl -fsS -o jquery.cdnjs.js "$BASE/repository/cdnjs/jquery/3.7.1/jquery.min.js" && ok "cdnjs jquery through proxy" || bad "cdnjs jquery through proxy"
  curl -fsS -o lodash.unpkg.js "$BASE/repository/unpkg/lodash@4.17.21/lodash.min.js" && ok "unpkg lodash through proxy" || bad "unpkg lodash through proxy"
else
  skip "cdn: cdn.jsdelivr.net is not reachable from this host ($(tail -1 "$WORK/cdn-direct.log"))"
  STEP="cdn: uncached file behind an unreachable upstream is a clean 503"; expect_code 503 "$BASE/repository/jsdelivr/$LODASH"
fi

echo "== cpan (cpanm --mirror-only)"
mkdir -p cpan && cd cpan
if have perl; then
  if curl -fsSL -o cpanm https://cpanmin.us 2>/dev/null || curl -fsSL -o cpanm https://raw.githubusercontent.com/miyagawa/cpanminus/master/cpanm; then
    ok "cpanm bootstrap script downloaded"
    STEP="cpanm App::cpanminus through cpan-proxy"; check perl cpanm --mirror "$BASE/repository/cpan-proxy/" --mirror-only -n -l "$WORK/cpan/local" App::cpanminus
    STEP="cpanm Acme::Meow through cpan-proxy"; check perl cpanm --mirror "$BASE/repository/cpan-proxy/" --mirror-only -n -l "$WORK/cpan/local" Acme::Meow
    [ -f "$WORK/cpan/local/lib/perl5/Acme/Meow.pm" ] && ok "Acme::Meow installed into -l dir" || bad "Acme::Meow installed into -l dir"
    # hosted: upload a distribution and install it from the regenerated 02packages index
    CPAN_MOD="Acme::Granary$RUNL"; CPAN_DIST="Acme-Granary$RUNL-0.01"
    mkdir -p "$CPAN_DIST/lib/Acme" && cat > "$CPAN_DIST/lib/Acme/Granary$RUNL.pm" <<PM
package $CPAN_MOD;
use strict; use warnings;
our \$VERSION = '0.01';
sub hello { 'hello from granary' }
1;
PM
    cat > "$CPAN_DIST/Makefile.PL" <<MK
use ExtUtils::MakeMaker;
WriteMakefile(NAME => '$CPAN_MOD', VERSION_FROM => 'lib/Acme/Granary$RUNL.pm');
MK
    tar czf "$CPAN_DIST.tar.gz" "$CPAN_DIST"
    curl -fsS "${AUTH[@]}" -T "$CPAN_DIST.tar.gz" "$BASE/repository/cpan-hosted/authors/id/G/GR/GRANARY/$CPAN_DIST.tar.gz" >/dev/null && ok "upload distribution to cpan-hosted" || bad "upload distribution to cpan-hosted"
    curl -fsS "$BASE/repository/cpan-hosted/modules/02packages.details.txt.gz" | gzip -dc | grep -q "^$CPAN_MOD *0.01 *G/GR/GRANARY/$CPAN_DIST.tar.gz" && ok "02packages regenerated" || bad "02packages regenerated"
    STEP="cpanm $CPAN_MOD from cpan-hosted"; check perl cpanm --mirror "$BASE/repository/cpan-hosted/" --mirror-only -n -l "$WORK/cpan/local2" "$CPAN_MOD"
    # group: one mirror URL for the hosted distribution and the proxied CPAN, through the merged 02packages
    curl -fsS "$BASE/repository/cpan-group/modules/02packages.details.txt.gz" | gzip -dc > group-02packages.txt
    grep -q "^$CPAN_MOD " group-02packages.txt && grep -q '^Acme::Meow ' group-02packages.txt && ok "cpan-group 02packages merges hosted and proxy" || bad "cpan-group 02packages merges hosted and proxy"
    STEP="cpanm $CPAN_MOD and Acme::Meow through cpan-group"; check perl cpanm --mirror "$BASE/repository/cpan-group/" --mirror-only -n -l "$WORK/cpan/local4" "$CPAN_MOD" Acme::Meow
    [ -f "$WORK/cpan/local4/lib/perl5/Acme/Granary$RUNL.pm" ] && [ -f "$WORK/cpan/local4/lib/perl5/Acme/Meow.pm" ] && ok "both modules installed from cpan-group" || bad "both modules installed from cpan-group"
    STEP="upload through cpan-group is 405"; expect_code 405 "${AUTH[@]}" -T "$CPAN_DIST.tar.gz" "$BASE/repository/cpan-group/authors/id/G/GR/GRANARY/$CPAN_DIST.tar.gz"
  else
    skip "cpanm bootstrap (cpanmin.us and raw.githubusercontent.com unreachable)"
  fi
else skip "perl"; fi
cd "$WORK"

echo "== cran (R in docker r-base:4.5.1)"
if have docker && image r-base:4.5.1; then
  mkdir -p cran && cd cran
  if curl -fsS -o /dev/null "https://cloud.r-project.org/src/contrib/PACKAGES" 2>"$WORK/cran-direct.log"; then
    STEP="install.packages(praise) from cran-proxy"; check docker run --rm --network host r-base:4.5.1 \
      Rscript -e 'install.packages("praise", repos="'"$BASE"'/repository/cran-proxy/", lib=tempdir()); library(praise, lib.loc=tempdir()); cat(praise())'
  else
    skip "cran-proxy (cloud.r-project.org unreachable from this host: $(tail -1 "$WORK/cran-direct.log"))"
    STEP="cran-proxy: uncached PACKAGES behind an unreachable upstream is a clean 503"; expect_code 503 "$BASE/repository/cran-proxy/src/contrib/PACKAGES"
  fi
  # hosted: build a tiny source package with R CMD build, upload it, install it from the regenerated PACKAGES
  CRAN_PKG="granaryhello$RUNL"
  mkdir -p "$CRAN_PKG/R" && cat > "$CRAN_PKG/DESCRIPTION" <<DESC
Package: $CRAN_PKG
Type: Package
Title: Says Hello
Version: 0.1.0
Author: Granary
Maintainer: Granary <granary@example.com>
Description: A package built only to verify the CRAN hosted repository.
License: MIT + file LICENSE
Encoding: UTF-8
DESC
  echo "YEAR: 2026" > "$CRAN_PKG/LICENSE"
  echo "hello <- function() 'hello from granary'" > "$CRAN_PKG/R/hello.R"
  printf 'export(hello)\n' > "$CRAN_PKG/NAMESPACE"
  rinstall() { docker run --rm --network host r-base:4.5.1 Rscript -e "install.packages(\"$CRAN_PKG\", repos=\"$BASE/repository/$1/\", lib=tempdir()); library($CRAN_PKG, lib.loc=tempdir()); cat(hello())"; }
  STEP="R CMD build"; check docker run --rm --network host -v "$WORK/cran:/w" -w /w r-base:4.5.1 R CMD build "$CRAN_PKG"
  [ -f "${CRAN_PKG}_0.1.0.tar.gz" ] && curl -fsS "${AUTH[@]}" -T "${CRAN_PKG}_0.1.0.tar.gz" "$BASE/repository/cran-hosted/src/contrib/${CRAN_PKG}_0.1.0.tar.gz" >/dev/null && ok "upload to cran-hosted" || bad "upload to cran-hosted"
  curl -fsS "$BASE/repository/cran-hosted/src/contrib/PACKAGES" | grep -q "^Package: $CRAN_PKG\$" && ok "PACKAGES regenerated" || bad "PACKAGES regenerated"
  STEP="install.packages($CRAN_PKG) from cran-hosted"; check rinstall cran-hosted
  # group: R reads the merged PACKAGES.gz; PACKAGES.rds is 404 in a group (503 while a proxy member's upstream is
  # unreachable) and R falls back to PACKAGES.gz either way; an unreachable proxy member is left out of the merge
  RDS=$(code "$BASE/repository/cran-group/src/contrib/PACKAGES.rds")
  case "$RDS" in 404|503) ok "cran-group PACKAGES.rds is not served ($RDS)";; *) bad "cran-group PACKAGES.rds is not served (got $RDS)";; esac
  curl -fsS "$BASE/repository/cran-group/src/contrib/PACKAGES.gz" | gzip -dc | grep -q "^Package: $CRAN_PKG\$" && ok "cran-group PACKAGES.gz lists the hosted package" || bad "cran-group PACKAGES.gz lists the hosted package"
  STEP="install.packages($CRAN_PKG) through cran-group"; check rinstall cran-group
  if [ ! -s "$WORK/cran-direct.log" ]; then
    STEP="install.packages(praise) through cran-group"; check docker run --rm --network host r-base:4.5.1 \
      Rscript -e 'install.packages("praise", repos="'"$BASE"'/repository/cran-group/", lib=tempdir()); library(praise, lib.loc=tempdir()); cat(praise())'
  fi
  cd "$WORK"
else skip "R (docker or image r-base:4.5.1 missing)"; fi

echo "== hackage (cabal and stack in docker haskell:9.6)"
if have docker && image haskell:9.6; then
  hs() { local dir=$1; shift; docker run --rm --network host -v "$WORK/$dir:/w" -w /w -e CABAL_DIR=/w/.cabal -e CABAL_CONFIG=/w/config -e STACK_ROOT=/w/.stack haskell:9.6 "$@"; }
  # 1. secure, under cabal's own name for Hackage: the TUF metadata passes through, cabal's built-in root keys verify it
  mkdir -p hs1 && snippet hackage-proxy cabal-secure > hs1/config
  grep -q '^repository hackage.haskell.org' hs1/config && ok "cabal-secure snippet" || bad "cabal-secure snippet"
  STEP="cabal update (secure, hackage.haskell.org -> hackage-proxy)"; check hs hs1 cabal update
  STEP="cabal get acme-missiles-0.3 through hackage-proxy"; check hs hs1 cabal get acme-missiles-0.3
  [ -f hs1/acme-missiles-0.3/acme-missiles.cabal ] && ok "package unpacked from the proxied tarball" || bad "package unpacked from the proxied tarball"
  rm -rf hs1
  # 2. secure under a name of its own, with the root keys the snippet lists
  mkdir -p hs2 && snippet hackage-proxy cabal-secure-named > hs2/config
  STEP="cabal update (secure, own name, snippet root keys)"; check hs hs2 cabal update
  rm -rf hs2
  # 3. and 4. with anonymous read off: credentials in the URL for secure: False (cabal) and for stack
  settings '{"anonymousRead":false}' && ok "anonymous read off" || bad "anonymous read off"
  STEP="anonymous index download is 401"; expect_code 401 "$BASE/repository/hackage-proxy/root.json"
  mkdir -p hs3 && snippet hackage-proxy cabal-insecure > hs3/config
  grep -q "url: http://$USER_NAME:gry_" hs3/config && ok "cabal-insecure snippet carries the credentials" || bad "cabal-insecure snippet carries the credentials"
  STEP="cabal update (secure: False, 00-index.tar.gz, credentials in the URL)"; check hs hs3 cabal update
  rm -rf hs3
  mkdir -p hs4/.stack && snippet hackage-proxy stack-package-index > hs4/.stack/config.yaml
  STEP="stack update (package-index download-prefix, credentials in the URL)"; check hs hs4 stack --allow-different-user update
  # stack keeps the verified 01-index under the name 00-index.tar
  [ -n "$(find hs4/.stack -name '0[01]-index.tar' -size +1M 2>/dev/null)" ] && ok "stack stored the verified index" || bad "stack stored the verified index"
  rm -rf hs4
  settings '{"anonymousRead":true}' && ok "anonymous read on" || bad "anonymous read on"
  STEP="package tarball through hackage-proxy"; expect_code 200 -o /dev/null "$BASE/repository/hackage-proxy/package/acme-missiles-0.3.tar.gz"
  if curl -fsS -o /dev/null "https://www.stackage.org/lts-22.43/cabal.config" 2>"$WORK/stackage-direct.log"; then
    STEP="Stackage cabal.config through stackage-proxy"; expect_code 200 -o /dev/null "$BASE/repository/stackage-proxy/lts-22.43/cabal.config"
  else
    skip "stackage-proxy (www.stackage.org unreachable from this host: $(tail -1 "$WORK/stackage-direct.log"))"
    STEP="stackage-proxy: uncached file behind an unreachable upstream is a clean 503"; expect_code 503 "$BASE/repository/stackage-proxy/lts-22.43/cabal.config"
  fi
else skip "cabal and stack (docker or image haskell:9.6 missing)"; fi

echo "== bower (npx bower, registry lookup)"
if have npx && have git; then
  mkdir -p bower && cd bower
  BOWER_PKG="granary-widget-$RUNL"
  git init -q --bare widget.git && git clone -q widget.git widget-src 2>/dev/null
  (cd widget-src && git -c user.name=granary -c user.email=granary@example.com commit -q --allow-empty -m init \
    && echo "{\"name\":\"$BOWER_PKG\",\"version\":\"1.0.0\",\"main\":\"widget.js\"}" > bower.json && echo 'window.widget=1;' > widget.js \
    && git add . && git -c user.name=granary -c user.email=granary@example.com commit -q -m widget && git tag v1.0.0 && git -c push.negotiate=false push -q origin HEAD:master --tags)
  curl -fsS "${AUTH[@]}" -X POST "$BASE/repository/bower-hosted/packages" -d "name=$BOWER_PKG" -d "url=$WORK/bower/widget.git" >/dev/null && ok "bower register (POST packages)" || bad "bower register (POST packages)"
  curl -fsS "$BASE/repository/bower-hosted/packages/$BOWER_PKG" | grep -q "\"name\":\"$BOWER_PKG\"" && ok "GET packages/<name>" || bad "GET packages/<name>"
  curl -fsS "$BASE/repository/bower-hosted/packages/search/widg" | grep -q "$BOWER_PKG" && ok "GET packages/search/<q>" || bad "GET packages/search/<q>"
  mkdir -p app && cd app
  STEP="npx bower install $BOWER_PKG through bower-hosted"; check npx --yes bower@1.8.14 install "$BOWER_PKG" --allow-root --config.registry="$BASE/repository/bower-hosted/" --config.interactive=false
  [ -f "bower_components/$BOWER_PKG/widget.js" ] && ok "bower_components populated" || bad "bower_components populated"
  cd "$WORK/bower"
  if curl -fsS -o /dev/null "https://registry.bower.io/packages/jquery" 2>/dev/null; then
    STEP="npx bower install jquery through bower-registry"; check npx --yes bower@1.8.14 install jquery --allow-root --config.registry="$BASE/repository/bower-registry/" --config.interactive=false
  else
    skip "bower-registry proxy (registry.bower.io unreachable from this host)"
    STEP="bower-registry: uncached lookup behind an unreachable upstream is a clean 503"; expect_code 503 "$BASE/repository/bower-registry/packages/jquery"
  fi
  cd "$WORK"
else skip "bower (npx or git missing)"; fi

echo "== vcpkg (curl with Azure blob headers)"
SHA512=$(sha512sum < blob.bin | cut -d' ' -f1)
STEP="asset PUT with x-ms-blob-type is 201"; expect_code 201 "${AUTH[@]}" -X PUT -H 'x-ms-blob-type: BlockBlob' -H 'x-ms-version: 2020-04-08' --data-binary @blob.bin "$BASE/repository/vcpkg-cache/assets/$SHA512"
STEP="identical asset PUT again is 201"; expect_code 201 "${AUTH[@]}" -X PUT -H 'x-ms-blob-type: BlockBlob' --data-binary @blob.bin "$BASE/repository/vcpkg-cache/assets/$SHA512"
STEP="different content for the same asset is 409"; expect_code 409 "${AUTH[@]}" -X PUT -H 'x-ms-blob-type: BlockBlob' --data-binary 'different-content' "$BASE/repository/vcpkg-cache/assets/$SHA512"
curl -fsS -o asset.bin "$BASE/repository/vcpkg-cache/assets/$SHA512" && cmp -s blob.bin asset.bin && ok "asset GET matches" || bad "asset GET matches"
STEP="binary cache HEAD miss is 404"; expect_code 404 -I "$BASE/repository/vcpkg-cache/binaries/zlib/1.3.1/$RUN"
STEP="binary cache PUT is 201"; expect_code 201 "${AUTH[@]}" -X PUT -H 'Content-Type: application/zip' --data-binary @blob.bin "$BASE/repository/vcpkg-cache/binaries/zlib/1.3.1/$RUN"
STEP="binary cache HEAD hit is 200"; expect_code 200 -I "$BASE/repository/vcpkg-cache/binaries/zlib/1.3.1/$RUN"

echo "== vcpkg (the real vcpkg with an overlay port; needs VCPKG_ROOT, a vcpkg binary, cmake, ninja and a C++ compiler)"
VCPKG_EXE=${VCPKG_EXE:-$(command -v vcpkg || echo "${VCPKG_ROOT:-/nonexistent}/vcpkg")}
if [ -n "${VCPKG_ROOT:-}" ] && [ -x "$VCPKG_EXE" ] && have cmake && have ninja; then
  mkdir -p vcpkg/ports/granary-hello && cd vcpkg
  # the port's origin is a file in raw-hosted; new bytes and a new port each run, so nothing is cached yet
  RUN_ID=$RUN
  head -c 4096 /dev/urandom > origin.h
  curl -fsS "${AUTH[@]}" -T origin.h "$BASE/repository/raw-hosted/vcpkg-origin/$RUN_ID/granary-hello.h" >/dev/null
  SRC_SHA=$(sha512sum < origin.h | cut -d' ' -f1)
  echo '{"name":"granary-hello","version":"1.0.0"}' > ports/granary-hello/vcpkg.json
  cat > ports/granary-hello/portfile.cmake <<PORT
# run $RUN_ID
vcpkg_download_distfile(SOURCE URLS "$BASE/repository/raw-hosted/vcpkg-origin/$RUN_ID/granary-hello.h"
    FILENAME "granary-hello-$RUN_ID.h" SHA512 $SRC_SHA)
file(INSTALL "\${SOURCE}" DESTINATION "\${CURRENT_PACKAGES_DIR}/include" RENAME granary-hello.h)
file(WRITE "\${CURRENT_PACKAGES_DIR}/share/\${PORT}/copyright" "test data")
PORT
  vrun() { VCPKG_FORCE_SYSTEM_BINARIES=1 VCPKG_DISABLE_METRICS=1 "$VCPKG_EXE" install granary-hello --classic \
    --vcpkg-root="$VCPKG_ROOT" --overlay-ports="$WORK/vcpkg/ports" --x-install-root="$WORK/vcpkg/$1/installed" \
    --x-buildtrees-root="$WORK/vcpkg/$1/buildtrees" --x-packages-root="$WORK/vcpkg/$1/packages" --downloads-root="$WORK/vcpkg/$1/downloads"; }
  restored() { vrun "$1" > "$WORK/vcpkg/$1.log" 2>&1 && grep -q 'Restored 1 package' "$WORK/vcpkg/$1.log"; }
  # 1. the write lines of the connect snippet: asset cache miss, origin download, uploads to both caches
  eval "$(snippet vcpkg-cache bash | grep '^export' | sed -n 1,2p)"
  STEP="vcpkg install with the snippet's caches (builds, uploads source and package)"; check vrun r1
  STEP="vcpkg stored the source in the asset cache"; expect_code 200 -o /dev/null "$BASE/repository/vcpkg-cache/assets/$SRC_SHA"
  curl -fsS -H 'Accept: application/json' "$BASE/repository/vcpkg-cache/binaries/granary-hello/1.0.0/" | grep -q '"type":"file"' && ok "vcpkg stored the package in the binary cache" || bad "vcpkg stored the package in the binary cache"
  # 2. a second machine: the built package comes from the binary cache
  STEP="vcpkg restores the package from the binary cache"; check restored r2
  # 3. read-only lines, the origin blocked and the binary cache off: the source must come from the asset cache
  eval "$(snippet vcpkg-cache bash | grep '^export' | sed -n 3,4p)"
  export X_VCPKG_ASSET_SOURCES="$X_VCPKG_ASSET_SOURCES;x-block-origin" VCPKG_BINARY_SOURCES=clear
  STEP="vcpkg builds from the asset cache with the origin blocked"; check vrun r3
  cmp -s origin.h r3/installed/x64-linux/include/granary-hello.h && ok "installed header matches the origin" || bad "installed header matches the origin"
  unset X_VCPKG_ASSET_SOURCES VCPKG_BINARY_SOURCES
  rm -rf r1 r2 r3
  cd "$WORK"
else skip "real vcpkg (set VCPKG_ROOT to a vcpkg checkout with a vcpkg binary, and install cmake and ninja)"; fi

echo "== elm"
ELM_PKG="granary/widget-$RUNL"
mkdir -p elm/pkg && (cd elm/pkg && echo "{\"type\":\"package\",\"name\":\"$ELM_PKG\",\"version\":\"1.0.0\"}" > elm.json && echo '[]' > docs.json && printf 'README' > README.md && zip -q -r ../widget-1.0.0.zip .)
for f in elm.json docs.json README.md; do curl -fsS "${AUTH[@]}" -T "elm/pkg/$f" "$BASE/repository/elm-hosted/packages/$ELM_PKG/1.0.0/$f" >/dev/null; done
curl -fsS "${AUTH[@]}" -T elm/widget-1.0.0.zip "$BASE/repository/elm-hosted/packages/$ELM_PKG/1.0.0/widget-1.0.0.zip" >/dev/null && ok "elm-hosted release uploaded" || bad "elm-hosted release uploaded"
curl -fsS -X POST "$BASE/repository/elm-hosted/all-packages" | grep -q "\"$ELM_PKG\":\\[\"1.0.0\"\\]" && ok "POST all-packages lists the release" || bad "POST all-packages lists the release"
curl -fsS -X POST "$BASE/repository/elm-hosted/all-packages/since/0" | grep -q "$ELM_PKG@1.0.0" && ok "POST all-packages/since/0" || bad "POST all-packages/since/0"
curl -fsS "$BASE/repository/elm-hosted/packages/$ELM_PKG/1.0.0/endpoint.json" | grep -q "\"url\":\"$BASE/repository/elm-hosted/packages/$ELM_PKG/1.0.0/widget-1.0.0.zip\",\"hash\":\"$(sha1sum < elm/widget-1.0.0.zip | cut -d' ' -f1)\"" && ok "generated endpoint.json (url + sha1)" || bad "generated endpoint.json (url + sha1)"
if curl -fsS -o /dev/null -X POST "https://package.elm-lang.org/all-packages/since/0" 2>/dev/null; then
  curl -fsS -X POST "$BASE/repository/elm-proxy/all-packages/since/0" | grep -q '@' && ok "elm-proxy all-packages/since/0" || bad "elm-proxy all-packages/since/0"
  curl -fsS "$BASE/repository/elm-proxy/packages/elm/core/1.0.5/endpoint.json" | grep -q "\"url\":\"$BASE/repository/elm-proxy/github/elm/core/zip/1.0.5\"" && ok "elm-proxy endpoint.json rewritten" || bad "elm-proxy endpoint.json rewritten"
  curl -fsS -o core.zip "$BASE/repository/elm-proxy/github/elm/core/zip/1.0.5" && ok "elm-proxy GitHub zip route" || bad "elm-proxy GitHub zip route"
else
  skip "elm-proxy (package.elm-lang.org unreachable from this host)"
  STEP="elm-proxy: uncached document behind an unreachable upstream is a clean 503"; expect_code 503 -X POST "$BASE/repository/elm-proxy/all-packages/since/0"
fi

echo "== elm (the real compiler through a TLS reverse proxy standing in for package.elm-lang.org)"
# the compiler has no registry setting: like the nginx snippet, a reverse proxy with a certificate the compiler
# trusts answers for package.elm-lang.org and keeps Granary's Host, so the zips are fetched from Granary itself
port443_free() { ! (exec 3<>/dev/tcp/127.0.0.1/443) 2>/dev/null; }
if have docker && have node && have npm && have openssl && have git && [ "$(id -u)" = 0 ] && [ "$(uname -m)" = x86_64 ] && port443_free && image alpine:3.21; then
  mkdir -p elmreal && cd elmreal
  npm install --silent --prefix "$WORK/elmreal/compiler" @elm_binaries/linux_x64@0.19.1-0 >/dev/null 2>&1
  ELM="$WORK/elmreal/compiler/node_modules/@elm_binaries/linux_x64/elm"
  if [ -x "$ELM" ] && git clone -q --depth 1 --branch 1.0.5 https://github.com/elm/core.git core 2>/dev/null \
      && git clone -q --depth 1 --branch 1.1.3 https://github.com/elm/json.git json 2>/dev/null; then
    curl -fsS -X POST "$BASE/api/v1/repositories" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -d '{"name":"elm-group","format":"elm","type":"GROUP","members":["elm-hosted","elm-proxy"]}' >/dev/null 2>&1 || true
    # elm/core and elm/json as hosted releases (a second run finds them already there: 409)
    for p in core:1.0.5 json:1.1.3; do n=${p%%:*}; v=${p##*:}
      (cd "$n" && git archive --format=zip --prefix="$n-$v/" HEAD) > "$n-$v.zip"
      for f in "$n/elm.json:elm.json" "$n-$v.zip:$n-$v.zip"; do
        c=$(code "${AUTH[@]}" -T "${f%%:*}" "$BASE/repository/elm-hosted/packages/elm/$n/$v/${f##*:}")
        [ "$c" = 201 ] || [ "$c" = 409 ] || bad "upload elm/$n $v ${f##*:} ($c)"
      done
    done
    openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days 1 -subj "/CN=verify-raw test CA" \
      -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" >/dev/null 2>&1
    openssl req -newkey rsa:2048 -nodes -keyout site.key -out site.csr -subj "/CN=package.elm-lang.org" >/dev/null 2>&1
    printf 'subjectAltName=DNS:package.elm-lang.org\nbasicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth\nkeyUsage=digitalSignature,keyEncipherment\n' > site.ext
    openssl x509 -req -in site.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out site.pem -days 1 -extfile site.ext >/dev/null 2>&1
    mkdir -p trust && cp ca.pem trust/
    cat > tls-proxy.js <<'JS'
const https = require('https'), http = require('http'), fs = require('fs');
const [target, logFile] = [new URL(process.argv[2]), process.argv[3]];
https.createServer({key: fs.readFileSync('site.key'), cert: fs.readFileSync('site.pem')}, (req, res) => {
  const up = http.request({host: target.hostname, port: target.port, method: req.method,
      path: target.pathname.replace(/\/$/, '') + req.url, headers: {...req.headers, host: target.host}}, r => {
    fs.appendFileSync(logFile, `${req.method} ${req.url} ${r.statusCode}\n`);
    res.writeHead(r.statusCode, r.headers); r.pipe(res);
  });
  up.on('error', () => { res.writeHead(502); res.end(); });
  req.pipe(up);
}).listen(443, '127.0.0.1');
JS
    node tls-proxy.js "$BASE/repository/elm-group/" "$WORK/elmreal/proxy.log" & TLS_PROXY=$!
    trap 'kill $TLS_PROXY 2>/dev/null; restore' EXIT
    for i in $(seq 1 50); do port443_free || break; sleep 0.1; done
    elm() { docker run --rm --network host --add-host package.elm-lang.org:127.0.0.1 -e HOME=/h -e SYSTEM_CERTIFICATE_PATH=/trust \
      -v "$WORK/elmreal/$1:/app" -v "$WORK/elmreal/home:/h" -v "$WORK/elmreal/trust:/trust:ro" -v "$ELM:/usr/local/bin/elm:ro" \
      -w /app alpine:3.21 elm make src/Main.elm --output=main.js; }
    app() { mkdir -p "$1/src" && printf '{"type":"application","source-directories":["src"],"elm-version":"0.19.1","dependencies":{"direct":{%s},"indirect":{}},"test-dependencies":{"direct":{},"indirect":{}}}\n' "$2" > "$1/elm.json"
      printf 'module Main exposing (main)\n\nimport Platform\n%s\n\nmain : Program () String ()\nmain =\n    Platform.worker\n        { init = \\_ -> ( %s, Cmd.none )\n        , update = \\_ m -> ( m, Cmd.none )\n        , subscriptions = \\_ -> Sub.none\n        }\n' "$3" "$4" > "$1/src/Main.elm"; }
    mkdir -p home
    # 1. an empty compiler cache: POST all-packages through elm-group, endpoint.json, the zips from Granary
    app app1 '"elm/core":"1.0.5","elm/json":"1.1.3"' 'import Json.Decode as D' 'Result.withDefault "" (D.decodeString D.string "\"granary\"")'
    STEP="elm make through elm-group with an empty package cache"; check elm app1
    [ -s app1/main.js ] && grep -q 'POST /all-packages 200' proxy.log && ok "compiler read all-packages and compiled" || bad "compiler read all-packages and compiled"
    # 2. a release published after the compiler cached the registry: all-packages/since/<count> hands it out
    PKG="greet-$RUNL"
    mkdir -p "greet/$PKG-1.0.0/src"
    printf '{"type":"package","name":"granary/%s","summary":"Greeting for the registry check","license":"BSD-3-Clause","version":"1.0.0","exposed-modules":["Greet"],"elm-version":"0.19.0 <= v < 0.20.0","dependencies":{"elm/core":"1.0.0 <= v < 2.0.0"},"test-dependencies":{}}\n' "$PKG" > "greet/$PKG-1.0.0/elm.json"
    printf 'module Greet exposing (hello)\n\n{-| Greeting.\n\n@docs hello\n\n-}\n\n\n{-| Says hello.\n-}\nhello : String -> String\nhello name =\n    "hello from " ++ name\n' > "greet/$PKG-1.0.0/src/Greet.elm"
    (cd greet && zip -q -r "$PKG-1.0.0.zip" "$PKG-1.0.0")
    curl -fsS "${AUTH[@]}" -T "greet/$PKG-1.0.0/elm.json" "$BASE/repository/elm-hosted/packages/granary/$PKG/1.0.0/elm.json" >/dev/null \
      && curl -fsS "${AUTH[@]}" -T "greet/$PKG-1.0.0.zip" "$BASE/repository/elm-hosted/packages/granary/$PKG/1.0.0/$PKG-1.0.0.zip" >/dev/null \
      && ok "new Elm release uploaded to elm-hosted" || bad "new Elm release uploaded to elm-hosted"
    app app2 '"elm/core":"1.0.5","elm/json":"1.1.3","granary/'"$PKG"'":"1.0.0"' 'import Greet' 'Greet.hello "granary"'
    STEP="elm make with the new release, from the cached registry"; check elm app2
    grep -q 'POST /all-packages/since/' proxy.log && grep -q 'hello from' app2/main.js 2>/dev/null && ok "compiler picked up the release via all-packages/since" || bad "compiler picked up the release via all-packages/since"
    kill $TLS_PROXY 2>/dev/null; trap restore EXIT
  else skip "real Elm compiler (could not fetch the compiler from npm or clone elm/core and elm/json)"; fi
  cd "$WORK"
else skip "real Elm compiler (needs docker, node, npm, openssl, git, root on x86_64 and 127.0.0.1:443 free)"; fi

echo "== Offline: cached content again with upstream access disabled"
settings '{"offlineMode":true}' && ok "offline mode on" || bad "offline mode on"
STEP="offline raw download"; expect_code 200 -o /dev/null "$BASE/repository/raw-hosted/releases/app/$RUN/blob.bin"
if [ -f cpan/cpanm ] && [ -f "$WORK/cpan/local/lib/perl5/Acme/Meow.pm" ]; then
  STEP="offline cpanm Acme::Meow from the cache"; check perl cpan/cpanm --mirror "$BASE/repository/cpan-proxy/" --mirror-only -n -l "$WORK/cpan/local3" Acme::Meow
  STEP="offline cpanm $CPAN_MOD and Acme::Meow through cpan-group"; check perl cpan/cpanm --mirror "$BASE/repository/cpan-group/" --mirror-only -n -l "$WORK/cpan/local5" "$CPAN_MOD" Acme::Meow
fi
if [ -f lodash.proxy.js ]; then
  STEP="offline cdn cached file"; expect_code 200 -o /dev/null "$BASE/repository/jsdelivr/$LODASH"
fi
if have docker && docker image inspect haskell:9.6 >/dev/null 2>&1; then
  mkdir -p hs5 && snippet hackage-proxy cabal-secure > hs5/config
  STEP="offline cabal update and cabal get from the cached index"; check hs hs5 sh -c 'cabal update && cabal get acme-missiles-0.3'
  rm -rf hs5
fi
STEP="offline uncached proxy file is a clean 503"; expect_code 503 "$BASE/repository/cran-proxy/src/contrib/never-seen_9.9.9.tar.gz"
settings '{"offlineMode":false}'

echo; echo "passed=$PASS failed=$FAIL skipped=$SKIP  (work dir $WORK)"
[ "$FAIL" = 0 ]
