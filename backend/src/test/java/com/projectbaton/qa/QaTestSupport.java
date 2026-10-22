package com.projectbaton.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.projectbaton.handover.HandoverPackageWorker;
import com.projectbaton.handover.HandoverRequestService;
import com.projectbaton.jobs.EmbeddingQueue;
import com.projectbaton.jobs.EmbeddingWorker;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Q&A 통합 시험이 함께 쓰는 합성 fixture와 각본 모델. 여기서는 네트워크에 닿는 것이 없다. */
final class QaTestSupport {
    private static final java.util.concurrent.atomic.AtomicInteger EXTRACTION_INDEX = new java.util.concurrent.atomic.AtomicInteger();
    // FailureIsolationIT처럼 시험 실행마다 만든다. 여기 고정 문자열을 두면 일회용 컨테이너의 합성 계정만 지키더라도
    // 비밀 스캐너가 하드코딩된 자격 증명으로 잡는다.
    static final String PASSWORD = "qa-" + java.util.UUID.randomUUID();
    static final Set<String> TYPES = Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION");
    private static final PasswordEncoder PASSWORDS = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private QaTestSupport() { }

    @TestConfiguration
    static class Models {
        @Bean ScriptedChatModel chatModel() { return new ScriptedChatModel(); }
        @Bean EmbeddingModel embeddingModel() { return new HashedEmbeddingModel(); }
    }

    record Fixture(long handoverId, long projectId, long managerId, long outgoingId, long incomingId, long memberId,
                   long decisionId, long commitmentId) { }

    static void reset(JdbcTemplate jdbc, ScriptedChatModel model) {
        model.script.clear();
        model.prompts.clear();
        model.delayMillis = 0;
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations(name) values('Q&A Organization')");
    }

