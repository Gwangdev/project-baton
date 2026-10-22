package com.projectbaton.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest @Testcontainers
class JobRecoveryIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);}
    @Autowired JdbcTemplate jdbc; @Autowired JobRepository jobs; @Autowired TransactionTemplate tx; @Autowired JobLeaseService leases;
    @BeforeEach void setup(){jdbc.update("delete from jobs");}
    @Test void businessRowAndJobRollbackTogether(){
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
            jdbc.update("insert into organizations(name) values(?)","rollback-job-org");
            jobs.enqueue("INDEX_RECORD",99,null,"rollback-job-key",OffsetDateTime.now().plusMinutes(5));
            throw new IllegalStateException("simulate process stop before commit");
        }));
        assertEquals(0,jdbc.queryForObject("select count(*) from organizations where name='rollback-job-org'",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from jobs where dedup_key='rollback-job-key'",Integer.class));
    }
    @Test void staleLeaseTokenCannotPublishAfterAnotherWorkerClaims(){
        jobs.enqueue("INDEX_RECORD",99,7,"lease-job-key",OffsetDateTime.now().plusMinutes(5));
        JobLeaseService.JobContext first=leases.claim("INDEX_RECORD").orElseThrow();
        jdbc.update("update jobs set status='RETRY_WAIT', available_at=now(), lease_token=null, lease_until=null where id=?",first.jobId());
        JobLeaseService.JobContext second=leases.claim("INDEX_RECORD").orElseThrow();
        assertEquals(false,leases.complete(first));
        assertEquals(true,leases.complete(second));
        assertEquals("SUCCEEDED",jdbc.queryForObject("select status from jobs where id=?",String.class,second.jobId()));
    }
    @Test void jobWithThreeAttemptsIsFailedInsteadOfClaimedAgain(){
        jobs.enqueue("INDEX_RECORD",99,null,"max-attempts-job-key",OffsetDateTime.now().plusMinutes(5));
        jdbc.update("update jobs set attempts=3 where dedup_key='max-attempts-job-key'");

        assertEquals(true,leases.claim("INDEX_RECORD").isEmpty());
        leases.sweep();
        assertEquals("FAILED",jdbc.queryForObject("select status from jobs where dedup_key='max-attempts-job-key'",String.class));
        assertEquals("MAX_ATTEMPTS_EXCEEDED",jdbc.queryForObject("select failure_code from jobs where dedup_key='max-attempts-job-key'",String.class));
    }
    @Test void expiredJobIsFailedInsteadOfRemainingPending(){
        jobs.enqueue("INDEX_RECORD",99,null,"expired-job-key",OffsetDateTime.now().minusSeconds(1));

        assertEquals(true,leases.claim("INDEX_RECORD").isEmpty());
        leases.sweep();
        assertEquals("FAILED",jdbc.queryForObject("select status from jobs where dedup_key='expired-job-key'",String.class));
        assertEquals("DEADLINE_EXCEEDED",jdbc.queryForObject("select failure_code from jobs where dedup_key='expired-job-key'",String.class));
    }
    @Test void enqueueRejectsWhenUnfinishedQueueReachesCapacity(){
        jdbc.update("""
                insert into jobs(id,kind,target_id,dedup_key,status,attempts,available_at,deadline_at)
                select ('00000000-0000-0000-0000-' || lpad(job_no::text,12,'0'))::uuid,
                       'INDEX_RECORD',job_no,'saturated-job-' || job_no,'PENDING',0,now(),now()+interval '5 minutes'
                from generate_series(1,50000) as job_no
                """);

        assertThrows(JobQueueSaturatedException.class,()->jobs.enqueue("INDEX_RECORD",99,null,"overflow-job-key",OffsetDateTime.now().plusMinutes(5)));
    }
    @Test void expiredThirdLeaseIsFailedInsteadOfBeingClaimedAgain(){
        jobs.enqueue("INDEX_RECORD",99,null,"expired-third-lease-key",OffsetDateTime.now().plusMinutes(5));
        JobLeaseService.JobContext first=leases.claim("INDEX_RECORD").orElseThrow();
        jdbc.update("update jobs set lease_until=now()-interval '1 second' where id=?",first.jobId());
        leases.sweep();
        JobLeaseService.JobContext second=leases.claim("INDEX_RECORD").orElseThrow();
        jdbc.update("update jobs set lease_until=now()-interval '1 second' where id=?",second.jobId());
        leases.sweep();
        JobLeaseService.JobContext third=leases.claim("INDEX_RECORD").orElseThrow();
        jdbc.update("update jobs set lease_until=now()-interval '1 second' where id=?",third.jobId());
        leases.sweep();

        assertEquals(true,leases.claim("INDEX_RECORD").isEmpty());
        assertEquals("FAILED",jdbc.queryForObject("select status from jobs where id=?",String.class,third.jobId()));
        assertEquals("MAX_ATTEMPTS_EXCEEDED",jdbc.queryForObject("select failure_code from jobs where id=?",String.class,third.jobId()));
    }
    // 예전에는 lease sweep이 claim마다 돌아서, 바쁜 runner 여섯이 가져가는 작업마다 update 다섯 개를 반복했다. 이제
    // dispatch tick마다 한 번 돌고, claim은 고르고 lease하기만 하며 시도를 다 쓴 작업은 가져가지 않는다.
    @Test void claimLeavesExpiredLeasesAndUsedUpAttemptsToTheSweep(){
        jobs.enqueue("INDEX_RECORD",99,null,"expired-lease-key",OffsetDateTime.now().plusMinutes(5));
        JobLeaseService.JobContext first=leases.claim("INDEX_RECORD").orElseThrow();
        jdbc.update("update jobs set lease_until=now()-interval '1 second' where id=?",first.jobId());
        jobs.enqueue("INDEX_RECORD",98,null,"used-up-key",OffsetDateTime.now().plusMinutes(5));
        jdbc.update("update jobs set attempts=3 where dedup_key='used-up-key'");

        assertEquals(true,leases.claim("INDEX_RECORD").isEmpty());
        assertEquals("RUNNING",jdbc.queryForObject("select status from jobs where id=?",String.class,first.jobId()));

        leases.sweep();
        assertEquals("FAILED",jdbc.queryForObject("select status from jobs where dedup_key='used-up-key'",String.class));
        assertEquals(first.jobId(),leases.claim("INDEX_RECORD").orElseThrow().jobId());
    }
    @Test void completionAfterDeadlineIsRejectedAndFailed(){
        jobs.enqueue("INDEX_RECORD",99,null,"expired-completion-key",OffsetDateTime.now().plusMinutes(5));
        JobLeaseService.JobContext context=leases.claim("INDEX_RECORD").orElseThrow();
        jdbc.update("update jobs set deadline_at=now()-interval '1 second' where id=?",context.jobId());

        assertEquals(false,leases.complete(context));
        assertEquals("FAILED",jdbc.queryForObject("select status from jobs where id=?",String.class,context.jobId()));
        assertEquals("DEADLINE_EXCEEDED",jdbc.queryForObject("select failure_code from jobs where id=?",String.class,context.jobId()));
    }
}
