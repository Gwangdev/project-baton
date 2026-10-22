package com.projectbaton.handover;

import com.projectbaton.jobs.JobLeaseService;
import com.projectbaton.jobs.LeaseHeartbeat;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 공유 생성 slot의 BUILD_PACKAGE lane(config/JobSlotConfiguration). */
@Service
public class HandoverPackageWorker {
    private final JobLeaseService jobs;
    private final HandoverGenerationHandler handler;
    private final LeaseHeartbeat heartbeat;

    public HandoverPackageWorker(JobLeaseService jobs, HandoverGenerationHandler handler, LeaseHeartbeat heartbeat) {
        this.jobs = jobs;
        this.handler = handler;
        this.heartbeat = heartbeat;
    }

    /** 호출한 스레드에서 BUILD_PACKAGE 작업 하나를 가져와 실행하고, 작업이 있었는지 돌려준다. */
    public boolean runOnce() {
        Optional<JobLeaseService.JobContext> claimed = jobs.claim("BUILD_PACKAGE");
        claimed.ifPresent(context -> heartbeat.hold(context, () -> handler.execute(context)));
        return claimed.isPresent();
    }
}
