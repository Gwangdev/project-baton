#!/usr/bin/env bash
# 호스트의 나가는 트래픽을 제한한다(work-orders D7, 결정 4a). AWS 밖으로 나가는 데이터는 GB 단위로 과금되고 데모는
# 공개되어 있어서, 나갈 수 있는 속도를 프록시나 앱이 무엇을 하든 그보다 아래인 kernel에서 묶는다. token bucket이
# 있어서 페이지의 첫 로드는 최고 속도(BATON_EGRESS_BURST)로 나가고 평균은 BATON_EGRESS_RATE에 머문다. 부팅 때
# baton-egress-shape.service가 돌리고, 다시 돌리면 규칙을 바꾼다.
set -euo pipefail
rate="${BATON_EGRESS_RATE:-2mbit}"
burst="${BATON_EGRESS_BURST:-4mb}"
dev="${BATON_EGRESS_DEV:-$(ip -o route show default | awk '{print $5; exit}')}"
[ -n "$dev" ] || { echo "egress-shape: no default route interface" >&2; exit 1; }
tc qdisc replace dev "$dev" root tbf rate "$rate" burst "$burst" latency 400ms
echo "egress-shape: $dev limited to $rate (burst $burst)"
