package com.projectbaton.handover;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
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
class HandoverQueryIT {
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
        jdbc.update("insert into organizations(name) values('Query Organization')");
    }

    @Test
    void listShowsOnlyParticipatingHandoversAndHidesForeignProjects() throws Exception {
        Fixture fixture = fixture();
        user("query-outsider");

        for (String participant : List.of("query-manager", "query-outgoing", "query-incoming")) {
            mockMvc.perform(get("/api/v1/projects/{projectId}/handovers", fixture.projectId()).cookie(cookie(signIn(participant))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()", is(1)))
                    .andExpect(jsonPath("$.items[0].id", is((int) fixture.handoverId())))
                    .andExpect(jsonPath("$.items[0].outgoingOwnerName", is("query-outgoing")))
                    .andExpect(jsonPath("$.items[0].incomingOwnerName", is("query-incoming")))
                    .andExpect(jsonPath("$.items[0].effectiveOn", is("2026-09-14")))
                    .andExpect(jsonPath("$.items[0].generation", is(1)))
                    .andExpect(jsonPath("$.page.total", is(1)));
        }
        mockMvc.perform(get("/api/v1/projects/{projectId}/handovers", fixture.projectId()).cookie(cookie(signIn("query-member"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(0)))
                .andExpect(jsonPath("$.page.total", is(0)));
        mockMvc.perform(get("/api/v1/projects/{projectId}/handovers", fixture.projectId()).cookie(cookie(signIn("query-outsider"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/{projectId}/handovers", fixture.otherProjectId()).cookie(cookie(signIn("query-manager"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void packageReadsAreLimitedToManagerAndNamedOwners() throws Exception {
        Fixture fixture = fixture();
        user("query-outsider");

        for (String participant : List.of("query-manager", "query-outgoing", "query-incoming")) {
            String session = signIn(participant);
            // C01: GET 상세는 버전을 ETag로 담아서, 클라이언트가 헤더만으로 If-Match를 만들 수 있다.
            mockMvc.perform(get("/api/v1/handovers/{handoverId}", fixture.handoverId()).cookie(cookie(session))).andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("ETag",
                            "\"" + jdbc.queryForObject("select version from handovers where id=?", Long.class, fixture.handoverId()) + "\""));
            mockMvc.perform(get("/api/v1/handovers/{handoverId}/items", fixture.handoverId()).cookie(cookie(session))).andExpect(status().isOk());
        }
        String member = signIn("query-member");
        mockMvc.perform(get("/api/v1/handovers/{handoverId}", fixture.handoverId()).cookie(cookie(member))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/handovers/{handoverId}/items", fixture.handoverId()).cookie(cookie(member))).andExpect(status().isForbidden());
        String outsider = signIn("query-outsider");
        mockMvc.perform(get("/api/v1/handovers/{handoverId}", fixture.handoverId()).cookie(cookie(outsider))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/handovers/{handoverId}/items", fixture.handoverId()).cookie(cookie(outsider))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/handovers/{handoverId}", 9_000_000L).cookie(cookie(signIn("query-manager")))).andExpect(status().isNotFound());
    }

    @Test
    void detailShowsScopeConflictsBlockersAndOnlyDraftsTheReaderMayReview() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(get("/api/v1/handovers/{handoverId}", fixture.handoverId()).cookie(cookie(signIn("query-manager"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("READY")))
                // REQUESTED -> GENERATING -> READY: 전이마다 버전이 한 번씩 오른다.
                .andExpect(jsonPath("$.version", is(2)))
                .andExpect(jsonPath("$.progressPercent", is(100)))
                .andExpect(jsonPath("$.scopeFrom", is("2026-08-20")))
                .andExpect(jsonPath("$.scopeTo", is("2026-09-14")))
                .andExpect(jsonPath("$.scopeRecordTypes.length()", is(6)))
                .andExpect(jsonPath("$.includeNoteDerivedRecords", is(true)))
                .andExpect(jsonPath("$.includedCount", is(6)))
                .andExpect(jsonPath("$.failureReason").value((Object) null))
                .andExpect(jsonPath("$.readyAt").isNotEmpty())
                .andExpect(jsonPath("$.completedAt").value((Object) null))
                .andExpect(jsonPath("$.conflicts.length()", is(1)))
                .andExpect(jsonPath("$.conflicts[0].relationType", is("CONFLICTS_WITH")))
                .andExpect(jsonPath("$.conflicts[0].fromRecordId", is((int) fixture.conflictFromId())))
                .andExpect(jsonPath("$.conflicts[0].toRecordId", is((int) fixture.conflictToId())))
                .andExpect(jsonPath("$.approvalBlockers", containsInAnyOrder("REQUIRED_ITEMS_UNACKNOWLEDGED",
                        "CONFLICTS_UNRESOLVED", "ASSESSMENT_NOT_SUBMITTED")))
                .andExpect(jsonPath("$.unconfirmedRecords[*].title", containsInAnyOrder("Shared draft")))
                .andExpect(jsonPath("$.unconfirmedRecords[0].trustStatus", is("AUTO_DRAFT")))
                .andExpect(jsonPath("$.unconfirmedRecords[0].originalExcerpt").doesNotExist());
        mockMvc.perform(get("/api/v1/handovers/{handoverId}", fixture.handoverId()).cookie(cookie(signIn("query-outgoing"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unconfirmedRecords[*].title", containsInAnyOrder("Shared draft", "Personal folder draft")));
        mockMvc.perform(get("/api/v1/handovers/{handoverId}", fixture.handoverId()).cookie(cookie(signIn("query-incoming"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unconfirmedRecords.length()", is(0)))
                .andExpect(jsonPath("$.approvalBlockers", hasItem("ASSESSMENT_NOT_SUBMITTED")))
                .andExpect(jsonPath("$.approvalBlockers", not(hasItem("HANDOVER_NOT_READY"))));
    }

    @Test
    void readsNeitherAdvanceProgressNorLetLiveSourceChangesIntoThePackage() throws Exception {
        Fixture fixture = fixture();
        // 로그인은 감사에 남으므로 참여자가 먼저 로그인한다. 비교하는 것은 읽기 자체가 쓰는 것이다.
        java.util.Map<String, String> sessions = new java.util.LinkedHashMap<>();
        for (String participant : List.of("query-manager", "query-outgoing", "query-incoming")) {
            sessions.put(participant, signIn(participant));
        }
        String snapshotHash = snapshotHash(fixture.handoverId());
        String itemsHash = itemsHash(fixture.handoverId());
        String state = state(fixture.handoverId());

        jdbc.update("update record_sources set source_body='LIVE SOURCE REWRITE', content_hash=repeat('d',64) where project_id=?", fixture.projectId());
        jdbc.update("update work_records set summary='LIVE SUMMARY REWRITE' where project_id=? and trust_status='CONFIRMED'", fixture.projectId());
        for (String participant : sessions.keySet()) {
            String session = sessions.get(participant);
            for (int i = 0; i < 2; i++) {
                mockMvc.perform(get("/api/v1/projects/{projectId}/handovers", fixture.projectId()).cookie(cookie(session))).andExpect(status().isOk());
                mockMvc.perform(get("/api/v1/handovers/{handoverId}", fixture.handoverId()).cookie(cookie(session))).andExpect(status().isOk());
                mockMvc.perform(get("/api/v1/handovers/{handoverId}/items", fixture.handoverId()).cookie(cookie(session)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[*].body", not(hasItem(org.hamcrest.Matchers.containsString("LIVE")))));
            }
        }
        mockMvc.perform(get("/api/v1/handovers/{handoverId}/assessment", fixture.handoverId()).cookie(cookie(sessions.get("query-incoming"))))
                .andExpect(status().isOk());

        assertEquals(snapshotHash, snapshotHash(fixture.handoverId()));
        assertEquals(itemsHash, itemsHash(fixture.handoverId()));
        assertEquals(state, state(fixture.handoverId()));
    }

    private String snapshotHash(long handoverId) {
        return jdbc.queryForObject("""
                select md5(string_agg(snapshot.content_json::text || snapshot.record_version, '|' order by snapshot.id))
                from handover_snapshot_records snapshot join handover_generations generation on generation.id=snapshot.generation_id
                where generation.handover_id=?
                """, String.class, handoverId);
    }

    private String itemsHash(long handoverId) {
        return jdbc.queryForObject("select md5(string_agg(title || body || version, '|' order by id)) from handover_items where handover_id=?",
                String.class, handoverId);
    }

    private String state(long handoverId) {
        return jdbc.queryForObject("""
                select concat_ws('|', handover.status, handover.version, handover.progress_percent,
                    (select count(*) from handover_item_acknowledgements),
                    (select count(*) from assessment_attempts),
                    (select count(*) from audit_events),
                    (select count(*) from idempotency_requests),
                    (select string_agg(status::text, ',') from assessments))
                from handovers handover where handover.id=?
                """, String.class, handoverId);
    }

    private Fixture fixture() {
        long manager = user("query-manager");
        long outgoing = user("query-outgoing");
        long incoming = user("query-incoming");
        long member = user("query-member");
        long project = project("QUERY");
        member(project, manager, "PROJECT_MANAGER");
        member(project, outgoing, "OUTGOING_OWNER");
        member(project, incoming, "INCOMING_OWNER");
        member(project, member, "MEMBER");
        long connection = jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','query fixture') returning id", Long.class, project);
        long source = source(connection, project, "query-doc");
        record(project, source, manager, "SCHEDULE", "Milestone", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 10, 22));
        long decision = record(project, source, manager, "CHANGE_DECISION", "Decision", LocalDate.of(2026, 8, 21), null);
        long commitment = record(project, source, manager, "COMMITMENT", "Commitment", LocalDate.of(2026, 8, 22), null);
        record(project, source, manager, "BLOCKER", "Blocker", LocalDate.of(2026, 8, 23), null);
        record(project, source, manager, "OPEN_ISSUE", "Open issue", LocalDate.of(2026, 8, 24), null);
        record(project, source, manager, "SCOPE_EXCEPTION", "Scope", LocalDate.of(2026, 8, 25), null);
        jdbc.update("insert into record_relations(project_id,from_record_id,to_record_id,relation_type) values(?,?,?,'CONFLICTS_WITH')",
                project, Math.min(decision, commitment), Math.max(decision, commitment));

        draft(project, source, "Shared draft", LocalDate.of(2026, 9, 1));
        draft(project, source, "Draft outside scope", LocalDate.of(2026, 9, 20));
        long personalFolder = jdbc.queryForObject("insert into source_connections(project_id,owner_user_id,channel,display_name) values(?,?,'CLOUD_PROJECT_FOLDER','outgoing folder') returning id",
                Long.class, project, outgoing);
        draft(project, source(personalFolder, project, "personal-doc"), "Personal folder draft", LocalDate.of(2026, 9, 2));

        long otherProject = project("OTHER");
        member(otherProject, user("query-other-manager"), "PROJECT_MANAGER");
        long otherConnection = jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','other fixture') returning id", Long.class, otherProject);
        draft(otherProject, source(otherConnection, otherProject, "other-doc"), "Other project draft", LocalDate.of(2026, 9, 3));

        long handover = handovers.request(manager, project, new HandoverRequestService.Command(outgoing, incoming,
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14),
                Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION"), true)).id();
        worker.runOnce();
        assertEquals("READY", jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover));
        return new Fixture(handover, project, otherProject, Math.min(decision, commitment), Math.max(decision, commitment));
    }

    private long project(String prefix) {
        return jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values(?,'Query Project','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id",
                Long.class, prefix + "-" + System.nanoTime());
    }
    private long source(long connection, long project, String document) {
        return jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,?,now(),?,?,'v1','eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee','synthetic private body') returning id
                """, Long.class, connection, document, project, document);
    }
    private void draft(long project, long source, String title, LocalDate occurredOn) {
        jdbc.update("insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,trust_status,source_version,extraction_index,search_text) values(?,?,?,'COMMITMENT',?,'Draft summary','private draft excerpt',?,'Synthetic author','AUTO_DRAFT','v1',?,'Draft summary')",
                "REC-" + System.nanoTime(), project, source, title, occurredOn, EXTRACTION_INDEX.getAndIncrement());
    }
    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }
    private long user(String employeeNo) { return jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),?,?,?, ?,?) returning id", Long.class, employeeNo, employeeNo, "Engineer", employeeNo + "@test", PASSWORDS.encode("synthetic-password")); }
    private void member(long project, long user, String role) { jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),date '2026-09-01')", project, user, role); }
    private long record(long project, long source, long manager, String type, String title, LocalDate occurredOn, LocalDate dueOn) { return jdbc.queryForObject("insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,due_on,trust_status,confirmed_by,confirmed_at,source_version,extraction_index,search_text) values(?,?,?,cast(? as record_type),?,'Synthetic summary','private synthetic excerpt',?,'Synthetic author',?,'CONFIRMED',?,now(),'v1',?, 'Synthetic summary') returning id", Long.class, "REC-" + System.nanoTime(), project, source, type, title, occurredOn, dueOn, manager, EXTRACTION_INDEX.getAndIncrement()); }
    private jakarta.servlet.http.Cookie cookie(String value) { return new jakarta.servlet.http.Cookie("BATON_SESSION", value); }
    private record Fixture(long handoverId, long projectId, long otherProjectId, long conflictFromId, long conflictToId) { }
}
