package com.projectbaton.project;

import java.time.LocalDate;
import java.util.List;

import com.projectbaton.shared.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로젝트 구성원이 읽는 구성원 목록(FR-10: 프로젝트 관리자가 기존 담당자와 신규 담당자를 지정한다). 관리자용
 * 목록(ProjectMemberService)은 끝난 배정까지 모두 시스템 관리자에게만 보여 줘서, 시스템 관리자가 아닌 PM은 사용자
 * id를 직접 입력해야 했다. 이 목록은 접근 정책과 같은 조건(ProjectAccessPolicy.CURRENT_MEMBERSHIP)으로 현재 구성원을
 * 같은 프로젝트의 현재 구성원에게 보여 준다. 응답은 관리자 목록과 같은 모양이고 이메일은 없다.
 */
@Service
public class ProjectRosterService {
    private static final String CURRENT_MEMBERS = """
            from project_members pm
            join users u on u.id = pm.user_id
            join organizations o on o.id = u.organization_id
            where pm.project_id = ? and
            """ + ProjectAccessPolicy.CURRENT_MEMBERSHIP;

    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy access;

    public ProjectRosterService(JdbcTemplate jdbc, ProjectAccessPolicy access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public ProjectMemberService.MemberList list(long actorId, long projectId, PageRequest page) {
        access.requireMember(actorId, projectId);
        long total = jdbc.queryForObject("select count(*) " + CURRENT_MEMBERS, Long.class, projectId);
        List<ProjectMemberService.MemberView> items = jdbc.query("""
                select pm.id, pm.user_id, u.name, u.title, pm.project_role, pm.joined_on, pm.left_on, pm.version
                """ + CURRENT_MEMBERS + " order by pm.id limit ? offset ?",
                (rs, n) -> new ProjectMemberService.MemberView(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"),
                        rs.getString("title"), rs.getString("project_role"), rs.getObject("joined_on", LocalDate.class),
                        rs.getObject("left_on", LocalDate.class), rs.getLong("version")),
                projectId, page.limit(), page.offset());
        return new ProjectMemberService.MemberList(items, page.meta(total));
    }
}
