package com.projectbaton.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.projectbaton.shared.AuditRows;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * 개인정보의 안전성 확보조치 기준 제8조 ③은 접속기록을 위·변조되지 않게 안전하게 보관하라고 한다. 감사 행은 추가만
 * 하고, 시스템이 하는 유일한 변경은 보존 기간이 지난 로그인의 요청 주소를 비우는 것이다. DB가 그 밖의 변경을 모두
 * 거절해서, 앱의 버그나 주입된 문장도 기록을 고치거나 지울 수 없다.
 */
@SpringBootTest
@Testcontainers
class AuditTamperIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        AuditRows.clear(jdbc);
    }

    @Test
    void anAuditRowCannotBeDeletedOrTruncated() {
        long row = row("CREATE_PROJECT", null, 0);
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from audit_events where id = ?", row));
        assertThrows(DataAccessException.class, () -> jdbc.execute("truncate table audit_events"));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events", Integer.class));
    }

    @Test
    void noColumnOfAnAuditRowCanBeRewritten() {
        long row = row("SIGN_IN", "203.0.113.7", 0);
        for (String change : new String[] {"action = 'SIGN_OUT'", "result = 'FAILURE'", "occurred_at = now() - interval '3 years'",
                "actor_id = null", "target_id = 'someone-else'", "trace_id = 'forged'", "remote_address = '198.51.100.1'"}) {
            assertThrows(DataAccessException.class, () -> jdbc.update("update audit_events set " + change + " where id = ?", row), change);
        }
        assertEquals("203.0.113.7", jdbc.queryForObject("select remote_address from audit_events where id = ?", String.class, row));
    }

    @Test
    void onlyASignInAddressCanBeClearedAndOnlyThatColumn() {
        long other = row("RESET_CREDENTIAL", "203.0.113.8", 0);
        assertThrows(DataAccessException.class, () -> jdbc.update("update audit_events set remote_address = null where id = ?", other));
        long signIn = row("SIGN_IN", "203.0.113.9", 0);
        assertThrows(DataAccessException.class,
                () -> jdbc.update("update audit_events set remote_address = null, result = 'FAILURE' where id = ?", signIn));

        assertEquals(1, jdbc.update("update audit_events set remote_address = null where id = ?", signIn));
        assertNull(jdbc.queryForObject("select remote_address from audit_events where id = ?", String.class, signIn));
    }

    @Test
    void theRetentionBatchStillClearsOldSignInAddresses() {
        long old = row("SIGN_IN", "203.0.113.10", 30);
        long recent = row("SIGN_IN", "203.0.113.11", 1);
        assertEquals(1, jdbc.update(AuditService.CLEAR_SIGN_IN_ADDRESSES, 24, 5_000));
        assertNull(jdbc.queryForObject("select remote_address from audit_events where id = ?", String.class, old));
        assertEquals("203.0.113.11", jdbc.queryForObject("select remote_address from audit_events where id = ?", String.class, recent));
    }

    private long row(String action, String address, int monthsAgo) {
        return jdbc.queryForObject("""
                insert into audit_events (actor_id, action, target_type, target_id, result, trace_id, remote_address, occurred_at)
                values (null, ?, 'user', '1', 'SUCCESS', 'trace', ?, now() - make_interval(months => ?)) returning id
                """, Long.class, action, address, monthsAgo);
    }
}
