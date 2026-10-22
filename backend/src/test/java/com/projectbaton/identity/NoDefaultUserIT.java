package com.projectbaton.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 계정은 users 테이블에 있고 {@link LocalAuthenticationService}가 확인하며, form이나 basic 로그인은 없다. 그래도
 * Spring Boot는 자동 구성을 빼지 않으면 생성한 비밀번호로 메모리 사용자를 만들어 기동 로그에 찍어서, 모든 환경의
 * 로그에 비밀번호 같은 비밀이 남는다. 빼는 설정은 본 구성에 있어서 이 시험은 따로 설정하지 않는다.
 */
@SpringBootTest
@Testcontainers
class NoDefaultUserIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ApplicationContext context;

    @Test
    void noInMemoryUserWithAGeneratedPasswordExists() {
        assertEquals(0, context.getBeanNamesForType(UserDetailsService.class).length);
    }
}
