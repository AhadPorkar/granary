#!/usr/bin/env bash
# End-to-end verification of the RubyGems format with the real clients (gem and Bundler), including an offline run
# against the Granary cache.
#
# Publishes with gem build + gem push (API key from ~/.gem/credentials, and a key obtained with gem signin), yanks
# with gem yank, installs with gem install, gem fetch, gem search and bundle install (Bundler talks the compact
# index, gem install the compact index or the Marshal indexes), all through rubygems-group, which also proxies
# rubygems.org. Everything runs with its own HOME, GEM_HOME and Bundler configuration inside the work directory.
#
# Needs curl, ruby, gem, bundle and script (util-linux, for the interactive gem signin) on the PATH and a running
# Granary with admin/admin123.
# Usage: verify-rubygems.sh http://localhost:8081
set -uo pipefail
BASE=${1:-http://localhost:8081}
BASE=${BASE%/}
SCHEME=${BASE%%://*}
HOSTPORT=${BASE#*://}; HOSTPORT=${HOSTPORT%%/*}
WORK=$(mktemp -d /tmp/granary-verify-rubygems.XXXX)
cd "$WORK"
PASS=0; FAIL=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# expect <text> <command…>: the command succeeds and prints the text
expect(){ local text=$1; shift; if "$@" >"$WORK/last.log" 2>&1 && grep -qF -- "$text" "$WORK/last.log"; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# refuse <text> <command…>: the command fails and prints the text
refuse(){ local text=$1; shift; if "$@" >"$WORK/last.log" 2>&1; then bad "$STEP (succeeded)"; tail -5 "$WORK/last.log"; else grep -qF -- "$text" "$WORK/last.log" && ok "$STEP" || { bad "$STEP"; tail -20 "$WORK/last.log"; }; fi; }
# checkin <dir> <command…>: check in another directory without a subshell, so the counters survive
checkin(){ local dir=$1; shift; pushd "$dir" >/dev/null || { bad "$STEP (no $dir)"; return; }; check "$@"; popd >/dev/null; }
for tool in curl ruby gem bundle script; do command -v "$tool" >/dev/null || { echo "missing required tool: $tool"; exit 2; }; done

# nothing from the caller's environment may point the tools elsewhere or reuse their caches
unset GEM_HOST_API_KEY RUBYGEMS_HOST BUNDLE_GEMFILE BUNDLE_PATH BUNDLE_MIRROR__ALL GEM_PATH
export HOME="$WORK/home" XDG_CONFIG_HOME="$WORK/home/.config" XDG_CACHE_HOME="$WORK/home/.cache" XDG_DATA_HOME="$WORK/home/.local/share"
export GEM_HOME="$WORK/gems" GEMRC="$WORK/gemrc" BUNDLE_USER_HOME="$WORK/home/.bundle" BUNDLE_SILENCE_ROOT_WARNING=1
export PATH="$GEM_HOME/bin:$PATH"
mkdir -p "$HOME/.gem" "$GEM_HOME"
printf -- '---\n:sources: []\n:update_sources: false\n' > "$GEMRC"
echo "ruby $(ruby -e 'print RUBY_VERSION'), gem $(gem --version), bundler $(bundle --version | awk '{print $NF}')"

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null && ok "standard repositories (rubygems-proxy, rubygems-hosted, rubygems-group)" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify-rubygems"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
# a rerun against the same server: online again, and the demo gems of the last run removed
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":false}' >/dev/null
for id in $(curl -fsS "$BASE/api/v1/repositories/rubygems-hosted/components?q=granary_demo&size=500" -H "Authorization: Bearer $TOKEN" \
    | grep -oE '"id":"[0-9a-f-]{36}"' | cut -d'"' -f4); do
  curl -fsS -X DELETE "$BASE/api/v1/repositories/rubygems-hosted/components/$id" -H "Authorization: Bearer $TOKEN" >/dev/null
done
HOSTED="$BASE/repository/rubygems-hosted"
GROUP="$BASE/repository/rubygems-group/"
PROXY="$BASE/repository/rubygems-proxy/"
# gem and Bundler take credentials from the source URL
GROUP_AUTH="$SCHEME://admin:$APITOKEN@$HOSTPORT/repository/rubygems-group/"
printf -- '---\n:granary: %s\n' "$APITOKEN" > "$HOME/.gem/credentials"; chmod 0600 "$HOME/.gem/credentials"

echo "== gem build"
# demo <name> <version> [runtime dependency]: a one-file gem
demo() {
  local dependency=""
  [ -n "${3:-}" ] && dependency="s.add_dependency \"$3\""
  mkdir -p "$WORK/src/$1-$2/lib"
  echo "module $(ruby -e 'print ARGV[0].split("_").map(&:capitalize).join' "$1"); VERSION = \"$2\"; def self.hello = \"hello from $1 $2\"; end" \
    > "$WORK/src/$1-$2/lib/$1.rb"
  cat > "$WORK/src/$1-$2/$1.gemspec" <<GEMSPEC
Gem::Specification.new do |s|
  s.name = "$1"
  s.version = "$2"
  s.summary = "Demo gem for the Granary RubyGems verification"
  s.authors = ["Acme Developers"]
  s.license = "MIT"
  s.files = ["lib/$1.rb"]
  s.required_ruby_version = ">= 2.7"
  s.metadata["allowed_push_host"] = "$HOSTED"
  $dependency
end
GEMSPEC
  STEP="gem build $1-$2"; checkin "$WORK/src/$1-$2" gem build "$1.gemspec"
}
demo granary_demo_hello 0.1.0 "rake"
demo granary_demo_hello 0.2.0 "rake"
demo granary_demo_tools 1.0.0 "granary_demo_hello"

echo "== gem push"
STEP="gem push granary_demo_hello 0.1.0 (--key granary)"
expect "Successfully registered gem: granary_demo_hello (0.1.0)" gem push --host "$HOSTED" --key granary "$WORK/src/granary_demo_hello-0.1.0/granary_demo_hello-0.1.0.gem"
STEP="gem push granary_demo_hello 0.2.0 (GEM_HOST_API_KEY)"
expect "Successfully registered gem: granary_demo_hello (0.2.0)" env GEM_HOST_API_KEY="$APITOKEN" gem push --host "$HOSTED" "$WORK/src/granary_demo_hello-0.2.0/granary_demo_hello-0.2.0.gem"
STEP="gem push of an existing version is refused (409)"
refuse "Repushing of gem versions is not allowed" gem push --host "$HOSTED" --key granary "$WORK/src/granary_demo_hello-0.1.0/granary_demo_hello-0.1.0.gem"
STEP="gem push to the group is refused and names the hosted member"
refuse "rubygems-hosted" gem push --host "${GROUP%/}" --key granary "$WORK/src/granary_demo_hello-0.1.0/granary_demo_hello-0.1.0.gem"

echo "== gem signin (interactive, in a pseudo terminal)"
# RUBYGEMS_HOST rather than --host: with --host alone gem first sends the credentials to rubygems.org
STEP="gem signin with username and password"
printf 'admin\nadmin123\nverify-signin\nn\n' | script -qec "env RUBYGEMS_HOST='$HOSTED' gem signin" /dev/null >"$WORK/last.log" 2>&1
grep -q "Signed in with API key: verify-signin" "$WORK/last.log" && ok "$STEP" || { bad "$STEP"; tail -20 "$WORK/last.log"; }
STEP="the signin key is stored for the repository URL"; check grep -Eq "^$HOSTED: \"?gry_" "$HOME/.gem/credentials"
STEP="gem push granary_demo_tools 1.0.0 with the signin key"
expect "Successfully registered gem: granary_demo_tools (1.0.0)" gem push --host "$HOSTED" "$WORK/src/granary_demo_tools-1.0.0/granary_demo_tools-1.0.0.gem"
STEP="gem signin with a wrong password is refused"
printf 'admin\nwrong\nx\nn\n' | script -qec "env HOME='$WORK/h2' RUBYGEMS_HOST='$HOSTED' gem signin" /dev/null >"$WORK/last.log" 2>&1
grep -q "Signed in" "$WORK/last.log" && { bad "$STEP"; tail -5 "$WORK/last.log"; } || ok "$STEP"

echo "== gem install, fetch, search, list"
STEP="gem install granary_demo_tools via rubygems-group (own gems + rake through rubygems.org)"
check gem install --clear-sources --source "$GROUP_AUTH" --no-document granary_demo_tools
STEP="installed granary_demo_tools, granary_demo_hello 0.2.0 and rake"
check sh -c 'gem list -i granary_demo_tools -v 1.0.0 && gem list -i granary_demo_hello -v 0.2.0 && gem list -i rake'
STEP="ruby requires the installed gems"; expect "hello from granary_demo_hello 0.2.0" ruby -e 'require "granary_demo_tools"; require "granary_demo_hello"; require "rake"; puts GranaryDemoHello.hello'
STEP="gem install an older version"; check gem install --clear-sources --source "$GROUP_AUTH" --no-document granary_demo_hello -v 0.1.0
STEP="gem fetch rake via rubygems-proxy"; checkin "$WORK" gem fetch --clear-sources --source "$PROXY" rake
ls "$WORK"/rake-*.gem >/dev/null 2>&1 && ok "rake .gem downloaded" || bad "rake .gem downloaded"
STEP="gem search --remote finds the hosted gems through the group (Marshal indexes)"
expect "granary_demo_hello (0.2.0)" gem search --remote --clear-sources --source "$GROUP_AUTH" granary_demo
STEP="gem list --remote --all on rubygems-hosted"
expect "granary_demo_hello (0.2.0, 0.1.0)" gem list --remote --all --clear-sources --source "$HOSTED/" granary_demo_hello
STEP="gem sources --add accepts the group"; check gem sources --config-file "$WORK/sources-gemrc" --add "$GROUP_AUTH"

echo "== Bundler"
mkdir -p "$WORK/bundle" && cd "$WORK/bundle"
cat > Gemfile <<GEMFILE
source "$GROUP"

gem "granary_demo_tools", "1.0.0"
gem "rack", "~> 3.0"
GEMFILE
STEP="bundle config credentials for the group"; check bundle config set --global "$GROUP" "admin:$APITOKEN"
STEP="bundle config path"; check bundle config set --local path "$WORK/bundle/vendor"
STEP="bundle install (compact index through rubygems-group)"; check bundle install --jobs 4
STEP="Gemfile.lock pins granary_demo_tools, granary_demo_hello and rack"
check sh -c 'grep -q "granary_demo_tools (1.0.0)" Gemfile.lock && grep -q "granary_demo_hello (" Gemfile.lock && grep -q "rack (3" Gemfile.lock'
if grep -q "^CHECKSUMS" Gemfile.lock; then
  STEP="Gemfile.lock CHECKSUMS match the pushed gem"
  check grep -q "granary_demo_hello (0.2.0) sha256=$(sha256sum "$WORK/src/granary_demo_hello-0.2.0/granary_demo_hello-0.2.0.gem" | cut -d' ' -f1)" Gemfile.lock
fi
STEP="bundle exec ruby uses them"; expect "hello from granary_demo_hello" bundle exec ruby -e 'require "granary_demo_tools"; require "granary_demo_hello"; require "rack"; puts GranaryDemoHello.hello'
cd "$WORK"
# Bundler 2 as well, when the Ruby installation still has one (Ruby 3.3 ships 2.5 as a default gem)
BUNDLER2=$(ruby -e 'v = Gem::Specification.find_all_by_name("bundler").map(&:version).select { |x| x.segments.first == 2 }.max; print v if v')
if [ -n "$BUNDLER2" ]; then
  mkdir -p "$WORK/bundle2" && cp "$WORK/bundle/Gemfile" "$WORK/bundle2/Gemfile"
  STEP="bundle _${BUNDLER2}_ install (compact index through rubygems-group)"
  checkin "$WORK/bundle2" sh -c "bundle _${BUNDLER2}_ config set --local path '$WORK/bundle2/vendor' && bundle _${BUNDLER2}_ install"
  STEP="Bundler $BUNDLER2 lock pins granary_demo_tools"; check grep -q "granary_demo_tools (1.0.0)" "$WORK/bundle2/Gemfile.lock"
else
  echo "SKIP  Bundler 2 (not installed)"
fi
mkdir -p "$WORK/mirror" && cd "$WORK/mirror"
printf 'source "https://rubygems.org"\n\ngem "rake"\n' > Gemfile
STEP="bundle config mirror.https://rubygems.org"; check bundle config set --local mirror.https://rubygems.org "$GROUP"
STEP="bundle config path (mirror)"; check bundle config set --local path "$WORK/mirror/vendor"
STEP="bundle install through the mirror"; check bundle install
STEP="Gemfile.lock lists rake"; check grep -q "rake (" Gemfile.lock
cd "$WORK"

echo "== gem yank"
STEP="gem yank granary_demo_hello 0.1.0"
expect "Successfully deleted gem: granary_demo_hello (0.1.0)" gem yank --host "$HOSTED" --key granary granary_demo_hello --version 0.1.0
STEP="the yanked version is gone from the compact index"
check sh -c "! curl -fsS -u 'admin:$APITOKEN' '$HOSTED/info/granary_demo_hello' | grep -q '^0.1.0 '"
STEP="gem install of the yanked version fails"
refuse "Could not find a valid gem" gem install --clear-sources --source "$GROUP_AUTH" --no-document --install-dir "$WORK/yanked" granary_demo_hello -v 0.1.0
STEP="unyank (PUT api/v1/gems/unyank)"
expect "Successfully reinstated gem: granary_demo_hello (0.1.0)" curl -fsS -X PUT -H "Authorization: $APITOKEN" -d gem_name=granary_demo_hello -d version=0.1.0 "$HOSTED/api/v1/gems/unyank"
STEP="the version is back"; check sh -c "curl -fsS -u 'admin:$APITOKEN' '$HOSTED/info/granary_demo_hello' | grep -q '^0.1.0 '"

echo "== JSON API"
STEP="api/v1/gems/granary_demo_hello.json via group"; expect '"name":"granary_demo_hello"' curl -fsS -u "admin:$APITOKEN" "${GROUP}api/v1/gems/granary_demo_hello.json"
STEP="api/v1/versions/rake/latest.json via proxy"; expect '"version"' curl -fsS -u "admin:$APITOKEN" "${PROXY}api/v1/versions/rake/latest.json"

echo "== Offline: install everything again with upstream access disabled and empty local caches"
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":true}' >/dev/null && ok "offline mode on" || bad "offline mode on"
rm -rf "$HOME/.gem/specs" "$HOME/.local/share/gem/specs" "$HOME/.cache/gem" "$HOME/.bundle/cache" "$WORK/bundle/vendor" "$WORK/mirror/vendor" "$WORK/bundle2/vendor"
export GEM_HOME="$WORK/gems-offline"
STEP="offline gem install granary_demo_tools (own gems + rake from the proxy cache)"
check gem install --clear-sources --source "$GROUP_AUTH" --no-document granary_demo_tools
STEP="offline installed gems load"; expect "hello from granary_demo_hello" ruby -e 'require "granary_demo_tools"; require "granary_demo_hello"; require "rake"; puts GranaryDemoHello.hello'
STEP="offline bundle install from scratch"; checkin "$WORK/bundle" sh -c "bundle config set --local path '$WORK/bundle/vendor-offline' && bundle install"
STEP="offline bundle install through the mirror"; checkin "$WORK/mirror" sh -c "bundle config set --local path '$WORK/mirror/vendor-offline' && bundle install"
if [ -n "$BUNDLER2" ]; then
  STEP="offline bundle _${BUNDLER2}_ install from scratch"
  checkin "$WORK/bundle2" sh -c "bundle _${BUNDLER2}_ config set --local path '$WORK/bundle2/vendor-offline' && bundle _${BUNDLER2}_ install"
fi
STEP="offline uncached gem is reported as not found by gem"
refuse "Could not find a valid gem" gem install --clear-sources --source "$GROUP_AUTH" --no-document rails
STEP="offline uncached info is a clean 503"; code=$(curl -s -o /dev/null -w "%{http_code}" -u "admin:$APITOKEN" "${GROUP}info/rails"); [ "$code" = "503" ] && ok "$STEP ($code)" || bad "$STEP ($code)"
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":false}' >/dev/null

echo; echo "passed=$PASS failed=$FAIL  (work dir $WORK)"
[ "$FAIL" = 0 ]
