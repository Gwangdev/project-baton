package com.projectbaton.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring MVC 밖의 실패(servlet filter, 보안 firewall, 컨테이너 오류 dispatch)는 {@code ApiExceptionHandler}에 닿지
 * 않는다. MockMvc는 컨테이너의 오류 dispatch를 하지 않아서 이 확인은 실제 서버와 실제 HTTP client를 쓴다. 서버가 평문
 * HTTP라 클라이언트가 Secure 쿠키를 돌려보내지 않으므로, 다른 로컬 HTTP 스택처럼 세션 쿠키의 Secure 플래그를 끈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "baton.session.cookie-secure=false")
@Testcontainers
class ErrorDispatchIT {
    private static final List<String> BOOT_DEFAULT_FIELDS = List.of("\"timestamp\"", "\"error\"", "\"path\"", "\"trace\"", "\"exception\"");

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations(name) values('Error Dispatch Organization')");
        jdbc.update("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),'dispatch-user','dispatch-user','Engineer','dispatch@test',?)",
                PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("synthetic-password"));
    }

    @Test
    void failureInsideAServletFilterBecomesAGenericProblem() throws Exception {
        HttpClient client = signedInClient();
        jdbc.execute("alter table organizations rename to organizations_offline");
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(uri("/api/v1/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
        } finally {
            jdbc.execute("alter table organizations_offline rename to organizations");
        }

        assertProblem(response, 500, "INTERNAL_ERROR");
        assertFalse(response.body().contains("organizations"), response.body());
        assertFalse(response.body().contains("relation"), response.body());
        assertFalse(response.body().contains("Exception"), response.body());
    }

    @Test
    void firewallRejectionKeepsItsStatusAsAProblem() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/api/v1/projects;jsessionid=forged")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 400, "INVALID_INPUT");
        assertFalse(response.body().contains("RequestRejected"), response.body());

        // 오류 dispatch는 POST 메서드를 그대로 가지고 온다. 그래도 405가 아니라 거절을 그려야 한다.
        HttpResponse<String> post = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/v1/projects;jsessionid=forged"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(post, 400, "INVALID_INPUT");
    }

    @Test
    void theErrorPathIsNotAReadableEndpoint() throws Exception {
        HttpResponse<String> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/error")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(anonymous, 401, "UNAUTHENTICATED");

        HttpResponse<String> signedIn = signedInClient().send(
                HttpRequest.newBuilder(uri("/error")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(signedIn, 404, "NOT_FOUND");
    }

    private JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"), response.headers().toString());
        JsonNode body = json.readTree(response.body());
        assertEquals(status, body.get("status").asInt(), response.body());
        if (code != null) assertEquals(code, body.get("code").asText(), response.body());
        assertFalse(body.get("traceId").asText().isBlank(), response.body());
        for (String field : BOOT_DEFAULT_FIELDS) assertFalse(response.body().contains(field), response.body());
        return body;
    }

    private HttpClient signedInClient() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        JsonNode csrf = json.readTree(client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        HttpResponse<String> signIn = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/sign-in"))
                .header("Content-Type", "application/json").header(csrf.get("headerName").asText(), csrf.get("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString("{\"employeeNo\":\"dispatch-user\",\"password\":\"synthetic-password\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, signIn.statusCode(), signIn.body());
        return client;
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
}
