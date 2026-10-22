package com.projectbaton.project;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProjectAdminIT {

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
        jdbcTemplate.update("delete from idempotency_requests");
        jdbcTemplate.update("delete from project_members");
        jdbcTemplate.update("delete from projects");
        jdbcTemplate.update("delete from users");
        jdbcTemplate.update("delete from organizations");
        jdbcTemplate.update("insert into organizations (name) values ('Project Test Organization')");
    }

    @Test
    void usesTheAuthenticatedSystemRoleRatherThanARequestClaimForGlobalProjectManagement() throws Exception {
        String regularSession = signIn("regular-project-user", "USER");
        mockMvc.perform(get("/api/v1/admin/projects").cookie(cookie(regularSession)))
                .andExpect(status().isForbidden());

        String adminSession = signIn("project-admin", "SYSTEM_ADMIN");
        mockMvc.perform(get("/api/v1/admin/projects").cookie(cookie(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)))
                .andExpect(jsonPath("$.page.limit", is(20)));
        mockMvc.perform(get("/api/v1/admin/projects?limit=100&offset=10000").cookie(cookie(adminSession)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.limit", is(100)));
        mockMvc.perform(get("/api/v1/admin/projects?limit=101").cookie(cookie(adminSession)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    void createsAProjectExactlyOnceForTheSameKeyAndAuditsTheCommand() throws Exception {
        String adminSession = signIn("project-admin", "SYSTEM_ADMIN");
        String key = UUID.randomUUID().toString();
        String body = projectRequest("PRJ-001");

        MvcResult first = mockMvc.perform(post("/api/v1/admin/projects").cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", is("PRJ-001")))
                .andReturn();
        mockMvc.perform(post("/api/v1/admin/projects").cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(content().json(first.getResponse().getContentAsString()));
        mockMvc.perform(post("/api/v1/admin/projects").cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(projectRequest("PRJ-001-ALTERED")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));

        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbcTemplate.queryForObject("select count(*) from projects where code = 'PRJ-001'", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbcTemplate.queryForObject("select count(*) from audit_events where action = 'CREATE_PROJECT'", Integer.class));
    }

    @Test
    void rejectsUnknownFieldsAndInvalidProjectDateOrder() throws Exception {
        String adminSession = signIn("project-admin", "SYSTEM_ADMIN");
        mockMvc.perform(post("/api/v1/admin/projects").cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(projectRequest("PRJ-UNKNOWN").replace("}", ",\"role\":\"SYSTEM_ADMIN\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")));
        mockMvc.perform(post("/api/v1/admin/projects").cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(projectRequest("PRJ-DATES").replace("2026-12-31", "2025-12-31")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    void preservesOmittedProjectFieldsButRequiresAFreshIfMatchForUpdates() throws Exception {
        String adminSession = signIn("project-admin", "SYSTEM_ADMIN");
        long projectId = project("PRJ-PATCH");

        mockMvc.perform(patch("/api/v1/admin/projects/{projectId}", projectId).cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phase\":\"Release\"}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(patch("/api/v1/admin/projects/{projectId}", projectId).cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"4\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phase\":\"Release\"}"))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(patch("/api/v1/admin/projects/{projectId}", projectId).cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phase\":\"Release\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.name", is("Project PRJ-PATCH"))).andExpect(jsonPath("$.phase", is("Release")));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbcTemplate.queryForObject("select count(*) from audit_events where action = 'UPDATE_PROJECT'", Integer.class));

        mockMvc.perform(patch("/api/v1/admin/projects/{projectId}", projectId).cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phase\":null}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    void returnsNotFoundForAProjectThatDoesNotExist() throws Exception {
        String adminSession = signIn("project-admin", "SYSTEM_ADMIN");
        mockMvc.perform(patch("/api/v1/admin/projects/{projectId}", 999_999L).cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phase\":\"Release\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("PROJECT_NOT_FOUND")));
    }

    @Test
    void returnsReferencesForAssignedProjectAndDeletesAnUnreferencedProjectWithoutABody() throws Exception {
        String adminSession = signIn("project-admin", "SYSTEM_ADMIN");
        long assigned = project("PRJ-ASSIGNED");
        long member = user("assigned-member", "USER");
        jdbcTemplate.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, 'MEMBER', current_date)",
                assigned, member);

        mockMvc.perform(delete("/api/v1/admin/projects/{projectId}", assigned).cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("REFERENCE_CONFLICT")))
                .andExpect(jsonPath("$.references[0].resource", is("project_members")));

        long removable = project("PRJ-REMOVABLE");
        mockMvc.perform(delete("/api/v1/admin/projects/{projectId}", removable).cookie(cookie(adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\""))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbcTemplate.queryForObject("select count(*) from audit_events where action = 'DELETE_PROJECT'", Integer.class));
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
                values ((select id from organizations where name = 'Project Test Organization'), ?, ?, 'Engineer', ?, cast(? as system_role), ?)
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

    private String projectRequest(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"Project " + code
                + "\",\"deliveringCompany\":\"Synthetic Delivery\",\"clientCompany\":\"Synthetic Client\",\"startedOn\":\"2026-01-01\",\"endsOn\":\"2026-12-31\",\"phase\":\"Build\",\"status\":\"ON_TRACK\"}";
    }

    private jakarta.servlet.http.Cookie cookie(String value) {
        return new jakarta.servlet.http.Cookie("BATON_SESSION", value);
    }
}
