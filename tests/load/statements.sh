#!/usr/bin/env bash
# 부하 실행의 단계별 DB 문장 비용. 이전 활동 표본은 어느 순간 가장 느린 문장을 밝힐 뿐 CPU를 쓴 문장(빈도 곱하기
# 비용)은 밝히지 못해서, 이 스크립트는 부하 overlay만 미리 불러오는 pg_stat_statements를 읽는다.
#
#  tests/load/statements.sh <out-dir> <start-epoch> <phase>:<seconds> ...
#
# <start-epoch>(run.sh가 정한, k6 단계가 시작하는 초)까지 기다렸다가 계수를 초기화하고, 경계(그 시작부터의 초)마다
# 업무 DB의 문장을 총 실행 시간이 큰 순서로 <out-dir>/<phase>.csv에 쓰고 다시 초기화해서 파일마다 단계 하나를 담는다.
# <out-dir>/stop이 먼저 생기면 진행 중인 단계는 <phase>-partial.csv로 가고 스크립트가 끝난다. run.sh가 이 스크립트를
# 시작하기 전에 postgres DB에 extension을 준비한다.
#
# 문장 글은 정규화한 형태(상수가 $n이 된다)라서 파라미터 값은 쓰지 않는다.
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$1"
start_at="$2"
shift 2
mkdir -p "$out"
psql=(docker compose exec -T db psql -U baton -d postgres -X -q -v ON_ERROR_STOP=1)

snapshot() {
  "${psql[@]}" -c "copy (
      select s.queryid, s.toplevel, s.calls, round(s.total_exec_time::numeric, 3) as total_exec_ms,
             round(s.mean_exec_time::numeric, 3) as mean_exec_ms, round(s.max_exec_time::numeric, 3) as max_exec_ms,
             s.rows, s.shared_blks_hit, s.shared_blks_read, round(s.shared_blk_read_time::numeric, 3) as shared_blk_read_ms,
             s.temp_blks_written, regexp_replace(s.query, '\s+', ' ', 'g') as query
      from pg_stat_statements s join pg_database d on d.oid = s.dbid
      where d.datname = 'baton'
      order by s.total_exec_time desc) to stdout with (format csv, header)" > "$1"
  "${psql[@]}" -At -c 'select pg_stat_statements_reset()' >/dev/null
}

while [ "$(date +%s)" -lt "$start_at" ] && [ ! -e "$out/stop" ]; do sleep 1; done
started=$start_at
"${psql[@]}" -At -c 'select pg_stat_statements_reset()' >/dev/null
echo "phase,boundary_s,written_at" > "$out/boundaries.csv"
echo "start,$start_at,$(date -u +%FT%TZ)" >> "$out/boundaries.csv"
for boundary in "$@"; do
  phase="${boundary%%:*}"
  at=$(( started + ${boundary#*:} ))
  while [ "$(date +%s)" -lt "$at" ] && [ ! -e "$out/stop" ]; do sleep 1; done
  # k6의 단계가 start_at에 시작하므로 실행은 마지막 경계에서 끝나고 stop이 같은 초에 올 수 있다. 경계 전에 온 stop만
  # 단계를 자른다.
  if [ -e "$out/stop" ] && [ "$(date +%s)" -lt "$at" ]; then
    snapshot "$out/$phase-partial.csv"
    echo "$phase-partial,$(( $(date +%s) - started )),$(date -u +%FT%TZ)" >> "$out/boundaries.csv"
    exit 0
  fi
  snapshot "$out/$phase.csv"
  echo "$phase,$(( $(date +%s) - started )),$(date -u +%FT%TZ)" >> "$out/boundaries.csv"
done
