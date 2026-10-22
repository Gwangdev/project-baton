package com.projectbaton.identity;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.shared.AuditRows;
import jakarta.servlet.http.Cookie;

import java.util.UUID;

import org.junit.jupiter.api.Assertions;
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

/**
 * FR-18 조직 관리 표면(contracts.md C01/C02). 실제 PostgreSQL로 페이지 경계, PATCH의 없음과 null 구분, If-Match 강제,
 * 참조를 확인하는 영구 삭제, 마지막 활성 SYSADMIN 보호를 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrganizationAdminIT {

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
        jdbcTemplate.update("delete from spring_session_attributes");
        jdbcTemplate.update("delete from spring_session");
        jdbcTemplate.update("delete from idempotency_requests");
        AuditRows.clear(jdbcTemplate);
        jdbcTemplate.update("delete from users");
        jdbcTemplate.update("delete from organizations");
    }

    @Test
    void listReturnsTheDefaultTwentyItemPageAndAllowsTheHundredItemMaximum() throws Exception {
        long adminOrgId = organization("Admin Home", true);
        user("list-admin", adminOrgId, "SYSTEM_ADMIN", true);
        for (int i = 0; i < 24; i++) {
            organization("Listed Org " + i, true);
        }
        Cookie session = signIn("list-admin");

        mockMvc.perform(get("/api/v1/admin/organizations").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.page.total", is(25)))
                .andExpect(jsonPath("$.page.limit", is(20)))
                .andExpect(jsonPath("$.page.offset", is(0)));

        mockMvc.perform(get("/api/v1/admin/organizations").cookie(session).param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(25)))
                .andExpect(jsonPath("$.page.limit", is(100)));

        mockMvc.perform(get("/api/v1/admin/organizations").cookie(session).param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    void rejectsAnonymousAndNonAdminAccessToTheOrganizationList() throws Exception {
        long orgId = organization("Access Org", true);
        user("plain-member", orgId, "USER", true);
        Cookie memberSession = signIn("plain-member");

        mockMvc.perform(get("/api/v1/admin/organizations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));

        mockMvc.perform(get("/api/v1/admin/organizations").cookie(memberSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    void rejectsAnUnknownFieldOnCreate() throws Exception {
        long adminOrgId = organization("Create Admin Org", true);
        user("create-admin", adminOrgId, "SYSTEM_ADMIN", true);
        Cookie session = signIn("create-admin");

        mockMvc.perform(post("/api/v1/admin/organizations")
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Org\",\"bogus\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    void createsAnOrganizationOnceForRepeatedRequestsWithTheSameIdempotencyKey() throws Exception {
        long adminOrgId = organization("Idempotent Admin Org", true);
        user("idempotent-admin", adminOrgId, "SYSTEM_ADMIN", true);
        Cookie session = signIn("idempotent-admin");
        UUID key = UUID.randomUUID();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/admin/organizations")
                            .cookie(session)
                            .with(csrf().asHeader())
                            .header("Idempotency-Key", key.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Once Created\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name", is("Once Created")));
        }

        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from organizations where name = 'Once Created'", Integer.class));
    }

    @Test
    void distinguishesAnOmittedFieldFromAnExplicitNullOnPatch() throws Exception {
        long adminOrgId = organization("Patch Admin Org", true);
        user("patch-admin", adminOrgId, "SYSTEM_ADMIN", true);
        Cookie session = signIn("patch-admin");
        long targetOrgId = organization("Patch Target", true);

        mockMvc.perform(patch("/api/v1/admin/organizations/" + targetOrgId)
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Patch Target")))
                .andExpect(jsonPath("$.isActive", is(false)));

        mockMvc.perform(patch("/api/v1/admin/organizations/" + targetOrgId)
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")));
    }

    @Test
    void rejectsAPatchWithoutAnIfMatchHeader() throws Exception {
        long adminOrgId = organization("Missing IfMatch Admin Org", true);
        user("missing-ifmatch-admin", adminOrgId, "SYSTEM_ADMIN", true);
        Cookie session = signIn("missing-ifmatch-admin");
        long targetOrgId = organization("Missing IfMatch Target", true);

        mockMvc.perform(patch("/api/v1/admin/organizations/" + targetOrgId)
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code", is("IF_MATCH_REQUIRED")));
    }

    @Test
    void rejectsAPatchWithAStaleIfMatchVersion() throws Exception {
        long adminOrgId = organization("Stale Admin Org", true);
        user("stale-admin", adminOrgId, "SYSTEM_ADMIN", true);
        Cookie session = signIn("stale-admin");
        long targetOrgId = organization("Stale Target", true);

        mockMvc.perform(patch("/api/v1/admin/organizations/" + targetOrgId)
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().is(412))
                .andExpect(jsonPath("$.code", is("STALE_VERSION")));

        // long에 들어가지 않는 버전은 서버 오류가 아니라 잘못된 입력이다.
        mockMvc.perform(patch("/api/v1/admin/organizations/" + targetOrgId)
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"99999999999999999999\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsHardDeleteOfAnOrganizationWithReferencingUsersAndReportsTheCount() throws Exception {
        long adminOrgId = organization("Reference Admin Org", true);
        user("reference-admin", adminOrgId, "SYSTEM_ADMIN", true);
        Cookie session = signIn("reference-admin");
        long referencedOrgId = organization("Referenced Org", true);
        user("referenced-user", referencedOrgId, "USER", true);

        mockMvc.perform(delete("/api/v1/admin/organizations/" + referencedOrgId)
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("REFERENCE_CONFLICT")))
                .andExpect(jsonPath("$.references[0].resource", is("users")))
                .andExpect(jsonPath("$.references[0].count", is(1)));
    }

    @Test
    void hardDeletesAnUnreferencedOrganizationWithNoResponseBody() throws Exception {
        long adminOrgId = organization("Delete Admin Org", true);
        user("delete-admin", adminOrgId, "SYSTEM_ADMIN", true);
        Cookie session = signIn("delete-admin");
        long emptyOrgId = organization("Empty Org", true);

        mockMvc.perform(delete("/api/v1/admin/organizations/" + emptyOrgId)
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "select count(*) from organizations where id = ?", Integer.class, emptyOrgId));
    }

    @Test
    void rejectsDeactivatingTheOrganizationOfTheLastActiveSystemAdministrator() throws Exception {
        long onlyAdminOrgId = organization("Only Admin Org", true);
        user("only-admin", onlyAdminOrgId, "SYSTEM_ADMIN", true);
        Cookie session = signIn("only-admin");

        mockMvc.perform(patch("/api/v1/admin/organizations/" + onlyAdminOrgId)
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("LAST_SYSTEM_ADMIN")));

        Assertions.assertTrue(jdbcTemplate.queryForObject(
                "select is_active from organizations where id = ?", Boolean.class, onlyAdminOrgId));
    }

    @Test
    void allowsDeactivatingAnOrganizationWhenAnotherActiveSystemAdministratorRemains() throws Exception {
        long otherAdminOrgId = organization("Other Admin Org", true);
        user("other-admin", otherAdminOrgId, "SYSTEM_ADMIN", true);
        long targetOrgId = organization("Deactivatable Org", true);
        user("deactivatable-admin", targetOrgId, "SYSTEM_ADMIN", true);
        Cookie session = signIn("other-admin");

        mockMvc.perform(patch("/api/v1/admin/organizations/" + targetOrgId)
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive", is(false)));
    }

    @Test
    void blocksTheNextRequestFromAUserAfterTheirOrganizationIsDeactivated() throws Exception {
        long adminOrgId = organization("Deactivation Admin Org", true);
        user("deactivation-admin", adminOrgId, "SYSTEM_ADMIN", true);
        Cookie adminSession = signIn("deactivation-admin");

        long memberOrgId = organization("Member Org", true);
        user("blocked-member", memberOrgId, "USER", true);
        Cookie memberSession = signIn("blocked-member");

        mockMvc.perform(get("/api/v1/me").cookie(memberSession))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/organizations/" + memberOrgId)
                        .cookie(adminSession)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me").cookie(memberSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCOUNT_INACTIVE")));
    }

    @Test
    void returns404ForDeleteOfAnUnknownOrganizationId() throws Exception {
        long adminOrgId = organization("Only Org", true);
        user("only-org-admin", adminOrgId, "SYSTEM_ADMIN", true);
        Cookie session = signIn("only-org-admin");
        jdbcTemplate.update("delete from organizations where id <> ?", adminOrgId);
        mockMvc.perform(delete("/api/v1/admin/organizations/999999999")
                        .cookie(session)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ORGANIZATION_NOT_FOUND")));
    }

    private long organization(String name, boolean active) {
        return jdbcTemplate.queryForObject(
                "insert into organizations (name, is_active) values (?, ?) returning id", Long.class, name, active);
    }

    private long user(String employeeNo, long organizationId, String systemRole, boolean active) {
        return jdbcTemplate.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email, credential_hash, system_role, is_active)
                values (?, ?, ?, 'Engineer', ?, ?, cast(? as system_role), ?)
                returning id
                """, Long.class, organizationId, employeeNo, employeeNo, employeeNo + "@example.test",
                PASSWORD_ENCODER.encode("synthetic-password"), systemRole, active);
    }

    private Cookie signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("BATON_SESSION");
    }
}
