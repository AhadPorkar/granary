#!/usr/bin/env bash
# Runs the real-client verification of every format against one running Granary and prints a summary table.
#
# Each format has its own script: scripts/verify-clients.sh for Maven, npm and NuGet, and
# granary-format-<f>/scripts/verify-<f>.sh for the others (read its header for the details). Each one publishes to
# the format's standard hosted repository with the real client, installs through the group and the proxy, and then
# installs again in offline mode with empty client caches. This script runs them one after the other (they switch
# global settings such as offline mode, anonymous read and the base URL, so they must never run in parallel against
# the same server), restores those three settings after every script, keeps each script's output in a log file and
# finally prints one row per format:
#   PASS     the script finished and no step failed
#   FAIL     a step failed, the script exited with an error, or it ran into the time limit
#   SKIP     a client the script needs is not installed (named in the table); the script was not run
# The step columns count the script's own PASS / FAIL lines; "other" counts steps a script reported as SKIP,
# BLOCK(ED) (an upstream the network does not allow, e.g. GitHub archives or cdn.jsdelivr.net) or KNOWN (a
# documented client limitation). Those are not failures, but they are not verified either: read the log.
# Every script can run again against the same server: its demo packages get new versions (or namespaces) on every
# run, or the earlier run's are removed first, and the extra repositories and users it needs are recreated.
# The first row, "server", needs no client: scripts/check-server.sh creates the standard repositories and checks
# every format's templates, the Connect snippets of every repository and the system pages.
#
# Usage: scripts/verify-all.sh [options] BASE_URL        e.g. scripts/verify-all.sh http://localhost:8081
#   -f, --formats LIST   run only these formats (comma or space separated), e.g. -f maven,npm,pypi
#   -x, --exclude LIST   run every format except these, e.g. -x swift,hex
#   -t, --timeout SECS   time limit per format (default 3600)
#   -o, --logs DIR       directory for the per-format logs (default: a new directory under /tmp)
#   -r, --remove-images  after each format, remove the Docker images its script pulled or built (for machines
#                        short of disk space; images that were there before are kept)
#   -l, --list           list the formats with their scripts and prerequisites, then exit
# The scripts' own environment variables (SWIFT_IMAGE, ELIXIR_IMAGE, HELM_IMAGE, CONAN_VERIFY_INSTALL, ...) pass
# through unchanged.
#
# Needs curl and a running Granary whose administrator is admin/admin123 (the format scripts log in with it; the
# settings are restored with GRANARY_USER / GRANARY_PASSWORD, default admin / admin123). The proxies need internet
# access to the public registries; run the scripts on a machine that can reach them. Clients that are not
# installed are run from Docker images where the format's script supports it (helm, pub, hex, swift, cocoapods,
# R and Haskell in raw), which needs a Docker daemon and disk space: swift:6.1 is about 3 GB, the hex image about
# 1 GB; VERIFY_SKIP_IMAGES="haskell:9.6 r-base:4.5.1" keeps the raw script from pulling those. The terraform script
# binds a TLS front to port 443 (root, or TF_TLS_PORT) and swift one to Granary's port + 1000.
# The offline drill across eight clients at once is scripts/offline-drill.sh.
set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
USER_NAME=${GRANARY_USER:-admin}
USER_PASSWORD=${GRANARY_PASSWORD:-admin123}

