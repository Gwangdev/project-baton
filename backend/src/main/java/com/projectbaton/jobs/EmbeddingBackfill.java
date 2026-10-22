package com.projectbaton.jobs;

import java.time.OffsetDateTime;
import java.util.List;

import com.projectbaton.qa.EmbeddingClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 새 임베딩 모델에 빠진 색인 작업을 넣는다. 기록의 색인 작업은 기록을 확정할 때 같은 기록 버전에는 다시 쓰지 않는
 * 키로 들어가고, 벡터는 만든 모델의 id 아래에 저장된다. 그래서 배포가 다른 임베딩 모델로 바뀌면(README §7) 이전
 * 모델로 색인한 기록을 다시 넣는 곳이 없고, 현재 모델의 벡터만 읽는 검색은 그 기록을 찾지 못한다.
 *
 * <p>주기마다 기록 id의 한 구간에서, 현재 모델의 벡터도 끝나지 않은 색인 작업도 없는 확인·수정 기록 버전을 찾아 모델
 * 이름이 들어간 키로 한 묶음까지 넣는다. 다음 주기는 다음 구간을 맡고 마지막 기록 뒤에서는 처음부터 다시 돌아서, 한
 * 주기는 모든 기록을 훑지 않고 색인 범위 하나만 읽는다(기록 900,000건을 한 바퀴 도는 데 몇 시간 걸리지만 backfill에는
 * 충분하다). 큐가 가득 차면 늘리지 않고 멈춘다(README §6).
 */
@Component
public class EmbeddingBackfill {
    private final JdbcTemplate jdbc;
    private final JobRepository jobs;
    private final EmbeddingClient client;
    private final int batch;
    private final long window;
    /** 순회가 마지막으로 끝낸 기록 id. 다음 주기는 그 뒤부터 시작한다. */
    private long cursor;

    @Autowired
    public EmbeddingBackfill(JdbcTemplate jdbc, JobRepository jobs, EmbeddingClient client,
                             @Value("${baton.embedding.backfill-batch:200}") int batch,
                             @Value("${baton.embedding.backfill-window:5000}") long window) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.client = client;
        this.batch = batch;
        this.window = window;
    }

    @Scheduled(fixedDelayString = "${baton.embedding.backfill-delay:60000}", initialDelayString = "${baton.embedding.backfill-delay:60000}")
    public void run() {
        DatabaseLane.CONTROL.run(this::enqueueMissing);
    }

    /** 이번 호출이 넣은 색인 작업 수를 돌려준다. */
    public synchronized int enqueueMissing() {
        String model = client.modelId();
        long from = cursor;
        long to = from + window;
        List<long[]> missing = jdbc.query("""
                select record.id, record.version
                from work_records record
                where record.id > ? and record.id <= ?
                  and record.trust_status in ('CONFIRMED','CORRECTED')
                  and not exists (select 1 from record_embeddings vector
                                  where vector.record_id = record.id and vector.record_version = record.version
                                    and vector.embedding_model = ?)
                  and not exists (select 1 from jobs job
                                  where job.kind = 'EMBED' and job.target_id = record.id
                                    and job.status in ('PENDING','RUNNING','RETRY_WAIT'))
                  and not exists (select 1 from jobs job where job.dedup_key = 'embed:' || record.id || ':' || record.version || ':' || ?)
                order by record.id
                limit ?
                """, (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)}, from, to, model, model, batch);
        int queued = 0;
        boolean saturated = false;
        for (long[] record : missing) {
            try {
                jobs.enqueue("EMBED", record[0], null, "embed:" + record[0] + ":" + record[1] + ":" + model, OffsetDateTime.now().plusMinutes(30));
                queued++;
            } catch (JobQueueSaturatedException full) {
                saturated = true;
                break;
            }
        }
        // 한 묶음(또는 큐)이 가득 차면 이 구간에 남은 기록이 있을 수 있어서 마지막으로 처리한 기록 뒤부터 다시 이어
        // 간다. 그렇지 않으면 구간 전체를 끝낸 것이고, 그 뒤에 기록이 없으면 처음부터 다시 돈다.
        if (missing.size() == batch || saturated) {
            cursor = queued == 0 ? from : missing.get(queued - 1)[0];
        } else {
            boolean beyond = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_records where id > ?)", Boolean.class, to));
            cursor = beyond ? to : 0;
        }
        return queued;
    }
}
