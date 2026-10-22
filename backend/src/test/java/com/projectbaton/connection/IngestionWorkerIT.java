package com.projectbaton.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.projectbaton.jobs.JobLeaseService;
import com.projectbaton.jobs.JobRepository;
import com.projectbaton.jobs.JobSlots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class IngestionWorkerIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JobRepository jobRepository;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired IngestionScheduler scheduler;
    @Autowired IngestionWorker worker;
    @Autowired IngestionJobHandler handler;
    @Autowired JobLeaseService leases;
    @Autowired @Qualifier("ingestionSlots") JobSlots ingestionSlots;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate table organizations, projects, jobs restart identity cascade");
        jdbc.update("insert into organizations(name) values ('Ingestion Worker Organization')");
    }

    @Test
    void schedulerQueuesOneIngestPerActiveConnectionAndWorkerCollectsIt() {
        long project = project();
        long active = connection(project, "ACTIVE");
        long paused = connection(project, "PAUSED");

        scheduler.enqueueDue();
        scheduler.enqueueDue();

        assertEquals(1, unfinishedIngest(active));
        assertEquals(0, unfinishedIngest(paused));

        worker.runOnce();

        assertEquals(0, unfinishedIngest(active));
        assertEquals("SUCCEEDED", jdbc.queryForObject("select status from jobs where kind='INGEST' and target_id=?", String.class, active));
        assertEquals(2, jdbc.queryForObject("select count(*) from record_sources where source_connection_id=?", Integer.class, active));
        assertEquals(2, jdbc.queryForObject("select count(*) from jobs where kind='STRUCTURE'", Integer.class));
        assertEquals("2", checkpoint(active));
        assertEquals("SUCCEEDED", jdbc.queryForObject("select last_status::text from source_connections where id=?", String.class, active));

        scheduler.enqueueDue();
        assertEquals(1, unfinishedIngest(active));
    }

    @Test
    void workerThatLostItsLeaseCannotPublishSourcesOrCheckpoint() {
        long connection = connection(project(), "ACTIVE");
        scheduler.enqueueDue();
        JobLeaseService.JobContext context = leases.claim("INGEST").orElseThrow();
        jdbc.update("update jobs set lease_token=gen_random_uuid() where id=?", context.jobId());

        assertFalse(handler.execute(context));

        assertEquals(0, jdbc.queryForObject("select count(*) from record_sources", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from jobs where kind='STRUCTURE'", Integer.class));
        assertNull(checkpoint(connection));
    }

    @Test
    void connectionPausedAfterQueuingFailsTheJobWithoutCollecting() {
        long connection = connection(project(), "ACTIVE");
        scheduler.enqueueDue();
        jdbc.update("update source_connections set status='PAUSED' where id=?", connection);

        worker.runOnce();

        assertEquals("FAILED", jdbc.queryForObject("select status from jobs where kind='INGEST'", String.class));
        assertEquals("CONNECTION_INACTIVE", jdbc.queryForObject("select failure_code from jobs where kind='INGEST'", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from record_sources", Integer.class));
        assertNull(checkpoint(connection));
    }

    @Test
    void changedContentUnderTheSameVersionFailsTheJobAndMarksTheConnection() {
        long project = project();
        long connection = connection(project, "ACTIVE");
        jdbc.update("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,'fixture-brief',now(),?,'fixture-brief','v1',repeat('f',64),'earlier synthetic body')
                """, connection, project);
        scheduler.enqueueDue();

        worker.runOnce();

        assertEquals("SOURCE_VERSION_CONFLICT", jdbc.queryForObject("select failure_code from jobs where kind='INGEST'", String.class));
        assertEquals("FAILED", jdbc.queryForObject("select last_status::text from source_connections where id=?", String.class, connection));
        assertEquals("SOURCE_VERSION_CONFLICT", jdbc.queryForObject("select last_message from source_connections where id=?", String.class, connection));
        assertEquals(1, jdbc.queryForObject("select count(*) from record_sources", Integer.class));
        assertNull(checkpoint(connection));
    }

    @Test
    void saturatedQueueLeavesCheckpointAndJobForLeaseRecovery() {
        long connection = connection(project(), "ACTIVE");
        scheduler.enqueueDue();
        jdbc.update("""
                insert into jobs(id,kind,target_id,dedup_key,status,available_at,deadline_at)
                select gen_random_uuid(),'STRUCTURE',g,'saturation-'||g,'PENDING',now()+interval '1 day',now()+interval '2 days'
                from generate_series(1,49999) g
                """);

        worker.runOnce();

        assertEquals(0, jdbc.queryForObject("select count(*) from record_sources", Integer.class));
        assertNull(checkpoint(connection));
        assertEquals("RUNNING", jdbc.queryForObject("select status from jobs where kind='INGEST'", String.class));
        assertEquals(1, jdbc.queryForObject("select attempts from jobs where kind='INGEST'", Integer.class));
    }

    @Test
    void saturatedQueueSkipsSchedulingWithoutFailing() {
        long connection = connection(project(), "ACTIVE");
        jdbc.update("""
                insert into jobs(id,kind,target_id,dedup_key,status,available_at,deadline_at)
                select gen_random_uuid(),'STRUCTURE',g,'saturation-'||g,'PENDING',now()+interval '1 day',now()+interval '2 days'
                from generate_series(1,50000) g
                """);

        scheduler.enqueueDue();

        assertEquals(0, unfinishedIngest(connection));
    }

    /**
     * 예전에는 끝나지 않은 작업 확인이 행 잠금과 같은 문장에 있어서, 잠금을 기다린 실행이 기다리기 전에 본 것으로
     * 판단했고 첫 실행이 작업을 커밋한 뒤 두 번째 작업을 넣었다. 이제 확인은 잠금 뒤의 따로 된 문장이다.
     */
    @Test
    void aRunThatWaitedForTheConnectionLockSeesTheJobTheOtherRunQueued() throws Exception {
        long active = connection(project(), "ACTIVE");
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection otherRun = dataSource.getConnection()) {
            otherRun.setAutoCommit(false);
            otherRun.createStatement().execute("select id from source_connections where id = " + active + " for update");
            java.util.concurrent.Future<?> waiting = executor.submit(scheduler::enqueueDue);
            com.projectbaton.LockWait.untilASessionWaits(jdbc);
            org.junit.jupiter.api.Assertions.assertFalse(waiting.isDone(), "the second run waits for the connection row");

            jobRepository.enqueue("INGEST", active, null, "ingest:" + active + ":other-run", java.time.OffsetDateTime.now().plusMinutes(30));
            otherRun.commit();
            waiting.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, unfinishedIngest(active));
    }

    private int unfinishedIngest(long connection) {
        return jdbc.queryForObject("select count(*) from jobs where kind='INGEST' and target_id=? and status in ('PENDING','RUNNING','RETRY_WAIT')",
                Integer.class, connection);
    }
    private String checkpoint(long connection) { return jdbc.queryForObject("select checkpoint from source_connections where id=?", String.class, connection); }
    /**
     * README §6은 5분마다 활성 연동마다 INGEST 작업 하나를 넣는다. 연동 1,200개면 초당 작업 넷이라서, dispatch 한
     * 번이 tick마다 작업 하나만 가져가지 않고 처리할 작업이 있는 동안 계속 수집해야 한다.
     */
    @Test
    void oneDispatchCollectsEveryDueConnectionInsteadOfOneJobPerTick() throws Exception {
        long project = project();
        for (int i = 0; i < 20; i++) connection(project, "ACTIVE");
        scheduler.enqueueDue();
        assertEquals(20, jdbc.queryForObject("select count(*) from jobs where kind='INGEST' and status='PENDING'", Integer.class));

        ingestionSlots.fill();

        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (jdbc.queryForObject("select count(*) from jobs where kind='INGEST' and status<>'SUCCEEDED'", Integer.class) > 0) {
            if (System.nanoTime() > deadline) throw new AssertionError("INGEST jobs left after one dispatch");
            Thread.sleep(50);
        }
        // runner는 slot을 돌려주기 전에 마지막 작업 뒤에 한 번 더 가져간다. 시험을 더 일찍 끝내면 그 가져가기가 다음
        // 시험의 truncate와 겹쳐 교착됐다.
        while (ingestionSlots.availableSlots() < 1) {
            if (System.nanoTime() > deadline) throw new AssertionError("ingestion slot still busy after the queue emptied");
            Thread.sleep(10);
        }
    }

    private long project() {
        return jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values (?,'Ingestion Worker Project','Synthetic Delivery','Synthetic Client','2026-01-01','2026-12-31','Build','ON_TRACK') returning id
                """, Long.class, "PRJ-WORKER-" + System.nanoTime());
    }
    private long connection(long projectId, String status) {
        return jdbc.queryForObject("""
                insert into source_connections(project_id,channel,display_name,external_ref,status,health,connector_kind)
                values (?,'EMAIL','Fixture source','fixture://project-baton',cast(? as connection_status),'NORMAL','FIXTURE')
                returning id
                """, Long.class, projectId, status);
    }
}
