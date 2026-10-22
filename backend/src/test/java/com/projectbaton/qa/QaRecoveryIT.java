package com.projectbaton.qa;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.handover.HandoverPackageWorker;
import com.projectbaton.handover.HandoverRequestService;
import com.projectbaton.jobs.EmbeddingQueue;
import com.projectbaton.jobs.EmbeddingWorker;
import java.io.IOException;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"baton.ai.profile=local", "baton.ai.embedding-model-id=test-hashed-256", "baton.ai.chat-model-id=chat-model"})
@AutoConfigureMockMvc
@Testcontainers
@Import(QaTestSupport.Models.class)
class QaRecoveryIT {
    private static final String QUESTION = "When is the pilot launch?";

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
    @Autowired QaRecoveryService recovery;

    QaTestSupport.Fixture fixture;
    String incoming;

    @BeforeEach
    void setUp() throws Exception {
        QaTestSupport.reset(jdbc, model);
        fixture = QaTestSupport.fixture(jdbc, handovers, packages, queue, embeddings, transactions, "recovery", false);
        incoming = mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"recovery-incoming\",\"password\":\"" + QaTestSupport.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }

    @Test
    void questionOfADeadProcessIsFailedOnceAndALateAnswerIsDiscarded() throws Exception {
        String key = UUID.randomUUID().toString();
        QuestionService.Accepted.Fresh accepted = (QuestionService.Accepted.Fresh) questions.accept(fixture.incomingId(), fixture.handoverId(), UUID.fromString(key), QUESTION);

        recovery.recover();
        assertEquals("RUNNING", jdbc.queryForObject("select status from jobs where kind='QA'", String.class));
        assertEquals(0, answers());
        mockMvc.perform(ask(key, QUESTION)).andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1")).andExpect(jsonPath("$.code", is("REQUEST_IN_PROGRESS")));
        mockMvc.perform(ask(key, "Another question")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));

        jdbc.update("update jobs set lease_until=now()-interval '1 second', deadline_at=now()-interval '1 second' where kind='QA'");
        recovery.recover();
        recovery.recover();

        assertEquals(1, answers());
        assertEquals("DEADLINE_EXCEEDED", jdbc.queryForObject("select failure_code from qa_messages where role='ASSISTANT'", String.class));
        assertEquals("COMPLETED", jdbc.queryForObject("select state from idempotency_requests where path like '%/questions'", String.class));
        mockMvc.perform(ask(key, QUESTION)).andExpect(status().isCreated()).andExpect(jsonPath("$.answerStatus", is("FAILED")));

        model.script.add("!cite:October 22|The pilot launch moved to October 22.");
        List<String> events = new ArrayList<>();
        assertNull(questions.answer(accepted, (event, data) -> events.add(event)));
        assertEquals(1, answers());
        assertEquals("error", events.getLast());
    }

    @Test
    void clientDisconnectFailsTheAnswerExactlyOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        model.script.add("!cite:October 22|The pilot launch moved to October 22. The steering meeting agreed to it.");
        QuestionService.Accepted.Fresh accepted = (QuestionService.Accepted.Fresh) questions.accept(fixture.incomingId(), fixture.handoverId(), UUID.fromString(key), QUESTION);

        tools.jackson.databind.JsonNode committed = questions.answer(accepted, (event, data) -> {
            if (event.equals("delta")) throw new IOException("client went away");
        });
        assertEquals("FAILED", committed.get("answerStatus").stringValue());
        recovery.recover();

        assertEquals(1, answers());
        assertEquals("CLIENT_DISCONNECTED", jdbc.queryForObject("select failure_code from qa_messages where role='ASSISTANT'", String.class));
        assertEquals("SUCCEEDED", jdbc.queryForObject("select status from jobs where kind='QA'", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from qa_citations", Integer.class));
        mockMvc.perform(ask(key, QUESTION)).andExpect(status().isCreated()).andExpect(jsonPath("$.answerStatus", is("FAILED")));
    }

    private int answers() { return jdbc.queryForObject("select count(*) from qa_messages where role='ASSISTANT'", Integer.class); }

    private MockHttpServletRequestBuilder ask(String key, String question) {
        return post("/api/v1/handovers/{handoverId}/questions", fixture.handoverId()).with(csrf()).cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", incoming))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode().put("question", question)));
    }
}
