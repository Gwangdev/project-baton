package com.projectbaton.jobs;

import java.util.Optional;
import org.springframework.stereotype.Service;

/** 임베딩 slot의 유일한 lane(config/JobSlotConfiguration). */
@Service
public class EmbeddingWorker {
    private final JobLeaseService jobs;
    private final EmbeddingHandler handler;
    private final LeaseHeartbeat heartbeat;

    public EmbeddingWorker(JobLeaseService jobs, EmbeddingHandler handler, LeaseHeartbeat heartbeat) {
        this.jobs = jobs;
        this.handler = handler;
        this.heartbeat = heartbeat;
    }

    /** 호출한 스레드에서 EMBED 작업 하나를 가져와 실행하고, 작업이 있었는지 돌려준다. */
    public boolean runOnce() {
        Optional<JobLeaseService.JobContext> claimed = jobs.claim("EMBED");
        claimed.ifPresent(context -> heartbeat.hold(context, () -> handler.execute(context)));
        return claimed.isPresent();
    }
}
