package com.projectbaton.shared;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** contracts.md C01: 1 MiB를 넘는 JSON 본문은 인증이나 해석보다 먼저 413으로 거절한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RequestBodyLimitIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;

    @Test
    void aBodyOverOneMebibyteIsRefusedWith413() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(RequestBodyLimitFilter.MAX_BODY_BYTES + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code", is("PAYLOAD_TOO_LARGE")));
    }

    @Test
    void aBodyOfExactlyOneMebibyteIsReadNormally() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(body(RequestBodyLimitFilter.MAX_BODY_BYTES)))
                .andExpect(status().is(not(413)));
    }

    /** 정확히 {@code size} 바이트로 채운 JSON 객체. */
    private static byte[] body(long size) {
        String prefix = "{\"employeeNo\":\"limit-user\",\"password\":\"";
        String suffix = "\"}";
        String padding = "x".repeat((int) (size - prefix.length() - suffix.length()));
        return (prefix + padding + suffix).getBytes(StandardCharsets.UTF_8);
    }
}
