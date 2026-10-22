package com.projectbaton.handover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.projectbaton.jobs.JobLeaseService;

@SpringBootTest
@Testcontainers
class HandoverPackageIT {
    private static final com.projectbaton.shared.PageRequest ALL = new com.projectbaton.shared.PageRequest(100, 0);
    private static final java.util.concurrent.atomic.AtomicInteger EXTRACTION_INDEX = new java.util.concurrent.atomic.AtomicInteger();
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired HandoverRequestService requests;
    @Autowired HandoverConflictService conflicts;
    @Autowired HandoverAcknowledgementService acknowledgements;
    @Autowired HandoverItemQueryService itemQueries;
    @Autowired HandoverReadinessCheck readiness;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired JobLeaseService leases;
    @Autowired ApplicationContext applicationContext;
    @Autowired com.projectbaton.record.RecordRelationService relations;
    @Autowired tools.jackson.databind.ObjectMapper json;
    private long organizationId;

    @BeforeEach
    void reset() {
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations restart identity cascade");
        organizationId = jdbc.queryForObject("insert into organizations(name) values('Package Organization') returning id", Long.class);
    }

    @Test
    void packageItemsRequireGenerationScopedOwnership() {
        long project = project("PACKAGE-A");
        long otherProject = project("PACKAGE-B");
        long manager = user("manager");
        long outgoing = user("outgoing");
        long incoming = user("incoming");
        long handover = jdbc.queryForObject("""
                insert into handovers(code,project_id,requested_by,outgoing_owner_id,incoming_owner_id,effective_on,scope_from,scope_to)
                values('HO-PACKAGE',?,?,?,?,?,?,?) returning id
                """, Long.class, project, manager, outgoing, incoming,
                java.time.LocalDate.of(2026, 9, 14), java.time.LocalDate.of(2026, 8, 20), java.time.LocalDate.of(2026, 9, 14));
        long generation = jdbc.queryForObject("""
                insert into handover_generations(handover_id,generation,project_id,created_by)
                values(?,1,?,?) returning id
                """, Long.class, handover, project, manager);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into handover_items(handover_id,generation_id,project_id,item_kind,title,body,display_order,is_required)
                values(?,?,?,'OVERVIEW','Overview','body',1,true)
                """, handover, generation, otherProject));
    }

    @Test
    void buildsOnlyFromSnapshotInDeterministicOrderAndPublishesAtomically() {
        Seed seed = seed();
        long schedule = record(seed, "SCHEDULE", "Milestone", "Original schedule", LocalDate.of(2026, 9, 1), null);
        long decision = record(seed, "CHANGE_DECISION", "Decision", "Original decision", LocalDate.of(2026, 8, 21), null);
        long commitment = record(seed, "COMMITMENT", "Commitment", "Original commitment", LocalDate.of(2026, 8, 22), null);
        long blocker = record(seed, "BLOCKER", "Blocker", "Original blocker", LocalDate.of(2026, 8, 23), LocalDate.of(2026, 9, 10));
        long openIssue = record(seed, "OPEN_ISSUE", "Open issue", "Original open issue", LocalDate.of(2026, 8, 24), null);
        long scope = record(seed, "SCOPE_EXCEPTION", "Scope", "Original scope", LocalDate.of(2026, 8, 25), null);
        long handover = request(seed);

        jdbc.update("update handover_snapshot_records set content_json=content_json-'dueOn' where work_record_id=?", blocker);
        jdbc.update("update work_records set summary='LIVE SECRET', due_on='2026-12-31' where project_id=?", seed.projectId());
        runWorker();

        List<String> kinds = jdbc.query("select item_kind::text from handover_items where handover_id=? order by display_order",
                (rs, row) -> rs.getString(1), handover);
        assertEquals(List.of("OVERVIEW", "KEY_DECISION", "COMMITMENT", "OPEN_RISK", "FOLLOW_UP", "OPEN_RISK", "FOLLOW_UP", "CHANGE", "SCHEDULE"), kinds);
        assertEquals("READY", jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover));
        assertEquals("READY", jdbc.queryForObject("select status::text from handover_generations where handover_id=? and generation=1", String.class, handover));
        assertEquals("SUCCEEDED", jdbc.queryForObject("select status from jobs where kind='BUILD_PACKAGE' and target_id=?", String.class, handover));
        long assessment = jdbc.queryForObject("select id from assessments where handover_id=? and generation=1", Long.class, handover);
        assertEquals(5, jdbc.queryForObject("select count(*) from assessment_questions where assessment_id=?", Integer.class, assessment));
        assertEquals(9, jdbc.queryForObject("select count(*) from handover_items where handover_id=?", Integer.class, handover));
        assertEquals(8, jdbc.queryForObject("select count(*) from handover_items where handover_id=? and is_required", Integer.class, handover));
        assertFalse(jdbc.queryForObject("select exists(select 1 from handover_items where handover_id=? and body like '%LIVE SECRET%')", Boolean.class, handover));
        assertTrue(jdbc.queryForObject("select exists(select 1 from handover_items where handover_id=? and body like '%Original decision%')", Boolean.class, handover));
        assertFollowUp(handover, blocker, seed.incomingId(), LocalDate.of(2026, 9, 14));
        assertFollowUp(handover, openIssue, seed.incomingId(), LocalDate.of(2026, 9, 14));
        // 화면이 한국어이고 데모 모드가 이 문구를 한국어로 쓰므로, 서버의 문구도 그와 맞아야 한다.
        assertEquals(List.of("인수인계 개요", "확정 기록 6건의 스냅샷으로 구성했다."), jdbc.queryForObject(
                "select array[title, body] from handover_items where handover_id=? and item_kind='OVERVIEW'",
                (rs, row) -> List.of((String[]) rs.getArray(1).getArray()), handover));
        assertEquals("후속 확인: Original blocker", jdbc.queryForObject(
                "select body from handover_items where handover_id=? and item_kind='FOLLOW_UP' and work_record_id=?", String.class, handover, blocker));
        List<String> prompts = jdbc.queryForList("select prompt from assessment_questions where assessment_id=? order by question_no", String.class, assessment);
        assertTrue(prompts.stream().allMatch(prompt -> prompt.startsWith("어떤 유형의 기록인가? ")), prompts.toString());
        List<String> choiceTexts = jdbc.queryForList("""
                select choice->>'text' from assessment_questions, jsonb_array_elements(choices) choice where assessment_id=?
                """, String.class, assessment);
        assertTrue(List.of("일정", "변경 결정", "고객 약속", "장애요인", "범위 예외", "미해결 결정").containsAll(choiceTexts), choiceTexts.toString());
        assertEquals(1, jdbc.queryForObject("select count(*) from handover_items where work_record_id=?", Integer.class, schedule));
        assertEquals(1, jdbc.queryForObject("select count(*) from handover_items where work_record_id=?", Integer.class, decision));
        assertEquals(1, jdbc.queryForObject("select count(*) from handover_items where work_record_id=?", Integer.class, commitment));
        assertEquals(1, jdbc.queryForObject("select count(*) from handover_items where work_record_id=?", Integer.class, scope));
    }

    @Test
    void staleLeasedGenerationCannotPublishOverTheCurrentGeneration() {
        Seed seed = seed();
        record(seed, "CHANGE_DECISION", "Decision", "Original decision", LocalDate.of(2026, 8, 21), null);
        long handover = request(seed);
        JobLeaseService.JobContext firstGeneration = leases.claim("BUILD_PACKAGE").orElseThrow();
        jdbc.update("update handovers set status='READY' where id=?", handover);
        requests.regenerate(seed.managerId(), handover, 0,
                new HandoverRequestService.Command(0, 0, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 8, 20),
                        LocalDate.of(2026, 9, 14), Set.of("CHANGE_DECISION"), true));

        execute(firstGeneration);

        assertEquals(2, jdbc.queryForObject("select generation from handovers where id=?", Integer.class, handover));
        assertEquals("REQUESTED", jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover));
        assertEquals(0, jdbc.queryForObject("select count(*) from handover_items where handover_id=?", Integer.class, handover));
        assertEquals("SUCCEEDED", jdbc.queryForObject("select status from jobs where id=?", String.class, firstGeneration.jobId()));
    }

    @Test
    void failsGenerationWithoutFiveCompleteSnapshotRecords() {
        Seed seed = seed();
        record(seed, "CHANGE_DECISION", "Decision", "Summary", LocalDate.of(2026, 8, 21), null);
        record(seed, "COMMITMENT", "Commitment", "Summary", LocalDate.of(2026, 8, 22), null);
        record(seed, "BLOCKER", "Blocker", "Summary", LocalDate.of(2026, 8, 23), null);
        record(seed, "OPEN_ISSUE", "Open issue", "Summary", LocalDate.of(2026, 8, 24), null);
        long handover = request(seed);

        runWorker();

        assertEquals("FAILED", jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover));
        assertEquals("INSUFFICIENT_EVIDENCE", jdbc.queryForObject("select failure_reason from handovers where id=?", String.class, handover));
        assertEquals("FAILED", jdbc.queryForObject("select status::text from handover_generations where handover_id=? and generation=1", String.class, handover));
        assertEquals("FAILED", jdbc.queryForObject("select status from jobs where kind='BUILD_PACKAGE' and target_id=?", String.class, handover));
        assertEquals(0, jdbc.queryForObject("select count(*) from handover_items where handover_id=?", Integer.class, handover));
        assertEquals(0, jdbc.queryForObject("select count(*) from assessments where handover_id=?", Integer.class, handover));
    }

    @Test
    void materializesSnapshotConflictAndManagerSelectsOnlyItsCandidate() {
        Seed seed = seed();
        long source = record(seed, "CHANGE_DECISION", "Source decision", "Source summary", LocalDate.of(2026, 8, 21), null);
        long target = record(seed, "CHANGE_DECISION", "Target decision", "Target summary", LocalDate.of(2026, 8, 22), null);
        record(seed, "COMMITMENT", "Commitment", "Summary", LocalDate.of(2026, 8, 23), null);
        record(seed, "BLOCKER", "Blocker", "Summary", LocalDate.of(2026, 8, 24), null);
        record(seed, "OPEN_ISSUE", "Open issue", "Summary", LocalDate.of(2026, 8, 25), null);
        jdbc.update("insert into record_relations(project_id,from_record_id,to_record_id,relation_type) values(?,?,?,'CONFLICTS_WITH')",
                seed.projectId(), source, target);
        long handover = request(seed);

        runWorker();

        long item = jdbc.queryForObject("select id from handover_items where handover_id=? and work_record_id=? and item_kind='KEY_DECISION'",
                Long.class, handover, source);
        assertEquals(1, jdbc.queryForObject("select count(*) from handover_item_conflicts where handover_item_id=?", Integer.class, item));
        HandoverConflictService.ResolvedItem resolved = conflicts.resolve(seed.managerId(), handover, item, 0, target, "Use the later confirmed decision.");
        assertEquals(target, resolved.resolvedByRecordId());
        assertEquals(1, resolved.version());
        assertEquals(target, jdbc.queryForObject("select resolved_by_record_id from handover_items where id=?", Long.class, item));
        HandoverItemQueryService.Item listed = itemQueries.list(seed.incomingId(), handover, "KEY_DECISION", ALL).items().getFirst();
        assertEquals(resolved.conflictId(), listed.conflictId());
        assertEquals(source, listed.fromRecordId());
        assertEquals(target, listed.toRecordId());
        assertThrows(HandoverRequestService.HandoverStaleException.class,
                () -> conflicts.resolve(seed.managerId(), handover, item, 0, source, "Stale request."));
    }

    /**
     * README §5.2는 대체 쌍도 충돌 쌍처럼 검토 항목으로 하고, 데모의 중심 장면도 대체 쌍이다(오픈일이 한 기록에서
     * 다음 기록으로 바뀐다). 예전에는 패키지가 두 기록을 모두 평범한 필수 항목으로 보여 줬고, 평가는 대체된 기록의
     * 날짜를 아직 유효한 것처럼 물었다.
     */
    @Test
    void aSupersedingPairIsAReviewItemAndTheReplacedRecordAsksNoQuestion() {
        Seed seed = seed();
        long replaced = record(seed, "SCHEDULE", "Opening date", "Opening on 15 October", LocalDate.of(2026, 8, 21), LocalDate.of(2026, 10, 15));
        long current = record(seed, "SCHEDULE", "Opening date moved", "Opening on 22 October", LocalDate.of(2026, 8, 26), LocalDate.of(2026, 10, 22));
        record(seed, "COMMITMENT", "Commitment", "Summary", LocalDate.of(2026, 8, 22), null);
        record(seed, "BLOCKER", "Blocker", "Summary", LocalDate.of(2026, 8, 23), null);
        record(seed, "OPEN_ISSUE", "Open issue", "Summary", LocalDate.of(2026, 8, 24), null);
        record(seed, "CHANGE_DECISION", "Decision", "Summary", LocalDate.of(2026, 8, 25), null);
        jdbc.update("insert into record_relations(project_id,from_record_id,to_record_id,relation_type) values(?,?,?,'SUPERSEDES')",
                seed.projectId(), current, replaced);
        long handover = request(seed);

        runWorker();

        assertEquals("READY", jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover));
        long item = jdbc.queryForObject("select id from handover_items where handover_id=? and work_record_id=? and item_kind='SCHEDULE'",
                Long.class, handover, current);
        assertEquals(1, jdbc.queryForObject("select count(*) from handover_item_conflicts where handover_item_id=?", Integer.class, item));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from assessment_questions question join assessments assessment on assessment.id=question.assessment_id
                where assessment.handover_id=? and question.linked_record_id=?
                """, Integer.class, handover, replaced));
        assertTrue(blockers(handover).contains("CONFLICTS_UNRESOLVED"));
        // 평가를 대체된 기록 없이 만들어서 대체된 기록을 고르면 PM이 버린 사실을 패키지가 묻게 되므로, 대체 쌍은 다른
        // 기록을 대체하는 기록으로 해소한다.
        assertThrows(HandoverRequestService.HandoverConflictException.class,
                () -> conflicts.resolve(seed.managerId(), handover, item, 0, replaced, "Keep the old date."));
        assertEquals(current, conflicts.resolve(seed.managerId(), handover, item, 0, current, "The later record moved the date.").resolvedByRecordId());
        assertFalse(blockers(handover).contains("CONFLICTS_UNRESOLVED"));
    }

    /**
     * 발행한 회차는 고정되어 있어서, 충돌 항목의 근거인 대체를 철회해도 그 회차는 바뀌지 않는다. 항목은 더 해소할 수
     * 없고 준비 확인은 선택 대신 새 패키지를 요구한다. 다시 만든 패키지에는 그 쌍의 항목이 없고 두 기록을 평범한
     * 근거로 다룬다.
     */
    @Test
    void aWithdrawnSupersessionBlocksItsGenerationUntilThePackageIsRebuiltWithoutThePair() {
        Seed seed = seed();
        long replaced = record(seed, "SCHEDULE", "Opening date", "Opening on 15 October", LocalDate.of(2026, 8, 21), LocalDate.of(2026, 10, 15));
        long current = record(seed, "SCHEDULE", "Opening date moved", "Opening on 22 October", LocalDate.of(2026, 8, 26), LocalDate.of(2026, 10, 22));
        record(seed, "COMMITMENT", "Commitment", "Summary", LocalDate.of(2026, 8, 22), null);
        record(seed, "BLOCKER", "Blocker", "Summary", LocalDate.of(2026, 8, 23), null);
        record(seed, "OPEN_ISSUE", "Open issue", "Summary", LocalDate.of(2026, 8, 24), null);
        record(seed, "CHANGE_DECISION", "Decision", "Summary", LocalDate.of(2026, 8, 25), null);
        long relation = jdbc.queryForObject("""
                insert into record_relations(project_id,from_record_id,to_record_id,relation_type) values(?,?,?,'SUPERSEDES') returning id
                """, Long.class, seed.projectId(), current, replaced);
        long handover = request(seed);
        runWorker();
        long item = jdbc.queryForObject("select id from handover_items where handover_id=? and work_record_id=? and item_kind='SCHEDULE'",
                Long.class, handover, current);

        relations.withdraw(seed.managerId(), relation, 0, json.readTree("{\"reason\":\"The move was never agreed.\"}"));

        List<String> blocked = blockers(handover);
        assertTrue(blocked.contains("RELATIONS_WITHDRAWN"), blocked.toString());
        assertFalse(blocked.contains("CONFLICTS_UNRESOLVED"), blocked.toString());
        assertThrows(HandoverRequestService.HandoverConflictException.class,
                () -> conflicts.resolve(seed.managerId(), handover, item, 0, current, "The later record moved the date."));

        long version = jdbc.queryForObject("select version from handovers where id=?", Long.class, handover);
        requests.regenerate(seed.managerId(), handover, version, new HandoverRequestService.Command(0, 0, LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 14),
                Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION"), true));
        runWorker();

        assertEquals(0, jdbc.queryForObject("""
                select count(*) from handover_item_conflicts conflict join handover_generations generation on generation.id=conflict.generation_id
                join handovers handover on handover.id=generation.handover_id and handover.generation=generation.generation
                where handover.id=?
                """, Integer.class, handover));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from handover_items item join handover_generations generation on generation.id=item.generation_id
                join handovers handover on handover.id=generation.handover_id and handover.generation=generation.generation
                where handover.id=? and item.work_record_id=? and item.item_kind='SCHEDULE'
                """, Integer.class, handover, replaced));
        blocked = blockers(handover);
        assertFalse(blocked.contains("RELATIONS_WITHDRAWN") || blocked.contains("CONFLICTS_UNRESOLVED"), blocked.toString());
        // 대체가 사라지면 이전 기록이 다시 유효해서, 다시 만든 평가는 그 기록을 물을 수 있다.
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from assessment_questions question join assessments assessment on assessment.id=question.assessment_id
                join handover_generations generation on generation.id=assessment.generation_id
                join handovers handover on handover.id=generation.handover_id and handover.generation=generation.generation
                where handover.id=? and question.linked_record_id=?
                """, Integer.class, handover, replaced));
    }

    /**
     * 새 기록 하나가 이전 기록 둘을 대체하거나, 하나를 대체하고 다른 하나와 충돌할 수 있다. 예전에는 모든 충돌 행이
     * 출발 기록의 항목 하나에 붙었고 해소는 충돌이 정확히 하나인 항목만 받아서, 그런 패키지는 승인할 수 없었다. 이제
     * 쌍마다 자기 항목을 받는다. 첫 쌍은 기록의 항목에, 다음 쌍은 그 사본에 붙는다.
     */
    @Test
    void aRecordThatIsTheSourceOfTwoRelationsGetsOneReviewItemPerPair() {
        Seed seed = seed();
        long firstOld = record(seed, "SCHEDULE", "Opening date", "Opening on 15 October", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 10, 15));
        long secondOld = record(seed, "SCHEDULE", "Soft launch date", "Soft launch on 8 October", LocalDate.of(2026, 8, 21), LocalDate.of(2026, 10, 8));
        long current = record(seed, "SCHEDULE", "Launch dates merged", "One launch on 22 October", LocalDate.of(2026, 8, 26), LocalDate.of(2026, 10, 22));
        record(seed, "COMMITMENT", "Commitment", "Summary", LocalDate.of(2026, 8, 22), null);
        record(seed, "BLOCKER", "Blocker", "Summary", LocalDate.of(2026, 8, 23), null);
        record(seed, "OPEN_ISSUE", "Open issue", "Summary", LocalDate.of(2026, 8, 24), null);
        record(seed, "CHANGE_DECISION", "Decision", "Summary", LocalDate.of(2026, 8, 25), null);
        for (long replaced : List.of(firstOld, secondOld)) {
            jdbc.update("insert into record_relations(project_id,from_record_id,to_record_id,relation_type) values(?,?,?,'SUPERSEDES')",
                    seed.projectId(), current, replaced);
        }
        long handover = request(seed);

        runWorker();

        assertEquals("READY", jdbc.queryForObject("select status::text from handovers where id=?", String.class, handover));
        List<Long> items = jdbc.queryForList("""
                select item.id from handover_items item
                where item.handover_id=? and item.work_record_id=? and item.item_kind='SCHEDULE' order by item.display_order
                """, Long.class, handover, current);
        assertEquals(2, items.size());
        for (long item : items) {
            assertEquals(1, jdbc.queryForObject("select count(*) from handover_item_conflicts where handover_item_id=?", Integer.class, item));
        }
        assertTrue(blockers(handover).contains("CONFLICTS_UNRESOLVED"));
        for (long item : items) {
            assertEquals(current, conflicts.resolve(seed.managerId(), handover, item, 0, current, "The merged date holds.").resolvedByRecordId());
        }
        assertFalse(blockers(handover).contains("CONFLICTS_UNRESOLVED"), blockers(handover).toString());
    }

    private List<String> blockers(long handoverId) {
        HandoverReadinessCheck.Target target = jdbc.queryForObject("""
                select id, project_id, incoming_owner_id, status::text, generation, effective_on from handovers where id=?
                """, (rs, row) -> new HandoverReadinessCheck.Target(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                rs.getInt(5), rs.getObject(6, LocalDate.class)), handoverId);
        // 확인이 승인의 트랜잭션(MANDATORY) 안에서 돌아서 시험이 트랜잭션을 연다.
        return transactions.execute(status -> readiness.blockers(target, readiness.businessDay()));
    }

    @Test
    void incomingOwnerAcknowledgesCurrentItemIdempotently() {
        Seed seed = seed();
        record(seed, "CHANGE_DECISION", "Decision", "Summary", LocalDate.of(2026, 8, 21), null);
        record(seed, "COMMITMENT", "Commitment", "Summary", LocalDate.of(2026, 8, 22), null);
        record(seed, "BLOCKER", "Blocker", "Summary", LocalDate.of(2026, 8, 23), null);
        record(seed, "OPEN_ISSUE", "Open issue", "Summary", LocalDate.of(2026, 8, 24), null);
        record(seed, "SCOPE_EXCEPTION", "Scope", "Summary", LocalDate.of(2026, 8, 25), null);
        long handover = request(seed);

        runWorker();

        long item = jdbc.queryForObject("select id from handover_items where handover_id=? order by display_order limit 1", Long.class, handover);
        HandoverAcknowledgementService.Acknowledgement created = acknowledgements.acknowledge(seed.incomingId(), handover, item, 0);
        HandoverAcknowledgementService.Acknowledgement replay = acknowledgements.acknowledge(seed.incomingId(), handover, item, 0);
        assertTrue(created.created());
        assertFalse(replay.created());
        assertEquals(created.acknowledgedAt(), replay.acknowledgedAt());
        assertEquals(1, jdbc.queryForObject("select count(*) from handover_item_acknowledgements where item_id=?", Integer.class, item));
        assertTrue(itemQueries.list(seed.incomingId(), handover, null, ALL).items().getFirst().acknowledged());
        assertThrows(HandoverAcknowledgementService.AccessDeniedException.class,
                () -> acknowledgements.acknowledge(seed.managerId(), handover, item, 0));
    }

    private void assertFollowUp(long handoverId, long recordId, long assigneeId, LocalDate dueOn) {
        List<Object[]> rows = jdbc.query("""
                select assignee_id,due_on from handover_items
                where handover_id=? and item_kind='FOLLOW_UP' and work_record_id=?
                """, (rs, row) -> new Object[] {rs.getLong(1), rs.getObject(2, LocalDate.class)}, handoverId, recordId);
        assertEquals(1, rows.size());
        assertEquals(assigneeId, rows.getFirst()[0]);
        assertEquals(dueOn, rows.getFirst()[1]);
    }

    private long request(Seed seed) {
        return requests.request(seed.managerId(), seed.projectId(), new HandoverRequestService.Command(
                seed.outgoingId(), seed.incomingId(), LocalDate.of(2026, 9, 14), LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 9, 14), Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION"), true)).id();
    }

    private Seed seed() {
        long project = project("HANDOVER-PACKAGE-" + System.nanoTime());
        long manager = user("manager-" + System.nanoTime());
        long outgoing = user("outgoing-" + System.nanoTime());
        long incoming = user("incoming-" + System.nanoTime());
        member(project, manager, "PROJECT_MANAGER");
        member(project, outgoing, "OUTGOING_OWNER");
        member(project, incoming, "INCOMING_OWNER");
        long connection = jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','fixture') returning id", Long.class, project);
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,'fixture',now(),?,'package-doc','v1','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','synthetic source') returning id
                """, Long.class, connection, project);
        return new Seed(project, manager, outgoing, incoming, source);
    }

    private long record(Seed seed, String type, String title, String summary, LocalDate occurredOn, LocalDate dueOn) {
        return jdbc.queryForObject("""
                insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,
                    author_name,due_on,trust_status,confirmed_by,confirmed_at,source_version,extraction_index,search_text)
                values(?,?,?,cast(? as record_type),?,?, 'private synthetic excerpt', ?, 'Synthetic author', ?, 'CONFIRMED', ?, now(), 'v1', ?, ?) returning id
                """, Long.class, "REC-PACKAGE-" + System.nanoTime(), seed.projectId(), seed.sourceId(), type, title, summary,
                occurredOn, dueOn, seed.managerId(), EXTRACTION_INDEX.getAndIncrement(), summary);
    }

    private void member(long projectId, long userId, String role) {
        jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),current_date)", projectId, userId, role);
    }

    private void execute(JobLeaseService.JobContext context) {
        try {
            Class<?> type = Class.forName("com.projectbaton.handover.HandoverGenerationHandler");
            Object handler = applicationContext.getBean(type);
            type.getMethod("execute", JobLeaseService.JobContext.class).invoke(handler, context);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("the package handler class is absent", exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError("package handler failed", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("package handler does not expose execute(JobContext)", exception);
        }
    }

    private void runWorker() {
        try {
            Class<?> type = Class.forName("com.projectbaton.handover.HandoverPackageWorker");
            Object worker = applicationContext.getBean(type);
            type.getMethod("runOnce").invoke(worker);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("the package worker class is absent", exception);
        } catch (InvocationTargetException exception) {
            throw new AssertionError("package worker failed", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("package worker does not expose runOnce()", exception);
        }
    }

    private long project(String code) {
        return jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values(?,?,'D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id
                """, Long.class, code, code);
    }

    private long user(String employeeNo) {
        return jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,credential_hash)
                values(?,?,?,?,?, 'x') returning id
                """, Long.class, organizationId, employeeNo, employeeNo, "Engineer", employeeNo + "@test");
    }

    private record Seed(long projectId, long managerId, long outgoingId, long incomingId, long sourceId) {
    }
}
