package com.projectbaton.qa;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.projectbaton.handover.HandoverRequestService;
import com.projectbaton.jobs.JobLeaseService;
import com.projectbaton.jobs.JobRepository;
import com.projectbaton.project.ProjectAccessPolicy;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.JsonIds;
import com.projectbaton.shared.RequestTrace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * C01이 정한 세 단계의 근거 기반 Q&A. 짧은 접수 트랜잭션이 질문, lease한 QA 작업, IN_PROGRESS 멱등 행을 커밋하고,
 * 검색과 모델 호출은 트랜잭션 밖에서 돌고, 짧은 마무리 트랜잭션이 lease와 질문자의 접근을 다시 확인한 뒤 답변, 인용,
 * 재실행용 결과를 함께 커밋한다. 권한은 스레드를 넘어 가져온 보안 context가 아니라 행위자 id로 다시 평가한다.
 */
@Service
public class QuestionService {
    static final int MAX_QUESTION = 2_000;
    /** README §7은 답변마다 근거 12,000자를 제안한다. */
    static final int MAX_CONTEXT = 12_000;
    static final int MAX_PER_MINUTE = 6;
    /** 한 사람의 질문 접수에 쓰는 advisory lock 분류. 두 번째 키는 사용자 ID의 해시다. */
    static final int RATE_LIMIT_LOCK = 0x51410001;
    static final Duration LEASE = Duration.ofSeconds(30);
    static final String METHOD = "POST";
    static final String FAILED_TEXT = "답변을 만들지 못했다. 잠시 후 다시 질문해 주세요.";
    static final String NO_EVIDENCE_TEXT = "확정된 인수인계 기록에서 근거를 찾지 못했다. 추측 답변은 만들지 않는다.";

    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;
    private final IdempotencyService idempotency;
    private final JobRepository jobs;
    private final JobLeaseService leases;
    private final EvidenceRetriever retriever;
    private final GroundedAnswerGenerator generator;
    private final CitationValidator citations;
    private final QaHistoryRepository history;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final QaExecutors executors;
    private final Duration modelTimeout;

