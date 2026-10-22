package com.projectbaton.handover;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.projectbaton.shared.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoverItemQueryService {
    private static final String ITEM_KINDS = "OVERVIEW|KEY_DECISION|CHANGE|COMMITMENT|OPEN_RISK|SCHEDULE|FOLLOW_UP";
    private final JdbcTemplate jdbc;
    private final HandoverQueryService handovers;

    public HandoverItemQueryService(JdbcTemplate jdbc, HandoverQueryService handovers) {
        this.jdbc = jdbc;
        this.handovers = handovers;
    }

    /**
     * 현재 회차의 항목을 한 페이지씩(contracts.md C01은 모든 목록을 제한한다). 근거 기록은 살아 있는 기록이 아니라 이
     * 회차의 snapshot에서 가져와서, 나중에 고쳐도 패키지가 인용한 내용은 바뀌지 않는다. 기록 코드와 작성자 이름만
     * 살아 있는 값을 join하고, 둘 다 비공개 정보가 아니다. FOLLOW_UP 항목은 승인에 필요한 담당자와 기한을
     * 담는다(README §5.2).
     */
    @Transactional(readOnly = true)
    public ItemPage list(long actor, long handoverId, String itemKind, PageRequest page) {
        HandoverQueryService.Summary handover = handovers.requireParticipant(actor, handoverId);
        if (itemKind != null && !itemKind.matches(ITEM_KINDS)) {
            throw new InvalidItemKindException();
        }
        String filter = itemKind == null ? "" : " and item.item_kind::text=?\n";
        List<Object> scope = new ArrayList<>(List.of(handoverId, handover.generation()));
        if (itemKind != null) {
            scope.add(itemKind);
        }
        long total = jdbc.queryForObject("""
                select count(*) from handover_items item
                join handover_generations generation on generation.id=item.generation_id
                where item.handover_id=? and generation.generation=?
                """ + filter, Long.class, scope.toArray());
        List<Object> parameters = new ArrayList<>();
        parameters.add(actor);
        parameters.addAll(scope);
        parameters.add(page.limit());
        parameters.add(page.offset());
        List<Item> items = jdbc.query("""
                select item.id, item.item_kind::text, item.title, item.body, item.display_order, item.is_required,
                       generation.generation, item.version, conflict.id, source_snapshot.work_record_id,
                       target_snapshot.work_record_id, resolved.code, item.resolution_note,
                       exists(select 1 from handover_item_acknowledgements acknowledgement
                              where acknowledgement.generation_id=item.generation_id
                                and acknowledgement.item_id=item.id and acknowledgement.user_id=?),
                       assignee.name, item.due_on,
                       evidence.work_record_id, evidence_record.code, item.project_id, evidence.content_json->>'recordType',
                       evidence.content_json->>'title', evidence.content_json->>'summary', evidence.content_json->>'occurredOn',
                       evidence.trust_status::text, evidence_record.author_name, evidence.record_version,
                       evidence.content_json->>'dueOn'
                from handover_items item
                join handover_generations generation on generation.id=item.generation_id
                left join handover_item_conflicts conflict on conflict.handover_item_id=item.id
                left join handover_snapshot_records source_snapshot on source_snapshot.id=conflict.from_snapshot_id
                left join handover_snapshot_records target_snapshot on target_snapshot.id=conflict.to_snapshot_id
                left join work_records resolved on resolved.id=item.resolved_by_record_id
                left join users assignee on assignee.id=item.assignee_id
                left join handover_snapshot_records evidence
                       on evidence.generation_id=item.generation_id and evidence.work_record_id=item.work_record_id
                left join work_records evidence_record on evidence_record.id=evidence.work_record_id
                where item.handover_id=? and generation.generation=?
                """ + filter + """
                order by item.display_order, item.id
                limit ? offset ?
                """, (rs, n) -> new Item(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getBoolean(6), rs.getInt(7), rs.getLong(8), rs.getObject(9, Long.class),
                rs.getObject(10, Long.class), rs.getObject(11, Long.class), rs.getString(12), rs.getString(13),
                rs.getBoolean(14), rs.getString(15), rs.getObject(16, LocalDate.class), evidence(rs)), parameters.toArray());
        return new ItemPage(items, total);
    }

    private Evidence evidence(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long recordId = rs.getObject(17, Long.class);
        if (recordId == null) {
            return null;
        }
        String dueOn = rs.getString(27);
        return new Evidence(recordId, rs.getString(18), rs.getLong(19), rs.getString(20), rs.getString(21), rs.getString(22),
                LocalDate.parse(rs.getString(23)), rs.getString(24), rs.getString(25), rs.getLong(26),
                dueOn == null || dueOn.isBlank() ? null : LocalDate.parse(dueOn));
    }

    public record ItemPage(List<Item> items, long total) { }

    public record Item(long id, String itemKind, String title, String body, int displayOrder, boolean isRequired,
                       int generation, long version, Long conflictId, Long fromRecordId, Long toRecordId,
                       String resolvedByRecordCode, String resolutionNote, boolean acknowledged,
                       String assigneeName, LocalDate dueOn, Evidence evidence) { }

    /** 항목이 인용한 snapshot의 WorkRecord 필드(OAS HandoverItem.evidence). */
    public record Evidence(long id, String code, long projectId, String recordType, String title, String summary,
                           LocalDate occurredOn, String trustStatus, String authorName, long version, LocalDate dueOn) { }

    public static class InvalidItemKindException extends RuntimeException { }
}
