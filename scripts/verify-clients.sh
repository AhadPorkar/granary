#!/usr/bin/env bash
# End-to-end verification of Granary with the real package managers (mvn, npm, dotnet) including an offline run.
# Needs a JDK (21+), Maven, Node/npm and the .NET SDK on the PATH, and a running Granary with admin/admin123.
# Usage: verify-clients.sh http://localhost:8081   (admin/admin123 must be valid)
set -uo pipefail
BASE=${1:-http://localhost:8081}
HOSTPORT=${BASE#http://}
WORK=$(mktemp -d /tmp/granary-verify.XXXX)
cd "$WORK"
PASS=0; FAIL=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null && ok "standard repositories" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"

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
  <groupId>com.acme</groupId><artifactId>demo</artifactId><version>1.0.0</version>
  <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
  <distributionManagement>
    <repository><id>granary-releases</id><url>$BASE/repository/maven-releases/</url></repository>
    <snapshotRepository><id>granary-snapshots</id><url>$BASE/repository/maven-snapshots/</url></snapshotRepository>
  </distributionManagement>
</project>
EOF
echo 'package com.acme; public class Demo { public static String hi() { return "hi"; } }' > demo/src/main/java/com/acme/Demo.java
STEP="mvn deploy (release, through Granary proxy for plugins)"; check mvn -B -q -s settings.xml -f demo/pom.xml -Dmaven.repo.local="$WORK/mvn/repo1" deploy
sed -i "s#<version>1.0.0</version>#<version>1.1.0-SNAPSHOT</version>#" demo/pom.xml
STEP="mvn deploy snapshot"
check mvn -B -q -s settings.xml -f demo/pom.xml -Dmaven.repo.local="$WORK/mvn/repo1" deploy
STEP="mvn dependency:get own release via group"; check mvn -B -q -s settings.xml -Dmaven.repo.local="$WORK/mvn/repo2" dependency:get -Dartifact=com.acme:demo:1.0.0
STEP="mvn dependency:get own snapshot via group"; check mvn -B -q -s settings.xml -Dmaven.repo.local="$WORK/mvn/repo2" dependency:get -Dartifact=com.acme:demo:1.1.0-SNAPSHOT
STEP="mvn dependency:get commons-lang3 via proxy"; check mvn -B -q -s settings.xml -Dmaven.repo.local="$WORK/mvn/repo2" dependency:get -Dartifact=org.apache.commons:commons-lang3:3.17.0
cd "$WORK"

echo "== npm"
npm cache clean --force --loglevel=error >/dev/null 2>&1
mkdir -p npm/pkg npm/consumer && cd npm
cat > .npmrc <<EOF
registry=$BASE/repository/npm-group/
//$HOSTPORT/repository/npm-group/:_authToken=$APITOKEN
//$HOSTPORT/repository/npm-hosted/:_authToken=$APITOKEN
EOF
cp .npmrc pkg/.npmrc; cp .npmrc consumer/.npmrc
cat > pkg/package.json <<'EOF'
{ "name": "@acme/demo", "version": "1.0.0", "main": "index.js", "license": "UNLICENSED" }
EOF
echo 'module.exports = "hi";' > pkg/index.js
STEP="npm publish to hosted"; check npm publish --prefix pkg --registry "$BASE/repository/npm-hosted/" --no-audit --no-fund --loglevel=error "$WORK/npm/pkg"
cat > consumer/package.json <<'EOF'
{ "name": "consumer", "version": "1.0.0", "private": true }
EOF
STEP="npm install own package via group"; check npm install --prefix consumer --no-audit --no-fund --loglevel=error @acme/demo@1.0.0
STEP="npm install left-pad via proxy"; check npm install --prefix consumer --no-audit --no-fund --loglevel=error left-pad@1.3.0
STEP="npm view dist-tags"; check npm view @acme/demo version
cd "$WORK"

echo "== NuGet"
mkdir -p nuget && cd nuget
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 NUGET_PACKAGES="$WORK/nuget/packages"
cat > nuget.config <<EOF
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources><clear /><add key="granary" value="$BASE/repository/nuget-group/index.json" /></packageSources>
</configuration>
EOF
STEP="dotnet new classlib"; check dotnet new classlib -n Acme.Demo -o lib --force
STEP="dotnet pack"; check dotnet pack lib/Acme.Demo.csproj -c Release -p:PackageVersion=1.0.0 -o out --nologo
STEP="dotnet nuget push to hosted"; check dotnet nuget push out/Acme.Demo.1.0.0.nupkg -s "$BASE/repository/nuget-hosted/index.json" -k "$APITOKEN"
STEP="dotnet new console"; check dotnet new console -n Consumer -o consumer --force
STEP="dotnet add package own via group"; check dotnet add consumer/Consumer.csproj package Acme.Demo -v 1.0.0
STEP="dotnet add package Newtonsoft.Json via proxy"; check dotnet add consumer/Consumer.csproj package Newtonsoft.Json -v 13.0.3
STEP="dotnet restore"; check dotnet restore consumer/Consumer.csproj --force
cd "$WORK"

echo "== Offline: everything above again with upstream access disabled and empty local caches"
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":true}' >/dev/null && ok "offline mode on" || bad "offline mode on"
STEP="offline mvn dependency:get commons-lang3"; check mvn -B -q -s mvn/settings.xml -Dmaven.repo.local="$WORK/mvn/repo3" dependency:get -Dartifact=org.apache.commons:commons-lang3:3.17.0
STEP="offline mvn dependency:get own release"; check mvn -B -q -s mvn/settings.xml -Dmaven.repo.local="$WORK/mvn/repo3" dependency:get -Dartifact=com.acme:demo:1.0.0
rm -rf npm/consumer/node_modules npm/consumer/package-lock.json; npm cache clean --force --loglevel=error >/dev/null 2>&1
STEP="offline npm install left-pad"; check npm install --prefix npm/consumer --no-audit --no-fund --loglevel=error left-pad@1.3.0 @acme/demo@1.0.0
rm -rf "$NUGET_PACKAGES" nuget/consumer/obj
STEP="offline dotnet restore"; (cd nuget && check dotnet restore consumer/Consumer.csproj --force)
STEP="offline uncached artifact is a clean 503"; code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/repository/maven-central/org/apache/commons/commons-csv/1.14.0/commons-csv-1.14.0.pom"); [ "$code" = "503" ] && ok "$STEP ($code)" || bad "$STEP ($code)"
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":false}' >/dev/null

echo; echo "passed=$PASS failed=$FAIL  (work dir $WORK)"
[ "$FAIL" = 0 ]
