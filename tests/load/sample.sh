#!/usr/bin/env bash
# 부하 시험이 도는 동안 BATON_SAMPLE_SECONDS(기본 30)마다 실행 중인 스택을 표본으로 $1 아래 CSV 파일에 남긴다. 종류별
# 작업 적체와 큐 대기 시간, 활성 문장(DB에서), 빈 AI slot과 pool별 사용 중인 연결(앱 컨테이너 안에서 닿는 내부 metric
# endpoint에서), 컨테이너 CPU와 메모리다. $1/stop 파일이 생기면 멈춘다.
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$1"
interval="${BATON_SAMPLE_SECONDS:-30}"
mkdir -p "$out"
echo "time,kind,status,count,oldest_age_s" > "$out/jobs.csv"
echo "time,pool,available" > "$out/slots.csv"
echo "time,pool,metric,value" > "$out/connections.csv"
echo "time,container,cpu_percent,mem_usage" > "$out/containers.csv"
echo "time,active,waiting_on_lock,longest_s,longest_query" > "$out/activity.csv"
while [ ! -e "$out/stop" ]; do
  now=$(date -u +%FT%TZ)
  docker compose exec -T db psql -U baton -d baton -X -At -F, -c "
      select kind, status, count(*), coalesce(round(extract(epoch from now()-min(created_at)))::int, 0)
      from jobs where status in ('PENDING','RUNNING','RETRY_WAIT') group by kind, status order by kind, status" \
    | sed "s/^/$now,/" >> "$out/jobs.csv" || true
  metrics=$(docker compose exec -T app curl -fsS http://127.0.0.1:8081/actuator/prometheus 2>/dev/null || true)
  printf '%s\n' "$metrics" | awk -v t="$now" -F'[{}" ]+' '/^baton_job_slots_available/ {print t "," $3 "," $NF}' >> "$out/slots.csv" || true
  # API와 worker pool을 따로(config/DatabasePools): AI 작업이 API 연결을 기다렸거나 가져간 적이 있는지.
  printf '%s\n' "$metrics" \
    | sed -nE "s/^hikaricp_connections_(active|pending)\{.*pool=\"([^\"]+)\".*\} ([0-9.eE+-]+)$/$now,\2,\1,\3/p" >> "$out/connections.csv" || true
  # 문장 글만 남긴다(파라미터는 bind되어서 값은 나오지 않는다). 지연 정점 뒤의 쿼리를 밝히는 데 쓴다.
  docker compose exec -T db psql -U baton -d baton -X -At -F, -c "
      select count(*), count(*) filter (where wait_event_type = 'Lock'),
             coalesce(round(max(extract(epoch from now()-query_start))::numeric, 2), 0),
             coalesce(replace(left((array_agg(query order by query_start))[1], 120), ',', ' '), '')
      from pg_stat_activity where state = 'active' and backend_type = 'client backend' and pid <> pg_backend_pid()" \
    | sed "s/^/$now,/" >> "$out/activity.csv" || true
  docker stats --no-stream --format "{{.Name}},{{.CPUPerc}},{{.MemUsage}}" \
    | grep -E "(-db-|-app-|-proxy-|-k6-)" | sed "s/^/$now,/" >> "$out/containers.csv" || true
  sleep "$interval"
done
