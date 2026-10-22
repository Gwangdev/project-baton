#!/usr/bin/env bash
# compose 스택에 부하 시험을 돌리고 performance.md가 보고하는 것을 모두 모은다.
#
#  tests/load/run.sh <results-dir> [prime]
#
# <results-dir>은 저장소 밖이어야 한다. "prime"을 주면 prime.js가 먼저 시험이 읽는 인수인계를 만든다. 스택이 떠 있고
# seed가 들어가 있어야 하며(tests/load/seed.sh), compose.yml이 요구하는 변수를 export해 둔다.
#
# 요약 출력과 함께 모든 요청을 requests.csv.gz에 쓰고 스택을 5초마다 표본으로 남겨서, 나중에 지연과 작업 활동을 나란히
# 놓을 수 있다. 수집 일정은 앱 기동부터 5분마다 몰아서 작업을 넣고 패키지 producer는 k6 시작부터 5분마다 재생성하므로,
# 둘이 다른 구간에 들어가도록 수집 burst 150초 뒤에 실행을 시작한다(performance.md가 이렇게 떼어 놓은 soak 정점을
# 다룬다).
#
# 그 밖에: BATON_LOAD_PLAN(기본 full, 또는 short; baton.js 참고), statements/ 아래 단계별 문장
# 비용(tests/load/statements.sh), counts.csv의 시나리오·역할·operation별 요청 수(tests/load/counts.py), 실행 전후
# 호스트 load average와 실행 중인 모든 컨테이너 사용량을 담은 environment.txt. 같은 코드의 실행이 달라지는 이유 하나가
# 호스트의 다른 작업이라서 남긴다.
set -euo pipefail
cd "$(dirname "$0")/../.."
results="$(cd "$1" && pwd)"
export BATON_LOAD_RESULTS="$results"
export BATON_LOAD_PLAN="${BATON_LOAD_PLAN:-full}"
# COMPOSE_FILE이 있으면 그 파일들을 쓰고(관측 실행은 compose.observe.yml을 더한다), 없으면 부하 스택의 두 파일을 쓴다.
if [ -n "${COMPOSE_FILE:-}" ]; then compose=(docker compose); else compose=(docker compose -f compose.yml -f tests/load/compose.load.yml); fi
# 단계 끝(초)은 baton.js가 단계를 읽는 파일인 plans.json에서 가져온다.
read -r -a boundary_seconds <<< "$(python3 - tests/load/plans.json "$BATON_LOAD_PLAN" "${BATON_LOAD_SCALE:-1}" <<'PY' || true
import json, sys
plan = json.load(open(sys.argv[1])).get(sys.argv[2])
if plan: print(" ".join(f"{p['name']}:{int(p['until'] * 60 * float(sys.argv[3]))}" for p in plan))
PY
)"
[ "${#boundary_seconds[@]}" -gt 0 ] || { echo "run.sh: BATON_LOAD_PLAN must name a plan in tests/load/plans.json" >&2; exit 2; }
host_conditions() {
  echo "host_loadavg_$1=$(sysctl -n vm.loadavg 2>/dev/null || cut -d' ' -f1-3 /proc/loadavg)"
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' | sed "s/^/docker_stats_$1: /"
}

{
  echo "git=$(git rev-parse --short HEAD)$(git diff --quiet || echo '+dirty')"
  echo "k6_image=$(grep -o 'grafana/k6:[^ ]*' tests/load/compose.load.yml)"
  echo "k6_version=$("${compose[@]}" run --rm --no-deps k6 version 2>/dev/null | head -1)"
  echo "host_cpu=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m) cores=$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
  echo "host_mem_bytes=$(sysctl -n hw.memsize 2>/dev/null || true)"
  echo "docker=$(docker info --format 'server={{.ServerVersion}} cpus={{.NCPU}} mem={{.MemTotal}}')"
  echo "db_image=$(docker inspect --format '{{.Config.Image}}' "$(docker compose ps -q db)")"
  echo "load_scale=${BATON_LOAD_SCALE:-1} seed=${BATON_LOAD_SEED:-20260928}"
  echo "plan=$BATON_LOAD_PLAN phases=${boundary_seconds[*]}"
} > "$results/environment.txt"

if [ "${2:-}" = "prime" ]; then
  "${compose[@]}" run --rm k6 run --quiet /scripts/prime.js > "$results/prime.log" 2>&1
fi

last_burst=$(docker compose exec -T db psql -U baton -d baton -X -At -c \
  "select coalesce(extract(epoch from max(created_at))::bigint, 0) from jobs where kind='INGEST'")
if [ "$last_burst" -gt 0 ]; then
  start_at=$(( last_burst + 150 ))
  while [ "$start_at" -le "$(date +%s)" ]; do start_at=$(( start_at + 300 )); done
  echo "run.sh: waiting $(( start_at - $(date +%s) ))s to start 150s after an ingestion burst"
