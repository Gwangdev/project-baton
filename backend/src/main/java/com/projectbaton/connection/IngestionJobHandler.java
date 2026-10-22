package com.projectbaton.connection;

import com.projectbaton.jobs.JobLeaseService;
import com.projectbaton.jobs.JobQueueSaturatedException;
import com.projectbaton.project.ProjectAccessPolicy.ProjectNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class IngestionJobHandler {
    private final IngestionService ingestion;
    private final JobLeaseService jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int batchSize;

    public IngestionJobHandler(IngestionService ingestion, JobLeaseService jobs, JdbcTemplate jdbc, TransactionTemplate transactions,
                               @Value("${baton.ingestion.batch-size:100}") int batchSize) {
        this.ingestion = ingestion;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.batchSize = batchSize;
    }

    /**
     * 한 묶음을 수집하고 같은 트랜잭션 안에서 작업을 끝낸다. 그 사이에 lease를 잃었다면 다른 worker가 작업을 가진
     * 것이라, 묶음을 두 번 발행하거나 더 새로운 checkpoint 뒤에 발행하지 않도록 rollback한다.
     */
    public boolean execute(JobLeaseService.JobContext context) {
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                ingestion.ingest(context.targetId(), batchSize);
                if (jobs.complete(context)) return true;
                status.setRollbackOnly();
                return false;
            }));
        } catch (JobQueueSaturatedException saturated) {
            // README §6: 큐가 가득 차 있는 동안에는 checkpoint를 앞으로 옮기지 않는다. 작업은 RUNNING으로 남고, lease
            // 복구가 세 번의 시도 한도 안에서 다시 돌려준다.
            return false;
        } catch (IngestionService.InactiveSourceConnectionException inactive) {
            return fail(context, "CONNECTION_INACTIVE", false);
        } catch (ProjectNotFoundException missing) {
            return fail(context, "CONNECTION_NOT_FOUND", false);
        } catch (IngestionService.UnsupportedConnectorException unsupported) {
            return fail(context, "UNSUPPORTED_CONNECTOR", true);
        } catch (IngestionService.SourceVersionConflictException conflict) {
            return fail(context, "SOURCE_VERSION_CONFLICT", true);
        }
    }

    /** 일시 중지되거나 삭제된 연동은 수집에 실패한 것이 아니므로, 수집 오류만 연동을 FAILED로 표시한다. */
    private boolean fail(JobLeaseService.JobContext context, String failureCode, boolean collectionFault) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!jobs.fail(context, failureCode)) return false;
            if (collectionFault) {
                jdbc.update("""
                        update source_connections
                        set last_status='FAILED', last_failed_count=last_failed_count+1, last_message=?, version=version+1
                        where id=?
                        """, failureCode, context.targetId());
            }
            return true;
        }));
    }
}
