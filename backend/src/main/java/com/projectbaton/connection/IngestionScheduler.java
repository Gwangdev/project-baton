package com.projectbaton.connection;

import com.projectbaton.jobs.JobQueueSaturatedException;
import com.projectbaton.jobs.JobRepository;
import com.projectbaton.jobs.DatabaseLane;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 활성 연동마다 INGEST 작업 하나를 넣어서 새 문서나 바뀐 문서가 사용자 조작 없이 초안 후보가 되게 한다(FR-04). 끝나지
 * 않은 INGEST 작업이 남은 연동은 건너뛰어 느린 원천에 작업이 쌓이지 않게 하고, 연동 행 잠금이 있어서 scheduler 실행
 * 두 개가 겹쳐도 이 확인이 안전하다.
 */
@Component
public class IngestionScheduler {
    private static final String UNFINISHED = "('PENDING','RUNNING','RETRY_WAIT')";

    private final JdbcTemplate jdbc;
    private final JobRepository jobs;
    private final TransactionTemplate transactions;

    public IngestionScheduler(JdbcTemplate jdbc, JobRepository jobs, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.transactions = transactions;
    }

    // 따로 정하지 않으면 늘 그랬듯 첫 실행은 기동 때다. 테이블을 직접 다루는 시험은 준비 도중에 scheduler가 돌지
    // 않도록 초기 지연을 둔다(TRUNCATE와 교착된 적이 있다).
    @Scheduled(fixedDelayString = "${baton.ingestion.schedule-delay:300000}", initialDelayString = "${baton.ingestion.schedule-initial-delay:0}")
    public void enqueueDue() {
        DatabaseLane.CONTROL.run(this::enqueueDueConnections);
    }

    private void enqueueDueConnections() {
        List<Long> due = jdbc.queryForList("""
                select connection.id from source_connections connection
                join projects project on project.id=connection.project_id
                where connection.status='ACTIVE' and project.is_active
                  and not exists(select 1 from jobs where kind='INGEST' and target_id=connection.id and status in %s)
                order by connection.id
                """.formatted(UNFINISHED), Long.class);
        for (long connectionId : due) {
            try {
                transactions.executeWithoutResult(status -> enqueue(connectionId));
            } catch (JobQueueSaturatedException saturated) {
                // README §6: 가득 찬 큐는 더 늘리지 않고 다음 주기에 다시 시도한다.
                return;
            }
        }
    }

    private void enqueue(long connectionId) {
        // 일부러 문장 두 개로 나눴다. 문장은 시작할 때의 snapshot으로 판단해서, 잠금과 끝나지 않은 작업 확인이 한
        // 문장일 때는 잠금을 기다린 실행이 기다리기 전에 본 것으로 판단했고, 첫 실행이 작업을 커밋한 뒤에 두 번째
        // 작업을 넣었다. 확인은 잠금을 쥔 뒤에 시작한다.
        Boolean active = jdbc.query("select status='ACTIVE' from source_connections where id=? for update",
                rs -> rs.next() ? rs.getBoolean(1) : null, connectionId);
        if (!Boolean.TRUE.equals(active)) {
            return;
        }
        Boolean unfinished = jdbc.queryForObject("select exists(select 1 from jobs where kind='INGEST' and target_id=? and status in %s)"
                .formatted(UNFINISHED), Boolean.class, connectionId);
        if (!Boolean.TRUE.equals(unfinished)) {
            jobs.enqueue("INGEST", connectionId, null, "ingest:" + connectionId + ":" + UUID.randomUUID(),
                    OffsetDateTime.now().plusMinutes(30));
        }
    }
}
