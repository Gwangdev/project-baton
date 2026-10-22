package com.projectbaton.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.shared.AuditRows;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * README §4.2는 타임라인 목록, 합계, 기록 상세, 대시보드 개수가 공개 범위 조건 하나를 쓰도록 요구한다. 아래
 * 행위자들은 같은 프로젝트의 서로 다른 부분을 본다. 확정 기록은 모든 구성원에게, 공유 연동의 초안은 기존 담당자와
 * 관리자에게만, 개인 연동의 초안은 그 주인에게만 보인다. 모든 필터 조합을 cursor로 끝까지 넘기며 항목 수를
 * page.total과 비교한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecordVisibilityIT {
    private static final PasswordEncoder PASSWORD_ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    // 시험이 이 값으로 계정을 만들어서, 소스에 두지 않고 실행마다 만든다.
    private static final String PASSWORD = UUID.randomUUID().toString();

    // 요청이 공개 범위 규칙을 몇 번 평가하는지 시험이 셀 수 있게 pg_stat_statements를 미리 불러온다. extension은
    // Flyway가 소유한 스키마 밖인 postgres DB에 만든다.
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private long projectId;
    private final Map<String, Long> users = new LinkedHashMap<>();
    private final Map<String, Long> records = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        AuditRows.clear(jdbc);
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations, projects restart identity cascade");
        jdbc.update("insert into organizations (name) values ('Visibility Organization')");
        projectId = project("PRJ-VISIBILITY");
        member("pm", "PROJECT_MANAGER", 0);
        member("outgoing", "OUTGOING_OWNER", 0);
        member("incoming", "INCOMING_OWNER", 0);
        member("member", "MEMBER", 0);
        member("folder-owner", "MEMBER", 0);
        member("former", "MEMBER", 30);
        jdbc.update("update project_members set left_on = current_date where user_id = ?", users.get("former"));
        users.put("outsider", user("outsider"));

        long personal = connection(users.get("folder-owner"));
        long shared = connection(null);
        long personalSource = source(personal, "personal-doc");
        long sharedSource = source(shared, "shared-doc");
        record("PRIVATE-DRAFT-1", personalSource, "AUTO_DRAFT", "BLOCKER", "2026-03-10");
        record("PRIVATE-DRAFT-2", personalSource, "AUTO_DRAFT", "SCHEDULE", "2026-03-08");
        record("PRIVATE-DISMISSED", personalSource, "DISMISSED", "BLOCKER", "2026-03-08");
        record("PRIVATE-CONFIRMED", personalSource, "CONFIRMED", "BLOCKER", "2026-03-07");
        record("PRIVATE-CORRECTED", personalSource, "CORRECTED", "OPEN_ISSUE", "2026-03-06");
        record("SHARED-DRAFT-1", sharedSource, "AUTO_DRAFT", "OPEN_ISSUE", "2026-03-10");
        record("SHARED-DRAFT-2", sharedSource, "AUTO_DRAFT", "BLOCKER", "2026-03-08");
        record("SHARED-DRAFT-3", sharedSource, "AUTO_DRAFT", "SCHEDULE", "2026-03-05");
        record("SHARED-DISMISSED", sharedSource, "DISMISSED", "OPEN_ISSUE", "2026-03-04");
        record("SHARED-CONFIRMED-1", sharedSource, "CONFIRMED", "BLOCKER", "2026-03-08");
        record("SHARED-CONFIRMED-2", sharedSource, "CONFIRMED", "SCHEDULE", "2026-03-03");
        noteRecord("NOTE-CONFIRMED", "2026-03-08");

        long otherProject = project("PRJ-VISIBILITY-OTHER");
        jdbc.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, 'PROJECT_MANAGER', current_date)",
                otherProject, users.get("pm"));
        long otherSource = sourceIn(otherProject, connectionIn(otherProject, null), "other-doc");
        recordIn(otherProject, "OTHER-DRAFT", otherSource, "AUTO_DRAFT", "BLOCKER", "2026-03-09");
    }

    @Test
    void eachActorSeesExactlyItsSliceInListTotalAndDetail() throws Exception {
        List<String> confirmed = List.of("PRIVATE-CONFIRMED", "PRIVATE-CORRECTED", "SHARED-CONFIRMED-1", "SHARED-CONFIRMED-2", "NOTE-CONFIRMED");
        List<String> sharedDrafts = List.of("SHARED-DRAFT-1", "SHARED-DRAFT-2", "SHARED-DRAFT-3", "SHARED-DISMISSED");
        List<String> privateDrafts = List.of("PRIVATE-DRAFT-1", "PRIVATE-DRAFT-2", "PRIVATE-DISMISSED");
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("pm", concat(confirmed, sharedDrafts));
        expected.put("outgoing", concat(confirmed, sharedDrafts));
        expected.put("incoming", confirmed);
        expected.put("member", confirmed);
        expected.put("folder-owner", concat(confirmed, privateDrafts));

        for (Map.Entry<String, List<String>> actor : expected.entrySet()) {
            String session = signIn(actor.getKey());
            List<String> seen = pageThrough(session, Map.of());
            assertEquals(sorted(actor.getValue()), sorted(seen), actor.getKey());
            for (String code : records.keySet()) {
                int status = mockMvc.perform(get("/api/v1/records/{recordId}", records.get(code)).cookie(cookie(session)))
                        .andReturn().getResponse().getStatus();
                assertEquals(actor.getValue().contains(code) ? 200 : 404, status, actor.getKey() + " detail " + code);
            }
        }
    }

    @Test
    void totalMatchesThePagedItemsForEveryFilterCombination() throws Exception {
        List<Map<String, String>> filters = List.of(
                Map.of(),
                Map.of("trustStatus", "AUTO_DRAFT"),
                Map.of("trustStatus", "DISMISSED"),
                Map.of("trustStatus", "CONFIRMED,CORRECTED"),
                Map.of("recordType", "BLOCKER"),
                Map.of("recordType", "BLOCKER,SCHEDULE", "trustStatus", "AUTO_DRAFT"),
                Map.of("occurredFrom", "2026-03-06", "occurredTo", "2026-03-08"),
                Map.of("occurredFrom", "2026-03-08", "trustStatus", "AUTO_DRAFT,CONFIRMED"));
        for (String actor : List.of("pm", "outgoing", "incoming", "member", "folder-owner")) {
            String session = signIn(actor);
            for (Map<String, String> filter : filters) {
                pageThrough(session, filter);
            }
        }
    }

    /**
     * cursor는 클라이언트에게 불투명하지만 클라이언트는 지어낸 cursor를 보낼 수 있다. 해석할 수 없는 날짜는 500으로
     * 끝났고(DateTimeParseException은 IllegalArgumentException이 아니다), long 범위를 넘거나 타입이 틀린 id도
     * 받아들여지거나 같은 식으로 실패했다. 잘못된 cursor는 모두 호출자의 오류다.
     */
    @Test
    void aMalformedCursorIsABadRequestNotAServerError() throws Exception {
        String session = signIn("pm");
        for (String cursor : List.of("{\"occurredOn\":\"x\",\"id\":1}", "{\"occurredOn\":5,\"id\":1}",
                "{\"occurredOn\":\"2026-03-08\",\"id\":99999999999999999999}", "{\"occurredOn\":\"2026-03-08\",\"id\":\"1\"}",
                "{\"occurredOn\":\"2026-03-08\"}")) {
            mockMvc.perform(get("/api/v1/projects/{projectId}/records", projectId)
                            .param("cursor", java.util.Base64.getUrlEncoder().withoutPadding()
                                    .encodeToString(cursor.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                            .cookie(cookie(session)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void formerMembersAndOutsidersGetNotFound() throws Exception {
        for (String actor : List.of("former", "outsider")) {
            mockMvc.perform(get("/api/v1/projects/{projectId}/records", projectId).cookie(cookie(signIn(actor))))
                    .andExpect(status().isNotFound());
        }
        jdbc.update("update projects set is_active = false where id = ?", projectId);
        mockMvc.perform(get("/api/v1/projects/{projectId}/records", projectId).cookie(cookie(signIn("pm"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void dashboardPendingCountIsTheVisibleDraftTotal() throws Exception {
        for (String actor : List.of("pm", "outgoing", "incoming", "member", "folder-owner")) {
            String session = signIn(actor);
            JsonNode dashboard = read(get("/api/v1/dashboard"), session);
            long expected = 0;
            for (JsonNode project : dashboard.get("projects")) {
                long drafts = read(get("/api/v1/projects/{projectId}/records", project.get("id").asLong())
                        .param("trustStatus", "AUTO_DRAFT").param("limit", "1"), session).get("page").get("total").asLong();
                assertEquals(drafts, project.get("pendingRecordCount").asLong(), actor + " project card");
                assertEquals(drafts, read(get("/api/v1/projects/{projectId}", project.get("id").asLong()), session)
                        .get("pendingRecordCount").asLong(), actor + " project detail");
                expected += drafts;
            }
            assertEquals(expected, dashboard.get("pendingRecordCount").asLong(), actor + " dashboard");
        }
        String pm = signIn("pm");
        assertEquals(4, read(get("/api/v1/dashboard"), pm).get("pendingRecordCount").asLong(),
                "three shared drafts here and one in the other project; the personal drafts are never counted for the manager");
        assertEquals(2, read(get("/api/v1/dashboard"), signIn("folder-owner")).get("pendingRecordCount").asLong());
    }

    @Test
    void dashboardSummaryCountsProjectsBeyondTheTwentyCards() throws Exception {
        for (int i = 0; i < 21; i++) {
            long extra = project("PRJ-VISIBILITY-EXTRA-" + i);
            jdbc.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, 'PROJECT_MANAGER', current_date)",
                    extra, users.get("pm"));
            recordIn(extra, "EXTRA-DRAFT-" + i, sourceIn(extra, connectionIn(extra, null), "extra-doc-" + i),
                    "AUTO_DRAFT", "BLOCKER", "2026-03-09");
        }
        JsonNode dashboard = read(get("/api/v1/dashboard"), signIn("pm"));
        assertEquals(23, dashboard.get("projectCount").asLong());
        assertEquals(20, dashboard.get("projects").size());
        assertEquals(4 + 21, dashboard.get("pendingRecordCount").asLong(),
                "the summary counts every accessible project, including the three without a card");
    }

    // 요약 합계와 프로젝트 카드가 같은 프로젝트에 대해 각자 공개 범위 규칙을 평가해서, 60 RPS에서 대시보드의 확인
    // 대기 수가 DB 시간에서 가장 큰 몫을 차지했다(pg_stat_statements, evidence/performance.md). 이제 요청마다 한 번
    // 평가한 결과로 둘 다 채운다.
    @Test
    void dashboardEvaluatesTheVisibilityRuleOnce() throws Exception {
        String session = signIn("pm");
        String statsUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/postgres");
        try (Connection stats = DriverManager.getConnection(statsUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = stats.createStatement()) {
            statement.execute("create extension if not exists pg_stat_statements");
            statement.execute("select pg_stat_statements_reset()");
            read(get("/api/v1/dashboard"), session);
            try (ResultSet calls = statement.executeQuery("""
                    select coalesce(sum(s.calls), 0) from pg_stat_statements s join pg_database d on d.oid = s.dbid
                    where d.datname = '%s' and s.query like '%%reviewable_source%%'
                    """.formatted(POSTGRES.getDatabaseName()))) {
                calls.next();
                assertEquals(1, calls.getLong(1), "statements that evaluated the visibility rule in one dashboard request");
            }
        }
    }

    /** cursor로 두 개씩 넘기며 모든 페이지의 합계가 본 항목 수와 같은지 확인한다. */
    private List<String> pageThrough(String session, Map<String, String> filter) throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        Long total = null;
        do {
            MockHttpServletRequestBuilder request = get("/api/v1/projects/{projectId}/records", projectId).param("limit", "2");
            for (Map.Entry<String, String> entry : filter.entrySet()) {
                request.param(entry.getKey(), entry.getValue().split(","));
            }
            if (cursor != null) request.param("cursor", cursor);
            JsonNode page = read(request, session);
            long reported = page.get("page").get("total").asLong();
            if (total != null) assertEquals(total, reported, "total changed between pages for " + filter);
            total = reported;
            for (JsonNode item : page.get("items")) {
                assertFalse(seen.contains(item.get("code").asString()), "duplicate across pages " + filter);
                seen.add(item.get("code").asString());
            }
            JsonNode next = page.get("page").get("nextCursor");
            cursor = next == null || next.isNull() ? null : next.asString();
        } while (cursor != null);
        assertEquals(total, (long) seen.size(), "total versus paged items for " + filter);
        return seen;
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String session) throws Exception {
        String body = mockMvc.perform(request.cookie(cookie(session))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }

    private long user(String employeeNo) {
        return jdbc.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email, system_role, credential_hash)
                values ((select id from organizations where name = 'Visibility Organization'), ?, ?, 'Engineer', ?, 'USER', ?)
                returning id
                """, Long.class, employeeNo, employeeNo, employeeNo + "@example.test", PASSWORD_ENCODER.encode(PASSWORD));
    }

    private long project(String code) {
        return jdbc.queryForObject("""
                insert into projects (code, name, delivering_company, client_company, started_on, ends_on, phase, status)
                values (?, 'Visibility Project', 'Synthetic Delivery', 'Synthetic Client', '2026-01-01', '2026-12-31', 'Build', 'ON_TRACK')
                returning id
                """, Long.class, code);
    }

    private void member(String name, String role, int joinedDaysAgo) {
        long id = user(name);
        users.put(name, id);
        jdbc.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, cast(? as project_role), current_date - ?)",
                projectId, id, role, joinedDaysAgo);
    }

    private long connection(Long ownerId) {
        return connectionIn(projectId, ownerId);
    }

    private long connectionIn(long project, Long ownerId) {
        return jdbc.queryForObject("""
                insert into source_connections (project_id, owner_user_id, channel, display_name, external_ref, status, health)
                values (?, ?, cast(? as source_channel), 'Visibility source', 'fixture://visibility', 'ACTIVE', 'NORMAL')
                returning id
                """, Long.class, project, ownerId, ownerId == null ? "EMAIL" : "CLOUD_PROJECT_FOLDER");
    }

    private long source(long connectionId, String document) {
        return sourceIn(projectId, connectionId, document);
    }

    private long sourceIn(long project, long connectionId, String document) {
        return jdbc.queryForObject("""
                insert into record_sources (source_connection_id, display_name, external_ref, collected_at, project_id,
                                            external_document_id, external_version, content_hash, source_body)
                values (?, 'Visibility file', 'fixture://visibility', now(), ?, ?, 'v1',
                        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'synthetic source body')
                returning id
                """, Long.class, connectionId, project, document);
    }

    private void record(String code, long sourceId, String trustStatus, String recordType, String occurredOn) {
        records.put(code, recordIn(projectId, code, sourceId, trustStatus, recordType, occurredOn));
    }

    private long recordIn(long project, String code, long sourceId, String trustStatus, String recordType, String occurredOn) {
        return jdbc.queryForObject("""
                insert into work_records (code, project_id, record_source_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_name, trust_status, confirmed_by, confirmed_at, source_version,
                                          extraction_index, search_text)
                select ?, ?, ?, cast(? as record_type), ?, 'synthetic summary', 'synthetic excerpt', cast(? as date),
                       'Source author', status, case when confirmed then ? end, case when confirmed then now() end, 'v1',
                       (select count(*) from work_records where record_source_id = ?), 'synthetic summary'
                from (select cast(? as record_trust_status) as status) given
                cross join lateral (select given.status in ('CONFIRMED', 'CORRECTED') as confirmed) state
                returning id
                """, Long.class, code, project, sourceId, recordType, code, occurredOn, users.get("pm"), sourceId, trustStatus);
    }

    private void noteRecord(String code, String occurredOn) {
        long author = users.get("member");
        long note = jdbc.queryForObject("""
                insert into personal_notes (code, project_id, author_id, body) values ('NOTE-VISIBILITY', ?, ?, 'private note body')
                returning id
                """, Long.class, projectId, author);
        records.put(code, jdbc.queryForObject("""
                insert into work_records (code, project_id, source_note_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_id, author_name, trust_status, confirmed_by, confirmed_at, search_text)
                values (?, ?, ?, 'CHANGE_DECISION', ?, 'selected summary', 'selected summary', cast(? as date), ?, 'Member',
                        'CONFIRMED', ?, now(), 'selected summary')
                returning id
                """, Long.class, code, projectId, note, code, occurredOn, author, author));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static List<String> sorted(List<String> codes) {
        return codes.stream().sorted().toList();
    }

    private jakarta.servlet.http.Cookie cookie(String value) {
        return new jakarta.servlet.http.Cookie("BATON_SESSION", value);
    }
}
