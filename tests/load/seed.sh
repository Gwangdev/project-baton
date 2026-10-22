#!/usr/bin/env bash
# 합성 부하 데이터 집합(tests/load/seed/seed.sql)을 compose DB에 넣는다.
#
#  BATON_SEED_PASSWORD  모든 합성 계정의 로그인 비밀번호(필수, 저장소에 두지 않는다)
#  BATON_LOAD_SEED      결정적 seed, 기본 20260928. 같은 seed는 같은 행을 만든다
#
# compose 스택이 마이그레이션된 빈 DB로 떠 있어야 하고, compose.yml이 요구하는 변수를 export해 둔다. seed.sql에는
# 비밀번호의 bcrypt 해시만 닿는다. 해시는 일회용 DB의 pgcrypto로 계산해서, 업무 스키마에 Flyway가 소유하지 않은
# extension이 생기지 않는다.
set -euo pipefail
cd "$(dirname "$0")/../.."

: "${BATON_SEED_PASSWORD:?BATON_SEED_PASSWORD is required}"
seed="${BATON_LOAD_SEED:-20260928}"
psql=(docker compose exec -T db psql -U baton -v ON_ERROR_STOP=1 -X -q)

"${psql[@]}" -d postgres -c 'drop database if exists baton_seed_hash' -c 'create database baton_seed_hash'
hash=$(docker compose exec -T -e BATON_SEED_PASSWORD db psql -U baton -d baton_seed_hash -X -q -At -v ON_ERROR_STOP=1 <<'SQL'
create extension pgcrypto;
\getenv password BATON_SEED_PASSWORD
select '{bcrypt}' || crypt(:'password', gen_salt('bf', 10));
SQL
)
"${psql[@]}" -d postgres -c 'drop database baton_seed_hash'

started=$(date +%s)
# seed가 도는 몇 분 동안 프로세스 목록에 보이는 -v 인자가 아니라 환경으로 해시를 psql에 넘긴다(seed.sql은 \getenv로
# 읽는다).
BATON_SEED_CREDENTIAL_HASH="$hash" docker compose exec -T -e BATON_SEED_CREDENTIAL_HASH db \
  psql -U baton -v ON_ERROR_STOP=1 -X -q -d baton -v seed="$seed" < tests/load/seed/seed.sql
echo "seed.sh: seed=$seed elapsed=$(( $(date +%s) - started ))s"
