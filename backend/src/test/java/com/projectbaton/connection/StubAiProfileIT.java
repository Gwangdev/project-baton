package com.projectbaton.connection;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.jobs.EmbeddingWorker;
import com.projectbaton.qa.StubEmbeddingClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 기본 {@code stub} 프로필을 끝까지 돌린다: fixture 수집, 재생한 구조화, 검토, 오프라인 임베딩. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StubAiProfileIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IngestionService ingestion;
    @Autowired SourceStructuringWorker structuring;
    @Autowired EmbeddingWorker embeddings;

    long projectId;
    long connectionId;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate table organizations, projects, jobs restart identity cascade");
        jdbc.update("insert into organizations(name) values('Stub Profile Organization')");
        long owner = jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),'stub-owner','stub-owner','Engineer','stub@test',?) returning id",
                Long.class, PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("synthetic-password"));
        projectId = jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values('PRJ-STUB','Stub','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id", Long.class);
        jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,'OUTGOING_OWNER',date '2026-09-01')", projectId, owner);
        connectionId = jdbc.queryForObject("""
                insert into source_connections(project_id,channel,display_name,external_ref,status,health,connector_kind)
                values (?,'EMAIL','Fixture source','fixture://project-baton','ACTIVE','NORMAL','FIXTURE') returning id
                """, Long.class, projectId);
    }

    @Test
    void fixtureDocumentsAreStructuredFromReplayedOutputsAndOtherDocumentsYieldNothing() {
        ingestion.ingest(connectionId, 100);
        long unknown = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,'unrecorded',now(),?,'unrecorded','v1',repeat('e',64),'A document the stub has no recorded output for.') returning id
                """, Long.class, connectionId, projectId);
        jdbc.update("insert into jobs(id,kind,target_id,dedup_key,status,available_at,deadline_at) values(gen_random_uuid(),'STRUCTURE',?,'structure-unrecorded','PENDING',now(),now()+interval '30 minutes')", unknown);

        for (int i = 0; i < 3; i++) structuring.runOnce();

        assertEquals(3, jdbc.queryForObject("select count(*) from record_sources where processing_status='SUCCEEDED'", Integer.class));
        assertEquals(List.of("OPEN_ISSUE:Open risk", "SCHEDULE:Handover brief"), jdbc.queryForList(
                "select record_type::text || ':' || title from work_records where trust_status='AUTO_DRAFT' order by 1", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from work_records where record_source_id=?", Integer.class, unknown));
    }

    @Test
    void confirmingADraftIndexesItWithTheStubModelUnlessTheQueueIsFull() throws Exception {
        ingestion.ingest(connectionId, 100);
        for (int i = 0; i < 2; i++) structuring.runOnce();
        List<Long> drafts = jdbc.queryForList("select id from work_records order by id", Long.class);
        String session = mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"stub-owner\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();

        mockMvc.perform(review(drafts.get(0), session, "96000000-0000-0000-0000-000000000001")).andExpect(status().isOk());
        embeddings.runOnce();
        assertEquals(StubEmbeddingClient.MODEL_ID, jdbc.queryForObject("select embedding_model from record_embeddings where record_id=?", String.class, drafts.get(0)));

        jdbc.update("""
                insert into jobs(id,kind,target_id,dedup_key,status,available_at,deadline_at)
                select gen_random_uuid(),'STRUCTURE',g,'saturation-'||g,'PENDING',now()+interval '1 day',now()+interval '2 days'
                from generate_series(1,50000) g
                """);
        mockMvc.perform(review(drafts.get(1), session, "96000000-0000-0000-0000-000000000002"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code", is("QUEUE_FULL")));
        assertEquals("AUTO_DRAFT", jdbc.queryForObject("select trust_status::text from work_records where id=?", String.class, drafts.get(1)));
    }

    private MockHttpServletRequestBuilder review(long recordId, String session, String key) {
        return post("/api/v1/records/{recordId}/review", recordId).with(csrf()).cookie(new jakarta.servlet.http.Cookie("BATON_SESSION", session))
                .header("Idempotency-Key", key).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CONFIRM\"}");
    }
}
