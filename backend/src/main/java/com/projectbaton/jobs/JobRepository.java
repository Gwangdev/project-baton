package com.projectbaton.jobs;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JobRepository {
    private static final int MAX_UNFINISHED_JOBS = 50_000;
    private static final long QUEUE_CAPACITY_LOCK = 50_001L;
    private static final int RETENTION_BATCH = 5_000;

    private final JdbcTemplate jdbc;
    public JobRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 해당 업무 상태를 저장하는 트랜잭션에서 부른다. */
    @Transactional
    public UUID enqueue(String kind, long targetId, Integer generation, String dedupKey, OffsetDateTime deadline) {
        requireCapacity();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into jobs(id,kind,target_id,generation,dedup_key,status,available_at,deadline_at)
                values(?,?,?,?,?,'PENDING',now(),?)
                """, id, kind, targetId, generation, dedupKey, deadline);
        return id;
    }

    /**
     * 작업을 큐에 넣어 worker에게 맡기지 않고, 호출한 요청이 이미 lease한 상태로 시작한다. Q&A는 검색과 모델 호출을
     * 요청 스레드에서 하고(C01), 작업은 그 프로세스가 죽었을 때 복구가 질문을 닫을 수 있게 하려고 둔다. deadline이
     * lease 끝과 같아서 lease 복구가 살아 있는 요청의 작업을 다른 곳에 넘기는 일은 없다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JobLeaseService.JobContext startLeased(String kind, long targetId, Integer generation, String dedupKey, Duration lease) {
        requireCapacity();
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        String interval = lease.toSeconds() + " seconds";
        OffsetDateTime deadline = jdbc.queryForObject("""
                insert into jobs(id,kind,target_id,generation,dedup_key,status,attempts,available_at,deadline_at,lease_token,lease_until)
                values(?,?,?,?,?,'RUNNING',1,now(),now()+cast(? as interval),?,now()+cast(? as interval))
                returning deadline_at
                """, OffsetDateTime.class, id, kind, targetId, generation, dedupKey, interval, token, interval);
        return new JobLeaseService.JobContext(id, targetId, generation, token, deadline);
    }

    /**
     * 마지막 갱신 기준으로 7일 지난 성공 작업과 30일 지난 실패 작업을 묶음 단위로 지운다. 묶음으로 나누어 한 문장이
     * 테이블의 큰 부분을 잠그지 않게 한다. 끝나지 않은 작업은 건드리지 않는다.
     */
    public void deleteFinished() {
        int deleted;
        do {
            deleted = jdbc.update("""
                    delete from jobs where id in (
                        select id from jobs
                        where (status='SUCCEEDED' and updated_at < now() - interval '7 days')
                           or (status='FAILED' and updated_at < now() - interval '30 days')
                        limit ?)
                    """, RETENTION_BATCH);
        } while (deleted == RETENTION_BATCH);
    }

    private void requireCapacity() {
        // 이 파일의 모든 문장처럼 SQL 문자열을 값으로 조립하지 않으려고 이어 붙이지 않고 bind한다.
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, QUEUE_CAPACITY_LOCK);
        Integer unfinished = jdbc.queryForObject("""
                select count(*) from jobs
                where status in ('PENDING','RUNNING','RETRY_WAIT')
                """, Integer.class);
        if (unfinished >= MAX_UNFINISHED_JOBS) {
            throw new JobQueueSaturatedException();
        }
    }
}
