package com.projectbaton.jobs;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobLeaseService {
    private final JdbcTemplate jdbc;

    public JobLeaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 한 종류에서 가장 오래된 처리 대상 작업을 lease한다. lease sweep은 여기 들어가지 않지만({@link #sweep()} 참고)
     * 시도 횟수와 deadline 조건은 들어가서, 다음 sweep을 기다리는 동안에도 한도를 넘긴 작업을 가져가지 못한다. 종류
     * 없이 가져가는 claim도 있었지만 시험만 불렀고 모든 worker는 자기 종류의 작업만 가져가서, lease하는 두 번째
     * 방법으로 남기지 않고 지웠다.
     */
    @Transactional
    public Optional<JobContext> claim(String kind) {
        UUID token = UUID.randomUUID();
        List<JobContext> rows = jdbc.query("""
                select id,target_id,generation,deadline_at from jobs
                where status in ('PENDING','RETRY_WAIT') and available_at<=now() and deadline_at>now() and attempts<3
                    and kind=?
                order by available_at,id for update skip locked limit 1
                """, (row, ignored) -> new JobContext(
                row.getObject("id", UUID.class),
                row.getLong("target_id"),
                row.getObject("generation", Integer.class),
                token,
                row.getObject("deadline_at", OffsetDateTime.class)), kind);
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        JobContext context = rows.getFirst();
        jdbc.update("""
                update jobs set status='RUNNING',attempts=attempts+1,lease_token=?,
                    lease_until=now()+interval '30 seconds',updated_at=now()
                where id=?
                """, token, context.jobId());
        return Optional.of(context);
    }

    /**
     * deadline을 넘겼거나 시도를 다 쓴 작업을 실패로 처리하고, 만료된 lease를 큐에 돌려준다. 예전에는 claim마다
     * 돌아서 runner가 밀린 작업을 비우는 동안 작업마다 update 다섯 개를 반복했다. 이제 config/JobHousekeeping이
     * dispatch tick마다 한 번 돌리므로, 되찾은 lease는 최대 한 tick 더 기다린다(README §6).
     */
    @Transactional
    public void sweep() {
        failExpiredRunningJobs();
        failExhaustedRunningJobs();
        recoverExpiredLeases();
        failExpiredQueuedJobs();
        failExhaustedQueuedJobs();
    }

    @Transactional
    public boolean complete(JobContext context) {
        if (jdbc.update("""
                update jobs set status='SUCCEEDED',lease_token=null,lease_until=null,updated_at=now()
                where id=? and status='RUNNING' and lease_token=?
                    and generation is not distinct from ? and deadline_at>now()
                """, context.jobId(), context.leaseToken(), context.generation()) == 1) {
            return true;
        }
        jdbc.update("""
                update jobs set status='FAILED',failure_code='DEADLINE_EXCEEDED',
                    lease_token=null,lease_until=null,updated_at=now()
                where id=? and status='RUNNING' and lease_token=?
                    and generation is not distinct from ? and deadline_at<=now()
                """, context.jobId(), context.leaseToken(), context.generation());
        return false;
    }

    public boolean fail(JobContext context, String failureCode) {
        return jdbc.update("""
                update jobs set status='FAILED',failure_code=?,lease_token=null,lease_until=null,updated_at=now()
                where id=? and status='RUNNING' and lease_token=?
                    and generation is not distinct from ?
                """, failureCode, context.jobId(), context.leaseToken(), context.generation()) == 1;
    }

    private void failExpiredRunningJobs() {
        jdbc.update("""
                update jobs set status='FAILED',failure_code='DEADLINE_EXCEEDED',
                    lease_token=null,lease_until=null,updated_at=now()
                where status='RUNNING' and lease_until<=now() and deadline_at<=now()
                """);
    }

    private void failExhaustedRunningJobs() {
        jdbc.update("""
                update jobs set status='FAILED',failure_code='MAX_ATTEMPTS_EXCEEDED',
                    lease_token=null,lease_until=null,updated_at=now()
                where status='RUNNING' and lease_until<=now() and attempts>=3
                """);
    }

    private void recoverExpiredLeases() {
        jdbc.update("""
                update jobs set status='RETRY_WAIT',available_at=now(),lease_token=null,
                    lease_until=null,updated_at=now()
                where status='RUNNING' and lease_until<=now() and attempts<3 and deadline_at>now()
                """);
    }

    private void failExpiredQueuedJobs() {
        jdbc.update("""
                update jobs set status='FAILED',failure_code='DEADLINE_EXCEEDED',
                    lease_token=null,lease_until=null,updated_at=now()
                where status in ('PENDING','RETRY_WAIT') and deadline_at<=now()
                """);
    }

    private void failExhaustedQueuedJobs() {
        jdbc.update("""
                update jobs set status='FAILED',failure_code='MAX_ATTEMPTS_EXCEEDED',
                    lease_token=null,lease_until=null,updated_at=now()
                where status in ('PENDING','RETRY_WAIT') and attempts>=3
                """);
    }

    public record JobContext(UUID jobId, long targetId, Integer generation, UUID leaseToken,
                             OffsetDateTime deadline) {
    }
}
