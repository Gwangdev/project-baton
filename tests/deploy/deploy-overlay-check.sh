#!/usr/bin/env bash
# 공개 데모 배포의 로컬 확인(work-orders D7): compose.yml + compose.deploy.yml을 호스트가 돌리는 그대로 이 기계에서
# 작업 트리로 빌드한 이미지로 띄우고 확인한다.
#  - 일회용 마이그레이션이 돌았고 앱이 baton_app으로 DB에 접속한다.
#  - 프록시가 HTTPS를 내주고, 글을 압축하고, 해시가 붙은 asset은 immutable, 페이지는 no-cache로 표시하고, 빌드가 공개 데모 안내를 보여 준다.
#  - 기준 덤프의 데모 계정이 로그인하고, 매일 초기화가 데이터를 되돌리는 동안 방문자는 점검 페이지를 받고, 그 뒤 다시 로그인된다.
#  - 로그인 뒤 서비스마다 메모리를 얼마나 쓰는지(결정 9: 호스트 메모리는 2 GiB).
#
#  tests/deploy/deploy-overlay-check.sh            # 일회용 기준 덤프를 만든다(몇 분)
#  BATON_DEMO_BASELINE=<dump> BATON_DEMO_PASSWORD=<그 비밀번호> tests/deploy/deploy-overlay-check.sh
#
# 모두 일회용이다: 자기 프로젝트, 이미지 이름(baton-deploycheck/*), 비밀번호, volume, 포트를 끝날 때 모두 지워서, 공용
# project-baton/*:local 이미지와 보존 중인 스택은 건드리지 않는다. 사이트 주소는 localhost라 Caddy가 자기 인증서를
# 내고, Let's Encrypt는 실제 호스트에서만 쓴다. BATON_GRADLE_RO_CACHE(기본 ~/.gradle/caches)가 있으면 Maven Central
# 없이 API 이미지를 빌드한다.
set -euo pipefail
cd "$(dirname "$0")/../.."
project="baton-deploycheck-$$"
prefix="baton-deploycheck"
tag="$$"
http_port="${BATON_CHECK_HTTP_PORT:-18080}"
https_port="${BATON_CHECK_HTTPS_PORT:-18443}"
work="$(mktemp -d)"
failures=0
pass() { echo "ok   $1"; }
fail() { echo "FAIL $1"; failures=$((failures + 1)); }

