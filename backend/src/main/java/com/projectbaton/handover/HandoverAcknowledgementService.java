package com.projectbaton.handover;

import java.time.OffsetDateTime;
import java.util.List;
import com.projectbaton.project.ProjectAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoverAcknowledgementService {
    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;

    public HandoverAcknowledgementService(JdbcTemplate jdbc, ProjectAccessPolicy projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    @Transactional
    public Acknowledgement acknowledge(long actor, long handoverId, long itemId, long expectedVersion) {
        Handover handover = handover(handoverId, false);
        lock(handover.projectId());
        handover = handover(handoverId, true);
        projects.requireMember(actor, handover.projectId());
        if (actor != handover.incomingOwnerId()) throw new AccessDeniedException();
        if (!SetStatus.allowed(handover.status())) {
            throw new HandoverRequestService.HandoverConflictException("Acknowledgement is unavailable in the current handover state.");
        }
        Item item = item(handoverId, itemId, true);
        if (item.generation() != handover.generation()) {
            throw new HandoverRequestService.HandoverConflictException("Item is not in the current generation.");
        }
        if (item.version() != expectedVersion) throw new HandoverRequestService.HandoverStaleException();

        int currentGeneration = handover.generation();
        List<Acknowledgement> existing = jdbc.query("""
                select item_id, user_id, acknowledged_at
                from handover_item_acknowledgements
                where generation_id=? and item_id=? and user_id=?
                """, (rs, n) -> new Acknowledgement(rs.getLong(1), rs.getLong(2), currentGeneration,
                rs.getObject(3, OffsetDateTime.class), item.version(), false), item.generationId(), item.id(), actor);
        if (!existing.isEmpty()) return existing.getFirst();

        OffsetDateTime at = jdbc.queryForObject("""
                insert into handover_item_acknowledgements(project_id,generation_id,item_id,user_id)
                values(?,?,?,?) returning acknowledged_at
                """, OffsetDateTime.class, handover.projectId(), item.generationId(), item.id(), actor);
        return new Acknowledgement(item.id(), actor, currentGeneration, at, item.version(), true);
    }

    @Transactional(readOnly = true)
    public void authorize(long actor, long handoverId) {
        Handover handover = handover(handoverId, false);
        projects.requireMember(actor, handover.projectId());
        if (actor != handover.incomingOwnerId()) throw new AccessDeniedException();
    }

    private Handover handover(long id, boolean lock) {
        List<Handover> rows = jdbc.query("select project_id,incoming_owner_id,status::text,generation from handovers where id=?"
                        + (lock ? " for update" : ""),
                (rs, n) -> new Handover(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)), id);
        if (rows.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        return rows.getFirst();
    }

    private Item item(long handoverId, long itemId, boolean lock) {
        List<Item> rows = jdbc.query("""
                select handover_items.id, handover_items.generation_id, handover_generations.generation, handover_items.version
                from handover_items join handover_generations on handover_generations.id=handover_items.generation_id
                where handover_items.handover_id=? and handover_items.id=?
                """ + (lock ? " for update of handover_items" : ""),
                (rs, n) -> new Item(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getLong(4)), handoverId, itemId);
        if (rows.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        return rows.getFirst();
    }

    private void lock(long project) {
        Long locked = jdbc.query("select id from projects where id=? for update", rs -> rs.next() ? rs.getLong(1) : null, project);
        if (locked == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
    }

    private record Handover(long projectId, long incomingOwnerId, String status, int generation) { }
    private record Item(long id, long generationId, int generation, long version) { }
    public record Acknowledgement(long itemId, long userId, int generation, OffsetDateTime acknowledgedAt,
                                  long itemVersion, boolean created) { }
    private static class SetStatus { static boolean allowed(String status) { return "READY".equals(status) || "REVIEW_REQUESTED".equals(status); } }
    public static class AccessDeniedException extends RuntimeException { }
}
