#!/usr/bin/env bash
# End-to-end verification of Granary with the real package managers (mvn, npm, dotnet) including an offline run.
# Needs curl and a running Granary with admin/admin123; Maven needs a JDK (21+) and mvn, npm needs Node/npm, NuGet
# needs the .NET SDK (dotnet) on the PATH. A client that is missing is reported as SKIP, not as a failure.
# CLIENTS selects the clients to run (default "maven npm nuget"), e.g. CLIENTS=npm verify-clients.sh ...
# Every format's verification against one server: scripts/verify-all.sh.
# Usage: [CLIENTS="maven npm nuget"] verify-clients.sh http://localhost:8081   (admin/admin123 must be valid)
set -uo pipefail
BASE=${1:-http://localhost:8081}
BASE=${BASE%/}
HOSTPORT=${BASE#http://}
CLIENTS=${CLIENTS:-maven npm nuget}
WORK=$(mktemp -d /tmp/granary-verify.XXXX)
cd "$WORK"
PASS=0; FAIL=0; SKIP=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
skip() { echo "SKIP  $1"; SKIP=$((SKIP+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# want <client> <tool…>: the client was selected and its tools are installed (a missing tool is a SKIP)
want() {
  local client=$1 tool; shift
  case " ${CLIENTS//,/ } " in *" $client "*) ;; *) return 1 ;; esac
  for tool in "$@"; do
    command -v "$tool" >/dev/null || { skip "$client: $tool is not installed"; return 1; }
  done
}
command -v curl >/dev/null || { echo "missing required tool: curl"; exit 2; }

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || { bad "login"; echo "passed=$PASS failed=$FAIL skipped=$SKIP"; exit 1; }
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null && ok "standard repositories" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
# versions unique to this run, so the script can run again against the same server (re-deploys are refused)
RUN=$(date +%s)
REL="1.0.$RUN"; SNAP="1.1.$RUN-SNAPSHOT"
MAVEN=0; NPM=0; NUGET=0

if want maven mvn java; then
MAVEN=1
echo "== Maven"
mkdir -p mvn && cd mvn
cat > settings.xml <<EOF
<settings>
  <mirrors><mirror><id>granary</id><mirrorOf>*</mirrorOf><url>$BASE/repository/maven-public/</url></mirror></mirrors>
  <servers>
    <server><id>granary</id><username>admin</username><password>$APITOKEN</password></server>
    <server><id>granary-releases</id><username>admin</username><password>$APITOKEN</password></server>
    <server><id>granary-snapshots</id><username>admin</username><password>$APITOKEN</password></server>
  </servers>
  <profiles><profile><id>granary</id>
    <repositories><repository><id>granary</id><url>$BASE/repository/maven-public/</url>
      <releases><enabled>true</enabled></releases><snapshots><enabled>true</enabled></snapshots></repository></repositories>
    <pluginRepositories><pluginRepository><id>granary</id><url>$BASE/repository/maven-public/</url>
      <releases><enabled>true</enabled></releases><snapshots><enabled>true</enabled></snapshots></pluginRepository></pluginRepositories>
  </profile></profiles>
  <activeProfiles><activeProfile>granary</activeProfile></activeProfiles>
</settings>
EOF
mkdir -p demo/src/main/java/com/acme && cat > demo/pom.xml <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.acme</groupId><artifactId>demo</artifactId><version>$REL</version>
  <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
  <distributionManagement>
    <repository><id>granary-releases</id><url>$BASE/repository/maven-releases/</url></repository>
    <snapshotRepository><id>granary-snapshots</id><url>$BASE/repository/maven-snapshots/</url></snapshotRepository>
  </distributionManagement>
</project>
EOF
echo 'package com.acme; public class Demo { public static String hi() { return "hi"; } }' > demo/src/main/java/com/acme/Demo.java
STEP="mvn deploy (release, through Granary proxy for plugins)"; check mvn -B -q -s settings.xml -f demo/pom.xml -Dmaven.repo.local="$WORK/mvn/repo1" deploy
sed -i "s#<version>$REL</version>#<version>$SNAP</version>#" demo/pom.xml
STEP="mvn deploy snapshot"
check mvn -B -q -s settings.xml -f demo/pom.xml -Dmaven.repo.local="$WORK/mvn/repo1" deploy
STEP="mvn dependency:get own release via group"; check mvn -B -q -s settings.xml -Dmaven.repo.local="$WORK/mvn/repo2" dependency:get -Dartifact=com.acme:demo:$REL
STEP="mvn dependency:get own snapshot via group"; check mvn -B -q -s settings.xml -Dmaven.repo.local="$WORK/mvn/repo2" dependency:get -Dartifact=com.acme:demo:$SNAP
STEP="mvn dependency:get commons-lang3 via proxy"; check mvn -B -q -s settings.xml -Dmaven.repo.local="$WORK/mvn/repo2" dependency:get -Dartifact=org.apache.commons:commons-lang3:3.17.0
cd "$WORK"
fi

if want npm npm node; then
NPM=1
echo "== npm"
# a cache of its own, so the caller's npm cache is neither used nor wiped
export npm_config_cache="$WORK/npm/cache"
mkdir -p npm/pkg npm/consumer && cd npm
cat > .npmrc <<EOF
registry=$BASE/repository/npm-group/
//$HOSTPORT/repository/npm-group/:_authToken=$APITOKEN
//$HOSTPORT/repository/npm-hosted/:_authToken=$APITOKEN
EOF
cp .npmrc pkg/.npmrc; cp .npmrc consumer/.npmrc
cat > pkg/package.json <<EOF
{ "name": "@acme/demo", "version": "$REL", "main": "index.js", "license": "UNLICENSED" }
EOF
echo 'module.exports = "hi";' > pkg/index.js
STEP="npm publish to hosted"; check npm publish --prefix pkg --registry "$BASE/repository/npm-hosted/" --no-audit --no-fund --loglevel=error "$WORK/npm/pkg"
cat > consumer/package.json <<'EOF'
{ "name": "consumer", "version": "1.0.0", "private": true }
EOF
STEP="npm install own package via group"; check npm install --prefix consumer --no-audit --no-fund --loglevel=error @acme/demo@$REL
STEP="npm install left-pad via proxy"; check npm install --prefix consumer --no-audit --no-fund --loglevel=error left-pad@1.3.0
STEP="npm view dist-tags"; check npm view @acme/demo version
cd "$WORK"
fi

if want nuget dotnet; then
NUGET=1
echo "== NuGet"
mkdir -p nuget && cd nuget
# packages and the HTTP cache inside the work directory: the user's HTTP cache (kept for 30 minutes) would answer
# with the version list of an earlier run, and the offline restore must come from Granary alone
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 NUGET_PACKAGES="$WORK/nuget/packages" \
  NUGET_HTTP_CACHE_PATH="$WORK/nuget/http-cache"
cat > nuget.config <<EOF
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources><clear /><add key="granary" value="$BASE/repository/nuget-group/index.json" /></packageSources>
</configuration>
EOF
STEP="dotnet new classlib"; check dotnet new classlib -n Acme.Demo -o lib --force
STEP="dotnet pack"; check dotnet pack lib/Acme.Demo.csproj -c Release -p:PackageVersion=$REL -o out --nologo
STEP="dotnet nuget push to hosted"; check dotnet nuget push out/Acme.Demo.$REL.nupkg -s "$BASE/repository/nuget-hosted/index.json" -k "$APITOKEN"
STEP="dotnet new console"; check dotnet new console -n Consumer -o consumer --force
STEP="dotnet add package own via group"; check dotnet add consumer/Consumer.csproj package Acme.Demo -v $REL
STEP="dotnet add package Newtonsoft.Json via proxy"; check dotnet add consumer/Consumer.csproj package Newtonsoft.Json -v 13.0.3
STEP="dotnet restore"; check dotnet restore consumer/Consumer.csproj --force
cd "$WORK"
fi

if [ $((MAVEN + NPM + NUGET)) -gt 0 ]; then
echo "== Offline: everything above again with upstream access disabled and empty local caches"
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":true}' >/dev/null && ok "offline mode on" || bad "offline mode on"
if [ $MAVEN = 1 ]; then
  STEP="offline mvn dependency:get commons-lang3"; check mvn -B -q -s mvn/settings.xml -Dmaven.repo.local="$WORK/mvn/repo3" dependency:get -Dartifact=org.apache.commons:commons-lang3:3.17.0
  STEP="offline mvn dependency:get own release"; check mvn -B -q -s mvn/settings.xml -Dmaven.repo.local="$WORK/mvn/repo3" dependency:get -Dartifact=com.acme:demo:$REL
  STEP="offline uncached artifact is a clean 503"; code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/repository/maven-central/org/apache/commons/commons-csv/1.14.0/commons-csv-1.14.0.pom"); [ "$code" = "503" ] && ok "$STEP ($code)" || bad "$STEP ($code)"
fi
if [ $NPM = 1 ]; then
  rm -rf npm/consumer/node_modules npm/consumer/package-lock.json "$npm_config_cache"
  STEP="offline npm install left-pad and own package"; check npm install --prefix npm/consumer --no-audit --no-fund --loglevel=error left-pad@1.3.0 @acme/demo@$REL
fi
if [ $NUGET = 1 ]; then
  rm -rf "$NUGET_PACKAGES" "$NUGET_HTTP_CACHE_PATH" nuget/consumer/obj
  # pushd instead of a subshell, so the counters survive
  pushd nuget >/dev/null; STEP="offline dotnet restore"; check dotnet restore consumer/Consumer.csproj --force; popd >/dev/null
fi
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":false}' >/dev/null
fi

echo; echo "passed=$PASS failed=$FAIL skipped=$SKIP  (work dir $WORK)"
[ "$FAIL" = 0 ]