. tests/lib/throwaway-stack.sh
scrub_stack_env
export COMPOSE_PROJECT_NAME="$project"
env_file="$work/deploy.env"
compose() { docker compose --env-file "$env_file" -f compose.yml -f compose.deploy.yml "$@"; }
cleanup() {
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  docker image rm "$prefix/app:$tag" "$prefix/proxy:$tag" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

baseline="${BATON_DEMO_BASELINE:-}"
demo_password="${BATON_DEMO_PASSWORD:-}"
if [ -z "$baseline" ]; then
  demo_password="$(openssl rand -hex 8)"
  baseline="$work/demo-baseline.dump"
  BATON_DEMO_PASSWORD="$demo_password" infra/demo/make-baseline.sh "$baseline" >/dev/null
fi

docker build -q --build-context "gradle-ro-cache=${BATON_GRADLE_RO_CACHE:-$HOME/.gradle/caches}" -t "$prefix/app:$tag" backend >/dev/null
docker build -q -f infra/proxy/Dockerfile --build-arg VITE_PUBLIC_DEMO=true -t "$prefix/proxy:$tag" . >/dev/null

cat > "$env_file" <<ENV
BATON_IMAGE_PREFIX=$prefix
BATON_IMAGE_TAG=$tag
BATON_DB_PASSWORD=$(openssl rand -hex 16)
BATON_DB_APP_PASSWORD=$(openssl rand -hex 16)
BATON_DB_INSPECT_PASSWORD=$(openssl rand -hex 16)
BATON_SITE_ADDRESS=localhost
BATON_HTTP_PORT=$http_port
BATON_HTTPS_PORT=$https_port
ENV
chmod 600 "$env_file"

if ! compose up -d --no-build --wait --wait-timeout 300 >"$work/up.log" 2>&1; then
  echo "deploy check: the stack did not become healthy" >&2; tail -5 "$work/up.log" >&2
  compose logs --no-color --tail 200 migrate app 2>/dev/null | grep -E "Caused by|ERROR|FATAL" | tail -6 >&2 || true
  exit 1
fi
[ "$(docker inspect -f '{{.State.ExitCode}}' "$project-migrate-1")" = "0" ] && pass "the migration step ran and exited 0" || fail "the migration step ran and exited 0"

# 호스트가 매일 하는 것처럼 데모 데이터를 넣는다.
BATON_DEPLOY_ENV="$env_file" BATON_DEMO_BASELINE="$baseline" infra/demo/reset.sh >/dev/null
compose up -d --no-build --wait --wait-timeout 300 app proxy >/dev/null 2>&1

users=$(compose exec -T db psql -At -U baton -d postgres -c "select string_agg(distinct usename, ',' order by usename) from pg_stat_activity where datname = 'baton' and backend_type = 'client backend' and application_name <> 'psql'")
[ "$users" = "baton_app" ] && pass "the application signs in to the database as baton_app only" || fail "the application signs in to the database as baton_app only (got: $users)"

site="https://localhost:$https_port"
headers() { curl -ksS -o /dev/null -D - -H 'Accept-Encoding: zstd, gzip' "$site$1" | tr -d '\r'; }
page=$(headers /)
grep -qi '^cache-control: no-cache' <<<"$page" && pass "the page is revalidated (no-cache)" || fail "the page is revalidated (no-cache)"
grep -qi '^strict-transport-security:' <<<"$page" && pass "HSTS is sent" || fail "HSTS is sent"
asset=$(curl -ksS "$site/" | grep -oE '/assets/[^"]+\.js' | head -1)
asset_headers=$(headers "$asset")
grep -qi '^cache-control: public, max-age=31536000, immutable' <<<"$asset_headers" && pass "hashed assets are cached as immutable" || fail "hashed assets are cached as immutable"
grep -qiE '^content-encoding: (zstd|gzip)' <<<"$asset_headers" && pass "scripts are compressed" || fail "scripts are compressed"
# 배포 전에 연 탭은 새 빌드에 없는 chunk를 요청할 수 있다. 그때는 그 chunk 이름으로 1년 동안 immutable로 표시된
# 페이지가 아니라 404를 받아야 한다.
missing_status=$(curl -ksS -o /dev/null -w '%{http_code}' "$site/assets/no-such-chunk-0000.js")
missing_headers=$(headers /assets/no-such-chunk-0000.js)
[ "$missing_status" = "404" ] && ! grep -qi 'immutable' <<<"$missing_headers" \
  && pass "a missing asset is a 404 that is not cached as immutable" \
  || fail "a missing asset is a 404 that is not cached as immutable (got $missing_status)"
# 로그인 페이지는 첫 페이지가 나중에 불러오는 chunk라서, 내주는 파일을 프록시가 두는 곳에서 찾는다.
compose exec -T proxy sh -c "grep -l '합성 데이터로 만든 공개 데모' /srv/assets/*.js" >/dev/null 2>&1 \
  && pass "the build shows the public demo notice" || fail "the build shows the public demo notice"

sign_in() {
  local jar="$work/cookies-$1" csrf header token
  rm -f "$jar"
  csrf=$(curl -ksS -c "$jar" -b "$jar" "$site/api/v1/auth/csrf")
  header=$(sed -E 's/.*"headerName":"([^"]+)".*/\1/' <<<"$csrf")
  token=$(sed -E 's/.*"token":"([^"]+)".*/\1/' <<<"$csrf")
  curl -ksS -o /dev/null -w '%{http_code}' -c "$jar" -b "$jar" -H "$header: $token" -H 'Content-Type: application/json' \
    -d "{\"employeeNo\":\"$1\",\"password\":\"$demo_password\"}" "$site/api/v1/auth/sign-in"
}
for account in A-10421 A-10188 A-10733 A-10002; do
  [ "$(sign_in "$account")" = "200" ] && pass "demo account $account signs in" || fail "demo account $account signs in"
done

compose stats --no-stream --format '{{.Name}} {{.MemUsage}}' 2>/dev/null | sed "s/^$project-//" > "$work/memory.txt" || true
echo "memory after sign-in:"; sed 's/^/     /' "$work/memory.txt"

# 방문자의 변경, 그다음 초기화. 그동안 프록시를 계속 확인한다.
compose exec -T db psql -q -U baton -d baton -c "update projects set name = 'changed by a visitor' where code = 'PJT-2026-014'"
( for _ in $(seq 1 120); do curl -ksS -o /dev/null -w '%{http_code}\n' "$site/api/v1/auth/csrf" 2>/dev/null; sleep 1; done ) > "$work/during-reset.txt" &
poller=$!
BATON_DEPLOY_ENV="$env_file" BATON_DEMO_BASELINE="$baseline" infra/demo/reset.sh >/dev/null
compose up -d --no-build --wait --wait-timeout 300 app proxy >/dev/null 2>&1
kill "$poller" 2>/dev/null || true; wait "$poller" 2>/dev/null || true
name=$(compose exec -T db psql -At -U baton -d baton -c "select name from projects where code = 'PJT-2026-014'")
[ "$name" != "changed by a visitor" ] && pass "the reset puts the visitor's change back" || fail "the reset puts the visitor's change back"
grep -q '^503$' "$work/during-reset.txt" && pass "visitors get the maintenance page during the reset" || fail "visitors get the maintenance page during the reset ($(sort "$work/during-reset.txt" | uniq -c | tr '\n' ' '))"
[ "$(sign_in A-10188)" = "200" ] && pass "signing in works again after the reset" || fail "signing in works again after the reset"

[ "$failures" -eq 0 ] && echo "deploy check: all passed" || { echo "deploy check: $failures failed"; exit 1; }
