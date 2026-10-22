package com.projectbaton.handover;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.Set;

import com.projectbaton.config.JobHousekeeping;
import com.projectbaton.jobs.JobLeaseService;
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
 * README §5.2: 시간이 초과되거나 시도를 다 쓴 패키지 생성은 인수인계를 FAILED로 옮기고, PM은 거기서 재시도할 수 있다.
 * 예전에는 작업 행만 FAILED가 되고 인수인계는 REQUESTED로 남아서 재시도, 재생성, 새 요청이 모두 거절했고, SQL 말고는
 * 프로젝트를 되살릴 방법이 없었다.
 */
@SpringBootTest
@Testcontainers
class HandoverRecoveryIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired HandoverRequestService requests;
    @Autowired HandoverGenerationHandler handler;
    @Autowired JobLeaseService leases;
    @Autowired JobHousekeeping housekeeping;
    private long organizationId;

    @BeforeEach
    void reset() {
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations restart identity cascade");
        organizationId = jdbc.queryForObject("insert into organizations(name) values('Recovery Organization') returning id", Long.class);
    }

    @Test
    void theHandoverIsGeneratingOnceItsPackageJobStarts() {
        Seed seed = seed();
        long handover = request(seed);

        long before = jdbc.queryForObject("select version from handovers where id=?", Long.class, handover);
        JobLeaseService.JobContext claimed = leases.claim("BUILD_PACKAGE").orElseThrow();
        handler.begin(claimed);

        assertEquals("GENERATING", status(handover));
        // 다른 인수인계 전이는 모두 버전을 올린다. REQUESTED의 ETag를 쥔 사람이 이미 GENERATING으로 넘어간 인수인계에
        // 손댈 수 있어서는 안 된다.
        assertEquals(before + 1, jdbc.queryForObject("select version from handovers where id=?", Long.class, handover));
        assertEquals("GENERATING", jdbc.queryForObject(
                "select status::text from handover_generations where handover_id=? and generation=1", String.class, handover));
    }

    @Test
    void anExhaustedPackageJobFailsTheHandoverAndRetryRecoversIt() {
        Seed seed = seed();
        long handover = request(seed);
        JobLeaseService.JobContext claimed = leases.claim("BUILD_PACKAGE").orElseThrow();
        handler.begin(claimed);
        jdbc.update("update jobs set attempts=3, lease_until=now()-interval '1 second' where id=?", claimed.jobId());

        housekeeping.sweep();

        assertEquals("FAILED", status(handover));
        assertEquals("MAX_ATTEMPTS_EXCEEDED", failureReason(handover));
        assertEquals("FAILED", jdbc.queryForObject(
                "select status::text from handover_generations where handover_id=? and generation=1", String.class, handover));
        long version = jdbc.queryForObject("select version from handovers where id=?", Long.class, handover);
        assertEquals("REQUESTED", requests.retry(seed.managerId(), handover, version).status());
    }

    @Test
    void aPackageJobPastItsDeadlineFailsTheHandover() {
        Seed seed = seed();
        long handover = request(seed);
        jdbc.update("update jobs set deadline_at=now()-interval '1 second' where kind='BUILD_PACKAGE' and target_id=?", handover);

        housekeeping.sweep();

        assertEquals("FAILED", status(handover));
        assertEquals("DEADLINE_EXCEEDED", failureReason(handover));
    }

    @Test
    void aFailedJobOfAnEarlierGenerationLeavesTheCurrentGenerationAlone() {
        Seed seed = seed();
        long handover = request(seed);
        jdbc.update("update jobs set status='FAILED', failure_code='MAX_ATTEMPTS_EXCEEDED' where kind='BUILD_PACKAGE' and target_id=?", handover);
        jdbc.update("update handovers set generation=2 where id=?", handover);
        jdbc.update("insert into handover_generations(handover_id,generation,project_id,created_by) values(?,2,?,?)",
                handover, seed.projectId(), seed.managerId());
        jdbc.update("""
                insert into jobs(id,kind,target_id,generation,dedup_key,status,available_at,deadline_at)
                values(gen_random_uuid(),'BUILD_PACKAGE',?,2,'recovery-current','PENDING',now(),now()+interval '2 hours')
                """, handover);

        housekeeping.sweep();

        assertEquals("REQUESTED", status(handover));
    }

    private String status(long handover) {
        return jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover);
    }

    private String failureReason(long handover) {
        return jdbc.queryForObject("select failure_reason from handovers where id=?", String.class, handover);
    }

    private long request(Seed seed) {
        return requests.request(seed.managerId(), seed.projectId(), new HandoverRequestService.Command(
                seed.outgoingId(), seed.incomingId(), LocalDate.of(2026, 9, 14), LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 9, 14), Set.of("CHANGE_DECISION"), true)).id();
    }

    private Seed seed() {
        long project = jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values(?,'Recovery','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id
                """, Long.class, "RECOVERY-" + System.nanoTime());
        long manager = user("recovery-manager");
        long outgoing = user("recovery-outgoing");
        long incoming = user("recovery-incoming");
        member(project, manager, "PROJECT_MANAGER");
        member(project, outgoing, "OUTGOING_OWNER");
        member(project, incoming, "INCOMING_OWNER");
        return new Seed(project, manager, outgoing, incoming);
    }

    private long user(String employeeNo) {
        return jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,credential_hash)
                values(?,?,?,?,?, 'x') returning id
                """, Long.class, organizationId, employeeNo, employeeNo, "Engineer", employeeNo + "@test");
    }

    private void member(long projectId, long userId, String role) {
        jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),current_date)",
                projectId, userId, role);
    }

    private record Seed(long projectId, long managerId, long outgoingId, long incomingId) {
    }
}
