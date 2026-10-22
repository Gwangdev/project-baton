package com.projectbaton.note;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class PersonalNoteIT {

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
        jdbcTemplate.update("insert into organizations (name) values ('Personal Note Test Organization')");
    }

    @Test
    void authorSharesOnlySelectedSummaryAndReplayDoesNotCreateAnotherRecord() throws Exception {
        long author = user("note-author");
        long projectId = project("PRJ-PERSONAL-NOTE");
        member(projectId, author);
        String session = signIn("note-author");
        String original = "0123456789".repeat(10);

        long noteId = createNote(projectId, session, original);

        mockMvc.perform(get("/api/v1/projects/{projectId}/notes", projectId).cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.page.total", is(1)))
                .andExpect(jsonPath("$.items[0].body", is(original)))
                .andExpect(jsonPath("$.items[0].sharedRecordCodes.length()", is(0)));

        String share = "{\"recordType\":\"CHANGE_DECISION\",\"title\":\"Selected note\",\"summary\":\"0123456789\",\"occurredOn\":\"2026-04-01\"}";
        mockMvc.perform(post("/api/v1/notes/{noteId}/share", noteId).with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "71000000-0000-0000-0000-000000000001")
                        .contentType(MediaType.APPLICATION_JSON).content(share))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trustStatus", is("CONFIRMED")))
                .andExpect(jsonPath("$.summary", is("0123456789")))
                .andExpect(jsonPath("$.originalExcerpt", is("0123456789")));

        mockMvc.perform(post("/api/v1/notes/{noteId}/share", noteId).with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "71000000-0000-0000-0000-000000000001")
                        .contentType(MediaType.APPLICATION_JSON).content(share))
                .andExpect(status().isCreated());

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from work_records where source_note_id = ?", Integer.class, noteId));
        org.junit.jupiter.api.Assertions.assertEquals("0123456789", jdbcTemplate.queryForObject(
                "select original_excerpt from work_records where source_note_id = ?", String.class, noteId));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'SHARE_PERSONAL_NOTE' and target_id = ?", Integer.class,
                String.valueOf(noteId)));
        // 공유한 요약은 검색되는 근거가 되므로 색인 작업이 기록과 함께 커밋된다. 재실행은 아무것도 더하지 않는다.
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                select count(*) from jobs where kind='EMBED'
                  and target_id=(select id from work_records where source_note_id = ?)
                """, Integer.class, noteId));
    }

    @Test
    void anotherProjectMemberCannotReadOrShareTheAuthorsPrivateNote() throws Exception {
        long author = user("note-private-author");
        long peer = user("note-private-peer");
        long projectId = project("PRJ-PERSONAL-NOTE-PRIVATE");
        member(projectId, author);
        member(projectId, peer);
        String authorSession = signIn("note-private-author");
        String peerSession = signIn("note-private-peer");
        long noteId = createNote(projectId, authorSession, "author-only note");

        mockMvc.perform(get("/api/v1/projects/{projectId}/notes", projectId).cookie(cookie(peerSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)));
        mockMvc.perform(post("/api/v1/notes/{noteId}/share", noteId).with(csrf()).cookie(cookie(peerSession))
                        .header("Idempotency-Key", "71000000-0000-0000-0000-000000000002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recordType\":\"CHANGE_DECISION\",\"title\":\"Forbidden\",\"summary\":\"not allowed\",\"occurredOn\":\"2026-04-01\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("PROJECT_NOT_FOUND")));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "select count(*) from work_records where source_note_id = ?", Integer.class, noteId));
    }

    @Test
    void theNoteListIsPagedWithTheRealTotal() throws Exception {
        long author = user("note-page-author");
        long projectId = project("PRJ-PERSONAL-NOTE-PAGE");
        member(projectId, author);
        for (int index = 0; index < 3; index++) {
            jdbcTemplate.update("insert into personal_notes (code, project_id, author_id, body) values (?, ?, ?, 'paged')",
                    "NOTE-PAGE-" + index, projectId, author);
        }
        String session = signIn("note-page-author");

        mockMvc.perform(get("/api/v1/projects/{projectId}/notes", projectId).param("limit", "2").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.page.total", is(3)))
                .andExpect(jsonPath("$.page.limit", is(2)));
        mockMvc.perform(get("/api/v1/projects/{projectId}/notes", projectId).param("offset", "2").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.page.offset", is(2)));
        mockMvc.perform(get("/api/v1/projects/{projectId}/notes", projectId).param("limit", "0").cookie(cookie(session)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anOwnerWhoIsNotAMemberOfTheProjectIsABadRequestNamingTheField() throws Exception {
        long author = user("note-author");
        long stranger = user("note-stranger");
        long projectId = project("PRJ-NOTE-OWNER");
        member(projectId, author);
        String session = signIn("note-author");
        long noteId = createNote(projectId, session, "0123456789".repeat(10));

        mockMvc.perform(post("/api/v1/notes/{noteId}/share", noteId).with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recordType\":\"CHANGE_DECISION\",\"title\":\"Selected note\",\"summary\":\"0123456789\","
                                + "\"occurredOn\":\"2026-04-01\",\"ownerUserId\":" + stranger + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")))
                .andExpect(jsonPath("$.errors[0].field", is("ownerUserId")));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("select count(*) from work_records", Integer.class));
    }

    private long createNote(long projectId, String session, String body) throws Exception {
        mockMvc.perform(post("/api/v1/projects/{projectId}/notes", projectId).with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", "71000000-0000-0000-0000-000000000000")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.body", is(body)));
        return jdbcTemplate.queryForObject("select id from personal_notes where project_id = ?", Long.class, projectId);
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }

    private long user(String employeeNo) {
        return jdbcTemplate.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email, system_role, credential_hash)
                values ((select id from organizations where name = 'Personal Note Test Organization'), ?, ?, 'Engineer', ?, 'USER', ?)
                returning id
                """, Long.class, employeeNo, employeeNo, employeeNo + "@example.test", PASSWORD_ENCODER.encode("synthetic-password"));
    }

    private long project(String code) {
        return jdbcTemplate.queryForObject("""
                insert into projects (code, name, delivering_company, client_company, started_on, ends_on, phase, status)
                values (?, 'Personal Note Project', 'Synthetic Delivery', 'Synthetic Client',
                        '2026-01-01', '2026-12-31', 'Build', 'ON_TRACK') returning id
                """, Long.class, code);
    }

    private void member(long projectId, long userId) {
        jdbcTemplate.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, 'MEMBER', current_date)",
                projectId, userId);
    }

    private jakarta.servlet.http.Cookie cookie(String value) {
        return new jakarta.servlet.http.Cookie("BATON_SESSION", value);
    }
}
