#!/usr/bin/env bash
# End-to-end verification of the CocoaPods format with the real pod client (CocoaPods 1.x), including an offline
# run against the Granary cache.
#
# What it does, all through cocoapods-group (cocoapods-hosted, cocoapods-standin, cocoapods-cdn):
#   * pod repo add-cdn / pod repo update / pod repo list against the group, with ETag revalidation;
#   * publishes GranaryDemoKit to cocoapods-hosted: a Ruby podspec converted with pod ipc spec, pushed with its
#     source archive (POST api/pods), with an archive uploaded on its own (PUT sources/…) and as a git-source
#     pre-release (PUT Specs/…); re-push, group push, anonymous push and bad pushes are refused; a version is deleted;
#   * pod search, pod spec cat and pod spec which over hosted and proxied pods, with source.http rewritten to the
#     group, and the rewritten archive URLs downloaded with curl (cached once);
#   * a dead archive host answers 502 and leaves the spec repository's proxy healthy;
#   * pod install of an Xcode project generated with the xcodeproj gem: GranaryDemoKit from the hosted repository
#     plus its dependencies GranaryStandInKit (zip) and GranaryStandInTar (tar.gz) through the proxy;
#   * anonymous read switched off: pod is refused without credentials and works with a ~/.netrc entry;
#   * offline mode with the upstream stopped and every local pod cache wiped: repo add-cdn, search, spec cat and
#     pod install again, all from the Granary cache;
#   * cdn.cocoapods.org itself through cocoapods-cdn: the index files, and the podspecs, which the CDN redirects to
#     cdn.jsdelivr.net. Where that host is unreachable the podspec steps are reported as BLOCK, not FAIL.
#
# cocoapods-standin is a proxy of stand-in-cdn.py (next to this script), which serves a CDN-style spec repository
# with podspecs behind a redirect and http source archives, exactly like cdn.cocoapods.org and jsDelivr do.
#
# pod: used from the PATH when present; otherwise the cocoapods gem is installed into $POD_GEMS (default: the work
# directory) with the local gem, or, without Ruby, run in Docker (${POD_IMAGE:-ruby:3.3}, host networking, gem
# installed into the work directory unless the image has pod or $POD_GEMS/bin/pod exists; the gem builds native
# extensions, so the image needs make and a C compiler, which the -slim images lack). pod itself needs git and
# libcurl; pod install also needs curl, unzip, tar and rsync.
# Needs curl, python3 and sha256sum, and a running Granary with admin/admin123.
# Usage: verify-cocoapods.sh http://localhost:8114
set -uo pipefail
BASE=${1:-http://localhost:8114}
BASE=${BASE%/}
HOSTPORT=${BASE#*://}; HOSTPORT=${HOSTPORT%%/*}
HOST=${HOSTPORT%:*}
HERE=$(cd "$(dirname "$0")" && pwd)
STANDIN_PORT=${STANDIN_PORT:-8994}
STANDIN="http://127.0.0.1:$STANDIN_PORT"
WORK=$(mktemp -d /tmp/granary-verify-cocoapods.XXXX)
cd "$WORK"
PASS=0; FAIL=0; BLOCKED=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
block(){ echo "BLOCK $1"; BLOCKED=$((BLOCKED+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# expect <text> <command…>: the command succeeds and prints the text
expect(){ local text=$1; shift; if "$@" >"$WORK/last.log" 2>&1 && grep -qF -- "$text" "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# refuse <text> <command…>: the command fails and prints the text
refuse(){ local text=$1; shift; if "$@" >"$WORK/last.log" 2>&1; then bad "$STEP (succeeded)"; tail -5 "$WORK/last.log"
  elif [ -z "$text" ] || grep -qF -- "$text" "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# status <code> <text> <curl args…>: curl gets this HTTP status and a body containing the text
status(){ local want=$1 text=$2; shift 2; local got; got=$(curl -sS -o "$WORK/last.body" -w '%{http_code}' "$@")
  if [ "$got" = "$want" ] && { [ -z "$text" ] || grep -qF -- "$text" "$WORK/last.body"; }; then ok "$STEP"; else bad "$STEP (HTTP $got, expected $want)"; head -c 400 "$WORK/last.body"; echo; fi; }
# specval <file> <key…>: a value of the podspec JSON in the file (what pod spec cat printed)
specval(){ python3 -c 'import json, sys
text = open(sys.argv[1], encoding="utf-8").read()
node = json.loads(text[text.index("{"):text.rindex("}") + 1])
for key in sys.argv[2:]:
    node = node[key]
print(node)' "$@" 2>/dev/null; }
# spec <expected> <key path> <pod args…>: pod prints a podspec whose value at the (space-separated) key path is expected
spec(){ local want=$1 keys=$2; shift 2
  if podrun . "$@" >"$WORK/spec.json" 2>&1 && [ "$(specval "$WORK/spec.json" $keys)" = "$want" ]; then ok "$STEP"
  else bad "$STEP"; tail -20 "$WORK/spec.json"; fi; }
for tool in curl python3 sha256sum; do command -v "$tool" >/dev/null || { echo "missing required tool: $tool"; exit 2; }; done

# --- pod -------------------------------------------------------------------------------------------------------
# Everything pod keeps (spec repos, download cache, .netrc) lives below $PODHOME.
PODHOME="$WORK/home"
mkdir -p "$PODHOME" "$WORK/app"
POD_ENV=(LANG=C.UTF-8 LC_ALL=C.UTF-8 COCOAPODS_DISABLE_STATS=true COCOAPODS_ALLOW_ROOT=1 HOME="$PODHOME")
# inpod <dir> <command…>: runs a command (pod, ruby) of the CocoaPods installation in $WORK/<dir>
if command -v pod >/dev/null; then
  POD_MODE="pod from the PATH"
  inpod(){ local dir=$1; shift; (cd "$WORK/$dir" && env "${POD_ENV[@]}" "$@"); }
elif command -v gem >/dev/null && command -v ruby >/dev/null; then
  POD_GEMS=${POD_GEMS:-$WORK/gems}
  POD_MODE="cocoapods gem in $POD_GEMS"
  if [ ! -x "$POD_GEMS/bin/pod" ]; then
    echo "installing the cocoapods gem into $POD_GEMS"
    gem install cocoapods -N --install-dir "$POD_GEMS" --bindir "$POD_GEMS/bin" ${COCOAPODS_VERSION:+--version "$COCOAPODS_VERSION"} \
      >"$WORK/gem-install.log" 2>&1 || { echo "gem install cocoapods failed"; tail -20 "$WORK/gem-install.log"; exit 2; }
  fi
  inpod(){ local dir=$1; shift; (cd "$WORK/$dir" && env "${POD_ENV[@]}" GEM_HOME="$POD_GEMS" GEM_PATH="$POD_GEMS" PATH="$POD_GEMS/bin:$PATH" "$@"); }
elif command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  POD_IMAGE=${POD_IMAGE:-ruby:3.3}
  POD_MODE="docker $POD_IMAGE"
  DOCKER_ENV=()
  for e in "${POD_ENV[@]}"; do DOCKER_ENV+=(-e "$e"); done
  if docker run --rm "$POD_IMAGE" sh -c 'command -v pod' >/dev/null 2>&1; then
    :
  elif [ -n "${POD_GEMS:-}" ] && [ -x "$POD_GEMS/bin/pod" ]; then
    # a cocoapods gem installed beforehand (for the same Ruby), e.g. for an image without a C compiler
    POD_MODE="$POD_MODE, cocoapods gem in $POD_GEMS"
    # its bin/pod starts the Ruby it was installed with, a path that need not exist in the image
    mkdir -p "$WORK/bin"
    printf '#!/bin/sh\nexec ruby "%s/bin/pod" "$@"\n' "$POD_GEMS" > "$WORK/bin/pod"; chmod +x "$WORK/bin/pod"
    DOCKER_ENV+=(-v "$POD_GEMS:$POD_GEMS:ro" -e GEM_HOME="$POD_GEMS" -e GEM_PATH="$POD_GEMS" -e PATH="$WORK/bin:$POD_GEMS/bin:/usr/local/bin:/usr/bin:/bin")
  else
    # the container downloads the gem itself: pass the proxy and CA settings through when there are any
    PROXY_ENV=()
    for e in HTTPS_PROXY https_proxy NO_PROXY no_proxy; do [ -n "${!e:-}" ] && PROXY_ENV+=(-e "$e"); done
    CA=${SSL_CERT_FILE:-}
    [ -n "$CA" ] && PROXY_ENV+=(-v "$CA:$CA:ro" -e SSL_CERT_FILE="$CA")
    echo "installing the cocoapods gem into $WORK/gems inside $POD_IMAGE"
    docker run --rm --network host -v "$WORK:$WORK" "${PROXY_ENV[@]}" "$POD_IMAGE" \
      gem install cocoapods -N --install-dir "$WORK/gems" --bindir "$WORK/gems/bin" >"$WORK/gem-install.log" 2>&1 \
      || { echo "gem install cocoapods failed"; tail -20 "$WORK/gem-install.log"; exit 2; }
    DOCKER_ENV+=(-e GEM_HOME="$WORK/gems" -e GEM_PATH="$WORK/gems" -e PATH="$WORK/gems/bin:/usr/local/bin:/usr/bin:/bin")
  fi
  inpod(){ local dir=$1; shift; docker run --rm --network host -v "$WORK:$WORK" -w "$WORK/$dir" "${DOCKER_ENV[@]}" "$POD_IMAGE" "$@"; }
else
  echo "pod, a Ruby with gem, or a Docker daemon is needed"; exit 2
fi
# pod refuses to start without git, even for CDN sources
inpod . sh -c 'command -v git' >/dev/null 2>&1 || { echo "pod needs git where it runs ($POD_MODE)"; exit 2; }
# podrun <dir> <pod arguments…>; PODRUN_HOME, when set, gives pod another home (its own spec repos)
podrun(){ local dir=$1; shift; inpod "$dir" env HOME="${PODRUN_HOME:-$PODHOME}" pod "$@" --no-ansi; }

GROUP="$BASE/repository/cocoapods-group"
HOSTED="$BASE/repository/cocoapods-hosted"
CDN="$BASE/repository/cocoapods-cdn"

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null \
  && ok "standard repositories (cocoapods-cdn, cocoapods-hosted, cocoapods-group)" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify-cocoapods"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
settings(){ curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" >/dev/null; }
api(){ local method=$1 path=$2 body=$3; curl -sS -o "$WORK/api.json" -w '%{http_code}' -X "$method" "$BASE/api/v1/$path" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body"; }
# a previous run that stopped half-way may have left the server offline or closed
settings '{"offlineMode":false,"anonymousRead":true}' && ok "server online with anonymous read" || bad "server online with anonymous read"
# a run before this one left its pods behind: start from an empty stand-in proxy and without GranaryDemoKit
group(){ api PUT repositories/cocoapods-group '{"name":"cocoapods-group","format":"cocoapods","type":"GROUP","description":"Single CDN source for Podfiles: your pods and trunk","members":['"$1"']}'; }
group '"cocoapods-hosted","cocoapods-cdn"' >/dev/null
curl -sS -o /dev/null -X DELETE "$BASE/api/v1/repositories/cocoapods-standin" -H "Authorization: Bearer $TOKEN"
for v in 0.9.0 1.0.0 1.1.0 1.2.0-beta.1; do curl -sS -o /dev/null -u "admin:$APITOKEN" -X DELETE "$BASE/repository/cocoapods-hosted/Specs/GranaryDemoKit/$v"; done
code=$(api POST repositories '{"name":"cocoapods-standin","format":"cocoapods","type":"PROXY","remoteUrl":"'"$STANDIN"'/","metadataMaxAgeMinutes":0,"description":"Proxy of stand-in-cdn.py"}')
[ "$code" = 201 ] && ok "proxy cocoapods-standin of $STANDIN/ (metadata max age 0)" || { bad "proxy cocoapods-standin (HTTP $code)"; cat "$WORK/api.json"; }
code=$(group '"cocoapods-hosted","cocoapods-standin","cocoapods-cdn"')
[ "$code" = 200 ] && ok "cocoapods-group = cocoapods-hosted, cocoapods-standin, cocoapods-cdn" || { bad "group members (HTTP $code)"; cat "$WORK/api.json"; }
STEP="connect snippets of cocoapods-group"
expect "pod repo add-cdn granary $GROUP/" curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/repositories/cocoapods-group/connect"
STEP="connect snippets of cocoapods-hosted name the publish URL"
expect "$HOSTED/Specs/MyPod/1.0.0/MyPod.podspec.json" curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/repositories/cocoapods-hosted/connect"
STEP="CocoaPods-version.yml of the group"; expect "prefix_lengths:" curl -fsS "$GROUP/CocoaPods-version.yml"

echo "== Stand-in CDN"
# objc <dir> <Name> <version>: the source tree of a tiny Objective-C pod
objc(){ mkdir -p "$1/Sources"
  printf '#import <Foundation/Foundation.h>\n@interface %s : NSObject\n+ (NSString *)greeting;\n@end\n' "$2" > "$1/Sources/$2.h"
  printf '#import "%s.h"\n@implementation %s\n+ (NSString *)greeting { return @"hello from %s %s"; }\n@end\n' "$2" "$2" "$2" "$3" > "$1/Sources/$2.m"; }
# zipdir <archive> <dir>: a zip of the directory's contents
zipdir(){ python3 -c 'import os, sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    for base, dirs, files in os.walk(sys.argv[2]):
        dirs.sort()
        for f in sorted(files):
            z.write(os.path.join(base, f), os.path.relpath(os.path.join(base, f), sys.argv[2]))' "$1" "$2"; }
sha(){ sha256sum "$1" | cut -d' ' -f1; }
# standin <Name> <version> <zip|tgz> [extra JSON members] [archive base URL]: a pod on the stand-in CDN with an http
# source archive, by default on the stand-in itself; a tarball holds one top-level directory, like GitHub's, which pod
# flattens away (zip archives are not flattened)
standin(){ local name=$1 version=$2 kind=$3 extra=${4:-} archives=${5:-$STANDIN/archives} file
  objc "$WORK/src/$name-$version" "$name" "$version"
  mkdir -p "$WORK/cdn/archives" "$WORK/cdn/Specs/$name/$version"
  if [ "$kind" = zip ]; then file="$name-$version.zip"; zipdir "$WORK/cdn/archives/$file" "$WORK/src/$name-$version"
  else file="$name-$version.tar.gz"; tar -C "$WORK/src" -czf "$WORK/cdn/archives/$file" "$name-$version"; fi
  cat > "$WORK/cdn/Specs/$name/$version/$name.podspec.json" <<JSON
{
  "name": "$name",
  "version": "$version",
  "summary": "A pod served by the stand-in CDN",
  "homepage": "https://example.com/$name",
  "license": { "type": "MIT" },
  "authors": { "Acme Developers": "dev@example.com" },
  "platforms": { "ios": "12.0", "osx": "10.13" },
  "source": { "http": "$archives/$file", "sha256": "$(sha "$WORK/cdn/archives/$file")" },
  "source_files": "Sources/*.{h,m}",
  "requires_arc": true$extra
}
JSON
}
standin GranaryStandInKit 1.0.0 zip
standin GranaryStandInKit 1.1.0 zip
# archives usually live on another host than the spec repository (GitHub releases, S3): "localhost" is another origin
# than the proxy's remote URL (127.0.0.1), so Granary fetches this one without the proxy's upstream credentials
standin GranaryStandInTar 2.0.0 tgz '' "http://localhost:$STANDIN_PORT/archives"
standin GranaryStandInOld 0.1.0 zip ', "deprecated_in_favor_of": "GranaryStandInKit"'
# a dead archive link (nothing listens on port 1), and a pod nothing asks for until the very end of that check
standin GranaryStandInDead 1.0.0 zip '' "http://localhost:1/archives"
standin LateArrival 1.0.0 zip
STANDIN_ZIP_SHA=$(sha "$WORK/cdn/archives/GranaryStandInKit-1.1.0.zip")
if curl -s -o /dev/null "$STANDIN/"; then echo "port $STANDIN_PORT is taken; set STANDIN_PORT"; exit 2; fi
python3 "$HERE/stand-in-cdn.py" --port "$STANDIN_PORT" --root "$WORK/cdn" --log "$WORK/cdn-requests.log" >"$WORK/stand-in.log" 2>&1 &
STANDIN_PID=$!
trap 'kill $STANDIN_PID 2>/dev/null' EXIT
for _ in $(seq 1 50); do curl -s -o /dev/null "$STANDIN/CocoaPods-version.yml" && break; sleep 0.2; done
STEP="stand-in CDN serves its index"; expect "GranaryStandInKit" curl -fsS "$STANDIN/all_pods.txt"
# requests <pattern>: how many requests the stand-in received for paths matching the pattern
requests(){ grep -cE "$1" "$WORK/cdn-requests.log" 2>/dev/null || true; }

echo "== pod ($POD_MODE)"
STEP="pod --version"; check podrun . --version
echo "      $(tail -1 "$WORK/last.log")"
STEP="pod repo add-cdn granary $GROUP/"; expect "Adding spec repo \`granary\` with CDN \`$GROUP/\`" podrun . repo add-cdn granary "$GROUP/"
STEP="pod repo list shows the CDN source"; expect "Type: CDN" podrun . repo list
STEP="pod repo update granary revalidates with the ETag (304)"
expect "Relative path not modified: CocoaPods-version.yml" podrun . repo update granary --verbose

echo "== Publish to cocoapods-hosted"
mkdir -p "$WORK/pub"
# podspec <version> <source Ruby hash> [dependency lines]: a Ruby podspec, as pod lib create writes it
podspec(){ cat > "$WORK/pub/GranaryDemoKit.podspec" <<RUBY
Pod::Spec.new do |s|
  s.name         = 'GranaryDemoKit'
  s.version      = '$1'
  s.summary      = 'Demo pod for the Granary CocoaPods verification'
  s.homepage     = 'https://example.com/GranaryDemoKit'
  s.license      = { :type => 'MIT' }
  s.author       = { 'Acme Developers' => 'dev@example.com' }
  s.ios.deployment_target = '12.0'
  s.osx.deployment_target = '10.13'
  s.source       = $2
  s.source_files = 'Sources/*.{h,m}'
${3:-}
end
RUBY
}
# ipc <version>: pod ipc spec, the documented way to get the JSON podspec
ipc(){ STEP="pod ipc spec GranaryDemoKit.podspec ($1)"
  if inpod pub pod ipc spec GranaryDemoKit.podspec --no-ansi >"$WORK/pub/GranaryDemoKit-$1.podspec.json" 2>"$WORK/last.log" \
     && python3 -c 'import json, sys; d = json.load(open(sys.argv[1])); assert d["version"] == sys.argv[2], d' "$WORK/pub/GranaryDemoKit-$1.podspec.json" "$1"
  then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log" "$WORK/pub/GranaryDemoKit-$1.podspec.json"; fi; }
AUTH=(-u "admin:$APITOKEN")

for v in 1.0.0 1.1.0; do objc "$WORK/src/GranaryDemoKit-$v" GranaryDemoKit "$v"; zipdir "$WORK/pub/GranaryDemoKit-$v.zip" "$WORK/src/GranaryDemoKit-$v"; done
podspec 1.0.0 "{ :http => 'https://downloads.example.com/GranaryDemoKit-1.0.0.zip' }" "  s.dependency 'GranaryStandInKit', '~> 1.0'"
ipc 1.0.0
STEP="POST api/pods: podspec with its source archive (curl -F)"
status 201 '"source":"'"$HOSTED/sources/GranaryDemoKit/1.0.0/GranaryDemoKit-1.0.0.zip"'"' "${AUTH[@]}" \
  -F "podspec=@$WORK/pub/GranaryDemoKit-1.0.0.podspec.json" -F "source=@$WORK/pub/GranaryDemoKit-1.0.0.zip" "$HOSTED/api/pods"

STEP="PUT sources/GranaryDemoKit/1.1.0/GranaryDemoKit-1.1.0.zip (archive on its own)"
status 201 "$HOSTED/sources/GranaryDemoKit/1.1.0/GranaryDemoKit-1.1.0.zip" "${AUTH[@]}" -X PUT \
  --data-binary "@$WORK/pub/GranaryDemoKit-1.1.0.zip" "$HOSTED/sources/GranaryDemoKit/1.1.0/GranaryDemoKit-1.1.0.zip"
podspec 1.1.0 "{ :http => '$HOSTED/sources/GranaryDemoKit/1.1.0/GranaryDemoKit-1.1.0.zip', :sha256 => '$(sha "$WORK/pub/GranaryDemoKit-1.1.0.zip")' }" \
  "  s.dependency 'GranaryStandInKit', '~> 1.1'
  s.dependency 'GranaryStandInTar', '~> 2.0'"
ipc 1.1.0
STEP="PUT Specs/GranaryDemoKit/1.1.0/GranaryDemoKit.podspec.json (curl, as the connect page says)"
status 201 '"version":"1.1.0"' "${AUTH[@]}" -X PUT -H 'Content-Type: application/json' \
  --data-binary "@$WORK/pub/GranaryDemoKit-1.1.0.podspec.json" "$HOSTED/Specs/GranaryDemoKit/1.1.0/GranaryDemoKit.podspec.json"
for v in 0.9.0 1.2.0-beta.1; do
  podspec "$v" "{ :git => 'https://git.example.com/acme/GranaryDemoKit.git', :tag => '$v' }"; ipc "$v"
  STEP="PUT GranaryDemoKit $v (git source)"
  status 201 "\"version\":\"$v\"" "${AUTH[@]}" -X PUT --data-binary "@$WORK/pub/GranaryDemoKit-$v.podspec.json" \
    "$HOSTED/Specs/GranaryDemoKit/$v/GranaryDemoKit.podspec.json"
done
STEP="re-pushing 1.1.0 is refused (409)"
status 409 "already exists" "${AUTH[@]}" -X PUT --data-binary "@$WORK/pub/GranaryDemoKit-1.1.0.podspec.json" \
  "$HOSTED/Specs/GranaryDemoKit/1.1.0/GranaryDemoKit.podspec.json"
STEP="pushing to the group is refused and names the hosted member"
status 405 "cocoapods-hosted" "${AUTH[@]}" -X PUT --data-binary "@$WORK/pub/GranaryDemoKit-1.1.0.podspec.json" \
  "$GROUP/Specs/GranaryDemoKit/1.1.0/GranaryDemoKit.podspec.json"
STEP="anonymous push gets a Basic challenge (401)"
status 401 "" -X PUT --data-binary "@$WORK/pub/GranaryDemoKit-1.1.0.podspec.json" "$HOSTED/Specs/GranaryDemoKit/1.1.1/GranaryDemoKit.podspec.json"
STEP="a podspec whose version differs from the URL is refused (400)"
status 400 "the URL names" "${AUTH[@]}" -X PUT --data-binary "@$WORK/pub/GranaryDemoKit-1.1.0.podspec.json" \
  "$HOSTED/Specs/GranaryDemoKit/9.9.9/GranaryDemoKit.podspec.json"
STEP="a Ruby podspec is refused with the pod ipc spec hint (400)"
status 400 "pod ipc spec" "${AUTH[@]}" -X PUT --data-binary "@$WORK/pub/GranaryDemoKit.podspec" \
  "$HOSTED/Specs/GranaryDemoKit/1.1.0/GranaryDemoKit.podspec.json"
STEP="DELETE Specs/GranaryDemoKit/0.9.0"; status 204 "" "${AUTH[@]}" -X DELETE "$HOSTED/Specs/GranaryDemoKit/0.9.0"
SHARD=$(printf %s GranaryDemoKit | md5sum | cut -c1-3); SHARD="all_pods_versions_${SHARD:0:1}_${SHARD:1:1}_${SHARD:2:1}.txt"
STEP="hosted shard $SHARD lists 1.0.0, 1.1.0, 1.2.0-beta.1 in CocoaPods order"
expect "GranaryDemoKit/1.0.0/1.1.0/1.2.0-beta.1" curl -fsS "$HOSTED/$SHARD"

echo "== pod search / spec through the group"
STEP="pod repo update granary"; check podrun . repo update granary
STEP="pod search Granary --simple finds hosted and proxied pods"
expect "GranaryStandInKit" podrun . search Granary --simple --no-pager
grep -qF -- "-> GranaryDemoKit (1.2.0-beta.1)" "$WORK/last.log" && ok "pod search shows GranaryDemoKit (1.2.0-beta.1)" || bad "pod search shows GranaryDemoKit"
grep -qF -- "Versions: 1.2.0-beta.1, 1.1.0, 1.0.0 [granary repo]" "$WORK/last.log" && ok "pod search lists the versions without the deleted 0.9.0" \
  || { bad "pod search lists the versions"; grep -A6 'GranaryDemoKit (' "$WORK/last.log"; }
grep -qF -- "GranaryStandInOld (0.1.0) [DEPRECATED in favor of GranaryStandInKit]" "$WORK/last.log" \
  && ok "the deprecated stand-in pod is shown as deprecated" || bad "the deprecated stand-in pod is shown as deprecated"
STEP="pod spec cat GranaryStandInKit: source.http points at the group"
spec "$GROUP/sources/GranaryStandInKit/1.1.0/GranaryStandInKit-1.1.0.zip" "source http" spec cat GranaryStandInKit
STEP="pod spec cat keeps the upstream's sha256"; [ "$(specval "$WORK/spec.json" source sha256)" = "$STANDIN_ZIP_SHA" ] && ok "$STEP" || bad "$STEP"
STEP="the rewritten podspec is laid out like pod ipc spec"; check grep -qF '  "source": {' "$WORK/spec.json"
STEP="pod spec cat GranaryDemoKit --version=1.0.0: the pushed archive, served through the group"
spec "$GROUP/sources/GranaryDemoKit/1.0.0/GranaryDemoKit-1.0.0.zip" "source http" spec cat GranaryDemoKit --version=1.0.0
STEP="pod spec cat GranaryDemoKit --version=1.2.0-beta.1: git sources stay untouched"
spec "https://git.example.com/acme/GranaryDemoKit.git" "source git" spec cat GranaryDemoKit --version=1.2.0-beta.1
STEP="pod spec which GranaryStandInKit (sharded path in the local CDN repo)"
expect "/.cocoapods/repos/granary/Specs/f/1/0/GranaryStandInKit/1.1.0/GranaryStandInKit.podspec.json" podrun . spec which GranaryStandInKit
STEP="the stand-in redirected the podspec once and Granary followed it"
[ "$(requests '^GET /mirror/Specs/f/1/0/GranaryStandInKit/1.1.0/')" = 1 ] && ok "$STEP" || { bad "$STEP"; grep GranaryStandInKit "$WORK/cdn-requests.log"; }
for i in 1 2; do
  STEP="curl the rewritten source URL of GranaryStandInKit ($i)"
  if curl -fsS -o "$WORK/standin.zip" "$GROUP/sources/GranaryStandInKit/1.1.0/GranaryStandInKit-1.1.0.zip" \
     && [ "$(sha "$WORK/standin.zip")" = "$STANDIN_ZIP_SHA" ]; then ok "$STEP"; else bad "$STEP"; fi
done
STEP="the archive was downloaded from the upstream once"
[ "$(requests '^GET /archives/GranaryStandInKit-1.1.0.zip ')" = 1 ] && ok "$STEP" || { bad "$STEP"; grep archives "$WORK/cdn-requests.log"; }
STEP="curl the rewritten source URL of GranaryDemoKit 1.0.0 (hosted archive)"
if curl -fsS -o "$WORK/demokit.zip" "$GROUP/sources/GranaryDemoKit/1.0.0/GranaryDemoKit-1.0.0.zip" \
   && [ "$(sha "$WORK/demokit.zip")" = "$(sha "$WORK/pub/GranaryDemoKit-1.0.0.zip")" ]; then ok "$STEP"; else bad "$STEP"; fi

echo "== A dead archive host"
for i in 1 2 3 4; do
  STEP="an archive host that is down answers 502 upstream.unavailable naming it ($i)"
  status 502 "http://localhost:1/archives/GranaryStandInDead-1.0.0.zip" "$GROUP/sources/GranaryStandInDead/1.0.0/GranaryStandInDead-1.0.0.zip"
done
STEP="the stand-in spec repository is still asked afterwards: pod spec cat LateArrival (never fetched before)"
spec LateArrival name spec cat LateArrival

echo "== pod install (Xcode project generated with the xcodeproj gem)"
STEP="create DemoApp.xcodeproj"
check inpod app ruby -e 'require "xcodeproj"; p = Xcodeproj::Project.new("DemoApp.xcodeproj"); p.new_target(:application, "DemoApp", :ios, "13.0"); p.save'
cat > "$WORK/app/Podfile" <<PODFILE
source '$GROUP/'
platform :ios, '13.0'

target 'DemoApp' do
  pod 'GranaryDemoKit', '~> 1.1'
end
PODFILE
podinstall(){ # podinstall <label>
  STEP="pod install ($1)"; expect "Pod installation complete! There is 1 dependency from the Podfile and 3 total pods installed." podrun app install
  STEP="Podfile.lock pins GranaryDemoKit 1.1.0, GranaryStandInKit 1.1.0, GranaryStandInTar 2.0.0 from the group ($1)"
  check sh -c "grep -q '  - GranaryDemoKit (1.1.0):' '$WORK/app/Podfile.lock' && grep -q '  - GranaryStandInKit (1.1.0)' '$WORK/app/Podfile.lock' \
    && grep -q '  - GranaryStandInTar (2.0.0)' '$WORK/app/Podfile.lock' && grep -qF '$GROUP/:' '$WORK/app/Podfile.lock'"
  STEP="Pods/ holds the sources of all three pods ($1)"
  check sh -c "grep -q 'GranaryDemoKit 1.1.0' '$WORK/app/Pods/GranaryDemoKit/Sources/GranaryDemoKit.m' \
    && grep -q 'GranaryStandInKit 1.1.0' '$WORK/app/Pods/GranaryStandInKit/Sources/GranaryStandInKit.m' \
    && grep -q 'GranaryStandInTar 2.0.0' '$WORK/app/Pods/GranaryStandInTar/Sources/GranaryStandInTar.m' && test -d '$WORK/app/Pods/Pods.xcodeproj'"
}
if inpod . sh -c 'command -v rsync && command -v curl && command -v unzip && command -v tar' >/dev/null 2>&1; then
  podinstall online
  STEP="each archive was downloaded from the stand-in once"
  [ "$(requests '^GET /archives/GranaryStandInTar-2.0.0.tar.gz ')" = 1 ] && [ "$(requests '^GET /archives/GranaryStandInKit-1.1.0.zip ')" = 1 ] \
    && ok "$STEP" || { bad "$STEP"; grep archives "$WORK/cdn-requests.log"; }
  STEP="pod outdated (nothing newer)"; check podrun app outdated
  HAVE_INSTALL=1
else
  block "pod install: rsync, curl, unzip or tar is missing where pod runs"
  HAVE_INSTALL=0
fi

echo "== Anonymous read off: ~/.netrc"
settings '{"anonymousRead":false}' && ok "anonymous read switched off" || bad "anonymous read switched off"
STEP="CocoaPods-version.yml needs credentials (401)"; status 401 "" "$GROUP/CocoaPods-version.yml"
STEP="pod repo add-cdn without credentials is refused"
refuse "401" podrun . repo add-cdn granary-auth "$GROUP/"
inpod . pod repo remove granary-auth --no-ansi >/dev/null 2>&1
printf 'machine %s\n  login admin\n  password %s\n' "$HOST" "$APITOKEN" > "$PODHOME/.netrc"; chmod 600 "$PODHOME/.netrc"
STEP="pod repo add-cdn with the API token in ~/.netrc"; expect "Adding spec repo \`granary-auth\`" podrun . repo add-cdn granary-auth "$GROUP/"
STEP="pod spec cat GranaryDemoKit with ~/.netrc"; spec GranaryDemoKit name spec cat GranaryDemoKit --version=1.1.0
STEP="the rewritten archive URL with curl --netrc-optional (what pod install runs)"
check curl -fsS --netrc-file "$PODHOME/.netrc" -o /dev/null "$GROUP/sources/GranaryDemoKit/1.1.0/GranaryDemoKit-1.1.0.zip"
inpod . pod repo remove granary-auth --no-ansi >/dev/null 2>&1
rm -f "$PODHOME/.netrc"
settings '{"anonymousRead":true}' && ok "anonymous read switched on again" || bad "anonymous read switched on again"

echo "== cdn.cocoapods.org through cocoapods-cdn"
STEP="CocoaPods-version.yml of cdn.cocoapods.org"; expect "prefix_lengths:" curl -fsS "$CDN/CocoaPods-version.yml"
STEP="shard all_pods_versions_a_7_5.txt lists AFNetworking 4.0.1"
check sh -c "curl -fsS '$CDN/all_pods_versions_a_7_5.txt' | grep -E '^AFNetworking/(.*/)?4\.0\.1(/|\$)'"
STEP="all_pods.txt of the group includes trunk and the hosted pods"
check sh -c "curl -fsS '$GROUP/all_pods.txt' > '$WORK/all_pods.txt' && grep -qx AFNetworking '$WORK/all_pods.txt' && grep -qx GranaryDemoKit '$WORK/all_pods.txt'"
STEP="group shard all_pods_versions_a_7_5.txt merges trunk"; expect "AFNetworking/" curl -fsS "$GROUP/all_pods_versions_a_7_5.txt"
code=$(curl -sS -o "$WORK/af.json" -w '%{http_code}' "$GROUP/Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json")
if [ "$code" = 200 ]; then
  ok "AFNetworking 4.0.1 podspec through the group (the CDN redirect followed)"
  STEP="pod search AFNetworking --simple"; expect "-> AFNetworking (" podrun . search AFNetworking --simple --no-pager
  STEP="pod spec cat AFNetworking --version=4.0.1"; spec "https://github.com/AFNetworking/AFNetworking.git" "source git" spec cat AFNetworking --version=4.0.1
elif grep -q 'Tunnel failed' "$WORK/af.json" || grep -q 'upstream.unavailable' "$WORK/af.json"; then
  block "AFNetworking podspec: cdn.cocoapods.org redirects podspecs to cdn.jsdelivr.net, unreachable from here (HTTP $code: $(head -c 220 "$WORK/af.json"))"
  # the same trunk podspecs straight from the CocoaPods/Specs repository, in front of the CDN's index files
  MIRROR=${SPECS_MIRROR:-https://raw.githubusercontent.com/CocoaPods/Specs/master/}
  TRUNK="$BASE/repository/cocoapods-trunk"
  curl -sS -o /dev/null -X DELETE "$BASE/api/v1/repositories/cocoapods-trunk" -H "Authorization: Bearer $TOKEN"
  curl -sS -o /dev/null -X DELETE "$BASE/api/v1/repositories/cocoapods-trunk-specs" -H "Authorization: Bearer $TOKEN"
  api POST repositories '{"name":"cocoapods-trunk-specs","format":"cocoapods","type":"PROXY","remoteUrl":"'"$MIRROR"'","description":"Trunk podspecs from the Specs repository"}' >/dev/null
  api POST repositories '{"name":"cocoapods-trunk","format":"cocoapods","type":"GROUP","members":["cocoapods-trunk-specs","cocoapods-cdn"]}' >/dev/null
  code=$(curl -sS -o "$WORK/af.json" -w '%{http_code}' "$TRUNK/Specs/a/7/5/AFNetworking/4.0.1/AFNetworking.podspec.json")
  if [ "$code" = 200 ] && grep -q '"AFNetworking"' "$WORK/af.json"; then
    # a home of its own: the granary source above would also answer for AFNetworking, with the unreachable podspec
    PODRUN_HOME="$WORK/trunk-home"; mkdir -p "$PODRUN_HOME"
    ok "AFNetworking 4.0.1 podspec through cocoapods-trunk (podspecs from $MIRROR, index files from cdn.cocoapods.org)"
    STEP="cocoapods-trunk shard all_pods_versions_a_7_5.txt comes from the CDN"; expect "AFNetworking/" curl -fsS "$TRUNK/all_pods_versions_a_7_5.txt"
    # not named trunk: pod reads a spec repo called trunk from cdn.cocoapods.org, whatever its URL
    STEP="pod repo add-cdn granary-trunk $TRUNK/"; expect "Adding spec repo \`granary-trunk\` with CDN" podrun . repo add-cdn granary-trunk "$TRUNK/"
    STEP="pod search --regex ^AFNetworking\$ --simple (trunk)"; expect "-> AFNetworking (" podrun . search --regex '^AFNetworking$' --simple --no-pager
    STEP="pod spec cat AFNetworking --version=4.0.1 (trunk)"
    spec "https://github.com/AFNetworking/AFNetworking.git" "source git" spec cat AFNetworking --version=4.0.1
    STEP="pod spec which AFNetworking --version=4.0.1 (trunk)"
    expect "AFNetworking/4.0.1/AFNetworking.podspec.json" podrun . spec which AFNetworking --version=4.0.1
    STEP="pod repo remove granary-trunk"; check podrun . repo remove granary-trunk
    PODRUN_HOME=
  else
    block "pod search / pod spec cat AFNetworking: neither cdn.jsdelivr.net nor $MIRROR is reachable from here (HTTP $code: $(head -c 220 "$WORK/af.json"))"
  fi
  curl -sS -o /dev/null -X DELETE "$BASE/api/v1/repositories/cocoapods-trunk" -H "Authorization: Bearer $TOKEN"
  curl -sS -o /dev/null -X DELETE "$BASE/api/v1/repositories/cocoapods-trunk-specs" -H "Authorization: Bearer $TOKEN"
else
  bad "AFNetworking podspec through the group (HTTP $code)"; head -c 400 "$WORK/af.json"; echo
fi
# whatever happened upstream, forget it before the offline run
curl -fsS -X POST "$BASE/api/v1/repositories/cocoapods-cdn/invalidate" -H "Authorization: Bearer $TOKEN" >/dev/null

echo "== Offline: upstream stopped, local caches wiped"
settings '{"offlineMode":true}' && ok "offline mode on" || bad "offline mode on"
kill $STANDIN_PID 2>/dev/null; wait $STANDIN_PID 2>/dev/null
STEP="the stand-in CDN is gone"; refuse "" curl -fsS "$STANDIN/CocoaPods-version.yml"
STEP="pod repo remove granary"; check podrun . repo remove granary
rm -rf "$PODHOME/.cocoapods" "$PODHOME/Library/Caches/CocoaPods" "$WORK/app/Pods" "$WORK/app/Podfile.lock" "$WORK/app/DemoApp.xcworkspace"
STEP="pod repo add-cdn granary (offline)"; expect "Adding spec repo \`granary\` with CDN" podrun . repo add-cdn granary "$GROUP/"
STEP="pod search GranaryStandIn --simple (offline)"; expect "-> GranaryStandInKit (1.1.0)" podrun . search GranaryStandIn --simple --no-pager
STEP="pod spec cat GranaryStandInKit (offline)"
spec "$GROUP/sources/GranaryStandInKit/1.1.0/GranaryStandInKit-1.1.0.zip" "source http" spec cat GranaryStandInKit
STEP="curl the cached archive (offline)"
if curl -fsS -o "$WORK/standin2.zip" "$GROUP/sources/GranaryStandInKit/1.1.0/GranaryStandInKit-1.1.0.zip" \
   && [ "$(sha "$WORK/standin2.zip")" = "$STANDIN_ZIP_SHA" ]; then ok "$STEP"; else bad "$STEP"; fi
[ "$HAVE_INSTALL" = 1 ] && podinstall offline
STEP="a never-downloaded archive is 503 upstream.offline"
status 503 "upstream.offline" "$GROUP/sources/GranaryStandInKit/1.0.0/GranaryStandInKit-1.0.0.zip"
if [ "$HAVE_INSTALL" = 1 ]; then
  # a Podfile alone, in a fresh home without any spec repo: pod recognises the URL as a CDN source by itself
  rm -rf "$PODHOME/.cocoapods" "$PODHOME/Library/Caches/CocoaPods" "$WORK/app/Pods" "$WORK/app/Podfile.lock"
  podinstall "offline, Podfile source only"
fi
settings '{"offlineMode":false}' && ok "offline mode off" || bad "offline mode off"

echo
echo "pod: $POD_MODE; work directory: $WORK"
echo "PASS=$PASS FAIL=$FAIL BLOCKED=$BLOCKED"
[ "$FAIL" = 0 ]
