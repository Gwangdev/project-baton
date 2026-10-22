package com.projectbaton.connection;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.shared.AuditRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SourceProcessingIT {
    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        AuditRows.clear(jdbc);
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations(name) values ('Source Processing Organization')");
    }

    @Test
    void failedSourceIsReturnedToPendingAndQueuedForStructure() throws Exception {
        long userId = user();
        long projectId = project();
        member(projectId, userId);
        String session = signIn();
        long connectionId = connection(projectId);
        long sourceId = failedSource(connectionId, projectId);

        mvc.perform(post("/api/v1/connections/{connectionId}/files/{sourceId}/processing-attempts", connectionId, sourceId)
                        .with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "76000000-0000-0000-0000-000000000001"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/connections/" + connectionId + "/files"))
                .andExpect(jsonPath("$.sourceId", is((int) sourceId)))
                .andExpect(jsonPath("$.status", is("PENDING")));

        org.junit.jupiter.api.Assertions.assertEquals("PENDING",
                jdbc.queryForObject("select processing_status from record_sources where id=?", String.class, sourceId));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("select count(*) from jobs where kind='STRUCTURE' and target_id=?", Integer.class, sourceId));
    }

    @Test
    void pendingSourceCannotBeRetried() throws Exception {
        long userId = user();
        long projectId = project();
        member(projectId, userId);
        String session = signIn();
        long connectionId = connection(projectId);
        long sourceId = source(connectionId, projectId, "PENDING");

        mvc.perform(post("/api/v1/connections/{connectionId}/files/{sourceId}/processing-attempts", connectionId, sourceId)
                        .with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "76000000-0000-0000-0000-000000000002"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE")));

        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("select count(*) from jobs where target_id=?", Integer.class, sourceId));
    }

    @Test
    void sourceFromAnotherConnectionIsNotFound() throws Exception {
        long userId = user();
        long projectId = project();
        member(projectId, userId);
        String session = signIn();
        long actualConnectionId = connection(projectId);
        long differentConnectionId = connection(projectId);
        long sourceId = failedSource(actualConnectionId, projectId);

        mvc.perform(post("/api/v1/connections/{connectionId}/files/{sourceId}/processing-attempts", differentConnectionId, sourceId)
                        .with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "76000000-0000-0000-0000-000000000003"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameIdempotencyKeyReplaysWithoutAnotherJob() throws Exception {
        long userId = user();
        long projectId = project();
        member(projectId, userId);
        String session = signIn();
        long connectionId = connection(projectId);
        long sourceId = failedSource(connectionId, projectId);
        String path = "/api/v1/connections/{connectionId}/files/{sourceId}/processing-attempts";
        String key = "76000000-0000-0000-0000-000000000004";

        mvc.perform(post(path, connectionId, sourceId).with(csrf()).cookie(cookie(session)).header("Idempotency-Key", key))
                .andExpect(status().isAccepted());
        mvc.perform(post(path, connectionId, sourceId).with(csrf()).cookie(cookie(session)).header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status", is("PENDING")));

        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("select count(*) from jobs where kind='STRUCTURE' and target_id=?", Integer.class, sourceId));
    }

    private long user() {
        return jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,credential_hash)
                values((select id from organizations),'processing-user','Processor','Engineer','processing@example.test',?) returning id
                """, Long.class, ENCODER.encode("synthetic-password"));
    }

    private long project() {
        return jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values (?, 'Processing','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id
                """, Long.class, "PROCESSING-" + System.nanoTime());
    }

    private void member(long projectId, long userId) {
        jdbc.update("""
                insert into project_members(project_id,user_id,project_role,joined_on)
                values (?,?,'PROJECT_MANAGER',current_date)
                """, projectId, userId);
    }

    private long connection(long projectId) {
        return jdbc.queryForObject("""
                insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','public') returning id
                """, Long.class, projectId);
    }

    private long failedSource(long connectionId, long projectId) {
        return source(connectionId, projectId, "FAILED");
    }

    private long source(long connectionId, long projectId, String processingStatus) {
        return jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,
                    external_version,content_hash,source_body,processing_status,failure_code)
                values(?,'failed',now(),?,'d','v1',
                    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','body',?,
                    case when ?='FAILED' then 'STRUCTURE_ERROR' else null end)
                returning id
                """, Long.class, connectionId, projectId, processingStatus, processingStatus);
    }

    private String signIn() throws Exception {
        return mvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType("application/json")
                        .content("{\"employeeNo\":\"processing-user\",\"password\":\"synthetic-password\"}"))
                .andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }

    private jakarta.servlet.http.Cookie cookie(String value) {
        return new jakarta.servlet.http.Cookie("BATON_SESSION", value);
    }
}
