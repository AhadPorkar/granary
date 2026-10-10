#!/usr/bin/env bash
# End-to-end verification of the PyPI format with the real Python tooling, including an offline run against
# the Granary cache.
#
# Publishers: twine, uv publish, hatch publish, flit publish, poetry publish (one demo project each, all into
# pypi-hosted). Installers: pip, uv, poetry, pdm, pipenv (all through pypi-group, which also proxies pypi.org).
# Also checks a proxy whose upstream is another Granary repository (chained proxies) and, when a Docker daemon
# is available, a docker build that uses the Dockerfile snippet of the connect dialogue.
#
# Needs curl, python3 (with venv) and uv on the PATH and a running Granary with admin/admin123. Any other tool
# that is missing (twine, poetry, hatch, flit, pdm, pipenv) is installed into the work directory with
# "uv tool install" through the Granary group itself, so nothing outside the work directory is changed.
# Usage: verify-pypi.sh http://localhost:8081
set -uo pipefail
BASE=${1:-http://localhost:8081}
BASE=${BASE%/}
HOSTPORT=${BASE#http://}; HOSTPORT=${HOSTPORT#https://}; HOSTPORT=${HOSTPORT%%/*}
HOSTNAME_ONLY=${HOSTPORT%%:*}
WORK=$(mktemp -d /tmp/granary-verify-pypi.XXXX)
cd "$WORK"
PASS=0; FAIL=0
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
check(){ if "$@" >"$WORK/last.log" 2>&1; then ok "$STEP"; else bad "$STEP"; tail -20 "$WORK/last.log"; fi; }
# checkin <dir> <command…>: check in another directory without a subshell, so the counters survive
checkin(){ local dir=$1; shift; pushd "$dir" >/dev/null || { bad "$STEP (no $dir)"; return; }; check "$@"; popd >/dev/null; }
for tool in curl python3 uv; do command -v "$tool" >/dev/null || { echo "missing required tool: $tool"; exit 2; }; done

# pip only trusts plain http on localhost; everything else needs --trusted-host (uv does not care)
TRUSTED=""; PDM_VERIFY=true; PIPENV_VERIFY=true
case "$BASE" in http://*)
  PDM_VERIFY=false; PIPENV_VERIFY=false
  case "$HOSTNAME_ONLY" in localhost|127.*|::1) ;; *) TRUSTED="--trusted-host $HOSTPORT";; esac;;
esac
# nothing from the caller's environment may point the tools elsewhere
unset PIP_INDEX_URL PIP_EXTRA_INDEX_URL UV_INDEX_URL UV_DEFAULT_INDEX UV_EXTRA_INDEX_URL UV_INDEX PIPENV_PYPI_MIRROR
export PIP_CONFIG_FILE=/dev/null PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_INPUT=1 UV_NO_PROGRESS=1 POETRY_NO_INTERACTION=1
export UV_CACHE_DIR="$WORK/uv-cache" POETRY_CACHE_DIR="$WORK/poetry-cache" PDM_CACHE_DIR="$WORK/pdm-cache"
export PDM_HOME="$WORK/pdm-home" PDM_CONFIG_FILE="$WORK/pdm-config.toml" PDM_CHECK_UPDATE=false
export PIPENV_CACHE_DIR="$WORK/pipenv-cache" PIPENV_VENV_IN_PROJECT=1 PIPENV_NOSPIN=1 PIPENV_YES=1
export PIPENV_IGNORE_VIRTUALENVS=1 POETRY_VIRTUALENVS_IN_PROJECT=true
export UV_TOOL_DIR="$WORK/tools" UV_TOOL_BIN_DIR="$WORK/bin" PATH="$WORK/bin:$PATH"

echo "== API"
TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
[ -n "$TOKEN" ] && ok "login" || bad "login"
curl -fsS -X POST "$BASE/api/v1/repositories/standard" -H "Authorization: Bearer $TOKEN" >/dev/null && ok "standard repositories (pypi-proxy, pypi-hosted, pypi-group)" || bad "standard repositories"
APITOKEN=$(curl -fsS -X POST "$BASE/api/v1/tokens" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"verify-pypi"}' | sed -E 's/.*"secret":"([^"]+)".*/\1/')
[[ "$APITOKEN" == gry_* ]] && ok "api token" || bad "api token"
HOSTED="$BASE/repository/pypi-hosted/"
GROUP="$BASE/repository/pypi-group/simple"

echo "== Tools"
need() { # need <command> [package]: use the installed tool, otherwise install it through the Granary group
  if command -v "$1" >/dev/null; then ok "$1 found ($(command -v "$1"))"; return; fi
  STEP="uv tool install ${2:-$1} through pypi-group"; check uv tool install --default-index "$GROUP" "${2:-$1}"
}
need twine; need poetry; need hatch; need flit; need pdm; need pipenv

