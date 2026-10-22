package com.projectbaton.handover;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.projectbaton.assessment.DeterministicAssessmentFactory;
import com.projectbaton.jobs.JobLeaseService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class HandoverGenerationHandler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JobLeaseService jobs;
    private final TransactionTemplate transactions;
    private final DeterministicAssessmentFactory assessments;

    public HandoverGenerationHandler(JdbcTemplate jdbc, ObjectMapper objectMapper, JobLeaseService jobs,
                                     TransactionTemplate transactions, DeterministicAssessmentFactory assessments) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.jobs = jobs;
        this.transactions = transactions;
        this.assessments = assessments;
    }

    /**
     * README §5.2: worker가 패키지 작업을 가져가는 순간부터 인수인계는 GENERATING이다. 예전에는 발행 트랜잭션
     * 안에서만 바뀌어서 아무도 그 상태를 볼 수 없었고, 도중에 죽은 생성은 여전히 REQUESTED로 보였다. 낡은 작업(이전
     * 회차, 이미 다음 단계로 간 인수인계)은 아무것도 바꾸지 않는다. 다른 인수인계 전이처럼 버전을 올려서, REQUESTED일
     * 때 읽은 ETag는 생성이 시작되면 더는 맞지 않는다.
     */
    public void begin(JobLeaseService.JobContext context) {
        transactions.executeWithoutResult(status -> {
            Long projectId = jdbc.query("select project_id from handovers where id=?", rs -> rs.next() ? rs.getLong(1) : null, context.targetId());
            if (projectId == null) {
                return;
            }
            lockProject(projectId);
            int changed = jdbc.update("""
                    update handovers set status='GENERATING',version=version+1
                    where id=? and generation=? and status='REQUESTED'
                    """, context.targetId(), context.generation());
            if (changed == 1) {
                jdbc.update("""
                        update handover_generations set status='GENERATING'
                        where handover_id=? and generation=? and status='REQUESTED'
                        """, context.targetId(), context.generation());
            }
        });
    }

    /**
     * 현재 패키지 작업이 이 처리기 밖에서 FAILED로 끝난 인수인계(lease나 시도 소진, 두 시간 deadline)는 이대로면
     * REQUESTED나 GENERATING에 남는다. 재시도는 FAILED만, 재생성은 READY·REVIEW_REQUESTED·FAILED만 받고 새 요청은
     * 진행 중인 인수인계를 보므로, 프로젝트가 회복할 수 없었다. 인수인계와 그 회차는 작업의 실패 코드를 받고, 같은
     * 회차의 살아 있는 작업(진행 중인 재시도)이 있으면 그대로 둔다.
     * SourceStructuringHandler.releaseAbandonedSources와 같은 방식이다.
     */
    public void releaseAbandonedHandovers() {
        // 아직 패키지를 기다리는 소수의 인수인계에서 출발해 각각의 최신 작업을 jobs_kind_target_created_idx로 찾아서,
        // tick마다 드는 비용이 작업 이력과 함께 늘지 않는다.
        jdbc.update("""
                with latest as (
                    select waiting.id as target_id, job.generation, job.status, job.failure_code
                    from handovers waiting
                    cross join lateral (select generation, status, failure_code from jobs
                                        where kind='BUILD_PACKAGE' and target_id=waiting.id
                                        order by created_at desc, id desc limit 1) job
                    where waiting.status in ('REQUESTED','GENERATING')
                ), failed as (
                    update handovers handover
                    set status='FAILED', failure_reason=coalesce(latest.failure_code, 'GENERATION_ABANDONED'),
                        version=handover.version+1
                    from latest
                    where latest.target_id=handover.id and latest.generation=handover.generation and latest.status='FAILED'
                      and handover.status in ('REQUESTED','GENERATING')
                      and not exists(select 1 from jobs live where live.kind='BUILD_PACKAGE' and live.target_id=handover.id
                                     and live.generation=handover.generation and live.status in ('PENDING','RUNNING','RETRY_WAIT'))
                    returning handover.id, handover.generation
                )
                update handover_generations generation set status='FAILED'
                from failed
                where generation.handover_id=failed.id and generation.generation=failed.generation
                  and generation.status in ('REQUESTED','GENERATING')
                """);
    }

    public boolean execute(JobLeaseService.JobContext context) {
        if (context.generation() == null) {
            throw new IllegalArgumentException("BUILD_PACKAGE requires a generation.");
        }
        begin(context);
        PackagePlan plan;
        try {
            plan = plan(context);
        } catch (DeterministicAssessmentFactory.InsufficientAssessmentEvidenceException exception) {
            return Boolean.TRUE.equals(transactions.execute(status -> failInsufficientEvidence(context)));
        }
        return Boolean.TRUE.equals(transactions.execute(status -> publish(context, plan)));
    }

    private boolean failInsufficientEvidence(JobLeaseService.JobContext context) {
        Long projectId = jdbc.query("select project_id from handovers where id=?", rs -> rs.next() ? rs.getLong(1) : null, context.targetId());
        if (projectId == null) {
            return jobs.complete(context);
        }
        lockProject(projectId);
        Handover handover = handover(context.targetId());
        if (handover.generation() != context.generation() || !isBuildable(handover.status())) {
            return jobs.complete(context);
        }
        jdbc.update("""
                update handover_generations set status='FAILED'
                where handover_id=? and generation=? and status in ('REQUESTED','GENERATING')
                """, handover.id(), context.generation());
        jdbc.update("""
                update handovers set status='FAILED',failure_reason='INSUFFICIENT_EVIDENCE',version=version+1
                where id=? and generation=? and status in ('REQUESTED','GENERATING')
                """, handover.id(), context.generation());
        if (!jobs.fail(context, "INSUFFICIENT_EVIDENCE")) {
            throw new PackagePublishRejectedException();
        }
        return true;
    }

    private PackagePlan plan(JobLeaseService.JobContext context) {
        Handover handover = readHandover(context.targetId());
        if (handover == null || handover.generation() != context.generation() || !isBuildable(handover.status())) {
            return null;
        }
        Generation generation = readGeneration(handover.id(), context.generation());
        if (generation == null) {
            return null;
        }
        List<Snapshot> snapshots = snapshots(generation.id());
        Set<Long> replaced = replacedRecords(generation.id(), handover.projectId());
        return new PackagePlan(generation.id(), build(snapshots, handover.effectiveOn(), handover.incomingOwnerId()),
                assessments.plan(snapshots.stream().filter(snapshot -> !replaced.contains(snapshot.recordId())).map(snapshot -> new DeterministicAssessmentFactory.SnapshotFact(
                        snapshot.recordId(), snapshot.content().required("recordType").asText(),
                        snapshot.content().required("title").asText(), snapshot.content().required("summary").asText(),
                        LocalDate.parse(snapshot.content().required("occurredOn").asText()),
                        optionalDate(snapshot.content().get("dueOn")))).toList()));
    }

    private boolean publish(JobLeaseService.JobContext context, PackagePlan plan) {
        Long projectId = jdbc.query("select project_id from handovers where id=?", rs -> rs.next() ? rs.getLong(1) : null, context.targetId());
        if (projectId == null) {
            return jobs.complete(context);
        }
        lockProject(projectId);
        Handover handover = handover(context.targetId());
        if (plan == null || handover.generation() != context.generation() || !isBuildable(handover.status())) {
            return jobs.complete(context);
        }
        Generation generation = generation(handover.id(), context.generation());
        if (generation.id() != plan.generationId()) {
            return jobs.complete(context);
        }
        jdbc.update("update handovers set status='GENERATING' where id=? and status='REQUESTED'", handover.id());
        jdbc.update("update handover_generations set status='GENERATING' where id=? and status='REQUESTED'", generation.id());
        insert(generation.id(), handover.id(), handover.projectId(), plan.items());
        materializeConflicts(generation.id(), handover.projectId());
        assessments.persist(handover.id(), generation.id(), handover.generation(), plan.questions());
        jdbc.update("""
                update handover_generations set status='READY', published_at=now()
                where id=? and status='GENERATING'
                """, generation.id());
        jdbc.update("""
                update handovers set status='READY', ready_at=now(), progress_percent=100, version=version+1
                where id=? and generation=? and status='GENERATING'
                """, handover.id(), context.generation());
        if (!jobs.complete(context)) {
            throw new PackagePublishRejectedException();
        }
        return true;
    }

    /**
     * 이 snapshot에서 같은 snapshot의 다른 기록이 대체하는 기록. 평가가 더는 유효하지 않은 사실(대체된 오픈일 등)을
     * 물어서는 안 되므로 이 기록으로는 문항을 만들지 않는다. 기록은 패키지에 남고, 대체 쌍은 그 안에서 검토 항목이
     * 된다.
     */
    private Set<Long> replacedRecords(long generationId, long projectId) {
        return new HashSet<>(jdbc.queryForList("""
                select relation.to_record_id from record_relations relation
                join handover_snapshot_records source_snapshot on source_snapshot.generation_id=? and source_snapshot.work_record_id=relation.from_record_id
                join handover_snapshot_records target_snapshot on target_snapshot.generation_id=? and target_snapshot.work_record_id=relation.to_record_id
                where relation.project_id=? and relation.relation_type='SUPERSEDES' and relation.withdrawn_at is null
                """, Long.class, generationId, generationId, projectId));
    }

    private List<Snapshot> snapshots(long generationId) {
        return jdbc.query("""
                select work_record_id, content_json::text
                from handover_snapshot_records
                where generation_id=?
                order by (content_json->>'occurredOn')::date asc, work_record_id asc
                """, (rs, row) -> new Snapshot(rs.getLong(1), json(rs.getString(2))), generationId);
    }

    private List<Item> build(List<Snapshot> snapshots, LocalDate effectiveOn, long incomingOwnerId) {
        List<Item> items = new ArrayList<>();
        // 서버가 패키지에 쓰는 문구는 데모 모드의 패키지처럼 한국어 화면에서 읽힌다.
        items.add(new Item("OVERVIEW", null, "인수인계 개요",
                "확정 기록 " + snapshots.size() + "건의 스냅샷으로 구성했다.", true, null, null));
        for (Snapshot snapshot : snapshots) {
            String recordType = snapshot.content().required("recordType").asText();
            String title = snapshot.content().required("title").asText();
            String summary = snapshot.content().required("summary").asText();
            switch (recordType) {
                case "CHANGE_DECISION" -> items.add(item("KEY_DECISION", snapshot.recordId(), title, summary, true, null, null));
                case "SCOPE_EXCEPTION" -> items.add(item("CHANGE", snapshot.recordId(), title, summary, true, null, null));
                case "COMMITMENT" -> items.add(item("COMMITMENT", snapshot.recordId(), title, summary, true, null, null));
                case "SCHEDULE" -> items.add(item("SCHEDULE", snapshot.recordId(), title, summary, false, null, null));
                case "BLOCKER", "OPEN_ISSUE" -> {
                    items.add(item("OPEN_RISK", snapshot.recordId(), title, summary, true, null, null));
                    JsonNode dueOnNode = snapshot.content().get("dueOn");
                    LocalDate dueOn = dueOnNode == null || dueOnNode.isNull() || dueOnNode.asText().isBlank()
                            ? effectiveOn : LocalDate.parse(dueOnNode.asText());
                    items.add(item("FOLLOW_UP", snapshot.recordId(), title, "후속 확인: " + summary,
                            true, incomingOwnerId, dueOn));
                }
                default -> throw new UnsupportedSnapshotRecordTypeException(recordType);
            }
        }
        return items;
    }

    private Item item(String kind, long recordId, String title, String body, boolean required, Long assigneeId, LocalDate dueOn) {
        return new Item(kind, recordId, title, body, required, assigneeId, dueOn);
    }

    private void insert(long generationId, long handoverId, long projectId, List<Item> items) {
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            jdbc.update("""
                    insert into handover_items(generation_id,handover_id,project_id,item_kind,work_record_id,title,body,display_order,is_required,assignee_id,due_on)
                    values(?,?,?,cast(? as handover_item_kind),?,?,?,?,?,?,?)
                    """, generationId, handoverId, projectId, item.kind(), item.recordId(), item.title(), item.body(),
                    index + 1, item.required(), item.assigneeId(), item.dueOn());
        }
    }

    /**
     * README §5.2: 충돌 쌍과 한 기록이 다른 기록을 대체하는 쌍은 둘 다 PM이 승인 전에 해소하는 검토 항목이다.
     * 예전에는 대체 쌍을 빼서, 패키지가 대체된 기록을 더는 유효하지 않다는 표시 없이 평범한 필수 항목으로 보여 줬다.
     * 쌍마다 자기 항목에서 해소하고, 항목은 관계의 출발 기록(대체 쌍이면 더 새로운 기록)에 붙는다. 해소는 항목마다
     * 충돌을 정확히 하나만 받으므로, 여러 쌍의 출발 기록은 첫 쌍(관계 id 순)에 자기 항목을 쓰고 나머지 쌍마다 그
     * 항목의 사본을 패키지 끝에 받는다.
     */
    private void materializeConflicts(long generationId, long projectId) {
        List<long[]> pairs = jdbc.query("""
                select relation.id, relation.from_record_id, source_snapshot.id, target_snapshot.id
                from record_relations relation
                join handover_snapshot_records source_snapshot on source_snapshot.generation_id=? and source_snapshot.work_record_id=relation.from_record_id
                join handover_snapshot_records target_snapshot on target_snapshot.generation_id=? and target_snapshot.work_record_id=relation.to_record_id
                where relation.project_id=? and relation.relation_type in ('CONFLICTS_WITH','SUPERSEDES') and relation.withdrawn_at is null
                order by relation.from_record_id, relation.id
                """, (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, generationId, generationId, projectId);
        long previousSource = -1;
        int slot = 0;
        for (long[] pair : pairs) {
            slot = pair[1] == previousSource ? slot + 1 : 0;
            previousSource = pair[1];
            Long item = slot == 0 ? sourceItem(generationId, pair[1]) : copySourceItem(generationId, pair[1], slot);
            if (item == null) continue;
            jdbc.update("""
                    insert into handover_item_conflicts(project_id,generation_id,handover_item_id,relation_id,from_snapshot_id,to_snapshot_id)
                    values(?,?,?,?,?,?) on conflict (generation_id,relation_id) do nothing
                    """, projectId, generationId, item, pair[0], pair[2], pair[3]);
        }
    }

    private Long sourceItem(long generationId, long recordId) {
        return jdbc.query("""
                select id from handover_items where generation_id=? and work_record_id=? and item_kind <> 'FOLLOW_UP' and conflict_slot=0
                order by display_order limit 1
                """, rs -> rs.next() ? rs.getLong(1) : null, generationId, recordId);
    }

    private Long copySourceItem(long generationId, long recordId, int slot) {
        return jdbc.query("""
                insert into handover_items(generation_id,handover_id,project_id,item_kind,work_record_id,title,body,display_order,is_required,conflict_slot)
                select source.generation_id, source.handover_id, source.project_id, source.item_kind, source.work_record_id, source.title,
                       source.body, (select max(display_order) + 1 from handover_items where generation_id=?), source.is_required, ?
                from handover_items source
                where source.id = (select id from handover_items where generation_id=? and work_record_id=? and item_kind <> 'FOLLOW_UP'
                                   and conflict_slot=0 order by display_order limit 1)
                returning id
                """, rs -> rs.next() ? rs.getLong(1) : null, generationId, slot, generationId, recordId);
    }

    private Handover handover(long handoverId) {
        List<Handover> handovers = jdbc.query("""
                select id, project_id, status::text, generation, incoming_owner_id, effective_on
                from handovers where id=? for update
                """, (rs, row) -> new Handover(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                rs.getLong(5), rs.getObject(6, LocalDate.class)), handoverId);
        if (handovers.isEmpty()) {
            throw new IllegalStateException("Handover disappeared after project lock.");
        }
        return handovers.getFirst();
    }

    private Handover readHandover(long handoverId) {
        List<Handover> handovers = jdbc.query("""
                select id, project_id, status::text, generation, incoming_owner_id, effective_on
                from handovers where id=?
                """, (rs, row) -> new Handover(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                rs.getLong(5), rs.getObject(6, LocalDate.class)), handoverId);
        return handovers.isEmpty() ? null : handovers.getFirst();
    }

    private Generation generation(long handoverId, int number) {
        List<Generation> generations = jdbc.query("""
                select id from handover_generations where handover_id=? and generation=? for update
                """, (rs, row) -> new Generation(rs.getLong(1)), handoverId, number);
        if (generations.isEmpty()) {
            throw new IllegalStateException("Current handover generation is missing.");
        }
        return generations.getFirst();
    }

    private Generation readGeneration(long handoverId, int number) {
        List<Generation> generations = jdbc.query("select id from handover_generations where handover_id=? and generation=?",
                (rs, row) -> new Generation(rs.getLong(1)), handoverId, number);
        return generations.isEmpty() ? null : generations.getFirst();
    }

    private void lockProject(long projectId) {
        Long locked = jdbc.query("select id from projects where id=? for update", rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (locked == null) {
            throw new IllegalStateException("Handover project is missing.");
        }
    }

    private JsonNode json(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid persisted handover snapshot.", exception);
        }
    }

    private LocalDate optionalDate(JsonNode value) {
        return value == null || value.isNull() || value.asText().isBlank() ? null : LocalDate.parse(value.asText());
    }

    private boolean isBuildable(String status) {
        return "REQUESTED".equals(status) || "GENERATING".equals(status);
    }

    private record Handover(long id, long projectId, String status, int generation, long incomingOwnerId, LocalDate effectiveOn) {
    }

    private record Generation(long id) {
    }

    private record Snapshot(long recordId, JsonNode content) {
    }

    private record Item(String kind, Long recordId, String title, String body, boolean required, Long assigneeId, LocalDate dueOn) {
    }

    private record PackagePlan(long generationId, List<Item> items, List<DeterministicAssessmentFactory.Question> questions) {
    }

    public static class PackagePublishRejectedException extends RuntimeException {
    }

    public static class UnsupportedSnapshotRecordTypeException extends RuntimeException {
        public UnsupportedSnapshotRecordTypeException(String recordType) {
            super(recordType);
        }
    }
}
