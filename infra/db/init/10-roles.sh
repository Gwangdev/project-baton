#!/usr/bin/env bash
# DB volume을 처음 만들 때 한 번(docker-entrypoint-initdb.d), 소유자 계정 POSTGRES_USER로 돈다. 앱은 감사
# 트리거(V21)를 끌 수 있는 superuser인 소유자 계정을 썼다. 배포는 대신 아무것도 소유하지 않는 baton_app을 앱에, 읽기만
# 하는 baton_inspect를 접속기록 점검에 준다. 권한은 Flyway 콜백 afterMigrate.sql이 마이그레이션마다 주므로 나중에 더한
# 테이블도 덮는다. 비밀번호는 환경에서 오고, 비밀번호가 없는 역할은 만들지 않는다.
set -euo pipefail
create_role() {
  local role="$1" password="$2"
  [ -n "$password" ] || { echo "10-roles: ${role} not created (no password)"; return 0; }
  # psql이 값을 변수로 넘기고 format(%L)이 literal로 감싸서, 값의 어떤 글자도 문장을 끝낼 수 없다.
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v role="$role" -v secret="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', :'role', :'secret') \gexec
SQL
}
create_role baton_app "${BATON_DB_APP_PASSWORD:-}"
create_role baton_inspect "${BATON_DB_INSPECT_PASSWORD:-}"
