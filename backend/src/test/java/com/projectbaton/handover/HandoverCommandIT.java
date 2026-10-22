package com.projectbaton.handover;

import com.projectbaton.shared.BusinessCalendar;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
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

/**
 * 인수인계의 PM·담당자 명령을 HTTP로 돌린다: 요청, 재시도, 재생성, 충돌 해소, 항목 확인. 예전에는 재시도, 재생성,
 * 해소, 확인이 서비스 수준 시험만 있어서, 이 endpoint들의 If-Match, 멱등 재실행, 상태 코드, 응답 본문 계약을 확인하지
 * 않았다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HandoverCommandIT {
    private static final PasswordEncoder PASSWORDS = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final LocalDate TODAY = BusinessCalendar.today();

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired HandoverPackageWorker worker;

    private long organizationId;
    private long projectId;
    private long managerId;
    private long outgoingId;
    private long incomingId;
    private long noteId;

    @BeforeEach
    void reset() {
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations restart identity cascade");
        organizationId = jdbc.queryForObject("insert into organizations(name) values('Command Organization') returning id", Long.class);
        projectId = jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values(?,'Command Project','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id
                """, Long.class, "COMMAND-" + System.nanoTime());
        managerId = user("command-manager");
        outgoingId = user("command-outgoing");
        incomingId = user("command-incoming");
        long memberId = user("command-member");
        user("command-outsider");
        member(managerId, "PROJECT_MANAGER");
        member(outgoingId, "OUTGOING_OWNER");
        member(incomingId, "INCOMING_OWNER");
        member(memberId, "MEMBER");
        noteId = jdbc.queryForObject("insert into personal_notes(code,project_id,author_id,body) values(?,?,?,'synthetic') returning id",
                Long.class, "NOTE-COMMAND-" + System.nanoTime(), projectId, managerId);
    }

    @Test
    void anAcceptedRequestReturnsTheWholeHandoverAndDefaultsToIncludingSharedNotes() throws Exception {
        String session = signIn("command-manager");

        mockMvc.perform(post("/api/v1/projects/{projectId}/handovers", projectId).with(csrf()).cookie(cookie(session))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("2026-03-01", "2026-04-30", "[\"OPEN_ISSUE\"]", null)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.code", notNullValue()))
                .andExpect(jsonPath("$.projectId", is((int) projectId)))
                .andExpect(jsonPath("$.status", is("REQUESTED")))
                .andExpect(jsonPath("$.outgoingOwnerName", is("command-outgoing")))
                .andExpect(jsonPath("$.incomingOwnerName", is("command-incoming")))
                .andExpect(jsonPath("$.effectiveOn", is(TODAY.toString())))
                .andExpect(jsonPath("$.generation", is(1)));

        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select include_note_derived_records from handovers where project_id=?", Boolean.class, projectId));
    }

    @Test
    void anInvalidScopeIsABadRequestNotAServerError() throws Exception {
        String session = signIn("command-manager");

        mockMvc.perform(requestHandover(session, requestBody("2026-05-01", "2026-04-01", "[\"OPEN_ISSUE\"]", true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")));
        mockMvc.perform(requestHandover(session, requestBody("2026-03-01", "2026-04-30", "[\"NOT_A_TYPE\"]", true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")));
        mockMvc.perform(requestHandover(session, requestBody("2026-03-01", "2026-04-30", "[]", true)))
                .andExpect(status().isBadRequest());

        assertEquals(0, jdbc.queryForObject("select count(*) from handovers", Integer.class));
    }

    @Test
    void aScopeOverFiveThousandRecordsIsRejectedWithoutWritingAnything() throws Exception {
        bulkRecords(5001);
        String session = signIn("command-manager");

        mockMvc.perform(requestHandover(session, requestBody("2026-03-01", "2026-04-30", "[\"OPEN_ISSUE\"]", true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("SCOPE_TOO_LARGE")));
        assertEquals(0, jdbc.queryForObject("select count(*) from handovers", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from jobs", Integer.class));

        jdbc.update("delete from work_records where id=(select max(id) from work_records)");
        mockMvc.perform(requestHandover(session, requestBody("2026-03-01", "2026-04-30", "[\"OPEN_ISSUE\"]", true)))
                .andExpect(status().isAccepted());
        assertEquals(5000, jdbc.queryForObject("select included_count from handovers", Integer.class));
    }

    @Test
    void aMemberWithoutTheManagerRoleIsForbiddenAndAnOutsiderSeesNothing() throws Exception {
        long handover = failedHandover();
        long item = readyPackageItem();
        String member = signIn("command-member");
        String outsider = signIn("command-outsider");

        mockMvc.perform(requestHandover(member, requestBody("2026-03-01", "2026-04-30", "[\"OPEN_ISSUE\"]", true)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
        mockMvc.perform(requestHandover(outsider, requestBody("2026-03-01", "2026-04-30", "[\"OPEN_ISSUE\"]", true)))
                .andExpect(status().isNotFound());
        mockMvc.perform(retry(member, handover, "\"" + version(handover) + "\"", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(regenerate(member, handover, "\"" + version(handover) + "\"", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(retry(outsider, handover, "\"" + version(handover) + "\"", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        long readyHandover = handoverOf(item);
        mockMvc.perform(resolve(member, readyHandover, item, "\"0\"", UUID.randomUUID().toString(), candidate(item)))
                .andExpect(status().isForbidden());
    }

    @Test
    void retryNeedsTheCurrentVersionAndReplaysTheSameAnswerForTheSameKey() throws Exception {
        long handover = failedHandover();
        String session = signIn("command-manager");
        long current = version(handover);
        String key = UUID.randomUUID().toString();

        mockMvc.perform(retry(session, handover, null, key))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code", is("IF_MATCH_REQUIRED")));
        mockMvc.perform(retry(session, handover, "\"" + (current + 7) + "\"", UUID.randomUUID().toString()))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code", is("STALE_VERSION")));
        String first = mockMvc.perform(retry(session, handover, "\"" + current + "\"", key))
                .andExpect(status().isAccepted())
                .andExpect(header().string("ETag", "\"" + (current + 1) + "\""))
                .andExpect(jsonPath("$.status", is("REQUESTED")))
                .andExpect(jsonPath("$.outgoingOwnerName", is("command-outgoing")))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(retry(session, handover, "\"" + current + "\"", key))
                .andExpect(status().isAccepted()).andExpect(content().json(first, true));
        assertEquals(2, jdbc.queryForObject("select count(*) from jobs where kind='BUILD_PACKAGE' and target_id=?", Integer.class, handover));
    }

    @Test
    void regenerateNeedsTheCurrentVersionAndReplaysTheSameAnswerForTheSameKey() throws Exception {
        long handover = failedHandover();
        String session = signIn("command-manager");
        long current = version(handover);
        String key = UUID.randomUUID().toString();

        mockMvc.perform(regenerate(session, handover, null, key))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code", is("IF_MATCH_REQUIRED")));
        mockMvc.perform(regenerate(session, handover, "\"" + (current + 7) + "\"", UUID.randomUUID().toString()))
                .andExpect(status().isPreconditionFailed());
        String first = mockMvc.perform(regenerate(session, handover, "\"" + current + "\"", key))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.generation", is(2)))
                .andExpect(jsonPath("$.incomingOwnerName", is("command-incoming")))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(regenerate(session, handover, "\"" + current + "\"", key))
                .andExpect(status().isAccepted()).andExpect(content().json(first, true));
        assertEquals(2, jdbc.queryForObject("select generation from handovers where id=?", Integer.class, handover));
    }

    @Test
    void aResolvedConflictCannotBeResolvedAgain() throws Exception {
        long item = readyPackageItem();
        long handover = handoverOf(item);
        String session = signIn("command-manager");

        mockMvc.perform(resolve(session, handover, item, null, UUID.randomUUID().toString(), candidate(item)))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(resolve(session, handover, item, "\"0\"", UUID.randomUUID().toString(), candidate(item)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.resolvedByRecordCode", is(jdbc.queryForObject(
                        "select code from work_records where id=?", String.class, candidate(item)))));
        mockMvc.perform(resolve(session, handover, item, "\"1\"", UUID.randomUUID().toString(), otherCandidate(item)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE")));
        assertEquals(candidate(item), jdbc.queryForObject("select resolved_by_record_id from handover_items where id=?", Long.class, item));
    }

    @Test
    void theIncomingOwnerAcknowledgesOnceAndLaterAcknowledgementsAreReplays() throws Exception {
        long item = readyPackageItem();
        long handover = handoverOf(item);
        String session = signIn("command-incoming");

        mockMvc.perform(acknowledge(session, handover, item, null, UUID.randomUUID().toString()))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(acknowledge(session, handover, item, "\"0\"", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.itemId", is((int) item)));
        mockMvc.perform(acknowledge(session, handover, item, "\"0\"", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        mockMvc.perform(acknowledge(signIn("command-manager"), handover, item, "\"0\"", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        assertEquals(1, jdbc.queryForObject("select count(*) from handover_item_acknowledgements where item_id=?", Integer.class, item));
    }

    @Test
    void itemsArePagedAndCarryTheirEvidenceAssigneeAndDueDate() throws Exception {
        long item = readyPackageItem();
        long handover = handoverOf(item);
        String session = signIn("command-incoming");
        int total = jdbc.queryForObject("select count(*) from handover_items where handover_id=?", Integer.class, handover);

        mockMvc.perform(get("/api/v1/handovers/{handoverId}/items", handover).param("limit", "2").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.page.total", is(total)))
                .andExpect(jsonPath("$.page.limit", is(2)));
        mockMvc.perform(get("/api/v1/handovers/{handoverId}/items", handover).param("itemKind", "FOLLOW_UP").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].assigneeName", is("command-incoming")))
                .andExpect(jsonPath("$.items[0].dueOn", notNullValue()))
                .andExpect(jsonPath("$.items[0].evidence.code", notNullValue()))
                .andExpect(jsonPath("$.items[0].evidence.trustStatus", is("CONFIRMED")));
        mockMvc.perform(get("/api/v1/handovers/{handoverId}/items", handover).param("limit", "101").cookie(cookie(session)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theHandoverListIsPaged() throws Exception {
        failedHandover();
        String session = signIn("command-manager");

        mockMvc.perform(get("/api/v1/projects/{projectId}/handovers", projectId).param("limit", "1").cookie(cookie(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.page.total", is(1)))
                .andExpect(jsonPath("$.page.limit", is(1)));
        mockMvc.perform(get("/api/v1/projects/{projectId}/handovers", projectId).param("offset", "-1").cookie(cookie(session)))
                .andExpect(status().isBadRequest());
    }

    /**
     * 예전에는 멱등 재실행이 권한 확인을 건너뛰어서, 첫 호출 뒤 역할을 잃은 관리자에게도 저장된 202가 나갔다. 이제
     * 저장된 결과를 찾기 전에 확인해서, 재실행도 같은 사람의 새 요청처럼 답한다.
     */
    @Test
    void aReplayOfARequestByAManagerWhoLostTheRoleIsForbiddenAndAFormerMemberSeesNothing() throws Exception {
        String session = signIn("command-manager");
        String key = UUID.randomUUID().toString();
        String body = requestBody("2026-03-01", "2026-04-30", "[\"OPEN_ISSUE\"]", true);
        mockMvc.perform(requestHandover(session, body, key)).andExpect(status().isAccepted());

        changeRole(managerId, "MEMBER");
        mockMvc.perform(requestHandover(session, body, key))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("ACCESS_DENIED")));

        endMembership(managerId);
        mockMvc.perform(requestHandover(session, body, key)).andExpect(status().isNotFound());
        assertEquals(1, jdbc.queryForObject("select count(*) from handovers", Integer.class));
    }

    @Test
    void aReplayOfARetryByAManagerWhoLostTheRoleIsForbiddenAndAFormerMemberSeesNothing() throws Exception {
        long handover = failedHandover();
        String session = signIn("command-manager");
        String ifMatch = "\"" + version(handover) + "\"";
        String key = UUID.randomUUID().toString();
        mockMvc.perform(retry(session, handover, ifMatch, key)).andExpect(status().isAccepted());

        changeRole(managerId, "MEMBER");
        mockMvc.perform(retry(session, handover, ifMatch, key))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("ACCESS_DENIED")));

        endMembership(managerId);
        mockMvc.perform(retry(session, handover, ifMatch, key)).andExpect(status().isNotFound());
        assertEquals(2, jdbc.queryForObject("select count(*) from jobs where kind='BUILD_PACKAGE' and target_id=?", Integer.class, handover));
    }

    @Test
    void aReplayOfARegenerationByAManagerWhoLostTheRoleIsForbiddenAndAFormerMemberSeesNothing() throws Exception {
        long handover = failedHandover();
        String session = signIn("command-manager");
        String ifMatch = "\"" + version(handover) + "\"";
        String key = UUID.randomUUID().toString();
        mockMvc.perform(regenerate(session, handover, ifMatch, key)).andExpect(status().isAccepted());

        changeRole(managerId, "MEMBER");
        mockMvc.perform(regenerate(session, handover, ifMatch, key))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("ACCESS_DENIED")));

        endMembership(managerId);
        mockMvc.perform(regenerate(session, handover, ifMatch, key)).andExpect(status().isNotFound());
        assertEquals(2, jdbc.queryForObject("select generation from handovers where id=?", Integer.class, handover));
    }

    @Test
    void aRegenerationOverFiveThousandRecordsIsRejectedAndLeavesTheHandoverAsItWas() throws Exception {
        long handover = failedHandover();
        bulkRecords(5000);
        String session = signIn("command-manager");
        long before = version(handover);

        mockMvc.perform(regenerate(session, handover, "\"" + before + "\"", UUID.randomUUID().toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("SCOPE_TOO_LARGE")));
        assertEquals(before, version(handover));
        assertEquals(1, jdbc.queryForObject("select generation from handovers where id=?", Integer.class, handover));
        assertEquals(1, jdbc.queryForObject("select count(*) from handover_generations where handover_id=?", Integer.class, handover));
    }

    @Test
    void aResolutionNoteIsLimitedToWhatTheColumnHolds() throws Exception {
        long item = readyPackageItem();
        long handover = handoverOf(item);
        String session = signIn("command-manager");

        mockMvc.perform(resolve(session, handover, item, "\"0\"", UUID.randomUUID().toString(), candidate(item), "x".repeat(301)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")));
        mockMvc.perform(resolve(session, handover, item, "\"0\"", UUID.randomUUID().toString(), candidate(item), "x".repeat(300)))
                .andExpect(status().isOk());
        assertEquals(300, jdbc.queryForObject("select length(resolution_note) from handover_items where id=?", Integer.class, item));
    }

    @Test
    void aUserWhoIsNotAMemberCannotBeNamedAsAnOwner() throws Exception {
        String session = signIn("command-manager");
        long outsider = jdbc.queryForObject("select id from users where employee_no='command-outsider'", Long.class);
        String scope = "\"scopeFrom\":\"2026-03-01\",\"scopeTo\":\"2026-04-30\",\"scopeRecordTypes\":[\"OPEN_ISSUE\"]";

        mockMvc.perform(requestHandover(session, "{\"outgoingOwnerId\":" + outsider + ",\"incomingOwnerId\":" + incomingId
                        + ",\"effectiveOn\":\"" + TODAY + "\"," + scope + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")))
                .andExpect(jsonPath("$.errors[0].field", is("outgoingOwnerId")));
        mockMvc.perform(requestHandover(session, "{\"outgoingOwnerId\":" + outgoingId + ",\"incomingOwnerId\":987654321"
                        + ",\"effectiveOn\":\"" + TODAY + "\"," + scope + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field", is("incomingOwnerId")));
        mockMvc.perform(requestHandover(signIn("command-outsider"), requestBody("2026-03-01", "2026-04-30", "[\"OPEN_ISSUE\"]", true)))
                .andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("select count(*) from handovers", Integer.class));
    }

    @Test
    void sameOutgoingAndIncomingOwnerIsABadRequestNamingTheField() throws Exception {
        String session = signIn("command-manager");

        mockMvc.perform(requestHandover(session, "{\"outgoingOwnerId\":" + outgoingId + ",\"incomingOwnerId\":" + outgoingId
                        + ",\"effectiveOn\":\"" + TODAY + "\",\"scopeFrom\":\"2026-03-01\",\"scopeTo\":\"2026-04-30\","
                        + "\"scopeRecordTypes\":[\"OPEN_ISSUE\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")))
                .andExpect(jsonPath("$.errors[0].field", is("incomingOwnerId")));
        assertEquals(0, jdbc.queryForObject("select count(*) from handovers", Integer.class));
    }

    @Test
    void anIdOutsideTheIntegerRangeIsABadRequestNotAServerError() throws Exception {
        String session = signIn("command-manager");
        String scope = "\"scopeFrom\":\"2026-03-01\",\"scopeTo\":\"2026-04-30\",\"scopeRecordTypes\":[\"OPEN_ISSUE\"]";

        mockMvc.perform(requestHandover(session, "{\"outgoingOwnerId\":99999999999999999999,\"incomingOwnerId\":" + incomingId
                        + ",\"effectiveOn\":\"" + TODAY + "\"," + scope + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_INPUT")));
        mockMvc.perform(requestHandover(session, "{\"outgoingOwnerId\":1.5,\"incomingOwnerId\":" + incomingId
                        + ",\"effectiveOn\":\"" + TODAY + "\"," + scope + "}"))
                .andExpect(status().isBadRequest());
    }

    private long failedHandover() throws Exception {
        record("OPEN_ISSUE", "Only issue", "2026-04-01");
        String session = signIn("command-manager");
        String body = mockMvc.perform(requestHandover(session, requestBody("2026-03-01", "2026-04-30", "[\"OPEN_ISSUE\"]", true)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        long handover = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
        worker.runOnce();
        assertEquals("FAILED", jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover));
        return handover;
    }

    /** 첫 결정이 나중 결정과 충돌하는, 준비된 패키지를 가진 두 번째 프로젝트. */
    private long readyPackageItem() {
        long project = jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values(?,'Ready Project','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id
                """, Long.class, "COMMAND-READY-" + System.nanoTime());
        for (String[] membership : new String[][] {{"command-manager", "PROJECT_MANAGER"}, {"command-outgoing", "OUTGOING_OWNER"},
                {"command-incoming", "INCOMING_OWNER"}, {"command-member", "MEMBER"}}) {
            jdbc.update("""
                    insert into project_members(project_id,user_id,project_role,joined_on)
                    values(?,(select id from users where employee_no=?),cast(? as project_role),current_date)
                    """, project, membership[0], membership[1]);
        }
        long note = jdbc.queryForObject("insert into personal_notes(code,project_id,author_id,body) values(?,?,?,'synthetic') returning id",
                Long.class, "NOTE-READY-" + System.nanoTime(), project, managerId);
        long source = record(project, note, "CHANGE_DECISION", "Source decision", "2026-04-01");
        long target = record(project, note, "CHANGE_DECISION", "Target decision", "2026-04-02");
        record(project, note, "COMMITMENT", "Commitment", "2026-04-03");
        record(project, note, "BLOCKER", "Blocker", "2026-04-04");
        record(project, note, "OPEN_ISSUE", "Open issue", "2026-04-05");
        jdbc.update("insert into record_relations(project_id,from_record_id,to_record_id,relation_type) values(?,?,?,'CONFLICTS_WITH')",
                project, source, target);
        long handover = jdbc.queryForObject("""
                insert into handovers(code,project_id,requested_by,outgoing_owner_id,incoming_owner_id,effective_on,scope_from,scope_to)
                values(?,?,?,?,?,?,'2026-03-01','2026-04-30') returning id
                """, Long.class, "HO-READY-" + System.nanoTime(), project, managerId, outgoingId, incomingId, TODAY);
        for (String type : new String[] {"CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE"}) {
            jdbc.update("insert into handover_scope_types(handover_id,record_type) values(?,cast(? as record_type))", handover, type);
        }
        long generation = jdbc.queryForObject("""
                insert into handover_generations(handover_id,generation,project_id,created_by) values(?,1,?,?) returning id
                """, Long.class, handover, project, managerId);
        jdbc.update("""
                insert into handover_snapshot_records(generation_id,project_id,work_record_id,record_version,content_json,trust_status)
                select ?,wr.project_id,wr.id,wr.version,jsonb_build_object('id',wr.id,'title',wr.title,'summary',wr.summary,
                    'recordType',wr.record_type,'occurredOn',wr.occurred_on,'dueOn',wr.due_on),wr.trust_status
                from work_records wr where wr.project_id=?
                """, generation, project);
        jdbc.update("""
                insert into jobs(id,kind,target_id,generation,dedup_key,status,available_at,deadline_at)
                values(gen_random_uuid(),'BUILD_PACKAGE',?,1,?,'PENDING',now(),now()+interval '2 hours')
                """, handover, "build-package:" + handover + ":1");
        worker.runOnce();
        assertEquals("READY", jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover));
        return jdbc.queryForObject("""
                select item.id from handover_items item join handover_item_conflicts conflict on conflict.handover_item_id=item.id
                where item.handover_id=?
                """, Long.class, handover);
    }

    private long handoverOf(long item) {
        return jdbc.queryForObject("select handover_id from handover_items where id=?", Long.class, item);
    }

    private long candidate(long item) {
        return jdbc.queryForObject("""
                select snapshot.work_record_id from handover_item_conflicts conflict
                join handover_snapshot_records snapshot on snapshot.id=conflict.to_snapshot_id where conflict.handover_item_id=?
                """, Long.class, item);
    }

    private long otherCandidate(long item) {
        return jdbc.queryForObject("""
                select snapshot.work_record_id from handover_item_conflicts conflict
                join handover_snapshot_records snapshot on snapshot.id=conflict.from_snapshot_id where conflict.handover_item_id=?
                """, Long.class, item);
    }

    private long version(long handover) {
        return jdbc.queryForObject("select version from handovers where id=?", Long.class, handover);
    }

    private String requestBody(String from, String to, String types, Boolean includeNotes) {
        return "{\"outgoingOwnerId\":" + outgoingId + ",\"incomingOwnerId\":" + incomingId + ",\"effectiveOn\":\"" + TODAY
                + "\",\"scopeFrom\":\"" + from + "\",\"scopeTo\":\"" + to + "\",\"scopeRecordTypes\":" + types
                + (includeNotes == null ? "" : ",\"includeNoteDerivedRecords\":" + includeNotes) + "}";
    }

    private MockHttpServletRequestBuilder requestHandover(String session, String body) {
        return requestHandover(session, body, UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder requestHandover(String session, String body, String key) {
        return post("/api/v1/projects/{projectId}/handovers", projectId).with(csrf()).cookie(cookie(session))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void changeRole(long userId, String role) {
        jdbc.update("update project_members set project_role=cast(? as project_role) where project_id=? and user_id=?", role, projectId, userId);
    }

    private void endMembership(long userId) {
        jdbc.update("update project_members set joined_on=current_date-5,left_on=current_date where project_id=? and user_id=?", projectId, userId);
    }

    private MockHttpServletRequestBuilder retry(String session, long handover, String ifMatch, String key) {
        MockHttpServletRequestBuilder request = post("/api/v1/handovers/{handoverId}/retry", handover).with(csrf())
                .cookie(cookie(session)).header("Idempotency-Key", key);
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private MockHttpServletRequestBuilder regenerate(String session, long handover, String ifMatch, String key) {
        MockHttpServletRequestBuilder request = post("/api/v1/handovers/{handoverId}/generations", handover).with(csrf())
                .cookie(cookie(session)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopeFrom\":\"2026-03-01\",\"scopeTo\":\"2026-04-30\",\"scopeRecordTypes\":[\"OPEN_ISSUE\"],"
                        + "\"includeNoteDerivedRecords\":true}");
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private MockHttpServletRequestBuilder resolve(String session, long handover, long item, String ifMatch, String key, long selected) {
        return resolve(session, handover, item, ifMatch, key, selected, "Use the later decision.");
    }

    private MockHttpServletRequestBuilder resolve(String session, long handover, long item, String ifMatch, String key, long selected,
                                                  String note) {
        MockHttpServletRequestBuilder request = patch("/api/v1/handovers/{handoverId}/items/{itemId}", handover, item).with(csrf())
                .cookie(cookie(session)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolvedByRecordId\":" + selected + ",\"resolutionNote\":\"" + note + "\"}");
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private MockHttpServletRequestBuilder acknowledge(String session, long handover, long item, String ifMatch, String key) {
        MockHttpServletRequestBuilder request = post("/api/v1/handovers/{handoverId}/items/{itemId}/acknowledgements", handover, item)
                .with(csrf()).cookie(cookie(session)).header("Idempotency-Key", key);
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private void bulkRecords(int count) {
        jdbc.update("""
                insert into work_records(code,project_id,source_note_id,record_type,title,summary,original_excerpt,occurred_on,
                    author_id,author_name,trust_status,confirmed_by,confirmed_at,search_text)
                select 'REC-BULK-'||g,?,?,'OPEN_ISSUE','Bulk','summary','excerpt','2026-04-01',?,'Author','CONFIRMED',?,now(),'summary'
                from generate_series(1,?) g
                """, projectId, noteId, managerId, managerId, count);
    }

    private long record(String type, String title, String occurredOn) {
        return record(projectId, noteId, type, title, occurredOn);
    }

    private long record(long project, long note, String type, String title, String occurredOn) {
        return jdbc.queryForObject("""
                insert into work_records(code,project_id,source_note_id,record_type,title,summary,original_excerpt,occurred_on,
                    author_id,author_name,trust_status,confirmed_by,confirmed_at,search_text)
                values(?,?,?,cast(? as record_type),?,'summary','excerpt',cast(? as date),?,'Author','CONFIRMED',?,now(),'summary')
                returning id
                """, Long.class, "REC-CMD-" + System.nanoTime(), project, note, type, title, occurredOn, managerId, managerId);
    }

    private String signIn(String employeeNo) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue();
    }

    private long user(String employeeNo) {
        return jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,credential_hash)
                values(?,?,?,'Engineer',?,?) returning id
                """, Long.class, organizationId, employeeNo, employeeNo, employeeNo + "@example.test", PASSWORDS.encode(PASSWORD));
    }

    private void member(long userId, String role) {
        jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),current_date)",
                projectId, userId, role);
    }

    private Cookie cookie(String session) {
        return new Cookie("BATON_SESSION", session);
    }
}
