package com.projectbaton.handover;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.assessment.AssessmentService;
import com.projectbaton.shared.AuditRows;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class HandoverTransitionIT {
    private static final java.util.concurrent.atomic.AtomicInteger EXTRACTION_INDEX = new java.util.concurrent.atomic.AtomicInteger();
    private static final PasswordEncoder PASSWORDS = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private static final String APPROVE = "{\"decision\":\"APPROVE\"}";
    private static final String REVISE = "{\"decision\":\"REQUEST_REVISION\",\"comment\":\"Explain the blocker owner.\"}";

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired HandoverRequestService handovers;
    @Autowired HandoverAcknowledgementService acknowledgements;
    @Autowired AssessmentService assessments;
    @Autowired HandoverPackageWorker worker;

    @BeforeEach
    void reset() {
        jdbc.update("delete from jobs");
        AuditRows.clear(jdbc);
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations(name) values('Transition Organization')");
    }

    @Test
    void revisionResubmissionReadyApprovalCompletesTheHandover() throws Exception {
        Fixture fixture = fixture();
        acknowledgeAll(fixture);
        submitDirect(fixture, 5);
        String manager = signIn("transition-manager");
        String incoming = signIn("transition-incoming");
        long ready = version(fixture);
        int acknowledged = jdbc.queryForObject("select count(*) from handover_item_acknowledgements", Integer.class);

        mockMvc.perform(decide(fixture, manager, ready, REVISE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REVIEW_REQUESTED")));
        long revised = version(fixture);
        assertEquals(ready + 1, revised);
        mockMvc.perform(get("/api/v1/handovers/{handoverId}", fixture.handoverId()).cookie(cookie(incoming)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REVIEW_REQUESTED")))
                .andExpect(jsonPath("$.approvalBlockers", hasItem("HANDOVER_NOT_READY")));
        mockMvc.perform(decide(fixture, manager, revised, APPROVE)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("HANDOVER_NOT_READY")));

        mockMvc.perform(submitHttp(fixture, incoming, answers(fixture, 5))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.score", is(5)));
        assertEquals("READY", handoverStatus(fixture));
        assertEquals(revised + 1, version(fixture));
        assertEquals(acknowledged, jdbc.queryForObject("select count(*) from handover_item_acknowledgements", Integer.class));

        mockMvc.perform(decide(fixture, manager, revised, APPROVE)).andExpect(status().isPreconditionFailed());
        mockMvc.perform(decide(fixture, manager, revised + 1, APPROVE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.approvalBlockers.length()", is(0)));

        assertEquals(1, audit("REQUEST_HANDOVER_REVISION"));
        assertEquals(1, audit("APPROVE_HANDOVER"));
        assertEquals(2, audit("TRANSITION_PROJECT_MEMBER"));
        assertEquals("OUTGOING_OWNER", jdbc.queryForObject("select project_role::text from project_members where project_id=? and user_id=?",
                String.class, fixture.projectId(), fixture.incomingId()));
        mockMvc.perform(get("/api/v1/handovers/{handoverId}", fixture.handoverId()).cookie(cookie(incoming)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("COMPLETED")));
        mockMvc.perform(get("/api/v1/handovers/{handoverId}", fixture.handoverId()).cookie(cookie(signIn("transition-outgoing"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void failingResubmissionReturnsToReadyButApprovalJudgesTheLatestAttempt() throws Exception {
        Fixture fixture = fixture();
        acknowledgeAll(fixture);
        submitDirect(fixture, 5);
        String manager = signIn("transition-manager");
        String incoming = signIn("transition-incoming");
        mockMvc.perform(decide(fixture, manager, version(fixture), REVISE)).andExpect(status().isOk());

        mockMvc.perform(submitHttp(fixture, incoming, answers(fixture, 3))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.score", is(3)));
        assertEquals("READY", handoverStatus(fixture));
        mockMvc.perform(decide(fixture, manager, version(fixture), APPROVE)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("ASSESSMENT_NOT_PASSED")));

        long readyVersion = version(fixture);
        mockMvc.perform(submitHttp(fixture, incoming, answers(fixture, 5))).andExpect(status().isCreated());
        assertEquals(readyVersion, version(fixture));
        mockMvc.perform(decide(fixture, manager, readyVersion, APPROVE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    void resubmissionReplayDoesNotTransitionTwice() throws Exception {
        Fixture fixture = fixture();
        acknowledgeAll(fixture);
        submitDirect(fixture, 5);
        String incoming = signIn("transition-incoming");
        mockMvc.perform(decide(fixture, signIn("transition-manager"), version(fixture), REVISE)).andExpect(status().isOk());
        long revised = version(fixture);
        String key = UUID.randomUUID().toString();
        String body = answers(fixture, 5);

        mockMvc.perform(submitHttp(fixture, incoming, body, key)).andExpect(status().isCreated());
        mockMvc.perform(submitHttp(fixture, incoming, body, key)).andExpect(status().isCreated());

        assertEquals(revised + 1, version(fixture));
        assertEquals(2, jdbc.queryForObject("select count(*) from assessment_attempts", Integer.class));
    }

    private MockHttpServletRequestBuilder decide(Fixture fixture, String session, long version, String body) {
        return post("/api/v1/handovers/{handoverId}/approval", fixture.handoverId()).with(csrf()).cookie(cookie(session))
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder submitHttp(Fixture fixture, String session, String body) {
        return submitHttp(fixture, session, body, UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder submitHttp(Fixture fixture, String session, String body, String key) {
        return post("/api/v1/handovers/{handoverId}/assessment/attempts", fixture.handoverId()).with(csrf()).cookie(cookie(session))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void acknowledgeAll(Fixture fixture) {
        jdbc.queryForList("""
                select item.id from handover_items item join handovers handover on handover.id=item.handover_id
                join handover_generations generation on generation.id=item.generation_id and generation.generation=handover.generation
                where item.handover_id=? and item.is_required order by item.display_order
                """, Long.class, fixture.handoverId()).forEach(itemId -> acknowledgements.acknowledge(fixture.incomingId(),
                fixture.handoverId(), itemId, jdbc.queryForObject("select version from handover_items where id=?", Long.class, itemId)));
    }

    private void submitDirect(Fixture fixture, int correct) {
        assessments.submit(fixture.incomingId(), fixture.handoverId(), answerList(fixture, correct));
    }

    private String answers(Fixture fixture, int correct) {
        StringBuilder body = new StringBuilder("{\"answers\":[");
        for (AssessmentService.SubmittedAnswer answer : answerList(fixture, correct)) {
            if (body.charAt(body.length() - 1) != '[') body.append(',');
            body.append("{\"questionId\":").append(answer.questionId()).append(",\"answerText\":\"").append(answer.answerText()).append("\"}");
        }
        return body.append("]}").toString();
    }

    private List<AssessmentService.SubmittedAnswer> answerList(Fixture fixture, int correctAnswers) {
        return jdbc.query("""
                select question.id, question.question_type::text, question.correct_choice_id, question.accepted_answers ->> 0,
                       (select choice ->> 'id' from jsonb_array_elements(question.choices) choice
                        where choice ->> 'id' <> question.correct_choice_id limit 1)
                from assessment_questions question join assessments assessment on assessment.id=question.assessment_id
                join handovers handover on handover.id=assessment.handover_id and handover.generation=assessment.generation
                where assessment.handover_id=? order by question.question_no
                """, (rs, row) -> {
            boolean correct = row < correctAnswers;
            boolean objective = "MULTIPLE_CHOICE".equals(rs.getString(2));
            String answer = objective ? (correct ? rs.getString(3) : rs.getString(5)) : (correct ? rs.getString(4) : "synthetic wrong answer");
            return new AssessmentService.SubmittedAnswer(rs.getLong(1), answer);
        }, fixture.handoverId());
    }

    private Fixture fixture() {
        long manager = user("transition-manager");
        long outgoing = user("transition-outgoing");
        long incoming = user("transition-incoming");
        long project = jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values(?,'Transition Project','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id",
                Long.class, "TRANSITION-" + System.nanoTime());
        member(project, manager, "PROJECT_MANAGER");
        member(project, outgoing, "OUTGOING_OWNER");
        member(project, incoming, "INCOMING_OWNER");
        long connection = jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','transition fixture') returning id", Long.class, project);
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,'transition fixture',now(),?,'transition-doc','v1','cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','synthetic') returning id
                """, Long.class, connection, project);
        record(project, source, manager, "SCHEDULE", "Milestone", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 10, 22));
        record(project, source, manager, "CHANGE_DECISION", "Decision", LocalDate.of(2026, 8, 21), null);
        record(project, source, manager, "COMMITMENT", "Commitment", LocalDate.of(2026, 8, 22), null);
        record(project, source, manager, "BLOCKER", "Blocker", LocalDate.of(2026, 8, 23), null);
        record(project, source, manager, "OPEN_ISSUE", "Open issue", LocalDate.of(2026, 8, 24), null);
        record(project, source, manager, "SCOPE_EXCEPTION", "Scope", LocalDate.of(2026, 8, 25), null);
        long handover = handovers.request(manager, project, new HandoverRequestService.Command(outgoing, incoming,
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14),
                Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION"), true)).id();
        worker.runOnce();
        assertEquals("READY", jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover));
        return new Fixture(handover, project, incoming);
    }

    private String handoverStatus(Fixture fixture) { return jdbc.queryForObject("select status::text from handovers where id=?", String.class, fixture.handoverId()); }
    private long version(Fixture fixture) { return jdbc.queryForObject("select version from handovers where id=?", Long.class, fixture.handoverId()); }
    private int audit(String action) { return jdbc.queryForObject("select count(*) from audit_events where action=?", Integer.class, action); }
    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }
    private long user(String employeeNo) { return jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),?,?,?, ?,?) returning id", Long.class, employeeNo, employeeNo, "Engineer", employeeNo + "@test", PASSWORDS.encode("synthetic-password")); }
    private void member(long project, long user, String role) { jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),date '2026-09-01')", project, user, role); }
    private void record(long project, long source, long manager, String type, String title, LocalDate occurredOn, LocalDate dueOn) { jdbc.update("insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,due_on,trust_status,confirmed_by,confirmed_at,source_version,extraction_index,search_text) values(?,?,?,cast(? as record_type),?,'Synthetic summary','private synthetic excerpt',?,'Synthetic author',?,'CONFIRMED',?,now(),'v1',?, 'Synthetic summary')", "REC-" + System.nanoTime(), project, source, type, title, occurredOn, dueOn, manager, EXTRACTION_INDEX.getAndIncrement()); }
    private jakarta.servlet.http.Cookie cookie(String value) { return new jakarta.servlet.http.Cookie("BATON_SESSION", value); }
    private record Fixture(long handoverId, long projectId, long incomingId) { }
}
