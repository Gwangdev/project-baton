package com.projectbaton.handover;

import java.util.List;

import com.projectbaton.project.ProjectAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoverConflictService {
    /**
     * handover_items.resolution_note의 폭(varchar(300)). 앱과 OAS는 500자를 허용해서 301~500자 메모가 검증을 통과한
     * 뒤 DB에서 500으로 실패했다. 이제 한도는 열의 폭이다.
     */
    static final int MAX_RESOLUTION_NOTE = 300;

    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;

    public HandoverConflictService(JdbcTemplate jdbc, ProjectAccessPolicy projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    @Transactional
    public ResolvedItem resolve(long actor, long handoverId, long itemId, long expectedVersion,
                                long resolvedByRecordId, String resolutionNote) {
        if (resolutionNote == null || resolutionNote.isBlank() || resolutionNote.length() > MAX_RESOLUTION_NOTE) {
            throw new InvalidResolutionException();
        }
        Handover handover = handover(handoverId, false);
        lockProject(handover.projectId());
        handover = handover(handoverId, true);
        projects.requireManager(actor, handover.projectId());
        if (!"READY".equals(handover.status()) && !"REVIEW_REQUESTED".equals(handover.status())) {
            throw new HandoverRequestService.HandoverConflictException("Conflict resolution is unavailable in the current handover state.");
        }
        Item item = item(handoverId, itemId);
        if (item.generation() != handover.generation()) {
            throw new HandoverRequestService.HandoverConflictException("Item is not in the current generation.");
        }
        if (item.version() != expectedVersion) throw new HandoverRequestService.HandoverStaleException();
        // 충돌은 한 번만 해소한다. 예전에는 두 번째 해소가 첫 해소를 조용히 덮어썼고, 신규 담당자가 이전 선택을
        // 확인한 기록은 그대로 남았다.
        if (item.resolvedByRecordId() != null) {
            throw new HandoverRequestService.HandoverConflictException("The conflict item is already resolved.");
        }

        List<Conflict> conflicts = jdbc.query("""
                select conflict.id, source_snapshot.work_record_id, target_snapshot.work_record_id, relation.relation_type::text,
                       relation.withdrawn_at is not null
                from handover_item_conflicts conflict
                join record_relations relation on relation.id=conflict.relation_id
                join handover_snapshot_records source_snapshot on source_snapshot.id=conflict.from_snapshot_id
                join handover_snapshot_records target_snapshot on target_snapshot.id=conflict.to_snapshot_id
                where conflict.project_id=? and conflict.generation_id=? and conflict.handover_item_id=?
                for update of conflict
                """, (rs, n) -> new Conflict(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getBoolean(5)),
                handover.projectId(), item.generationId(), item.id());
        if (conflicts.size() != 1) {
            throw new HandoverRequestService.HandoverConflictException("Item must represent exactly one unresolved conflict.");
        }
        Conflict conflict = conflicts.getFirst();
        // 패키지를 만든 뒤 이 항목의 관계가 철회됐다. 회차는 고정되어 있고 그 평가도 관계를 반영한 채라서, 해소하지
        // 않고 그 쌍을 뺀 패키지를 다시 만든다.
        if (conflict.withdrawn()) {
            throw new HandoverRequestService.HandoverConflictException("The relation of this conflict was withdrawn; rebuild the package.");
        }
        if (resolvedByRecordId != conflict.fromRecordId() && resolvedByRecordId != conflict.toRecordId()) {
            throw new HandoverRequestService.HandoverConflictException("The selected record is not a conflict candidate.");
        }
        // 대체 쌍은 이미 어느 기록이 유효한지 말한다. 더 새로운 기록, 즉 관계의 출발 기록이다. 이 회차의 평가는
        // 대체된 기록을 빼고 만들어서, 대체된 기록으로 해소하면 PM이 버린 사실을 패키지가 묻게 된다. 대체에 동의하지
        // 않는 PM은 관계를 철회하고 패키지를 다시 만든다.
        if ("SUPERSEDES".equals(conflict.relationType()) && resolvedByRecordId != conflict.fromRecordId()) {
            throw new HandoverRequestService.HandoverConflictException("A superseding pair is settled by the record that replaces the other.");
        }
        jdbc.update("""
                update handover_items
                set resolved_by_record_id=?, resolution_note=?, version=version+1
                where id=?
                """, resolvedByRecordId, resolutionNote, item.id());
        boolean acknowledged = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from handover_item_acknowledgements
                where generation_id=? and item_id=? and user_id=?)
                """, Boolean.class, item.generationId(), item.id(), actor));
        String resolvedByRecordCode = jdbc.queryForObject("select code from work_records where id=?", String.class, resolvedByRecordId);
        return new ResolvedItem(item.id(), item.kind(), item.title(), item.body(), item.displayOrder(), item.required(),
                handover.generation(), conflict.id(), List.of(conflict.fromRecordId(), conflict.toRecordId()),
                resolvedByRecordId, resolvedByRecordCode, resolutionNote, acknowledged, item.version() + 1);
    }

    @Transactional(readOnly = true)
    public void authorize(long actor, long handoverId) {
        Handover handover = handover(handoverId, false);
        projects.requireManager(actor, handover.projectId());
    }

    private Handover handover(long handoverId, boolean lock) {
        List<Handover> rows = jdbc.query("select project_id,status::text,generation from handovers where id=?" + (lock ? " for update" : ""),
                (rs, n) -> new Handover(rs.getLong(1), rs.getString(2), rs.getInt(3)), handoverId);
        if (rows.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        return rows.getFirst();
    }

    private Item item(long handoverId, long itemId) {
        return item(handoverId, itemId, true);
    }

    private Item item(long handoverId, long itemId, boolean lock) {
        List<Item> rows = jdbc.query("""
                select handover_items.id, handover_items.generation_id, handover_generations.generation,
                       handover_items.item_kind::text, handover_items.title, handover_items.body,
                       handover_items.display_order, handover_items.is_required, handover_items.version,
                       handover_items.resolved_by_record_id
                from handover_items join handover_generations on handover_generations.id=handover_items.generation_id
                where handover_items.handover_id=? and handover_items.id=?
                """ + (lock ? " for update of handover_items" : ""), (rs, n) -> new Item(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), rs.getBoolean(8), rs.getLong(9), rs.getObject(10, Long.class)), handoverId, itemId);
        if (rows.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        return rows.getFirst();
    }

    private void lockProject(long projectId) {
        Long locked = jdbc.query("select id from projects where id=? for update", rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (locked == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
    }

    private record Handover(long projectId, String status, int generation) { }
    private record Item(long id, long generationId, int generation, String kind, String title, String body,
                        int displayOrder, boolean required, long version, Long resolvedByRecordId) { }
    private record Conflict(long id, long fromRecordId, long toRecordId, String relationType, boolean withdrawn) { }
    public record ResolvedItem(long id, String itemKind, String title, String body, int displayOrder, boolean isRequired,
                               int generation, long conflictId, List<Long> candidateRecordIds, long resolvedByRecordId,
                               String resolvedByRecordCode, String resolutionNote, boolean acknowledged, long version) { }
    public static class InvalidResolutionException extends RuntimeException { }
}
