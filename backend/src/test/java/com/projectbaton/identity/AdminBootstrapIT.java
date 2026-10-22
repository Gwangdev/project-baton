package com.projectbaton.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 운영 스택은 빈 사용자 테이블로 시작하고 공개 가입이 없고 관리자가 만든 계정에는 자격 증명이 없어서, 아무도 로그인할
 * 수 없었다. 이제 활성 시스템 관리자가 없는 동안 환경변수로 첫 시스템 관리자를 한 번 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminBootstrapIT {
    private static final String PASSWORD = UUID.randomUUID().toString();

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired PasswordEncoder passwords;
    @Autowired TransactionTemplate transactions;
    @Autowired com.projectbaton.audit.AuditService audit;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate table organizations restart identity cascade");
        AuditRows.clear(jdbc);
    }

    @Test
    void createsTheFirstSystemAdministratorWhoCanSignIn() throws Exception {
        bootstrap(settings()).run();

        assertEquals(1, admins());
        mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"first-admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void doesNothingOnceAnActiveSystemAdministratorExists() {
        bootstrap(settings()).run();
        bootstrap(new AdminBootstrap.Settings("second-admin", PASSWORD, "Second", "second@example.test", "Bootstrap Org", false)).run();

        assertEquals(1, admins());
        assertEquals(0, jdbc.queryForObject("select count(*) from users where employee_no='second-admin'", Integer.class));
    }

    @Test
    void doesNothingWithoutSettingsAndRefusesHalfOfThem() {
        bootstrap(new AdminBootstrap.Settings("", "", "", "", "", false)).run();
        assertEquals(0, admins());

        assertThrows(IllegalStateException.class,
                () -> bootstrap(new AdminBootstrap.Settings("first-admin", "", "", "", "", false)).run());
        assertEquals(0, admins());
    }

    @Test
    void refusesAnInactiveOrganizationOrATakenAccountWithAClearReason() {
        jdbc.update("insert into organizations(name,is_active) values('Bootstrap Org', false)");
        IllegalStateException inactive = assertThrows(IllegalStateException.class, () -> bootstrap(settings()).run());
        assertEquals(true, inactive.getMessage().contains("inactive"));

        jdbc.update("update organizations set is_active=true");
        jdbc.update("""
                insert into users(organization_id,employee_no,name,title,email)
                values((select id from organizations where name='Bootstrap Org'),'first-admin','Existing','Engineer','existing@example.test')
                """);
        IllegalStateException taken = assertThrows(IllegalStateException.class, () -> bootstrap(settings()).run());
        assertEquals(true, taken.getMessage().contains("already"));
        assertEquals(0, admins());
    }

    /**
     * 시스템 관리자에게는 설정 코드를 발급하지 않아서, 비밀번호를 잃은 관리자에게는 운영자만 남는다. 같은 변수에
     * 재설정 플래그를 더하면 비밀번호를 바꾸고, 관리자의 세션을 끝내고, 감사에 남긴다.
     */
    @Test
    void theOperatorCanResetTheOnlyAdministratorsPasswordAndItEndsTheirSessions() throws Exception {
        bootstrap(settings()).run();
        String session = mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"first-admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
        String replacement = "replacement-" + UUID.randomUUID();

        bootstrap(resetSettings("first-admin", replacement)).run();

        mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"first-admin\",\"password\":\"" + PASSWORD + "\"}")
                        .with(request -> { request.setRemoteAddr("198.51.100.77"); return request; }))
                .andExpect(status().isUnauthorized());
        String after = mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"first-admin\",\"password\":\"" + replacement + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me")
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", session)))
                .andExpect(status().isUnauthorized());
        // 관리자는 계정의 행위 가운데 재설정을 최신순으로 보고, 요청이 아니었으므로 주소는 없다.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me")
                        .cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", after)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.accountActivity[0].action", org.hamcrest.Matchers.is("SIGN_IN")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.accountActivity[1].action", org.hamcrest.Matchers.is("RESET_SYSTEM_ADMIN_CREDENTIAL")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.accountActivity[1].remoteAddress").value(org.hamcrest.Matchers.nullValue()));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_events where action='RESET_SYSTEM_ADMIN_CREDENTIAL' and actor_id is null", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events t where t::text like ?", Integer.class, "%" + replacement + "%"));
    }

    @Test
    void theVariablesAloneNeverResetAPassword() throws Exception {
        bootstrap(settings()).run();

        bootstrap(new AdminBootstrap.Settings("first-admin", "other-" + UUID.randomUUID(), "First Admin", "first-admin@example.test", "Bootstrap Org", false)).run();

        mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"first-admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where action='RESET_SYSTEM_ADMIN_CREDENTIAL'", Integer.class));
    }

    /**
     * 관리자는 권한이 가장 큰데도, 설정 코드 비밀번호가 따르는 규칙을 피한 유일한 계정이었다. 이제 첫 관리자와 재설정
     * 모두 너무 짧거나 길거나 사번과 같은 비밀번호를 거절하고, 메시지에는 값을 넣지 않는다.
     */
    @Test
    void aWeakAdministratorPasswordStopsStartUpAndTheMessageDoesNotQuoteIt() {
        for (String weak : new String[] {"short-pw", "a".repeat(129), "first-admin"}) {
            IllegalStateException create = assertThrows(IllegalStateException.class,
                    () -> bootstrap(new AdminBootstrap.Settings("first-admin", weak, "N", "n@example.test", "Bootstrap Org", false)).run());
            assertEquals(true, create.getMessage().contains("BATON_BOOTSTRAP_ADMIN_PASSWORD"), create.getMessage());
            assertEquals(false, create.getMessage().contains(weak), "the value is not quoted");
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from users", Integer.class), "a refused password creates nobody");

        bootstrap(settings()).run();
        for (String weak : new String[] {"short-pw", "a".repeat(129), "first-admin"}) {
            IllegalStateException reset = assertThrows(IllegalStateException.class, () -> bootstrap(resetSettings("first-admin", weak)).run());
            assertEquals(true, reset.getMessage().contains("BATON_BOOTSTRAP_ADMIN_PASSWORD"), reset.getMessage());
            assertEquals(false, reset.getMessage().contains(weak), "the value is not quoted");
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where action='RESET_SYSTEM_ADMIN_CREDENTIAL'", Integer.class));
    }

    /**
     * 문서대로 관리자가 생기면 변수를 무시하므로, 규칙이 생기기 전부터 변수에 남아 있던 비밀번호가 재기동을 막아서도
     * 무엇을 바꿔서도 안 된다.
     */
    @Test
    void aLeftoverWeakPasswordIsIgnoredOnceAnAdministratorExists() {
        bootstrap(settings()).run();
        int users = jdbc.queryForObject("select count(*) from users", Integer.class);

        bootstrap(new AdminBootstrap.Settings("another-admin", "short-pw", "N", "n@example.test", "Bootstrap Org", false)).run();

        assertEquals(users, jdbc.queryForObject("select count(*) from users", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from users where employee_no = 'another-admin'", Integer.class));
    }

    @Test
    void theResetFlagWithoutTheOtherVariablesStopsStartUpInsteadOfDoingNothing() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> bootstrap(new AdminBootstrap.Settings("", "", "", "", "", true)).run());

        assertEquals(true, refused.getMessage().contains("BATON_BOOTSTRAP_ADMIN_RESET"));
    }

    @Test
    void aResetThatMatchesNoActiveLocalAdministratorStopsStartUpWithTheReason() {
        bootstrap(settings()).run();
        jdbc.update("""
                insert into users(organization_id,employee_no,name,title,email)
                values((select id from organizations where name='Bootstrap Org'),'plain-user','Plain','Engineer','plain@example.test')
                """);

        IllegalStateException notAdmin = assertThrows(IllegalStateException.class, () -> bootstrap(resetSettings("plain-user", "x".repeat(20))).run());
        IllegalStateException unknown = assertThrows(IllegalStateException.class, () -> bootstrap(resetSettings("nobody", "x".repeat(20))).run());

        assertEquals(true, notAdmin.getMessage().contains("BATON_BOOTSTRAP_ADMIN_RESET") && unknown.getMessage().contains("EMPLOYEE_NO"));
    }

    private AdminBootstrap.Settings settings() {
        return new AdminBootstrap.Settings("first-admin", PASSWORD, "First Admin", "first-admin@example.test", "Bootstrap Org", false);
    }

    private AdminBootstrap.Settings resetSettings(String employeeNo, String password) {
        return new AdminBootstrap.Settings(employeeNo, password, "First Admin", "first-admin@example.test", "Bootstrap Org", true);
    }

    private AdminBootstrap bootstrap(AdminBootstrap.Settings settings) {
        return new AdminBootstrap(jdbc, passwords, transactions, audit, settings, 12);
    }

    private int admins() {
        return jdbc.queryForObject("select count(*) from users where system_role='SYSTEM_ADMIN' and is_active", Integer.class);
    }
}
