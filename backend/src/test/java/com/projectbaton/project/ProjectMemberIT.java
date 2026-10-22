package com.projectbaton.project;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.shared.AuditRows;
import java.util.UUID;

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
class ProjectMemberIT {

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

    @Autowired
    private ProjectAccessPolicy accessPolicy;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate table organizations restart identity cascade");
        AuditRows.clear(jdbcTemplate);
        jdbcTemplate.update("delete from idempotency_requests");
        jdbcTemplate.update("insert into organizations (name) values ('Membership Test Organization')");
    }

    @Test
    void allowsOnlyASystemAdminToListAndAssignProjectMembers() throws Exception {
        long projectId = project("PRJ-MEMBERS");
        long assignee = user("member-target", "USER");
        String regularSession = signIn("regular-member-user", "USER");
        String adminSession = signIn("member-admin", "SYSTEM_ADMIN");

        mockMvc.perform(get("/api/v1/admin/projects/{projectId}/members", projectId).cookie(cookie(regularSession)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/projects/{projectId}/members", projectId).cookie(cookie(adminSession)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()", is(0)));
        mockMvc.perform(post("/api/v1/admin/projects/{projectId}/members", projectId).cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + assignee + ",\"projectRole\":\"MEMBER\",\"joinedOn\":\"2026-01-01\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.userId", is((int) assignee)))
                .andExpect(jsonPath("$.projectRole", is("MEMBER")));
    }

    @Test
    void rejectsFutureAndDuplicateAssignments() throws Exception {
        long projectId = project("PRJ-ASSIGNMENT-VALIDATION");
        long assignee = user("assignment-target", "USER");
        String admin = signIn("assignment-admin", "SYSTEM_ADMIN");
        mockMvc.perform(post("/api/v1/admin/projects/{projectId}/members", projectId).cookie(cookie(admin)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + assignee + ",\"projectRole\":\"MEMBER\",\"joinedOn\":\"2999-01-01\"}"))
                .andExpect(status().isBadRequest());
        String body = "{\"userId\":" + assignee + ",\"projectRole\":\"MEMBER\",\"joinedOn\":\"2026-01-01\"}";
        String replayKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/admin/projects/{projectId}/members", projectId).cookie(cookie(admin)).with(csrf().asHeader())
                        .header("Idempotency-Key", replayKey).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/projects/{projectId}/members", projectId).cookie(cookie(admin)).with(csrf().asHeader())
                        .header("Idempotency-Key", replayKey).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/admin/projects/{projectId}/members", projectId).cookie(cookie(admin)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'CREATE_PROJECT_MEMBER'", Integer.class));
    }

    @Test
    void endsAMembershipWithAValidVersionAndBlocksFutureAccess() throws Exception {
        long projectId = project("PRJ-END");
        long assignee = user("ending-member", "USER");
        long memberId = member(projectId, assignee, "MEMBER", "2026-01-01");
        String admin = signIn("ending-admin", "SYSTEM_ADMIN");

        mockMvc.perform(patch("/api/v1/admin/projects/{projectId}/members/{memberId}", projectId, memberId)
                        .cookie(cookie(admin)).with(csrf().asHeader()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leftOn\":\"2026-01-02\"}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(patch("/api/v1/admin/projects/{projectId}/members/{memberId}", projectId, memberId)
                        .cookie(cookie(admin)).with(csrf().asHeader()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"9\"").contentType(MediaType.APPLICATION_JSON).content("{\"leftOn\":\"2026-01-02\"}"))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(patch("/api/v1/admin/projects/{projectId}/members/{memberId}", projectId, memberId)
                        .cookie(cookie(admin)).with(csrf().asHeader()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"leftOn\":\"2026-01-02\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.leftOn", is("2026-01-02")));
        org.junit.jupiter.api.Assertions.assertThrows(ProjectAccessPolicy.ProjectNotFoundException.class,
                () -> accessPolicy.requireMember(assignee, projectId));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'UPDATE_PROJECT_MEMBER'", Integer.class));
    }

    @Test
    void reassigningAUserWhoseAssignmentEndedRestoresThatAssignment() throws Exception {
        long projectId = project("PRJ-RETURN");
        long assignee = user("returning-member", "USER");
        long memberId = member(projectId, assignee, "MEMBER", "2026-01-01");
        jdbcTemplate.update("update project_members set left_on = '2026-02-01' where id = ?", memberId);
        String admin = signIn("returning-admin", "SYSTEM_ADMIN");

        mockMvc.perform(post("/api/v1/admin/projects/{projectId}/members", projectId).cookie(cookie(admin)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + assignee + ",\"projectRole\":\"INCOMING_OWNER\",\"joinedOn\":\"" + com.projectbaton.shared.BusinessCalendar.today() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is((int) memberId)))
                .andExpect(jsonPath("$.projectRole", is("INCOMING_OWNER")))
                .andExpect(jsonPath("$.leftOn").value(org.hamcrest.Matchers.nullValue()));
        accessPolicy.requireMember(assignee, projectId);
        mockMvc.perform(post("/api/v1/admin/projects/{projectId}/members", projectId).cookie(cookie(admin)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + assignee + ",\"projectRole\":\"MEMBER\",\"joinedOn\":\"2026-01-01\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void theMemberListIsPaged() throws Exception {
        long projectId = project("PRJ-MEMBER-PAGE");
        for (int index = 0; index < 3; index++) {
            member(projectId, user("paged-member-" + index, "USER"), "MEMBER", "2026-01-01");
        }
        String admin = signIn("paged-admin", "SYSTEM_ADMIN");

        mockMvc.perform(get("/api/v1/admin/projects/{projectId}/members", projectId).param("limit", "2").cookie(cookie(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.page.total", is(3)))
                .andExpect(jsonPath("$.page.limit", is(2)));
        mockMvc.perform(get("/api/v1/admin/projects/{projectId}/members", projectId).param("limit", "101").cookie(cookie(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesAnUnreferencedMemberWithoutAResponseBodyAndAudits() throws Exception {
        long projectId = project("PRJ-DELETE-MEMBER");
        long assignee = user("deletable-member", "USER");
        long memberId = member(projectId, assignee, "MEMBER", "2026-01-01");
        String admin = signIn("delete-admin", "SYSTEM_ADMIN");
        mockMvc.perform(delete("/api/v1/admin/projects/{projectId}/members/{memberId}", projectId, memberId)
                        .cookie(cookie(admin)).with(csrf().asHeader()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'DELETE_PROJECT_MEMBER'", Integer.class));
    }

    @Test
    void rejectsHardDeleteWhenTheMemberConfirmedAProjectRecord() throws Exception {
        long projectId = project("PRJ-MEMBER-REFERENCE");
        long assignee = user("record-confirming-member", "USER");
        long memberId = member(projectId, assignee, "MEMBER", "2026-01-01");
        long noteId = jdbcTemplate.queryForObject("insert into personal_notes (code, project_id, author_id, body) values ('NOTE-MEMBER-REF', ?, ?, 'fixture') returning id", Long.class, projectId, assignee);
        jdbcTemplate.update("insert into work_records (code, project_id, source_note_id, record_type, title, summary, original_excerpt, occurred_on, author_name, trust_status, confirmed_by, confirmed_at, search_text) values ('REC-MEMBER-REF', ?, ?, 'OPEN_ISSUE', 'fixture', 'fixture', 'fixture', '2026-01-01', 'fixture', 'CONFIRMED', ?, now(), 'fixture')", projectId, noteId, assignee);
        String admin = signIn("reference-delete-admin", "SYSTEM_ADMIN");
        mockMvc.perform(delete("/api/v1/admin/projects/{projectId}/members/{memberId}", projectId, memberId)
                .cookie(cookie(admin)).with(csrf().asHeader()).header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("REFERENCE_CONFLICT")))
                .andExpect(jsonPath("$.references[0].resource", is("confirmed_work_records")))
                .andExpect(jsonPath("$.references[0].count", is(1)));
    }

    @Test
    void rejectsHardDeleteWhenTheMemberCreatedAProjectRecord() throws Exception {
        DeleteFixture fixture = deleteFixture("created-record-member");
        long noteId = personalNote(fixture.projectId(), fixture.memberUserId(), "NOTE-MEMBER-CREATED");
        jdbcTemplate.update("insert into work_records (code, project_id, source_note_id, record_type, title, summary, original_excerpt, occurred_on, author_id, author_name, search_text) values ('REC-MEMBER-CREATED', ?, ?, 'OPEN_ISSUE', 'fixture', 'fixture', 'fixture', '2026-01-01', ?, 'fixture', 'fixture')", fixture.projectId(), noteId, fixture.memberUserId());
        assertHardDeleteBlocked(fixture, "created_work_records");
    }

    @Test
    void rejectsHardDeleteWhenTheMemberRequestedAHandover() throws Exception {
        DeleteFixture fixture = deleteFixture("handover-requesting-member");
        handover(fixture.projectId(), fixture.memberUserId(), otherUser("request-outgoing"), otherUser("request-incoming"), null, "HND-MEMBER-REQUEST");
        assertHardDeleteBlocked(fixture, "handover_requests");
    }

    @Test
    void rejectsHardDeleteWhenTheMemberIsAnOutgoingHandoverOwner() throws Exception {
        DeleteFixture fixture = deleteFixture("handover-outgoing-member");
        handover(fixture.projectId(), otherUser("outgoing-requester"), fixture.memberUserId(), otherUser("outgoing-incoming"), null, "HND-MEMBER-OUTGOING");
        assertHardDeleteBlocked(fixture, "handover_outgoing_owners");
    }

    @Test
    void rejectsHardDeleteWhenTheMemberReceivesAHandover() throws Exception {
        DeleteFixture fixture = deleteFixture("handover-receiving-member");
        handover(fixture.projectId(), otherUser("receiving-requester"), otherUser("receiving-outgoing"), fixture.memberUserId(), null, "HND-MEMBER-RECEIVING");
        assertHardDeleteBlocked(fixture, "handover_recipients");
    }

    @Test
    void rejectsHardDeleteWhenTheMemberApprovedAHandover() throws Exception {
        DeleteFixture fixture = deleteFixture("handover-approving-member");
        handover(fixture.projectId(), otherUser("approval-requester"), otherUser("approval-outgoing"), otherUser("approval-incoming"), fixture.memberUserId(), "HND-MEMBER-APPROVAL");
        assertHardDeleteBlocked(fixture, "handover_approvals");
    }

    @Test
    void rejectsHardDeleteWhenTheMemberHasAssessmentHistory() throws Exception {
        DeleteFixture fixture = deleteFixture("assessment-member");
        long handoverId = handover(fixture.projectId(), otherUser("assessment-requester"), otherUser("assessment-outgoing"), otherUser("assessment-incoming"), null, "HND-MEMBER-ASSESSMENT");
        long generationId = jdbcTemplate.queryForObject("""
                insert into handover_generations(handover_id,generation,project_id,created_by)
                values(?,1,?,?) returning id
                """, Long.class, handoverId, fixture.projectId(), otherUser("assessment-generation-creator"));
        long assessmentId = jdbcTemplate.queryForObject("""
                insert into assessments(handover_id,generation_id,generation)
                values(?,?,1) returning id
                """, Long.class, handoverId, generationId);
        jdbcTemplate.update("insert into assessment_attempts(assessment_id,generation_id,attempt_no,taken_by,total) values(?,?,1,?,5)",
                assessmentId, generationId, fixture.memberUserId());
        assertHardDeleteBlocked(fixture, "assessment_attempts");
    }

    private DeleteFixture deleteFixture(String employeeNo) throws Exception {
        long projectId = project("PRJ-" + employeeNo.toUpperCase());
        long memberUserId = user(employeeNo, "USER");
        return new DeleteFixture(projectId, memberUserId, member(projectId, memberUserId, "MEMBER", "2026-01-01"), signIn(employeeNo + "-admin", "SYSTEM_ADMIN"));
    }

    private void assertHardDeleteBlocked(DeleteFixture fixture, String resource) throws Exception {
        mockMvc.perform(delete("/api/v1/admin/projects/{projectId}/members/{memberId}", fixture.projectId(), fixture.memberId())
                        .cookie(cookie(fixture.adminSession())).with(csrf().asHeader()).header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("REFERENCE_CONFLICT")))
                .andExpect(jsonPath("$.references.length()", is(1)))
                .andExpect(jsonPath("$.references[0].resource", is(resource)))
                .andExpect(jsonPath("$.references[0].count", is(1)));
    }

    private long personalNote(long projectId, long authorId, String code) {
        return jdbcTemplate.queryForObject("insert into personal_notes (code, project_id, author_id, body) values (?, ?, ?, 'fixture') returning id", Long.class, code, projectId, authorId);
    }

    private long handover(long projectId, long requestedBy, long outgoingOwnerId, long incomingOwnerId, Long approvedBy, String code) {
        return jdbcTemplate.queryForObject("insert into handovers (code, project_id, requested_by, outgoing_owner_id, incoming_owner_id, effective_on, scope_from, scope_to, approved_by) values (?, ?, ?, ?, ?, '2026-02-01', '2026-01-01', '2026-01-31', ?) returning id", Long.class, code, projectId, requestedBy, outgoingOwnerId, incomingOwnerId, approvedBy);
    }

    private long otherUser(String employeeNo) {
        return user(employeeNo, "USER");
    }

    private String signIn(String employeeNo, String role) throws Exception {
        user(employeeNo, role);
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }

    private long user(String employeeNo, String role) {
        return jdbcTemplate.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email, system_role, credential_hash)
                values ((select id from organizations where name = 'Membership Test Organization'), ?, ?, 'Engineer', ?, cast(? as system_role), ?)
                returning id
                """, Long.class, employeeNo, employeeNo, employeeNo + "@example.test", role,
                PASSWORD_ENCODER.encode("synthetic-password"));
    }

    private long project(String code) {
        return jdbcTemplate.queryForObject("""
                insert into projects (code, name, delivering_company, client_company, started_on, ends_on, phase, status)
                values (?, ?, 'Synthetic Delivery', 'Synthetic Client', '2026-01-01', '2026-12-31', 'Build', 'ON_TRACK')
                returning id
                """, Long.class, code, "Project " + code);
    }

    private long member(long projectId, long userId, String role, String joinedOn) {
        return jdbcTemplate.queryForObject("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, cast(? as project_role), cast(? as date)) returning id",
                Long.class, projectId, userId, role, joinedOn);
    }

    private jakarta.servlet.http.Cookie cookie(String value) {
        return new jakarta.servlet.http.Cookie("BATON_SESSION", value);
    }

    private record DeleteFixture(long projectId, long memberUserId, long memberId, String adminSession) {
    }
}
