package com.projectbaton.jobs;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이 프로세스가 실행 중인 모든 작업의 30초 lease를 갱신한다(README §6: heartbeat 10초). 갱신하지 않으면 lease보다 긴
 * 모델 호출이 첫 시도가 아직 도는 동안 복구가 작업을 다른 slot에 넘긴다. 갱신에는 작업의 현재 token이 필요해서 이미
 * 넘어간 시도는 갱신되지 않고, 작업의 deadline에서 멈춰서 멈춘 worker가 작업을 영원히 RUNNING으로 붙잡지 못한다.
 */
@Component
public class LeaseHeartbeat {
    private final JdbcTemplate jdbc;
    private final Map<UUID, UUID> held = new ConcurrentHashMap<>();

    public LeaseHeartbeat(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 가져온 작업을 lease를 갱신하면서 실행한다. */
    public void hold(JobLeaseService.JobContext context, Runnable work) {
        held.put(context.jobId(), context.leaseToken());
        try {
            work.run();
        } finally {
            held.remove(context.jobId(), context.leaseToken());
        }
    }

    @Scheduled(fixedDelayString = "${baton.jobs.heartbeat-interval:10000}")
    public void extend() {
        List<Object[]> leases = held.entrySet().stream()
                .map(entry -> new Object[] {entry.getKey(), entry.getValue()})
                .toList();
        if (leases.isEmpty()) return;
        // control lane. heartbeat는 자기가 lease를 갱신하는 작업 뒤에서 기다리면 안 된다.
        DatabaseLane.CONTROL.run(() -> jdbc.batchUpdate("""
                update jobs set lease_until=now()+interval '30 seconds', updated_at=now()
                where id=? and lease_token=? and status='RUNNING' and deadline_at>now()
                """, leases));
    }
}
