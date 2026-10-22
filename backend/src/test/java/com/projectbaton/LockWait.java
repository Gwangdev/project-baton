package com.projectbaton;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 한 연결에서 행 잠금을 쥔 시험이, 고정된 대기로 짐작하지 않고 다른 세션이 정말 그 잠금을 기다리는지 알게 한다.
 * 대기가 너무 짧으면 잠금 순서를 증명하지 않고도 시험이 통과한다.
 */
public final class LockWait {
    private LockWait() {
    }

    public static void untilASessionWaits(JdbcTemplate jdbc) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no session waited for a lock within 10 seconds");
    }
}
