package com.projectbaton.config;

import com.projectbaton.connection.SourceStructuringHandler;
import com.projectbaton.handover.HandoverGenerationHandler;
import com.projectbaton.jobs.DatabaseLane;
import com.projectbaton.jobs.JobLeaseService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * dispatch tick마다 한 번, 한도를 넘은 작업을 실패로 처리하고 만료된 lease를 돌려준 뒤, 구조화나 패키지 작업이 끝내
 * 실패한 원문과 인수인계를 풀어 준다. 마지막 시도를 막 쓴 작업이 FAILED가 되어야 그 원문이나 인수인계를 풀 수
 * 있으므로 순서가 중요하다. 예전에는 두 단계를 claim마다 돌려서, 작업이 밀리면 생성 runner 여섯이 작업마다 반복했고
 * 풀어 주는 단계는 원문마다 최신 구조화 작업을 훑었다.
 */
@Component
public class JobHousekeeping {
    private final JobLeaseService leases;
    private final SourceStructuringHandler structuring;
    private final HandoverGenerationHandler packages;

    public JobHousekeeping(JobLeaseService leases, SourceStructuringHandler structuring, HandoverGenerationHandler packages) {
        this.leases = leases;
        this.structuring = structuring;
        this.packages = packages;
    }

    @Scheduled(fixedDelayString = "${baton.jobs.dispatch-delay:1000}", initialDelayString = "${baton.jobs.dispatch-delay:1000}")
    public void sweep() {
        DatabaseLane.CONTROL.run(() -> {
            leases.sweep();
            structuring.releaseAbandonedSources();
            packages.releaseAbandonedHandovers();
        });
    }
}
