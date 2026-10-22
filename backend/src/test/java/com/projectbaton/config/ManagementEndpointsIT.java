package com.projectbaton.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * README §8: liveness, readiness, Prometheus metric은 내부 관리 포트에서만 나간다. readiness는 DB를 확인하고, LLM이
 * 멈춰도 서비스가 프록시에서 빠지지 않도록 모델은 readiness 조건이 아니다. 공개 API 포트는 이 가운데 아무것도
 * 내보내지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Testcontainers
class ManagementEndpointsIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int apiPort;
    @LocalManagementPort int managementPort;
    @Autowired ObjectMapper json;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void livenessChecksOnlyTheProcessAndReadinessChecksTheDatabase() throws Exception {
        HttpResponse<String> liveness = get(managementPort, "/actuator/health/liveness");
        HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");

        assertEquals(200, liveness.statusCode(), liveness.body());
        assertEquals(200, readiness.statusCode(), readiness.body());
        assertEquals(java.util.Set.of("livenessState"), components(liveness));
        assertEquals(java.util.Set.of("readinessState", "db"), components(readiness));
    }

    @Test
    void prometheusExposesJobSlotAndHttpMetricsWithoutUserOrProjectTags() throws Exception {
        get(apiPort, "/api/v1/auth/csrf");
        HttpResponse<String> metrics = get(managementPort, "/actuator/prometheus");

        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("baton_job_slots_available{pool=\"generation\"} 6.0"), "generation slots");
        assertTrue(metrics.body().contains("baton_job_slots_available{pool=\"embedding\"} 1.0"), "embedding slot");
        assertTrue(metrics.body().contains("http_server_requests_seconds_count"), "HTTP server timings");
        assertFalse(metrics.body().matches("(?s).*(user_?id|project_?id|employee).*"), "no identifying tags");
    }

    @Test
    void httpTimingsCarryBucketsAtTheTwoLatencyTargets() throws Exception {
        // README §8은 p95 목표를 500ms(일반 API)와 1s(타임라인)로 정한다. bucket이 없으면 서버는 p95를 아예 알릴 수
        // 없고, 그 값에 정확히 둔 bucket이 있어야 모니터가 목표마다 그 안의 요청을 셀 수 있다.
        get(apiPort, "/api/v1/auth/csrf");
        String body = get(managementPort, "/actuator/prometheus").body();

        assertTrue(body.matches("(?s).*http_server_requests_seconds_bucket\\{[^}]*le=\"0\\.5\".*"), "bucket at 500 ms");
        assertTrue(body.matches("(?s).*http_server_requests_seconds_bucket\\{[^}]*le=\"1\\.0\".*"), "bucket at 1 s");
    }

    @Test
    void thePublicPortServesNoActuatorEndpoint() throws Exception {
        for (String path : java.util.List.of("/actuator/health", "/actuator/health/readiness", "/actuator/prometheus")) {
            int status = get(apiPort, path).statusCode();
            assertTrue(status == 401 || status == 404, path + " answered " + status);
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private java.util.Set<String> components(HttpResponse<String> response) {
        JsonNode components = json.readTree(response.body()).path("components");
        java.util.Set<String> names = new java.util.TreeSet<>();
        components.propertyNames().forEach(names::add);
        return names;
    }
}
