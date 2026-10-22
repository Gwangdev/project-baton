package com.projectbaton.identity;

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
class UserManagementIT {

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
        jdbcTemplate.update("delete from users");
        jdbcTemplate.update("delete from organizations");
        jdbcTemplate.update("insert into organizations (name) values ('User Test Organization')");
    }

    @Test
    void ignoresARegularUsersClaimedAdminRoleAndAllowsOnlyTheAuthenticatedSysadminToListUsers() throws Exception {
        String regularSession = signIn("regular-user", "USER");
        mockMvc.perform(get("/api/v1/admin/users").cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", regularSession)))
                .andExpect(status().isForbidden());

        String adminSession = signIn("admin-user", "SYSTEM_ADMIN");
        mockMvc.perform(get("/api/v1/admin/users").cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].employeeNo").exists());
    }

    @Test
    void createsAUserExactlyOnceForAnIdempotencyKeyAndLeavesLocalLoginDisabledWithoutCredentialSetup() throws Exception {
        String adminSession = signIn("admin-user", "SYSTEM_ADMIN");
        String key = UUID.randomUUID().toString();
        String body = createRequest("new-user", "USER");

        MvcResult first = mockMvc.perform(post("/api/v1/admin/users")
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeNo", is("new-user")))
                .andExpect(jsonPath("$.loginEnabled", is(false)))
                .andReturn();

        mockMvc.perform(post("/api/v1/admin/users")
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json(first.getResponse().getContentAsString()));

        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbcTemplate.queryForObject("select count(*) from users where employee_no = 'new-user'", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'CREATE_USER' and target_type = 'user'", Integer.class));
    }

    @Test
    void enforcesTheUserListPageBoundsAndUsesTheContractDefaultPageSize() throws Exception {
        String adminSession = signIn("admin-user", "SYSTEM_ADMIN");
        for (int index = 0; index < 101; index++) {
            user("page-" + index, "USER", true);
        }

        mockMvc.perform(get("/api/v1/admin/users").cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.limit", is(20)))
                .andExpect(jsonPath("$.items.length()", is(20)));
        mockMvc.perform(get("/api/v1/admin/users?limit=101").cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownUserFieldsInsteadOfSilentlyIgnoringThem() throws Exception {
        String adminSession = signIn("admin-user", "SYSTEM_ADMIN");
        mockMvc.perform(post("/api/v1/admin/users")
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession))
                        .with(csrf().asHeader()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":1,\"employeeNo\":\"unknown-field\",\"name\":\"Name\",\"title\":\"Engineer\",\"email\":\"unknown@example.test\",\"permission\":\"SYSTEM_ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    void preservesOmittedFieldsButRejectsExplicitNullForANonNullableUserField() throws Exception {
        String adminSession = signIn("admin-user", "SYSTEM_ADMIN");
        long targetId = user("patch-target", "USER", true);
        String etag = "\"0\"";

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", targetId)
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession))
                        .with(csrf().asHeader()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", etag).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Lead\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.name", is("patch-target")))
                .andExpect(jsonPath("$.title", is("Lead")));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'UPDATE_USER' and target_id = ?", Integer.class, Long.toString(targetId)));

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", targetId)
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession))
                        .with(csrf().asHeader()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON).content("{\"name\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    void requiresAFreshIfMatchAndPreventsSelfOrLastSysadminDemotion() throws Exception {
        String adminSession = signIn("only-admin", "SYSTEM_ADMIN");
        long adminId = jdbcTemplate.queryForObject("select id from users where employee_no = 'only-admin'", Long.class);

        mockMvc.perform(patch("/api/v1/admin/users/{userId}", adminId)
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(patch("/api/v1/admin/users/{userId}", adminId)
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"9\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(patch("/api/v1/admin/users/{userId}", adminId)
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
                .andExpect(status().isConflict());
    }

    @Test
    void returnsReferenceConflictForAssignedUserAndDeletesAnUnreferencedUserWithoutABody() throws Exception {
        String adminSession = signIn("admin-user", "SYSTEM_ADMIN");
        long assigned = user("assigned-user", "USER", true);
        long projectId = jdbcTemplate.queryForObject("""
                insert into projects (code, name, delivering_company, client_company, started_on, ends_on, phase, status)
                values ('USER-DELETE', 'User Delete', 'Synthetic', 'Synthetic', current_date, current_date, 'Build', 'ON_TRACK')
                returning id
                """, Long.class);
        jdbcTemplate.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, 'MEMBER', current_date)",
                projectId, assigned);

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", assigned)
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("REFERENCE_CONFLICT")))
                .andExpect(jsonPath("$.references[0].resource", is("project_members")));

        long removable = user("removable-user", "USER", true);
        mockMvc.perform(delete("/api/v1/admin/users/{userId}", removable)
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\""))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action = 'DELETE_USER' and target_id = ?", Integer.class, Long.toString(removable)));
    }

    /**
     * 감사 행은 외래키 없이 id로 행위자를 가리켜서, 로그인했거나 행동한 사용자를 지우면 그 사용자의 모든 행이 아무도
     * 가리키지 않게 됐다. 감사에 행위자로 나오는 사용자는 지우지 않고 비활성화한다. 감사 행의 대상으로만 나오는
     * 사용자(만들고 쓰지 않은 계정)는 지울 수 있고, 그 삭제도 감사에 남는다.
     */
    @Test
    void aUserWhoActedInTheAuditIsNotDeletedButACreatedAndUnusedOneIs() throws Exception {
        String adminSession = signIn("admin-user", "SYSTEM_ADMIN");
        signIn("signed-in-user", "USER");
        long signedIn = jdbcTemplate.queryForObject("select id from users where employee_no = 'signed-in-user'", Long.class);

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", signedIn)
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("REFERENCE_CONFLICT")))
                .andExpect(jsonPath("$.references[?(@.resource == 'audit_events')].count", org.hamcrest.Matchers.contains(1)));

        long created = jdbcTemplate.queryForObject("select (json ->> 'id')::bigint from (select ?::json as json) body", Long.class,
                mockMvc.perform(post("/api/v1/admin/users").cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession))
                                .with(csrf().asHeader()).header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON).content(createRequest("created-unused", "USER")))
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(delete("/api/v1/admin/users/{userId}", created)
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", adminSession)).with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\""))
                .andExpect(status().isNoContent());
    }

    private String signIn(String employeeNo, String role) throws Exception {
        user(employeeNo, role, true);
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }

    private long user(String employeeNo, String role, boolean active) {
        return jdbcTemplate.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email, system_role, credential_hash, is_active)
                values ((select id from organizations where name = 'User Test Organization'), ?, ?, 'Engineer', ?, cast(? as system_role), ?, ?)
                returning id
                """, Long.class, employeeNo, employeeNo, employeeNo + "@example.test", role,
                PASSWORD_ENCODER.encode("synthetic-password"), active);
    }

    private String createRequest(String employeeNo, String systemRole) {
        return "{\"organizationId\":" + jdbcTemplate.queryForObject(
                "select id from organizations where name = 'User Test Organization'", Long.class)
                + ",\"employeeNo\":\"" + employeeNo + "\",\"name\":\"New User\",\"title\":\"Engineer\",\"email\":\""
                + employeeNo + "@example.test\",\"systemRole\":\"" + systemRole + "\"}";
    }
}
