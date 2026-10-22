package com.projectbaton.shared;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ErrorContractIT {
    private static final PasswordEncoder PASSWORDS = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private static final String REQUEST_KEY = "95000000-0000-0000-0000-000000000001";

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private long projectId;
    private long outgoingId;
    private long incomingId;

    @BeforeEach
    void reset() {
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations(name) values('Error Contract Organization')");
        long manager = user("error-manager");
        outgoingId = user("error-outgoing");
        incomingId = user("error-incoming");
        projectId = jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values(?,'Error Project','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id",
                Long.class, "ERROR-" + System.nanoTime());
        member(manager, "PROJECT_MANAGER");
        member(outgoingId, "OUTGOING_OWNER");
        member(incomingId, "INCOMING_OWNER");
    }

    @Test
    void saturatedQueueReturns503WithRetryAfterAndDoesNotConsumeTheKey() throws Exception {
        jdbc.update("""
                insert into jobs(id,kind,target_id,dedup_key,status,available_at,deadline_at)
                select gen_random_uuid(),'INGEST',g,'saturation-'||g,'PENDING',now()+interval '1 day',now()+interval '2 days'
                from generate_series(1,50000) g
                """);
        String session = signIn("error-manager");

        mockMvc.perform(requestHandover(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", is(503)))
                .andExpect(jsonPath("$.code", is("QUEUE_FULL")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertEquals(0, jdbc.queryForObject("select count(*) from handovers", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from idempotency_requests", Integer.class));

        jdbc.update("delete from jobs where dedup_key like 'saturation-%'");
        mockMvc.perform(requestHandover(session)).andExpect(status().isAccepted());
    }

    @Test
    void unexpectedFailureReturnsAGenericProblemWithoutInternalDetail() throws Exception {
        String session = signIn("error-manager");
        jdbc.execute("alter table handovers rename to handovers_offline");
        try {
            mockMvc.perform(get("/api/v1/projects/{projectId}/handovers", projectId).cookie(cookie(session)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status", is(500)))
                    .andExpect(jsonPath("$.code", is("INTERNAL_ERROR")))
                    .andExpect(jsonPath("$.detail", is("The request could not be completed.")))
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.instance", is("/api/v1/projects/" + projectId + "/handovers")))
                    .andExpect(content().string(not(containsString("does not exist"))))
                    .andExpect(content().string(not(containsString("relation"))))
                    .andExpect(content().string(not(containsString("select"))))
                    .andExpect(content().string(not(containsString("SQL"))))
                    .andExpect(content().string(not(containsString("Exception"))));
        } finally {
            jdbc.execute("alter table handovers_offline rename to handovers");
        }
    }

    /** 요청이 도는 동안 쓴 로그 줄은 요청의 trace id를 담고, 그 id는 오류 응답과 감사 행이 담는 id와 같다. */
    @Test
    void aLogLineWrittenDuringARequestCarriesItsTraceId(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String session = signIn("error-manager");
        jdbc.execute("alter table handovers rename to handovers_offline");
        try {
            org.springframework.test.web.servlet.MvcResult failed = mockMvc.perform(get("/api/v1/projects/{projectId}/handovers", projectId).cookie(cookie(session)))
                    .andExpect(status().isInternalServerError()).andReturn();

            String trace = (String) failed.getRequest().getAttribute("baton.traceId");
            assertEquals(true, output.getAll().contains("[" + trace + "]"), "no log line is tagged with " + trace);
        } finally {
            jdbc.execute("alter table handovers_offline rename to handovers");
        }
    }

    @Test
    void frameworkRejectionsKeepTheirStatusAsProblems() throws Exception {
        String session = signIn("error-manager");

        mockMvc.perform(put("/api/v1/projects/{projectId}/handovers", projectId).with(csrf()).cookie(cookie(session)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code", is("METHOD_NOT_ALLOWED")));
        mockMvc.perform(post("/api/v1/projects/{projectId}/handovers", projectId).with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", REQUEST_KEY).contentType(MediaType.TEXT_PLAIN).content("plain"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code", is("UNSUPPORTED_MEDIA_TYPE")));
        mockMvc.perform(get("/api/v1/no-such-resource").cookie(cookie(session)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("NOT_FOUND")));
    }

    private MockHttpServletRequestBuilder requestHandover(String session) {
        return post("/api/v1/projects/{projectId}/handovers", projectId).with(csrf()).cookie(cookie(session))
                .header("Idempotency-Key", REQUEST_KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outgoingOwnerId\":" + outgoingId + ",\"incomingOwnerId\":" + incomingId
                        + ",\"effectiveOn\":\"2026-09-14\",\"scopeFrom\":\"2026-08-20\",\"scopeTo\":\"2026-09-14\","
                        + "\"scopeRecordTypes\":[\"COMMITMENT\"],\"includeNoteDerivedRecords\":true}");
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }
    private long user(String employeeNo) { return jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),?,?,?, ?,?) returning id", Long.class, employeeNo, employeeNo, "Engineer", employeeNo + "@test", PASSWORDS.encode("synthetic-password")); }
    private void member(long user, String role) { jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),date '2026-09-01')", projectId, user, role); }
    private jakarta.servlet.http.Cookie cookie(String value) { return new jakarta.servlet.http.Cookie("BATON_SESSION", value); }
}
