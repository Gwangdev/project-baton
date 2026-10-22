#!/usr/bin/env bash
# 관측 확인: 관측 overlay(compose.yml + compose.observe.yml)로 일회용 스택을 띄우고 overlay가 약속한 것을 확인한다.
#  1. Grafana 관리자 비밀번호가 없으면 overlay가 기동을 거절한다.
#  2. Prometheus가 설정과 경보 규칙을 받아들이고(promtool) 규칙을 불러왔다.
#  3. 모든 scrape 대상(app, postgres_exporter, Prometheus)이 up이다.
#  4. 요청을 몇 번 보낸 뒤 API의 서버 쪽 p95를 조회할 수 있다.
#  5. Grafana가 익명 요청을 거절하고, 생성기와 맞는 대시보드 셋을 준비했다.
#  6. 모니터링 DB 계정이 통계 view는 읽지만 업무 테이블은 읽지 못한다.
#  7. 자원 한도가 적용됐다.
#  8. Prometheus는 포트를 열지 않고, Grafana는 127.0.0.1에만 열며, Grafana의 외부 호출(사용 보고, 업데이트 확인,
#     plugin 미리 설치)은 꺼져 있다.
#
#  tests/observe/observe-check.sh
#
# 이미지는 이미 빌드한 것(docker compose build나 기동 확인)을 쓰고, overlay 자체의 이미지는 digest로 고정되어 있다.
# 확인이 만든 것(프로젝트 baton-observecheck-<pid>, 비밀번호, 인증서, volume)은 끝날 때 모두 지운다.
set -euo pipefail
cd "$(dirname "$0")/../.."
project="baton-observecheck-$$"
proxy_port="${BATON_PROXY_PORT:-18444}"
grafana_port="${BATON_GRAFANA_PORT:-13000}"
work="$(mktemp -d)"
compose=(docker compose -p "$project" -f compose.yml -f compose.observe.yml)
cleanup() {
  "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

. tests/lib/throwaway-stack.sh
scrub_stack_env
throwaway_cert "$work"
BATON_DB_PASSWORD="$(openssl rand -hex 16)"
BATON_MONITOR_DB_PASSWORD="$(openssl rand -hex 16)"
export BATON_DB_PASSWORD BATON_MONITOR_DB_PASSWORD BATON_TLS_DIR="$work" BATON_PROXY_PORT="$proxy_port" BATON_GRAFANA_PORT="$grafana_port"

failures=0
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; failures=$((failures + 1)); fi; }

check "the dashboard files match infra/observe/grafana/build-dashboards.py" \
  'python3 infra/observe/grafana/build-dashboards.py --check'

check "the overlay refuses to start without the Grafana administrator password" \
  '! "${compose[@]}" config >/dev/null 2>&1'

BATON_GRAFANA_ADMIN_PASSWORD="$(openssl rand -hex 16)"
export BATON_GRAFANA_ADMIN_PASSWORD
if ! "${compose[@]}" up -d --wait --wait-timeout 300 >"$work/up.log" 2>&1; then
  echo "FAIL  the stack with the overlay did not become healthy"; tail -8 "$work/up.log"
  "${compose[@]}" ps -a
  exit 1
fi

prom() { "${compose[@]}" exec -T prometheus wget -qO- "http://127.0.0.1:9090$1"; }
promql() { prom "/api/v1/query?query=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$1")"; }

check "promtool accepts the configuration and the alert rules" \
  '"${compose[@]}" exec -T prometheus promtool check config /etc/prometheus/prometheus.yml >/dev/null 2>&1'
check "Prometheus has loaded the alert rules" \
  'prom /api/v1/rules | python3 -c "import json,sys; g=json.load(sys.stdin)[\"data\"][\"groups\"]; sys.exit(0 if sum(len(x[\"rules\"]) for x in g) >= 5 else 1)"'

targets_up() {
  prom /api/v1/targets | python3 -c '
import json, sys
active = json.load(sys.stdin)["data"]["activeTargets"]
jobs = {t["labels"]["job"]: t["health"] for t in active}
want = {"app", "postgres", "prometheus"}
sys.exit(0 if want <= set(jobs) and all(jobs[j] == "up" for j in want) else 1)'
}
for _ in $(seq 1 30); do targets_up && break; sleep 3; done
check "every scrape target is up (app, postgres, prometheus)" 'targets_up'

