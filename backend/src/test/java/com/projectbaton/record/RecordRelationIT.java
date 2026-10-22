package com.projectbaton.record;

import static org.hamcrest.Matchers.is;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecordRelationIT {

    private static final PasswordEncoder PASSWORD_ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void setUp() {
        AuditRows.clear(jdbc);
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations restart identity cascade");
        jdbc.update("insert into organizations (name) values ('Relation Test Organization')");
    }

    @Test
    void pmCanCreateRelationsButCycleCrossProjectAndSymmetricDuplicateAreRejected() throws Exception {
        long pm = user("relation-pm");
        long member = user("relation-member");
        long project = project("REL-A");
        long otherProject = project("REL-B");
        member(project, pm, "PROJECT_MANAGER");
        member(project, member, "MEMBER");
        String session = signIn("relation-pm");
        String memberSession = signIn("relation-member");
        long first = record(project, pm, "REL-REC-1");
        long second = record(project, pm, "REL-REC-2");
        long third = record(project, pm, "REL-REC-4");
        long other = record(otherProject, pm, "REL-REC-3");

        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000001",
                        "{\"fromRecordId\":" + first + ",\"toRecordId\":" + second + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version", is(0)))
                .andExpect(jsonPath("$.relationType", is("SUPERSEDES")));
        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000001",
                        "{\"fromRecordId\":" + first + ",\"toRecordId\":" + second + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isCreated());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "select count(*) from record_relations where relation_type = 'SUPERSEDES'", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "select count(*) from audit_events where action = 'CREATE_RECORD_RELATION'", Integer.class));
        mockMvc.perform(create(project, memberSession, "73000000-0000-0000-0000-000000000006",
                        "{\"fromRecordId\":" + first + ",\"toRecordId\":" + second + ",\"relationType\":\"REFERENCES\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000002",
                        "{\"fromRecordId\":" + second + ",\"toRecordId\":" + first + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000003",
                        "{\"fromRecordId\":" + first + ",\"toRecordId\":" + other + ",\"relationType\":\"REFERENCES\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000004",
                        "{\"fromRecordId\":" + third + ",\"toRecordId\":" + second + ",\"relationType\":\"CONFLICTS_WITH\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fromRecordId", is((int) second)))
                .andExpect(jsonPath("$.toRecordId", is((int) third)));
        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000005",
                        "{\"fromRecordId\":" + second + ",\"toRecordId\":" + third + ",\"relationType\":\"CONFLICTS_WITH\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void relationsJoinOnlyRecordsAPersonConfirmed() throws Exception {
        long pm = user("relation-trust-pm");
        long project = project("REL-TRUST");
        member(project, pm, "PROJECT_MANAGER");
        String session = signIn("relation-trust-pm");
        long confirmed = record(project, pm, "REL-TRUST-1");
        long corrected = record(project, pm, "REL-TRUST-2");
        // 초안은 수집한 원문에서 온다. 공유 연동의 초안은 PM에게 보여서 규칙이 409로 답한다.
        long draft = sharedDraft(project, "REL-TRUST-3", "AUTO_DRAFT");
        long dismissed = sharedDraft(project, "REL-TRUST-4", "DISMISSED");
        jdbc.update("update work_records set trust_status='CORRECTED' where id=?", corrected);

        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000011",
                        "{\"fromRecordId\":" + confirmed + ",\"toRecordId\":" + draft + ",\"relationType\":\"REFERENCES\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000012",
                        "{\"fromRecordId\":" + dismissed + ",\"toRecordId\":" + confirmed + ",\"relationType\":\"CONFLICTS_WITH\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000013",
                        "{\"fromRecordId\":" + corrected + ",\"toRecordId\":" + confirmed + ",\"relationType\":\"REFERENCES\"}"))
                .andExpect(status().isCreated());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "select count(*) from record_relations where project_id = ?", Integer.class, project));
    }

    @Test
    void anotherMembersPrivateDraftIsNotFoundRatherThanUnconfirmed() throws Exception {
        long pm = user("relation-private-pm");
        long owner = user("relation-private-owner");
        long project = project("REL-PRIVATE");
        member(project, pm, "PROJECT_MANAGER");
        member(project, owner, "MEMBER");
        String session = signIn("relation-private-pm");
        long confirmed = record(project, pm, "REL-PRIV-1");
        long folder = jdbc.queryForObject("""
                insert into source_connections (project_id, owner_user_id, channel, display_name, status, health)
                values (?, ?, 'CLOUD_PROJECT_FOLDER', 'Private folder', 'ACTIVE', 'NORMAL') returning id
                """, Long.class, project, owner);
        long source = jdbc.queryForObject("""
                insert into record_sources (source_connection_id, display_name, collected_at, project_id, external_document_id,
                                            external_version, content_hash, source_body)
                values (?, 'private', now(), ?, 'private-doc', 'v1', 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', 'private')
                returning id
                """, Long.class, folder, project);
        long privateDraft = jdbc.queryForObject("""
                insert into work_records (code, project_id, record_source_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_name, trust_status, source_version, search_text)
                values ('REL-PRIV-DRAFT', ?, ?, 'OPEN_ISSUE', 'private', 'private', 'private', '2026-04-01', 'owner', 'AUTO_DRAFT', 'v1', 'private')
                returning id
                """, Long.class, project, source);

        // README §4.2: 다른 구성원의 비공개 초안은 PM에게 없는 기록이라서, 없는 기록처럼 답한다.
        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000031",
                        "{\"fromRecordId\":" + confirmed + ",\"toRecordId\":" + privateDraft + ",\"relationType\":\"REFERENCES\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRecordAlreadySupersededCannotBeSupersededByAnotherRecord() throws Exception {
        long pm = user("relation-supersede-pm");
        long project = project("REL-SUPERSEDE");
        member(project, pm, "PROJECT_MANAGER");
        String session = signIn("relation-supersede-pm");
        long older = record(project, pm, "REL-SUP-1");
        long first = record(project, pm, "REL-SUP-2");
        long second = record(project, pm, "REL-SUP-3");

        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000021",
                        "{\"fromRecordId\":" + first + ",\"toRecordId\":" + older + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(create(project, session, "73000000-0000-0000-0000-000000000022",
                        "{\"fromRecordId\":" + second + ",\"toRecordId\":" + older + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "select count(*) from record_relations where project_id = ? and relation_type = 'SUPERSEDES'", Integer.class, project));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/records/{recordId}", older)
                        .cookie(cookie(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supersededByCode", is("REL-SUP-2")));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/projects/{projectId}/records", project)
                        .cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code == 'REL-SUP-1')].supersededByCode").value(org.hamcrest.Matchers.contains("REL-SUP-2")));
    }

    /**
     * 관계는 만들 수만 있었고, 반대 방향의 대체는 순환이고, 대체된 기록은 다시 대체될 수 없어서 실수로 등록한 대체를
     * 바로잡을 길이 없었다. 그래서 PM이 틀렸다고 믿는 기록으로 인수인계를 해소해야 했다. 이제 PM이 사유와 함께
     * 철회한다. 행은 남고 이후의 모든 확인은 그 관계를 없는 것으로 본다.
     */
    @Test
    void aPmWithdrawsAWrongSupersessionWithAReasonAndCanThenRegisterTheReverse() throws Exception {
        long pm = user("relation-withdraw-pm");
        long member = user("relation-withdraw-member");
        user("relation-withdraw-outsider");
        long project = project("REL-WITHDRAW");
        member(project, pm, "PROJECT_MANAGER");
        member(project, member, "MEMBER");
        String session = signIn("relation-withdraw-pm");
        String memberSession = signIn("relation-withdraw-member");
        String outsiderSession = signIn("relation-withdraw-outsider");
        long older = record(project, pm, "REL-WD-1");
        long newer = record(project, pm, "REL-WD-2");
        mockMvc.perform(create(project, session, "74000000-0000-0000-0000-000000000001",
                        "{\"fromRecordId\":" + newer + ",\"toRecordId\":" + older + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isCreated());
        long relation = jdbc.queryForObject("select id from record_relations where project_id = ?", Long.class, project);
        String reason = "{\"reason\":\"The older record still holds; the newer one misread the minutes.\"}";

        mockMvc.perform(withdraw(relation, session, "74000000-0000-0000-0000-000000000002", null, reason))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(withdraw(relation, session, "74000000-0000-0000-0000-000000000003", "\"7\"", reason))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(withdraw(relation, memberSession, "74000000-0000-0000-0000-000000000004", "\"0\"", reason))
                .andExpect(status().isForbidden());
        mockMvc.perform(withdraw(relation, outsiderSession, "74000000-0000-0000-0000-000000000005", "\"0\"", reason))
                .andExpect(status().isNotFound());
        mockMvc.perform(withdraw(relation, session, "74000000-0000-0000-0000-000000000006", "\"0\"", "{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withdraw(relation, session, "74000000-0000-0000-0000-000000000007", "\"0\"",
                        "{\"reason\":\"" + "x".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(withdraw(relation, session, "74000000-0000-0000-0000-000000000008", "\"0\"", reason))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.withdrawnAt").isNotEmpty())
                .andExpect(jsonPath("$.withdrawalReason", is("The older record still holds; the newer one misread the minutes.")));
        mockMvc.perform(withdraw(relation, session, "74000000-0000-0000-0000-000000000008", "\"0\"", reason))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "select count(*) from audit_events where action = 'WITHDRAW_RECORD_RELATION'", Integer.class));
        mockMvc.perform(withdraw(relation, session, "74000000-0000-0000-0000-000000000009", "\"1\"", reason))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE")));
        org.junit.jupiter.api.Assertions.assertEquals(pm, jdbc.queryForObject(
                "select withdrawn_by from record_relations where id = ? and withdrawn_at is not null", Long.class, relation));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/records/{recordId}", older)
                        .cookie(cookie(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supersededByCode").doesNotExist())
                .andExpect(jsonPath("$.relations", org.hamcrest.Matchers.empty()));
        mockMvc.perform(create(project, session, "74000000-0000-0000-0000-000000000010",
                        "{\"fromRecordId\":" + older + ",\"toRecordId\":" + newer + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isCreated());
    }

    /** 실수로 대체된 기록은 잘못된 대체를 철회하면 올바른 기록이 다시 대체할 수 있다. */
    @Test
    void aWithdrawnSupersessionNoLongerCountsAsTheTargetsReplacement() throws Exception {
        long pm = user("relation-replace-pm");
        long project = project("REL-REPLACE");
        member(project, pm, "PROJECT_MANAGER");
        String session = signIn("relation-replace-pm");
        long older = record(project, pm, "REL-RP-1");
        long wrong = record(project, pm, "REL-RP-2");
        long right = record(project, pm, "REL-RP-3");
        mockMvc.perform(create(project, session, "76000000-0000-0000-0000-000000000001",
                        "{\"fromRecordId\":" + wrong + ",\"toRecordId\":" + older + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isCreated());
        long relation = jdbc.queryForObject("select id from record_relations where project_id = ?", Long.class, project);
        mockMvc.perform(withdraw(relation, session, "76000000-0000-0000-0000-000000000002", "\"0\"",
                        "{\"reason\":\"The wrong record was chosen as the replacement.\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(create(project, session, "76000000-0000-0000-0000-000000000003",
                        "{\"fromRecordId\":" + right + ",\"toRecordId\":" + older + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withdraw(long relationId, String session,
                                                                                                 String key, String ifMatch, String body) {
        var request = post("/api/v1/record-relations/{relationId}/withdrawal", relationId).with(csrf()).cookie(cookie(session))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private long sharedDraft(long projectId, String code, String trustStatus) {
        long connection = jdbc.queryForObject("""
                insert into source_connections (project_id, channel, display_name, status, health)
                values (?, 'EMAIL', ?, 'ACTIVE', 'NORMAL') returning id
                """, Long.class, projectId, "Shared " + code);
        long source = jdbc.queryForObject("""
                insert into record_sources (source_connection_id, display_name, collected_at, project_id, external_document_id,
                                            external_version, content_hash, source_body)
                values (?, 'shared', now(), ?, ?, 'v1', 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', 'shared')
                returning id
                """, Long.class, connection, projectId, code);
        return jdbc.queryForObject("""
                insert into work_records (code, project_id, record_source_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_name, trust_status, source_version, search_text)
                values (?, ?, ?, 'OPEN_ISSUE', 'draft', 'draft', 'draft', '2026-04-01', 'mail', cast(? as record_trust_status), 'v1', 'draft')
                returning id
                """, Long.class, code, projectId, source, trustStatus);
    }

    /**
     * 대체는 두 기록 가운데 어느 쪽이 유효한지 말하고, 충돌은 아직 정해지지 않았다고 말한다. 한 쌍이 둘 다 가지면 한
     * 패키지에 후보가 같은 충돌 항목이 두 개 생겨 서로 반대 기록으로 해소할 수 있었고, 후보로 항목의 관계를 찾는 충돌
     * 화면이 다른 관계를 철회할 수 있었다. 이제 둘 다 철회되지 않은 동안 한 쌍은 둘 가운데 최대 하나만 가진다. 참조는
     * 충돌 항목을 만들지 않으므로 그대로 허용한다.
     */
    @Test
    void onePairCarriesEitherASupersessionOrAConflictNotBoth() throws Exception {
        long pm = user("relation-pair-pm");
        long project = project("REL-PAIR");
        member(project, pm, "PROJECT_MANAGER");
        String session = signIn("relation-pair-pm");
        long older = record(project, pm, "REL-PAIR-1");
        long newer = record(project, pm, "REL-PAIR-2");
        long first = record(project, pm, "REL-PAIR-3");
        long second = record(project, pm, "REL-PAIR-4");

        mockMvc.perform(create(project, session, "75000000-0000-0000-0000-000000000001",
                        "{\"fromRecordId\":" + newer + ",\"toRecordId\":" + older + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(create(project, session, "75000000-0000-0000-0000-000000000002",
                        "{\"fromRecordId\":" + newer + ",\"toRecordId\":" + older + ",\"relationType\":\"CONFLICTS_WITH\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE")));
        mockMvc.perform(create(project, session, "75000000-0000-0000-0000-000000000003",
                        "{\"fromRecordId\":" + newer + ",\"toRecordId\":" + older + ",\"relationType\":\"REFERENCES\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(create(project, session, "75000000-0000-0000-0000-000000000004",
                        "{\"fromRecordId\":" + first + ",\"toRecordId\":" + second + ",\"relationType\":\"CONFLICTS_WITH\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(create(project, session, "75000000-0000-0000-0000-000000000005",
                        "{\"fromRecordId\":" + second + ",\"toRecordId\":" + first + ",\"relationType\":\"SUPERSEDES\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE")));
        org.junit.jupiter.api.Assertions.assertEquals(3, jdbc.queryForObject(
                "select count(*) from record_relations where project_id = ?", Integer.class, project));

        long supersession = jdbc.queryForObject("select id from record_relations where project_id = ? and relation_type = 'SUPERSEDES'",
                Long.class, project);
        mockMvc.perform(withdraw(supersession, session, "75000000-0000-0000-0000-000000000006", "\"0\"",
                        "{\"reason\":\"Neither record replaces the other; they disagree.\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(create(project, session, "75000000-0000-0000-0000-000000000007",
                        "{\"fromRecordId\":" + newer + ",\"toRecordId\":" + older + ",\"relationType\":\"CONFLICTS_WITH\"}"))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(long projectId, String session,
                                                                                               String key, String body) {
        return post("/api/v1/projects/{projectId}/record-relations", projectId).with(csrf()).cookie(cookie(session))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private String signIn(String employeeNo) throws Exception { return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"synthetic-password\"}")).andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue(); }
    private long user(String employeeNo) { return jdbc.queryForObject("insert into users (organization_id, employee_no, name, title, email, credential_hash) values ((select id from organizations where name = 'Relation Test Organization'), ?, ?, 'Engineer', ?, ?) returning id", Long.class, employeeNo, employeeNo, employeeNo + "@example.test", PASSWORD_ENCODER.encode("synthetic-password")); }
    private long project(String suffix) { return jdbc.queryForObject("insert into projects (code, name, delivering_company, client_company, started_on, ends_on, phase, status) values (?, 'Relation Project', 'Synthetic Delivery', 'Synthetic Client', '2026-01-01', '2026-12-31', 'Build', 'ON_TRACK') returning id", Long.class, suffix + '-' + System.nanoTime()); }
    private void member(long projectId, long userId, String role) { jdbc.update("insert into project_members (project_id, user_id, project_role, joined_on) values (?, ?, cast(? as project_role), current_date)", projectId, userId, role); }
    private long record(long projectId, long authorId, String code) { long note = jdbc.queryForObject("insert into personal_notes (code, project_id, author_id, body) values (?, ?, ?, 'synthetic') returning id", Long.class, "NOTE-" + code, projectId, authorId); return jdbc.queryForObject("insert into work_records (code, project_id, source_note_id, record_type, title, summary, original_excerpt, occurred_on, author_id, author_name, trust_status, confirmed_by, confirmed_at, search_text) values (?, ?, ?, 'OPEN_ISSUE', 'Relation record', 'summary', 'excerpt', '2026-04-01', ?, 'Author', 'CONFIRMED', ?, now(), 'summary') returning id", Long.class, code, projectId, note, authorId, authorId); }
    private jakarta.servlet.http.Cookie cookie(String value) { return new jakarta.servlet.http.Cookie("BATON_SESSION", value); }
}
