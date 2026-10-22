package com.projectbaton.handover;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.projectbaton.project.ProjectAccessPolicy;
import com.projectbaton.record.RecordQueryService;
import com.projectbaton.shared.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoverQueryService {
    /** 초안은 근거가 아니라 경고라서, 검토를 안내하는 데는 기록 목록 한 페이지면 충분하다. */
    private static final int UNCONFIRMED_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;
    private final HandoverReadinessCheck readiness;
    private final RecordQueryService records;

    public HandoverQueryService(JdbcTemplate jdbc, ProjectAccessPolicy projects, HandoverReadinessCheck readiness,
                                RecordQueryService records) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.readiness = readiness;
        this.records = records;
    }

    /**
     * 프로젝트 구성원은 현재 관리자이거나 지정된 두 담당자 가운데 한 명일 때만 프로젝트의 인수인계를 본다. 그래서
     * 일반 구성원은 빈 목록을 받고, S02는 이것으로 신규 담당자에게 아직 배정된 것이 없다고 알린다.
     */
    @Transactional(readOnly = true)
    public SummaryPage list(long actor, long projectId, PageRequest page) {
        projects.requireMember(actor, projectId);
        boolean manager = projects.isManager(actor, projectId);
        long total = jdbc.queryForObject("""
                select count(*) from handovers handover
                where handover.project_id=? and (? or handover.outgoing_owner_id=? or handover.incoming_owner_id=?)
                """, Long.class, projectId, manager, actor, actor);
        List<Summary> items = jdbc.query(summarySelect() + """
                where handover.project_id=? and (? or handover.outgoing_owner_id=? or handover.incoming_owner_id=?)
                order by handover.requested_at desc, handover.id desc
                limit ? offset ?
                """, (rs, row) -> summary(rs), projectId, manager, actor, actor, page.limit(), page.offset());
        return new SummaryPage(items, total);
    }

    /**
     * 요청, 재시도, 재생성의 202 본문에 쓸, 명령이 막 남긴 인수인계. 호출자는 명령에서 이미 인가를 받았다.
     */
    @Transactional(readOnly = true)
    public Summary summary(long handoverId) {
        return jdbc.queryForObject(summarySelect() + " where handover.id=?", (rs, row) -> summary(rs), handoverId);
    }

    @Transactional(readOnly = true)
    public Detail detail(long actor, long handoverId) {
        Summary summary = requireParticipant(actor, handoverId);
        return detail(actor, summary);
    }

    /** 패키지는 현재 관리자와 지정된 두 담당자만 읽는다(README §4.2). 일반 구성원은 403을 받는다. */
    @Transactional(readOnly = true)
    public Summary requireParticipant(long actor, long handoverId) {
        List<Summary> rows = jdbc.query(summarySelect() + " where handover.id=?", (rs, row) -> summary(rs), handoverId);
        if (rows.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        Summary summary = rows.getFirst();
        projects.requireMember(actor, summary.projectId());
        if (!projects.isManager(actor, summary.projectId()) && actor != summary.outgoingOwnerId() && actor != summary.incomingOwnerId()) {
            throw new HandoverAccessDeniedException();
        }
        return summary;
    }

    private Detail detail(long actor, Summary summary) {
        long handoverId = summary.id();
        List<String> scopeTypes = jdbc.queryForList(
                "select record_type::text from handover_scope_types where handover_id=? order by record_type::text", String.class, handoverId);
        List<String> blockers = "COMPLETED".equals(summary.status()) ? List.of() : readiness.blockers(
                new HandoverReadinessCheck.Target(handoverId, summary.projectId(), summary.incomingOwnerId(), summary.status(),
                        summary.generation(), summary.effectiveOn()), readiness.businessDay());
        // 충돌은 이 회차를 발행할 때 고정한 쌍에서 오므로 나중에 등록한 관계가 패키지가 보여 준 것을 바꾸지 못한다.
        // 초안은 아직 확정되지 않은 근거를 경고하는 것이어서 살아 있는 값을 쓴다.
        List<Conflict> conflicts = jdbc.query("""
                select relation.id, relation.version, relation.project_id, relation.from_record_id, relation.to_record_id,
                       relation.relation_type::text, relation.note, relation.withdrawn_at, relation.withdrawal_reason
                from handover_item_conflicts conflict
                join handover_generations generation on generation.id=conflict.generation_id
                join record_relations relation on relation.id=conflict.relation_id
                where generation.handover_id=? and generation.generation=?
                order by relation.id
                """, (rs, row) -> new Conflict(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getString(6), rs.getString(7), rs.getObject(8, java.time.OffsetDateTime.class), rs.getString(9)),
                handoverId, summary.generation());
        List<RecordQueryService.RecordView> unconfirmed = records.list(actor, summary.projectId(), scopeTypes, List.of("AUTO_DRAFT"),
                summary.scopeFrom(), summary.scopeTo(), UNCONFIRMED_LIMIT, 0, null).items();
        return jdbc.queryForObject("""
                select included_count, revision_comment, failure_reason, include_note_derived_records, ready_at, completed_at
                from handovers where id=?
                """, (rs, row) -> new Detail(summary, scopeTypes, rs.getBoolean(4), rs.getInt(1), rs.getString(2), rs.getString(3),
                conflicts, unconfirmed, blockers, rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class)), handoverId);
    }

    private String summarySelect() {
        return """
                select handover.id, handover.code, handover.project_id, handover.status::text, handover.outgoing_owner_id,
                       outgoing.name, handover.incoming_owner_id, incoming.name, handover.effective_on, handover.generation,
                       handover.version, handover.progress_percent, handover.requested_at, handover.scope_from, handover.scope_to
                from handovers handover
                join users outgoing on outgoing.id=handover.outgoing_owner_id
                join users incoming on incoming.id=handover.incoming_owner_id
                """;
    }

    private Summary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Summary(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                rs.getLong(7), rs.getString(8), rs.getObject(9, LocalDate.class), rs.getInt(10), rs.getLong(11), rs.getInt(12),
                rs.getObject(13, OffsetDateTime.class), rs.getObject(14, LocalDate.class), rs.getObject(15, LocalDate.class));
    }

    public record Summary(long id, String code, long projectId, String status, long outgoingOwnerId, String outgoingOwnerName,
                          long incomingOwnerId, String incomingOwnerName, LocalDate effectiveOn, int generation, long version,
                          int progressPercent, OffsetDateTime requestedAt, LocalDate scopeFrom, LocalDate scopeTo) { }
    /** 회차를 만든 뒤 철회된 관계는 패키지를 다시 만들 때까지 철회 정보와 함께 목록에 남는다. */
    public record Conflict(long id, long version, long projectId, long fromRecordId, long toRecordId, String relationType, String note,
                           java.time.OffsetDateTime withdrawnAt, String withdrawalReason) { }
    public record SummaryPage(List<Summary> items, long total) { }
    /**
     * revisionComment는 최신 REQUEST_REVISION 결정에 PM이 단 사유다. 저장만 하고 돌려주지 않아서 신규 담당자가 무엇을
     * 고쳐야 하는지 볼 수 없었다(FR-13).
     */
    public record Detail(Summary summary, List<String> scopeRecordTypes, boolean includeNoteDerivedRecords, int includedCount,
                         String revisionComment, String failureReason, List<Conflict> conflicts,
                         List<RecordQueryService.RecordView> unconfirmedRecords, List<String> approvalBlockers,
                         OffsetDateTime readyAt, OffsetDateTime completedAt) { }
    public static class HandoverAccessDeniedException extends RuntimeException { }
}
