package com.projectbaton.connection;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.http.MediaType;
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
class ConnectionIT {

    private static final PasswordEncoder PASSWORD_ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        AuditRows.clear(jdbcTemplate);
        jdbcTemplate.update("delete from jobs");
        jdbcTemplate.execute("truncate table organizations restart identity cascade");
        jdbcTemplate.update("insert into organizations (name) values ('Connection Test Organization')");
    }

    @Test
    void onlySysadminCreatesPublicConnectionAndPersonalOwnerComesFromPrincipal() throws Exception {
        long member = user("connection-member", "USER");
        long admin = user("connection-admin", "SYSTEM_ADMIN");
        long projectId = project();
        member(projectId, member);
        member(projectId, admin);
        String memberSession = signIn("connection-member");
        String adminSession = signIn("connection-admin");

        mockMvc.perform(post("/api/v1/projects/{projectId}/connections", projectId).with(csrf()).cookie(cookie(memberSession))
                        .header("Idempotency-Key", "72000000-0000-0000-0000-000000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"EMAIL\",\"displayName\":\"Public mail\",\"externalRef\":\"fixture://mail\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/projects/{projectId}/connections", projectId).with(csrf()).cookie(cookie(memberSession))
                        .header("Idempotency-Key", "72000000-0000-0000-0000-000000000002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"CLOUD_PROJECT_FOLDER\",\"displayName\":\"My folder\",\"externalRef\":\"fixture://member\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId", is((int) member)))
                .andExpect(jsonPath("$.channel", is("CLOUD_PROJECT_FOLDER")));
        mockMvc.perform(post("/api/v1/projects/{projectId}/connections", projectId).with(csrf()).cookie(cookie(memberSession))
                        .header("Idempotency-Key", "72000000-0000-0000-0000-000000000002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"CLOUD_PROJECT_FOLDER\",\"displayName\":\"My folder\",\"externalRef\":\"fixture://member\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId", is((int) member)));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from source_connections where project_id = ? and owner_user_id = ?", Integer.class, projectId, member));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'CREATE_SOURCE_CONNECTION'", Integer.class));

        mockMvc.perform(post("/api/v1/projects/{projectId}/connections", projectId).with(csrf()).cookie(cookie(memberSession))
                        .header("Idempotency-Key", "72000000-0000-0000-0000-000000000005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"CLOUD_PROJECT_FOLDER\",\"displayName\":\"Spoofed folder\",\"externalRef\":\"fixture://spoof\",\"ownerUserId\":999}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/projects/{projectId}/connections", projectId).with(csrf()).cookie(cookie(adminSession))
                        .header("Idempotency-Key", "72000000-0000-0000-0000-000000000003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"EMAIL\",\"displayName\":\"Public mail\",\"externalRef\":\"fixture://mail\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ownerUserId").doesNotExist());
    }

    @Test
    void disconnectPreservesCollectedRecords() throws Exception {
        long admin = user("disconnect-admin", "SYSTEM_ADMIN");
        long projectId = project();
        member(projectId, admin);
        String session = signIn("disconnect-admin");
        long connectionId = jdbcTemplate.queryForObject("""
                insert into source_connections (project_id, channel, display_name, status, health)
                values (?, 'EMAIL', 'Existing public connection', 'ACTIVE', 'NORMAL') returning id
                """, Long.class, projectId);
        long sourceId = jdbcTemplate.queryForObject("""
                insert into record_sources (source_connection_id, display_name, collected_at, project_id, external_document_id,
                                            external_version, content_hash, source_body)
                values (?, 'Existing source', now(), ?, 'file', 'v1',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'body') returning id
                """, Long.class, connectionId, projectId);
        jdbcTemplate.update("""
                insert into work_records (code, project_id, record_source_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_name, trust_status, source_version, search_text)
                values ('REC-CONNECTION', ?, ?, 'OPEN_ISSUE', 'Existing record', 'summary', 'excerpt',
                        '2026-04-01', 'External author', 'AUTO_DRAFT', 'v1', 'summary')
                """, projectId, sourceId);

        mockMvc.perform(patch("/api/v1/connections/{connectionId}", connectionId).with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "72000000-0000-0000-0000-000000000004").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISCONNECTED\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status", is("DISCONNECTED")));
        mockMvc.perform(patch("/api/v1/connections/{connectionId}", connectionId).with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "72000000-0000-0000-0000-000000000008").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isPreconditionFailed());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from work_records where record_source_id = ?", Integer.class, sourceId));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'UPDATE_SOURCE_CONNECTION'", Integer.class));
    }

    @Test
    void listsVisibleConnectionsDetailsFilesReconnectsAndDeletesEmptyConnection() throws Exception {
        long admin = user("connection-operations-admin", "SYSTEM_ADMIN");
        long projectId = project();
        member(projectId, admin, "PROJECT_MANAGER");
        String session = signIn("connection-operations-admin");
        long populatedConnection = jdbcTemplate.queryForObject("""
                insert into source_connections (project_id, channel, display_name, status, health)
                values (?, 'EMAIL', 'Visible connection', 'PAUSED', 'FAILED') returning id
                """, Long.class, projectId);
        long sourceId = jdbcTemplate.queryForObject("""
                insert into record_sources (source_connection_id, display_name, collected_at, project_id, external_document_id,
                                            external_version, content_hash, source_body, processing_status)
                values (?, 'Collected file', now(), ?, 'file', 'v1',
                        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'body', 'SUCCEEDED') returning id
                """, Long.class, populatedConnection, projectId);
        jdbcTemplate.update("""
                insert into work_records (code, project_id, record_source_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_name, trust_status, source_version, search_text)
                values ('REC-CONNECTION-FILE', ?, ?, 'OPEN_ISSUE', 'Derived record', 'summary', 'excerpt',
                        '2026-04-01', 'External author', 'AUTO_DRAFT', 'v1', 'summary')
                """, projectId, sourceId);

        mockMvc.perform(get("/api/v1/projects/{projectId}/connections", projectId).cookie(cookie(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id", is((int) populatedConnection)));
        mockMvc.perform(get("/api/v1/connections/{connectionId}", populatedConnection).cookie(cookie(session)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.collectedFileCount", is(1)));
        mockMvc.perform(get("/api/v1/connections/{connectionId}/files", populatedConnection).cookie(cookie(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id", is((int) sourceId)))
                .andExpect(jsonPath("$.items[0].derivedRecordCodes[0]", is("REC-CONNECTION-FILE")));
        mockMvc.perform(post("/api/v1/connections/{connectionId}/reconnect", populatedConnection).with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "72000000-0000-0000-0000-000000000006"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("ACTIVE")));

        long emptyConnection = jdbcTemplate.queryForObject("""
                insert into source_connections (project_id, channel, display_name, status, health)
                values (?, 'APPROVAL', 'Empty connection', 'ACTIVE', 'NORMAL') returning id
                """, Long.class, projectId);
        mockMvc.perform(delete("/api/v1/connections/{connectionId}", emptyConnection).with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "72000000-0000-0000-0000-000000000007").header("If-Match", "\"0\""))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "select count(*) from source_connections where id = ?", Integer.class, emptyConnection));
    }

    private String signIn(String employeeNo) throws Exception { return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}")).andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue(); }
    private long user(String no, String role) { return jdbcTemplate.queryForObject("insert into users (organization_id, employee_no, name, title, email, system_role, credential_hash) values ((select id from organizations where name = 'Connection Test Organization'), ?, ?, 'Engineer', ?, cast(? as system_role), ?) returning id", Long.class, no, no, no + "@example.test", role, PASSWORD_ENCODER.encode("synthetic-password")); }
    private long project() { return jdbcTemplate.queryForObject("insert into projects (code, name, delivering_company, client_company, started_on, ends_on, phase, status) values (?, 'Connection Project', 'Synthetic Delivery', 'Synthetic Client', '2026-01-01', '2026-12-31', 'Build', 'ON_TRACK') returning id", Long.class, "PRJ-CONNECTION-" + System.nanoTime()); }
    private void member(long projectId, long userId) { member(projectId, userId, "MEMBER"); }
    private void member(long projectId, long userId, String role) { jdbcTemplate.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, cast(? as project_role), current_date)", projectId, userId, role); }
    private jakarta.servlet.http.Cookie cookie(String value) { return new jakarta.servlet.http.Cookie("BATON_SESSION", value); }
}