# name | script (relative to the repository root) | environment for the script | required tools
# A required tool written as a|b means either one is enough.
FORMATS=(
  "server|scripts/check-server.sh||curl python3"
  "maven|scripts/verify-clients.sh|CLIENTS=maven|curl mvn java"
  "npm|scripts/verify-clients.sh|CLIENTS=npm|curl npm node"
  "nuget|scripts/verify-clients.sh|CLIENTS=nuget|curl dotnet"
  "pypi|granary-format-pypi/scripts/verify-pypi.sh||curl python3 uv"
  "docker|granary-format-docker/scripts/verify-docker.sh||curl docker"
  "go|granary-format-go/scripts/verify-go.sh||curl go zip openssl python3"
  "cargo|granary-format-cargo/scripts/verify-cargo.sh||curl cargo"
  "rubygems|granary-format-rubygems/scripts/verify-rubygems.sh||curl ruby gem bundle script"
  "composer|granary-format-composer/scripts/verify-composer.sh||curl php composer"
  "helm|granary-format-helm/scripts/verify-helm.sh||curl helm|docker"
  "terraform|granary-format-terraform/scripts/verify-terraform.sh||curl jq python3 openssl unzip tar gzip"
  "conan|granary-format-conan/scripts/verify-conan.sh||curl python3 conan cmake c++"
  "hex|granary-format-hex/scripts/verify-hex.sh||curl docker"
  "pub|granary-format-pub/scripts/verify-pub.sh||curl dart|docker"
  "swift|granary-format-swift/scripts/verify-swift.sh||curl docker jq python3 openssl"
  "cocoapods|granary-format-cocoapods/scripts/verify-cocoapods.sh||curl python3 sha256sum pod|gem|docker"
  "raw|granary-format-raw/scripts/verify-raw.sh||curl wget perl python3 sha256sum git zip"
)

usage() { sed -n '2,/^set -uo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }
field() { local IFS='|'; local parts; read -r -a parts <<<"$1"; echo "${parts[$2]:-}"; }
# the "a|b" alternatives make the record split unreliable, so the requirements are everything after the third '|'
requirements() { local rest=${1#*|}; rest=${rest#*|}; echo "${rest#*|}"; }
names() { local f; for f in "${FORMATS[@]}"; do field "$f" 0; done; }

# missing_tools "<tool tool a|b>": prints the requirements that are not met
missing_tools() {
  local req alt found missing=()
  for req in $1; do
    found=0
    IFS='|' read -r -a alts <<<"$req"
    for alt in "${alts[@]}"; do command -v "$alt" >/dev/null 2>&1 && { found=1; break; }; done
    [ $found = 1 ] || missing+=("${req//|/ or }")
  done
  echo "${missing[*]:-}"
}

ONLY=""; EXCLUDE=""; TIMEOUT=3600; LOGS=""; BASE=""; REMOVE_IMAGES=0
while [ $# -gt 0 ]; do
  case "$1" in
    -f|--formats) ONLY="${2:-}"; shift 2 ;;
    -x|--exclude) EXCLUDE="${2:-}"; shift 2 ;;
    -t|--timeout) TIMEOUT="${2:-}"; shift 2 ;;
    -o|--logs) LOGS="${2:-}"; shift 2 ;;
    -r|--remove-images) REMOVE_IMAGES=1; shift ;;
    -l|--list)
      printf '%-10s %-55s %s\n' FORMAT SCRIPT "NEEDS (a|b: either)"
      for f in "${FORMATS[@]}"; do
        printf '%-10s %-55s %s\n' "$(field "$f" 0)" "$(field "$f" 1)$( [ -n "$(field "$f" 2)" ] && echo " ($(field "$f" 2))")" "$(requirements "$f")"
      done
      exit 0 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    *) BASE="$1"; shift ;;
  esac
done
[ -n "$BASE" ] || { usage >&2; exit 2; }
BASE=${BASE%/}
command -v curl >/dev/null || { echo "missing required tool: curl" >&2; exit 2; }
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || { echo "--timeout needs a number of seconds" >&2; exit 2; }

ALL=" $(names | tr '\n' ' ')"
for n in ${ONLY//,/ } ${EXCLUDE//,/ }; do
  case "$ALL " in *" $n "*) ;; *) echo "unknown format: $n (known:$ALL)" >&2; exit 2 ;; esac
done
selected() {
  local n=$1
  if [ -n "$ONLY" ]; then case " ${ONLY//,/ } " in *" $n "*) ;; *) return 1 ;; esac; fi
  case " ${EXCLUDE//,/ } " in *" $n "*) return 1 ;; esac
  return 0
}

LOGS=${LOGS:-$(mktemp -d /tmp/granary-verify-all.XXXX)}
mkdir -p "$LOGS" || exit 2

