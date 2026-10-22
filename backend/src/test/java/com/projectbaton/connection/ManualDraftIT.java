package com.projectbaton.connection;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.shared.AuditRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class ManualDraftIT {
    private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);}
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
    @BeforeEach void setup(){AuditRows.clear(jdbc);jdbc.update("delete from jobs");jdbc.execute("truncate table organizations restart identity cascade");jdbc.update("insert into organizations(name) values('Manual Draft Organization')");}
    @Test void failedSourceManualDraftStaysAutoDraftAndCanBeReviewed() throws Exception {
        long user=user(); long project=project(); member(project,user); String session=signIn();
        long connection=jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','public') returning id",Long.class,project);
        long source=jdbc.queryForObject("insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body,processing_status) values(?,'failed',now(),?,'d','v1','cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','body','FAILED') returning id",Long.class,connection,project);
        mvc.perform(post("/api/v1/connections/{connectionId}/files/{sourceId}/records",connection,source).with(csrf()).cookie(cookie(session)).header("Idempotency-Key","74000000-0000-0000-0000-000000000001").contentType(MediaType.APPLICATION_JSON).content("{\"recordType\":\"OPEN_ISSUE\",\"title\":\"Manual\",\"summary\":\"summary\",\"originalExcerpt\":\"excerpt\",\"occurredOn\":\"2026-04-01\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.trustStatus",is("AUTO_DRAFT"))).andExpect(jsonPath("$.confirmedByName").doesNotExist());
        long record=jdbc.queryForObject("select id from work_records where record_source_id=?",Long.class,source);
        mvc.perform(post("/api/v1/records/{recordId}/review",record).with(csrf()).cookie(cookie(session)).header("Idempotency-Key","74000000-0000-0000-0000-000000000002").header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CONFIRM\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trustStatus",is("CONFIRMED")));
    }
    @Test void anOwnerWhoIsNotAMemberOfTheProjectIsABadRequestAndASourceThatCannotBeSeenIsNotFound() throws Exception {
        long user=user(); long project=project(); member(project,user); String session=signIn();
        long stranger=jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),'manual-stranger','Stranger','Engineer','stranger@example.test',?) returning id",Long.class,ENCODER.encode("synthetic-password"));
        long connection=jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','public') returning id",Long.class,project);
        long source=jdbc.queryForObject("insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body,processing_status) values(?,'failed',now(),?,'d','v1','cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','body','FAILED') returning id",Long.class,connection,project);
        mvc.perform(post("/api/v1/connections/{connectionId}/files/{sourceId}/records",connection,source).with(csrf()).cookie(cookie(session)).header("Idempotency-Key","74000000-0000-0000-0000-000000000003").contentType(MediaType.APPLICATION_JSON).content("{\"recordType\":\"OPEN_ISSUE\",\"title\":\"Manual\",\"summary\":\"summary\",\"originalExcerpt\":\"excerpt\",\"occurredOn\":\"2026-04-01\",\"ownerUserId\":"+stranger+"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code",is("INVALID_INPUT"))).andExpect(jsonPath("$.errors[0].field",is("ownerUserId")));
        mvc.perform(post("/api/v1/connections/{connectionId}/files/{sourceId}/records",connection,source).with(csrf()).cookie(cookie(session)).header("Idempotency-Key","74000000-0000-0000-0000-000000000004").contentType(MediaType.APPLICATION_JSON).content("{\"recordType\":\"OPEN_ISSUE\",\"title\":\"Manual\",\"summary\":\"summary\",\"originalExcerpt\":\"excerpt\",\"occurredOn\":\"2026-04-01\",\"ownerUserId\":99999999999999999999}"))
                .andExpect(status().isBadRequest());
        String strangerSession=mvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"employeeNo\":\"manual-stranger\",\"password\":\"synthetic-password\"}")).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
        mvc.perform(post("/api/v1/connections/{connectionId}/files/{sourceId}/records",connection,source).with(csrf()).cookie(cookie(strangerSession)).header("Idempotency-Key","74000000-0000-0000-0000-000000000005").contentType(MediaType.APPLICATION_JSON).content("{\"recordType\":\"OPEN_ISSUE\",\"title\":\"Manual\",\"summary\":\"summary\",\"originalExcerpt\":\"excerpt\",\"occurredOn\":\"2026-04-01\"}"))
                .andExpect(status().isNotFound());
        assertEquals(0,jdbc.queryForObject("select count(*) from work_records",Integer.class));
    }
    private long user(){return jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),'manual-user','Manual','Engineer','manual@example.test',?) returning id",Long.class,ENCODER.encode("synthetic-password"));}
    private long project(){return jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values(?, 'Manual','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id",Long.class,"MANUAL-"+System.nanoTime());}
    private void member(long p,long u){jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,'PROJECT_MANAGER',current_date)",p,u);}
    private String signIn() throws Exception{return mvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"employeeNo\":\"manual-user\",\"password\":\"synthetic-password\"}")).andReturn().getResponse().getCookie("BATON_SESSION").getValue();}
    private jakarta.servlet.http.Cookie cookie(String value){return new jakarta.servlet.http.Cookie("BATON_SESSION",value);}
}
