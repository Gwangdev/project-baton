package com.projectbaton.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;

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

/** README §6: 실행 중인 작업은 worker가 돌리는 동안 10초 heartbeat로 30초 lease를 유지한다. */
@SpringBootTest @Testcontainers
class LeaseHeartbeatIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JobRepository jobs;
    @Autowired JobLeaseService leases;
    @Autowired LeaseHeartbeat heartbeat;

    @BeforeEach void setup() { jdbc.update("delete from jobs"); }

    @Test
    void aHeldJobKeepsItsLeaseAndLosesTheHeartbeatWhenItsWorkReturns() {
        JobLeaseService.JobContext context = claim("heartbeat-held", OffsetDateTime.now().plusMinutes(5));
        shortenLease(context);

        heartbeat.hold(context, () -> {
            heartbeat.extend();
            assertTrue(leaseSecondsLeft(context) > 25, "lease renewed to 30 seconds while the job runs");
        });

        shortenLease(context);
        heartbeat.extend();
        assertTrue(leaseSecondsLeft(context) < 5, "a job whose work returned is no longer renewed");
    }

    @Test
    void theHeartbeatStopsAtTheDeadlineSoAHungWorkerCannotHoldAJobForever() {
        JobLeaseService.JobContext context = claim("heartbeat-deadline", OffsetDateTime.now().plusMinutes(5));
        shortenLease(context);
        jdbc.update("update jobs set deadline_at=now()-interval '1 second' where id=?", context.jobId());

        heartbeat.hold(context, () -> {
            heartbeat.extend();
            assertTrue(leaseSecondsLeft(context) < 5);
        });
    }

    @Test
    void aLeaseTakenOverByAnotherAttemptIsNotRenewedForTheStaleWorker() {
        JobLeaseService.JobContext stale = claim("heartbeat-stale", OffsetDateTime.now().plusMinutes(5));
        jdbc.update("update jobs set lease_token=gen_random_uuid(), lease_until=now()+interval '2 seconds' where id=?",
                stale.jobId());
        OffsetDateTime before = leaseUntil(stale);

        heartbeat.hold(stale, heartbeat::extend);

        assertEquals(before, leaseUntil(stale));
    }

    @Test
    void aHandlerFailureStillReleasesTheHeartbeat() {
        JobLeaseService.JobContext context = claim("heartbeat-failure", OffsetDateTime.now().plusMinutes(5));
        try {
            heartbeat.hold(context, () -> { throw new IllegalStateException("handler bug"); });
        } catch (IllegalStateException expected) {
            // worker의 slot이 알린다. heartbeat가 버려진 작업을 살려 두면 안 된다.
        }
        shortenLease(context);
        heartbeat.extend();
        assertTrue(leaseSecondsLeft(context) < 5);
    }

    private JobLeaseService.JobContext claim(String key, OffsetDateTime deadline) {
        jobs.enqueue("INDEX_RECORD", 99, null, key, deadline);
        return leases.claim("INDEX_RECORD").orElseThrow();
    }

    private void shortenLease(JobLeaseService.JobContext context) {
        jdbc.update("update jobs set lease_until=now()+interval '2 seconds' where id=?", context.jobId());
    }

    private double leaseSecondsLeft(JobLeaseService.JobContext context) {
        return jdbc.queryForObject("select extract(epoch from lease_until-now()) from jobs where id=?", Double.class,
                context.jobId());
    }

    private OffsetDateTime leaseUntil(JobLeaseService.JobContext context) {
        return jdbc.queryForObject("select lease_until from jobs where id=?", OffsetDateTime.class, context.jobId());
    }
}
