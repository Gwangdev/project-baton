package com.projectbaton.project;

import java.time.LocalDate;
import java.util.List;

import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.PageMeta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 행위자의 현재 프로젝트를 읽는 모델. 모든 SQL에 구성원 조건이 들어가서 페이지 합계와 대시보드 값이 거르지 않은
 * 프로젝트 목록에서 나오는 일이 없다.
 */
@Service
public class ProjectQueryService {

    // "확인 대기 기록 수"는 타임라인이 이 행위자에게 trustStatus=AUTO_DRAFT로 보여 줄 초안의 수다. 예전에는 author_id가
    // 행위자인 초안을 셌는데, 구조화와 수동 복구는 초안에 author_id를 넣지 않아서 대시보드가 늘 0을 보였다. 타임라인과
    // 같은 조건을 쓰면 두 숫자가 구조적으로 맞는다. 파라미터는 행위자 id 하나이고, 프로젝트는 바깥 쿼리의 p.id에서 온다.
    private static final String PENDING_IN_PROJECT = "select count(*) from work_records wr cross join"
            + " (select cast(? as bigint) as actor_id, p.id as project_id) scope"
            + " where wr.trust_status = 'AUTO_DRAFT' and " + RecordVisibility.visible("wr", "scope");

    private static final String CURRENT_MEMBERSHIP = """
            pm.user_id = ?
              and p.is_active
              and u.is_active
              and o.is_active
              and pm.joined_on <= current_date
              and (pm.left_on is null or current_date < pm.left_on)
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProjectQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ProjectList list(long actorId, int limit, int offset) {
        validatePage(limit, offset);
        long total = accessibleProjectCount(actorId);
        List<ProjectView> items = jdbcTemplate.query("""
                select p.id, p.code, p.name, p.delivering_company, p.client_company, p.started_on, p.ends_on,
                       p.phase, p.status, p.is_active, p.version
                from projects p
                join project_members pm on pm.project_id = p.id
                join users u on u.id = pm.user_id
                join organizations o on o.id = u.organization_id
                where """ + " " + CURRENT_MEMBERSHIP + """
                order by p.created_at desc, p.id desc
                limit ? offset ?
                """, (rs, row) -> project(rs), actorId, limit, offset);
        return new ProjectList(items, new PageMeta(total, limit, offset, null));
    }

    public ProjectDetail detail(long actorId, long projectId) {
        List<ProjectDetail> details = jdbcTemplate.query("""
                select p.id, p.code, p.name, p.delivering_company, p.client_company, p.started_on, p.ends_on,
                       p.phase, p.status, p.is_active, p.version, pm.project_role,
                       (""" + PENDING_IN_PROJECT + """
                       ) as pending_record_count,
                       (select count(*) from work_records wr
                         where wr.project_id = p.id and wr.record_type = 'OPEN_ISSUE'
                           and wr.trust_status in ('CONFIRMED', 'CORRECTED')) as open_risk_count
                from projects p
                join project_members pm on pm.project_id = p.id
                join users u on u.id = pm.user_id
                join organizations o on o.id = u.organization_id
                where p.id = ? and """ + " " + CURRENT_MEMBERSHIP,
                (rs, row) -> detail(rs), actorId, projectId, actorId);
        if (details.isEmpty()) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
        return details.getFirst();
    }

    // 행위자가 열 수 있는 모든 프로젝트를 최신순으로. 요약은 그 전부의 확인 대기 수를 더하므로 공개 범위 규칙은
    // 요청마다 한 번 돈다(DashboardService). 처음 `cards`개 행만 보여 주므로 미해결 위험 수는 그 행에서만 구한다.
    // PostgreSQL은 CASE 안의 subquery를 그 분기에 닿은 행에서만 평가한다. 파라미터: actor(확인 대기 수), cards,
    // actor(구성원).
    static final String DASHBOARD_PROJECTS = """
            select p.id, p.code, p.name, p.delivering_company, p.client_company, p.started_on, p.ends_on,
                   p.phase, p.status, p.is_active, p.version, pm.project_role,
                   (""" + PENDING_IN_PROJECT + """
                   ) as pending_record_count,
                   case when row_number() over (order by p.created_at desc, p.id desc) <= ?
                        then (select count(*) from work_records wr
                               where wr.project_id = p.id and wr.record_type = 'OPEN_ISSUE'
                                 and wr.trust_status in ('CONFIRMED', 'CORRECTED'))
                        else 0 end as open_risk_count
            from projects p
            join project_members pm on pm.project_id = p.id
            join users u on u.id = pm.user_id
            join organizations o on o.id = u.organization_id
            where """ + " " + CURRENT_MEMBERSHIP + """
            order by p.created_at desc, p.id desc
            """;

    /** 대시보드용 행위자의 프로젝트. 미해결 위험 수는 처음 {@code cards}개에만 채운다. */
    public List<ProjectDetail> dashboardProjects(long actorId, int cards) {
        return jdbcTemplate.query(DASHBOARD_PROJECTS, (rs, row) -> detail(rs), actorId, cards, actorId);
    }

    public long accessibleProjectCount(long actorId) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from projects p
                join project_members pm on pm.project_id = p.id
                join users u on u.id = pm.user_id
                join organizations o on o.id = u.organization_id
                where """ + " " + CURRENT_MEMBERSHIP, Long.class, actorId);
    }

    public long activeAssignedHandoverCount(long actorId) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from handovers h
                join projects p on p.id = h.project_id
                join project_members pm on pm.project_id = p.id
                join users u on u.id = pm.user_id
                join organizations o on o.id = u.organization_id
                where h.status <> 'COMPLETED'
                  and (h.requested_by = ? or h.outgoing_owner_id = ? or h.incoming_owner_id = ?)
                  and """ + " " + CURRENT_MEMBERSHIP, Long.class, actorId, actorId, actorId, actorId);
    }

    /**
     * 다른 구성원의 개인 폴더는 소유자만 본다(README §4.2). 연동 목록은 이미 숨겼지만 이 미리보기는 숨기지 않아서,
     * 대시보드가 폴더 이름과 상태를 프로젝트 구성원 모두에게 보여 줬다.
     */
    public List<ConnectionHealth> memberConnectionHealth(long actorId) {
        return jdbcTemplate.query("""
                select sc.id, sc.project_id, sc.channel, sc.display_name, sc.status, sc.health, sc.version
                from source_connections sc
                join projects p on p.id = sc.project_id
                join project_members pm on pm.project_id = p.id
                join users u on u.id = pm.user_id
                join organizations o on o.id = u.organization_id
                where (sc.owner_user_id is null or sc.owner_user_id = ?) and """ + " " + CURRENT_MEMBERSHIP + """
                order by p.created_at desc, p.id desc, sc.id desc
                limit 20
                """, (rs, row) -> connection(rs), actorId, actorId);
    }

    public List<ConnectionHealth> allConnectionHealth() {
        return jdbcTemplate.query("""
                select sc.id, sc.project_id, sc.channel, sc.display_name, sc.status, sc.health, sc.version
                from source_connections sc
                join projects p on p.id = sc.project_id
                where p.is_active
                order by p.created_at desc, p.id desc, sc.id desc
                limit 20
                """, (rs, row) -> connection(rs));
    }

    private void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) {
            throw new InvalidInputException();
        }
    }

    private ProjectView project(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProjectView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("delivering_company"), rs.getString("client_company"),
                rs.getObject("started_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class),
                rs.getString("phase"), rs.getString("status"), rs.getBoolean("is_active"), rs.getLong("version"));
    }

    private ProjectDetail detail(java.sql.ResultSet rs) throws java.sql.SQLException {
        ProjectView project = project(rs);
        return new ProjectDetail(project.id(), project.code(), project.name(), project.deliveringCompany(), project.clientCompany(),
                project.startedOn(), project.endsOn(), project.phase(), project.status(), project.isActive(), project.version(),
                rs.getString("project_role"), rs.getLong("pending_record_count"), rs.getLong("open_risk_count"));
    }

    private ConnectionHealth connection(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConnectionHealth(rs.getLong("id"), rs.getLong("project_id"), rs.getString("channel"),
                rs.getString("display_name"), rs.getString("status"), rs.getString("health"), rs.getLong("version"));
    }

    public record ProjectView(long id, String code, String name, String deliveringCompany, String clientCompany,
                              LocalDate startedOn, LocalDate endsOn, String phase, String status, boolean isActive, long version) { }
    public record ProjectDetail(long id, String code, String name, String deliveringCompany, String clientCompany,
                                LocalDate startedOn, LocalDate endsOn, String phase, String status, boolean isActive, long version,
                                String myRole, long pendingRecordCount, long openRiskCount) { }
    public record ProjectList(List<ProjectView> items, PageMeta page) { }
    public record ConnectionHealth(long id, long projectId, String channel, String displayName, String status, String health, long version) { }
}
