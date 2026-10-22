#!/usr/bin/env bash
# 복구 확인: compose DB를 백업하고 따로 된 빈 volume에 복원해서, 업무 읽기, snapshot, 작업이 같은지 보인다. 원본
# volume은 지우거나 바꾸지 않는다.
#
#  tests/recovery/restore-check.sh <results-dir>
#
# <results-dir>은 저장소 밖이어야 하고, 덤프, fingerprint 둘, API probe 둘, 시간 기록을 받는다. compose 스택이 떠
# 있어야 하고 compose.yml이 요구하는 변수(BATON_DB_PASSWORD, BATON_SEED_PASSWORD, COMPOSE_PROJECT_NAME)를 export해
# 둔다.
#
# 백업하는 동안 API와 프록시를 멈춰서 원본의 fingerprint를 덤프와 같은 시점에 뜬다. 점검 시간대 백업이고, 온라인
# 백업은 pg_dump --snapshot이 필요해서 여기서 재지 않는다. 복원한 사본은 공개 포트 없이 자기 내부 네트워크에서 돌고
# 살펴볼 수 있게 남겨 두며, 스크립트가 지우는 방법을 출력한다.
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$(cd "$1" && pwd)"
: "${BATON_DB_PASSWORD:?BATON_DB_PASSWORD is required}"
project="${COMPOSE_PROJECT_NAME:-project-baton}"
restore_volume="${project}_restore-db-data"
restore_network="${project}_restore"
restore_db="${project}-restore-db"
restore_app="${project}-restore-app"
db_image=$(docker inspect --format '{{.Config.Image}}' "$(docker compose ps -q db)")
app_image=$(docker inspect --format '{{.Config.Image}}' "$(docker compose ps -q app)")
seconds() { date +%s; }
log() { echo "$(date -u +%FT%TZ) $*" | tee -a "$out/timings.txt"; }

if docker volume inspect "$restore_volume" >/dev/null 2>&1; then
  echo "restore volume $restore_volume already exists; remove it or use another COMPOSE_PROJECT_NAME" >&2
  exit 1
fi

log "stop api and proxy for a consistent backup"
# 아래 재시작 전에 실패하면(pg_dump 도중 결과 디스크가 가득 참, 중단된 실행) 원본 서비스가 멈춘 채 끝나므로,
# 스크립트가 재시작에 닿을 때까지 재시작을 걸어 둔다.
trap 'log "backup did not finish; starting the original api and proxy again"; docker compose start app proxy >/dev/null' EXIT
docker compose stop proxy app >/dev/null

started=$(seconds)
docker compose exec -T db psql -U baton -d baton -X -q < tests/recovery/fingerprint.sql > "$out/original.fingerprint"
docker compose exec -T db pg_dump -U baton -d baton -Fc -Z 6 > "$out/baton.dump"
backup_s=$(( $(seconds) - started ))
shasum -a 256 "$out/baton.dump" > "$out/baton.dump.sha256"
log "backup done in ${backup_s}s, $(du -h "$out/baton.dump" | cut -f1)"

log "start the original api and proxy again"
trap - EXIT
docker compose start app proxy >/dev/null

# 복원한 DB는 원본과 같은 비밀번호를 받는다. 값이 아니라 이름으로(-e VAR) 넘기고 명령줄에는 두지 않는다.
export POSTGRES_PASSWORD="$BATON_DB_PASSWORD"
running() { [ "$(docker inspect --format '{{.State.Running}}' "$1" 2>/dev/null)" = true ] || { docker logs "$1" >&2; exit 1; }; }

started=$(seconds)
docker volume create "$restore_volume" >/dev/null
docker network create --internal "$restore_network" >/dev/null
docker run -d --name "$restore_db" --network "$restore_network" --user 999:999 --read-only \
  --tmpfs /var/run/postgresql:uid=999,gid=999,mode=2775 --tmpfs /tmp:uid=999,gid=999 \
  -e POSTGRES_DB=baton -e POSTGRES_USER=baton -e POSTGRES_PASSWORD \
  -v "$restore_volume:/var/lib/postgresql/data" "$db_image" \
  postgres -c shared_buffers=512MB -c effective_cache_size=1536MB >/dev/null
until docker exec "$restore_db" pg_isready -U baton -d baton >/dev/null 2>&1; do running "$restore_db"; sleep 1; done
# 첫 "ready"는 initdb의 임시 서버에서 올 수 있으므로, 최종 서버가 쿼리를 받을 때까지 기다린다.
until docker exec "$restore_db" psql -U baton -d baton -Atc 'select 1' >/dev/null 2>&1; do running "$restore_db"; sleep 1; done
docker exec -i "$restore_db" pg_restore -U baton -d baton --no-owner --exit-on-error < "$out/baton.dump"
docker exec "$restore_db" psql -U baton -d baton -X -q -c analyze
restore_s=$(( $(seconds) - started ))
log "restore into empty volume $restore_volume done in ${restore_s}s"

docker exec -i "$restore_db" psql -U baton -d baton -X -q < tests/recovery/fingerprint.sql > "$out/restored.fingerprint"
if diff "$out/original.fingerprint" "$out/restored.fingerprint" > "$out/fingerprint.diff"; then
  log "fingerprint: identical ($(wc -l < "$out/original.fingerprint") lines)"
else
  log "fingerprint: DIFFERENT, see fingerprint.diff"
fi

# 복원한 API는 원본과 같은 이미지, 사용자, 비밀값으로 복원한 DB를 읽는다. worker도 돌아서, 예약 수집이 probe가 읽는
# 것을 바꾸기 전에 답을 비교한다.
docker run -d --name "$restore_app" --network "$restore_network" --user 10001:10001 --read-only \
  --tmpfs /tmp:uid=10001,gid=10001 -e TZ=Asia/Seoul -e BATON_SESSION_COOKIE_SECURE=true -e BATON_AI_PROFILE=stub \
  -e BATON_DB_URL="jdbc:postgresql://$restore_db:5432/baton" -e BATON_DB_USERNAME=baton -e BATON_DB_PASSWORD \
  "$app_image" >/dev/null
for container in "$(docker compose ps -q app)" "$restore_app"; do
  until docker exec "$container" curl -fsS -o /dev/null http://127.0.0.1:8081/actuator/health/readiness 2>/dev/null; do
    running "$container"; sleep 2
  done
done
docker exec -i -e BATON_SEED_PASSWORD "$(docker compose ps -q app)" bash -s < tests/recovery/api-probe.sh > "$out/original.api"
docker exec -i -e BATON_SEED_PASSWORD "$restore_app" bash -s < tests/recovery/api-probe.sh > "$out/restored.api"
if diff "$out/original.api" "$out/restored.api" > "$out/api.diff"; then
  log "api probe: identical ($(wc -l < "$out/original.api") responses)"
else
  log "api probe: DIFFERENT, see api.diff"
fi
log "original volume untouched: $(docker volume inspect --format '{{.Name}} created {{.CreatedAt}}' "${project}_db-data")"
cat <<MSG | tee -a "$out/timings.txt"
The restored copy is still running for inspection. Remove it with:
  docker rm -f $restore_app $restore_db && docker network rm $restore_network && docker volume rm $restore_volume
MSG
