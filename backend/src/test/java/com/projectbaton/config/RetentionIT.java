package com.projectbaton.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.projectbaton.shared.AuditRows;
import java.util.List;
import java.util.UUID;

import com.projectbaton.audit.AuditService;
import com.projectbaton.jobs.JobRepository;
import com.projectbaton.shared.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
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
 * README §8과 C01: 운영 보존 정책이 아직 확정되지 않아서, 작업 메타데이터는 스위치가 켜진 곳에서만 성공 뒤 7일, 실패
 * 뒤 30일 남긴다. 재실행용 멱등 결과는 상관없이 24시간 뒤 만료된다. 끝나지 않은 작업과 진행 중인 멱등 행은 오래돼도
 * 지우지 않는다. 로그인 요청 주소는 끝을 정할 프로젝트가 없는 개인정보라서, 감사 스위치가 켜진 곳에서는 로그인이
 * 2년을 넘으면 주소를 비운다. 감사 행 자체는 지우지 않는다.
 */
@SpringBootTest(properties = {"baton.retention.jobs.enabled=true", "baton.retention.audit.enabled=true"})
@Testcontainers
class RetentionIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionSchedule retention;
    @Autowired JobRepository jobs;
    @Autowired IdempotencyService idempotency;
    @Autowired com.projectbaton.identity.CredentialService credentials;
    @Autowired AuditService audit;

    @BeforeEach
    void reset() {
        jdbc.update("delete from jobs");
        jdbc.update("delete from idempotency_requests");
        AuditRows.clear(jdbc);
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations(name) values('Retention Organization')");
        jdbc.update("""
                insert into users(organization_id,employee_no,name,title,email,system_role)
                values((select id from organizations),'retention-actor','Retention actor','Engineer','retention@example.test','USER')
                """);
    }

    @Test
    void removesOnlyFinishedJobsPastTheirWindowAndExpiredCompletedIdempotencyRows() {
        job("SUCCEEDED", 8);
        job("SUCCEEDED", 6);
        job("FAILED", 31);
        job("FAILED", 29);
        job("PENDING", 60);
        job("RUNNING", 60);
        job("RETRY_WAIT", 60);
        idempotencyRow("COMPLETED", -1);
        idempotencyRow("COMPLETED", 1);
        idempotencyRow("IN_PROGRESS", -1);

        retention.run();

        assertEquals(List.of("FAILED:29", "PENDING:60", "RETRY_WAIT:60", "RUNNING:60", "SUCCEEDED:6"), remainingJobs());
        assertEquals(List.of("COMPLETED:future", "IN_PROGRESS:past"), remainingIdempotency());
    }

    @Test
    void keepsEveryJobWhileTheSwitchIsOffButStillExpiresIdempotencyRows() {
        job("SUCCEEDED", 400);
        job("FAILED", 400);
        idempotencyRow("COMPLETED", -1);

        new RetentionSchedule(jobs, idempotency, credentials, audit, false, false, 24).run();

        assertEquals(List.of("FAILED:400", "SUCCEEDED:400"), remainingJobs());
        assertEquals(List.of(), remainingIdempotency());
    }

    @Test
    void removesUsedAndLongExpiredSetupCodesButKeepsOpenOnes() {
        long user = jdbc.queryForObject("select id from users", Long.class);
        // 사용자마다 쓰지 않은 코드는 최대 하나라서, 쓰지 않은 행은 서로 다른 사용자의 것이다.
        long second = jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,system_role)
                values((select id from organizations),'retention-two','Two','Engineer','two@example.test','USER') returning id
                """, Long.class);
        long third = jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,system_role)
                values((select id from organizations),'retention-three','Three','Engineer','three@example.test','USER') returning id
                """, Long.class);
        credentialToken(user, "open", "now() + interval '1 hour'", false);
        credentialToken(second, "expired-today", "now() - interval '1 hour'", false);
        credentialToken(third, "expired-long-ago", "now() - interval '3 days'", false);
        credentialToken(user, "used", "now() + interval '1 hour'", true);

        retention.run();

        assertEquals(List.of("expired-today", "open"), jdbc.queryForList("select rtrim(token_hash, 'x') from credential_tokens order by 1", String.class));
    }

    private void credentialToken(long userId, String hash, String expiresAt, boolean used) {
        jdbc.update("insert into credential_tokens(user_id,token_hash,expires_at,used_at) values(?, rpad(?, 64, 'x'), " + expiresAt
                + ", " + (used ? "now()" : "null") + ")", userId, hash);
    }

    @Test
    void deletesInBatchesUntilNothingIsLeft() {
        jdbc.update("""
                insert into jobs(id,kind,target_id,dedup_key,status,available_at,deadline_at,created_at,updated_at)
                select gen_random_uuid(),'INGEST',n,'retention-batch-'||n,'SUCCEEDED',now(),now(),now()-interval '9 days',now()-interval '9 days'
                from generate_series(1,12001) n
                """);

        retention.run();

        assertEquals(0, jdbc.queryForObject("select count(*) from jobs", Integer.class));
    }

    @Test
    void clearsOnlyTheAddressOfASignInPastItsWindowAndKeepsEveryRow() {
        audit("signin-old", "SIGN_IN", "192.0.2.1", 25);
        audit("signin-inside", "SIGN_IN", "192.0.2.2", 23);
        audit("signin-bare", "SIGN_IN", null, 30);
        audit("credential-old", "ISSUE_CREDENTIAL_TOKEN", "192.0.2.3", 36);
        audit("other-old", "CREATE_USER", "192.0.2.4", 36);

        retention.run();

        assertEquals(List.of("credential-old=192.0.2.3", "other-old=192.0.2.4", "signin-bare=null", "signin-inside=192.0.2.2", "signin-old=null"),
                jdbc.queryForList("select trace_id || '=' || coalesce(remote_address, 'null') from audit_events order by trace_id", String.class));
        assertEquals(4, jdbc.queryForObject("select count(*) from audit_events where occurred_at < now() - interval '24 months'", Integer.class));
    }

    @Test
    void clearsAddressesInBatchesUntilNothingIsLeftAndAFurtherRunChangesNothing() {
        jdbc.update("""
                insert into audit_events(actor_id,action,target_type,target_id,result,trace_id,remote_address,occurred_at)
                select null,'SIGN_IN','user',n::text,'SUCCESS','retention-batch-'||n,'192.0.2.9',now()-interval '30 months'
                from generate_series(1,12001) n
                """);

        retention.run();

        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where remote_address is not null", Integer.class));
        assertEquals(12001, jdbc.queryForObject("select count(*) from audit_events", Integer.class));
        assertEquals(0, audit.clearSignInAddresses(24));
    }

    @Test
    void keepsEveryAddressWhileTheAuditSwitchIsOff() {
        audit("signin-old", "SIGN_IN", "192.0.2.1", 36);

        new RetentionSchedule(jobs, idempotency, credentials, audit, true, false, 24).run();

        assertEquals("192.0.2.1", jdbc.queryForObject("select remote_address from audit_events", String.class));
    }

    /**
     * 조회는 바꿀 행만 오래된 순서로 읽어야 한다. 부분 인덱스가 없으면 10분마다 계속 커지는 감사 테이블 전체를
     * 읽는다. 작은 테이블은 인덱스와 상관없이 통째로 읽히므로 계획을 볼 때는 순차 스캔을 끈다.
     */
    @Test
    void theClearingStatementReadsThroughItsPartialIndex() {
        String plan = jdbc.execute((java.sql.Connection connection) -> {
            connection.setAutoCommit(false);
            try (java.sql.Statement off = connection.createStatement()) {
                off.execute("set local enable_seqscan = off");
            }
            String sql = "explain " + AuditService.CLEAR_SIGN_IN_ADDRESSES.replaceFirst("\\?", "24").replaceFirst("\\?", "5000");
            try (java.sql.Statement explain = connection.createStatement(); java.sql.ResultSet rows = explain.executeQuery(sql)) {
                StringBuilder text = new StringBuilder();
                while (rows.next()) text.append(rows.getString(1)).append('\n');
                return text.toString();
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        });

        // 순차 스캔을 끄면 planner는 나이로 찾을 수 없을 때 부분 인덱스 전체를 읽고 걸러서 인덱스 이름만으로는 통과해
        // 버리므로, 나이가 인덱스 조건이어야 한다.
        assertEquals(true, plan.contains("audit_events_sign_in_address_idx"), plan);
        assertEquals(true, plan.contains("Index Cond: (occurred_at < (now() - '2 years'::interval))"), plan);
    }

    private void audit(String label, String action, String address, int ageMonths) {
        jdbc.update("""
                insert into audit_events(actor_id,action,target_type,target_id,result,trace_id,remote_address,occurred_at)
                values(null, ?, 'user', '1', 'SUCCESS', ?, ?, now() - make_interval(months => ?))
                """, action, label, address, ageMonths);
    }

    private void job(String status, int ageDays) {
        jdbc.update("""
                insert into jobs(id,kind,target_id,dedup_key,status,available_at,deadline_at,created_at,updated_at)
                values(?, 'INGEST', ?, ?, ?, now(), now() + interval '1 hour',
                       now() - make_interval(days => ?), now() - make_interval(days => ?))
                """, UUID.randomUUID(), ageDays, status + ":" + ageDays, status, ageDays, ageDays);
    }

    private void idempotencyRow(String state, int expiresInHours) {
        boolean completed = state.equals("COMPLETED");
        jdbc.update("""
                insert into idempotency_requests(actor_id,method,path,key,request_hash,state,http_status,response_json,expires_at)
                values((select id from users), 'POST', '/api/v1/retention', ?, repeat('a', 64), ?, ?, cast(? as jsonb),
                       now() + make_interval(hours => ?))
                """, UUID.randomUUID(), state, completed ? 201 : null, completed ? "{}" : null, expiresInHours);
    }

    private List<String> remainingJobs() {
        return jdbc.queryForList("select dedup_key from jobs order by dedup_key", String.class);
    }

    private List<String> remainingIdempotency() {
        return jdbc.queryForList("""
                select state || ':' || case when expires_at > now() then 'future' else 'past' end
                from idempotency_requests order by 1
                """, String.class);
    }
}