# demo <dir> <name> <backend requires> <backend>: a project with one module and no dependencies
demo() {
  mkdir -p "$1/src/${2//-/_}"
  cat > "$1/pyproject.toml" <<PYPROJECT
[build-system]
requires = ["$3"]
build-backend = "$4"

[project]
name = "$2"
version = "1.0.0"
description = "Demo package for the Granary PyPI verification"
requires-python = ">=3.9"
PYPROJECT
  printf '"""Demo package for the Granary PyPI verification."""\n__version__ = "1.0.0"\n' > "$1/src/${2//-/_}/__init__.py"
}

echo "== Build acme-demo 1.0.0 (depends on six from pypi.org)"
demo pkg acme-demo "setuptools>=68" setuptools.build_meta
sed -i 's/^requires-python = ">=3.9"$/requires-python = ">=3.9"\ndependencies = ["six>=1.16"]/' pkg/pyproject.toml
# the build backend (setuptools) is resolved through the Granary proxy as well
STEP="uv build (sdist + wheel, setuptools via pypi-proxy)"; checkin pkg env UV_DEFAULT_INDEX="$GROUP" uv build --no-cache
ls pkg/dist/acme_demo-1.0.0-py3-none-any.whl pkg/dist/acme_demo-1.0.0.tar.gz >/dev/null 2>&1 && ok "dist/ has wheel and sdist" || bad "dist/ has wheel and sdist"

echo "== twine"
cat > pypirc <<PYPIRC
[distutils]
index-servers =
    granary

