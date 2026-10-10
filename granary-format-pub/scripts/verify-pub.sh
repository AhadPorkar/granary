#!/usr/bin/env bash
# End-to-end verification of the pub format with the real Dart client, including an offline run against the
# Granary cache.
#
# Publishes a demo package to pub-hosted with dart pub publish, resolves it and public packages from pub.dev
# (path, http and their dependencies) through pub-group with dart pub get, uses a dependency with an explicit
# "hosted:" URL, exercises retraction, discontinuation, anonymous-read-off with dart pub token add and the
# refusal to publish to a group, then switches the server to offline mode and resolves everything again with
# empty pub caches.
#
# Needs curl and either dart on the PATH or a Docker daemon (the dart:stable image is run with host networking,
# so the base URL must be reachable from the host itself, e.g. http://localhost:8112). Needs a running Granary
# with admin/admin123. Set FLUTTER=1 with flutter on the PATH to also publish a Flutter package with
# flutter pub publish and resolve a Flutter app (SDK packages from pub.dev through the group) with flutter pub get,
# online and offline.
# The work directory (pub caches, demo packages) is created under $TMPDIR (default /tmp) and kept for inspection.
# Usage: verify-pub.sh http://localhost:8112
set -uo pipefail
BASE=${1:-http://localhost:8112}
BASE=${BASE%/}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/granary-verify-pub.XXXX")
cd "$WORK"
PASS=0; FAIL=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# expect_fail <pattern> <command…>: the command must fail and print the pattern
expect_fail(){ local pattern=$1; shift
  if "$@" >"$WORK/last.log" 2>&1; then bad "$STEP (succeeded)"; tail -10 "$WORK/last.log"
  elif grep -q -- "$pattern" "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -10 "$WORK/last.log"; fi; }
# expect_output <pattern> <command…>: the command must succeed and print the pattern
expect_output(){ local pattern=$1; shift
  if "$@" >"$WORK/last.log" 2>&1 && grep -q -- "$pattern" "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -10 "$WORK/last.log"; fi; }
command -v curl >/dev/null || { echo "missing required tool: curl"; exit 2; }

GROUP="$BASE/repository/pub-group"
HOSTED="$BASE/repository/pub-hosted"
PROXY="$BASE/repository/pub-dev"

# pub <dir> <pub cache> <config dir> <hosted url> <shell command>: runs dart in <dir> below the work directory.
# The config dir holds the tokens of dart pub token add, the pub cache the downloaded packages.
if command -v dart >/dev/null; then
  DART_MODE=local
  pub(){ local dir=$1 cache=$2 config=$3 hosted=$4; shift 4
    (cd "$WORK/$dir" && env PUB_HOSTED_URL="$hosted" PUB_CACHE="$WORK/$cache" XDG_CONFIG_HOME="$WORK/$config" \
      PUB_MAX_HTTP_RETRIES="${PUB_MAX_HTTP_RETRIES:-7}" sh -c "$1"); }
elif command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  DART_MODE=docker
  pub(){ local dir=$1 cache=$2 config=$3 hosted=$4; shift 4
    docker run --rm --network host -v "$WORK:$WORK" -w "$WORK/$dir" -e PUB_HOSTED_URL="$hosted" -e PUB_CACHE="$WORK/$cache" \
      -e XDG_CONFIG_HOME="$WORK/$config" -e PUB_MAX_HTTP_RETRIES="${PUB_MAX_HTTP_RETRIES:-7}" \
      "${DART_IMAGE:-dart:stable}" sh -c "$1"; }
else
  echo "neither dart nor a Docker daemon is available"; exit 2
fi

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null \
  && ok "standard repositories (pub-dev, pub-hosted, pub-group)" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify-pub"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
settings(){ curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" >/dev/null; }
# a previous run that stopped half-way may have left the server offline or closed
settings '{"offlineMode":false,"anonymousRead":true}' && ok "server online with anonymous read" || bad "server online with anonymous read"
STEP="pub-group root names the PUB_HOSTED_URL"; expect_output "PUB_HOSTED_URL=$GROUP" curl -fsS "$GROUP/"
STEP="connect snippets of pub-hosted"
expect_output "dart pub token add $HOSTED" curl -fsS -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/repositories/pub-hosted/connect"

echo "== Tools"
STEP="dart ($DART_MODE)"; check pub . cache-tools cfg-none "$GROUP" "dart --version"
grep -m1 'Dart SDK' "$WORK/last.log"

echo "== dart pub get through pub-group (path, http and their dependencies from pub.dev, with advisories)"
mkdir -p app/bin
cat > app/pubspec.yaml <<'PUBSPEC'
name: app
publish_to: none
environment:
  sdk: ^3.4.0
dependencies:
  path: ^1.9.0
  http: ^1.2.0
PUBSPEC
cat > app/bin/app.dart <<'DART'
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;

void main() => print('${p.join('granary', 'pub')} ${http.Request('GET', Uri.parse('http://localhost/')).method}');
DART
STEP="dart pub get (app)"; check pub app cache1 cfg "$GROUP" "dart pub get"
STEP="dart run (app)"; expect_output "granary/pub GET" pub app cache1 cfg "$GROUP" "dart run bin/app.dart"
STEP="pubspec.lock names the group"; expect_output "url: \"$GROUP/\"" cat app/pubspec.lock
STEP="dart pub outdated (listings and advisories)"; expect_output "Showing outdated packages" pub app cache1 cfg "$GROUP" "dart pub outdated"
STEP="dart pub deps"; expect_output "http_parser" pub app cache1 cfg "$GROUP" "dart pub deps"
STEP="http advisories reach the group"; expect_output '"advisories":\[{' curl -fsS "$GROUP/api/packages/http/advisories"

echo "== dart pub publish to pub-hosted"
mkdir -p granary_demo/lib
cat > granary_demo/pubspec.yaml <<PUBSPEC
name: granary_demo
description: A tiny package published to a Granary hosted pub repository during the client verification.
version: 1.0.0
homepage: https://example.com/granary_demo
publish_to: $HOSTED
environment:
  sdk: ^3.4.0
dependencies:
  path: ^1.9.0
PUBSPEC
cat > granary_demo/lib/granary_demo.dart <<'DART'
import 'package:path/path.dart' as p;

/// Greets the last segment of [name].
String greet(String name) => 'Hello, ${p.basename(name)}!';
DART
printf '## 1.0.0\n\n- Initial version.\n' > granary_demo/CHANGELOG.md
printf '# granary_demo\n\nA demo package for the Granary pub verification.\n' > granary_demo/README.md
printf 'MIT License\n\nCopyright (c) 2026 Acme\n\nPermission is hereby granted, free of charge, to any person obtaining a copy of this software.\n' > granary_demo/LICENSE
# repeatable runs: remove what an earlier run published (a Granary extension of the pub API)
for p in granary_demo granary_widgets; do
  curl -s -o /dev/null -X DELETE -H "Authorization: Bearer $APITOKEN" "$HOSTED/api/packages/$p"
done
STEP="dart pub token add pub-hosted (token on stdin)"
expect_output "will now be authenticated" pub granary_demo cache1 cfg "$GROUP" "echo '$APITOKEN' | dart pub token add $HOSTED"
STEP="dart pub publish granary_demo 1.0.0"
expect_output "Successfully uploaded granary_demo version 1.0.0" pub granary_demo cache1 cfg "$GROUP" "dart pub publish --force"
STEP="dart pub publish 1.0.0 again is refused with the server's reason"
expect_fail "already exists in repository 'pub-hosted'" pub granary_demo cache1 cfg "$GROUP" "dart pub publish --force"
sed -i 's/^version: 1.0.0$/version: 1.1.0/' granary_demo/pubspec.yaml
printf '## 1.1.0\n\n- Second version.\n\n## 1.0.0\n\n- Initial version.\n' > granary_demo/CHANGELOG.md
STEP="dart pub publish granary_demo 1.1.0"
expect_output "Successfully uploaded granary_demo version 1.1.0" pub granary_demo cache1 cfg "$GROUP" "dart pub publish --force"
STEP="hosted listing has both versions"; expect_output '"version":"1.1.0"' curl -fsS -H 'Accept: application/vnd.pub.v2+json' "$HOSTED/api/packages/granary_demo"

echo "== dart pub get of the own package through pub-group"
mkdir -p consumer/bin
cat > consumer/pubspec.yaml <<'PUBSPEC'
name: consumer
publish_to: none
environment:
  sdk: ^3.4.0
dependencies:
  granary_demo: ^1.0.0
PUBSPEC
printf "import 'package:granary_demo/granary_demo.dart';\n\nvoid main() => print(greet('/tmp/world'));\n" > consumer/bin/consumer.dart
STEP="dart pub get (consumer, fresh cache)"; expect_output "granary_demo 1.1.0" pub consumer cache2 cfg-none "$GROUP" "dart pub get"
STEP="dart run (consumer)"; expect_output "Hello, world!" pub consumer cache2 cfg-none "$GROUP" "dart run bin/consumer.dart"

echo "== A dependency with an explicit hosted: URL"
mkdir -p direct/bin
cat > direct/pubspec.yaml <<PUBSPEC
name: direct
publish_to: none
environment:
  sdk: ^3.4.0
dependencies:
  granary_demo:
    hosted: $HOSTED
    version: ^1.0.0
PUBSPEC
printf "import 'package:granary_demo/granary_demo.dart';\n\nvoid main() => print(greet('x/direct'));\n" > direct/bin/direct.dart
STEP="dart pub get (hosted: pub-hosted, path through pub-group)"; check pub direct cache3 cfg-none "$GROUP" "dart pub get"
STEP="dart run (direct)"; expect_output "Hello, direct!" pub direct cache3 cfg-none "$GROUP" "dart run bin/direct.dart"
STEP="pubspec.lock names pub-hosted for granary_demo"; expect_output "url: \"$HOSTED/\"" cat direct/pubspec.lock

echo "== Retraction and discontinuation"
STEP="retract granary_demo 1.1.0"
expect_output "Retracted granary_demo 1.1.0" curl -fsS -X POST -H "Authorization: Bearer $APITOKEN" "$HOSTED/api/packages/granary_demo/versions/1.1.0/retract"
mkdir -p retracted && cp consumer/pubspec.yaml retracted/
STEP="dart pub get avoids the retracted version"; expect_output "granary_demo 1.0.0" pub retracted cache4 cfg-none "$GROUP" "dart pub get"
STEP="a lock file pinning the retracted version still works"; expect_output "granary_demo 1.1.0" pub consumer cache5 cfg-none "$GROUP" "dart pub get --enforce-lockfile && dart pub deps"
STEP="restore granary_demo 1.1.0"
expect_output "Restored granary_demo 1.1.0" curl -fsS -X DELETE -H "Authorization: Bearer $APITOKEN" "$HOSTED/api/packages/granary_demo/versions/1.1.0/retract"
STEP="discontinue granary_demo"
expect_output '"isDiscontinued":true' curl -fsS -X PUT -H "Authorization: Bearer $APITOKEN" -H 'Content-Type: application/json' \
  -d '{"isDiscontinued":true,"replacedBy":"path"}' "$HOSTED/api/packages/granary_demo/options"
# a fresh pub cache: dart pub get with a lock file may reuse the version listing cached in PUB_CACHE
STEP="dart pub get reports the discontinued package"; expect_output "discontinued replaced by path" pub consumer cache8 cfg-none "$GROUP" "dart pub get"
STEP="lift the discontinuation"
expect_output '"isDiscontinued":false' curl -fsS -X PUT -H "Authorization: Bearer $APITOKEN" -H 'Content-Type: application/json' \
  -d '{"isDiscontinued":false}' "$HOSTED/api/packages/granary_demo/options"

echo "== Read-only repositories"
mkdir -p to_group/lib && cp granary_demo/LICENSE granary_demo/README.md granary_demo/CHANGELOG.md to_group/ && cp granary_demo/lib/granary_demo.dart to_group/lib/
sed -e "s#^publish_to: .*#publish_to: $GROUP#" -e 's/^version: .*/version: 9.0.0/' granary_demo/pubspec.yaml > to_group/pubspec.yaml
STEP="dart pub publish to the group is refused naming the hosted member"
expect_fail "publish to its hosted member instead" pub to_group cache1 cfg "$GROUP" "echo '$APITOKEN' | dart pub token add $GROUP && dart pub publish --force"

echo "== The proxy on its own"
mkdir -p proxied && printf 'name: proxied\npublish_to: none\nenvironment:\n  sdk: ^3.4.0\ndependencies:\n  path: ^1.9.0\n' > proxied/pubspec.yaml
STEP="dart pub get with PUB_HOSTED_URL=pub-dev"; expect_output "path 1.9" pub proxied cache6 cfg-none "$PROXY" "dart pub get"

echo "== HTTP details"
ARCHIVE_URL="$GROUP/packages/granary_demo/versions/1.0.0.tar.gz"
STEP="group listing points archive_url at the group"
expect_output "\"archive_url\":\"$ARCHIVE_URL\"" curl -fsS -H 'Accept: application/vnd.pub.v2+json' "$GROUP/api/packages/granary_demo"
STEP="archive is application/gzip with an ETag"
expect_output "content-type: application/gzip" sh -c "curl -fsS -o /dev/null -D - '$ARCHIVE_URL' | tr 'A-Z' 'a-z'"
ETAG=$(curl -fsS -o /dev/null -D - "$ARCHIVE_URL" | tr -d '\r' | sed -n 's/^[Ee][Tt][Aa][Gg]: //p')
STEP="If-None-Match on the archive is 304"
code=$(curl -s -o /dev/null -w "%{http_code}" -H "If-None-Match: $ETAG" "$ARCHIVE_URL"); [ "$code" = "304" ] && ok "$STEP" || bad "$STEP ($code)"
STEP="unknown package is a pub-shaped 404"; code=$(curl -s -o "$WORK/nf.json" -w "%{http_code}" "$HOSTED/api/packages/no_such_package_here")
[ "$code" = "404" ] && grep -q '"code":"NotFound"' "$WORK/nf.json" && ok "$STEP" || bad "$STEP ($code)"

echo "== Anonymous read switched off: dart pub token add"
settings '{"anonymousRead":false}' && ok "anonymous read off" || bad "anonymous read off"
STEP="dart pub get without a token shows the server's message"
expect_fail "needs an API token to read it" pub consumer cache7 cfg-none "$GROUP" "dart pub get"
STEP="dart pub get with dart pub token add for the group"
check pub consumer cache7 cfg-anon "$GROUP" "echo '$APITOKEN' | dart pub token add $GROUP && dart pub get"
STEP="CI style: dart pub token add --env-var, the token from the environment"
mkdir -p ci && cp consumer/pubspec.yaml consumer/pubspec.lock ci/
check pub ci cache9 cfg-ci "$GROUP" "export GRANARY_PUB_TOKEN='$APITOKEN' && dart pub token add $GROUP --env-var GRANARY_PUB_TOKEN && dart pub get --enforce-lockfile"
settings '{"anonymousRead":true}' && ok "anonymous read on again" || bad "anonymous read on again"

echo "== Docker build with the connect dialogue's Dockerfile snippet (needs docker; the build uses the host network)"
if command -v docker >/dev/null && docker info >/dev/null 2>&1 && command -v python3 >/dev/null; then
  mkdir -p dockerbuild/bin && cp consumer/pubspec.yaml dockerbuild/
  printf "import 'package:granary_demo/granary_demo.dart';\n\nvoid main() => print(greet('/docker/build'));\n" > dockerbuild/bin/main.dart
  STEP="connect snippets of pub-group"
  check sh -c "curl -fsS -H 'Authorization: Bearer $TOKEN' '$BASE/api/v1/repositories/pub-group/connect' > '$WORK/connect.json'"
  python3 -I -c 'import json, sys; print(next(s["code"] for s in json.load(open(sys.argv[1])) if s["id"] == "dockerfile"))' \
    "$WORK/connect.json" > dockerbuild/Dockerfile
  STEP="docker build resolves through pub-group and compiles"; check docker build --network host --no-cache -t granary-verify-pub dockerbuild
  STEP="the compiled program runs"; expect_output "Hello, build!" docker run --rm granary-verify-pub /app/bin/server
  docker rmi granary-verify-pub >/dev/null 2>&1
else
  echo "SKIP  docker build (no docker daemon or no python3)"
fi

FLUTTER_RUN=0
if [ "${FLUTTER:-0}" = 1 ] && command -v flutter >/dev/null; then
  FLUTTER_RUN=1
  echo "== flutter pub (the Flutter SDK's pub client)"
  # flutter_pub <dir> <pub cache> <config dir> <hosted url> <shell command>, like pub() but with the local flutter
  flutter_pub(){ local dir=$1 cache=$2 config=$3 hosted=$4; shift 4
    (cd "$WORK/$dir" && env PUB_HOSTED_URL="$hosted" PUB_CACHE="$WORK/$cache" XDG_CONFIG_HOME="$WORK/$config" \
      PUB_MAX_HTTP_RETRIES="${PUB_MAX_HTTP_RETRIES:-7}" sh -c "$1"); }
  STEP="flutter --version"; check flutter --version
  grep -m1 '^Flutter' "$WORK/last.log"
  # a private Flutter package (depends on the Flutter SDK), published with flutter pub publish
  mkdir -p granary_widgets/lib && cp granary_demo/LICENSE granary_widgets/
  cat > granary_widgets/pubspec.yaml <<PUBSPEC
name: granary_widgets
description: A tiny Flutter widget package published to a Granary hosted pub repository during the client verification.
version: 1.0.0
homepage: https://example.com/granary_widgets
publish_to: $HOSTED
environment:
  sdk: ^3.4.0
  flutter: ">=3.19.0"
dependencies:
  flutter:
    sdk: flutter
PUBSPEC
  printf "import 'package:flutter/widgets.dart';\n\n/// A greeting.\nWidget greeting(String who) => Text('Hello, \$who!', textDirection: TextDirection.ltr);\n" \
    > granary_widgets/lib/granary_widgets.dart
  printf '## 1.0.0\n\n- Initial version.\n' > granary_widgets/CHANGELOG.md
  printf '# granary_widgets\n\nA demo Flutter package for the Granary pub verification.\n' > granary_widgets/README.md
  STEP="flutter pub publish granary_widgets 1.0.0"
  expect_output "Successfully uploaded granary_widgets version 1.0.0" flutter_pub granary_widgets cache-flutter cfg "$GROUP" "flutter pub publish --force"
  # a Flutter app: the SDK's own dependencies come from pub.dev through the group, the private packages from pub-hosted
  mkdir -p flutter_app/lib
  cat > flutter_app/pubspec.yaml <<'PUBSPEC'
name: flutter_app
publish_to: none
environment:
  sdk: ^3.4.0
dependencies:
  flutter:
    sdk: flutter
  granary_demo: ^1.0.0
  granary_widgets: ^1.0.0
  path: ^1.9.0
PUBSPEC
  printf "import 'package:flutter/widgets.dart';\nimport 'package:granary_widgets/granary_widgets.dart';\n\nvoid main() => runApp(greeting('world'));\n" \
    > flutter_app/lib/main.dart
  STEP="flutter pub get (flutter_app through pub-group, fresh cache)"; check flutter_pub flutter_app cache-flutter2 cfg-none "$GROUP" "flutter pub get"
  STEP="flutter pub deps names the private packages"
  expect_output "granary_widgets 1.0.0" flutter_pub flutter_app cache-flutter2 cfg-none "$GROUP" "flutter pub deps --style=list | grep -E 'granary_(demo|widgets) '"
  STEP="pubspec.lock of flutter_app names the group"; expect_output "url: \"$GROUP/\"" cat flutter_app/pubspec.lock
else
  echo "SKIP  flutter pub get (set FLUTTER=1 with flutter on the PATH)"
fi

echo "== Offline: resolve everything again with upstream access disabled and empty pub caches"
settings '{"offlineMode":true}' && ok "offline mode on" || bad "offline mode on"
rm -rf app/.dart_tool consumer/.dart_tool direct/.dart_tool
STEP="offline dart pub get --enforce-lockfile (app: path, http and dependencies)"; check pub app off1 cfg-none "$GROUP" "dart pub get --enforce-lockfile"
STEP="offline dart run (app)"; expect_output "granary/pub GET" pub app off1 cfg-none "$GROUP" "dart run bin/app.dart"
STEP="offline dart pub get without a lock file (app)"; check pub app off2 cfg-none "$GROUP" "rm -f pubspec.lock && dart pub get"
STEP="offline dart pub get (consumer: own package through the group)"; expect_output "granary_demo 1.1.0" pub consumer off3 cfg-none "$GROUP" "rm -f pubspec.lock && dart pub get"
STEP="offline dart run (consumer)"; expect_output "Hello, world!" pub consumer off3 cfg-none "$GROUP" "dart run bin/consumer.dart"
STEP="offline dart pub get (direct: hosted: URL)"; check pub direct off4 cfg-none "$GROUP" "dart pub get --enforce-lockfile"
if [ "$FLUTTER_RUN" = 1 ]; then
  rm -rf flutter_app/.dart_tool
  STEP="offline flutter pub get --enforce-lockfile (flutter_app, fresh cache)"
  check flutter_pub flutter_app off-flutter1 cfg-none "$GROUP" "flutter pub get --enforce-lockfile"
  STEP="offline flutter pub get without a lock file (flutter_app, fresh cache)"
  check flutter_pub flutter_app off-flutter2 cfg-none "$GROUP" "rm -f pubspec.lock && flutter pub get"
fi
# english_words has no dependencies and nothing else here depends on it (the flutter tool, when its dart wrapper
# refreshes itself, resolves its own dependencies such as shelf through PUB_HOSTED_URL)
mkdir -p uncached && printf 'name: uncached\npublish_to: none\nenvironment:\n  sdk: ^3.4.0\ndependencies:\n  english_words: ^4.0.0\n' > uncached/pubspec.yaml
STEP="offline uncached package fails cleanly (503)"
# (Tomcat sends no reason phrase, so the client prints "503  trying to find package english_words at …")
PUB_MAX_HTTP_RETRIES=1 expect_fail "503 .*trying to find package english_words" pub uncached off5 cfg-none "$GROUP" "dart pub get"
STEP="offline uncached package is a 503 upstream.offline over HTTP"
code=$(curl -s -o "$WORK/off.json" -w "%{http_code}" -H 'Accept: application/vnd.pub.v2+json' "$GROUP/api/packages/english_words")
[ "$code" = "503" ] && grep -q 'upstream.offline' "$WORK/off.json" && ok "$STEP" || bad "$STEP ($code)"
settings '{"offlineMode":false}' && ok "offline mode off" || bad "offline mode off"

echo; echo "passed=$PASS failed=$FAIL  (work dir $WORK)"
[ "$FAIL" = 0 ]
