package com.projectbaton.config;

import com.projectbaton.connection.IngestionWorker;
import com.projectbaton.connection.SourceStructuringWorker;
import com.projectbaton.handover.HandoverPackageWorker;
import com.projectbaton.jobs.EmbeddingWorker;
import com.projectbaton.jobs.JobSlots;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 작업 동시 실행(README §6): 배포 전체에서 호출 10개이고, 채팅이 qa/QaExecutors에서 3개, 구조화와 패키지 생성이
 * 함께 6개, 임베딩이 1개를 쓴다. API와 worker가 한 인스턴스로 돌아서 이 프로세스 안의 slot이 배포 전체의 한도가 되고,
 * 인스턴스를 늘리려면 먼저 DB에 둔 slot이 필요하다.
 *
 * <p>수집은 모델을 부르지 않으므로 따로 slot을 받아, AI 작업을 기다리지도 AI slot을 쓰지도 않는다. INGEST 작업이 남아
 * 있는 동안 runner 하나가 계속 수집한다. 5분 일정이 활성 연동마다 작업 하나를 넣어서, scheduler tick마다 작업
 * 하나로는 따라잡을 수 없다(연동 1,200개면 초당 작업 4개).
 */
@Configuration
public class JobSlotConfiguration {
    static final int GENERATION_SLOTS = 6;
    static final int EMBEDDING_SLOTS = 1;
    static final int INGESTION_SLOTS = 1;
    // 종료할 때 실행 중인 작업에 이만큼 마칠 시간을 준다. compose.yml은 컨테이너를 죽이기 전에 30초를 준다.
    static final Duration SHUTDOWN_DRAIN = Duration.ofSeconds(25);

    @Bean
    JobSlots generationSlots(SourceStructuringWorker structuring, HandoverPackageWorker packages, MeterRegistry meters) {
        return observed(new JobSlots("generation", GENERATION_SLOTS, List.of(structuring::runOnce, packages::runOnce), SHUTDOWN_DRAIN),
                "generation", meters);
    }

    @Bean
    JobSlots embeddingSlots(EmbeddingWorker embeddings, MeterRegistry meters) {
        return observed(new JobSlots("embedding", EMBEDDING_SLOTS, List.of(embeddings::runOnce), SHUTDOWN_DRAIN), "embedding", meters);
    }

    @Bean
    JobSlots ingestionSlots(IngestionWorker ingestion, MeterRegistry meters) {
        return observed(new JobSlots("ingestion", INGESTION_SLOTS, List.of(ingestion::runOnce), SHUTDOWN_DRAIN), "ingestion", meters);
    }

    /** pool별 빈 slot 수. 오래 0이면 AI 작업이 포화 상태다(README §8). */
    private static JobSlots observed(JobSlots slots, String pool, MeterRegistry meters) {
        Gauge.builder("baton.job.slots.available", slots, JobSlots::availableSlots).tag("pool", pool).register(meters);
        return slots;
    }
}
