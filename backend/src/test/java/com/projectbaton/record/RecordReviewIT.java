package com.projectbaton.record;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.shared.AuditRows;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecordReviewIT {

    private static final PasswordEncoder PASSWORD_ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        AuditRows.clear(jdbcTemplate);
        jdbcTemplate.update("delete from jobs");
        jdbcTemplate.execute("truncate table organizations restart identity cascade");
        jdbcTemplate.update("insert into organizations (name) values ('Record Review Test Organization')");
    }

    @Test
    void anOwnerWhoIsNotAMemberOfTheProjectIsABadRequestNamingTheField() throws Exception {
        long manager = user("record-review-manager");
        long stranger = user("record-review-stranger");
        long projectId = project("PRJ-REVIEW-OWNER");
        member(projectId, manager, "PROJECT_MANAGER");
        long recordId = autoDraft(projectId);

        mockMvc.perform(post("/api/v1/records/{recordId}/review", recordId)
                        .with(csrf()).cookie(cookie(signIn("record-review-manager")))
                        .header("Idempotency-Key", "61000000-0000-0000-0000-000000000077").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CORRECT\",\"title\":\"Corrected record\",\"summary\":\"Corrected summary\","
                                + "\"ownerUserId\":" + stranger + ",\"dueOn\":\"2026-05-01\",\"changeReason\":\"Owner named\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")))
                .andExpect(jsonPath("$.errors[0].field", is("ownerUserId")));
        org.junit.jupiter.api.Assertions.assertEquals("AUTO_DRAFT", jdbcTemplate.queryForObject(
                "select trust_status::text from work_records where id = ?", String.class, recordId));
    }

    @Test
    void projectManagerCorrectsDraftAndCreatesRevisionWithNewVersion() throws Exception {
        long manager = user("record-review-manager");
        long projectId = project("PRJ-REVIEW-CORRECT");
        member(projectId, manager, "PROJECT_MANAGER");
        long recordId = autoDraft(projectId);
        String session = signIn("record-review-manager");

        mockMvc.perform(post("/api/v1/records/{recordId}/review", recordId)
                        .with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "61000000-0000-0000-0000-000000000001")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"CORRECT","title":"Corrected record","summary":"Corrected summary",
                                 "ownerUserId":null,"dueOn":"2026-05-01","changeReason":"Source wording clarified"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.trustStatus", is("CORRECTED")))
                .andExpect(jsonPath("$.title", is("Corrected record")))
                .andExpect(jsonPath("$.version", is(1)));

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from record_revisions where work_record_id = ?", Integer.class, recordId));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'REVIEW_RECORD' and target_id = ?", Integer.class, String.valueOf(recordId)));
        org.junit.jupiter.api.Assertions.assertEquals("Original record", jdbcTemplate.queryForObject(
                "select before_data ->> 'title' from record_revisions where work_record_id = ?", String.class, recordId));
        org.junit.jupiter.api.Assertions.assertEquals("Corrected record", jdbcTemplate.queryForObject(
                "select after_data ->> 'title' from record_revisions where work_record_id = ?", String.class, recordId));

        mockMvc.perform(post("/api/v1/records/{recordId}/review", recordId)
                        .with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "61000000-0000-0000-0000-000000000004")
                        .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE")));
    }

    @Test
    void simultaneousConfirmAndCorrectAllowExactlyOneReviewAndKeepVersionAndRevisionConsistent() throws Exception {
        long manager = user("record-review-concurrent-manager");
        long projectId = project("PRJ-REVIEW-CONCURRENT");
        member(projectId, manager, "PROJECT_MANAGER");
        long recordId = autoDraft(projectId);
        String session = signIn("record-review-concurrent-manager");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> confirm = executor.submit(reviewAfterStart(ready, start, recordId, session,
                    "61000000-0000-0000-0000-000000000002", "{\"action\":\"CONFIRM\"}"));
            Future<MvcResult> correct = executor.submit(reviewAfterStart(ready, start, recordId, session,
                    "61000000-0000-0000-0000-000000000003", """
                            {"action":"CORRECT","title":"Concurrent correction","changeReason":"Corrected concurrently"}
                            """));
            ready.await();
            start.countDown();
            int confirmStatus = confirm.get().getResponse().getStatus();
            int correctStatus = correct.get().getResponse().getStatus();
            org.junit.jupiter.api.Assertions.assertTrue(
                    (confirmStatus == 200 && correctStatus == 412) || (confirmStatus == 412 && correctStatus == 200),
                    () -> "statuses were " + confirmStatus + " and " + correctStatus);
        } finally {
            executor.shutdownNow();
        }
        String trustStatus = jdbcTemplate.queryForObject("select trust_status from work_records where id = ?", String.class, recordId);
        int revisionCount = jdbcTemplate.queryForObject("select count(*) from record_revisions where work_record_id = ?", Integer.class, recordId);
        org.junit.jupiter.api.Assertions.assertEquals(1L, jdbcTemplate.queryForObject("select version from work_records where id = ?", Long.class, recordId));
        org.junit.jupiter.api.Assertions.assertEquals("CORRECTED".equals(trustStatus) ? 1 : 0, revisionCount);
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'REVIEW_RECORD' and target_id = ?", Integer.class, String.valueOf(recordId)));
    }

    private Callable<MvcResult> reviewAfterStart(CountDownLatch ready, CountDownLatch start, long recordId, String session,
                                                   String key, String body) {
        return () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(post("/api/v1/records/{recordId}/review", recordId)
                            .with(csrf()).cookie(cookie(session)).header("Idempotency-Key", key).header("If-Match", "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        };
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }

    private long user(String employeeNo) {
        return jdbcTemplate.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email, system_role, credential_hash)
                values ((select id from organizations where name = 'Record Review Test Organization'), ?, ?, 'Engineer', ?, 'USER', ?)
                returning id
                """, Long.class, employeeNo, employeeNo, employeeNo + "@example.test", PASSWORD_ENCODER.encode("synthetic-password"));
    }

    private long project(String code) {
        return jdbcTemplate.queryForObject("""
                insert into projects (code, name, delivering_company, client_company, started_on, ends_on, phase, status)
                values (?, 'Record Review Project', 'Synthetic Delivery', 'Synthetic Client',
                        '2026-01-01', '2026-12-31', 'Build', 'ON_TRACK') returning id
                """, Long.class, code);
    }

    private void member(long projectId, long userId, String role) {
        jdbcTemplate.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, cast(? as project_role), current_date)",
                projectId, userId, role);
    }

    private long autoDraft(long projectId) {
        long connectionId = jdbcTemplate.queryForObject("""
                insert into source_connections (project_id, channel, display_name, status, health)
                values (?, 'EMAIL', 'Public source', 'ACTIVE', 'NORMAL') returning id
                """, Long.class, projectId);
        long sourceId = jdbcTemplate.queryForObject("""
                insert into record_sources (source_connection_id, display_name, collected_at, project_id,
                                            external_document_id, external_version, content_hash, source_body)
                values (?, 'Source file', now(), ?, 'fixture-file', 'v1',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'source body') returning id
                """, Long.class, connectionId, projectId);
        return jdbcTemplate.queryForObject("""
                insert into work_records (code, project_id, record_source_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_name, trust_status, source_version, search_text)
                values ('REC-REVIEW', ?, ?, 'OPEN_ISSUE', 'Original record', 'Original summary', 'original excerpt',
                        '2026-03-01', 'External author', 'AUTO_DRAFT', 'v1', 'Original summary') returning id
                """, Long.class, projectId, sourceId);
    }

    private jakarta.servlet.http.Cookie cookie(String value) {
        return new jakarta.servlet.http.Cookie("BATON_SESSION", value);
    }
}
