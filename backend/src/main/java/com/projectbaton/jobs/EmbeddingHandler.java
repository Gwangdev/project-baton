package com.projectbaton.jobs;

import com.projectbaton.qa.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기록 버전 하나의 확정된 제목과 요약을 임베딩한다. 원문 발췌, 개인 메모, 원문 본문은 임베딩하지 않는다(README §7).
 * 모델 호출은 트랜잭션 밖에서 하고 발행할 때 기록 버전을 다시 확인하므로, 그 사이에 바뀐 버전은 낡은 번호로 색인되지
 * 않고 자기 작업에 맡겨진다.
 */
@Component
public class EmbeddingHandler {
    static final int CHUNK_SIZE = 800;
    static final int CHUNK_OVERLAP = 100;
    static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final JobLeaseService jobs;
    private final EmbeddingClient client;
    private final TransactionTemplate transactions;

    public EmbeddingHandler(JdbcTemplate jdbc, JobLeaseService jobs, EmbeddingClient client, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.client = client;
        this.transactions = transactions;
    }

    public boolean execute(JobLeaseService.JobContext context) {
        Optional<Target> target = current(context.targetId());
        if (target.isEmpty() || indexed(target.get())) {
            return Boolean.TRUE.equals(transactions.execute(status -> jobs.complete(context)));
        }
        List<String> chunks = chunks(target.get().title() + "\n" + target.get().summary());
        List<float[]> vectors;
        try {
            vectors = client.embed(chunks);
        } catch (EmbeddingClient.EmbeddingUnavailableException unavailable) {
            if (attempts(context) < MAX_ATTEMPTS) return false;
            return fail(context, "EMBEDDING_UNAVAILABLE");
        }
        if (vectors.size() != chunks.size() || vectors.stream().anyMatch(vector -> vector.length != EmbeddingClient.DIMENSIONS)) {
            return fail(context, "EMBEDDING_INVALID");
        }
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Optional<Target> locked = current(target.get().recordId(), true);
            if (locked.isPresent() && locked.get().version() == target.get().version()) {
                for (int index = 0; index < chunks.size(); index++) {
                    jdbc.update("""
                            insert into record_embeddings(id,project_id,record_id,record_version,chunk_no,chunk_text,embedding_model,embedding)
                            values(?,?,?,?,?,?,?,cast(? as vector))
                            on conflict (record_id, record_version, chunk_no, embedding_model) do nothing
                            """, UUID.randomUUID(), target.get().projectId(), target.get().recordId(), target.get().version(), index,
                            chunks.get(index), client.modelId(), EmbeddingClient.literal(vectors.get(index)));
                }
            }
            if (jobs.complete(context)) return true;
            status.setRollbackOnly();
            return false;
        }));
    }

    static List<String> chunks(String text) {
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += CHUNK_SIZE - CHUNK_OVERLAP) {
            chunks.add(text.substring(start, Math.min(text.length(), start + CHUNK_SIZE)));
            if (start + CHUNK_SIZE >= text.length()) break;
        }
        return chunks;
    }

    private Optional<Target> current(long recordId) {
        return current(recordId, false);
    }

    private Optional<Target> current(long recordId, boolean lock) {
        return jdbc.query("""
                select id, project_id, version, title, summary from work_records
                where id=? and trust_status in ('CONFIRMED','CORRECTED')
                """ + (lock ? " for share" : ""), (rs, row) -> new Target(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), rs.getString(5)), recordId).stream().findFirst();
    }

    private boolean indexed(Target target) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from record_embeddings where record_id=? and record_version=? and embedding_model=?)
                """, Boolean.class, target.recordId(), target.version(), client.modelId()));
    }

    private boolean fail(JobLeaseService.JobContext context, String code) {
        return Boolean.TRUE.equals(transactions.execute(status -> jobs.fail(context, code)));
    }

    private int attempts(JobLeaseService.JobContext context) {
        Integer attempts = jdbc.queryForObject("select attempts from jobs where id=?", Integer.class, context.jobId());
        return attempts == null ? MAX_ATTEMPTS : attempts;
    }


    private record Target(long recordId, long projectId, long version, String title, String summary) { }
}
