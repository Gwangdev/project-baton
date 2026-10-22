#!/usr/bin/env bash
# 공개 데모의 매일 초기화(work-orders D7, 결정 12). 호스트에서 baton-demo-reset.timer가 04:00 KST에 돌린다:
#  BATON_DEPLOY_ENV=<deploy env file> BATON_DEMO_BASELINE=<baseline.dump> infra/demo/reset.sh
# 데모 계정은 공개되어 있어서, 방문자가 바꾸거나(관리자 계정으로) 망가뜨린 것을 매일 기준 덤프로 되돌린다. 앱을
# 멈추고, DB를 지우고 기준 덤프로 복원하고, 마이그레이션 단계를 다시 돌리고(복원한 테이블에 baton_app 권한을 준다),
# 앱을 띄운다. 그동안 프록시는 점검 페이지로 답한다. DB를 통째로 지워서 감사 트리거(V21)가 거절하지 않는데, 이것은
# 운영 시스템의 접속기록이 아니라 데모 자체의 데이터다.
set -euo pipefail
cd "$(dirname "$0")/../.."
env_file="${BATON_DEPLOY_ENV:?BATON_DEPLOY_ENV is required}"
baseline="${BATON_DEMO_BASELINE:?BATON_DEMO_BASELINE is required}"
[ -r "$baseline" ] || { echo "reset: cannot read $baseline" >&2; exit 1; }
compose() { docker compose --env-file "$env_file" -f compose.yml -f compose.deploy.yml "$@"; }

compose stop app
compose exec -T db psql -v ON_ERROR_STOP=1 -U baton -d postgres \
  -c 'DROP DATABASE IF EXISTS baton WITH (FORCE)' -c 'CREATE DATABASE baton OWNER baton'
compose exec -T db pg_restore -U baton -d baton --no-owner --no-privileges --exit-on-error < "$baseline"
compose run --rm migrate
compose start app
echo "reset: restored $(basename "$baseline") at $(date -u +%FT%TZ)"
