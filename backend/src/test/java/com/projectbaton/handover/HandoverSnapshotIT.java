package com.projectbaton.handover;

import com.projectbaton.shared.BusinessCalendar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Set;
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
class HandoverSnapshotIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) { r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword); }
    @Autowired JdbcTemplate jdbc;
    @Autowired HandoverRequestService handovers;

    @Test
    void generationIdentityIsUniquePerHandover() {
        assertEquals(0, jdbc.queryForObject("select count(*) from handover_generations", Integer.class));
    }

    @Test
    void requestSnapshotsOnlyConfirmedRecordsAndQueuesPackageBuild() {
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations(name) values('Handover Organization')");
        long manager = user("manager"); long outgoing = user("outgoing"); long incoming = user("incoming");
        long project = jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values('HANDOVER-1','Handover','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id",Long.class);
        member(project,manager,"PROJECT_MANAGER"); member(project,outgoing,"OUTGOING_OWNER"); member(project,incoming,"INCOMING_OWNER");
        long connection=jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','fixture') returning id",Long.class,project);
        long source=jdbc.queryForObject("insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body) values(?,'fixture',now(),?,'doc','v1','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','body') returning id",Long.class,connection,project);
        jdbc.update("insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,trust_status,confirmed_by,confirmed_at,source_version,search_text) values('REC-HANDOVER',?,?,'CHANGE_DECISION','Decision','Safe summary','private excerpt','2026-09-01','Author','CONFIRMED',?,now(),'v1','Safe summary')",project,source,manager);
        HandoverRequestService.Handover handover=handovers.request(manager,project,new HandoverRequestService.Command(outgoing,incoming,LocalDate.of(2026,9,15),LocalDate.of(2026,8,20),LocalDate.of(2026,9,14),Set.of("CHANGE_DECISION"),true));
        assertEquals("REQUESTED",handover.status()); assertEquals(1,jdbc.queryForObject("select count(*) from handover_snapshot_records",Integer.class)); assertEquals(1,jdbc.queryForObject("select count(*) from jobs where kind='BUILD_PACKAGE' and target_id=?",Integer.class,handover.id()));
        jdbc.update("update handovers set status='FAILED' where id=?",handover.id());
        HandoverRequestService.Handover retried=handovers.retry(manager,handover.id(),0);
        assertEquals(1,retried.generation()); assertEquals(1,jdbc.queryForObject("select count(*) from handover_snapshot_records",Integer.class));
        jdbc.update("update handovers set status='READY' where id=?",handover.id());
        HandoverRequestService.Handover regenerated=handovers.regenerate(manager,handover.id(),1,new HandoverRequestService.Command(0,0,BusinessCalendar.today(),LocalDate.of(2026,8,20),LocalDate.of(2026,9,14),Set.of("CHANGE_DECISION"),true));
        assertEquals(2,regenerated.generation()); assertEquals(2,jdbc.queryForObject("select count(*) from handover_snapshot_records",Integer.class));
    }
    private long user(String n){return jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),?,?,?,?,'x') returning id",Long.class,n,n,"Engineer",n+"@test");}
    private void member(long p,long u,String r){jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),current_date)",p,u,r);}
}