# rate()는 counter의 첫 값을 무시하므로, 시간 계열이 그것이 잴 요청보다 한 scrape 먼저 있어야 한다.
curl -sk -o /dev/null "https://127.0.0.1:$proxy_port/api/v1/auth/csrf"
sleep 15
for _ in $(seq 1 40); do curl -sk -o /dev/null "https://127.0.0.1:$proxy_port/api/v1/auth/csrf"; done
p95_present() {
  promql 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"/api/.*"}[2m])))' \
    | python3 -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; sys.exit(0 if r and r[0]["value"][1] not in ("NaN", "") else 1)'
}
for _ in $(seq 1 20); do p95_present && break; sleep 3; done
check "a server-side p95 of the API can be queried" 'p95_present'

grafana="http://127.0.0.1:$grafana_port"
check "Grafana refuses an anonymous request" \
  '[ "$(curl -s -o /dev/null -w "%{http_code}" "$grafana/api/search")" = 401 ]'
check "Grafana has provisioned the three dashboards" \
  'curl -s -u "admin:$BATON_GRAFANA_ADMIN_PASSWORD" "$grafana/api/search?type=dash-db" | python3 -c "import json,sys; t={d[\"title\"] for d in json.load(sys.stdin)}; sys.exit(0 if {\"Baton 서비스\",\"Baton 자원\",\"Baton 부하 시험\"} <= t else 1)"'

monitor_sql() {
  # 거절된 쿼리는 0이 아닌 코드로 끝나는데, 확인하는 것은 상태가 아니라 출력이다.
  "${compose[@]}" exec -T -e PGPASSWORD="$BATON_MONITOR_DB_PASSWORD" db psql -h 127.0.0.1 -U baton_monitor -d baton -X -At -c "$1" 2>&1 || true
}
check "the monitoring account reads pg_stat_activity" 'monitor_sql "select count(*) >= 0 from pg_stat_activity" | grep -qx t'
check "the monitoring account cannot read a business table" 'monitor_sql "select count(*) from users" | grep -q "permission denied"'

limit() { docker inspect -f '{{.HostConfig.Memory}}' "$("${compose[@]}" ps -q "$1")"; }
check "memory limits are applied (prometheus 1GiB, grafana 256MiB, exporter 64MiB)" \
  '[ "$(limit prometheus)" = 1073741824 ] && [ "$(limit grafana)" = 268435456 ] && [ "$(limit postgres-exporter)" = 67108864 ]'

published() { docker port "$("${compose[@]}" ps -q "$1")" 2>/dev/null || true; }
# 내부 네트워크에만 붙은 컨테이너는 포트를 설정해도 열리지 않아서, 실수로 더한 binding은 Prometheus가 다른 네트워크에
# 붙은 뒤에야 드러난다. 그래서 살아 있는 binding만이 아니라 설정된 binding을 본다.
bindings() { docker inspect -f '{{json .HostConfig.PortBindings}}' "$("${compose[@]}" ps -q "$1")"; }
check "Prometheus publishes no port" '[ -z "$(published prometheus)" ] && [[ "$(bindings prometheus)" =~ ^(\{\}|null)$ ]]'
check "Grafana publishes only on 127.0.0.1" \
  '[ -n "$(published grafana)" ] && ! published grafana | grep -v -q "^3000/tcp -> 127\.0\.0\.1:"'
check "Grafana's usage reports, update checks and plugin preinstall are off" \
  'curl -s -u "admin:$BATON_GRAFANA_ADMIN_PASSWORD" "$grafana/api/admin/settings" | python3 -c "
import json, sys
s = json.load(sys.stdin)
a, pl = s.get(\"analytics\", {}), s.get(\"plugins\", {})
ok = a.get(\"reporting_enabled\") == \"false\" and a.get(\"check_for_updates\") == \"false\" \
    and a.get(\"check_for_plugin_updates\") == \"false\" and pl.get(\"preinstall_disabled\") == \"true\"
sys.exit(0 if ok else 1)"'

[ "$failures" -eq 0 ] && echo "observe check: all passed" || { echo "observe check: $failures failed"; exit 1; }
