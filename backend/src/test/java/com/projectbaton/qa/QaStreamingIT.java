package com.projectbaton.qa;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.handover.HandoverPackageWorker;
import com.projectbaton.handover.HandoverRequestService;
import com.projectbaton.jobs.EmbeddingQueue;
import com.projectbaton.jobs.EmbeddingWorker;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"baton.ai.profile=local", "baton.ai.embedding-model-id=test-hashed-256", "baton.ai.chat-model-id=chat-model", "baton.qa.model-timeout=1s"})
@AutoConfigureMockMvc
@Testcontainers
@Import(QaTestSupport.Models.class)
class QaStreamingIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired QaTestSupport.ScriptedChatModel model;
    @Autowired HandoverRequestService handovers;
    @Autowired HandoverPackageWorker packages;
    @Autowired EmbeddingQueue queue;
    @Autowired EmbeddingWorker embeddings;
    @Autowired TransactionTemplate transactions;
    @Autowired QuestionService questions;

    QaTestSupport.Fixture fixture;
    String incoming;

    @BeforeEach
    void setUp() throws Exception {
        QaTestSupport.reset(jdbc, model);
        fixture = QaTestSupport.fixture(jdbc, handovers, packages, queue, embeddings, transactions, "stream", false);
        incoming = signIn("stream-incoming");
    }

    @Test
    void jsonAnswerRebuildsCitationsFromServerCandidates() throws Exception {
        model.script.add("!cite:October 22|The pilot launch moved to October 22.");
        String decisionCode = jdbc.queryForObject("select code from work_records where id=?", String.class, fixture.decisionId());

        mockMvc.perform(ask(incoming, key(), "When is the pilot launch?", MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role", is("ASSISTANT")))
                .andExpect(jsonPath("$.answerStatus", is("ANSWERED")))
                .andExpect(jsonPath("$.content", is("The pilot launch moved to October 22.")))
                .andExpect(jsonPath("$.citations.length()", is(1)))
                .andExpect(jsonPath("$.citations[0].recordId", is((int) fixture.decisionId())))
                .andExpect(jsonPath("$.citations[0].recordCode", is(decisionCode)))
                .andExpect(jsonPath("$.citations[0].recordTitle", is("Pilot launch date")))
                .andExpect(jsonPath("$.citations[0].sourceName", is("steering-minutes.eml")))
                .andExpect(jsonPath("$.citations[0].occurredOn", is("2026-08-21")))
                .andExpect(jsonPath("$.citations[0].trustStatusAtAnswer", is("CONFIRMED")))
                .andExpect(jsonPath("$.citations[0].isPrimary", is(true)));

        assertEquals(1, jdbc.queryForObject("""
                select count(*) from qa_messages answer join qa_messages question on question.id=answer.reply_to_id
                where answer.role='ASSISTANT' and question.role='USER' and answer.session_user_id=? and answer.model_id='chat-model'
                  and answer.prompt_version is not null and answer.generation_id=question.generation_id
                """, Integer.class, fixture.incomingId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from qa_citations where snapshot_record_id is not null", Integer.class));
        assertTrue(model.prompts.getFirst().contains("<evidence>"), model.prompts.getFirst());
        assertTrue(model.prompts.getFirst().contains("untrusted"), model.prompts.getFirst());
    }

    @Test
    void streamSendsMetaEvidenceDeltasAndDoneInOrder() throws Exception {
        model.script.add("!cite:October 22|The pilot launch moved to October 22. The steering meeting agreed to it.");

        List<Event> events = stream(ask(incoming, key(), "When is the pilot launch?", MediaType.TEXT_EVENT_STREAM));

        assertTrue(events.size() >= 4, lastStream);
        assertEquals("meta", events.getFirst().name());
        assertEquals("evidence", events.get(1).name());
        assertEquals("done", events.getLast().name(), lastStream);
        assertEquals(List.of("delta", "delta"), events.subList(2, events.size() - 1).stream().map(Event::name).toList());
        assertEquals(1, events.getFirst().data().get("generation").intValue());
        assertTrue(events.getFirst().data().get("questionId").longValue() > 0);
        assertTrue(events.get(1).data().get("citations").size() >= 1);
        assertEquals("The pilot launch moved to October 22.", events.get(2).data().get("text").stringValue());
        JsonNode answer = events.getLast().data().get("answer");
        assertEquals("ANSWERED", answer.get("answerStatus").stringValue());
        assertEquals(answer.get("content").stringValue(), events.get(2).data().get("text").stringValue() + " " + events.get(3).data().get("text").stringValue());
    }

    /** stream이 첫 이벤트에서 알리는 id는 stream을 연 요청의 id로, 오류 응답이 담을 id와 같다. */
    @Test
    void theStreamAnnouncesTheTraceIdOfItsRequest() throws Exception {
        model.script.add("!cite:October 22|The pilot launch moved to October 22.");

        MvcResult started = mockMvc.perform(ask(incoming, key(), "When is the pilot launch?", MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String requestTrace = (String) started.getRequest().getAttribute("baton.traceId");
        String data = body.substring(body.indexOf("data:") + 5, body.indexOf('\n', body.indexOf("data:")));
        assertTrue(requestTrace != null && !requestTrace.isBlank());
        assertEquals(requestTrace, json.readTree(data).get("traceId").stringValue(), body);
    }

    @Test
    void noEvidenceIsAnsweredWithoutCallingTheModel() throws Exception {
        List<Event> events = stream(ask(incoming, key(), "zebra warehouse invoice", MediaType.TEXT_EVENT_STREAM));

        assertEquals(List.of("meta", "evidence", "done"), events.stream().map(Event::name).toList());
        assertEquals(0, events.get(1).data().get("citations").size());
        JsonNode answer = events.getLast().data().get("answer");
        assertEquals("NO_EVIDENCE", answer.get("answerStatus").stringValue());
        assertEquals(0, answer.get("citations").size());
        assertTrue(answer.get("suggestedContacts").stringValue().contains("stream-outgoing"));
        assertTrue(model.prompts.isEmpty());
    }

    @Test
    void providerFailureTimeoutAndForeignCitationsAreFailedAnswers() throws Exception {
        model.script.add("!outage");
        mockMvc.perform(ask(incoming, key(), "When is the pilot launch?", MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.answerStatus", is("FAILED")));

        model.script.add("{\"answer\":\"Invented answer.\",\"citations\":[\"E99\"]}");
        mockMvc.perform(ask(incoming, key(), "When is the pilot launch?", MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.answerStatus", is("FAILED")))
                .andExpect(jsonPath("$.citations.length()", is(0)));

        model.script.add("{\"answer\":\"No citation at all.\",\"citations\":[]}");
        mockMvc.perform(ask(incoming, key(), "When is the pilot launch?", MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.answerStatus", is("FAILED")));

        model.delayMillis = 2_000;
        model.script.add("!cite:October 22|Too late.");
        mockMvc.perform(ask(incoming, key(), "When is the pilot launch?", MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.answerStatus", is("FAILED")));
        model.delayMillis = 0;

        assertEquals(List.of("MODEL_UNAVAILABLE", "CITATION_INVALID", "CITATION_INVALID", "MODEL_TIMEOUT"), jdbc.queryForList(
                "select failure_code from qa_messages where role='ASSISTANT' order by id", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from qa_citations", Integer.class));

        model.script.add("!outage");
        List<Event> events = stream(ask(incoming, key(), "When is the pilot launch?", MediaType.TEXT_EVENT_STREAM));
        assertEquals("error", events.getLast().name());
        assertEquals("MODEL_UNAVAILABLE", events.getLast().data().get("code").stringValue());
        assertTrue(events.getLast().data().get("retryable").booleanValue());
        assertTrue(events.stream().noneMatch(event -> event.name().equals("delta")));
    }

    @Test
    void unresolvedConflictPutsBothRecordsInFrontOfTheModel() throws Exception {
        QaTestSupport.reset(jdbc, model);
        fixture = QaTestSupport.fixture(jdbc, handovers, packages, queue, embeddings, transactions, "conflict", true);
        incoming = signIn("conflict-incoming");
        model.script.add("!cite:Pilot launch|Two records disagree about the pilot launch; the PM has not resolved them.");

        mockMvc.perform(ask(incoming, key(), "When is the pilot launch?", MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.answerStatus", is("ANSWERED")))
                .andExpect(jsonPath("$.citations.length()", is(2)));
        String prompt = model.prompts.getFirst();
        String decision = jdbc.queryForObject("select code from work_records where id=?", String.class, fixture.decisionId());
        String commitment = jdbc.queryForObject("select code from work_records where id=?", String.class, fixture.commitmentId());
        // 지시문은 늘 표식을 이름으로 부르므로, 확인은 표식 줄 자체를 찾는다.
        assertTrue(prompt.contains("UNRESOLVED CONFLICT: ") && prompt.contains(decision) && prompt.contains(commitment), prompt);
    }

    /**
     * 충돌을 철회한 PM은 그 쌍이 다르지 않다고 말한 것이므로, 다시 만들기 전이라도 답변에 다르다고 알려서는 안 된다.
     */
    @Test
    void aWithdrawnConflictIsNoLongerPutInFrontOfTheModel() throws Exception {
        QaTestSupport.reset(jdbc, model);
        fixture = QaTestSupport.fixture(jdbc, handovers, packages, queue, embeddings, transactions, "withdrawn", true);
        incoming = signIn("withdrawn-incoming");
        jdbc.update("""
                update record_relations set withdrawn_at=now(), withdrawn_by=?, withdrawal_reason='The two records do not disagree.'
                where project_id=? and relation_type='CONFLICTS_WITH'
                """, fixture.managerId(), fixture.projectId());
        model.script.add("!cite:Pilot launch|The pilot launch moved to October 22.");

        mockMvc.perform(ask(incoming, key(), "When is the pilot launch?", MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        String prompt = model.prompts.getFirst();
        assertTrue(!prompt.contains("UNRESOLVED CONFLICT: "), prompt);
    }

    @Test
    void replayInputRulesNegotiationAndRateLimit() throws Exception {
        String replayKey = key();
        model.script.add("!cite:October 22|The pilot launch moved to October 22.");
        String first = mockMvc.perform(ask(incoming, replayKey, "When is the pilot launch?", MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mockMvc.perform(ask(incoming, replayKey, "When is the pilot launch?", MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(first), json.readTree(replay));
        List<Event> streamedReplay = stream(ask(incoming, replayKey, "When is the pilot launch?", MediaType.TEXT_EVENT_STREAM));
        assertEquals(List.of("done"), streamedReplay.stream().map(Event::name).toList());
        assertEquals(json.readTree(first), streamedReplay.getFirst().data().get("answer"));
        mockMvc.perform(ask(incoming, replayKey, "A different question?", MediaType.APPLICATION_JSON)).andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("select count(*) from qa_messages where role='USER'", Integer.class));

        mockMvc.perform(ask(incoming, key(), "When is the pilot launch?", MediaType.APPLICATION_XML)).andExpect(status().isNotAcceptable());
        mockMvc.perform(ask(incoming, key(), "x".repeat(2001), MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        mockMvc.perform(ask(incoming, key(), "   ", MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/handovers/{handoverId}/questions", fixture.handoverId()).with(csrf()).cookie(cookie(incoming))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"When?\",\"extra\":1}"))
                .andExpect(status().isBadRequest());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(ask(incoming, key(), "zebra warehouse invoice " + i, MediaType.APPLICATION_JSON)).andExpect(status().isCreated());
        }
        mockMvc.perform(ask(incoming, key(), "zebra warehouse invoice again", MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", notNullValue()))
                .andExpect(jsonPath("$.code", is("QA_RATE_LIMITED")))
                .andExpect(jsonPath("$.detail", containsString("minute")));
        assertEquals(6, jdbc.queryForObject("select count(*) from qa_messages where role='USER'", Integer.class));
    }

    /**
     * 분당 한도와 한 번에 하나 한도는 행을 센 뒤 하나를 넣는다. 같은 사람의 접수 두 개가 둘 다 넣기 전에 세면 둘 다
     * 통과할 수 있었다. 이제 접수가 먼저 사람별 advisory lock을 잡아서, 두 번째 접수는 첫 접수의 커밋을 기다린 뒤
     * 그것까지 센다.
     */
    @Test
    void admissionsOfTheSamePersonAreCountedOneAtATime() throws Exception {
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.query("select pg_advisory_xact_lock(?, hashtext(cast(? as text)))", rs -> null, QuestionService.RATE_LIMIT_LOCK, fixture.incomingId());
                held.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(held.await(10, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<QuestionService.Accepted> admission = pool.submit(() ->
                    questions.accept(fixture.incomingId(), fixture.handoverId(), UUID.randomUUID(), "When is the pilot launch?"));

            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> admission.get(1, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(admission.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 접수 잠금 키를 Math.toIntExact로 만들어서, int 범위를 넘는 사용자 id는 질문이 저장되기 전에 500으로 끝났다.
     * 이제 키를 해시해서 그런 id의 사용자도 다른 사람처럼 질문한다.
     */
    @Test
    void aPersonWhoseIdIsPastTheIntRangeCanAskAQuestion() throws Exception {
        QaTestSupport.reset(jdbc, model);
        jdbc.execute("select setval('users_id_seq', 4000000000)");
        QaTestSupport.Fixture large = QaTestSupport.fixture(jdbc, handovers, packages, queue, embeddings, transactions, "large", false);
        assertTrue(large.incomingId() > Integer.MAX_VALUE, "the fixture user id must exceed the int range");
        model.script.add("!cite:October 22|The pilot launch moved to October 22.");

        mockMvc.perform(ask(signIn("large-incoming"), key(), "When is the pilot launch?", MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.answerStatus", is("ANSWERED")));
    }

    record Event(String name, JsonNode data) { }

    private String lastStream = "";

    private List<Event> stream(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult started = mockMvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        String body = mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Event> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) name = line.substring(6).strip();
                if (line.startsWith("data:")) data.append(line.substring(5));
            }
            if (name != null) events.add(new Event(name, json.readTree(data.toString())));
        }
        // 순서가 틀렸을 때 클라이언트가 실제로 받은 것을 보이도록 날 stream을 남긴다.
        lastStream = body;
        return events;
    }

    private MockHttpServletRequestBuilder ask(String session, String key, String question, MediaType accept) throws Exception {
        return post("/api/v1/handovers/{handoverId}/questions", fixture.handoverId()).with(csrf()).cookie(cookie(session))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).accept(accept)
                .content(json.writeValueAsString(json.createObjectNode().put("question", question)));
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + QaTestSupport.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static jakarta.servlet.http.Cookie cookie(String value) { return new jakarta.servlet.http.Cookie("BATON_SESSION", value); }
}
