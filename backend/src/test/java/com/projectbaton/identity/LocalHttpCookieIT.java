package com.projectbaton.identity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP로 붙는 클라이언트는 Secure 쿠키를 돌려보내지 않으므로, 평문 HTTP 로컬 스택(브라우저 E2E, compose.dev.yml)은
 * Secure를 명시적으로 끈다. 다른 속성은 모두 README §4.1이 정한 그대로다.
 */
@SpringBootTest(properties = "baton.session.cookie-secure=false")
@AutoConfigureMockMvc
@Testcontainers
class LocalHttpCookieIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CsrfTokenRepository csrfTokenRepository;

    @Test
    void cookiesOmitSecureOnlyWhenTheSwitchIsOff() throws Exception {
        jdbc.update("insert into organizations (name) values ('Local HTTP Organization')");
        jdbc.update("""
                insert into users (organization_id, employee_no, name, title, email, credential_hash, is_active)
                values ((select id from organizations where name = 'Local HTTP Organization'), 'http-user', 'http-user',
                        'Engineer', 'http-user@example.test', ?, true)
                """, PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("synthetic-password"));

        var signIn = mockMvc.perform(post("/api/v1/auth/sign-in")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"http-user\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk())
                .andReturn();

        var cookies = new java.util.ArrayList<>(AuthenticationIT.csrfCookie(csrfTokenRepository));
        cookies.addAll(signIn.getResponse().getHeaders("Set-Cookie"));
        AuthenticationIT.assertCookie(signIn.getResponse().getHeaders("Set-Cookie"), "BATON_SESSION",
                "HttpOnly", "SameSite=Lax", "Path=/");
        Assertions.assertTrue(cookies.stream().noneMatch(value -> value.contains("Secure")), cookies.toString());
    }
}
