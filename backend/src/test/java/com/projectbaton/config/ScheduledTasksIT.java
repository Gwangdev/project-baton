package com.projectbaton.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 첫 실행이 기동 때인 예약 작업은 클래스의 첫 시험이 하는 일 한가운데서 돈다. 시험은 TRUNCATE로 테이블을 비우는데,
 * TRUNCATE가 잡는 잠금 순서가 작업의 잠금 순서와 엇갈려 교착으로 끝날 수 있다(수집 worker 시험에서 실제로 한 번
 * 일어났다). 그래서 시험 프로필은 모든 예약 작업의 첫 실행을 미룬다. 기동 때 돌게 남겨 둔 두 작업은 이유와 함께 여기
 * 이름을 적어서, 새 작업이 모르는 사이에 끼어들지 못하게 한다.
 */
@SpringBootTest
@Testcontainers
class ScheduledTasksIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * 기동 때 돌게 남겨 둔 작업. Q&A 복구는 질문 행과 작업만 조직, 프로젝트, 작업의 TRUNCATE와 엇갈릴 수 없는 순서로
     * 건드리고, heartbeat는 시험이 쥐지 않은 lease만 늘린다. 둘 다 가정하지 않고 확인했다.
     */
    private static final Set<String> AT_START_UP = Set.of("QaRecoveryService.recover", "LeaseHeartbeat.extend");
    private static final Duration LONGER_THAN_ANY_TEST_CLASS = Duration.ofMinutes(5);

    @Autowired ScheduledAnnotationBeanPostProcessor scheduling;

    @Test
    void everyScheduledTaskExceptTheNamedOnesWaitsBeforeItsFirstRun() {
        List<String> problems = new ArrayList<>();
        Set<String> leftAtStartUp = new java.util.HashSet<>();
        int seen = 0;
        for (ScheduledTask scheduled : scheduling.getScheduledTasks()) {
            seen++;
            // runnable은 감싸져 있고, 그 글자는 예약된 메서드(선언한 클래스와 메서드 이름)다.
            String name = scheduled.getTask().getRunnable().toString();
            if (!(scheduled.getTask() instanceof FixedDelayTask task)) {
                // fixed-rate나 cron 작업은 바로 시작하거나 자기 시계로 시작한다. 이 시험은 지연만 판단할 줄 안다.
                problems.add(name + " is not a fixed-delay task, so its first run is not judged here");
                continue;
            }
            boolean named = AT_START_UP.stream().anyMatch(name::endsWith);
            if (named) {
                leftAtStartUp.addAll(AT_START_UP.stream().filter(name::endsWith).toList());
            } else if (task.getInitialDelayDuration().compareTo(LONGER_THAN_ANY_TEST_CLASS) < 0) {
                problems.add(name + " first runs after " + task.getInitialDelayDuration());
            }
        }

        assertTrue(seen >= 9, "the scheduled tasks were found: " + seen);
        assertEquals(List.of(), problems);
        // 이름을 올린 예외가 더는 없으면 남겨 두어 새 작업의 핑계가 되지 않게 목록에서 빼야 한다.
        assertEquals(AT_START_UP, leftAtStartUp);
    }
}
