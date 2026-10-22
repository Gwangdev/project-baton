package com.projectbaton.project;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProjectAccessPolicy {

    /**
     * 배정이 현재인 조건: 시작했고 아직 끝나지 않았으며 사용자와 조직이 활성 상태다. 접근 확인과 프로젝트 구성원이
     * 읽는 구성원 목록(ProjectRosterService)이 함께 쓰므로, 행동할 수 있는 사람과 목록에 보이는 사람이 어긋나지
     * 않는다. 별칭 pm(project_members), u(users), o(organizations)를 전제로 한다.
     */
    static final String CURRENT_MEMBERSHIP = """
            u.is_active and o.is_active and pm.joined_on <= current_date
            and (pm.left_on is null or current_date < pm.left_on)
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProjectAccessPolicy(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void requireMember(long actorId, long projectId) {
        if (!hasCurrentMembership(actorId, projectId, false)) {
            throw new ProjectNotFoundException();
        }
    }

    /**
     * README §4.2: 행위자가 볼 수 없는 프로젝트는 숨기고(404), 관리자 역할이 없는 현재 구성원에게는 그렇다고
     * 알린다(403). PM 명령은 모두 여기를 거친다. 예전에는 요청, 재시도, 재생성, 충돌 해소, 관계 등록이 그런
     * 구성원에게 404로 답하고 승인만 403으로 답했다.
     */
    public void requireManager(long actorId, long projectId) {
        requireMember(actorId, projectId);
        if (!hasCurrentMembership(actorId, projectId, true)) {
            throw new ManagerRequiredException();
        }
    }

    /** 행위자가 지정한 사용자에 대한 예·아니오. {@link #requireMember}는 행위자에 대해 404로 답한다. */
    public boolean isMember(long userId, long projectId) {
        return hasCurrentMembership(userId, projectId, false);
    }

    public boolean isManager(long actorId, long projectId) {
        return hasCurrentMembership(actorId, projectId, true);
    }

    private boolean hasCurrentMembership(long actorId, long projectId, boolean managerRequired) {
        Boolean permitted = jdbcTemplate.queryForObject("""
                select exists (
                    select 1
                    from project_members pm
                    join projects p on p.id = pm.project_id
                    join users u on u.id = pm.user_id
                    join organizations o on o.id = u.organization_id
                    where pm.project_id = ?
                      and pm.user_id = ?
                      and p.is_active
                      and (? = false or pm.project_role = 'PROJECT_MANAGER')
                      and
                """ + CURRENT_MEMBERSHIP + ")", Boolean.class, projectId, actorId, managerRequired);
        return Boolean.TRUE.equals(permitted);
    }

    public static class ProjectNotFoundException extends RuntimeException {
    }

    public static class ManagerRequiredException extends RuntimeException {
    }
}
