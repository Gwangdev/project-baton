#!/usr/bin/env bash
# 네트워크나 Docker 없이 월 전송 할당(infra/host/egress-quota.sh)을 확인한다. 파일 하나가 인터페이스 계수를 대신하고,
# 프록시의 멈춤과 시작 명령은 돌았다는 사실만 기록한다.
#  tests/host/egress-quota-check.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0
GB=1000000000
run() {
  BATON_EGRESS_QUOTA_GB=90 BATON_EGRESS_COUNTER_FILE="$work/tx" BATON_EGRESS_STATE_DIR="$work/state" BATON_EGRESS_MONTH="$1" \
    BATON_EGRESS_STOP="echo stop >> $work/calls" BATON_EGRESS_START="echo start >> $work/calls" \
    infra/host/egress-quota.sh >/dev/null
}
counter() { echo "$1" > "$work/tx"; }
calls() { cat "$work/calls" 2>/dev/null | tr '\n' ' ' | sed 's/ $//'; }
used() { cat "$work/state/$1.bytes"; }
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1 (expected '$3', got '$2')"; failures=$((failures + 1)); fi; }

counter $((500 * GB)); run 2026-11
check "the first run counts nothing already on the counter" "$(used 2026-11)" 0
counter $((550 * GB)); run 2026-11
check "traffic since the last run is added" "$(used 2026-11)" $((50 * GB))
check "under the allowance the proxy keeps running" "$(calls)" ""
counter $((1 * GB)); run 2026-11
check "after a reboot the whole new counter is added" "$(used 2026-11)" $((51 * GB))
counter $((40 * GB)); run 2026-11
check "reaching the allowance stops the proxy" "$(calls)" "stop"
# 재배포(compose up)는 달 중간에 프록시를 다시 띄운다. 멈춤은 멱등이라서 할당을 넘은 동안 매 실행이 프록시를 멈추고,
# 프록시가 떠 있는 시간은 timer 간격 하나를 넘지 않는다.
counter $((41 * GB)); run 2026-11
check "over the allowance every run stops the proxy, so a redeploy cannot reopen it" "$(calls)" "stop stop"
counter $((42 * GB)); run 2026-12
check "a new month starts the proxy again" "$(calls)" "stop stop start"
check "a new month counts from zero" "$(used 2026-12)" $((1 * GB))
counter $((43 * GB)); run 2026-12
check "the proxy is started once" "$(calls)" "stop stop start"

[ "$failures" -eq 0 ] && echo "egress quota check: all passed" || { echo "egress quota check: $failures failed"; exit 1; }
