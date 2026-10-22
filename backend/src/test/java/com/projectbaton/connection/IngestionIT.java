package com.projectbaton.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.projectbaton.jobs.JobQueueSaturatedException;

@SpringBootTest
@Testcontainers
class IngestionIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired IngestionService ingestion;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate table organizations, projects, jobs restart identity cascade");
        jdbc.update("insert into organizations(name) values ('Ingestion Test Organization')");
    }

    @Test
    void fixtureBatchPersistsSourcesJobsAndCheckpointTogether() {
        long projectId = project();
        long connectionId = connection(projectId, "ACTIVE");

        IngestionService.IngestionResult result = ingestion.ingest(connectionId, 100);

        assertEquals(2, result.insertedSources());
        assertEquals("2", jdbc.queryForObject("select checkpoint from source_connections where id=?", String.class, connectionId));
        assertEquals(2, jdbc.queryForObject("select count(*) from record_sources where source_connection_id=?", Integer.class, connectionId));
        assertEquals("2026-01-10", jdbc.queryForObject("""
                select source_occurred_on::text from record_sources
                where source_connection_id=? and external_document_id='fixture-brief'
                """, String.class, connectionId));
        assertEquals(2, jdbc.queryForObject("select count(*) from jobs where kind='STRUCTURE'", Integer.class));
    }

    @Test
    void repeatedFixtureVersionIsIgnoredWithoutCreatingAnotherJob() {
        long projectId = project();
        long connectionId = connection(projectId, "ACTIVE");
        ingestion.ingest(connectionId, 100);
        jdbc.update("update source_connections set checkpoint=null where id=?", connectionId);

        IngestionService.IngestionResult result = ingestion.ingest(connectionId, 100);

        assertEquals(0, result.insertedSources());
        assertEquals("2", jdbc.queryForObject("select checkpoint from source_connections where id=?", String.class, connectionId));
        assertEquals(2, jdbc.queryForObject("select count(*) from record_sources where source_connection_id=?", Integer.class, connectionId));
        assertEquals(2, jdbc.queryForObject("select count(*) from jobs where kind='STRUCTURE'", Integer.class));
    }

    @Test
    void sameVersionWithDifferentHashFailsWithoutAdvancingCheckpoint() {
        long projectId = project();
        long connectionId = connection(projectId, "ACTIVE");
        ingestion.ingest(connectionId, 100);
        jdbc.update("update record_sources set content_hash=? where source_connection_id=? and external_document_id='fixture-brief'",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", connectionId);
        jdbc.update("update source_connections set checkpoint=null where id=?", connectionId);

        assertThrows(IngestionService.SourceVersionConflictException.class, () -> ingestion.ingest(connectionId, 100));

        assertNull(jdbc.queryForObject("select checkpoint from source_connections where id=?", String.class, connectionId));
        assertEquals(2, jdbc.queryForObject("select count(*) from record_sources where source_connection_id=?", Integer.class, connectionId));
        assertEquals(2, jdbc.queryForObject("select count(*) from jobs where kind='STRUCTURE'", Integer.class));
    }

    @Test
    void pausedConnectionDoesNotCollectFixtureDocuments() {
        long projectId = project();
        long connectionId = connection(projectId, "PAUSED");

        assertThrows(IngestionService.InactiveSourceConnectionException.class, () -> ingestion.ingest(connectionId, 100));

        assertNull(jdbc.queryForObject("select checkpoint from source_connections where id=?", String.class, connectionId));
        assertEquals(0, jdbc.queryForObject("select count(*) from record_sources where source_connection_id=?", Integer.class, connectionId));
        assertEquals(0, jdbc.queryForObject("select count(*) from jobs", Integer.class));
    }

    @Test
    void fullQueueRollsBackSourcesAndDoesNotAdvanceCheckpoint() {
        long projectId = project();
        long connectionId = connection(projectId, "ACTIVE");
        jdbc.update("""
                insert into jobs(id,kind,target_id,dedup_key,status,attempts,available_at,deadline_at)
                select ('00000000-0000-0000-0000-' || lpad(job_no::text,12,'0'))::uuid,
                       'STRUCTURE',job_no,'saturated-source-job-' || job_no,'PENDING',0,now(),now()+interval '5 minutes'
                from generate_series(1,50000) as job_no
                """);

        assertThrows(JobQueueSaturatedException.class, () -> ingestion.ingest(connectionId, 100));

        assertNull(jdbc.queryForObject("select checkpoint from source_connections where id=?", String.class, connectionId));
        assertEquals(0, jdbc.queryForObject("select count(*) from record_sources where source_connection_id=?", Integer.class, connectionId));
    }

    private long project() {
        return jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values ('PRJ-INGEST','Ingestion Project','Synthetic Delivery','Synthetic Client',
                        '2026-01-01','2026-12-31','Build','ON_TRACK') returning id
                """, Long.class);
    }

    private long connection(long projectId, String status) {
        return jdbc.queryForObject("""
                insert into source_connections(project_id,channel,display_name,external_ref,status,health,connector_kind)
                values (?,'EMAIL','Fixture source','fixture://project-baton',cast(? as connection_status),'NORMAL','FIXTURE')
                returning id
                """, Long.class, projectId, status);
    }
}
