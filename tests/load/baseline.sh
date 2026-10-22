#!/usr/bin/env bash
# 모든 부하 실행을 같은 DB에서 시작한다. 같은 코드를 두 번 다시 돌렸을 때 seed와 prime이 달라서 60 RPS에서 252ms와
# 600ms로 벌어졌고, 실행 사이의 차이를 데이터의 차이와 구분할 수 없었다. 이 스크립트는 seed와 prime을 마친 DB를 한 번
# 덤프하고, 실행마다 그 덤프를 새 volume에 복원한다.
#
#   tests/load/baseline.sh dump <dir>                    seed.sh와 run.sh ... prime 뒤; <dir>/baseline.*을 쓴다
#   tests/load/baseline.sh restore <dir> <project>       실행 전마다; <project>는 COMPOSE_PROJECT_NAME과 같아야 한다
#
# <dir>은 저장소 밖이어야 한다. compose.yml과 부하 overlay가 요구하는 변수를 export해 둔다.
#
# 덤프는 tests/recovery/restore-check.sh처럼 API와 프록시를 멈추고 떠서, fingerprint와 덤프가 같은 순간을 담는다.
# 복원은 스택의 DB volume을 지우므로 프로젝트를 두 번(인자와 환경) 밝히게 하고 기본 스택은 거절하며, 복원한 DB는 API가
# 그 위에서 뜨기 전에 덤프의 fingerprint와 맞아야 한다.
set -euo pipefail
cd "$(dirname "$0")/../.."
# COMPOSE_FILE이 있으면 그 파일들을 쓰고(관측 실행은 compose.observe.yml을 더한다), 없으면 부하 스택의 두 파일을 쓴다.
if [ -n "${COMPOSE_FILE:-}" ]; then compose=(docker compose); else compose=(docker compose -f compose.yml -f tests/load/compose.load.yml); fi
psql=("${compose[@]}" exec -T db psql -U baton -d baton -X -q -v ON_ERROR_STOP=1)
log() { echo "$(date -u +%FT%TZ) baseline.sh: $*"; }

dump() {
  local dir
  dir="$(cd "$1" && pwd)"
  log "stop api and proxy for a consistent dump"
  trap 'log "dump did not finish; starting api and proxy again"; "${compose[@]}" start app proxy >/dev/null' EXIT
  "${compose[@]}" stop proxy app >/dev/null
  local started=$SECONDS
  "${psql[@]}" < tests/recovery/fingerprint.sql > "$dir/baseline.fingerprint"
  "${compose[@]}" exec -T db pg_dump -U baton -d baton -Fc -Z 6 > "$dir/baseline.dump"
  (cd "$dir" && shasum -a 256 baseline.dump > baseline.dump.sha256)
  log "dump done in $(( SECONDS - started ))s, $(du -h "$dir/baseline.dump" | cut -f1)"
  trap - EXIT
  "${compose[@]}" start app proxy >/dev/null
}

restore() {
  local dir project volume
  dir="$(cd "$1" && pwd)"
  project="$2"
  volume="${project}_db-data"
  if [ "$project" != "${COMPOSE_PROJECT_NAME:-}" ] || [ "$project" = project-baton ]; then
    echo "baseline.sh: restore recreates the database volume of '$project'; it must equal COMPOSE_PROJECT_NAME and must not be project-baton" >&2
    exit 1
  fi
  (cd "$dir" && shasum -a 256 -c baseline.dump.sha256 >/dev/null) || { echo "baseline.sh: $dir/baseline.dump does not match its checksum" >&2; exit 1; }

  local started=$SECONDS restored_at
  log "stop $project and recreate $volume"
  "${compose[@]}" stop proxy app db >/dev/null
  "${compose[@]}" rm -f db >/dev/null
  docker volume rm "$volume" >/dev/null
  "${compose[@]}" up -d --wait db >/dev/null
  # 첫 "ready"는 initdb의 임시 서버에서 올 수 있으므로, 최종 서버가 쿼리를 받을 때까지 기다리되 끝없이 기다리지는
  # 않는다.
  local ready_limit=${BATON_RESTORE_READY_SECONDS:-120} waited_ready=0
  until "${psql[@]}" -At -c 'select 1' >/dev/null 2>&1; do
    if [ $waited_ready -ge "$ready_limit" ]; then echo "baseline.sh: database did not accept a query within ${ready_limit}s" >&2; exit 1; fi
    sleep 1; waited_ready=$(( waited_ready + 1 ))
  done
  "${compose[@]}" exec -T db pg_restore -U baton -d baton --no-owner --exit-on-error < "$dir/baseline.dump"
  # 복원한 테이블에는 visibility map이 없어서 autovacuum이 닿기 전까지 index-only scan이 heap을 읽고, 실행 전에 그렇게
  # 됐는지는 타이밍에 달렸다(복원한 baseline으로 처음 돌린 실행은 그 1분 뒤에 시작했다). 그래서 analyze와 함께
  # vacuum도 한다.
  "${psql[@]}" -c "vacuum (analyze)"
  "${psql[@]}" < tests/recovery/fingerprint.sql > "$dir/restored.fingerprint"
  if ! diff -q "$dir/baseline.fingerprint" "$dir/restored.fingerprint" >/dev/null; then
    echo "baseline.sh: restored database differs from the dump's fingerprint; api not started" >&2
    exit 1
  fi
  log "restore done in $(( SECONDS - started ))s, fingerprint identical"
  # API가 마이그레이션 checksum을 검증하므로, 덤프 당시의 checksum까지 포함하는 fingerprint 확인 뒤에 한다.
  "${psql[@]}" < tests/load/flyway-checksums.sql

  restored_at=$(date +%s)
  "${compose[@]}" up -d --wait app proxy >/dev/null
  # run.sh는 가장 최근 INGEST 작업으로 찾은 수집 burst에서 150초 뒤에 시작한다. 덤프에는 prime 실행의 burst가 들어
  # 있고, 다시 띄운 API는 기동 때 자기 burst를 넣으므로 run.sh가 그것을 봐야 한다.
  local waited=0
  until [ "$("${psql[@]}" -At -c "select count(*) from jobs where kind = 'INGEST' and created_at > to_timestamp($restored_at)")" -gt 0 ]; do
    if [ $waited -ge 360 ]; then echo "baseline.sh: no ingestion burst within 360s of the api start" >&2; exit 1; fi
    sleep 5; waited=$(( waited + 5 ))
  done
  log "api up and first ingestion burst queued; $(( SECONDS - started ))s in all"
}

case "${1:-}" in
  dump) [ $# -eq 2 ] || { echo "usage: $0 dump <dir>" >&2; exit 2; }; dump "$2" ;;
  restore) [ $# -eq 3 ] || { echo "usage: $0 restore <dir> <project>" >&2; exit 2; }; restore "$2" "$3" ;;
  *) echo "usage: $0 dump <dir> | restore <dir> <project>" >&2; exit 2 ;;
esac
