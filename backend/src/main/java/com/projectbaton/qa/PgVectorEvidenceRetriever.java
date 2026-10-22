package com.projectbaton.qa;

import com.projectbaton.jobs.DatabaseLane;
import com.projectbaton.project.ProjectAccessPolicy;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 질문한 회차의 발행된 snapshot만 검색한다. 벡터 검색 결과는 기록 버전이 snapshot과 같은 임베딩에서만 오므로 다른
 * 버전으로 만든 색인은 쓰지 않는다. 그런 임베딩이 없는 snapshot 기록(색인 지연, 실패한 EMBED 작업)도 정규화한
 * 키워드로 검색하고, 임베딩 모델이 멈추면 모든 snapshot 기록을 키워드로 검색한다. README §7이 필터 조건에서 recall이
 * 떨어질 수 있는 HNSW를 넣기 전에 프로젝트 필터 아래의 정확 검색을 먼저 재라고 해서, ANN 색인 없이 정확 검색을 한다.
 */
@Service
public class PgVectorEvidenceRetriever implements EvidenceRetriever {
    static final int VECTOR_CANDIDATES = 40;

    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;
    private final EmbeddingClient embeddings;
    private final double maxDistance;

    public PgVectorEvidenceRetriever(JdbcTemplate jdbc, ProjectAccessPolicy projects, EmbeddingClient embeddings,
                                     @Value("${baton.retrieval.max-cosine-distance:0.8}") double maxDistance) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.embeddings = embeddings;
        this.maxDistance = maxDistance;
    }

    /**
     * 트랜잭션 없이 돈다. 질문은 권한 확인과 검색 사이에서 임베딩하는데, 그 둘레에 트랜잭션을 두면 임베딩 모델이
     * 걸리는 시간 내내 pool 연결을 쥐게 된다(C01, README §4.2). 문장마다 바뀌지 않는 한 회차의 발행 snapshot을
     * 읽으므로 읽기에도 공통 트랜잭션이 필요 없다.
     */
    @Override
    public List<EvidenceChunk> retrieve(EvidenceQuery query) {
        // 근거 검색은 AI 작업이라 문장이 worker pool을 써서, 느린 검색이 API 연결을 쥐지 못한다.
        return DatabaseLane.WORKER.call(() -> search(query));
    }

    private List<EvidenceChunk> search(EvidenceQuery query) {
        authorize(query);
        int limit = Math.max(1, Math.min(query.limit(), MAX_EVIDENCE));
        List<EvidenceChunk> found = new ArrayList<>();
        boolean vectorAvailable = true;
        try {
            float[] question = embeddings.embed(List.of(query.question())).getFirst();
            jdbc.query("""
                    select embedding.id::text, snapshot.id, embedding.chunk_text, record.code,
                           embedding.embedding <=> cast(? as vector) as distance
                    from handover_snapshot_records snapshot
                    join handover_generations generation on generation.id=snapshot.generation_id
                    join record_embeddings embedding on embedding.record_id=snapshot.work_record_id
                         and embedding.record_version=snapshot.record_version and embedding.project_id=snapshot.project_id
                         and embedding.embedding_model=?
                    join work_records record on record.id=snapshot.work_record_id
                    where generation.handover_id=? and generation.generation=? and snapshot.project_id=?
                    order by distance, embedding.id
                    limit ?
                    """, rs -> {
                double distance = rs.getDouble(5);
                if (!Double.isNaN(distance) && distance <= maxDistance) {
                    found.add(new EvidenceChunk(rs.getLong(2), rs.getString(1), rs.getString(3), rs.getString(4)));
                }
            }, EmbeddingClient.literal(question), embeddings.modelId(), query.handoverId(), query.generation(), query.projectId(), VECTOR_CANDIDATES);
        } catch (EmbeddingClient.EmbeddingUnavailableException unavailable) {
            vectorAvailable = false;
        }
        for (EvidenceChunk chunk : keyword(query, !vectorAvailable)) {
            if (found.size() >= limit) break;
            found.add(chunk);
        }
        return found.size() > limit ? List.copyOf(found.subList(0, limit)) : List.copyOf(found);
    }

    private void authorize(EvidenceQuery query) {
        Boolean allowed = jdbc.query("""
                select handover.incoming_owner_id=? and generation.published_at is not null
                from handovers handover
                join handover_generations generation on generation.handover_id=handover.id and generation.generation=handover.generation
                where handover.id=? and handover.project_id=? and handover.generation=?
                """, rs -> rs.next() ? rs.getBoolean(1) : null, query.actorId(), query.handoverId(), query.projectId(), query.generation());
        if (!Boolean.TRUE.equals(allowed)) throw new EvidenceAccessDeniedException();
        try {
            projects.requireMember(query.actorId(), query.projectId());
        } catch (ProjectAccessPolicy.ProjectNotFoundException notMember) {
            throw new EvidenceAccessDeniedException();
        }
    }

    private List<EvidenceChunk> keyword(EvidenceQuery query, boolean allRecords) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : normalize(query.question()).split("[^\\p{L}\\p{N}]+")) {
            if (term.length() >= 2) terms.add(term);
        }
        if (terms.isEmpty()) return List.of();
        List<Scored> scored = new ArrayList<>();
        jdbc.query("""
                select snapshot.id, record.code,
                       coalesce(snapshot.content_json->>'title','') || E'\\n' || coalesce(snapshot.content_json->>'summary','')
                from handover_snapshot_records snapshot
                join handover_generations generation on generation.id=snapshot.generation_id
                join work_records record on record.id=snapshot.work_record_id
                where generation.handover_id=? and generation.generation=? and snapshot.project_id=?
                  and (? or not exists(select 1 from record_embeddings embedding
                                       where embedding.record_id=snapshot.work_record_id and embedding.record_version=snapshot.record_version
                                         and embedding.embedding_model=?))
                """, rs -> {
            String text = rs.getString(3);
            String normalized = normalize(text);
            long score = terms.stream().filter(normalized::contains).count();
            if (score > 0) scored.add(new Scored(score, new EvidenceChunk(rs.getLong(1), "snapshot:" + rs.getLong(1), text, rs.getString(2))));
        }, query.handoverId(), query.generation(), query.projectId(), allRecords, embeddings.modelId());
        scored.sort(Comparator.comparingLong(Scored::score).reversed().thenComparingLong(item -> item.chunk().snapshotRecordId()));
        return scored.stream().map(Scored::chunk).toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }


    private record Scored(long score, EvidenceChunk chunk) { }
}
