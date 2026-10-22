package com.projectbaton.assessment;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.handover.HandoverPackageWorker;
import com.projectbaton.handover.HandoverRequestService;
import java.time.LocalDate;
import java.util.Set;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AssessmentIT {
    private static final java.util.concurrent.atomic.AtomicInteger EXTRACTION_INDEX = new java.util.concurrent.atomic.AtomicInteger();
    private static final PasswordEncoder PASSWORDS = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired HandoverRequestService handovers;
    @Autowired HandoverPackageWorker worker;

    @BeforeEach
    void reset() {
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations(name) values('Assessment API Organization')");
    }

    @Test
    void incomingOwnerCanReadPublicQuestionsSubmitAndReplayTheSameAttempt() throws Exception {
        Fixture fixture = fixture();
        String incomingSession = signIn("assessment-incoming");
        String memberSession = signIn("assessment-member");

        mockMvc.perform(get("/api/v1/handovers/{handoverId}/assessment", fixture.handoverId()).cookie(cookie(incomingSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation", is(1)))
                .andExpect(jsonPath("$.questions.length()", is(5)))
                .andExpect(jsonPath("$.questions[0].acceptedAnswers").doesNotExist())
                .andExpect(jsonPath("$.questions[0].correctChoiceId").doesNotExist())
                .andExpect(jsonPath("$.questions[0].expectedAnswer").doesNotExist());

        String answers = answers(fixture.handoverId());
        mockMvc.perform(submit(fixture.handoverId(), incomingSession, "84000000-0000-0000-0000-000000000001", answers))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.score", is(5)))
                .andExpect(jsonPath("$.total", is(5)));
        mockMvc.perform(submit(fixture.handoverId(), incomingSession, "84000000-0000-0000-0000-000000000001", answers))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.score", is(5)));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("select count(*) from assessment_attempts", Integer.class));

        mockMvc.perform(submit(fixture.handoverId(), memberSession, "84000000-0000-0000-0000-000000000002", answers))
                .andExpect(status().isForbidden());
    }

    /** 관리자 확인이 ProjectAccessPolicy로 옮겨 갔다. 이 시험은 그 확인이 지켜야 할 동작을 고정한다. */
    @Test
    void aProjectManagerReadsTheAssessmentAndAPlainMemberDoesNot() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(get("/api/v1/handovers/{handoverId}/assessment", fixture.handoverId()).cookie(cookie(signIn("assessment-manager"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/handovers/{handoverId}/assessment", fixture.handoverId()).cookie(cookie(signIn("assessment-member"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsOldGenerationQuestionIdsAsAConflict() throws Exception {
        Fixture fixture = fixture();
        String incomingSession = signIn("assessment-incoming");
        String oldAnswers = answers(fixture.handoverId());

        handovers.regenerate(fixture.managerId(), fixture.handoverId(), version(fixture.handoverId()), new HandoverRequestService.Command(0, 0,
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14),
                Set.of("CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION"), true));
        worker.runOnce();

        mockMvc.perform(submit(fixture.handoverId(), incomingSession, "84000000-0000-0000-0000-000000000003", oldAnswers))
                .andExpect(status().isConflict());
    }

    @Test
    void readingWhileTheNewGenerationIsBuiltIsADeclaredConflict() throws Exception {
        // 재생성은 인수인계를 곧바로 다음 회차로 옮기고, 그 회차의 평가는 패키지 생성이 끝나야 생긴다. 그 사이의
        // 읽기는 409 INVALID_STATE로 답하고, 이제 계약도 getAssessment에 이를 선언한다. 부하 시험에서 질문 읽기가
        // 재생성과 겹칠 때마다 만났다.
        Fixture fixture = fixture();
        String incomingSession = signIn("assessment-incoming");
        handovers.regenerate(fixture.managerId(), fixture.handoverId(), version(fixture.handoverId()), new HandoverRequestService.Command(0, 0,
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14),
                Set.of("CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION"), true));

        mockMvc.perform(get("/api/v1/handovers/{handoverId}/assessment", fixture.handoverId()).cookie(cookie(incomingSession)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        worker.runOnce();
        mockMvc.perform(get("/api/v1/handovers/{handoverId}/assessment", fixture.handoverId()).cookie(cookie(incomingSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(2));
    }

    @Test
    void scoresOnlyExplicitDateSynonymsAndRejectsPartialAnswers() throws Exception {
        Fixture fixture = fixture();
        String incomingSession = signIn("assessment-incoming");
        String isoAnswers = answers(fixture.handoverId());
        // 문항은 데모 모드의 날짜 문항처럼 한국어 화면에 보인다.
        String datePrompt = jdbc.queryForObject("select prompt from assessment_questions where question_type='SHORT_ANSWER'", String.class);
        org.junit.jupiter.api.Assertions.assertTrue(datePrompt.endsWith("'의 확정 기한은 언제인가?"), datePrompt);

        mockMvc.perform(submit(fixture.handoverId(), incomingSession, "84000000-0000-0000-0000-000000000004",
                        isoAnswers.replace("2026-10-22", "10월 22일")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.score", is(5)));
        mockMvc.perform(submit(fixture.handoverId(), incomingSession, "84000000-0000-0000-0000-000000000005",
                        isoAnswers.replace("2026-10-22", "10/22")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.score", is(5)));
        mockMvc.perform(submit(fixture.handoverId(), incomingSession, "84000000-0000-0000-0000-000000000006",
                        isoAnswers.replace("2026-10-22", "10월")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.score", is(4)))
                .andExpect(jsonPath("$.answers[0].linkedRecordCode").exists());
        mockMvc.perform(submit(fixture.handoverId(), incomingSession, "84000000-0000-0000-0000-000000000007", "{\"answers\":[]}"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submit(long handoverId, String session,
                                                                                               String key, String body) {
        return post("/api/v1/handovers/{handoverId}/assessment/attempts", handoverId).with(csrf()).cookie(cookie(session))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String answers(long handoverId) {
        return jdbc.query("""
                select question.id, question.question_type::text, question.correct_choice_id, question.accepted_answers ->> 0
                from assessment_questions question join assessments assessment on assessment.id=question.assessment_id
                where assessment.handover_id=? order by question.question_no
                """, result -> {
            StringBuilder body = new StringBuilder("{\"answers\":[");
            while (result.next()) {
                if (body.charAt(body.length() - 1) != '[') body.append(',');
                String answer = "MULTIPLE_CHOICE".equals(result.getString(2)) ? result.getString(3) : result.getString(4);
                body.append("{\"questionId\":").append(result.getLong(1)).append(",\"answerText\":\"")
                        .append(answer).append("\"}");
            }
            return body.append("]}").toString();
        }, handoverId);
    }

    private Fixture fixture() {
        long manager = user("assessment-manager");
        long outgoing = user("assessment-outgoing");
        long incoming = user("assessment-incoming");
        long member = user("assessment-member");
        long project = project();
        member(project, manager, "PROJECT_MANAGER");
        member(project, outgoing, "OUTGOING_OWNER");
        member(project, incoming, "INCOMING_OWNER");
        member(project, member, "MEMBER");
        long connection = jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','assessment fixture') returning id", Long.class, project);
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,'assessment fixture',now(),?,'assessment-doc','v1','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','synthetic') returning id
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
        return new Fixture(handover, manager);
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }
    private long user(String employeeNo) { return jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),?,?,?, ?,?) returning id", Long.class, employeeNo, employeeNo, "Engineer", employeeNo + "@test", PASSWORDS.encode("synthetic-password")); }
    private long project() { return jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values(?,'Assessment Project','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id", Long.class, "ASSESSMENT-" + System.nanoTime()); }
    private void member(long project, long user, String role) { jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),current_date)", project, user, role); }
    private void record(long project, long source, long manager, String type, String title, LocalDate occurredOn, LocalDate dueOn) { jdbc.update("insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,due_on,trust_status,confirmed_by,confirmed_at,source_version,extraction_index,search_text) values(?,?,?,cast(? as record_type),?,'Synthetic summary','private synthetic excerpt',?,'Synthetic author',?,'CONFIRMED',?,now(),'v1',?, 'Synthetic summary')", "REC-" + System.nanoTime(), project, source, type, title, occurredOn, dueOn, manager, EXTRACTION_INDEX.getAndIncrement()); }
    private jakarta.servlet.http.Cookie cookie(String value) { return new jakarta.servlet.http.Cookie("BATON_SESSION", value); }
    private record Fixture(long handoverId, long managerId) { }

    /** PM이 읽은 버전. 고정값은 인수인계 전이가 버전을 올리기 시작할 때마다 깨졌다. */
    private long version(long handoverId) {
        return jdbc.queryForObject("select version from handovers where id=?", Long.class, handoverId);
    }
}
