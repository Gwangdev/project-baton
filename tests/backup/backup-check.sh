#!/usr/bin/env bash
# 일회용 컨테이너로만 하는 야간 백업 인수 확인.
#  1. 백업 이미지를 빌드하고 임시 폴더에 일회용 키 쌍을 만든다(실제 키는 쓰지 않는다).
#  2. 내부 네트워크에 일회용 DB를 띄우고 마이그레이션을 적용한 뒤 합성 행을 더한다.
#  3. 백업을 한 번 돌리고, 파일이 age로 암호화됐는지, 평문 테이블 이름이 없는지, SHA-256과 맞는지 확인한다.
#  4. tests/recovery/restore-drill.sh로 복원하고 fingerprint를 원본과 비교한다.
#  5. 거절을 확인한다: 수신자 없음, 쓸 수 없는 폴더, 실패하는 덤프(파일이 남지 않는다), 원격 대상.
#  6. 실행이 폴더의 오래된 백업에 보존 규칙을 적용하는지 확인한다.
# 만든 것(컨테이너, 네트워크, volume, 키가 든 임시 폴더)은 끝날 때 모두 지운다.
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
IMAGE=project-baton/backup:local
RUN="baton-backupcheck-$$-$(date +%s)"
WORK=$(mktemp -d)
NET="$RUN-net"; DB="$RUN-db"; VOL="$RUN-data"
PASSWORD="$(openssl rand -hex 24)"
failures=0

