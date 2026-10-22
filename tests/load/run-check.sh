#!/usr/bin/env bash
# 스택 없이 tests/load/run.sh가 k6를 어떻게 시작하는지 확인한다. PATH의 가짜 `docker`가 인자를 기록하고 run.sh의 몇
# 가지 조회에 답한다. 실행은 둘이다.
#  1. 평범한 실행은 k6에 CSV 출력만 넘기고 tag override는 넘기지 않는다.
#  2. BATON_LOAD_PROMETHEUS=1은 Prometheus 출력을 더하고, URL tag를 빼고, 관측 실행이라고 기록한다.
# run.sh가 Mac에서 bash 3.2로 돌듯이 이 확인도 run.sh의 shebang이 찾는 셸로 돈다.
#
#  tests/load/run-check.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
cat > "$work/bin/docker" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_DOCKER_LOG"
case "$*" in
  *" run "*" k6 version"*) echo "k6 fake" ;;
  info*) echo "server=fake cpus=1 mem=1" ;;
  inspect*) echo "fake-db-image" ;;
  *" ps -q "*) echo "fake-db-id" ;;
  *"psql"*"max(created_at)"*) echo 0 ;;
  *"psql"*"json_agg"*) echo "[]" ;;
esac
exit 0
FAKE
chmod +x "$work/bin/docker"

failures=0
check() { if eval "$2"; then echo "ok    $1"; else echo "FAIL  $1"; failures=$((failures + 1)); fi; }

run() {  # $1 결과 폴더, 그다음 run.sh에 줄 추가 환경
  local out="$1"; shift
  mkdir -p "$out"
  env PATH="$work/bin:$PATH" FAKE_DOCKER_LOG="$out/docker.log" BATON_SAMPLE_SECONDS=1 BATON_LOAD_PLAN=short "$@" \
    tests/load/run.sh "$out" > "$out/run.log" 2>&1
}
k6_call() { grep -E " run --rm .*k6 run " "$1/docker.log" || true; }

run "$work/plain" || true
check "a plain run reaches k6" '[ -n "$(k6_call "$work/plain")" ]'
check "a plain run passes only the CSV output" \
  'k6_call "$work/plain" | grep -q -- "--out csv=/results/requests.csv.gz" && ! k6_call "$work/plain" | grep -q "prometheus"'
check "a plain run keeps every k6 tag" '! k6_call "$work/plain" | grep -q K6_SYSTEM_TAGS'

run "$work/observed" BATON_LOAD_PROMETHEUS=1 || true
check "an observed run adds the Prometheus output" 'k6_call "$work/observed" | grep -q -- "--out experimental-prometheus-rw"'
check "an observed run drops the URL tag" \
  'k6_call "$work/observed" | grep -q "K6_SYSTEM_TAGS=scenario,name,status,method,expected_response,error_code"'
check "an observed run says so in environment.txt" 'grep -q "^observability=prometheus" "$work/observed/environment.txt"'

[ "$failures" -eq 0 ] && echo "run check: all passed" || { echo "run check: $failures failed"; tail -3 "$work/plain/run.log"; exit 1; }
