package com.projectbaton.connection;

import com.projectbaton.jobs.JobLeaseService;
import com.projectbaton.record.RecordStructurer;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PENDING 원문을 AUTO_DRAFT 기록으로 바꾼다. 모델 호출은 트랜잭션 밖에서 하고(README §6) 짧은 claim과 발행 단계만
 * 잠금을 쥔다. 발행은 lease 아래에서 작업을 끝내서, 다른 시도가 넘겨받은 뒤에는 낡은 worker가 초안을 더하지 못한다.
 */
@Component
public class SourceStructuringHandler {
    /** README §6: 첫 시도를 포함해 모두 세 번. */
    static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final JobLeaseService jobs;
    private final RecordStructurer structurer;
    private final TransactionTemplate transactions;

    public SourceStructuringHandler(JdbcTemplate jdbc, JobLeaseService jobs, RecordStructurer structurer, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.structurer = structurer;
        this.transactions = transactions;
    }

    public boolean execute(JobLeaseService.JobContext context) {
        Optional<Source> source = transactions.execute(status -> begin(context.targetId()));
        if (source == null || source.isEmpty()) {
            // 다른 작업이 이미 구조화했거나 실패로 끝냈거나 원문이 사라져서 발행할 것이 없다.
            return Boolean.TRUE.equals(transactions.execute(status -> jobs.complete(context)));
        }
        List<RecordStructurer.DraftRecord> drafts;
        try {
            drafts = structurer.extract(source.get().document());
        } catch (RecordStructurer.StructuringException failure) {
            if (failure.retryable() && attempts(context) < MAX_ATTEMPTS) {
                // 작업을 RUNNING으로 둔다. lease 복구가 다음 시도에 넘긴다.
                return false;
            }
            return fail(context, failure.code());
        }
        return Boolean.TRUE.equals(transactions.execute(status -> publish(context, source.get(), drafts, status)));
    }

    /**
     * 최신 STRUCTURE 작업이 이 처리기 밖에서 FAILED로 끝난 원문(중단 뒤 lease나 시도 소진)은 이대로면 PENDING이나
     * PROCESSING에 영원히 남고, retrySourceProcessing은 FAILED 원문만 받는다.
     */
    public void releaseAbandonedSources() {
        jdbc.update("""
                update record_sources source
                set processing_status='FAILED', failure_code=coalesce(latest.failure_code, 'STRUCTURING_ABANDONED')
                from (select distinct on (target_id) target_id, status, failure_code
                      from jobs where kind='STRUCTURE' order by target_id, created_at desc, id desc) latest
                where latest.target_id=source.id and latest.status='FAILED'
                  and source.processing_status in ('PENDING','PROCESSING')
                  and not exists(select 1 from jobs pending where pending.kind='STRUCTURE' and pending.target_id=source.id
                                 and pending.status in ('PENDING','RUNNING','RETRY_WAIT'))
                """);
    }

    private Optional<Source> begin(long sourceId) {
        List<Source> rows = jdbc.query("""
                select id, project_id, source_body, source_author_name, source_occurred_on, external_version
                from record_sources where id=? and processing_status in ('PENDING','PROCESSING') for update
                """, (rs, row) -> new Source(rs.getLong(1), rs.getLong(2), rs.getString(6), new RecordStructurer.SourceDocument(
                rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class), List.of())), sourceId);
        if (rows.isEmpty()) return Optional.empty();
        Source source = rows.getFirst();
        jdbc.update("update record_sources set processing_status='PROCESSING', failure_code=null where id=?", sourceId);
        List<RecordStructurer.Member> members = jdbc.query("""
                select account.id, account.name from project_members member
                join users account on account.id=member.user_id
                join organizations organization on organization.id=account.organization_id
                where member.project_id=? and account.is_active and organization.is_active
                  and member.joined_on<=current_date and (member.left_on is null or current_date<member.left_on)
                order by account.id
                """, (rs, row) -> new RecordStructurer.Member(rs.getLong(1), rs.getString(2)), source.projectId());
        RecordStructurer.SourceDocument document = source.document();
        return Optional.of(new Source(source.id(), source.projectId(), source.version(),
                new RecordStructurer.SourceDocument(document.body(), document.authorName(), document.occurredOn(), members)));
    }

    private boolean publish(JobLeaseService.JobContext context, Source source, List<RecordStructurer.DraftRecord> drafts,
                            TransactionStatus status) {
        Long locked = jdbc.query("select id from record_sources where id=? and processing_status='PROCESSING' for update",
                rs -> rs.next() ? rs.getLong(1) : null, source.id());
        if (locked == null) return jobs.complete(context);
        // 수동 복구 초안이 (원문, 버전, 순번) 키를 함께 쓰므로 번호는 이미 있는 것 뒤에서 이어 간다.
        int index = jdbc.queryForObject("""
                select coalesce(max(extraction_index), -1) + 1 from work_records where record_source_id=? and source_version=?
                """, Integer.class, source.id(), source.version());
        for (RecordStructurer.DraftRecord draft : drafts) {
            jdbc.update("""
                    insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,
                                             author_name,owner_user_id,due_on,trust_status,source_version,extraction_index,search_text)
                    values('REC-'||nextval('work_records_id_seq'),?,?,cast(? as record_type),?,?,?,?,?,?,?,'AUTO_DRAFT',?,?,?)
                    """, source.projectId(), source.id(), draft.recordType(), draft.title(), draft.summary(), draft.originalExcerpt(),
                    draft.occurredOn(), source.document().authorName() == null ? "알 수 없는 작성자" : source.document().authorName(),
                    draft.ownerUserId(), draft.dueOn(), source.version(), index++, draft.summary());
        }
        jdbc.update("update record_sources set processing_status='SUCCEEDED', failure_code=null where id=?", source.id());
        if (jobs.complete(context)) return true;
        status.setRollbackOnly();
        return false;
    }

    private boolean fail(JobLeaseService.JobContext context, String code) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!jobs.fail(context, code)) return false;
            jdbc.update("""
                    update record_sources set processing_status='FAILED', failure_code=?
                    where id=? and processing_status in ('PENDING','PROCESSING')
                    """, code, context.targetId());
            return true;
        }));
    }

    private int attempts(JobLeaseService.JobContext context) {
        Integer attempts = jdbc.queryForObject("select attempts from jobs where id=?", Integer.class, context.jobId());
        return attempts == null ? MAX_ATTEMPTS : attempts;
    }

    private record Source(long id, long projectId, String version, RecordStructurer.SourceDocument document) { }
}
