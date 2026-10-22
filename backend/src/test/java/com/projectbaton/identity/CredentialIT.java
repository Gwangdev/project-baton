package com.projectbaton.identity;

import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.shared.AuditRows;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 관리자가 만든 사용자는 자격 증명이 없어서 로그인할 수 없다. 관리자가 일회용 설정 코드를 발급하고 사용자가 그 코드로
 * 비밀번호를 정하며, 잊은 비밀번호도 같은 경로로 재설정한다. 코드는 한 번만 보여 주고 해시만 저장한다. 관리자가
 * 다루는 비밀은 이 코드뿐이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CredentialIT {
    private static final PasswordEncoder PASSWORDS = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private static final String ADMIN_PASSWORD = "admin-" + UUID.randomUUID();

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CredentialService credentials;
    @Autowired javax.sql.DataSource dataSource;

    /** 실패 limiter는 JVM 전체에서 수를 세므로 시험마다 다른 주소에서 온다. */
    private static final java.util.concurrent.atomic.AtomicInteger ADDRESSES = new java.util.concurrent.atomic.AtomicInteger();
    private final String address = "192.0.2." + ADDRESSES.incrementAndGet();

    private long organizationId;
    private long adminId;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate table organizations restart identity cascade");
        AuditRows.clear(jdbc);
        jdbc.update("delete from spring_session_attributes");
        jdbc.update("delete from spring_session");
        organizationId = jdbc.queryForObject("insert into organizations(name) values('Credential Organization') returning id", Long.class);
        adminId = user("cred-admin", "SYSTEM_ADMIN", ADMIN_PASSWORD);
        user("cred-plain", "USER", ADMIN_PASSWORD);
    }

    @Test
    void theAdministratorIssuesACodeThatIsStoredOnlyAsAHash() throws Exception {
        long target = user("cred-new", "USER", null);
        String admin = signIn("cred-admin", ADMIN_PASSWORD);

        String body = issue(admin, target).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.token", hasLength(43)))
                .andExpect(jsonPath("$.expiresAt", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        assertEquals(sha256(token), jdbc.queryForObject("select token_hash from credential_tokens where user_id=?", String.class, target));
        OffsetDateTime expiresAt = jdbc.queryForObject("select expires_at from credential_tokens where user_id=?", OffsetDateTime.class, target);
        assertTrue(Duration.between(OffsetDateTime.now(), expiresAt).toMinutes() >= 23 * 60 + 58, "valid for about 24 hours");
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where action='ISSUE_CREDENTIAL_TOKEN'", Integer.class));
        assertEquals(0, mentions(token), "the code appears in no table");
    }

    @Test
    void onlyAnAdministratorMayIssueAndTheTargetMustBeAnActiveLocalAccount() throws Exception {
        long target = user("cred-new", "USER", null);
        String plain = signIn("cred-plain", ADMIN_PASSWORD);
        String admin = signIn("cred-admin", ADMIN_PASSWORD);

        issue(plain, target).andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
        issue(admin, 987654321L).andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("USER_NOT_FOUND")));
        jdbc.update("update users set is_active=false where id=?", target);
        issue(admin, target).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE")));
        jdbc.update("update users set is_active=true, auth_provider='OIDC', external_subject='s', external_issuer='https://issuer.example.test' where id=?", target);
        issue(admin, target).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE")));
        mockMvc.perform(post("/api/v1/admin/users/{userId}/credential-tokens", target).with(csrf())).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("select count(*) from credential_tokens", Integer.class));
    }

    @Test
    void theUserChoosesAPasswordWithTheCodeAndTheCodeWorksOnce() throws Exception {
        long target = user("cred-new", "USER", null);
        String token = issuedToken(signIn("cred-admin", ADMIN_PASSWORD), target);
        String password = "chosen-" + UUID.randomUUID();

        mockMvc.perform(signInRequest("cred-new", password)).andExpect(status().isUnauthorized());
        set("cred-new", token, password).andExpect(status().isNoContent());

        mockMvc.perform(signInRequest("cred-new", password)).andExpect(status().isOk());
        set("cred-new", token, "again-" + UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_CREDENTIAL_TOKEN")));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where action='SET_CREDENTIAL'", Integer.class));
        assertEquals(0, mentions(password), "the password appears in no table in clear");
    }

    @Test
    void issuingAgainVoidsTheEarlierCode() throws Exception {
        long target = user("cred-new", "USER", null);
        String admin = signIn("cred-admin", ADMIN_PASSWORD);
        String first = issuedToken(admin, target);
        String second = issuedToken(admin, target);

        set("cred-new", first, "chosen-" + UUID.randomUUID()).andExpect(status().isBadRequest());
        set("cred-new", second, "chosen-" + UUID.randomUUID()).andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("select count(*) from credential_tokens where user_id=?", Integer.class, target) , "one row stays");
    }

    @Test
    void everyKindOfWrongCodeAnswersTheSameWayAndRevealsNoAccount() throws Exception {
        long target = user("cred-new", "USER", null);
        long other = user("cred-other", "USER", null);
        String admin = signIn("cred-admin", ADMIN_PASSWORD);
        String token = issuedToken(admin, target);
        String otherToken = issuedToken(admin, other);
        String password = "chosen-" + UUID.randomUUID();

        String unknownAccount = set("nobody", token, password).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_CREDENTIAL_TOKEN"))).andReturn().getResponse().getContentAsString();
        String wrongCode = set("cred-new", "x".repeat(43), password).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        String othersCode = set("cred-new", otherToken, password).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        jdbc.update("update credential_tokens set expires_at = now() - interval '1 minute' where user_id=?", target);
        String expired = set("cred-new", token, password).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        for (String answer : new String[] {wrongCode, othersCode, expired}) {
            assertEquals(strip(unknownAccount), strip(answer));
        }
    }

    @Test
    void aWeakPasswordIsRefusedAndDoesNotUseUpTheCode() throws Exception {
        long target = user("cred-new", "USER", null);
        String token = issuedToken(signIn("cred-admin", ADMIN_PASSWORD), target);

        for (String weak : new String[] {"a".repeat(11), "a".repeat(129), "cred-new"}) {
            set("cred-new", token, weak).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")))
                    .andExpect(jsonPath("$.errors[0].field", is("newPassword")));
        }
        set("cred-new", token, "a".repeat(12)).andExpect(status().isNoContent());
    }

    @Test
    void theSixthFailureFromOneAddressIsThrottled() throws Exception {
        user("cred-new", "USER", null);
        for (int attempt = 0; attempt < 5; attempt++) {
            set("cred-new", "x".repeat(43), "chosen-" + UUID.randomUUID(), "198.51.100.20").andExpect(status().isBadRequest());
        }
        set("cred-new", "x".repeat(43), "chosen-" + UUID.randomUUID(), "198.51.100.20")
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code", is("AUTH_RATE_LIMITED")));
    }

    @Test
    void resettingAPasswordEndsTheSessionsOpenedWithTheOldOne() throws Exception {
        String session = signIn("cred-plain", ADMIN_PASSWORD);
        mockMvc.perform(get("/api/v1/me").cookie(new Cookie("BATON_SESSION", session))).andExpect(status().isOk());
        long plain = jdbc.queryForObject("select id from users where employee_no='cred-plain'", Long.class);
        String token = issuedToken(signIn("cred-admin", ADMIN_PASSWORD), plain);

        set("cred-plain", token, "chosen-" + UUID.randomUUID()).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me").cookie(new Cookie("BATON_SESSION", session))).andExpect(status().isUnauthorized());
    }

    @Test
    void settingACredentialNeedsTheCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/credentials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"cred-new\",\"token\":\"" + "x".repeat(43) + "\",\"newPassword\":\"" + "p".repeat(12) + "\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 관리자가 발급한 코드는 관리자를 포함해 코드를 가진 누구나 쓸 수 있고, 설정은 대상 본인의 이름으로 남는다. 둘을
     * 가를 수 있는 것은 두 요청의 주소뿐이다.
     */
    @Test
    void theAuditTrailKeepsTheAddressOfTheIssueAndOfTheSetting() throws Exception {
        long target = user("cred-new", "USER", null);
        String token = issuedToken(signIn("cred-admin", ADMIN_PASSWORD), target);
        String password = "chosen-" + UUID.randomUUID();

        set("cred-new", token, password).andExpect(status().isNoContent());

        assertEquals(ISSUING_ADDRESS, jdbc.queryForObject("select remote_address from audit_events where action='ISSUE_CREDENTIAL_TOKEN'", String.class));
        assertEquals(address, jdbc.queryForObject("select remote_address from audit_events where action='SET_CREDENTIAL'", String.class));
        assertEquals(0, mentions(token) + mentions(password), "neither the code nor the password reached a table");
    }

    @Test
    void aUserIdThatIsNotANumberIsABadRequest() throws Exception {
        String admin = signIn("cred-admin", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/v1/admin/users/{userId}/credential-tokens", "abc").with(csrf()).cookie(new Cookie("BATON_SESSION", admin)))
                .andExpect(status().isBadRequest());
    }

    /**
     * 발급은 사용자를 잠그고 코드를 지운다. 예전에는 코드를 쓸 때 코드를 잠그고 사용자를 바꿔서 둘이 서로를 기다릴 수
     * 있었고, PostgreSQL이 그 가운데 하나를 교착 오류로 끝냈다. 이제 둘 다 사용자를 먼저 잠근다. 다른 트랜잭션이
     * 사용자 행을 쥐고 있으면 코드 사용은 코드 행을 건드리기 전에 기다려야 한다.
     */
    @Test
    void redeemingTakesTheUserRowBeforeTheCodeRow() throws Exception {
        long target = user("cred-new", "USER", null);
        String token = credentials.issue(adminId, target, ISSUING_ADDRESS).token();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection holder = dataSource.getConnection(); java.sql.Connection probe = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("select id from users where id = " + target + " for update");
            java.util.concurrent.Future<?> redeeming = executor.submit(() -> credentials.setCredential("cred-new", token, "chosen-" + UUID.randomUUID(), address));
            com.projectbaton.LockWait.untilASessionWaits(jdbc);

            org.junit.jupiter.api.Assertions.assertFalse(redeeming.isDone(), "redeeming waits for the user row");
            probe.setAutoCommit(false);
            // 코드를 쓰는 트랜잭션이 이미 코드 행을 쥐고 있으면 NOWAIT가 바로 실패한다.
            probe.createStatement().execute("select id from credential_tokens where user_id = " + target + " for update nowait");
            probe.rollback();
            holder.commit();
            redeeming.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from credential_tokens where used_at is not null", Integer.class));
    }

    /**
     * 코드를 쥔 사람은 누구나 비밀번호를 정할 수 있어서 관리자가 다른 관리자의 계정을 가져갈 수 있다. 그래서 시스템
     * 관리자는 자기 계정에 대해서도 코드를 받지 않고, 그 비밀번호는 운영자가 재설정한다.
     */
    @Test
    void aSystemAdministratorNeverReceivesASetupCode() throws Exception {
        long otherAdmin = user("cred-admin-2", "SYSTEM_ADMIN", ADMIN_PASSWORD);
        String admin = signIn("cred-admin", ADMIN_PASSWORD);

        issue(admin, otherAdmin).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE")));
        issue(admin, adminId).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE")));

        assertEquals(0, jdbc.queryForObject("select count(*) from credential_tokens", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where action='ISSUE_CREDENTIAL_TOKEN'", Integer.class));
    }

    /**
     * 일반 계정에 발급한 코드를 가진 사람이 관리자의 비밀번호를 정해서는 안 된다. 코드를 쓰기 전에 계정이 승격되면
     * 틀린 코드와 같은 답으로 거절하고 코드를 소모하지 않아서, 계정이 다시 일반 계정이 되면 그 코드가 다시 동작한다.
     */
    @Test
    void aCodeIssuedBeforeTheAccountBecameAnAdministratorIsRefusedLikeAWrongCode() throws Exception {
        long target = user("cred-new", "USER", null);
        String token = issuedToken(signIn("cred-admin", ADMIN_PASSWORD), target);
        String password = "chosen-" + UUID.randomUUID();
        jdbc.update("update users set system_role = cast('SYSTEM_ADMIN' as system_role) where id=?", target);

        String refused = set("cred-new", token, password).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_CREDENTIAL_TOKEN"))).andReturn().getResponse().getContentAsString();
        String wrongCode = set("cred-new", "x".repeat(43), password).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertEquals(strip(wrongCode), strip(refused));
        assertTrue(jdbc.queryForObject("select credential_hash is null from users where id=?", Boolean.class, target), "no password was set");
        jdbc.update("update users set system_role = cast('USER' as system_role) where id=?", target);
        set("cred-new", token, password).andExpect(status().isNoContent());
    }

    private static final String ISSUING_ADDRESS = "203.0.113.7";

    private ResultActions issue(String session, long userId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/users/{userId}/credential-tokens", userId).with(csrf())
                .with(request -> { request.setRemoteAddr(ISSUING_ADDRESS); return request; })
                .cookie(new Cookie("BATON_SESSION", session)));
    }

    private String issuedToken(String session, long userId) throws Exception {
        String body = issue(session, userId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions set(String employeeNo, String token, String password) throws Exception {
        return set(employeeNo, token, password, address);
    }

    private ResultActions set(String employeeNo, String token, String password, String from) throws Exception {
        var request = post("/api/v1/auth/credentials").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeNo\":\"" + employeeNo + "\",\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}");
        request = request.with(r -> { r.setRemoteAddr(from); return r; });
        return mockMvc.perform(request);
    }

    private MockHttpServletRequestBuilder signInRequest(String employeeNo, String password) {
        return post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + password + "\"}");
    }

    private String signIn(String employeeNo, String password) throws Exception {
        return mockMvc.perform(signInRequest(employeeNo, password)).andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("BATON_SESSION").getValue();
    }

    private long user(String employeeNo, String role, String password) {
        return jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,system_role,credential_hash)
                values(?,?,?,'Engineer',?,cast(? as system_role),?) returning id
                """, Long.class, organizationId, employeeNo, employeeNo, employeeNo + "@example.test", role,
                password == null ? null : PASSWORDS.encode(password));
    }

    /** 글이 어디에든 나오는 행. 코드나 비밀번호는 어느 행에도 없어야 한다. */
    private int mentions(String secret) {
        int found = 0;
        for (String table : new String[] {"credential_tokens", "audit_events", "idempotency_requests", "users"}) {
            found += jdbc.queryForObject("select count(*) from " + table + " t where t::text like ?", Integer.class, "%" + secret + "%");
        }
        return found;
    }

    private static String strip(String problem) {
        return problem.replaceAll("\"traceId\":\"[^\"]*\"", "");
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
