package com.projectbaton.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.projectbaton.handover.HandoverPackageWorker;
import com.projectbaton.handover.HandoverRequestService;
import com.projectbaton.jobs.EmbeddingQueue;
import com.projectbaton.jobs.EmbeddingWorker;
import com.projectbaton.record.RecordReviewService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * 바꿔 끼울 수 있는 임베딩 모델로 Spring AI 임베딩 어댑터, EMBED 작업, pgvector 검색을 돌린다. 모델이 오프라인 stub과
 * 같은 해시 벡터를 계산해서 유사도는 진짜이고 시험 밖으로 나가는 것은 없다.
 */
@SpringBootTest(properties = {"baton.ai.profile=local", "baton.ai.embedding-model-id=test-hashed-256", "baton.ai.chat-model-id=test-chat"})
@Testcontainers
class EvidenceRetrievalIT {
    private static final java.util.concurrent.atomic.AtomicInteger EXTRACTION_INDEX = new java.util.concurrent.atomic.AtomicInteger();
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class Models {
        @Bean SwitchableEmbeddingModel embeddingModel() { return new SwitchableEmbeddingModel(); }
        @Bean ChatModel chatModel() { return prompt -> { throw new IllegalStateException("not used"); }; }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired com.projectbaton.config.JobHousekeeping housekeeping;
    @Autowired SwitchableEmbeddingModel model;
    @Autowired EmbeddingQueue queue;
    @Autowired EmbeddingWorker embeddings;
    @Autowired com.projectbaton.jobs.EmbeddingBackfill backfill;
    @Autowired com.projectbaton.jobs.JobRepository jobRepository;
    @Autowired com.projectbaton.qa.EmbeddingClient embeddingClient;
    @Autowired EvidenceRetriever retriever;
    @Autowired HandoverRequestService handovers;
    @Autowired HandoverPackageWorker packages;
    @Autowired RecordReviewService reviews;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        model.down.set(false);
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations(name) values('Retrieval Organization')");
    }