[granary]
repository = $HOSTED
username = __token__
password = $APITOKEN
PYPIRC
STEP="twine upload to pypi-hosted"; check twine upload --non-interactive --config-file pypirc -r granary pkg/dist/*
STEP="twine upload again is refused (400 File already exists)"
# twine prints the server's reason only with --verbose
if twine upload --verbose --non-interactive --config-file pypirc -r granary pkg/dist/* >"$WORK/last.log" 2>&1; then bad "$STEP"; else grep -q "File already exists" "$WORK/last.log" && ok "$STEP" || { bad "$STEP"; tail -5 "$WORK/last.log"; }; fi
STEP="hosted simple page lists both files with PEP 658 metadata"
curl -fsS -H 'Accept: application/vnd.pypi.simple.v1+json' "$BASE/repository/pypi-hosted/simple/acme-demo/" >"$WORK/page.json" 2>&1 \
  && grep -q 'acme_demo-1.0.0-py3-none-any.whl' "$WORK/page.json" && grep -q 'acme_demo-1.0.0.tar.gz' "$WORK/page.json" && grep -q 'core-metadata' "$WORK/page.json" \
  && ok "$STEP" || bad "$STEP"

echo "== uv publish, hatch publish, flit publish, poetry publish"
demo uvpkg acme-uv "setuptools>=68" setuptools.build_meta
STEP="uv build acme-uv"; checkin uvpkg env UV_DEFAULT_INDEX="$GROUP" uv build --no-cache
STEP="uv publish acme-uv"; checkin uvpkg uv publish --publish-url "$HOSTED" --check-url "$GROUP" --username __token__ --password "$APITOKEN"
STEP="uv publish again skips the existing files (--check-url)"
checkin uvpkg sh -c 'uv publish --publish-url "$1" --check-url "$2" --username __token__ --password "$3" 2>&1 | tee /dev/stderr | grep -q "already exists, skipping"' \
  sh "$HOSTED" "$GROUP" "$APITOKEN"
demo hatchpkg acme-hatch hatchling hatchling.build
STEP="uv build acme-hatch (hatchling via pypi-proxy)"; checkin hatchpkg env UV_DEFAULT_INDEX="$GROUP" uv build --no-cache
STEP="hatch publish acme-hatch"; checkin hatchpkg hatch publish -r "$HOSTED" -u __token__ -a "$APITOKEN" dist/acme_hatch-1.0.0-py3-none-any.whl dist/acme_hatch-1.0.0.tar.gz
demo flitpkg acme-flit "flit_core>=3.4,<4" flit_core.buildapi
mv flitpkg/src/acme_flit flitpkg/acme_flit && rmdir flitpkg/src
STEP="flit publish acme-flit (builds itself, uses the .pypirc section)"; checkin flitpkg flit publish --pypirc "$WORK/pypirc" --repository granary
demo poetrypkg acme-poetry "poetry-core>=2.0" poetry.core.masonry.api
printf '\n[tool.poetry]\npackages = [{ include = "acme_poetry", from = "src" }]\n' >> poetrypkg/pyproject.toml
STEP="poetry config repositories.granary"; checkin poetrypkg poetry config --local repositories.granary "$HOSTED"
STEP="poetry config http-basic.granary"; checkin poetrypkg poetry config --local http-basic.granary __token__ "$APITOKEN"
STEP="poetry publish --build acme-poetry"; checkin poetrypkg poetry publish --build -r granary
STEP="hosted project list has all five projects"
curl -fsS -H 'Accept: application/vnd.pypi.simple.v1+json' "$BASE/repository/pypi-hosted/simple/" >"$WORK/list.json" 2>&1
missing=""; for p in acme-demo acme-uv acme-hatch acme-flit acme-poetry; do grep -q "\"$p\"" "$WORK/list.json" || missing="$missing $p"; done
[ -z "$missing" ] && ok "$STEP" || bad "$STEP (missing:$missing)"

echo "== pip"
STEP="python -m venv"; check python3 -m venv --without-pip venv-pip
# pip itself comes through the Granary proxy, like everything else
STEP="bootstrap pip through pypi-group"; check uv pip install --python venv-pip/bin/python --default-index "$GROUP" --no-cache pip
STEP="pip install acme-demo via pypi-group (own package + six through pypi.org)"
check venv-pip/bin/python -m pip install --no-cache-dir --index-url "$GROUP" $TRUSTED acme-demo==1.0.0
STEP="pip installs what the other publishers uploaded"
check venv-pip/bin/python -m pip install --no-cache-dir --index-url "$GROUP" $TRUSTED acme-uv==1.0.0 acme-hatch==1.0.0 acme-flit==1.0.0 acme-poetry==1.0.0
STEP="pip imports every demo package and six"; check venv-pip/bin/python -c 'import acme_demo, acme_uv, acme_hatch, acme_flit, acme_poetry, six; print(acme_demo.__version__, six.__version__)'
STEP="pip download of a sdist-only project via proxy"; check venv-pip/bin/python -m pip download --no-cache-dir --no-deps --no-binary :all: --index-url "$GROUP" $TRUSTED -d dl "six==1.16.0"

echo "== uv"
STEP="uv venv"; check uv venv venv-uv
STEP="uv pip install acme-demo via pypi-group"; check env UV_CACHE_DIR="$WORK/uv-cache1" uv pip install --python venv-uv/bin/python --default-index "$GROUP" acme-demo==1.0.0
STEP="uv imports acme_demo and six"; check venv-uv/bin/python -c 'import acme_demo, six; print(acme_demo.__version__, six.__version__)'

echo "== poetry"
mkdir -p poetrydemo && cd poetrydemo
cat > pyproject.toml <<'PYPROJECT'
[project]
name = "poetrydemo"
version = "0.1.0"
description = "consumer"
requires-python = ">=3.9"
dependencies = ["acme-demo==1.0.0", "acme-poetry==1.0.0"]

[tool.poetry]
package-mode = false
PYPROJECT
STEP="poetry source add pypi-group"; check poetry source add --priority=primary granary "$GROUP"
STEP="poetry config http-basic"; check poetry config http-basic.granary admin "$APITOKEN" --local
STEP="poetry lock"; check poetry lock
STEP="poetry install"; check poetry install --no-root
STEP="poetry env imports acme_demo and acme_poetry"; check poetry run python -c 'import acme_demo, acme_poetry, six; print(acme_demo.__version__)'
cd "$WORK"

echo "== pdm"
mkdir -p pdmdemo && cd pdmdemo
cat > pyproject.toml <<'PYPROJECT'
[project]
name = "pdmdemo"
version = "0.1.0"
requires-python = ">=3.9"
dependencies = ["acme-demo==1.0.0", "acme-flit==1.0.0"]

[tool.pdm]
distribution = false
PYPROJECT
STEP="pdm config pypi.url"; check pdm config --local pypi.url "$GROUP"
STEP="pdm config credentials"; check sh -c "pdm config --local pypi.username admin && pdm config --local pypi.password '$APITOKEN' && pdm config --local pypi.verify_ssl $PDM_VERIFY"
STEP="pdm lock"; check pdm lock
STEP="pdm install"; check pdm install
STEP="pdm env imports acme_demo and acme_flit"; check .venv/bin/python -c 'import acme_demo, acme_flit, six'
cd "$WORK"

echo "== pipenv"
mkdir -p pipenvdemo && cd pipenvdemo
cat > Pipfile <<PIPFILE
[[source]]
url = "$GROUP"
verify_ssl = $PIPENV_VERIFY
name = "granary"

[packages]
acme-demo = "==1.0.0"
acme-hatch = "==1.0.0"
PIPFILE
STEP="pipenv install (lock + install)"; check pipenv install
STEP="pipenv env imports acme_demo and acme_hatch"; check .venv/bin/python -c 'import acme_demo, acme_hatch, six'
cd "$WORK"

echo "== Docker build with the connect dialogue's Dockerfile snippet (needs docker; the build uses the host network)"
if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  mkdir -p dockerbuild
  printf 'acme-demo==1.0.0\nacme-flit==1.0.0\n' > dockerbuild/requirements.txt
  STEP="connect snippets of pypi-group"
  check sh -c "curl -fsS -H 'Authorization: Bearer $TOKEN' '$BASE/api/v1/repositories/pypi-group/connect' > '$WORK/connect.json'"
  { echo "FROM python:3.13-slim"; echo "COPY requirements.txt ."
    python3 -I -c 'import json, sys; print(next(s["code"] for s in json.load(open(sys.argv[1])) if s["id"] == "dockerfile"))' "$WORK/connect.json"
    echo 'RUN python -c "import acme_demo, acme_flit, six"'; } > dockerbuild/Dockerfile
  STEP="docker build installs from pypi-group"; check docker build --network host --no-cache -t granary-verify-pypi dockerbuild
  docker rmi granary-verify-pypi >/dev/null 2>&1
else
  echo "SKIP  docker build (no docker daemon)"
fi

echo "== Chained proxy: a proxy whose upstream is this Granary's pypi-hosted (simple URL as remoteUrl)"
STEP="create pypi-chained"
check curl -fsS -X POST "$BASE/api/v1/repositories" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"pypi-chained\",\"format\":\"pypi\",\"type\":\"PROXY\",\"remoteUrl\":\"$BASE/repository/pypi-hosted/simple/\"}"
STEP="pip install acme-uv through the chained proxy"
check venv-pip/bin/python -m pip install --no-cache-dir --force-reinstall --no-deps --index-url "$BASE/repository/pypi-chained/simple" $TRUSTED acme-uv==1.0.0

echo "== JSON API and downloads"
STEP="pypi/acme-demo/json via group"; check curl -fsS "$BASE/repository/pypi-group/pypi/acme-demo/json"
STEP="pypi/six/json via proxy"; check curl -fsS "$BASE/repository/pypi-proxy/pypi/six/json"
STEP="non-canonical name redirects"; code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/repository/pypi-group/simple/Acme.Demo"); [ "$code" = "301" ] && ok "$STEP ($code)" || bad "$STEP ($code)"

echo "== Offline: install everything again with upstream access disabled and empty local caches"
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":true}' >/dev/null && ok "offline mode on" || bad "offline mode on"
venv-pip/bin/python -m pip cache purge >/dev/null 2>&1
STEP="offline fresh venv"; check python3 -m venv --without-pip venv-pip-offline
STEP="offline bootstrap pip from the cache"; check uv pip install --python venv-pip-offline/bin/python --default-index "$GROUP" --cache-dir "$(mktemp -d)" pip
STEP="offline pip install acme-demo + six (no pip cache)"; check venv-pip-offline/bin/python -m pip install --no-cache-dir --index-url "$GROUP" $TRUSTED acme-demo==1.0.0
STEP="offline uv venv"; check uv venv venv-uv-offline
STEP="offline uv pip install acme-demo + six (empty uv cache)"; check env UV_CACHE_DIR="$(mktemp -d)" uv pip install --python venv-uv-offline/bin/python --default-index "$GROUP" acme-demo==1.0.0
STEP="offline poetry install from scratch"; rm -rf poetrydemo/.venv; checkin poetrydemo env POETRY_CACHE_DIR="$(mktemp -d)" poetry install --no-root
STEP="offline pdm install from scratch"; rm -rf pdmdemo/.venv; checkin pdmdemo env PDM_CACHE_DIR="$(mktemp -d)" pdm install
STEP="offline pipenv install from the lock file"; rm -rf pipenvdemo/.venv; checkin pipenvdemo env PIPENV_CACHE_DIR="$(mktemp -d)" PIP_NO_CACHE_DIR=1 pipenv install --deploy
STEP="offline uncached project is a clean 503"; code=$(curl -s -o /dev/null -w "%{http_code}" -H 'Accept: application/vnd.pypi.simple.v1+json' "$BASE/repository/pypi-group/simple/requests/"); [ "$code" = "503" ] && ok "$STEP ($code)" || bad "$STEP ($code)"
curl -fsS -X PUT "$BASE/api/v1/system/settings" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"offlineMode":false}' >/dev/null

echo; echo "passed=$PASS failed=$FAIL  (work dir $WORK)"
[ "$FAIL" = 0 ]
