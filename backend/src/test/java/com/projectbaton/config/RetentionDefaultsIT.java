package com.projectbaton.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.projectbaton.shared.AuditRows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * README §8: 운영 보존 정책이 확정되기 전에는 자동 삭제하지 않으므로, 보존 속성을 하나도 주지 않은 스택은 오래된
 * 로그인의 요청 주소를 남겨야 한다. 합성 데이터 스택만 감사 보존을 켠다.
 */
@SpringBootTest
@Testcontainers
class RetentionDefaultsIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionSchedule retention;

    @Test
    void anOldSignInKeepsItsAddressWhereNoRetentionPropertyIsSet() {
        AuditRows.clear(jdbc);
        jdbc.update("""
                insert into audit_events(actor_id,action,target_type,target_id,result,trace_id,remote_address,occurred_at)
                values(null, 'SIGN_IN', 'user', '1', 'SUCCESS', 'retention-default', '192.0.2.1', now() - interval '900 days')
                """);

        retention.run();

        assertEquals("192.0.2.1", jdbc.queryForObject("select remote_address from audit_events", String.class));
    }
}