login() {
  curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASSWORD\"}" 2>/dev/null | sed -E 's/.*"token":"([^"]+)".*/\1/'
}
TOKEN=$(login)
[ -n "$TOKEN" ] || { echo "cannot log in to $BASE as $USER_NAME" >&2; exit 2; }
# the global settings the scripts change; restored after each one, so a script that stops half-way (offline mode
# still on, anonymous read off, base URL pointing at a TLS front that is gone) does not break the next one
SETTINGS=$(curl -fsS "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN")
[ -n "$SETTINGS" ] || { echo "cannot read the settings of $BASE (is $USER_NAME an administrator?)" >&2; exit 2; }
restore_settings() {
  local token; token=$(login)
  [ -n "$token" ] && curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' -d "$SETTINGS" >/dev/null 2>&1
}
VERSION=$(curl -fsS "$BASE/api/v1/system/info" | sed -nE 's/.*"version":"([^"]*)".*/\1/p')
echo "Granary ${VERSION:-?} at $BASE, logs in $LOGS"

count() { grep -c -E "^$1" "$2" 2>/dev/null || true; }
images() { docker image ls -q --no-trunc 2>/dev/null | sort -u; }
# remove_new_images <list before>: removes the images (tagged or not) that are not in the list
remove_new_images() {
  local id
  for id in $(comm -13 <(echo "$1") <(images)); do docker image rm -f "$id" >/dev/null 2>&1; done
}
if [ "$REMOVE_IMAGES" = 1 ] && ! docker info >/dev/null 2>&1; then REMOVE_IMAGES=0; fi
ROWS=(); FAILED=0; RAN=0
for f in "${FORMATS[@]}"; do
  name=$(field "$f" 0); script=$(field "$f" 1); env=$(field "$f" 2); req=$(requirements "$f")
  selected "$name" || continue
  log="$LOGS/$name.log"
  missing=$(missing_tools "$req")
  if [ -n "$missing" ]; then
    ROWS+=("$name|SKIP|-|-|-|-|needs $missing")
    echo "== $name: SKIP (needs $missing)"
    continue
  fi
  echo "== $name: $script"
  [ "$REMOVE_IMAGES" = 1 ] && before_images=$(images)
  start=$(date +%s)
  # shellcheck disable=SC2086
  env $env timeout --kill-after=30 "$TIMEOUT" bash "$ROOT/$script" "$BASE" >"$log" 2>&1 </dev/null
  code=$?
  secs=$(( $(date +%s) - start ))
  restore_settings
  [ "$REMOVE_IMAGES" = 1 ] && remove_new_images "$before_images"
  RAN=$((RAN + 1))
  pass=$(count 'PASS ' "$log"); fail=$(count '(FAIL |ERROR )' "$log"); other=$(count '(SKIP |BLOCK|KNOWN )' "$log")
  if [ "$code" = 124 ] || [ "$code" = 137 ]; then result=FAIL; note="time limit of ${TIMEOUT}s"
  elif [ "$code" != 0 ] || [ "$fail" != 0 ]; then result=FAIL; note="exit $code"
  else result=PASS; note=""
  fi
  [ "$result" = FAIL ] && FAILED=$((FAILED + 1))
  ROWS+=("$name|$result|$pass|$fail|$other|$(printf '%dm%02ds' $((secs / 60)) $((secs % 60)))|${note:+$note, }$log")
  echo "   $result  passed=$pass failed=$fail other=$other (${secs}s)"
  grep -E '^(FAIL |ERROR )' "$log" | sed 's/^/   /' | head -10
done

echo
printf '%-10s %-6s %6s %6s %6s %8s  %s\n' FORMAT RESULT PASSED FAILED OTHER TIME "NOTES / LOG"
for row in "${ROWS[@]}"; do
  IFS='|' read -r n r p fl o t note <<<"$row"
  printf '%-10s %-6s %6s %6s %6s %8s  %s\n' "$n" "$r" "$p" "$fl" "$o" "$t" "$note"
done
echo
echo "formats run: $RAN, failed: $FAILED, skipped: $(( ${#ROWS[@]} - RAN ))"
[ "$FAILED" = 0 ]
