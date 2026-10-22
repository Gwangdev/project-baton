package com.projectbaton.qa;

import com.projectbaton.jobs.DatabaseLane;
import com.projectbaton.shared.IdempotencyService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 답하던 프로세스가 죽은 질문을 닫는다(C01 4단계). QA 작업의 deadline이 lease 끝과 같아서 둘 다 지난 뒤에만 작업을
 * 실패로 처리하고, 살아 있는 요청의 lease는 넘겨받지 않는다. FAILED 답변과 재실행용 결과를 함께 커밋하고, 고유한
 * reply_to_id가 늦게 도착한 마무리가 두 번째 답변을 더하지 못하게 막는다.
 */
@Service
public class QaRecoveryService {
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final QaHistoryRepository history;
    private final IdempotencyService idempotency;

    public QaRecoveryService(JdbcTemplate jdbc, QaHistoryRepository history, IdempotencyService idempotency,
                             TransactionTemplate transactions) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.history = history;
        this.idempotency = idempotency;
    }

    /** 정리 작업이라 control 연결을 쓴다. lane은 트랜잭션이 연결을 고르기 전에 정한다. */
    @Scheduled(fixedDelayString = "${baton.qa.recovery-delay:10000}")
    public void recover() {
        DatabaseLane.CONTROL.run(() -> transactions.executeWithoutResult(status -> recoverInTransaction()));
    }

    private void recoverInTransaction() {
        jdbc.update("""
                update jobs set status='FAILED', failure_code='DEADLINE_EXCEEDED', lease_token=null, lease_until=null, updated_at=now()
                where kind='QA' and status in ('PENDING','RUNNING','RETRY_WAIT') and deadline_at<=now()
                  and (lease_until is null or lease_until<=now())
                """);
        List<Orphan> orphans = jdbc.query("""
                select question.id, question.handover_id, question.generation_id, question.session_user_id, job.failure_code
                from qa_messages question
                join jobs job on job.kind='QA' and job.target_id=question.id
                where question.role='USER' and job.status='FAILED'
                  and not exists(select 1 from qa_messages answer where answer.reply_to_id=question.id)
                order by question.id
                limit 100
                for update of question skip locked
                """, (rs, row) -> new Orphan(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)));
        for (Orphan orphan : orphans) {
            List<Long> inserted = jdbc.queryForList("""
                    insert into qa_messages(handover_id,generation_id,session_user_id,role,content,answer_status,reply_to_id,failure_code)
                    values(?,?,?,'ASSISTANT',?,'FAILED',?,?)
                    on conflict (reply_to_id) do nothing
                    returning id
                    """, Long.class, orphan.handoverId(), orphan.generationId(), orphan.actorId(), QuestionService.FAILED_TEXT,
                    orphan.questionId(), orphan.failureCode() == null ? "DEADLINE_EXCEEDED" : orphan.failureCode());
            if (inserted.isEmpty()) continue;
            String answer = history.answer(inserted.getFirst(), orphan.actorId()).toString();
            idempotency.completeTarget(orphan.actorId(), QuestionService.METHOD, QuestionService.path(orphan.handoverId()),
                    orphan.questionId(), 201, answer);
        }
    }

    private record Orphan(long questionId, long handoverId, long generationId, long actorId, String failureCode) { }
}
