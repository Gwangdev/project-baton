package com.projectbaton.project;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.projectbaton.shared.AuditRows;
import java.util.List;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProjectQueryIT {

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
        jdbcTemplate.update("insert into organizations (name) values ('Project Query Test Organization')");
    }

    @Test
    void listsOnlyCurrentMembershipProjectsWithPageBoundaries() throws Exception {
        long actor = user("query-list-user", "USER");
        for (int index = 1; index <= 21; index++) {
            long projectId = project("PRJ-LIST-" + index);
            member(projectId, actor, "MEMBER");
        }
        project("PRJ-NOT-A-MEMBER");
        long expiredProject = project("PRJ-MEMBERSHIP-EXPIRED");
        member(expiredProject, actor, "MEMBER");
        jdbcTemplate.update("update project_members set left_on = current_date where project_id = ? and user_id = ?", expiredProject, actor);
        String session = signIn("query-list-user");

        mockMvc.perform(get("/api/v1/projects?limit=20&offset=20").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.page.total", is(21)))
                .andExpect(jsonPath("$.page.limit", is(20)))
                .andExpect(jsonPath("$.page.offset", is(20)));
        mockMvc.perform(get("/api/v1/projects?limit=100&offset=10000").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)))
                .andExpect(jsonPath("$.page.limit", is(100)));
        mockMvc.perform(get("/api/v1/projects?limit=101").cookie(cookie(session)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    void returnsAccessibleProjectDetailsAndHidesAnotherProject() throws Exception {
        long actor = user("query-detail-user", "USER");
        long accessibleProject = project("PRJ-DETAIL-ACCESSIBLE");
        member(accessibleProject, actor, "PROJECT_MANAGER");
        long noteId = personalNote(accessibleProject, actor, "NOTE-DETAIL");
        draftFromOwnFolder(accessibleProject, actor, "REC-PENDING");
        workRecord(accessibleProject, noteId, actor, "REC-RISK", "CONFIRMED", "OPEN_ISSUE");
        long hiddenProject = project("PRJ-DETAIL-HIDDEN");
        String session = signIn("query-detail-user");

        mockMvc.perform(get("/api/v1/projects/{projectId}", accessibleProject).cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.myRole", is("PROJECT_MANAGER")))
                .andExpect(jsonPath("$.pendingRecordCount", is(1)))
                .andExpect(jsonPath("$.openRiskCount", is(1)));
        mockMvc.perform(get("/api/v1/projects/{projectId}", hiddenProject).cookie(cookie(session)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("PROJECT_NOT_FOUND")));
    }

    @Test
    void returnsEmptyProjectListForAnAuthenticatedUserWithoutCurrentMembership() throws Exception {
        user("query-empty-user", "USER");
        project("PRJ-EMPTY-HIDDEN");
        String session = signIn("query-empty-user");

        mockMvc.perform(get("/api/v1/projects").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)))
                .andExpect(jsonPath("$.page.total", is(0)));
    }

    @Test
    void exposesAllConnectionStatusToSystemAdminWithoutGrantingProjectContent() throws Exception {
        user("query-dashboard-admin", "SYSTEM_ADMIN");
        long firstProject = project("PRJ-DASHBOARD-ADMIN-FIRST");
        long secondProject = project("PRJ-DASHBOARD-ADMIN-SECOND");
        connection(firstProject, "CONNECTION-ADMIN-FIRST");
        connection(secondProject, "CONNECTION-ADMIN-SECOND");
        String session = signIn("query-dashboard-admin");

        mockMvc.perform(get("/api/v1/dashboard").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectCount", is(0)))
                .andExpect(jsonPath("$.projects.length()", is(0)))
                .andExpect(jsonPath("$.connectionHealth.length()", is(2)));
    }

    @Test
    void limitsDashboardProjectAndConnectionPreviewsToTwenty() throws Exception {
        long actor = user("query-dashboard-limit-user", "USER");
        for (int index = 1; index <= 21; index++) {
            long projectId = project("PRJ-DASHBOARD-LIMIT-" + index);
            member(projectId, actor, "MEMBER");
            connection(projectId, "CONNECTION-DASHBOARD-LIMIT-" + index);
        }
        String session = signIn("query-dashboard-limit-user");

        mockMvc.perform(get("/api/v1/dashboard").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectCount", is(21)))
                .andExpect(jsonPath("$.projects.length()", is(20)))
                .andExpect(jsonPath("$.connectionHealth.length()", is(20)));
    }

    @Test
    void scopesDashboardCountsAndConnectionHealthToCurrentMemberships() throws Exception {
        long actor = user("query-dashboard-user", "USER");
        long accessibleProject = project("PRJ-DASHBOARD-ACCESSIBLE");
        member(accessibleProject, actor, "MEMBER");
        long noteId = personalNote(accessibleProject, actor, "NOTE-DASHBOARD");
        draftFromOwnFolder(accessibleProject, actor, "REC-DASHBOARD-PENDING");
        workRecord(accessibleProject, noteId, actor, "REC-DASHBOARD-RISK", "CONFIRMED", "OPEN_ISSUE");
        connection(accessibleProject, "CONNECTION-ACCESSIBLE");
        long hiddenProject = project("PRJ-DASHBOARD-HIDDEN");
        connection(hiddenProject, "CONNECTION-HIDDEN");
        String session = signIn("query-dashboard-user");

        mockMvc.perform(get("/api/v1/dashboard").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectCount", is(1)))
                .andExpect(jsonPath("$.pendingRecordCount", is(1)))
                .andExpect(jsonPath("$.activeHandoverCount", is(0)))
                .andExpect(jsonPath("$.failedJobCount", is(0)))
                .andExpect(jsonPath("$.projects.length()", is(1)))
                .andExpect(jsonPath("$.projects[0].id", is((int) accessibleProject)))
                .andExpect(jsonPath("$.connectionHealth.length()", is(2)))
                .andExpect(jsonPath("$.connectionHealth[0].projectId", is((int) accessibleProject)))
                .andExpect(jsonPath("$.connectionHealth[1].projectId", is((int) accessibleProject)));
    }

    @Test
    void dashboardConnectionHealthHidesAnotherMembersPersonalFolder() throws Exception {
        long actor = user("query-folder-viewer", "USER");
        long colleague = user("query-folder-owner", "USER");
        long project = project("PRJ-DASHBOARD-FOLDERS");
        member(project, actor, "MEMBER");
        member(project, colleague, "MEMBER");
        connection(project, "SHARED-MAILBOX");
        jdbcTemplate.update("""
                insert into source_connections (project_id, owner_user_id, channel, display_name, status, health)
                values (?, ?, 'CLOUD_PROJECT_FOLDER', 'COLLEAGUE-PRIVATE-FOLDER', 'ACTIVE', 'NORMAL')
                """, project, colleague);
        jdbcTemplate.update("""
                insert into source_connections (project_id, owner_user_id, channel, display_name, status, health)
                values (?, ?, 'CLOUD_PROJECT_FOLDER', 'OWN-FOLDER', 'ACTIVE', 'NORMAL')
                """, project, actor);
        String session = signIn("query-folder-viewer");

        mockMvc.perform(get("/api/v1/dashboard").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionHealth.length()", is(2)))
                .andExpect(jsonPath("$.connectionHealth[?(@.displayName == 'COLLEAGUE-PRIVATE-FOLDER')]").isEmpty());
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }

    @Test
    void countsOpenRisksOnlyForTheProjectsShownAsCards() throws Exception {
        // 대시보드는 요약이 확인 대기 수를 더할 수 있게 접근 가능한 프로젝트를 모두 읽지만, 카드로는 스무 개만 보여
        // 준다. 나머지 프로젝트의 미해결 위험 수는 계산했다가 버렸다.
        long actor = user("query-open-risk-user", "USER");
        for (int index = 1; index <= 23; index++) {
            long projectId = project("PRJ-OPEN-RISK-" + index);
            member(projectId, actor, "MEMBER");
            workRecord(projectId, personalNote(projectId, actor, "NOTE-OPEN-RISK-" + index), actor, "REC-OPEN-RISK-" + index,
                    "CONFIRMED", "OPEN_ISSUE");
        }
        mockMvc.perform(get("/api/v1/dashboard").cookie(cookie(signIn("query-open-risk-user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectCount", is(23)))
                .andExpect(jsonPath("$.projects.length()", is(20)))
                .andExpect(jsonPath("$.projects[*].openRiskCount", everyItem(is(1))));

        String plan = jdbcTemplate.queryForObject("explain (analyze, format json) " + ProjectQueryService.DASHBOARD_PROJECTS,
                String.class, actor, 20, actor);
        assertEquals(20, openRiskEvaluations(new ObjectMapper().readTree(plan)), plan);
    }

    /** 확정된 OPEN_ISSUE 기록을 세는 계획 노드의 실행 횟수. 조건 글로 찾는다. */
    private static int openRiskEvaluations(JsonNode node) {
        int loops = 0;
        if (node.isObject()) {
            for (String field : List.of("Index Cond", "Filter", "Recheck Cond")) {
                if (node.path(field).asString("").contains("OPEN_ISSUE")) return node.path("Actual Loops").asInt();
            }
        }
        for (JsonNode child : node) loops += openRiskEvaluations(child);
        return loops;
    }

    private long user(String employeeNo, String role) {
        return jdbcTemplate.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email, system_role, credential_hash)
                values ((select id from organizations where name = 'Project Query Test Organization'), ?, ?, 'Engineer', ?, cast(? as system_role), ?)
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

    private void member(long projectId, long userId, String role) {
        jdbcTemplate.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, cast(? as project_role), current_date)", projectId, userId, role);
    }

    private long personalNote(long projectId, long authorId, String code) {
        return jdbcTemplate.queryForObject("insert into personal_notes (code, project_id, author_id, body) values (?, ?, ?, 'fixture') returning id", Long.class, code, projectId, authorId);
    }

    private void workRecord(long projectId, long noteId, long actorId, String code, String trustStatus, String recordType) {
        jdbcTemplate.update("insert into work_records (code, project_id, source_note_id, record_type, title, summary, original_excerpt, occurred_on, author_id, author_name, trust_status, confirmed_by, confirmed_at, search_text) values (?, ?, ?, cast(? as record_type), 'fixture', 'fixture', 'fixture', '2026-01-01', ?, 'fixture', cast(? as record_trust_status), case when ? = 'CONFIRMED' then ? else null end, case when ? = 'CONFIRMED' then now() else null end, 'fixture')", code, projectId, noteId, recordType, actorId, trustStatus, trustStatus, actorId, trustStatus);
    }

    // 초안은 수집한 원문에서만 나온다. 공유 메모는 초안이 아니라 확정 기록이 된다.
    private void draftFromOwnFolder(long projectId, long ownerId, String code) {
        long folder = jdbcTemplate.queryForObject("""
                insert into source_connections (project_id, owner_user_id, channel, display_name, status, health)
                values (?, ?, 'CLOUD_PROJECT_FOLDER', 'Own folder', 'ACTIVE', 'NORMAL') returning id
                """, Long.class, projectId, ownerId);
        long source = jdbcTemplate.queryForObject("""
                insert into record_sources (source_connection_id, display_name, collected_at, project_id, external_document_id,
                                            external_version, content_hash, source_body)
                values (?, 'fixture', now(), ?, ?, 'v1', 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', 'fixture')
                returning id
                """, Long.class, folder, projectId, code);
        jdbcTemplate.update("""
                insert into work_records (code, project_id, record_source_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_name, trust_status, source_version, search_text)
                values (?, ?, ?, 'OPEN_ISSUE', 'fixture', 'fixture', 'fixture', '2026-01-01', 'fixture', 'AUTO_DRAFT', 'v1', 'fixture')
                """, code, projectId, source);
    }

    private void connection(long projectId, String displayName) {
        jdbcTemplate.update("insert into source_connections (project_id, channel, display_name, status, health) values (?, 'EMAIL', ?, 'ACTIVE', 'FAILED')", projectId, displayName);
    }

    private jakarta.servlet.http.Cookie cookie(String value) {
        return new jakarta.servlet.http.Cookie("BATON_SESSION", value);
    }
}
