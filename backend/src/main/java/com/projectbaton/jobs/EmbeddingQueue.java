package com.projectbaton.jobs;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** README §7: 새로 확정되거나 바뀐 기록은 그 변경을 만든 트랜잭션 안에서 색인 작업을 넣는다. */
@Component
public class EmbeddingQueue {
    private final JdbcTemplate jdbc;
    private final JobRepository jobs;

    public EmbeddingQueue(JdbcTemplate jdbc, JobRepository jobs) {
        this.jdbc = jdbc;
        this.jobs = jobs;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long recordId, long recordVersion) {
        String key = "embed:" + recordId + ":" + recordVersion;
        // 같은 기록 버전에는 두 번째 색인 작업이 필요 없으므로 dedup 키는 영원히 고유하다.
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from jobs where dedup_key=?)", Boolean.class, key))) return;
        jobs.enqueue("EMBED", recordId, null, key, OffsetDateTime.now().plusMinutes(30));
    }
}
