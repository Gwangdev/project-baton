#!/usr/bin/env bash
# 개발자 기계에서 공개 데모의 기준 덤프를 만든다(work-orders D7, 결정 12):
#  BATON_DEMO_PASSWORD=<공개한 데모 비밀번호> infra/demo/make-baseline.sh <output.dump>
# (Maven Central이 이 기계를 거절하면 BATON_GRADLE_FLAGS=--offline으로 로컬 Gradle 캐시에서 빌드한다.)
# 합성 시나리오는 패키지한 앱에 들어 있지 않은 실제 백엔드 E2E seeder(backend/src/e2e: 계정 넷, 가상 프로젝트 둘)에서
# 와서, 덤프를 여기서 만들어 호스트에 한 번 복사한다. e2e 프로필로 띄운 앱이 로컬 포트의 일회용 DB를 마이그레이션하고
# seed를 넣으며, 큐의 작업이 모두 돌면(stub이 기록을 임베딩해서 초기화 직후에도 Q&A가 된다) 소유자와 권한 없이 DB를
# 덤프한다. 그래서 덤프는 마이그레이션 단계가 앱에 권한을 다시 주는 배포 DB에 복원된다.
# 덤프에는 비밀번호가 아니라 데모 비밀번호의 해시가 들어 있지만, 그래도 저장소에는 두지 않는다.
set -euo pipefail
[ $# -eq 1 ] || { echo "usage: BATON_DEMO_PASSWORD=... $0 <output.dump>" >&2; exit 64; }
out="$1"
[ "${#BATON_DEMO_PASSWORD}" -ge 12 ] || { echo "BATON_DEMO_PASSWORD must be at least 12 characters" >&2; exit 64; }
cd "$(dirname "$0")/../.."
run="baton-baseline-$$"
password="$(openssl rand -hex 16)"
app_pid=""
cleanup() {
  [ -n "$app_pid" ] && kill -TERM -- "-$app_pid" 2>/dev/null || true
  docker rm -fv "$run" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --name "$run" -e POSTGRES_USER=baton -e POSTGRES_DB=baton -e POSTGRES_PASSWORD="$password" \
  -p 127.0.0.1::5432 pgvector/pgvector:pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f >/dev/null
for _ in $(seq 1 60); do docker exec "$run" pg_isready -U baton -d baton >/dev/null 2>&1 && break; sleep 1; done
sleep 2
port="$(docker port "$run" 5432/tcp | head -1 | sed 's/.*://')"
q() { docker exec "$run" psql -At -U baton -d baton -c "$1" 2>/dev/null; }

set -m
BATON_DB_URL="jdbc:postgresql://127.0.0.1:${port}/baton" BATON_DB_USERNAME=baton BATON_DB_PASSWORD="$password" \
  BATON_SEED_PASSWORD="$BATON_DEMO_PASSWORD" SERVER_PORT=0 MANAGEMENT_SERVER_PORT=0 \
  ./backend/gradlew -p backend bootRunE2e --no-daemon --quiet ${BATON_GRADLE_FLAGS:-} > "${TMPDIR:-/tmp}/$run.log" 2>&1 &
app_pid=$!
set +m

ready=""
for _ in $(seq 1 300); do
  seeded="$(q "select count(*) from projects where code in ('PJT-2026-014','PJT-2026-021')" || true)"
  open="$(q "select count(*) from jobs where status in ('PENDING','RUNNING','RETRY_WAIT')" || true)"
  if [ "$seeded" = "2" ] && [ "$open" = "0" ]; then ready=yes; break; fi
  sleep 2
done
[ -n "$ready" ] || { echo "the seeded application did not settle; log: ${TMPDIR:-/tmp}/$run.log" >&2; exit 1; }
kill -TERM -- "-$app_pid" 2>/dev/null || true
wait "$app_pid" 2>/dev/null || true
app_pid=""

docker exec "$run" pg_dump -U baton -d baton -Fc --no-owner --no-privileges > "$out"
rm -f "${TMPDIR:-/tmp}/$run.log"
echo "baseline: $out ($(wc -c < "$out" | tr -d ' ') bytes; $(q "select count(*) from users") accounts, $(q "select count(*) from work_records") records)"
