package com.projectbaton.connection;

import com.projectbaton.jobs.JobLeaseService;
import com.projectbaton.jobs.LeaseHeartbeat;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 수집 slot의 유일한 lane(config/JobSlotConfiguration). */
@Service
public class IngestionWorker {
    private final JobLeaseService jobs;
    private final IngestionJobHandler handler;
    private final LeaseHeartbeat heartbeat;

    public IngestionWorker(JobLeaseService jobs, IngestionJobHandler handler, LeaseHeartbeat heartbeat) {
        this.jobs = jobs;
        this.handler = handler;
        this.heartbeat = heartbeat;
    }

    /** 호출한 스레드에서 INGEST 작업 하나를 가져와 실행하고, 작업이 있었는지 돌려준다. */
    public boolean runOnce() {
        Optional<JobLeaseService.JobContext> claimed = jobs.claim("INGEST");
        claimed.ifPresent(context -> heartbeat.hold(context, () -> handler.execute(context)));
        return claimed.isPresent();
    }
}
