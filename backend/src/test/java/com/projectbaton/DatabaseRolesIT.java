package com.projectbaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.projectbaton.audit.AuditService;
import com.projectbaton.config.DatabaseMigration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * 배포는 DB 계정을 나눈다(work-orders D7, 결정 11). 마이그레이션은 소유자로, 앱은 baton_app으로, 접속기록 점검은
 * baton_inspect로 돈다. 소유자는 superuser라 감사 트리거(V21)를 끌 수 있지만, baton_app은 아무것도 소유하지 않아서 끌
 * 수 없다. 역할은 DB 첫 기동 스크립트에서, 권한은 Flyway 콜백에서 배포가 만드는 것과 똑같이 받는다.
 */
@SpringBootTest
@Testcontainers
class DatabaseRolesIT {
    private static final String APP_PASSWORD = "synthetic-app-password";
    private static final String INSPECT_PASSWORD = "synthetic-inspect-password";

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withEnv("BATON_DB_APP_PASSWORD", APP_PASSWORD)
            .withEnv("BATON_DB_INSPECT_PASSWORD", INSPECT_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/db/init/10-roles.sh", 0755), "/docker-entrypoint-initdb.d/10-roles.sh");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "baton_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        // 배포에서처럼 일회용 마이그레이션 단계가 먼저 소유자로 돌고, 앱은 마이그레이션하지 않는다.
        DatabaseMigration.migrate(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AuditService audit;

    @Test
    void theApplicationRunsAsAnAccountThatOwnsNothing() {
        assertEquals("baton_app", jdbc.queryForObject("select current_user", String.class));
        assertFalse(jdbc.queryForObject("select rolsuper from pg_roles where rolname = current_user", Boolean.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pg_tables where schemaname = 'public' and tableowner = current_user", Integer.class));
    }

    @Test
    void theApplicationReadsAndWritesEveryTableButOnlyAddsToTheAuditAndClearsItsAddress() {
        List<String> tables = jdbc.queryForList("""
                select tablename from pg_tables where schemaname = 'public' and tablename <> 'flyway_schema_history' order by tablename
                """, String.class);
        assertTrue(tables.size() >= 30, tables.toString());
        for (String table : tables) {
            boolean audit = table.equals("audit_events");
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                boolean expected = !audit || privilege.equals("SELECT") || privilege.equals("INSERT");
                assertEquals(expected, jdbc.queryForObject("select has_table_privilege(?, ?)", Boolean.class, "public." + table, privilege),
                        table + " " + privilege);
            }
        }
        assertTrue(jdbc.queryForObject("select has_column_privilege('public.audit_events', 'remote_address', 'UPDATE')", Boolean.class));
        assertFalse(jdbc.queryForObject("select has_column_privilege('public.audit_events', 'action', 'UPDATE')", Boolean.class));
        assertFalse(jdbc.queryForObject("select has_table_privilege('public.flyway_schema_history', 'SELECT')", Boolean.class));

        audit.record(null, "SIGN_IN", "user", "1", "SUCCESS", "trace", "203.0.113.20");
        // 트리거보다 앞에서도, 이 계정은 감사 행의 다른 열을 건드릴 수 없다.
        assertThrows(DataAccessException.class, () -> jdbc.update("update audit_events set occurred_at = now()"));
        assertEquals(1, jdbc.update("update audit_events set remote_address = null where action = 'SIGN_IN'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from audit_events"));
    }

    @Test
    void theApplicationCannotTurnTheAuditTriggersOff() {
        assertThrows(DataAccessException.class, () -> jdbc.execute("alter table audit_events disable trigger audit_events_append_only_rows"));
        assertThrows(DataAccessException.class, () -> jdbc.execute("set session_replication_role = replica"));
    }

    @Test
    void theInspectionAccountReadsOnlyTheAuditAndTheUsers() throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "baton_inspect", INSPECT_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.executeQuery("select count(*) from audit_events a left join users u on u.id = a.actor_id").close();
            assertThrows(SQLException.class, () -> statement.executeQuery("select count(*) from work_records"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "insert into audit_events (action, target_type, target_id, result, trace_id) values ('X', 'x', 'x', 'x', 'x')"));
        }
    }
}
