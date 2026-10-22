package com.projectbaton.qa;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.handover.HandoverPackageWorker;
import com.projectbaton.handover.HandoverRequestService;
import com.projectbaton.jobs.EmbeddingQueue;
import com.projectbaton.jobs.EmbeddingWorker;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

/** 기본 stub 프로필은 가장 높은 근거를 인용해 답하고, 해소되지 않은 충돌이면 판단을 관리자에게 넘긴다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StubQaIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired HandoverRequestService handovers;
    @Autowired HandoverPackageWorker packages;
    @Autowired EmbeddingQueue queue;
    @Autowired EmbeddingWorker embeddings;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void reset() {
        QaTestSupport.reset(jdbc, new QaTestSupport.ScriptedChatModel());
    }

    @Test
    void stubQuotesTheTopEvidence() throws Exception {
        QaTestSupport.Fixture fixture = QaTestSupport.fixture(jdbc, handovers, packages, queue, embeddings, transactions, "stubqa", false);
        String code = jdbc.queryForObject("select code from work_records where id=?", String.class, fixture.decisionId());

        mockMvc.perform(ask(fixture, signIn("stubqa-incoming"), "When was the pilot launch moved?"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.answerStatus", is("ANSWERED")))
                .andExpect(jsonPath("$.content", startsWith(code + ": ")))
                .andExpect(jsonPath("$.citations[0].recordCode", is(code)));
    }

    @Test
    void stubNamesBothSidesOfAnUnresolvedConflict() throws Exception {
        QaTestSupport.Fixture fixture = QaTestSupport.fixture(jdbc, handovers, packages, queue, embeddings, transactions, "stubconflict", true);
        String decision = jdbc.queryForObject("select code from work_records where id=?", String.class, fixture.decisionId());
        String commitment = jdbc.queryForObject("select code from work_records where id=?", String.class, fixture.commitmentId());

        mockMvc.perform(ask(fixture, signIn("stubconflict-incoming"), "When was the pilot launch moved?"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.answerStatus", is("ANSWERED")))
                .andExpect(jsonPath("$.content", containsString(decision)))
                .andExpect(jsonPath("$.content", containsString(commitment)))
                .andExpect(jsonPath("$.citations.length()", is(2)));
    }

    private MockHttpServletRequestBuilder ask(QaTestSupport.Fixture fixture, String session, String question) {
        return post("/api/v1/handovers/{handoverId}/questions", fixture.handoverId()).with(csrf()).cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", session))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode().put("question", question)));
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + QaTestSupport.PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }
}
