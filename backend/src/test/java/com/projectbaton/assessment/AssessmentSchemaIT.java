package com.projectbaton.assessment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AssessmentSchemaIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations(name) values('Assessment Schema Organization')");
    }

    @Test
    void assessmentQuestionIsOwnedByItsHandoverGeneration() {
        Boolean exists = jdbc.queryForObject("""
                select exists(
                    select 1 from information_schema.columns
                    where table_schema = 'public'
                      and table_name = 'assessment_questions'
                      and column_name = 'generation_id'
                )
                """, Boolean.class);

        assertTrue(exists);
    }

    @Test
    void questionCannotLinkToARecordOutsideItsGenerationSnapshot() {
        long manager = user("schema-manager");
        long outgoing = user("schema-outgoing");
        long incoming = user("schema-incoming");
        long project = project("ASSESSMENT-SCHEMA-A");
        long otherProject = project("ASSESSMENT-SCHEMA-B");
        long handover = handover(project, manager, outgoing, incoming);
        long generation = jdbc.queryForObject("""
                insert into handover_generations(handover_id,generation,project_id,created_by)
                values(?,1,?,?) returning id
                """, Long.class, handover, project, manager);
        long assessment = jdbc.queryForObject("""
                insert into assessments(handover_id,generation_id,generation)
                values(?,?,1) returning id
                """, Long.class, handover, generation);
        long foreignRecord = record(otherProject, manager, "REC-FOREIGN");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into assessment_questions(assessment_id,generation_id,question_no,question_type,prompt,choices,correct_choice_id,linked_record_id)
                values(?,?,1,'MULTIPLE_CHOICE','Which option is confirmed?',cast(? as jsonb),'a',?)
                """, assessment, generation, "[{\"id\":\"a\",\"text\":\"Confirmed\"},{\"id\":\"b\",\"text\":\"Not confirmed\"}]", foreignRecord));
    }

    private long user(String employeeNo) {
        return jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,credential_hash)
                values((select id from organizations),?,?,?,?,'x') returning id
                """, Long.class, employeeNo, employeeNo, "Engineer", employeeNo + "@test");
    }

    private long project(String code) {
        return jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values(?,?,'Synthetic Delivery','Synthetic Client','2026-01-01','2026-12-31','Build','ON_TRACK') returning id
                """, Long.class, code, code);
    }

    private long handover(long projectId, long managerId, long outgoingId, long incomingId) {
        return jdbc.queryForObject("""
                insert into handovers(code,project_id,requested_by,outgoing_owner_id,incoming_owner_id,effective_on,scope_from,scope_to)
                values('HO-SCHEMA',?,?,?,?,?,?,?) returning id
                """, Long.class, projectId, managerId, outgoingId, incomingId,
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14));
    }

    private long record(long projectId, long authorId, String code) {
        long connection = jdbc.queryForObject("""
                insert into source_connections(project_id,channel,display_name)
                values(?,'EMAIL','schema fixture') returning id
                """, Long.class, projectId);
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,'schema fixture',now(),?,'schema-document','v1','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','synthetic') returning id
                """, Long.class, connection, projectId);
        return jdbc.queryForObject("""
                insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,trust_status,confirmed_by,confirmed_at,source_version,search_text)
                values(?,?,?,'CHANGE_DECISION','Schema record','Synthetic summary','synthetic private excerpt','2026-09-01','Synthetic author','CONFIRMED',?,now(),'v1','Synthetic summary') returning id
                """, Long.class, code, projectId, source, authorId);
    }
}
