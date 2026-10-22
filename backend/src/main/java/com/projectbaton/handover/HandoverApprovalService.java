package com.projectbaton.handover;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;

import com.projectbaton.audit.AuditService;
import com.projectbaton.project.ProjectAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoverApprovalService {
    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;
    private final HandoverReadinessCheck readiness;
    private final HandoverQueryService queries;
    private final AuditService audit;

    public HandoverApprovalService(JdbcTemplate jdbc, ProjectAccessPolicy projects, HandoverReadinessCheck readiness,
                                   HandoverQueryService queries, AuditService audit) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.readiness = readiness;
        this.queries = queries;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public void authorize(long actor, long handoverId) {
        Handover handover = handover(handoverId, false);
        requireManager(actor, handover.projectId());
    }

    @Transactional
    public HandoverQueryService.Detail decide(long actor, long handoverId, long expectedVersion, Decision decision, String comment) {
        Handover handover = handover(handoverId, false);
        // 잠금 순서는 project -> users -> memberships -> handover로 고정한다. 담당자 ID는 요청 뒤 바뀌지 않아서
        // handover 잠금 전에 읽어도 안전하고, 이 순서는 배정 관리와 같다.
        lockProject(handover.projectId());
        lockUsers(actor, handover.outgoingOwnerId(), handover.incomingOwnerId());
        lockMemberships(handover.projectId(), handover.outgoingOwnerId(), handover.incomingOwnerId());
        handover = handover(handoverId, true);
        requireManager(actor, handover.projectId());
        if (handover.version() != expectedVersion) throw new HandoverRequestService.HandoverStaleException();
        if ("COMPLETED".equals(handover.status())) {
            throw new HandoverRequestService.HandoverConflictException("A completed handover is immutable.");
        }
        LocalDate businessDay = readiness.businessDay();
        if (decision == Decision.REQUEST_REVISION) {
            requestRevision(actor, handover, comment);
        } else {
            List<String> blockers = readiness.blockers(new HandoverReadinessCheck.Target(handover.id(), handover.projectId(),
                    handover.incomingOwnerId(), handover.status(), handover.generation(), handover.effectiveOn()), businessDay);
            if (!blockers.isEmpty()) {
                throw new HandoverRequestService.HandoverConflictException("Approval is blocked: " + String.join(", ", blockers));
            }
            complete(actor, handover, businessDay);
        }
        return queries.detail(actor, handoverId);
    }

    private void requestRevision(long actor, Handover handover, String comment) {
        if (!"READY".equals(handover.status())) {
            throw new HandoverRequestService.HandoverConflictException("Revision can be requested only for a ready handover.");
        }
        jdbc.update("update handovers set status='REVIEW_REQUESTED', revision_comment=?, version=version+1 where id=?",
                comment, handover.id());
        audit.record(actor, "REQUEST_HANDOVER_REVISION", "handover", Long.toString(handover.id()), "SUCCESS", null);
    }

    private void complete(long actor, Handover handover, LocalDate businessDay) {
        jdbc.update("""
                update handovers set status='COMPLETED', approved_by=?, completed_at=now(), version=version+1
                where id=?
                """, actor, handover.id());
        // 기존 담당자의 접근은 승인한 업무일에 끝난다. 승인이 누군가의 접근을 늘려서는 안 되므로, 관리자가 이미 더
        // 이른 종료일을 정해 뒀다면 그대로 둔다.
        List<Long> ended = jdbc.queryForList("""
                update project_members set left_on=?, version=version+1
                where project_id=? and user_id=? and (left_on is null or left_on>?)
                returning id
                """, Long.class, businessDay, handover.projectId(), handover.outgoingOwnerId(), businessDay);
        // 이제 현재 담당자를 뜻하므로 신규 담당자에게도 enum 이름 OUTGOING_OWNER를 그대로 쓴다.
        List<Long> promoted = jdbc.queryForList("""
                update project_members set project_role='OUTGOING_OWNER', version=version+1
                where project_id=? and user_id=?
                returning id
                """, Long.class, handover.projectId(), handover.incomingOwnerId());
        audit.record(actor, "APPROVE_HANDOVER", "handover", Long.toString(handover.id()), "SUCCESS", null);
        for (Long memberId : ended) audit.record(actor, "TRANSITION_PROJECT_MEMBER", "project_member", Long.toString(memberId), "SUCCESS", null);
        for (Long memberId : promoted) audit.record(actor, "TRANSITION_PROJECT_MEMBER", "project_member", Long.toString(memberId), "SUCCESS", null);
    }

    private void requireManager(long actor, long projectId) {
        projects.requireManager(actor, projectId);
    }

    private Handover handover(long handoverId, boolean lock) {
        List<Handover> rows = jdbc.query("""
                select id, project_id, outgoing_owner_id, incoming_owner_id, status::text, generation, version, effective_on
                from handovers where id=?
                """ + (lock ? " for update" : ""), (rs, n) -> new Handover(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getString(5), rs.getInt(6), rs.getLong(7), rs.getObject(8, LocalDate.class)), handoverId);
        if (rows.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        return rows.getFirst();
    }

    private void lockProject(long projectId) {
        Long locked = jdbc.query("select id from projects where id=? for update", rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (locked == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
    }

    private void lockUsers(long... userIds) {
        TreeSet<Long> ordered = new TreeSet<>();
        for (long userId : userIds) ordered.add(userId);
        for (long userId : ordered) jdbc.queryForList("select id from users where id=? for share", Long.class, userId);
    }

    private void lockMemberships(long projectId, long outgoingOwnerId, long incomingOwnerId) {
        jdbc.queryForList("select id from project_members where project_id=? and user_id in (?,?) order by id for update",
                Long.class, projectId, outgoingOwnerId, incomingOwnerId);
    }

    public enum Decision { APPROVE, REQUEST_REVISION }
    private record Handover(long id, long projectId, long outgoingOwnerId, long incomingOwnerId, String status, int generation,
                            long version, LocalDate effectiveOn) { }
}
