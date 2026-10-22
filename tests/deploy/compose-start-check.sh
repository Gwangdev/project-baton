#!/usr/bin/env bash
# 기동 확인: 기본 Compose 스택(README 배포 절처럼 compose.yml만)을 AI, 부트스트랩, 그 밖의 선택 변수를 하나도 주지
# 않고 띄워서, 모든 서비스가 healthy가 되고 프록시가 앱을 내주는지 본다. 배포 전에 돌린다.
#
#  tests/deploy/compose-start-check.sh
#
# 단위와 통합 시험은 Gradle에서 앱을 띄우는데, 거기서는 아무도 설정하지 않은 변수가 없는 것이다. Compose는 같은 변수를
# 빈 문자열로 넘기고(compose.yml은 선택 변수를 모두 ${NAME:-}로 나열한다) Spring은 빈 값을 설정된 값으로 봐서, 그
# 차이에 기대는 기동은 모든 시험을 통과하고도 Compose 스택에서는 뜨지 않을 수 있다. 배포와 같은 방식으로 앱을 띄우는
# 것은 이 확인뿐이다.
#
# 모두 일회용이다: 자기 프로젝트(baton-startcheck-<pid>), 무작위 DB 비밀번호, 하루짜리 자체 서명 인증서, 자기 volume.
# 끝날 때 모두 지운다. 이미지는 작업 트리로 빌드한다. 프록시는 BATON_PROXY_PORT(여기서는 기본 18443이라 8443에서 돌고
# 있는 스택을 건드리지 않는다)로 연다.
set -euo pipefail
cd "$(dirname "$0")/../.."
project="baton-startcheck-$$"
port="${BATON_PROXY_PORT:-18443}"
work="$(mktemp -d)"
cleanup() {
  docker compose -p "$project" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

. tests/lib/throwaway-stack.sh
throwaway_cert "$work"
# compose.yml이 요구하는 것만 설정한다.
scrub_stack_env
BATON_DB_PASSWORD="$(openssl rand -hex 16)"
export BATON_DB_PASSWORD BATON_TLS_DIR="$work" BATON_PROXY_PORT="$port"

started=$SECONDS
if ! docker compose -p "$project" up -d --build --wait --wait-timeout 300 >"$work/up.log" 2>&1; then
  echo "start check: the stack did not become healthy" >&2
  tail -5 "$work/up.log" >&2
  # stack trace 전체가 아니라 원인만.
  docker compose -p "$project" logs --no-color --tail 300 app 2>/dev/null | grep -E "Caused by|APPLICATION FAILED|ERROR" | tail -6 >&2 || true
  exit 1
fi

status=$(curl -sk -o /dev/null -w '%{http_code}' "https://127.0.0.1:$port/")
if [ "$status" != 200 ]; then
  echo "start check: the proxy answered $status for / instead of 200" >&2
  exit 1
fi
echo "start check: db, app and proxy healthy and the proxy serves the app ($(( SECONDS - started ))s)"
