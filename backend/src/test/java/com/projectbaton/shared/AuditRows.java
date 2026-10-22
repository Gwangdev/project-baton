package com.projectbaton.shared;

import java.sql.Statement;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시험이 감사 행을 바꾸는 유일한 방법. 운영에서는 DB가 감사 행의 삭제와 수정을 거절하는 것이 핵심이지만(migration
 * V21), 모든 통합 시험은 빈 감사 테이블에서 시작하고 한 시험은 보존 기간에 닿도록 로그인을 오래된 것으로 만든다.
 * session_replication_role을 replica로 두면 PostgreSQL이 그 연결에서 일반 트리거를 건너뛰고, Testcontainers 계정은
 * superuser라서 이를 설정할 수 있다. 연결을 pool에 돌려주기 전에 역할을 되돌리며, 앱 자체에는 이런 경로가 없다.
 */
public final class AuditRows {
    private AuditRows() { }

    public static void clear(JdbcTemplate jdbc) {
        change(jdbc, "delete from audit_events");
    }

    public static void change(JdbcTemplate jdbc, String sql) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
                try {
                    statement.execute(sql);
                } finally {
                    statement.execute("reset session_replication_role");
                }
            }
            return null;
        });
    }
}
