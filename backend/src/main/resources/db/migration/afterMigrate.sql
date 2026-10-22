-- Flyway가 migrate마다 마이그레이션 계정으로 돌린다. 배포는 DB를 처음 띄울 때 역할 둘을
-- 만든다(infra/db/init/10-roles.sh). 앱이 접속하는 baton_app과 접속기록 점검용 baton_inspect다(docs/operations). 둘
-- 다 아무것도 소유하지 않아서 감사 트리거(V21)를 끌 수 없다. 나중 마이그레이션이 더한 테이블도 다음 실행에서 권한을
-- 받는다. 역할이 없는 곳(계정 하나를 쓰는 로컬 스택과 시험)에서는 아무 권한도 주지 않는다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'baton_app') THEN
        GRANT USAGE ON SCHEMA public TO baton_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO baton_app;
        GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO baton_app;
        -- 감사는 추가하고 읽기만 한다. 앱이 하는 유일한 변경은 로그인 주소를 비우는 것이다.
        REVOKE UPDATE, DELETE, TRUNCATE ON audit_events FROM baton_app;
        GRANT UPDATE (remote_address) ON audit_events TO baton_app;
        -- 마이그레이션은 소유자의 일이다.
        REVOKE ALL ON flyway_schema_history FROM baton_app;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'baton_inspect') THEN
        GRANT USAGE ON SCHEMA public TO baton_inspect;
        GRANT SELECT ON audit_events, users TO baton_inspect;
    END IF;
END
$$;
