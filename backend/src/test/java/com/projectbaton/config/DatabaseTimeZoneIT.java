package com.projectbaton.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.projectbaton.jobs.DatabaseLane;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 배정 날짜(joined_on, left_on)는 Asia/Seoul의 업무일이고 SQL은 이를 current_date와 비교한다. 그 날짜는 연결의
 * 시간대를 따르는데 드라이버가 JVM에서 시간대를 가져와서, TZ 없이 띄운 서비스(컨테이너 기본값은 UTC)는 매일 아침
 * 날짜가 서울보다 9시간 늦었고 오늘 들어온 구성원은 09:00까지 남이었다. 이제 모든 pool의 모든 연결이 Asia/Seoul로
 * 시작한다. 여기서는 JVM을 UTC로 돌려서 설정이 빠지면 드러난다.
 */
@SpringBootTest
@Testcontainers
class DatabaseTimeZoneIT {
    /**
     * PostgreSQL 드라이버는 세션을 서버가 아니라 JVM 기본 시간대로 연다. context가 뜨기 전에 JVM을 TZ 없는 컨테이너와
     * 같은 UTC로 두어서, 시간대를 고정하지 않은 pool이 드러나게 한다. 시험 엔진은 첫 클래스를 돌리기 전에 모든
     * 클래스를 불러오므로, 클래스를 불러올 때 바꾸면 이 클래스보다 먼저 도는 클래스의 시간대도 UTC가 된다. 그래서
     * before-all 메서드에서 바꾼다.
     */
    private static java.util.TimeZone originalZone;

    @org.junit.jupiter.api.BeforeAll
    static void runTheJvmInUtc() {
        originalZone = java.util.TimeZone.getDefault();
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreZone() {
        java.util.TimeZone.setDefault(originalZone);
    }

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void everyPoolStartsItsConnectionsInTheBusinessZone() {
        assertEquals("Asia/Seoul", jdbc.queryForObject("show timezone", String.class));
        assertEquals("Asia/Seoul", DatabaseLane.WORKER.call(() -> jdbc.queryForObject("show timezone", String.class)));
        assertEquals("Asia/Seoul", DatabaseLane.CONTROL.call(() -> jdbc.queryForObject("show timezone", String.class)));
    }

    @Test
    void currentDateIsTheSeoulDate() {
        assertTrue(jdbc.queryForObject("select current_date = (now() at time zone 'Asia/Seoul')::date", Boolean.class));
    }
}
