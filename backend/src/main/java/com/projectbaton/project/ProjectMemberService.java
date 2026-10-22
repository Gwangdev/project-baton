package com.projectbaton.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.projectbaton.audit.AuditService;
import com.projectbaton.identity.SystemAdminAccessPolicy;
import com.projectbaton.shared.BusinessCalendar;
import com.projectbaton.shared.JsonIds;
import com.projectbaton.shared.PageMeta;
import com.projectbaton.shared.PageRequest;
import com.projectbaton.shared.ReferenceConflictException;
import com.projectbaton.shared.ReferenceConflictProblem;
import com.projectbaton.shared.StaleVersionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ProjectMemberService {
    private static final Set<String> ROLES = Set.of("OUTGOING_OWNER", "INCOMING_OWNER", "PROJECT_MANAGER", "MEMBER");
    private static final String KEEP_HISTORY_HINT = "배정을 종료해 업무 이력을 보존할 수 있습니다.";
    private static final String MEMBER_COLUMNS = """
            select pm.id, pm.user_id, u.name, u.title, pm.project_role, pm.joined_on, pm.left_on, pm.version
            from project_members pm join users u on u.id = pm.user_id
            """;

    private final JdbcTemplate jdbc;
    private final SystemAdminAccessPolicy access;
    private final AuditService audit;

    public ProjectMemberService(JdbcTemplate jdbc, SystemAdminAccessPolicy access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    public void requireAdmin(long actorId) {
        access.requireSystemAdmin(actorId);
    }

    /** 모든 목록처럼 페이지로 나눈다(contracts.md C01). 예전에는 프로젝트의 배정을 한꺼번에 모두 돌려줬다. */
    public MemberList list(long actorId, long projectId, PageRequest page) {
        requireAdmin(actorId);
        requireProject(projectId);
        long total = jdbc.queryForObject("select count(*) from project_members where project_id = ?", Long.class, projectId);
        List<MemberView> items = jdbc.query(MEMBER_COLUMNS + " where pm.project_id = ? order by pm.id limit ? offset ?",
                (rs, n) -> view(rs), projectId, page.limit(), page.offset());
        return new MemberList(items, page.meta(total));
    }

    /**
     * 사용자는 프로젝트마다 배정 행이 하나다(UNIQUE project_id, user_id). 그 배정이 끝났으면 사용자를 다시 추가할 때
     * 같은 행을 새 역할과 시작일로 되살린다. 예전에는 고유 키가 409로 답해서 돌아온 구성원을 다시 배정할 수 없었다.
     * 새 시작일은 이전 종료일보다 앞설 수 없고, 아직 현재인 배정은 그대로 409다.
     */
    @Transactional
    public MemberView create(long actorId, long projectId, JsonNode body) {
        lockProject(projectId);
        Command command = createCommand(body);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from users where id = ? and is_active)", Boolean.class, command.userId()))) {
            throw new MemberNotFoundException();
        }
        List<MemberView> existing = jdbc.query(MEMBER_COLUMNS + " where pm.project_id = ? and pm.user_id = ? for update of pm",
                (rs, n) -> view(rs), projectId, command.userId());
        if (!existing.isEmpty()) {
            return restore(actorId, existing.getFirst(), command);
        }
        try {
            long id = jdbc.queryForObject("""
                    insert into project_members (project_id, user_id, project_role, joined_on)
                    values (?, ?, cast(? as project_role), ?) returning id
                    """, Long.class, projectId, command.userId(), command.role(), command.joinedOn());
            MemberView created = find(id);
            audit.record(actorId, "CREATE_PROJECT_MEMBER", "project_member", Long.toString(id), "SUCCESS", null);
            return created;
        } catch (DataIntegrityViolationException duplicate) {
            throw new MemberConflictException("The user is already assigned to this project.");
        }
    }

    @Transactional
    public MemberView update(long actorId, long projectId, long memberId, long version, JsonNode body) {
        lockProject(projectId);
        Patch patch = patch(body);
        MemberView current = lock(projectId, memberId);
        if (current.version() != version) {
            throw new StaleVersionException();
        }
        if (patch.leftOn() != null && (patch.leftOn().isBefore(current.joinedOn()) || patch.leftOn().isAfter(BusinessCalendar.today()))) {
            throw new InvalidMemberInputException();
        }
        jdbc.update("""
                update project_members
                set project_role = cast(coalesce(?, project_role::text) as project_role), left_on = coalesce(?, left_on), version = version + 1
                where id = ? and version = ?
                """, patch.role(), patch.leftOn(), memberId, version);
        MemberView updated = find(memberId);
        audit.record(actorId, "UPDATE_PROJECT_MEMBER", "project_member", Long.toString(memberId), "SUCCESS", null);
        return updated;
    }

    @Transactional
    public void delete(long actorId, long projectId, long memberId, long version) {
        lockProject(projectId);
        MemberView current = lock(projectId, memberId);
        if (current.version() != version) {
            throw new StaleVersionException();
        }
        List<ReferenceConflictProblem.BlockingReference> references = references(projectId, current.userId());
        if (!references.isEmpty()) {
            throw new ReferenceConflictException(references);
        }
        try {
            jdbc.update("delete from project_members where id = ? and version = ?", memberId, version);
        } catch (DataIntegrityViolationException referenced) {
            throw new ReferenceConflictException(List.of(new ReferenceConflictProblem.BlockingReference("business_history", 1, KEEP_HISTORY_HINT)));
        }
        audit.record(actorId, "DELETE_PROJECT_MEMBER", "project_member", Long.toString(memberId), "SUCCESS", null);
    }

    private MemberView restore(long actorId, MemberView ended, Command command) {
        if (ended.leftOn() == null || ended.leftOn().isAfter(BusinessCalendar.today())) {
            throw new MemberConflictException("The user is already assigned to this project.");
        }
        if (command.joinedOn().isBefore(ended.leftOn())) {
            throw new InvalidMemberInputException();
        }
        jdbc.update("""
                update project_members
                set project_role = cast(? as project_role), joined_on = ?, left_on = null, version = version + 1
                where id = ?
                """, command.role(), command.joinedOn(), ended.id());
        MemberView restored = find(ended.id());
        audit.record(actorId, "RESTORE_PROJECT_MEMBER", "project_member", Long.toString(ended.id()), "SUCCESS", null);
        return restored;
    }

    private List<ReferenceConflictProblem.BlockingReference> references(long projectId, long userId) {
        Map<String, String> counts = new LinkedHashMap<>();
        counts.put("created_work_records", "select count(*) from work_records where project_id = ? and author_id = ?");
        counts.put("confirmed_work_records", "select count(*) from work_records where project_id = ? and confirmed_by = ?");
        counts.put("handover_requests", "select count(*) from handovers where project_id = ? and requested_by = ?");
        counts.put("handover_outgoing_owners", "select count(*) from handovers where project_id = ? and outgoing_owner_id = ?");
        counts.put("handover_recipients", "select count(*) from handovers where project_id = ? and incoming_owner_id = ?");
        counts.put("handover_approvals", "select count(*) from handovers where project_id = ? and approved_by = ?");
        counts.put("assessment_attempts", """
                select count(*) from assessment_attempts aa join assessments a on a.id = aa.assessment_id
                join handovers h on h.id = a.handover_id where h.project_id = ? and aa.taken_by = ?
                """);
        List<ReferenceConflictProblem.BlockingReference> references = new ArrayList<>();
        for (Map.Entry<String, String> entry : counts.entrySet()) {
            long count = jdbc.queryForObject(entry.getValue(), Long.class, projectId, userId);
            if (count > 0) {
                references.add(new ReferenceConflictProblem.BlockingReference(entry.getKey(), Math.toIntExact(count), KEEP_HISTORY_HINT));
            }
        }
        return references;
    }

    private void requireProject(long projectId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from projects where id = ?)", Boolean.class, projectId))) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
    }

    private void lockProject(long projectId) {
        if (jdbc.queryForList("select id from projects where id = ? for update", projectId).isEmpty()) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
    }

    private MemberView lock(long projectId, long memberId) {
        List<MemberView> rows = jdbc.query(MEMBER_COLUMNS + " where pm.project_id = ? and pm.id = ? for update of pm",
                (rs, n) -> view(rs), projectId, memberId);
        if (rows.isEmpty()) {
            throw new MemberNotFoundException();
        }
        return rows.getFirst();
    }

    private MemberView find(long id) {
        return jdbc.query(MEMBER_COLUMNS + " where pm.id = ?", (rs, n) -> view(rs), id).getFirst();
    }

    private MemberView view(ResultSet rs) throws SQLException {
        return new MemberView(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"), rs.getString("title"),
                rs.getString("project_role"), rs.getObject("joined_on", LocalDate.class), rs.getObject("left_on", LocalDate.class),
                rs.getLong("version"));
    }

    private Command createCommand(JsonNode body) {
        only(body, Set.of("userId", "projectRole", "joinedOn"));
        long userId = positive(body, "userId");
        String role = text(body, "projectRole");
        LocalDate joinedOn = date(body, "joinedOn");
        if (!ROLES.contains(role) || joinedOn.isAfter(BusinessCalendar.today())) {
            throw new InvalidMemberInputException();
        }
        return new Command(userId, role, joinedOn);
    }

    private Patch patch(JsonNode body) {
        only(body, Set.of("projectRole", "leftOn"));
        if (body.isEmpty()) {
            throw new InvalidMemberInputException();
        }
        String role = body.has("projectRole") ? text(body, "projectRole") : null;
        if (role != null && !ROLES.contains(role)) {
            throw new InvalidMemberInputException();
        }
        return new Patch(role, body.has("leftOn") ? date(body, "leftOn") : null);
    }

    private void only(JsonNode body, Set<String> fields) {
        if (!body.isObject() || body.properties().stream().anyMatch(field -> !fields.contains(field.getKey()))) {
            throw new InvalidMemberInputException();
        }
    }

    private long positive(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (!JsonIds.isId(value)) {
            throw new InvalidMemberInputException();
        }
        return value.longValue();
    }

    private String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidMemberInputException();
        }
        return value.asText();
    }

    private LocalDate date(JsonNode body, String field) {
        try {
            return LocalDate.parse(text(body, field));
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new InvalidMemberInputException();
        }
    }

    private record Command(long userId, String role, LocalDate joinedOn) { }

    private record Patch(String role, LocalDate leftOn) { }

    public record MemberView(long id, long userId, String userName, String userTitle, String projectRole, LocalDate joinedOn,
                             LocalDate leftOn, long version) { }

    public record MemberList(List<MemberView> items, PageMeta page) { }

    public static class InvalidMemberInputException extends RuntimeException { }

    public static class MemberNotFoundException extends RuntimeException { }

    public static class MemberConflictException extends RuntimeException {
        public MemberConflictException(String message) {
            super(message);
        }
    }
}