else
  start_at=$(( $(date +%s) + 30 ))
  echo "run.sh: no ingestion burst yet; starting without phase alignment" | tee -a "$results/environment.txt"
fi
# k6의 단계와 statement snapshot이 모두 start_at에 시작한다. k6를 30초 일찍 띄워서 컨테이너 기동과 setup의 로그인이 그
# 전에 끝나게 한다(setup은 그 뒤 start_at까지 기다린다; traffic.js startClock).
export BATON_LOAD_START_AT="$start_at"

# baton.js가 SharedArray로 읽는 검토용 초안: 큰 프로젝트마다 2,500개, 타임라인 순서대로 최신순, 프로젝트를 번갈아.
# 목록이 실행이 시작하는 데이터와 맞도록 prime 뒤에 뽑는다. seed의 연동은 모두 공유 연동이라 이 초안은 모두 프로젝트
# 관리자가 검토할 수 있다.
docker compose exec -T db psql -U baton -d baton -X -At -c "
    select coalesce(json_agg(json_build_array(id, version, project_id) order by rank, project_id), '[]')
    from (select id, version, project_id, row_number() over (partition by project_id order by occurred_on desc, id desc) as rank
          from work_records where project_id between 1 and 10 and trust_status = 'AUTO_DRAFT') drafts
    where rank <= 2500" > "$results/drafts.json"
echo "review_drafts=$(grep -o '\], *\[' "$results/drafts.json" | wc -l | tr -d ' ')+1" >> "$results/environment.txt"

# pg_stat_statements는 업무 DB가 아니라 postgres DB에 두어서 Flyway의 스키마는 그대로다. DB를 부하 overlay의 preload
# 설정으로 띄우지 않았으면 이 쿼리가 실행 전에 여기서 실패한다.
docker compose exec -T db psql -U baton -d postgres -X -q -v ON_ERROR_STOP=1 \
  -c 'create extension if not exists pg_stat_statements' -c 'select count(*) from pg_stat_statements' >/dev/null

rm -f "$results/samples/stop"
BATON_SAMPLE_SECONDS="${BATON_SAMPLE_SECONDS:-5}" tests/load/sample.sh "$results/samples" &
sampler=$!
host_conditions before >> "$results/environment.txt"
launch=$(( start_at - 30 ))
[ "$launch" -gt "$(date +%s)" ] && sleep $(( launch - $(date +%s) ))
started=$(date -u +%FT%TZ)
echo "started=$started phases_start_at=$(date -u -r "$start_at" +%FT%TZ 2>/dev/null || date -u -d "@$start_at" +%FT%TZ)" >> "$results/environment.txt"
mkdir -p "$results/statements"
rm -f "$results/statements/stop"
tests/load/statements.sh "$results/statements" "$start_at" "${boundary_seconds[@]}" &
statements=$!
set +e
# BATON_LOAD_PROMETHEUS=1이면 k6의 metric을 관측 overlay의 Prometheus로도 보낸다(COMPOSE_FILE에 compose.observe.yml과
# tests/load/compose.load-observe.yml을 더해야 한다). 기록 id마다 Prometheus 계열이 따로 생기고 counts.py는 scenario와
# name tag만 읽어서, 이때는 모든 출력에서 URL tag를 뺀다. 관측 스택이 CPU를 조금 쓰므로 이런 실행은 그렇다고 기록한다.
outputs=(--out csv=/results/requests.csv.gz)
k6_env=()
if [ "${BATON_LOAD_PROMETHEUS:-0}" = 1 ]; then
  outputs+=(--out experimental-prometheus-rw)
  k6_env=(-e K6_SYSTEM_TAGS=scenario,name,status,method,expected_response,error_code)
  echo "observability=prometheus (k6 metrics streamed; observability stack running during the run)" >> "$results/environment.txt"
fi
# ${a[@]+"${a[@]}"}: bash 3.2(macOS)는 set -u에서 빈 배열을 unbound로 다룬다.
"${compose[@]}" run --rm ${k6_env[@]+"${k6_env[@]}"} k6 run --quiet "${outputs[@]}" /scripts/baton.js > "$results/k6.log" 2>&1
k6_exit=$?
set -e
echo "finished=$(date -u +%FT%TZ) k6_exit=$k6_exit" >> "$results/environment.txt"
touch "$results/samples/stop" "$results/statements/stop"
wait "$statements" || echo "run.sh: statements.sh ended with an error" >> "$results/environment.txt"
wait "$sampler" || true
host_conditions after >> "$results/environment.txt"
tests/load/counts.py "$results" || echo "run.sh: counts.py failed" >> "$results/environment.txt"

docker compose exec -T db psql -U baton -d baton -X -v started="$started" < tests/load/verify.sql > "$results/verify.txt" 2>&1
echo "run.sh: k6 exit $k6_exit; results in $results"
exit "$k6_exit"