    static Fixture fixture(JdbcTemplate jdbc, HandoverRequestService handovers, HandoverPackageWorker packages,
                           EmbeddingQueue queue, EmbeddingWorker embeddings, TransactionTemplate transactions, String label, boolean conflict) {
        long manager = user(jdbc, label + "-manager");
        long outgoing = user(jdbc, label + "-outgoing");
        long incoming = user(jdbc, label + "-incoming");
        long member = user(jdbc, label + "-member");
        long project = jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values(?,'Q&A','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id",
                Long.class, "QA-" + label + "-" + System.nanoTime());
        member(jdbc, project, manager, "PROJECT_MANAGER");
        member(jdbc, project, outgoing, "OUTGOING_OWNER");
        member(jdbc, project, incoming, "INCOMING_OWNER");
        member(jdbc, project, member, "MEMBER");
        long connection = jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','Steering mail') returning id", Long.class, project);
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,'steering-minutes.eml',now(),?,?,'v1',repeat('c',64),'synthetic private body') returning id
                """, Long.class, connection, project, "doc-" + label);
        record(jdbc, project, source, manager, "SCHEDULE", "Integration window", "Integration test window opens on 2026-10-01.", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 10, 1));
        long decision = record(jdbc, project, source, manager, "CHANGE_DECISION", "Pilot launch date", "Pilot launch moved to October 22 after the steering meeting.", LocalDate.of(2026, 8, 21), null);
        long commitment = record(jdbc, project, source, manager, "COMMITMENT", "Pilot launch promise", "The vendor promised the pilot launch for November 5.", LocalDate.of(2026, 8, 22), null);
        record(jdbc, project, source, manager, "BLOCKER", "Access blocker", "Firewall access for the staging server is pending.", LocalDate.of(2026, 8, 23), null);
        record(jdbc, project, source, manager, "OPEN_ISSUE", "Budget question", "Overtime budget approval is still open.", LocalDate.of(2026, 8, 24), null);
        record(jdbc, project, source, manager, "SCOPE_EXCEPTION", "Scope exception", "Mobile push notifications are out of scope.", LocalDate.of(2026, 8, 25), null);
        long privateFolder = jdbc.queryForObject("insert into source_connections(project_id,owner_user_id,channel,display_name) values(?,?,'CLOUD_PROJECT_FOLDER','outgoing folder') returning id",
                Long.class, project, outgoing);
        long privateSource = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,'outgoing-private-notes.docx',now(),?,?,'v1',repeat('d',64),'synthetic private folder body') returning id
                """, Long.class, privateFolder, project, "private-" + label);
        record(jdbc, project, privateSource, outgoing, "BLOCKER", "Month end batch", "The legacy batch job fails on month end closing.", LocalDate.of(2026, 8, 26), null);
        if (conflict) {
            jdbc.update("insert into record_relations(project_id,from_record_id,to_record_id,relation_type) values(?,?,?,'CONFLICTS_WITH')",
                    project, Math.min(decision, commitment), Math.max(decision, commitment));
        }
        long handover = handovers.request(manager, project, new HandoverRequestService.Command(outgoing, incoming, LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14), TYPES, true)).id();
        packages.runOnce();
        assertEquals("READY", jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover));
        jdbc.queryForList("select id, version from work_records where project_id=?", project)
                .forEach(row -> transactions.executeWithoutResult(status -> queue.enqueue((Long) row.get("id"), (Long) row.get("version"))));
        while (jdbc.queryForObject("select count(*) from jobs where kind='EMBED' and status='PENDING'", Integer.class) > 0) embeddings.runOnce();
        return new Fixture(handover, project, manager, outgoing, incoming, member, decision, commitment);
    }

    static long user(JdbcTemplate jdbc, String employeeNo) {
        return jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),?,?,'Engineer',?,?) returning id",
                Long.class, employeeNo, employeeNo, employeeNo + "@test", PASSWORDS.encode(PASSWORD));
    }

    static void member(JdbcTemplate jdbc, long project, long user, String role) {
        jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),date '2026-09-01')", project, user, role);
    }

    private static long record(JdbcTemplate jdbc, long project, long source, long manager, String type, String title, String summary,
                               LocalDate occurredOn, LocalDate dueOn) {
        return jdbc.queryForObject("""
                insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,due_on,trust_status,confirmed_by,confirmed_at,source_version,extraction_index,search_text)
                values(?,?,?,cast(? as record_type),?,?,'private synthetic excerpt',?,'Synthetic author',?,'CONFIRMED',?,now(),'v1',?,?) returning id
                """, Long.class, "REC-" + System.nanoTime(), project, source, type, title, summary, occurredOn, dueOn, manager, EXTRACTION_INDEX.getAndIncrement(), summary);
    }

    /**
     * 넣어 둔 출력을 순서대로 돌려준다. "!outage"는 닿지 않는 공급자처럼 예외를 던진다. {@code delayMillis}는 느린
     * 모델을 흉내 낸다.
     */
    static class ScriptedChatModel implements ChatModel {
        final Deque<String> script = new ArrayDeque<>();
        final List<String> prompts = new CopyOnWriteArrayList<>();
        volatile long delayMillis;

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getContents());
            // 시간이 초과된 호출이 나중 시험 단계의 출력을 써 버리지 않도록, 잠들기 전에 각본 출력을 꺼낸다.
            String next;
            synchronized (script) {
                next = script.poll();
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            if (next == null || next.equals("!outage")) throw new IllegalStateException("synthetic provider outage");
            if (next.startsWith("!cite:")) next = cite(prompt.getContents(), next.substring("!cite:".length()));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(next))));
        }

        /** "!cite:phrase|answer"는 근거 있는 모델처럼, 블록에 phrase가 들어 있는 근거 라벨을 모두 인용한다. */
        private static String cite(String prompt, String instruction) {
            String phrase = instruction.substring(0, instruction.indexOf('|'));
            String answer = instruction.substring(instruction.indexOf('|') + 1);
            List<String> labels = new ArrayList<>();
            java.util.regex.Matcher block = java.util.regex.Pattern.compile("(?s)\\[(E\\d+)\\](.*?)(?=\\n\\[E\\d+\\]|</evidence>)").matcher(prompt);
            while (block.find()) if (block.group(2).contains(phrase)) labels.add(block.group(1));
            return "{\"answer\":\"" + answer + "\",\"citations\":[" + String.join(",", labels.stream().map(label -> "\"" + label + "\"").toList()) + "]}";
        }
    }

    /** 오프라인 stub과 같은 해시 벡터라서 모델 없이도 검색이 현실적으로 동작한다. */
    static class HashedEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
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
