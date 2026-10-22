package com.projectbaton.handover;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.assessment.AssessmentService;
import com.projectbaton.shared.AuditRows;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
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
class HandoverApprovalIT {
    private static final java.util.concurrent.atomic.AtomicInteger EXTRACTION_INDEX = new java.util.concurrent.atomic.AtomicInteger();
    private static final PasswordEncoder PASSWORDS = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private static final LocalDate EFFECTIVE_ON = LocalDate.of(2026, 9, 14);
    private static final String APPROVE = "{\"decision\":\"APPROVE\"}";

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
        jdbc.update("insert into organizations(name) values('Approval Organization')");
    }

    @AfterEach
    void dropFailureInjection() {
        jdbc.execute("drop trigger if exists approval_audit_failure on audit_events");
        jdbc.execute("drop function if exists approval_audit_failure()");
    }

    @Test
    void onlyTheCurrentProjectManagerMayDecideCompletion() throws Exception {
        Fixture fixture = fixture(EFFECTIVE_ON, false);
        prepareApprovable(fixture);
        long version = handoverVersion(fixture.handoverId());

        for (String employeeNo : List.of("approval-incoming", "approval-outgoing", "approval-member")) {
            mockMvc.perform(approval(fixture.handoverId(), signIn(employeeNo), key(), version, APPROVE))
                    .andExpect(status().isForbidden());
        }
        user("approval-outsider");
        mockMvc.perform(approval(fixture.handoverId(), signIn("approval-outsider"), key(), version, APPROVE))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/handovers/{handoverId}/approval", fixture.handoverId())
                        .cookie(cookie(signIn("approval-manager"))).header("Idempotency-Key", key())
                        .header("If-Match", "\"" + version + "\"").contentType(MediaType.APPLICATION_JSON).content(APPROVE))
                .andExpect(status().isForbidden());

        assertUnchanged(fixture);
    }

    @Test
    void unacknowledgedRequiredItemBlocksApproval() throws Exception {
        Fixture fixture = fixture(EFFECTIVE_ON, false);
        List<Long> required = requiredItems(fixture.handoverId());
        required.subList(0, required.size() - 1).forEach(itemId -> acknowledge(fixture, itemId));
        submit(fixture, 5);

        mockMvc.perform(approval(fixture.handoverId(), signIn("approval-manager"), key(), handoverVersion(fixture.handoverId()), APPROVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE")))
                .andExpect(jsonPath("$.detail", containsString("REQUIRED_ITEMS_UNACKNOWLEDGED")));
        assertUnchanged(fixture);
    }

    @Test
    void currentGenerationAssessmentMustPassWithTheLatestAttempt() throws Exception {
        Fixture fixture = fixture(EFFECTIVE_ON, false);
        String manager = signIn("approval-manager");
        acknowledgeAll(fixture);

        mockMvc.perform(approval(fixture.handoverId(), manager, key(), handoverVersion(fixture.handoverId()), APPROVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("ASSESSMENT_NOT_SUBMITTED")));

        submit(fixture, 5);
        submit(fixture, 4);
        mockMvc.perform(approval(fixture.handoverId(), manager, key(), handoverVersion(fixture.handoverId()), APPROVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("ASSESSMENT_NOT_PASSED")));
        assertUnchanged(fixture);
    }

    @Test
    void previousGenerationPerfectScoreCannotBypassCurrentGenerationFailure() throws Exception {
        Fixture fixture = fixture(EFFECTIVE_ON, false);
        prepareApprovable(fixture);
        handovers.regenerate(fixture.managerId(), fixture.handoverId(), handoverVersion(fixture.handoverId()),
                new HandoverRequestService.Command(0, 0, EFFECTIVE_ON, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14),
                        Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION"), true));
        worker.runOnce();
        assertEquals("READY", handoverStatus(fixture.handoverId()));
        acknowledgeAll(fixture);
        submit(fixture, 0);

        mockMvc.perform(approval(fixture.handoverId(), signIn("approval-manager"), key(), handoverVersion(fixture.handoverId()), APPROVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("ASSESSMENT_NOT_PASSED")));
        assertEquals(1, jdbc.queryForObject("select count(*) from assessment_attempts where score=5", Integer.class));
        assertUnchanged(fixture);
    }

    @Test
    void futureEffectiveDateIsRejectedWithoutScheduledTransition() throws Exception {
        LocalDate future = jdbc.queryForObject("select (now() at time zone 'Asia/Seoul')::date + 1", LocalDate.class);
        Fixture fixture = fixture(future, false);
        prepareApprovable(fixture);

        mockMvc.perform(approval(fixture.handoverId(), signIn("approval-manager"), key(), handoverVersion(fixture.handoverId()), APPROVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("EFFECTIVE_DATE_NOT_REACHED")));
        assertUnchanged(fixture);
    }

    @Test
    void unresolvedConflictBlocksApproval() throws Exception {
        Fixture fixture = fixture(EFFECTIVE_ON, true);
        prepareApprovable(fixture);

        mockMvc.perform(approval(fixture.handoverId(), signIn("approval-manager"), key(), handoverVersion(fixture.handoverId()), APPROVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("CONFLICTS_UNRESOLVED")));
        assertUnchanged(fixture);
    }

    @Test
    void approvalCommitsCompletionMembershipTransitionAndAuditTogether() throws Exception {
        Fixture fixture = fixture(EFFECTIVE_ON, false);
        prepareApprovable(fixture);
        String manager = signIn("approval-manager");
        String incoming = signIn("approval-incoming");
        long version = handoverVersion(fixture.handoverId());
        int acknowledgementsBefore = jdbc.queryForObject("select count(*) from handover_item_acknowledgements", Integer.class);

        mockMvc.perform(get("/api/v1/handovers/{handoverId}/items", fixture.handoverId()).cookie(cookie(incoming))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/handovers/{handoverId}/assessment", fixture.handoverId()).cookie(cookie(manager))).andExpect(status().isOk());
        assertEquals(version, handoverVersion(fixture.handoverId()));
        assertEquals(acknowledgementsBefore, jdbc.queryForObject("select count(*) from handover_item_acknowledgements", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from assessment_attempts", Integer.class));

        mockMvc.perform(approval(fixture.handoverId(), manager, key(), version, APPROVE))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + (version + 1) + "\""))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.version", is((int) version + 1)))
                .andExpect(jsonPath("$.generation", is(1)))
                .andExpect(jsonPath("$.outgoingOwnerName", is("approval-outgoing")))
                .andExpect(jsonPath("$.incomingOwnerName", is("approval-incoming")))
                .andExpect(jsonPath("$.approvalBlockers.length()", is(0)))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        LocalDate businessDay = jdbc.queryForObject("select (now() at time zone 'Asia/Seoul')::date", LocalDate.class);
        Map<String, Object> outgoing = membership(fixture.projectId(), fixture.outgoingId());
        Map<String, Object> incomingMembership = membership(fixture.projectId(), fixture.incomingId());
        assertEquals(businessDay, ((java.sql.Date) outgoing.get("left_on")).toLocalDate());
        assertEquals("OUTGOING_OWNER", outgoing.get("project_role"));
        assertEquals(1L, outgoing.get("version"));
        assertEquals("OUTGOING_OWNER", incomingMembership.get("project_role"));
        assertNull(incomingMembership.get("left_on"));
        assertEquals(1L, incomingMembership.get("version"));
        assertEquals(fixture.managerId(), jdbc.queryForObject("select approved_by from handovers where id=?", Long.class, fixture.handoverId()));
        assertEquals(1, auditCount("APPROVE_HANDOVER"));
        assertEquals(2, auditCount("TRANSITION_PROJECT_MEMBER"));
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_events where target_id like '%summary%' or action like '%ANSWER%'", Integer.class));

        mockMvc.perform(get("/api/v1/handovers/{handoverId}/items", fixture.handoverId()).cookie(cookie(signIn("approval-outgoing"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/handovers/{handoverId}/items", fixture.handoverId()).cookie(cookie(incoming)))
                .andExpect(status().isOk());
        mockMvc.perform(approval(fixture.handoverId(), manager, key(), version + 1, APPROVE))
                .andExpect(status().isConflict());
        mockMvc.perform(approval(fixture.handoverId(), manager, key(), version + 1,
                        "{\"decision\":\"REQUEST_REVISION\",\"comment\":\"Too late\"}"))
                .andExpect(status().isConflict());
        assertEquals(1, auditCount("APPROVE_HANDOVER"));
    }

    @Test
    void auditFailureRollsBackCompletionAndMembershipTransition() throws Exception {
        Fixture fixture = fixture(EFFECTIVE_ON, false);
        prepareApprovable(fixture);
        jdbc.execute("""
                create function approval_audit_failure() returns trigger language plpgsql as $$
                begin
                    if new.action = 'APPROVE_HANDOVER' then raise exception 'synthetic audit outage'; end if;
                    return new;
                end $$
                """);
        jdbc.execute("create trigger approval_audit_failure before insert on audit_events for each row execute function approval_audit_failure()");

        String manager = signIn("approval-manager");
        long version = handoverVersion(fixture.handoverId());
        mockMvc.perform(approval(fixture.handoverId(), manager, key(), version, APPROVE))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code", is("INTERNAL_ERROR")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("synthetic audit outage"))));
        assertUnchanged(fixture);
        assertEquals(0, jdbc.queryForObject("select count(*) from idempotency_requests where path like '%/approval'", Integer.class));
        assertEquals(0, auditCount("TRANSITION_PROJECT_MEMBER"));
    }

    @Test
    void concurrentApprovalsOnTheSameDayTransitionExactlyOnce() throws Exception {
        Fixture fixture = fixture(EFFECTIVE_ON, false);
        prepareApprovable(fixture);
        String manager = signIn("approval-manager");
        long version = handoverVersion(fixture.handoverId());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String requestKey = key();
            Callable<Integer> call = () -> {
                start.await();
                return mockMvc.perform(approval(fixture.handoverId(), manager, requestKey, version, APPROVE))
                        .andReturn().getResponse().getStatus();
            };
            results.add(executor.submit(call));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) statuses.add(result.get());
        executor.shutdown();

        assertEquals(1, statuses.stream().filter(code -> code == 200).count(), statuses.toString());
        assertEquals(1, statuses.stream().filter(code -> code == 412 || code == 409).count(), statuses.toString());
        assertEquals("COMPLETED", handoverStatus(fixture.handoverId()));
        assertEquals(version + 1, handoverVersion(fixture.handoverId()));
        assertEquals(1, auditCount("APPROVE_HANDOVER"));
        assertEquals(2, auditCount("TRANSITION_PROJECT_MEMBER"));
        assertEquals(1L, membership(fixture.projectId(), fixture.outgoingId()).get("version"));
        assertEquals(1L, membership(fixture.projectId(), fixture.incomingId()).get("version"));
    }

    @Test
    void enforcesIfMatchAndIdempotencyReplayContract() throws Exception {
        Fixture fixture = fixture(EFFECTIVE_ON, false);
        prepareApprovable(fixture);
        String manager = signIn("approval-manager");
        long version = handoverVersion(fixture.handoverId());

        mockMvc.perform(post("/api/v1/handovers/{handoverId}/approval", fixture.handoverId()).with(csrf()).cookie(cookie(manager))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(APPROVE))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(approval(fixture.handoverId(), manager, key(), version + 7, APPROVE))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(post("/api/v1/handovers/{handoverId}/approval", fixture.handoverId()).with(csrf()).cookie(cookie(manager))
                        .header("If-Match", "\"" + version + "\"").contentType(MediaType.APPLICATION_JSON).content(APPROVE))
                .andExpect(status().isBadRequest());
        // 의견은 보완 요청에 속한다. 예전에는 승인에서도 의견을 검사한 뒤 아무 말 없이 버렸다.
        for (String invalid : List.of("{}", "{\"decision\":\"MAYBE\"}", "{\"decision\":\"APPROVE\",\"extra\":1}",
                "{\"decision\":\"APPROVE\",\"comment\":\"Looks good\"}",
                "{\"decision\":\"REQUEST_REVISION\"}", "{\"decision\":\"REQUEST_REVISION\",\"comment\":\" \"}",
                "{\"decision\":\"REQUEST_REVISION\",\"comment\":\"" + "x".repeat(501) + "\"}")) {
            mockMvc.perform(approval(fixture.handoverId(), manager, key(), version, invalid)).andExpect(status().isBadRequest());
        }
        assertUnchanged(fixture);

        String replayKey = key();
        mockMvc.perform(approval(fixture.handoverId(), manager, replayKey, version, APPROVE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("COMPLETED")));
        mockMvc.perform(approval(fixture.handoverId(), manager, replayKey, version, APPROVE))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + (version + 1) + "\""))
                .andExpect(jsonPath("$.status", is("COMPLETED")));
        mockMvc.perform(approval(fixture.handoverId(), manager, replayKey, version,
                        "{\"decision\":\"REQUEST_REVISION\",\"comment\":\"Different body\"}"))
                .andExpect(status().isConflict());
        assertEquals(1, auditCount("APPROVE_HANDOVER"));
        assertEquals(1, jdbc.queryForObject("select count(*) from idempotency_requests where path like '%/approval'", Integer.class));
    }

    @Test
    void revisionRequestReturnsReadyPackageToTheIncomingOwnerWithAComment() throws Exception {
        Fixture fixture = fixture(EFFECTIVE_ON, false);
        prepareApprovable(fixture);
        String manager = signIn("approval-manager");
        long version = handoverVersion(fixture.handoverId());

        mockMvc.perform(approval(fixture.handoverId(), manager, key(), version,
                        "{\"decision\":\"REQUEST_REVISION\",\"comment\":\"Explain the open risk owner.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REVIEW_REQUESTED")))
                .andExpect(jsonPath("$.revisionComment", is("Explain the open risk owner.")))
                .andExpect(jsonPath("$.excludedCount").doesNotExist())
                .andExpect(jsonPath("$.approvalBlockers[0]", is("HANDOVER_NOT_READY")));
        assertEquals("Explain the open risk owner.", jdbc.queryForObject("select revision_comment from handovers where id=?", String.class, fixture.handoverId()));
        assertEquals(1, auditCount("REQUEST_HANDOVER_REVISION"));
        assertEquals(0, auditCount("TRANSITION_PROJECT_MEMBER"));

        mockMvc.perform(approval(fixture.handoverId(), manager, key(), version + 1, APPROVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("HANDOVER_NOT_READY")));
        mockMvc.perform(approval(fixture.handoverId(), manager, key(), version + 1,
                        "{\"decision\":\"REQUEST_REVISION\",\"comment\":\"Again\"}"))
                .andExpect(status().isConflict());
        assertEquals(0L, membership(fixture.projectId(), fixture.outgoingId()).get("version"));
    }

    private void assertUnchanged(Fixture fixture) {
        assertEquals("READY", handoverStatus(fixture.handoverId()));
        assertNull(jdbc.queryForObject("select completed_at from handovers where id=?", java.time.OffsetDateTime.class, fixture.handoverId()));
        assertEquals(0, auditCount("APPROVE_HANDOVER"));
        Map<String, Object> outgoing = membership(fixture.projectId(), fixture.outgoingId());
        Map<String, Object> incoming = membership(fixture.projectId(), fixture.incomingId());
        assertNull(outgoing.get("left_on"));
        assertEquals(0L, outgoing.get("version"));
        assertEquals("INCOMING_OWNER", incoming.get("project_role"));
        assertEquals(0L, incoming.get("version"));
    }

    private void prepareApprovable(Fixture fixture) {
        acknowledgeAll(fixture);
        submit(fixture, 5);
    }

    private void acknowledgeAll(Fixture fixture) {
        requiredItems(fixture.handoverId()).forEach(itemId -> acknowledge(fixture, itemId));
    }

    private void acknowledge(Fixture fixture, long itemId) {
        long version = jdbc.queryForObject("select version from handover_items where id=?", Long.class, itemId);
        acknowledgements.acknowledge(fixture.incomingId(), fixture.handoverId(), itemId, version);
    }

    private List<Long> requiredItems(long handoverId) {
        return jdbc.queryForList("""
                select item.id from handover_items item join handovers handover on handover.id=item.handover_id
                join handover_generations generation on generation.id=item.generation_id and generation.generation=handover.generation
                where item.handover_id=? and item.is_required order by item.display_order
                """, Long.class, handoverId);
    }

    private void submit(Fixture fixture, int correctAnswers) {
        List<AssessmentService.SubmittedAnswer> answers = jdbc.query("""
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
        assessments.submit(fixture.incomingId(), fixture.handoverId(), answers);
    }

    private MockHttpServletRequestBuilder approval(long handoverId, String session, String key, long version, String body) {
        return post("/api/v1/handovers/{handoverId}/approval", handoverId).with(csrf()).cookie(cookie(session))
                .header("Idempotency-Key", key).header("If-Match", "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Fixture fixture(LocalDate effectiveOn, boolean withConflict) {
        long manager = user("approval-manager");
        long outgoing = user("approval-outgoing");
        long incoming = user("approval-incoming");
        long member = user("approval-member");
        long project = jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values(?,'Approval Project','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id",
                Long.class, "APPROVAL-" + System.nanoTime());
        member(project, manager, "PROJECT_MANAGER");
        member(project, outgoing, "OUTGOING_OWNER");
        member(project, incoming, "INCOMING_OWNER");
        member(project, member, "MEMBER");
        long connection = jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','approval fixture') returning id", Long.class, project);
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,'approval fixture',now(),?,'approval-doc','v1','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','synthetic') returning id
                """, Long.class, connection, project);
        record(project, source, manager, "SCHEDULE", "Milestone", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 10, 22));
        long decision = record(project, source, manager, "CHANGE_DECISION", "Decision", LocalDate.of(2026, 8, 21), null);
        long commitment = record(project, source, manager, "COMMITMENT", "Commitment", LocalDate.of(2026, 8, 22), null);
        record(project, source, manager, "BLOCKER", "Blocker", LocalDate.of(2026, 8, 23), null);
        record(project, source, manager, "OPEN_ISSUE", "Open issue", LocalDate.of(2026, 8, 24), null);
        record(project, source, manager, "SCOPE_EXCEPTION", "Scope", LocalDate.of(2026, 8, 25), null);
        if (withConflict) {
            jdbc.update("insert into record_relations(project_id,from_record_id,to_record_id,relation_type) values(?,?,?,'CONFLICTS_WITH')",
                    project, Math.min(decision, commitment), Math.max(decision, commitment));
        }
        long handover = handovers.request(manager, project, new HandoverRequestService.Command(outgoing, incoming,
                effectiveOn, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14),
                Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION"), true)).id();
        worker.runOnce();
        assertEquals("READY", handoverStatus(handover));
        return new Fixture(handover, project, manager, outgoing, incoming);
    }

    private String handoverStatus(long handoverId) { return jdbc.queryForObject("select status::text from handovers where id=?", String.class, handoverId); }
    private long handoverVersion(long handoverId) { return jdbc.queryForObject("select version from handovers where id=?", Long.class, handoverId); }
    private int auditCount(String action) { return jdbc.queryForObject("select count(*) from audit_events where action=?", Integer.class, action); }
    private Map<String, Object> membership(long project, long user) {
        return jdbc.queryForMap("select project_role::text as project_role, left_on, version from project_members where project_id=? and user_id=?", project, user);
    }
    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }
    private long user(String employeeNo) { return jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),?,?,?, ?,?) returning id", Long.class, employeeNo, employeeNo, "Engineer", employeeNo + "@test", PASSWORDS.encode("synthetic-password")); }
    private void member(long project, long user, String role) { jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),date '2026-09-01')", project, user, role); }
    private long record(long project, long source, long manager, String type, String title, LocalDate occurredOn, LocalDate dueOn) { return jdbc.queryForObject("insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,due_on,trust_status,confirmed_by,confirmed_at,source_version,extraction_index,search_text) values(?,?,?,cast(? as record_type),?,'Synthetic summary','private synthetic excerpt',?,'Synthetic author',?,'CONFIRMED',?,now(),'v1',?, 'Synthetic summary') returning id", Long.class, "REC-" + System.nanoTime(), project, source, type, title, occurredOn, dueOn, manager, EXTRACTION_INDEX.getAndIncrement()); }
    private String key() { return UUID.randomUUID().toString(); }
    private jakarta.servlet.http.Cookie cookie(String value) { return new jakarta.servlet.http.Cookie("BATON_SESSION", value); }
    private record Fixture(long handoverId, long projectId, long managerId, long outgoingId, long incomingId) { }
}
