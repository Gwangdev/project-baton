#!/usr/bin/env bash
# 야간 백업의 월간 복원 연습(가정 A-07). 개인키 보관자가 돌린다:
#  tests/recovery/restore-drill.sh <backup.dump.age> <age identity file> [expected fingerprint file]
# 백업은 stream으로 복호화해서(평문으로 쓰지 않는다) 밖으로 가는 경로가 없는 내부 네트워크의 일회용 DB에 넣고,
# fingerprint를 뜬다(tests/recovery/fingerprint-generic.sql). 세 번째 인자가 있으면 fingerprint가 그와 같아야 한다.
# 만든 컨테이너, 네트워크, volume은 끝날 때 지우고, identity 파일은 읽기만 한다.
set -euo pipefail
[ $# -ge 2 ] || { echo "usage: $0 <backup.dump.age> <identity> [expected fingerprint]" >&2; exit 64; }
cd "$(dirname "$0")/../.."
BACKUP=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
IDENTITY=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
EXPECTED="${3:-}"
IMAGE=project-baton/backup:local
RUN="baton-drill-$$-$(date +%s)"
NET="$RUN-net"; DB="$RUN-db"; VOL="$RUN-data"
PASSWORD="$(openssl rand -hex 24)"
OUT=$(mktemp)

cleanup() {
  docker rm -f "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm "$VOL" >/dev/null 2>&1 || true
  rm -f "$OUT"
}
trap cleanup EXIT

[ -r "$BACKUP" ] && [ -r "$IDENTITY" ] || { echo "cannot read the backup or the identity file" >&2; exit 1; }
if [ -f "${BACKUP}.sha256" ]; then
  # Linux는 sha256sum, macOS는 shasum. 운영자 호스트에 있는 쪽을 쓴다.
  if command -v sha256sum >/dev/null; then sum=(sha256sum -c); else sum=(shasum -a 256 -c); fi
  (cd "$(dirname "$BACKUP")" && "${sum[@]}" "$(basename "$BACKUP").sha256" >/dev/null) || { echo "checksum mismatch" >&2; exit 1; }
fi

docker network create --internal "$NET" >/dev/null
docker volume create "$VOL" >/dev/null
docker run -d --name "$DB" --network "$NET" --network-alias db --user 999:999 --read-only \
  --tmpfs /var/run/postgresql:uid=999,gid=999 --tmpfs /tmp:uid=999,gid=999 \
  -e POSTGRES_DB=baton -e POSTGRES_USER=baton -e POSTGRES_PASSWORD="$PASSWORD" \
  -v "$VOL:/var/lib/postgresql/data" --entrypoint docker-entrypoint.sh "$IMAGE" postgres >/dev/null
for _ in $(seq 1 60); do
  docker exec "$DB" pg_isready -U baton -d baton -q 2>/dev/null && break
  [ "$(docker inspect -f '{{.State.Running}}' "$DB")" = true ] || { docker logs "$DB" >&2; exit 1; }
  sleep 1
done

# identity 파일은 운영자 소유(mode 600)라서 복호화 컨테이너를 운영자 자신의 사용자로 돌린다.
docker run --rm -i --network "$NET" --user "$(id -u):$(id -g)" --read-only -e PGPASSWORD="$PASSWORD" \
  -v "$IDENTITY:/run/identity:ro" --entrypoint bash "$IMAGE" \
  -c 'set -o pipefail; age --decrypt --identity /run/identity | pg_restore --exit-on-error --no-owner -h db -U baton -d baton' < "$BACKUP"
docker exec "$DB" psql -q -U baton -d baton -c analyze >/dev/null
docker exec -i "$DB" psql -At -U baton -d baton -f - < tests/recovery/fingerprint-generic.sql > "$OUT"

echo "restored $(basename "$BACKUP"): $(wc -l < "$OUT" | tr -d ' ') tables, $(awk -F'|' '{s+=$2} END {print s+0}' "$OUT") rows"
if docker exec "$DB" psql -At -U baton -d baton -c "select max(version::int) from flyway_schema_history where success" >/dev/null 2>&1; then
  echo "flyway version: $(docker exec "$DB" psql -At -U baton -d baton -c "select max(version::int) from flyway_schema_history where success")"
fi
if [ -n "$EXPECTED" ]; then
  if diff -u "$EXPECTED" "$OUT"; then echo "fingerprint: same as expected"; else echo "fingerprint: DIFFERENT" >&2; exit 1; fi
fi
