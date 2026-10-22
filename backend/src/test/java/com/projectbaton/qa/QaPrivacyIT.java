package com.projectbaton.qa;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"baton.ai.profile=local", "baton.ai.embedding-model-id=test-hashed-256", "baton.ai.chat-model-id=chat-model"})
@AutoConfigureMockMvc
@Testcontainers
@Import(QaTestSupport.Models.class)
class QaPrivacyIT {
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

    @BeforeEach
    void setUp() {
        QaTestSupport.reset(jdbc, model);
        fixture = QaTestSupport.fixture(jdbc, handovers, packages, queue, embeddings, transactions, "privacy", false);
    }

    @Test
    void onlyTheIncomingOwnerMayAskOrReadTheConversation() throws Exception {
        QaTestSupport.user(jdbc, "privacy-outsider");
        for (String employee : List.of("privacy-manager", "privacy-outgoing", "privacy-member")) {
            String session = signIn(employee);
            mockMvc.perform(ask(session, UUID.randomUUID().toString(), "zebra")).andExpect(status().isForbidden());
            mockMvc.perform(history(session, "")).andExpect(status().isForbidden());
        }
        String outsider = signIn("privacy-outsider");
        mockMvc.perform(ask(outsider, UUID.randomUUID().toString(), "zebra")).andExpect(status().isNotFound());
        mockMvc.perform(history(outsider, "")).andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("select count(*) from qa_messages", Integer.class));
    }

    @Test
    void historyShowsOnlyTheCallersConversationInOrderWithCursorPaging() throws Exception {
        String incoming = signIn("privacy-incoming");
        model.script.add("!cite:October 22|The pilot launch moved to October 22.");
        mockMvc.perform(ask(incoming, UUID.randomUUID().toString(), "When is the pilot launch?")).andExpect(status().isCreated());
        mockMvc.perform(ask(incoming, UUID.randomUUID().toString(), "zebra warehouse invoice")).andExpect(status().isCreated());
        jdbc.update("""
                insert into qa_messages(handover_id,generation_id,session_user_id,role,content)
                select ?, generation.id, ?, 'USER', 'Another person''s private question'
                from handover_generations generation where generation.handover_id=? and generation.generation=1
                """, fixture.handoverId(), fixture.managerId(), fixture.handoverId());

        JsonNode all = json.readTree(mockMvc.perform(history(incoming, "")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Another person")))).andReturn().getResponse().getContentAsString());
        assertEquals(4, all.get("page").get("total").intValue());
        List<String> shape = new ArrayList<>();
        all.get("items").forEach(item -> shape.add(item.get("role").stringValue() + ":" + (item.has("answerStatus") ? item.get("answerStatus").stringValue() : "-")));
        assertEquals(List.of("USER:-", "ASSISTANT:ANSWERED", "USER:-", "ASSISTANT:NO_EVIDENCE"), shape);
        assertEquals(1, all.get("items").get(1).get("citations").size());

        JsonNode first = json.readTree(mockMvc.perform(history(incoming, "?limit=3")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(3, first.get("items").size());
        String cursor = first.get("page").get("nextCursor").stringValue();
        mockMvc.perform(history(incoming, "?limit=3&cursor=" + cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].answerStatus", is("NO_EVIDENCE")))
                .andExpect(jsonPath("$.page.nextCursor").value((Object) null));
        mockMvc.perform(history(incoming, "?offset=1&cursor=" + cursor)).andExpect(status().isBadRequest());
        mockMvc.perform(history(incoming, "?offset=10001")).andExpect(status().isBadRequest());
        mockMvc.perform(history(incoming, "?limit=0")).andExpect(status().isBadRequest());
        mockMvc.perform(history(incoming, "?limit=101")).andExpect(status().isBadRequest());
        mockMvc.perform(history(incoming, "?cursor=not-base64")).andExpect(status().isBadRequest());
    }

    @Test
    void citationsOfAPersonalFolderRecordHideTheSourceName() throws Exception {
        String incoming = signIn("privacy-incoming");
        model.script.add("!cite:month end|The legacy batch fails at month end.");

        mockMvc.perform(ask(incoming, UUID.randomUUID().toString(), "Why does the month end batch fail?"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.answerStatus", is("ANSWERED")))
                .andExpect(jsonPath("$.citations[0].sourceName", is("비공개 출처")))
                .andExpect(content().string(not(containsString("outgoing-private-notes.docx"))))
                .andExpect(content().string(not(containsString("outgoing folder"))));
    }

    @Test
    void revokingAccessStopsTheStreamBeforeTheNextChunk() throws Exception {
        String key = UUID.randomUUID().toString();
        model.script.add("!cite:October 22|The pilot launch moved to October 22. The steering meeting agreed to it.");
        QuestionService.Accepted accepted = questions.accept(fixture.incomingId(), fixture.handoverId(), UUID.fromString(key), "When is the pilot launch?");
        List<String> events = new ArrayList<>();

        questions.answer((QuestionService.Accepted.Fresh) accepted, (event, data) -> {
            events.add(event);
            if (event.equals("delta")) {
                jdbc.update("update project_members set left_on=current_date where project_id=? and user_id=?", fixture.projectId(), fixture.incomingId());
            }
        });

        assertEquals(List.of("meta", "evidence", "delta", "error"), events);
        assertEquals("ACCESS_REVOKED", jdbc.queryForObject("select failure_code from qa_messages where role='ASSISTANT'", String.class));
        // 계정은 여전히 로그인할 수 있고 프로젝트 배정만 끝났으므로, 재실행은 찾기 전에 거절된다.
        mockMvc.perform(ask(signIn("privacy-incoming"), key, "When is the pilot launch?")).andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder ask(String session, String key, String question) {
        return post("/api/v1/handovers/{handoverId}/questions", fixture.handoverId()).with(csrf()).cookie(cookie(session))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode().put("question", question)));
    }

    private MockHttpServletRequestBuilder history(String session, String query) {
        return get("/api/v1/handovers/" + fixture.handoverId() + "/questions" + query).cookie(cookie(session));
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + QaTestSupport.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }
    private static jakarta.servlet.http.Cookie cookie(String value) { return new jakarta.servlet.http.Cookie("BATON_SESSION", value); }
}
