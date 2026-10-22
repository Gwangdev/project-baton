#!/usr/bin/env bash
# 월 전송 할당(work-orders D7, 결정 4b). baton-egress-quota.timer가 5분마다 돌린다. 속도 상한만으로도 한 달에 약 650
# GB가 나갈 수 있고, AWS의 월 무료 전송을 넘으면 돈이 든다. 호스트는 인터페이스가 이번 달 보낸 양을 세고, 할당을 다
# 쓰면 달이 끝날 때까지 매 실행 프록시를 멈춘다. 새 달의 첫 실행에서는 할당이 멈춘 프록시를 다시 띄운다. 인터페이스
# 계수는 부팅 때 처음부터 다시 세므로, 실행마다 지난 실행 뒤 보낸 양만 더한다(계수가 작아졌으면 재부팅이고 계수 전체가
# 새 트래픽이다). 방문자만이 아니라 호스트가 보내는 모든 바이트를 세서 안전한 쪽으로 틀린다.
set -euo pipefail
quota_gb="${BATON_EGRESS_QUOTA_GB:-90}"
counter_file="${BATON_EGRESS_COUNTER_FILE:-}"
if [ -z "$counter_file" ]; then
  dev="${BATON_EGRESS_DEV:-$(ip -o route show default | awk '{print $5; exit}')}"
  counter_file="/sys/class/net/$dev/statistics/tx_bytes"
fi
state_dir="${BATON_EGRESS_STATE_DIR:-/var/lib/baton/egress}"
month="${BATON_EGRESS_MONTH:-$(date -u +%Y-%m)}"
stop_proxy="${BATON_EGRESS_STOP:-docker stop project-baton-proxy-1}"
start_proxy="${BATON_EGRESS_START:-docker start project-baton-proxy-1}"

mkdir -p "$state_dir"
now=$(cat "$counter_file")
last=$(cat "$state_dir/last-counter" 2>/dev/null || echo "$now")
used=$(cat "$state_dir/$month.bytes" 2>/dev/null || echo 0)
if [ "$now" -ge "$last" ]; then sent=$((now - last)); else sent=$now; fi
used=$((used + sent))
echo "$now" > "$state_dir/last-counter"
echo "$used" > "$state_dir/$month.bytes"

limit=$((quota_gb * 1000 * 1000 * 1000))
stopped_for="$(cat "$state_dir/stopped-for" 2>/dev/null || true)"
if [ "$used" -ge "$limit" ]; then
  # 재배포(compose up)로 프록시가 다시 떠도 이 스크립트는 다음 실행에서야 알 수 있으므로 한 번이 아니라 실행할 때마다
  # 멈춘다. 멈춘 컨테이너는 다시 멈춰도 아무 일이 없어서 프록시가 내려가 있는 동안 드는 비용은 없다.
  sh -c "$stop_proxy" >/dev/null
  if [ "$stopped_for" != "$month" ]; then
    echo "$month" > "$state_dir/stopped-for"
    echo "egress-quota: $month used $used bytes of $limit; proxy stopped until next month"
  fi
elif [ -n "$stopped_for" ] && [ "$stopped_for" != "$month" ]; then
  sh -c "$start_proxy" >/dev/null
  rm -f "$state_dir/stopped-for"
  echo "egress-quota: new month $month; proxy started again"
fi
