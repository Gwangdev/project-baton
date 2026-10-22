package com.projectbaton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class SchemaIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void startsWithAnEmptyDatabaseAndRerunsMigrationsWithoutLosingData() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from organizations", Long.class)).isZero();

        jdbcTemplate.update("insert into organizations (name) values (?)", "Migration Preserve Org");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbcTemplate.queryForObject("select count(*) from organizations", Long.class)).isEqualTo(1L);
    }

    @Test
    void indexesTheNoteReferenceOfRecordsThatTheNoteListJoinsOn() {
        // ProjectBaton-DB.dbml이 선언한다. 없으면 메모 목록마다 모든 기록을 훑는다(evidence/performance.md, 첫 측정).
        assertThat(jdbcTemplate.queryForList("""
                select indexdef from pg_indexes where tablename = 'work_records' and indexdef like '%(source_note_id)%'
                """, String.class)).hasSize(1);
    }

    @Test
    void ordersTheTimelineAndTheLatestJobPerTargetFromIndexes() {
        // 필터 없는 타임라인은 요청마다 프로젝트의 기록 전체를 정렬했고, 버려진 원문 sweep과 수집 일정은 모든 작업을
        // 훑었다. 이제 두 순서 모두 인덱스에서 나온다(evidence/performance.md).
        assertThat(jdbcTemplate.queryForList("select indexdef from pg_indexes where tablename = 'work_records'", String.class))
                .anyMatch(definition -> definition.endsWith("(project_id, occurred_on DESC, id DESC)"));
        assertThat(jdbcTemplate.queryForList("select indexdef from pg_indexes where tablename = 'jobs'", String.class))
                .anyMatch(definition -> definition.endsWith("(kind, target_id, created_at DESC, id DESC)"));
    }

    @Test
    void countsVisibleRecordsFromTheStatusIndexAlone() {
        // 타임라인 합계와 확인 대기 수는 행위자가 검토할 수 있는 초안을 가리려고 프로젝트의 모든 기록에서
        // record_source_id를 읽는다. 상태 인덱스에 그 열이 들어가면 테이블을 들르지 않는다(evidence/performance.md,
        // 60 RPS의 문장 비용).
        assertThat(jdbcTemplate.queryForList("select indexdef from pg_indexes where tablename = 'work_records'", String.class))
                .anyMatch(definition -> definition.endsWith(
                        "(project_id, trust_status, occurred_on DESC, id DESC) INCLUDE (record_source_id)"))
                .noneMatch(definition -> definition.endsWith("(project_id, trust_status, occurred_on DESC, id DESC)"));
    }

    @Test
    void findsExpiredRetentionRowsThroughAnIndex() {
        // RetentionSchedule은 5,000건씩 묶어 지우는데, 이 인덱스가 없으면 묶음마다 끝난 작업이나 저장된 멱등 결과를
        // 모두 다시 읽었다. 시험 테이블의 행이 적어도 계획이 인덱스로 문장을 처리할 수 있는지 보이도록 순차 스캔을
        // 끈다. 두 select는 JobRepository.deleteFinished와 IdempotencyService.deleteExpired의 조건을 그대로 따른다.
        String jobs = jdbcTemplate.execute((java.sql.Connection connection) -> plan(connection, """
                select id from jobs
                where (status='SUCCEEDED' and updated_at < now() - interval '7 days')
                   or (status='FAILED' and updated_at < now() - interval '30 days')
                limit 5000
                """));
        String idempotency = jdbcTemplate.execute((java.sql.Connection connection) -> plan(connection, """
                select id from idempotency_requests where state = 'COMPLETED' and expires_at < now() limit 5000
                """));
        assertThat(jobs).contains("jobs_finished_updated_idx");
        assertThat(idempotency).contains("idempotency_requests_completed_expires_idx");
    }

    private static String plan(java.sql.Connection connection, String query) throws java.sql.SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("set local enable_seqscan = off");
            StringBuilder plan = new StringBuilder();
            try (var rows = statement.executeQuery("explain " + query)) {
                while (rows.next()) plan.append(rows.getString(1)).append('\n');
            }
            return plan.toString();
        } finally {
            connection.rollback();
            connection.setAutoCommit(autoCommit);
        }
    }

    @Test
    void rejectsWorkRecordWithoutExactlyOneSource() {
        long projectId = project("P-XOR");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into work_records
                    (code, project_id, record_type, title, summary, original_excerpt, occurred_on,
                     author_name, trust_status, search_text)
                values (?, ?, 'SCHEDULE', 'title', 'summary', 'excerpt', current_date,
                        'author', 'AUTO_DRAFT', 'summary')
                """, "REC-XOR", projectId))
                .hasMessageContaining("work_records_source_xor");
    }

    @Test
    void rejectsProjectWithEndDateBeforeStartDate() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into projects
                    (code, name, delivering_company, client_company, started_on, ends_on, phase, status)
                values ('P-DATE', 'invalid date project', 'A', 'B', date '2026-09-15', date '2026-09-14',
                        'build', 'PLANNED')
                """))
                .hasMessageContaining("projects_date_order");
    }

    @Test
    void rejectsRecordRelationWhoseRecordsBelongToAnotherProject() {
        long firstProject = project("P-REL-A");
        long secondProject = project("P-REL-B");
        long firstRecord = sourcedRecord(firstProject, "REC-REL-A");
        long secondRecord = sourcedRecord(secondProject, "REC-REL-B");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into record_relations (project_id, from_record_id, to_record_id, relation_type)
                values (?, ?, ?, 'REFERENCES')
                """, firstProject, firstRecord, secondRecord))
                .hasMessageContaining("record_relations_project_to_record_fkey");
    }

    private long project(String code) {
        return jdbcTemplate.queryForObject("""
                insert into projects
                    (code, name, delivering_company, client_company, started_on, ends_on, phase, status)
                values (?, ?, 'A', 'B', date '2026-09-01', date '2026-09-30', 'build', 'ON_TRACK')
                returning id
                """, Long.class, code, code);
    }

    private long sourcedRecord(long projectId, String code) {
        long connectionId = jdbcTemplate.queryForObject("""
                insert into source_connections (project_id, channel, display_name)
                values (?, 'MEETING_MINUTES', ?)
                returning id
                """, Long.class, projectId, code + " source");
        long sourceId = jdbcTemplate.queryForObject("""
                insert into record_sources
                    (source_connection_id, display_name, collected_at, project_id, external_document_id,
                     external_version, content_hash, source_body)
                values (?, ?, now(), ?, ?, 'v1', repeat('a', 64), 'synthetic source')
                returning id
                """, Long.class, connectionId, code + " source", projectId, code + " external");
        return jdbcTemplate.queryForObject("""
                insert into work_records
                    (code, project_id, record_source_id, record_type, title, summary, original_excerpt,
                     occurred_on, author_name, trust_status, search_text)
                values (?, ?, ?, 'SCHEDULE', ?, 'summary', 'excerpt', current_date, 'author',
                        'AUTO_DRAFT', 'summary')
                returning id
                """, Long.class, code, projectId, sourceId, code);
    }
}
