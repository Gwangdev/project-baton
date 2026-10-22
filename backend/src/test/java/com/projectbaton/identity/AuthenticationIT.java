package com.projectbaton.identity;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthenticationIT {

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
    private CsrfTokenRepository csrfTokenRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from spring_session_attributes");
        jdbcTemplate.update("delete from spring_session");
        jdbcTemplate.update("delete from users");
        jdbcTemplate.update("delete from organizations");
        jdbcTemplate.update("insert into organizations (name) values ('Auth Test Organization')");
    }

    @Test
    void rejectsWrongPasswordWithoutRevealingWhetherTheAccountExists() throws Exception {
        localUser("active-user", true);

        mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"active-user\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type", is("about:blank")))
                .andExpect(jsonPath("$.title", is("Unauthorized")))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance", is("/api/v1/auth/sign-in")))
                .andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void throttlesTheSixthFailedAttemptForTheSameAccountAndIp() throws Exception {
        localUser("limited-user", true);

        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/sign-in")
                            .with(csrf().asHeader())
                            .with(request -> { request.setRemoteAddr("198.51.100.10"); return request; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"employeeNo\":\"limited-user\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")));
        }

        mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .with(request -> { request.setRemoteAddr("198.51.100.10"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"limited-user\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code", is("AUTH_RATE_LIMITED")));
    }

    @Test
    void rejectsInactiveUserOnlyAfterThePasswordMatches() throws Exception {
        localUser("inactive-user", false);

        mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"inactive-user\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCOUNT_INACTIVE")));
    }

    @Test
    void rejectsAnonymousCurrentUserRequest() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance", is("/api/v1/me")))
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
    }

    @Test
    void returnsFieldErrorsInTheSharedProblemFormatForInvalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.code", is("INVALID_INPUT")))
                .andExpect(jsonPath("$.errors[0].field").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void rejectsStateChangingAuthenticationRequestWithoutCsrfToken() throws Exception {
        localUser("csrf-user", true);

        mockMvc.perform(post("/api/v1/auth/sign-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"csrf-user\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("CSRF_REQUIRED")));
    }

    @Test
    void returnsANoStoreCsrfTokenUsingTheContractHeaderName() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName", is("X-CSRF-TOKEN")));
    }

    @Test
    void restoresTheJdbcBackedSessionForTheNextProtectedRequest() throws Exception {
        localUser("session-user", true);
        var signIn = mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"session-user\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("BATON_SESSION"))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andReturn();

        mockMvc.perform(get("/api/v1/me").cookie(signIn.getResponse().getCookie("BATON_SESSION")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.employeeNo", is("session-user")))
                // OAS User는 authProvider를 요구하고 organizationName을 선언한다. createdAt은 스키마에 없다.
                .andExpect(jsonPath("$.user.authProvider", is("LOCAL")))
                .andExpect(jsonPath("$.user.organizationName").isNotEmpty())
                .andExpect(jsonPath("$.user.createdAt").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbcTemplate.queryForObject("select count(*) from spring_session", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1800,
                jdbcTemplate.queryForObject("select max_inactive_interval from spring_session", Integer.class));
    }

    @Test
    void removesSessionOnSignOutSoProtectedEndpointBecomesUnauthorized() throws Exception {
        localUser("signout-user", true);

        var signIn = mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"signout-user\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("BATON_SESSION"))
                .andReturn();

        var sessionCookie = signIn.getResponse().getCookie("BATON_SESSION");
        mockMvc.perform(post("/api/v1/auth/sign-out")
                        .cookie(sessionCookie)
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("BATON_SESSION", 0));

        mockMvc.perform(get("/api/v1/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void issuesSecureSessionAndCsrfCookiesUnlessTheDeploymentSwitchesThemOff() throws Exception {
        localUser("secure-cookie-user", true);

        // MockMvc의 csrf() 후처리가 filter의 repository를 시험용으로 바꾸므로 repository bean에서 확인한다.
        assertCookie(csrfCookie(csrfTokenRepository), "XSRF-TOKEN", "Secure", "Path=/");

        var signIn = mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"secure-cookie-user\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertCookie(signIn.getResponse().getHeaders("Set-Cookie"), "BATON_SESSION",
                "Secure", "HttpOnly", "SameSite=Lax", "Path=/");
    }

    @Test
    void signOutExpiresTheSessionCookieOnceWithTheAttributesItWasIssuedWith() throws Exception {
        localUser("expire-cookie-user", true);
        var signIn = mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"expire-cookie-user\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk())
                .andReturn();

        var signOut = mockMvc.perform(post("/api/v1/auth/sign-out")
                        .cookie(signIn.getResponse().getCookie("BATON_SESSION"))
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent())
                .andReturn();
        assertCookie(signOut.getResponse().getHeaders("Set-Cookie"), "BATON_SESSION",
                "Max-Age=0", "Secure", "HttpOnly", "SameSite=Lax", "Path=/");
    }

    @Test
    void rejectsTheNextProtectedRequestAfterTheSignedInUserIsDeactivated() throws Exception {
        long userId = localUser("deactivated-user", true);
        var signIn = mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"deactivated-user\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk())
                .andReturn();

        jdbcTemplate.update("update users set is_active = false where id = ?", userId);

        mockMvc.perform(get("/api/v1/me").cookie(signIn.getResponse().getCookie("BATON_SESSION")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCOUNT_INACTIVE")));
    }

    /** 이름에 대한 Set-Cookie가 정확히 하나이고, 기대한 속성을 모두 담는다. */
    static void assertCookie(java.util.List<String> setCookies, String name, String... attributes) {
        var matching = setCookies.stream().filter(value -> value.startsWith(name + "=")).toList();
        org.junit.jupiter.api.Assertions.assertEquals(1, matching.size(), "Set-Cookie headers: " + setCookies);
        for (String attribute : attributes) {
            org.junit.jupiter.api.Assertions.assertTrue(matching.getFirst().contains(attribute),
                    name + " lacks " + attribute + ": " + matching.getFirst());
        }
    }

    static java.util.List<String> csrfCookie(CsrfTokenRepository repository) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        repository.saveToken(repository.generateToken(request), request, response);
        return response.getHeaders("Set-Cookie");
    }

    private long localUser(String employeeNo, boolean active) {
        return jdbcTemplate.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email, credential_hash, is_active)
                values ((select id from organizations where name = 'Auth Test Organization'), ?, ?, 'Engineer', ?, ?, ?)
                returning id
                """, Long.class, employeeNo, employeeNo, employeeNo + "@example.test",
                PASSWORD_ENCODER.encode("synthetic-password"), active);
    }
}
