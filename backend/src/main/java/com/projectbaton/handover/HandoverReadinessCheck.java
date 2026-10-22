package com.projectbaton.handover;

import com.projectbaton.assessment.AssessmentService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 현재 회차에 대해 README §5.2의 완료 조건을 평가한다. 승인은 이 결과로 완료를 거절하고 상세 화면도 같은 코드를 보여
 * 줘서, 관리자는 승인이 무엇을 거절할지 그대로 본다.
 */
@Component
public class HandoverReadinessCheck {
    private final JdbcTemplate jdbc;
    private final AssessmentService assessments;

    public HandoverReadinessCheck(JdbcTemplate jdbc, AssessmentService assessments) {
        this.jdbc = jdbc;
        this.assessments = assessments;
    }

    public LocalDate businessDay() {
        // 업무 날짜는 Asia/Seoul이고 시각은 UTC로 저장한다. 모든 연결이 그 시간대로 시작해서(config/DatabasePools)
        // current_date는 서울 날짜이고, 트랜잭션의 날짜라서 자정 전후에도 적용일 확인과 기록된 left_on이 같은 달력
        // 날짜에 놓인다.
        return jdbc.queryForObject("select current_date", LocalDate.class);
    }

    /** 평가 조회가 기존 트랜잭션에만 참여하므로 트랜잭션 안에서 불러야 한다. */
    public List<String> blockers(Target handover, LocalDate businessDay) {
        List<String> blockers = new ArrayList<>();
        if (!"READY".equals(handover.status())) blockers.add("HANDOVER_NOT_READY");
        if (handover.effectiveOn().isAfter(businessDay)) blockers.add("EFFECTIVE_DATE_NOT_REACHED");
        if (!activeOn(handover.incomingOwnerId(), handover.projectId(), businessDay)) blockers.add("INCOMING_OWNER_INACTIVE");
        if (count("""
                select count(*) from handover_items item
                join handover_generations generation on generation.id=item.generation_id
                where item.handover_id=? and generation.generation=? and item.is_required
                  and not exists(select 1 from handover_item_acknowledgements acknowledgement
                                 where acknowledgement.generation_id=item.generation_id
                                   and acknowledgement.item_id=item.id and acknowledgement.user_id=?)
                """, handover.id(), handover.generation(), handover.incomingOwnerId()) > 0) {
            blockers.add("REQUIRED_ITEMS_UNACKNOWLEDGED");
        }
        if (count("""
                select count(*) from handover_item_conflicts conflict
                join handover_items item on item.id=conflict.handover_item_id
                join handover_generations generation on generation.id=conflict.generation_id
                join record_relations relation on relation.id=conflict.relation_id
                where item.handover_id=? and generation.generation=? and item.resolved_by_record_id is null
                  and relation.withdrawn_at is null
                """, handover.id(), handover.generation()) > 0) {
            blockers.add("CONFLICTS_UNRESOLVED");
        }
        // 이 회차를 만든 뒤 관계가 철회된 충돌 항목은 해소할 수 없다(회차와 그 평가는 고정되어 있다). 그 쌍을 뺀
        // 패키지를 다시 만들어야 하므로 따로 사유를 둔다.
        if (count("""
                select count(*) from handover_item_conflicts conflict
                join handover_generations generation on generation.id=conflict.generation_id
                join record_relations relation on relation.id=conflict.relation_id
                where generation.handover_id=? and generation.generation=? and relation.withdrawn_at is not null
                """, handover.id(), handover.generation()) > 0) {
            blockers.add("RELATIONS_WITHDRAWN");
        }
        if (count("""
                select count(*) from handover_items item
                join handover_generations generation on generation.id=item.generation_id
                where item.handover_id=? and generation.generation=? and item.item_kind='FOLLOW_UP'
                  and (item.assignee_id is distinct from ? or item.due_on is null)
                """, handover.id(), handover.generation(), handover.incomingOwnerId()) > 0) {
            blockers.add("FOLLOW_UP_UNASSIGNED");
        }
        Optional<AssessmentService.LatestResult> result =
                assessments.latestResult(handover.id(), handover.generation(), handover.incomingOwnerId());
        if (result.isEmpty()) blockers.add("ASSESSMENT_NOT_SUBMITTED");
        else if (!result.get().passed()) blockers.add("ASSESSMENT_NOT_PASSED");
        return blockers;
    }

    private boolean activeOn(long userId, long projectId, LocalDate businessDay) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                    select 1 from project_members member
                    join projects project on project.id=member.project_id
                    join users account on account.id=member.user_id
                    join organizations organization on organization.id=account.organization_id
                    where member.project_id=? and member.user_id=? and project.is_active and account.is_active
                      and organization.is_active and member.joined_on<=? and (member.left_on is null or ?<member.left_on)
                )
                """, Boolean.class, projectId, userId, businessDay, businessDay));
    }

    private int count(String sql, Object... parameters) {
        Integer value = jdbc.queryForObject(sql, Integer.class, parameters);
        return value == null ? 0 : value;
    }

    public record Target(long id, long projectId, long incomingOwnerId, String status, int generation, LocalDate effectiveOn) { }
}
