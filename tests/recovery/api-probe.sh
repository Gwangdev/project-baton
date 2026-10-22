#!/usr/bin/env bash
# API 컨테이너 안에서 돈다(bash와 curl만 있으면 된다). 컨테이너 자신의 API 포트에서 합성 사용자로 로그인하고, 읽기
# 응답마다 sha256 한 줄을 출력한다. 원본 API와 복원한 API에 돌린 두 출력은 같아야 한다. 읽기만 하고, 고른 operation은
# 시계나 예약 수집에 기대지 않는다.
#
#  BATON_SEED_PASSWORD  합성 계정의 비밀번호(docker exec -e로 넘기고 명령줄에는 두지 않는다)
set -euo pipefail
base=http://127.0.0.1:8080/api/v1
: "${BATON_SEED_PASSWORD:?}"

session() {
  local headers token cookie
  headers=$(mktemp)
  token=$(curl -fsS -D "$headers" "$base/auth/csrf" | sed 's/.*"token":"\([^"]*\)".*/\1/')
  cookie=$(grep -i '^set-cookie: XSRF-TOKEN=' "$headers" | sed 's/^[^:]*: \([^;]*\).*/\1/' | tr -d '\r')
  # printf는 셸 내장 명령이라서 본문을 stdin으로 넘기면 비밀번호가 프로세스 인자가 되지 않는다.
  printf '{"employeeNo":"%s","password":"%s"}' "$1" "$BATON_SEED_PASSWORD" \
    | curl -fsS -o /dev/null -D "$headers" -H 'Content-Type: application/json' -H "X-CSRF-TOKEN: $token" \
      -H "Cookie: $cookie" --data-binary @- "$base/auth/sign-in"
  # 세션 쿠키는 Secure인데 이 probe는 컨테이너 안에서 평문 HTTP로 말하므로 직접 보낸다.
  grep -i '^set-cookie: BATON_SESSION=' "$headers" | sed 's/^[^:]*: \([^;]*\).*/\1/' | tr -d '\r'
  rm -f "$headers"
}

probe() {
  local cookie=$1 path=$2
  printf '%s %s\n' "$(curl -fsS -H "Cookie: $cookie" "$base$path" | sha256sum | cut -c1-64)" "$path"
}

manager=$(session LT00004)
incoming=$(session LT00604)
member=$(session LT00904)
for path in /projects/1 "/projects/1/records?limit=100&trustStatus=CONFIRMED" "/projects/1/records?limit=100&trustStatus=AUTO_DRAFT" \
            /records/1000 /records/44999 /projects/1/handovers; do
  probe "$manager" "$path"
done
handover=$(curl -fsS -H "Cookie: $manager" "$base/projects/1/handovers" | sed 's/.*"items":\[{"id":\([0-9]*\).*/\1/')
for path in "/handovers/$handover" "/handovers/$handover/items?limit=100" "/handovers/$handover/assessment" \
            "/handovers/$handover/questions?limit=100"; do
  probe "$incoming" "$path"
done
for path in "/projects/1/records?limit=100&trustStatus=CONFIRMED&occurredFrom=2026-09-01&occurredTo=2026-09-27" \
            "/projects/1/notes?limit=20" /records/12345; do
  probe "$member" "$path"
done