cleanup() {
  # 백업 이미지는 데이터 volume을 선언한 DB 이미지라서, 분리 실행한 컨테이너는 `rm -f`만으로는 남는 익명 volume을
  # 받는다. 그래서 여기서는 지울 때마다 volume도 함께 지운다(-v).
  docker rm -fv "$DB" "$RUN-schedule" "$RUN-holder" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm "$VOL" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

pass() { echo "ok   $1"; }
fail() { echo "FAIL $1"; failures=$((failures + 1)); }

docker build -q -t "$IMAGE" infra/backup >/dev/null
mkdir -p "$WORK/key" "$WORK/backups" "$WORK/unmarked"
chmod 0777 "$WORK/backups" "$WORK/unmarked"
# 표식은 「여기가 백업 대상」이라는 뜻이다(NAS에서는 공유 폴더에 있어서, 마운트되지 않은 공유 폴더에는 표식이 없다).
: > "$WORK/backups/.baton-backup-target"
docker run --rm --entrypoint age-keygen --user "$(id -u):$(id -g)" -v "$WORK/key:/out" "$IMAGE" -o /out/identity 2>/dev/null
docker run --rm --entrypoint age-keygen --user "$(id -u):$(id -g)" -v "$WORK/key:/out" "$IMAGE" -y /out/identity > "$WORK/key/recipients"
: > "$WORK/key/empty"

docker network create --internal "$NET" >/dev/null
docker volume create "$VOL" >/dev/null
docker run -d --name "$DB" --network "$NET" --network-alias db --user 999:999 --read-only \
  --tmpfs /var/run/postgresql:uid=999,gid=999 --tmpfs /tmp:uid=999,gid=999 \
  -e POSTGRES_DB=baton -e POSTGRES_USER=baton -e POSTGRES_PASSWORD="$PASSWORD" \
  -v "$VOL:/var/lib/postgresql/data" -v "$ROOT/backend/src/main/resources/db/migration:/migrations:ro" \
  --entrypoint docker-entrypoint.sh "$IMAGE" postgres >/dev/null
for _ in $(seq 1 60); do
  docker exec "$DB" pg_isready -U baton -d baton -q 2>/dev/null && break
  [ "$(docker inspect -f '{{.State.Running}}' "$DB")" = true ] || { docker logs "$DB"; exit 1; }
  sleep 1
done
for f in $(ls backend/src/main/resources/db/migration | sort -t_ -k1.2 -n); do
  docker exec "$DB" psql -q -v ON_ERROR_STOP=1 -U baton -d baton -f "/migrations/$f" >/dev/null
done
docker exec "$DB" psql -q -v ON_ERROR_STOP=1 -U baton -d baton -c "
  insert into organizations(name) values ('Backup Check Org');
  insert into users(organization_id,employee_no,name,title,email,system_role)
  select (select id from organizations), 'bc-'||n, 'Synthetic '||n, 'Engineer', 'bc'||n||'@example.test', 'USER'
  from generate_series(1,25) n;" >/dev/null

backup() { # 추가 docker 인자, 그다음 명령
  docker run --rm --network "$NET" --user 999:999 --read-only --tmpfs /tmp:uid=999,gid=999 \
    -e PGHOST=db -e PGUSER=baton -e PGDATABASE=baton -e TZ=Asia/Seoul "$@"
}

# 새 백업이 생기면 보존 규칙이 지워야 하는 오래된 백업(하루, 남길 달 없음).
: > "$WORK/backups/baton-20200101-020000.dump.age"
: > "$WORK/backups/baton-20200101-020000.dump.age.sha256"

backup -e PGPASSWORD="$PASSWORD" -e BATON_BACKUP_KEEP_DAILY=1 -e BATON_BACKUP_KEEP_MONTHLY=0 \
  -v "$WORK/backups:/backups" -v "$WORK/key/recipients:/etc/baton/backup/recipients:ro" "$IMAGE" once
made=$(ls "$WORK/backups" | grep -E '^baton-[0-9]{8}-[0-9]{6}\.dump\.age$' || true)
[ "$(printf '%s\n' "$made" | sed '/^$/d' | wc -l | tr -d ' ')" = 1 ] && pass "one backup file" || fail "one backup file (got: $made)"
file="$WORK/backups/$made"
[ "$(head -c 21 "$file")" = "age-encryption.org/v1" ] && pass "age format" || fail "age format"
grep -aq -e organizations -e 'Synthetic 1' "$file" && fail "no plain content" || pass "no plain content"
(cd "$WORK/backups" && { command -v sha256sum >/dev/null && sha256sum -c "$made.sha256" || shasum -a 256 -c "$made.sha256"; } >/dev/null 2>&1) \
  && pass "SHA-256 matches" || fail "SHA-256 matches"
mode=$(docker run --rm --user 999:999 -v "$WORK/backups:/backups" --entrypoint stat "$IMAGE" -c %a "/backups/$made")
[ "$mode" = 640 ] && pass "backup is owner-writable and group-readable (640)" || fail "backup is owner-writable and group-readable (640), got $mode"
[ ! -e "$WORK/backups/baton-20200101-020000.dump.age" ] && [ ! -e "$WORK/backups/baton-20200101-020000.dump.age.sha256" ] \
  && pass "retention removed the old backup and its checksum" || fail "retention removed the old backup and its checksum"

docker exec -i "$DB" psql -At -U baton -d baton -f - < tests/recovery/fingerprint-generic.sql > "$WORK/source.fingerprint"
if tests/recovery/restore-drill.sh "$file" "$WORK/key/identity" "$WORK/source.fingerprint" > "$WORK/drill.log" 2>&1; then
  pass "restore drill reproduces the source"
else
  fail "restore drill reproduces the source"; cat "$WORK/drill.log";
fi

before=$(ls "$WORK/backups" | sort)
backup -e PGPASSWORD="$PASSWORD" -v "$WORK/backups:/backups" -v "$WORK/key/empty:/etc/baton/backup/recipients:ro" "$IMAGE" once \
  >/dev/null 2>&1 && fail "refuses without recipients" || pass "refuses without recipients"
backup -e PGPASSWORD="$PASSWORD" -v "$WORK/backups:/backups:ro" -v "$WORK/key/recipients:/etc/baton/backup/recipients:ro" "$IMAGE" once \
  >/dev/null 2>&1 && fail "refuses an unwritable folder" || pass "refuses an unwritable folder"
backup -e PGPASSWORD=wrong-password -v "$WORK/backups:/backups" -v "$WORK/key/recipients:/etc/baton/backup/recipients:ro" "$IMAGE" once \
  >/dev/null 2>&1 && fail "a failing dump fails" || pass "a failing dump fails"
backup -e PGPASSWORD="$PASSWORD" -e BATON_BACKUP_S3_ENDPOINT=https://storage.example.test -v "$WORK/backups:/backups" \
  -v "$WORK/key/recipients:/etc/baton/backup/recipients:ro" "$IMAGE" once >/dev/null 2>&1 \
  && fail "refuses a remote target" || pass "refuses a remote target"
backup -e PGPASSWORD="$PASSWORD" -v "$WORK/unmarked:/backups" -v "$WORK/key/recipients:/etc/baton/backup/recipients:ro" "$IMAGE" once \
  >/dev/null 2>&1 && fail "refuses a folder without the target marker" || pass "refuses a folder without the target marker"
[ -z "$(ls -A "$WORK/unmarked")" ] && pass "nothing written to an unmarked folder" || fail "nothing written to an unmarked folder"
# Compose처럼 빈 값은 기본값을 뜻하고, 1 이상의 정수가 아닌 값만 거절한다.
for bad in 0 30d -1; do
  backup -e PGPASSWORD="$PASSWORD" -e BATON_BACKUP_KEEP_DAILY="$bad" -v "$WORK/backups:/backups" \
    -v "$WORK/key/recipients:/etc/baton/backup/recipients:ro" "$IMAGE" once >/dev/null 2>&1 \
    && fail "refuses KEEP_DAILY='$bad'" || pass "refuses KEEP_DAILY='$bad'"
done
backup -e PGPASSWORD="$PASSWORD" -e BATON_BACKUP_KEEP_MONTHLY=x -v "$WORK/backups:/backups" \
  -v "$WORK/key/recipients:/etc/baton/backup/recipients:ro" "$IMAGE" once >/dev/null 2>&1 \
  && fail "refuses KEEP_MONTHLY='x'" || pass "refuses KEEP_MONTHLY='x'"
# 한 실행이 잠금을 쥔 동안의 두 번째 실행은 첫 실행의 쓰다 만 파일을 지우지 않고 거절된다.
docker run -d --name "$RUN-holder" --user 999:999 --read-only -v "$WORK/backups:/backups" --entrypoint bash "$IMAGE" \
  -c 'exec flock /backups/.baton-backup.lock sleep 20' >/dev/null
sleep 1
backup -e PGPASSWORD="$PASSWORD" -v "$WORK/backups:/backups" -v "$WORK/key/recipients:/etc/baton/backup/recipients:ro" "$IMAGE" once \
  >/dev/null 2>&1 && fail "refuses a run while another holds the lock" || pass "refuses a run while another holds the lock"
docker rm -fv "$RUN-holder" >/dev/null 2>&1
# 일정은 HH:MM이 아닌 시각을 거절하고, 그렇지 않으면 Asia/Seoul로 계산한 다음 실행까지 기다린다.
backup -e PGPASSWORD="$PASSWORD" -e BATON_BACKUP_AT=25:00 -v "$WORK/backups:/backups" \
  -v "$WORK/key/recipients:/etc/baton/backup/recipients:ro" "$IMAGE" schedule >/dev/null 2>&1 \
  && fail "the schedule refuses a bad time" || pass "the schedule refuses a bad time"
docker run -d --name "$RUN-schedule" --network "$NET" --user 999:999 --read-only --tmpfs /tmp:uid=999,gid=999 \
  -e PGHOST=db -e PGUSER=baton -e PGDATABASE=baton -e PGPASSWORD="$PASSWORD" -e BATON_BACKUP_AT=02:00 \
  -v "$WORK/backups:/backups" -v "$WORK/key/recipients:/etc/baton/backup/recipients:ro" "$IMAGE" schedule >/dev/null
sleep 2
next=$(docker logs "$RUN-schedule" 2>&1 | grep -o 'next backup at .*' || true)
docker rm -fv "$RUN-schedule" >/dev/null 2>&1
[[ "$next" =~ ^next\ backup\ at\ [0-9]{4}-[0-9]{2}-[0-9]{2}\ 02:00\ KST$ ]] && pass "the schedule waits for 02:00 KST" \
  || fail "the schedule waits for 02:00 KST (got: $next)"
[ "$(ls "$WORK/backups" | sort)" = "$before" ] && pass "refusals leave no file behind" || fail "refusals leave no file behind ($(ls "$WORK/backups" | tr '\n' ' '))"

[ "$failures" -eq 0 ] && echo "backup check: all passed" || { echo "backup check: $failures failed"; exit 1; }