    public QuestionService(JdbcTemplate jdbc, ProjectAccessPolicy projects, IdempotencyService idempotency, JobRepository jobs,
                           JobLeaseService leases, EvidenceRetriever retriever, GroundedAnswerGenerator generator,
                           CitationValidator citations, QaHistoryRepository history, TransactionTemplate transactions, ObjectMapper json,
                           QaExecutors executors,
                           @Value("${baton.qa.model-timeout:20s}") Duration modelTimeout) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.idempotency = idempotency;
        this.jobs = jobs;
        this.leases = leases;
        this.retriever = retriever;
        this.generator = generator;
        this.citations = citations;
        this.history = history;
        this.transactions = transactions;
        this.json = json;
        this.executors = executors;
        this.modelTimeout = modelTimeout;
    }

    /** 접수 단계. 입력과 접근 실패는 키를 잡기 전에 일어나서 키를 쓰지 않는다. */
    public Accepted accept(long actorId, long handoverId, UUID key, String question) {
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION) throw new InvalidQuestionException();
        String path = path(handoverId);
        String body = json.createObjectNode().put("question", question).toString();
        return transactions.execute(status -> {
            Handover handover = authorize(actorId, handoverId);
            IdempotencyService.Reservation reservation = idempotency.reserve(new IdempotencyService.Request(actorId, METHOD, path, key, body, () -> { }));
            if (reservation.replay() != null) return new Accepted.Replay(read(reservation.replay().responseJson()));
            if (!handover.answerable()) {
                throw new HandoverRequestService.HandoverConflictException("Questions need a published handover package.");
            }
            rateLimit(actorId);
            long questionId = jdbc.queryForObject("""
                    insert into qa_messages(handover_id,generation_id,session_user_id,role,content) values(?,?,?,'USER',?)
                    returning id
                    """, Long.class, handoverId, handover.generationId(), actorId, question);
            JobLeaseService.JobContext job = jobs.startLeased("QA", questionId, handover.generation(), "qa:" + questionId, LEASE);
            idempotency.attachTarget(actorId, METHOD, path, key, questionId);
            return new Accepted.Fresh(new Context(actorId, handoverId, handover.projectId(), handover.generation(), handover.generationId(),
                    questionId, question, path, job, RequestTrace.currentOrNew()));
        });
    }

    /**
     * 새 질문의 검색, 생성, 마무리. 커밋한 QaAnswer(FAILED 포함)를 돌려주고, 복구가 이미 이 질문을 닫아 커밋할 것이
     * 없으면 null을 돌려준다.
     */
    public JsonNode answer(Accepted.Fresh fresh, AnswerSink sink) {
        Context context = fresh.context();
        Sink events = new Sink(sink);
        ObjectNode meta = json.createObjectNode().put("questionId", context.questionId()).put("generation", context.generation())
                .put("traceId", context.traceId());
        events.send("meta", meta);
        if (events.closed()) return finish(context, events, Outcome.failed("CLIENT_DISCONNECTED", false, false));

        List<EvidenceRetriever.EvidenceChunk> chunks;
        try {
            chunks = retriever.retrieve(new EvidenceRetriever.EvidenceQuery(context.actorId(), context.projectId(), context.handoverId(),
                    context.generation(), context.question(), EvidenceRetriever.MAX_EVIDENCE));
        } catch (EvidenceRetriever.EvidenceAccessDeniedException revoked) {
            return finish(context, events, Outcome.failed("ACCESS_REVOKED", false, false));
        }
        Evidence evidence = evidence(context, chunks);
        events.send("evidence", json.createObjectNode().set("citations", history.candidates(evidence.snapshotIds(), context.actorId())));
        if (events.closed()) return finish(context, events, Outcome.failed("CLIENT_DISCONNECTED", false, false));
        if (evidence.blocks().isEmpty()) return finish(context, events, Outcome.noEvidence(false));

        GroundedAnswerGenerator.Generated generated;
        List<String> cited;
        try {
            generated = generate(context, evidence);
            cited = citations.validate(generated, evidence.labels().keySet());
        } catch (GroundedAnswerGenerator.GenerationException failure) {
            return finish(context, events, Outcome.failed(failure.code(), failure.retryable(), true));
        }
        if (cited.isEmpty()) return finish(context, events, Outcome.noEvidence(true));

        for (String sentence : sentences(generated.answer())) {
            // 배정을 거두면 다음 질문뿐 아니라 진행 중인 stream도 멈춰야 하므로, 조각마다 접근을 확인한다.
            if (!stillAllowed(context)) return finish(context, events, Outcome.failed("ACCESS_REVOKED", false, true));
            events.send("delta", json.createObjectNode().put("text", sentence));
            if (events.closed()) return finish(context, events, Outcome.failed("CLIENT_DISCONNECTED", false, true));
        }
        List<Long> citedSnapshots = cited.stream().map(evidence.labels()::get).distinct().toList();
        return finish(context, events, Outcome.answered(generated.answer(), citedSnapshots));
    }

    /** 질문에 이미 저장된 답변. 예를 들어 이 요청이 lease를 잃은 뒤 복구가 저장한 것이다. */
    public JsonNode storedAnswer(Context context) {
        List<Long> ids = jdbc.queryForList("select id from qa_messages where reply_to_id=? and session_user_id=?", Long.class,
                context.questionId(), context.actorId());
        if (ids.isEmpty()) throw new IllegalStateException("Question " + context.questionId() + " has no stored answer");
        return history.answer(ids.getFirst(), context.actorId());
    }

    public History history(long actorId, long handoverId, int limit, int offset, String cursor) {
        authorize(actorId, handoverId);
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10_000 || (cursor != null && offset != 0)) throw new InvalidQuestionException();
        Long after = cursor == null ? null : decodeCursor(cursor);
        List<ObjectNode> items = history.page(handoverId, actorId, after, limit, offset);
        String next = items.size() == limit ? encodeCursor(items.getLast().get("id").longValue()) : null;
        return new History(items, history.count(handoverId, actorId), limit, offset, next);
    }

    private JsonNode finish(Context context, Sink events, Outcome outcome) {
        Outcome[] effectiveOutcome = {outcome};
        JsonNode committed = transactions.execute(status -> {
            Outcome effective = outcome;
            // 발행한 답변은 여전히 읽을 수 있는 사람의 것이어야 하므로, 마무리 단계에서 접근을 다시 확인한다(C01).
            if ("ANSWERED".equals(outcome.status()) && !stillAllowed(context)) effective = Outcome.failed("ACCESS_REVOKED", false, true);
            effectiveOutcome[0] = effective;
            Long assistantId = insertAnswer(context, effective);
            if (assistantId == null) {
                status.setRollbackOnly();
                return null;
            }
            for (Long snapshotId : effective.snapshotIds()) {
                jdbc.update("""
                        insert into qa_citations(qa_message_id,work_record_id,snapshot_record_id,trust_status_at_answer,is_primary)
                        select ?, snapshot.work_record_id, snapshot.id, snapshot.trust_status, true
                        from handover_snapshot_records snapshot where snapshot.id=?
                        """, assistantId, snapshotId);
            }
            ObjectNode answer = history.answer(assistantId, context.actorId());
            if (!idempotency.completeTarget(context.actorId(), METHOD, context.path(), context.questionId(), 201, answer.toString())
                    || !leases.complete(context.job())) {
                status.setRollbackOnly();
                return null;
            }
            return answer;
        });
        if (committed == null) {
            events.send("error", error("ANSWER_DISCARDED", false, context));
        } else if ("FAILED".equals(committed.get("answerStatus").stringValue())) {
            events.send("error", error(effectiveOutcome[0].failureCode(), effectiveOutcome[0].retryable(), context));
        } else {
            events.send("done", json.createObjectNode().set("answer", committed));
        }
        return committed;
    }

    private Long insertAnswer(Context context, Outcome outcome) {
        boolean modelUsed = outcome.modelCalled();
        String contacts = "NO_EVIDENCE".equals(outcome.status()) ? contacts(context) : null;
        List<Long> ids = jdbc.queryForList("""
                insert into qa_messages(handover_id,generation_id,session_user_id,role,content,answer_status,suggested_contacts,
                                        reply_to_id,failure_code,model_id,prompt_version)
                values(?,?,?,'ASSISTANT',?,cast(? as qa_answer_status),?,?,?,?,?)
                on conflict (reply_to_id) do nothing
                returning id
                """, Long.class, context.handoverId(), context.generationId(), context.actorId(), outcome.content(), outcome.status(),
                contacts, context.questionId(), outcome.failureCode(), modelUsed ? generator.modelId() : null,
                modelUsed ? generator.promptVersion() : null);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /** 대신 답할 수 있는 사람(지정된 기존 담당자와 현재 관리자)을 알려 준다. */
    private String contacts(Context context) {
        List<String> names = jdbc.queryForList("""
                select contact from (
                    select 1 as rank, account.name || ' (전임 담당자)' as contact from handovers handover
                    join users account on account.id=handover.outgoing_owner_id where handover.id=?
                    union all
                    select 2, account.name || ' (PM)' from project_members member join users account on account.id=member.user_id
                    where member.project_id=? and member.project_role='PROJECT_MANAGER' and account.is_active
                      and member.joined_on<=current_date and (member.left_on is null or current_date<member.left_on)
                ) contacts order by rank, contact
                """, String.class, context.handoverId(), context.projectId());
        String joined = String.join(", ", names);
        return joined.length() > 300 ? joined.substring(0, 300) : joined;
    }

    private GroundedAnswerGenerator.Generated generate(Context context, Evidence evidence) {
        // CompletableFuture.cancel은 실행 중인 호출을 interrupt하지 않아서, 시간이 초과된 공급자 호출이 답을 포기한
        // 뒤에도 채팅 스레드 셋 가운데 하나를 계속 쥔다. 그래서 평범한 Future를 쓴다.
        Future<GroundedAnswerGenerator.Generated> call;
        try {
            call = executors.model().submit(() -> generator.generate(context.question(), evidence.blocks(), evidence.conflicts()));
        } catch (RejectedExecutionException saturated) {
            throw new GroundedAnswerGenerator.GenerationException("MODEL_BUSY", true);
        }
        try {
            return call.get(modelTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            call.cancel(true);
            throw new GroundedAnswerGenerator.GenerationException("MODEL_TIMEOUT", true);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof GroundedAnswerGenerator.GenerationException generation) throw generation;
            throw new GroundedAnswerGenerator.GenerationException("MODEL_UNAVAILABLE", true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GroundedAnswerGenerator.GenerationException("MODEL_UNAVAILABLE", true);
        }
    }

    /**
     * 검색한 조각에 라벨을 붙이고, 그 안에 해소되지 않은 충돌이 있으면 반대편 기록도 더해서 모델이 한쪽만 보고 조용히
     * 답하지 않고 두 기록을 함께 보게 한다(README §7). context 한도를 넘는 조각은 버린다. PM이 관계를 철회한 쌍은 그
     * 쌍을 뺀 패키지를 다시 만들기 전이라도 더는 충돌이 아니다.
     */
    private Evidence evidence(Context context, List<EvidenceRetriever.EvidenceChunk> retrieved) {
        List<EvidenceRetriever.EvidenceChunk> chunks = new ArrayList<>(retrieved);
        List<long[]> pairs = jdbc.query("""
                select conflict.from_snapshot_id, conflict.to_snapshot_id
                from handover_item_conflicts conflict
                join handover_items item on item.id=conflict.handover_item_id
                join handover_generations generation on generation.id=conflict.generation_id
                join record_relations relation on relation.id=conflict.relation_id
                where generation.handover_id=? and generation.generation=? and item.resolved_by_record_id is null
                  and relation.withdrawn_at is null
                """, (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)}, context.handoverId(), context.generation());
        java.util.Set<Long> present = new java.util.HashSet<>();
        chunks.forEach(chunk -> present.add(chunk.snapshotRecordId()));
        List<long[]> relevant = new ArrayList<>();
        for (long[] pair : pairs) {
            if (!present.contains(pair[0]) && !present.contains(pair[1])) continue;
            relevant.add(pair);
            for (long snapshotId : pair) {
                if (present.add(snapshotId)) chunks.add(snapshotChunk(snapshotId));
            }
        }
        List<GroundedAnswerGenerator.Evidence> blocks = new ArrayList<>();
        Map<String, Long> labels = new LinkedHashMap<>();
        Map<Long, String> codes = new HashMap<>();
        List<Long> snapshotIds = new ArrayList<>();
        int used = 0;
        for (EvidenceRetriever.EvidenceChunk chunk : chunks) {
            if (used + chunk.text().length() > MAX_CONTEXT) continue;
            used += chunk.text().length();
            String label = "E" + (blocks.size() + 1);
            blocks.add(new GroundedAnswerGenerator.Evidence(label, chunk.sourceLabel(), chunk.text()));
            labels.put(label, chunk.snapshotRecordId());
            codes.put(chunk.snapshotRecordId(), chunk.sourceLabel());
            if (!snapshotIds.contains(chunk.snapshotRecordId())) snapshotIds.add(chunk.snapshotRecordId());
        }
        List<GroundedAnswerGenerator.Conflict> conflicts = relevant.stream()
                .filter(pair -> codes.containsKey(pair[0]) && codes.containsKey(pair[1]))
                .map(pair -> new GroundedAnswerGenerator.Conflict(codes.get(pair[0]), codes.get(pair[1]))).toList();
        return new Evidence(blocks, labels, snapshotIds, conflicts);
    }

    private EvidenceRetriever.EvidenceChunk snapshotChunk(long snapshotId) {
        return jdbc.queryForObject("""
                select snapshot.id, record.code,
                       coalesce(snapshot.content_json->>'title','') || E'\\n' || coalesce(snapshot.content_json->>'summary','')
                from handover_snapshot_records snapshot join work_records record on record.id=snapshot.work_record_id where snapshot.id=?
                """, (rs, row) -> new EvidenceRetriever.EvidenceChunk(rs.getLong(1), "snapshot:" + rs.getLong(1), rs.getString(3), rs.getString(2)),
                snapshotId);
    }

    private Handover authorize(long actorId, long handoverId) {
        List<Handover> rows = jdbc.query("""
                select handover.project_id, handover.incoming_owner_id, handover.generation, generation.id,
                       handover.status::text in ('READY','REVIEW_REQUESTED','COMPLETED') and generation.published_at is not null
                from handovers handover
                join handover_generations generation on generation.handover_id=handover.id and generation.generation=handover.generation
                where handover.id=?
                """, (rs, row) -> new Handover(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getLong(4), rs.getBoolean(5)), handoverId);
        if (rows.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        Handover handover = rows.getFirst();
        projects.requireMember(actorId, handover.projectId());
        if (actorId != handover.incomingOwnerId()) throw new QaAccessDeniedException();
        return handover;
    }

    private boolean stillAllowed(Context context) {
        try {
            return authorize(context.actorId(), context.handoverId()) != null;
        } catch (ProjectAccessPolicy.ProjectNotFoundException | QaAccessDeniedException denied) {
            return false;
        }
    }

    /**
     * 세고 나서 넣는 것은 check-then-act라서, 한 사람의 접수 두 개가 모두 한도 아래로 세고 둘 다 넣을 수 있다. 사람에
     * 거는 트랜잭션 범위 advisory lock이 두 번째 접수를 첫 접수의 커밋까지 기다리게 해서, 두 번째 접수도 그 질문을
     * 함께 센다. 두 키 형식이라 큐 용량 잠금과 겹치지 않고, 해시한 사용자 ID는 어떤 int64 ID도 32비트 키에 들어간다.
     * 해시가 같은 두 사용자는 잠깐 서로를 기다릴 뿐이다.
     */
    private void rateLimit(long actorId) {
        jdbc.query("select pg_advisory_xact_lock(?, hashtext(cast(? as text)))", rs -> null, RATE_LIMIT_LOCK, actorId);
        Integer recent = jdbc.queryForObject("""
                select count(*) from qa_messages where session_user_id=? and role='USER' and created_at > now() - interval '1 minute'
                """, Integer.class, actorId);
        if (recent != null && recent >= MAX_PER_MINUTE) throw new QaRateLimitedException(60, "At most " + MAX_PER_MINUTE + " questions per minute.");
        Boolean running = jdbc.queryForObject("""
                select exists(select 1 from jobs job join qa_messages question on question.id=job.target_id
                              where job.kind='QA' and job.status='RUNNING' and job.lease_until>now() and question.session_user_id=?)
                """, Boolean.class, actorId);
        if (Boolean.TRUE.equals(running)) throw new QaRateLimitedException(1, "One question may be answered at a time.");
    }

    private ObjectNode error(String code, boolean retryable, Context context) {
        return json.createObjectNode().put("code", code).put("retryable", retryable).put("traceId", context.traceId());
    }

    static List<String> sentences(String answer) {
        List<String> sentences = new ArrayList<>();
        for (String sentence : answer.strip().split("(?<=[.!?。])\\s+|\\n+")) {
            if (!sentence.isBlank()) sentences.add(sentence.strip());
        }
        return sentences;
    }

    static String path(long handoverId) {
        return "/api/v1/handovers/" + handoverId + "/questions";
    }

    private JsonNode read(String stored) {
        return json.readTree(stored);
    }

    private long decodeCursor(String cursor) {
        try {
            JsonNode value = json.readTree(new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8));
            if (!value.isObject() || value.size() != 1 || !JsonIds.isId(value.get("id"))) throw new IllegalArgumentException();
            return value.get("id").longValue();
        } catch (RuntimeException invalid) {
            throw new InvalidQuestionException();
        }
    }

    private String encodeCursor(long id) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.createObjectNode().put("id", id).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public sealed interface Accepted {
        record Replay(JsonNode answer) implements Accepted { }
        record Fresh(Context context) implements Accepted { }
    }

    public record Context(long actorId, long handoverId, long projectId, int generation, long generationId, long questionId,
                          String question, String path, JobLeaseService.JobContext job, String traceId) { }

    public record History(List<ObjectNode> items, long total, int limit, int offset, String nextCursor) { }

    private record Handover(long projectId, long incomingOwnerId, int generation, long generationId, boolean answerable) { }

    private record Evidence(List<GroundedAnswerGenerator.Evidence> blocks, Map<String, Long> labels, List<Long> snapshotIds,
                            List<GroundedAnswerGenerator.Conflict> conflicts) { }

    /** {@code modelCalled}로 저장한 답변에 그 답을 만든 모델과 프롬프트 버전을 남길지 정한다. */
    private record Outcome(String status, String content, String failureCode, boolean retryable, boolean modelCalled, List<Long> snapshotIds) {
        static Outcome answered(String content, List<Long> snapshotIds) { return new Outcome("ANSWERED", content, null, false, true, snapshotIds); }
        static Outcome noEvidence(boolean modelCalled) { return new Outcome("NO_EVIDENCE", NO_EVIDENCE_TEXT, null, false, modelCalled, List.of()); }
        static Outcome failed(String code, boolean retryable, boolean modelCalled) {
            return new Outcome("FAILED", FAILED_TEXT, code, retryable, modelCalled, List.of());
        }
    }

    /** 연결이 끊긴 것을 기억해서 이후 이벤트를 건너뛰고 답변을 CLIENT_DISCONNECTED로 닫는다. */
    private static final class Sink {
        private final AnswerSink target;
        private boolean closed;

        Sink(AnswerSink target) { this.target = target; }

        void send(String event, JsonNode data) {
            if (closed) return;
            try {
                target.send(event, data);
            } catch (IOException | RuntimeException unusable) {
                // I/O 오류뿐 아니라 어떤 전달 실패든 클라이언트가 더는 이 답을 받을 수 없다는 뜻이다. 연결 끊김으로
                // 다뤄야 질문이 최종 결과를 커밋하는 경로에 남는다.
                closed = true;
            }
        }

        boolean closed() { return closed; }
    }

    public static class InvalidQuestionException extends RuntimeException { }

    public static class QaAccessDeniedException extends RuntimeException { }

    public static class QaRateLimitedException extends RuntimeException {
        private final int retryAfterSeconds;

        public QaRateLimitedException(int retryAfterSeconds, String detail) {
            super(detail);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int retryAfterSeconds() { return retryAfterSeconds; }
    }
}