    @Test
    void confirmingARecordQueuesItsEmbeddingInTheSameTransaction() {
        Fixture a = fixture("A", "Pilot launch moved to October 22 after the steering meeting.");
        long draft = jdbc.queryForObject("""
                insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,trust_status,source_version,extraction_index,search_text)
                values('REC-DRAFT',?,?,'COMMITMENT','Vendor contract','Vendor contract renewal is due in November.','Vendor contract renewal is due in November.',date '2026-09-02','Author','AUTO_DRAFT','v1',99,'x') returning id
                """, Long.class, a.projectId(), a.sourceId());

        reviews.review(a.outgoingId(), draft, 0, json.readTree("{\"action\":\"CONFIRM\"}"));

        assertEquals(1, jdbc.queryForObject("select count(*) from jobs where kind='EMBED' and dedup_key=?", Integer.class, "embed:" + draft + ":1"));
        embeddings.runOnce();
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from record_embeddings where record_id=? and record_version=1 and embedding_model='test-hashed-256'
                """, Integer.class, draft));
        assertTrue(jdbc.queryForObject("select chunk_text from record_embeddings where record_id=?", String.class, draft)
                .contains("Vendor contract renewal"));
    }

    @Test
    void retrievalReturnsOnlyCurrentGenerationEvidenceOfTheQueriedProject() {
        Fixture a = fixture("A", "Pilot launch moved to October 22 after the steering meeting.");
        Fixture b = fixture("B", "Pilot launch for the warehouse team stays in December.");
        embedAll();

        List<EvidenceRetriever.EvidenceChunk> fromA = retriever.retrieve(query(a, "When is the pilot launch?"));
        assertFalse(fromA.isEmpty());
        assertTrue(fromA.stream().allMatch(chunk -> snapshotProject(chunk.snapshotRecordId()) == a.projectId()), fromA.toString());
        assertTrue(fromA.stream().anyMatch(chunk -> chunk.text().contains("October 22")), fromA.toString());
        assertTrue(fromA.stream().noneMatch(chunk -> chunk.text().contains("December")), fromA.toString());
        assertTrue(fromA.stream().allMatch(chunk -> chunk.sourceLabel().startsWith("REC-")), fromA.toString());
        assertTrue(fromA.size() <= 8);

        List<EvidenceRetriever.EvidenceChunk> fromB = retriever.retrieve(query(b, "October 22 steering meeting"));
        assertTrue(fromB.stream().noneMatch(chunk -> chunk.text().contains("October 22")), fromB.toString());
        assertTrue(fromB.stream().allMatch(chunk -> snapshotProject(chunk.snapshotRecordId()) == b.projectId()), fromB.toString());

        assertThrows(EvidenceRetriever.EvidenceAccessDeniedException.class, () -> retriever.retrieve(
                new EvidenceRetriever.EvidenceQuery(a.incomingId(), b.projectId(), a.handoverId(), 1, "pilot", 8)));
        assertThrows(EvidenceRetriever.EvidenceAccessDeniedException.class, () -> retriever.retrieve(
                new EvidenceRetriever.EvidenceQuery(a.outgoingId(), a.projectId(), a.handoverId(), 1, "pilot", 8)));
        assertThrows(EvidenceRetriever.EvidenceAccessDeniedException.class, () -> retriever.retrieve(
                new EvidenceRetriever.EvidenceQuery(b.incomingId(), a.projectId(), a.handoverId(), 1, "pilot", 8)));
    }

    @Test
    void theQuestionIsEmbeddedBeforeAnyDatabaseTransactionOpens() {
        // C01과 README §4.2: 모델이 답하는 동안 트랜잭션도, 따라서 pool 연결도 쥐지 않는다. 예전에는 검색기가 읽기
        // 전용 트랜잭션 안에서 질문을 임베딩해서, 느린 임베딩 모델이면 호출 내내 연결을 쥐었다.
        Fixture a = fixture("A", "Pilot launch moved to October 22 after the steering meeting.");
        embedAll();
        model.calledInTransaction.clear();

        assertFalse(retriever.retrieve(query(a, "When is the pilot launch?")).isEmpty());

        assertEquals(List.of(false), model.calledInTransaction);
    }

    @Test
    void embeddingsOfAnotherRecordVersionAreNotUsedAndTheCurrentSnapshotStaysSearchable() {
        Fixture a = fixture("A", "Pilot launch moved to October 22 after the steering meeting.");
        embedAll();
        long decision = a.decisionId();
        jdbc.update("update work_records set summary='Pilot launch moved to November 5 by the client.', version=version+1 where id=?", decision);
        handovers.regenerate(a.managerId(), a.handoverId(), version(a.handoverId()), new HandoverRequestService.Command(0, 0,
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14), TYPES, true));
        packages.runOnce();

        List<EvidenceRetriever.EvidenceChunk> chunks = retriever.retrieve(query(a, 2, "pilot launch"));

        assertTrue(chunks.stream().noneMatch(chunk -> chunk.text().contains("October 22")), chunks.toString());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.text().contains("November 5") && chunk.chunkId().startsWith("snapshot:")), chunks.toString());
        assertThrows(EvidenceRetriever.EvidenceAccessDeniedException.class, () -> retriever.retrieve(query(a, 1, "pilot launch")));
    }

    @Test
    void vectorOutageFallsBackToNormalizedKeywordSearch() {
        Fixture a = fixture("A", "파일럿 오픈 일정은 10월 22일로 변경되었다.");
        embedAll();
        model.down.set(true);

        List<EvidenceRetriever.EvidenceChunk> chunks = retriever.retrieve(query(a, "파일럿 일정"));

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.chunkId().startsWith("snapshot:")), chunks.toString());
        assertTrue(chunks.getFirst().text().contains("10월 22일"), chunks.toString());
        assertTrue(retriever.retrieve(query(a, "zebra warehouse invoice")).isEmpty());
    }

    @Test
    void embeddingOutageIsRetriedThenFailsWithoutRows() {
        Fixture a = fixture("A", "Pilot launch moved to October 22 after the steering meeting.");
        transactions.executeWithoutResult(status -> queue.enqueue(a.decisionId(), 0));
        model.down.set(true);
        for (int attempt = 1; attempt <= 3; attempt++) {
            embeddings.runOnce();
            if (attempt < 3) {
                jdbc.update("update jobs set lease_until=now()-interval '1 second' where kind='EMBED' and status='RUNNING'");
                housekeeping.sweep();
            }
        }
        assertEquals("FAILED", jdbc.queryForObject("select status from jobs where kind='EMBED'", String.class));
        assertEquals("EMBEDDING_UNAVAILABLE", jdbc.queryForObject("select failure_code from jobs where kind='EMBED'", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from record_embeddings", Integer.class));
    }

    /**
     * 새 임베딩 모델에는 이전 모델로 색인한 기록의 벡터가 없고 색인 작업의 dedup 키는 다시 쓰이지 않아서, 그 기록을
     * 다시 넣는 곳이 없었다. backfill은 현재 모델의 벡터가 없는 확정 기록 버전마다 작업 하나를 넣는다. 검색은 현재
     * 모델의 벡터만 읽으므로 이전 벡터는 답에 쓰이지 않는다.
     */
    @Test
    void aSwitchedEmbeddingModelGetsItsMissingVectorsQueuedAndTheOldOnesNeverAnswer() {
        Fixture a = fixture("A", "Pilot launch moved to October 22 after the steering meeting.");
        embedAll();
        int records = jdbc.queryForObject("select count(*) from work_records where trust_status in ('CONFIRMED','CORRECTED')", Integer.class);
        int oldVectors = jdbc.queryForObject("select count(*) from record_embeddings", Integer.class);
        jdbc.update("update record_embeddings set embedding_model='older-model'");
        jdbc.update("delete from jobs");

        assertEquals(records, backfill.enqueueMissing());
        assertEquals(0, backfill.enqueueMissing(), "a record that already has an unfinished job is not queued again");
        while (embeddings.runOnce()) { }

        assertEquals(records, jdbc.queryForObject(
                "select count(distinct record_id) from record_embeddings where embedding_model='test-hashed-256'", Integer.class));
        assertEquals(oldVectors, jdbc.queryForObject("select count(*) from record_embeddings where embedding_model='older-model'", Integer.class));
        assertEquals(0, backfill.enqueueMissing());
        jdbc.update("update record_embeddings set chunk_text='OLD-MODEL-MARKER' where embedding_model='older-model'");
        List<EvidenceRetriever.EvidenceChunk> found = retriever.retrieve(query(a, "When is the pilot launch?"));
        assertTrue(found.stream().anyMatch(chunk -> chunk.text().contains("October 22")));
        assertTrue(found.stream().noneMatch(chunk -> chunk.text().contains("OLD-MODEL-MARKER")), "vectors of the old model never answer");
    }

    @Test
    void theBackfillSkipsDraftsAndQueuesOnlyOneBatchAtATime() {
        Fixture a = fixture("A", "Pilot launch moved to October 22 after the steering meeting.");
        jdbc.update("delete from jobs");
        jdbc.update("""
                insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,trust_status,source_version,extraction_index,search_text)
                values('REC-DRAFT-BF',?,?,'COMMITMENT','Vendor','Vendor contract renewal is due in November.','x',date '2026-09-02','Author','AUTO_DRAFT','v1',98,'x')
                """, a.projectId(), a.sourceId());
        int records = jdbc.queryForObject("select count(*) from work_records where trust_status in ('CONFIRMED','CORRECTED')", Integer.class);
        com.projectbaton.jobs.EmbeddingBackfill small = new com.projectbaton.jobs.EmbeddingBackfill(jdbc, jobRepository, embeddingClient, 2, 5_000);

        assertEquals(2, small.enqueueMissing());
        assertEquals(2, small.enqueueMissing());
        assertEquals(records - 4, small.enqueueMissing());
        assertEquals(0, jdbc.queryForObject("select count(*) from jobs where kind='EMBED' and target_id=(select id from work_records where code='REC-DRAFT-BF')", Integer.class));
        assertEquals(records, jdbc.queryForObject("select count(*) from jobs where kind='EMBED'", Integer.class));
    }

    /**
     * 주기마다 모든 기록을 읽으면 큰 DB에서 1분마다 work_records 전체를 훑게 되므로, 한 주기는 id 한 구간을 읽고 다음
     * 주기는 그다음 구간을 읽다가 마지막 뒤에서 처음으로 돌아간다. 여기서는 구간 폭이 id 셋이라 모든 기록을 찾는 데
     * 여러 주기가 걸린다.
     */
    @Test
    void theBackfillWalksTheRecordsInWindowsAndStartsOverAfterTheLastOne() {
        fixture("A", "Pilot launch moved to October 22 after the steering meeting.");
        jdbc.update("delete from jobs");
        int records = jdbc.queryForObject("select count(*) from work_records where trust_status in ('CONFIRMED','CORRECTED')", Integer.class);
        com.projectbaton.jobs.EmbeddingBackfill narrow = new com.projectbaton.jobs.EmbeddingBackfill(jdbc, jobRepository, embeddingClient, 100, 3);

        int queued = 0;
        int firstCycle = narrow.enqueueMissing();
        queued += firstCycle;
        assertTrue(firstCycle < records, "one cycle reads one window, not every record");
        for (int cycle = 0; cycle < 20; cycle++) queued += narrow.enqueueMissing();

        assertEquals(records, queued);
        assertEquals(records, jdbc.queryForObject("select count(*) from jobs where kind='EMBED'", Integer.class));
    }

    private static final Set<String> TYPES = Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION");

    private void embedAll() {
        jdbc.queryForList("select id, version from work_records where trust_status in ('CONFIRMED','CORRECTED')")
                .forEach(row -> transactions.executeWithoutResult(status -> queue.enqueue((Long) row.get("id"), (Long) row.get("version"))));
        while (jdbc.queryForObject("select count(*) from jobs where kind='EMBED' and status='PENDING'", Integer.class) > 0) embeddings.runOnce();
    }

    private EvidenceRetriever.EvidenceQuery query(Fixture fixture, String question) { return query(fixture, 1, question); }
    private EvidenceRetriever.EvidenceQuery query(Fixture fixture, int generation, String question) {
        return new EvidenceRetriever.EvidenceQuery(fixture.incomingId(), fixture.projectId(), fixture.handoverId(), generation, question, 8);
    }
    private long snapshotProject(long snapshotId) { return jdbc.queryForObject("select project_id from handover_snapshot_records where id=?", Long.class, snapshotId); }
    private long version(long handoverId) { return jdbc.queryForObject("select version from handovers where id=?", Long.class, handoverId); }

    private Fixture fixture(String label, String decisionSummary) {
        long manager = user(label + "-manager");
        long outgoing = user(label + "-outgoing");
        long incoming = user(label + "-incoming");
        long project = jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values(?,'Retrieval','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id",
                Long.class, "RETRIEVAL-" + label + "-" + System.nanoTime());
        member(project, manager, "PROJECT_MANAGER");
        member(project, outgoing, "OUTGOING_OWNER");
        member(project, incoming, "INCOMING_OWNER");
        long connection = jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','retrieval') returning id", Long.class, project);
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,'retrieval',now(),?,?,'v1',repeat('c',64),'synthetic private body') returning id
                """, Long.class, connection, project, "doc-" + label);
        List<Long> records = new ArrayList<>();
        records.add(record(project, source, manager, "SCHEDULE", "Milestone", "Integration test window opens.", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 10, 22)));
        records.add(record(project, source, manager, "CHANGE_DECISION", "Launch decision", decisionSummary, LocalDate.of(2026, 8, 21), null));
        records.add(record(project, source, manager, "COMMITMENT", "Report promise", "Weekly report goes to the client every Friday.", LocalDate.of(2026, 8, 22), null));
        records.add(record(project, source, manager, "BLOCKER", "Access blocker", "Firewall access for the staging server is pending.", LocalDate.of(2026, 8, 23), null));
        records.add(record(project, source, manager, "OPEN_ISSUE", "Budget question", "Overtime budget approval is still open.", LocalDate.of(2026, 8, 24), null));
        records.add(record(project, source, manager, "SCOPE_EXCEPTION", "Scope exception", "Mobile push notifications are out of scope.", LocalDate.of(2026, 8, 25), null));
        long handover = handovers.request(manager, project, new HandoverRequestService.Command(outgoing, incoming, LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14), TYPES, true)).id();
        packages.runOnce();
        return new Fixture(handover, project, manager, outgoing, incoming, source, records.get(1));
    }

    private long user(String employeeNo) {
        return jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email) values((select id from organizations),?,?,'Engineer',?) returning id",
                Long.class, employeeNo, employeeNo, employeeNo + "@test");
    }
    private void member(long project, long user, String role) {
        jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),date '2026-09-01')", project, user, role);
    }
    private long record(long project, long source, long manager, String type, String title, String summary, LocalDate occurredOn, LocalDate dueOn) {
        return jdbc.queryForObject("""
                insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,due_on,trust_status,confirmed_by,confirmed_at,source_version,extraction_index,search_text)
                values(?,?,?,cast(? as record_type),?,?,'private synthetic excerpt',?,'Synthetic author',?,'CONFIRMED',?,now(),'v1',?,?) returning id
                """, Long.class, "REC-" + System.nanoTime(), project, source, type, title, summary, occurredOn, dueOn, manager, EXTRACTION_INDEX.getAndIncrement(), summary);
    }

    record Fixture(long handoverId, long projectId, long managerId, long outgoingId, long incomingId, long sourceId, long decisionId) { }

    static class SwitchableEmbeddingModel implements EmbeddingModel {
        final AtomicBoolean down = new AtomicBoolean();
        /** 모델을 부를 때마다 하나씩, 호출한 스레드에 DB 트랜잭션이 열려 있었는지. */
        final List<Boolean> calledInTransaction = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            calledInTransaction.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            if (down.get()) throw new IllegalStateException("synthetic embedding outage");
            List<Embedding> results = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                results.add(new Embedding(StubEmbeddingClient.vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(results);
        }
        @Override public float[] embed(Document document) { return call(new EmbeddingRequest(List.of(document.getText()), null)).getResult().getOutput(); }
        @Override public int dimensions() { return StubEmbeddingClient.DIMENSIONS; }
    }
}
