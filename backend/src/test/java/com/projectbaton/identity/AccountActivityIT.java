package com.projectbaton.identity;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.shared.AuditRows;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 계정 주인이 자기 계정에 대해 알 수 있는 것. 로그인은 주소와 함께 감사에 남고(인증되지 않은 호출자가 감사 테이블을
 * 채울 수 있어서 실패한 로그인은 남기지 않는다), 주인은 /me에서 이 계정을 가리키는 최근 다섯 행위를 읽고, 모든 감사
 * 행과 오류 응답은 자기 요청의 trace id를 담는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AccountActivityIT {
    private static final PasswordEncoder PASSWORDS = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final java.util.concurrent.atomic.AtomicInteger ADDRESSES = new java.util.concurrent.atomic.AtomicInteger();

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.projectbaton.audit.AuditService audit;

    /** 실패 limiter는 JVM 전체에서 수를 세므로 시험마다 다른 주소에서 온다. */
    private final String address = "192.0.2." + (100 + ADDRESSES.incrementAndGet());
    private long organizationId;
    private long adminId;
    private long ownerId;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate table organizations restart identity cascade");
        AuditRows.clear(jdbc);
        jdbc.update("delete from spring_session_attributes");
        jdbc.update("delete from spring_session");
        organizationId = jdbc.queryForObject("insert into organizations(name) values('Activity Organization') returning id", Long.class);
        adminId = user("act-admin", "SYSTEM_ADMIN", PASSWORD);
        ownerId = user("act-owner", "USER", null);
        user("act-bystander", "USER", PASSWORD);
    }

    /** 운영자의 재설정은 바꾼 계정을 가리켜서, 그 계정의 주인만 보고 다른 사람은 보지 못한다. */
    @Test
    void anOperatorsResetShowsOnTheResetAccountAndOnNoOtherAccount() throws Exception {
        jdbc.update("insert into audit_events(actor_id, action, target_type, target_id, result, trace_id) values (null, 'RESET_SYSTEM_ADMIN_CREDENTIAL', 'user', ?, 'SUCCESS', 'operator')",
                Long.toString(adminId));
        String admin = cookie(mockMvc.perform(signIn("act-admin", PASSWORD)).andExpect(status().isOk()).andReturn());
        String bystander = cookie(mockMvc.perform(signIn("act-bystander", PASSWORD)).andExpect(status().isOk()).andReturn());

        mockMvc.perform(get("/api/v1/me").cookie(new Cookie("BATON_SESSION", admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountActivity", hasSize(2)))
                .andExpect(jsonPath("$.accountActivity[?(@.action == 'RESET_SYSTEM_ADMIN_CREDENTIAL')]", hasSize(1)));
        mockMvc.perform(get("/api/v1/me").cookie(new Cookie("BATON_SESSION", bystander))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountActivity", hasSize(1)))
                .andExpect(jsonPath("$.accountActivity[0].action", is("SIGN_IN")));
    }

    @Test
    void aSignInIsAuditedWithItsAddressAndAFailedOneIsNot() throws Exception {
        mockMvc.perform(signIn("act-bystander", "wrong-" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where action='SIGN_IN'", Integer.class));

        mockMvc.perform(signIn("act-bystander", PASSWORD)).andExpect(status().isOk());

        assertEquals(address, jdbc.queryForObject("select remote_address from audit_events where action='SIGN_IN'", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where action='SIGN_IN' and actor_id = (select id from users where employee_no='act-bystander')", Integer.class));
    }

    @Test
    void theOwnerSeesTheLatestFiveEventsThatNameTheAccountAndNobodyElses() throws Exception {
        String admin = cookie(mockMvc.perform(signIn("act-admin", PASSWORD)).andExpect(status().isOk()).andReturn());
        String token = mockMvc.perform(post("/api/v1/admin/users/{userId}/credential-tokens", ownerId).with(csrf())
                        .with(request -> { request.setRemoteAddr("203.0.113.9"); return request; }).cookie(new Cookie("BATON_SESSION", admin)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(post("/api/v1/auth/credentials").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"act-owner\",\"token\":\"" + token + "\",\"newPassword\":\"" + "owner-" + PASSWORD + "\"}")
                        .with(request -> { request.setRemoteAddr("198.51.100.5"); return request; }))
                .andExpect(status().isNoContent());
        String owner = cookie(mockMvc.perform(signIn("act-owner", "owner-" + PASSWORD)).andExpect(status().isOk()).andReturn());

        mockMvc.perform(get("/api/v1/me").cookie(new Cookie("BATON_SESSION", owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountActivity", hasSize(3)))
                .andExpect(jsonPath("$.accountActivity[0].action", is("SIGN_IN")))
                .andExpect(jsonPath("$.accountActivity[0].remoteAddress", is(address)))
                .andExpect(jsonPath("$.accountActivity[1].action", is("SET_CREDENTIAL")))
                .andExpect(jsonPath("$.accountActivity[1].remoteAddress", is("198.51.100.5")))
                .andExpect(jsonPath("$.accountActivity[2].action", is("ISSUE_CREDENTIAL_TOKEN")))
                .andExpect(jsonPath("$.accountActivity[2].remoteAddress", is("203.0.113.9")))
                .andExpect(jsonPath("$.accountActivity[2].occurredAt", matchesPattern(".+T.+")));
        // 관리자 자신의 로그인은 관리자의 것이라 주인의 목록에 없고, 발급은 관리자의 목록에 없다.
        mockMvc.perform(get("/api/v1/me").cookie(new Cookie("BATON_SESSION", admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountActivity", hasSize(1))).andExpect(jsonPath("$.accountActivity[0].action", is("SIGN_IN")));

        for (int round = 0; round < 6; round++) {
            mockMvc.perform(signIn("act-owner", "owner-" + PASSWORD)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/me").cookie(new Cookie("BATON_SESSION", owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountActivity", hasSize(5)));
    }

    /** 오래된 로그인의 주소를 비워도 행은 남아서 계정의 목록은 길이와 순서를 유지한다. */
    @Test
    void aSignInWhoseAddressWasClearedStillShowsWithoutAddress() throws Exception {
        String first = cookie(mockMvc.perform(signIn("act-bystander", PASSWORD)).andExpect(status().isOk()).andReturn());
        AuditRows.change(jdbc, "update audit_events set occurred_at = now() - interval '30 months' where action = 'SIGN_IN'");
        mockMvc.perform(signIn("act-bystander", PASSWORD)).andExpect(status().isOk());

        assertEquals(1, audit.clearSignInAddresses(24));

        mockMvc.perform(get("/api/v1/me").cookie(new Cookie("BATON_SESSION", first))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountActivity", hasSize(2)))
                .andExpect(jsonPath("$.accountActivity[0].remoteAddress", is(address)))
                .andExpect(jsonPath("$.accountActivity[1].action", is("SIGN_IN")))
                .andExpect(jsonPath("$.accountActivity[1].remoteAddress", nullValue()));
    }

    @Test
    void everyAuditRowAndErrorResponseCarriesItsOwnRequestsTraceId() throws Exception {
        MvcResult signIn = mockMvc.perform(signIn("act-bystander", PASSWORD)).andExpect(status().isOk()).andReturn();
        String signInTrace = (String) signIn.getRequest().getAttribute("baton.traceId");
        MvcResult refused = mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized()).andReturn();
        String refusedTrace = (String) refused.getRequest().getAttribute("baton.traceId");

        assertEquals(signInTrace, jdbc.queryForObject("select trace_id from audit_events where action='SIGN_IN'", String.class));
        assertNotEquals(signInTrace, refusedTrace);
        String bodyTrace = new tools.jackson.databind.ObjectMapper().readTree(refused.getResponse().getContentAsString()).get("traceId").stringValue();
        assertEquals(refusedTrace, bodyTrace);
        assertEquals(true, bodyTrace.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    /**
     * trace id를 본문 크기 제한보다 먼저 준다. 크기 때문에 거절된 요청도 trace id를 가진 요청이고, 거절 응답의 id는
     * 그 요청의 id다.
     */
    @Test
    void aRequestRefusedForItsSizeStillCarriesItsTraceId() throws Exception {
        MvcResult refused = mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + "x".repeat(1024 * 1024 + 16) + "\"}"))
                .andExpect(status().isPayloadTooLarge()).andReturn();

        String requestTrace = (String) refused.getRequest().getAttribute("baton.traceId");
        assertEquals(true, requestTrace != null);
        assertEquals(requestTrace, new tools.jackson.databind.ObjectMapper().readTree(refused.getResponse().getContentAsString()).get("traceId").stringValue());
    }

    /**
     * /me는 호출마다 감사 테이블을 읽으므로, 그 문장은 두 종류의 행 모두에 부분 인덱스로 닿아야 한다. 실행 계획은
     * 사본이 아니라 서비스가 돌리는 그 문장으로 물어서, 문장이 바뀌어 인덱스를 못 쓰게 되면 여기서 실패한다. 작은
     * 테이블은 인덱스와 상관없이 통째로 읽히므로 계획을 볼 때는 순차 스캔을 끈다.
     */
    @Test
    void theAccountActivityStatementUsesBothPartialIndexes() {
        String plan = plan(LocalAuthenticationService.ACCOUNT_ACTIVITY);

        // 순차 스캔을 끄면 planner는 계정 id로 찾을 수 없을 때도 인덱스를 읽어서(부분 인덱스 전체를 읽고 거른다)
        // 인덱스 이름만으로는 부족하다. 계정 id가 인덱스 조건이어야 한다.
        assertEquals(true, plan.contains("audit_events_sign_in_idx"), plan);
        assertEquals(true, plan.contains("Index Cond: (actor_id = 1)"), plan);
        assertEquals(true, plan.contains("audit_events_credential_idx"), plan);
        assertEquals(true, plan.contains("Index Cond: ((target_id)::text = '1'::text)"), plan);
    }

    private String plan(String sql) {
        return jdbc.execute((java.sql.Connection connection) -> {
            connection.setAutoCommit(false);
            try (java.sql.Statement off = connection.createStatement()) {
                off.execute("set local enable_seqscan = off");
            }
            // 두 파라미터는 계정 id로, 행위자용은 숫자, 대상용은 문자열이다.
            String statement = "explain " + sql.replaceFirst("\\?", "1").replaceFirst("\\?", "'1'");
            try (java.sql.Statement explain = connection.createStatement(); java.sql.ResultSet rows = explain.executeQuery(statement)) {
                StringBuilder text = new StringBuilder();
                while (rows.next()) text.append(rows.getString(1)).append('\n');
                return text.toString();
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        });
    }

    private MockHttpServletRequestBuilder signIn(String employeeNo, String password) {
        return post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + password + "\"}")
                .with(request -> { request.setRemoteAddr(address); return request; });
    }

    private static String cookie(MvcResult result) {
        return result.getResponse().getCookie("BATON_SESSION").getValue();
    }

    private long user(String employeeNo, String role, String password) {
        return jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,system_role,credential_hash)
                values(?,?,?,'Engineer',?,cast(? as system_role),?) returning id
                """, Long.class, organizationId, employeeNo, employeeNo, employeeNo + "@example.test", role,
                password == null ? null : PASSWORDS.encode(password));
    }
}
