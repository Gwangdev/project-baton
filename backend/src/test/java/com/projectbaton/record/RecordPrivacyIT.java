package com.projectbaton.record;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

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
class RecordPrivacyIT {

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
        jdbcTemplate.update("insert into organizations (name) values ('Record Privacy Test Organization')");
    }

    @Test
    void hidesAnotherMembersPrivateDraftAndPrivateFieldsFromListCountsAndDetail() throws Exception {
        long owner = user("record-owner");
        long peer = user("record-peer");
        long projectId = project("PRJ-RECORD-PRIVACY");
        member(projectId, owner);
        member(projectId, peer);
        long privateConnection = privateConnection(projectId, owner);
        long privateSource = recordSource(projectId, privateConnection);
        long privateDraft = sourceRecord(projectId, privateSource, owner, "REC-PRIVATE-DRAFT", "AUTO_DRAFT", "2026-03-03", "draft original");
        long privateConfirmed = sourceRecord(projectId, privateSource, owner, "REC-PRIVATE-CONFIRMED", "CONFIRMED", "2026-03-02", "confirmed private original");
        long publicSource = recordSource(projectId, publicConnection(projectId));
        sourceRecord(projectId, publicSource, owner, "REC-PUBLIC-DRAFT", "AUTO_DRAFT", "2026-03-04", "public draft original");
        long noteId = personalNote(projectId, owner);
        long noteRecord = noteRecord(projectId, noteId, owner);
        jdbcTemplate.update("insert into record_relations (project_id, from_record_id, to_record_id, relation_type) values (?, ?, ?, 'REFERENCES')",
                projectId, privateConfirmed, noteRecord);
        String ownerSession = signIn("record-owner");
        String peerSession = signIn("record-peer");

        mockMvc.perform(get("/api/v1/projects/{projectId}/records", projectId).cookie(cookie(peerSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.page.total", is(2)))
                .andExpect(jsonPath("$.items[0].code", is("REC-PRIVATE-CONFIRMED")))
                .andExpect(jsonPath("$.items[0].source").doesNotExist())
                .andExpect(jsonPath("$.items[1].code", is("REC-NOTE-CONFIRMED")))
                .andExpect(jsonPath("$.items[1].sourceNoteCode").doesNotExist());
        mockMvc.perform(get("/api/v1/projects/{projectId}/records", projectId)
                        .param("recordType", "CHANGE_DECISION").cookie(cookie(peerSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].code", is("REC-NOTE-CONFIRMED")));
        mockMvc.perform(get("/api/v1/projects/{projectId}/records", projectId).cookie(cookie(ownerSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(3)))
                .andExpect(jsonPath("$.page.total", is(3)))
                .andExpect(jsonPath("$.items[0].code", is("REC-PRIVATE-DRAFT")))
                .andExpect(jsonPath("$.items[0].source.externalRef", is("fixture://private-source")));
        mockMvc.perform(get("/api/v1/records/{recordId}", privateConfirmed).cookie(cookie(peerSession)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.code", is("REC-PRIVATE-CONFIRMED")))
                .andExpect(jsonPath("$.originalExcerpt").doesNotExist())
                .andExpect(jsonPath("$.source").doesNotExist())
                .andExpect(jsonPath("$.relations.length()", is(1)))
                .andExpect(jsonPath("$.relations[0].relatedRecordCode", is("REC-NOTE-CONFIRMED")));
        mockMvc.perform(get("/api/v1/records/{recordId}", noteRecord).cookie(cookie(peerSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceNoteCode").doesNotExist());
        mockMvc.perform(get("/api/v1/records/{recordId}", privateDraft).cookie(cookie(peerSession)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("PROJECT_NOT_FOUND")));
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }

    private long user(String employeeNo) {
        return jdbcTemplate.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email, system_role, credential_hash)
                values ((select id from organizations where name = 'Record Privacy Test Organization'), ?, ?, 'Engineer', ?, 'USER', ?)
                returning id
                """, Long.class, employeeNo, employeeNo, employeeNo + "@example.test", PASSWORD_ENCODER.encode("synthetic-password"));
    }

    private long project(String code) {
        return jdbcTemplate.queryForObject("""
                insert into projects (code, name, delivering_company, client_company, started_on, ends_on, phase, status)
                values (?, 'Record Privacy Project', 'Synthetic Delivery', 'Synthetic Client', '2026-01-01', '2026-12-31', 'Build', 'ON_TRACK')
                returning id
                """, Long.class, code);
    }

    private void member(long projectId, long userId) {
        jdbcTemplate.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, 'MEMBER', current_date)", projectId, userId);
    }

    private long privateConnection(long projectId, long ownerId) {
        return jdbcTemplate.queryForObject("""
                insert into source_connections (project_id, owner_user_id, channel, display_name, external_ref, status, health)
                values (?, ?, 'CLOUD_PROJECT_FOLDER', 'Private source', 'fixture://private-source', 'ACTIVE', 'NORMAL')
                returning id
                """, Long.class, projectId, ownerId);
    }

    private long publicConnection(long projectId) {
        return jdbcTemplate.queryForObject("""
                insert into source_connections (project_id, channel, display_name, status, health)
                values (?, 'EMAIL', 'Public source', 'ACTIVE', 'NORMAL') returning id
                """, Long.class, projectId);
    }

    private long recordSource(long projectId, long connectionId) {
        return jdbcTemplate.queryForObject("""
                insert into record_sources (source_connection_id, display_name, external_ref, collected_at, project_id,
                                            external_document_id, external_version, content_hash, source_body)
                values (?, 'Private source file', 'fixture://private-source', now(), ?, 'fixture-file', 'v1',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'private source body')
                returning id
                """, Long.class, connectionId, projectId);
    }

    private long sourceRecord(long projectId, long sourceId, long ownerId, String code, String trustStatus,
                              String occurredOn, String originalExcerpt) {
        return jdbcTemplate.queryForObject("""
                insert into work_records (code, project_id, record_source_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_id, author_name, owner_user_id, trust_status, confirmed_by,
                                          confirmed_at, source_version, search_text)
                values (?, ?, ?, 'OPEN_ISSUE', ?, 'shared summary', ?, cast(? as date), ?, 'External author', ?, cast(? as record_trust_status),
        case when ? = 'CONFIRMED' then ? else null end, case when ? = 'CONFIRMED' then now() else null end, ?, 'shared summary')
                returning id
                """, Long.class, code, projectId, sourceId, code, originalExcerpt, occurredOn, ownerId, ownerId,
                trustStatus, trustStatus, ownerId, trustStatus, code);
    }

    private long personalNote(long projectId, long authorId) {
        return jdbcTemplate.queryForObject("""
                insert into personal_notes (code, project_id, author_id, body) values ('NOTE-PRIVATE', ?, ?, 'private note body') returning id
                """, Long.class, projectId, authorId);
    }

    private long noteRecord(long projectId, long noteId, long authorId) {
        return jdbcTemplate.queryForObject("""
                insert into work_records (code, project_id, source_note_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_id, author_name, trust_status, confirmed_by, confirmed_at, search_text)
                values ('REC-NOTE-CONFIRMED', ?, ?, 'CHANGE_DECISION', 'Shared note record', 'selected summary', 'selected summary',
                        '2026-03-01', ?, 'Record owner', 'CONFIRMED', ?, now(), 'selected summary')
                returning id
                """, Long.class, projectId, noteId, authorId, authorId);
    }

    private jakarta.servlet.http.Cookie cookie(String value) {
        return new jakarta.servlet.http.Cookie("BATON_SESSION", value);
    }
}
