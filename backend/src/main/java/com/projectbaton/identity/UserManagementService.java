package com.projectbaton.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.projectbaton.audit.AuditService;
import com.projectbaton.shared.JsonIds;
import com.projectbaton.shared.ReferenceConflictException;
import com.projectbaton.shared.ReferenceConflictProblem;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class UserManagementService {
    // 아직 업무 이력이 있는 사용자는 지우지 않고 비활성화한다(README §2 삭제 정책).
    private static final String DEACTIVATE_HINT = "비활성화로 전환하면 로그인을 막은 채 업무 이력을 유지한다.";

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public UserManagementService(JdbcTemplate jdbcTemplate, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    public void requireSysadmin(long actorId) {
        Boolean permitted = jdbcTemplate.queryForObject("""
                select exists (
                    select 1 from users u join organizations o on o.id = u.organization_id
                    where u.id = ? and u.is_active and o.is_active and u.system_role = 'SYSTEM_ADMIN'
                )
                """, Boolean.class, actorId);
        if (!Boolean.TRUE.equals(permitted)) {
            throw new AdminAccessDeniedException();
        }
    }

    public UserList list(Long organizationId, int limit, int offset) {
        long total;
        List<UserView> users;
        if (organizationId == null) {
            total = jdbcTemplate.queryForObject("select count(*) from users", Long.class);
            users = jdbcTemplate.query("""
                    select u.id, u.organization_id, o.name as organization_name, u.employee_no, u.name, u.title, u.email,
                           u.system_role, u.is_active, u.auth_provider, u.credential_hash is not null as login_enabled,
                           u.version, u.created_at
                    from users u join organizations o on o.id = u.organization_id
                    order by u.created_at desc, u.id desc limit ? offset ?
                    """, (rs, row) -> user(rs), limit, offset);
        } else {
            total = jdbcTemplate.queryForObject("select count(*) from users where organization_id = ?", Long.class,
                    organizationId);
            users = jdbcTemplate.query("""
                    select u.id, u.organization_id, o.name as organization_name, u.employee_no, u.name, u.title, u.email,
                           u.system_role, u.is_active, u.auth_provider, u.credential_hash is not null as login_enabled,
                           u.version, u.created_at
                    from users u join organizations o on o.id = u.organization_id
                    where u.organization_id = ? order by u.created_at desc, u.id desc limit ? offset ?
                    """, (rs, row) -> user(rs), organizationId, limit, offset);
        }
        return new UserList(users, new PageMeta(total, limit, offset));
    }

    @Transactional
    public UserView create(long actorId, JsonNode body) {
        CreateCommand command = createCommand(body);
        requireOrganization(command.organizationId());
        try {
            long id = jdbcTemplate.queryForObject("""
                    insert into users (organization_id, employee_no, name, title, email, system_role, credential_hash)
                    values (?, ?, ?, ?, ?, cast(? as system_role), null)
                    returning id
                    """, Long.class, command.organizationId(), command.employeeNo(), command.name(), command.title(),
                    command.email(), command.systemRole());
            UserView created = find(id);
            auditService.record(actorId, "CREATE_USER", "user", Long.toString(id), "SUCCESS", null);
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw new UserConflictException("A user with the same employee number or email already exists.");
        }
    }

    @Transactional
    public UserView update(long actorId, long userId, long expectedVersion, JsonNode body) {
        UpdateCommand command = updateCommand(body);
        UserView current = findForUpdate(userId);
        if (current.version() != expectedVersion) {
            throw new StaleUserVersionException();
        }
        if (command.organizationId() != null) {
            requireOrganization(command.organizationId());
        }
        boolean losesAdmin = "SYSTEM_ADMIN".equals(current.systemRole()) && current.isActive()
                && ((command.systemRole() != null && !"SYSTEM_ADMIN".equals(command.systemRole()))
                || Boolean.FALSE.equals(command.isActive()));
        if (losesAdmin) {
            if (actorId == userId || activeSysadminCount() <= 1) {
                throw new UserConflictException("A user cannot remove their own or the last active system administrator role.");
            }
        }
        int updated = jdbcTemplate.update("""
                update users
                set name = coalesce(?, name), title = coalesce(?, title),
                    organization_id = coalesce(?, organization_id),
                    system_role = cast(coalesce(?, system_role::text) as system_role),
                    is_active = coalesce(?, is_active), version = version + 1
                where id = ? and version = ?
                """, command.name(), command.title(), command.organizationId(), command.systemRole(), command.isActive(),
                userId, expectedVersion);
        if (updated == 0) {
            throw new StaleUserVersionException();
        }
        UserView result = find(userId);
        auditService.record(actorId, "UPDATE_USER", "user", Long.toString(userId), "SUCCESS", null);
        return result;
    }

    @Transactional
    public void delete(long actorId, long userId, long expectedVersion) {
        UserView current = findForUpdate(userId);
        if (current.version() != expectedVersion) {
            throw new StaleUserVersionException();
        }
        if ("SYSTEM_ADMIN".equals(current.systemRole()) && current.isActive()
                && (actorId == userId || activeSysadminCount() <= 1)) {
            throw new UserConflictException("A user cannot delete their own or the last active system administrator account.");
        }
        List<ReferenceConflictProblem.BlockingReference> references = references(userId);
        if (!references.isEmpty()) {
            throw new ReferenceConflictException(references);
        }
        try {
            jdbcTemplate.update("delete from users where id = ? and version = ?", userId, expectedVersion);
        } catch (DataIntegrityViolationException exception) {
            throw new ReferenceConflictException(List.of(new ReferenceConflictProblem.BlockingReference("business_history", 1, DEACTIVATE_HINT)));
        }
        auditService.record(actorId, "DELETE_USER", "user", Long.toString(userId), "SUCCESS", null);
    }

    private UserView find(long userId) {
        List<UserView> users = jdbcTemplate.query("""
                select u.id, u.organization_id, o.name as organization_name, u.employee_no, u.name, u.title, u.email,
                       u.system_role, u.is_active, u.auth_provider, u.credential_hash is not null as login_enabled,
                       u.version, u.created_at
                from users u join organizations o on o.id = u.organization_id where u.id = ?
                """, (rs, row) -> user(rs), userId);
        if (users.isEmpty()) {
            throw new UserNotFoundException();
        }
        return users.getFirst();
    }

    private UserView findForUpdate(long userId) {
        List<UserView> users = jdbcTemplate.query("""
                select u.id, u.organization_id, o.name as organization_name, u.employee_no, u.name, u.title, u.email,
                       u.system_role, u.is_active, u.auth_provider, u.credential_hash is not null as login_enabled,
                       u.version, u.created_at
                from users u join organizations o on o.id = u.organization_id where u.id = ? for update
                """, (rs, row) -> user(rs), userId);
        if (users.isEmpty()) {
            throw new UserNotFoundException();
        }
        return users.getFirst();
    }

    private UserView user(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserView(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("organization_name"),
                rs.getString("employee_no"), rs.getString("name"), rs.getString("title"), rs.getString("email"),
                rs.getString("system_role"), rs.getBoolean("is_active"), rs.getString("auth_provider"),
                rs.getBoolean("login_enabled"), rs.getLong("version"));
    }

    private void requireOrganization(long organizationId) {
        Boolean exists = jdbcTemplate.queryForObject("select exists(select 1 from organizations where id = ?)",
                Boolean.class, organizationId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new UserNotFoundException();
        }
    }

    private long activeSysadminCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from users u join organizations o on o.id = u.organization_id
                where u.system_role = 'SYSTEM_ADMIN' and u.is_active and o.is_active
                """, Long.class);
    }

    private List<ReferenceConflictProblem.BlockingReference> references(long userId) {
        Map<String, String> counts = new LinkedHashMap<>();
        counts.put("project_members", "select count(*) from project_members where user_id = ?");
        counts.put("source_connections", "select count(*) from source_connections where owner_user_id = ?");
        counts.put("personal_notes", "select count(*) from personal_notes where author_id = ?");
        counts.put("work_records", "select count(*) from work_records where author_id = ? or owner_user_id = ? or confirmed_by = ?");
        counts.put("record_revisions", "select count(*) from record_revisions where changed_by = ?");
        counts.put("handovers", "select count(*) from handovers where requested_by = ? or outgoing_owner_id = ? or incoming_owner_id = ? or approved_by = ?");
        counts.put("handover_items", "select count(*) from handover_items where assignee_id = ?");
        counts.put("qa_messages", "select count(*) from qa_messages where session_user_id = ?");
        counts.put("assessment_attempts", "select count(*) from assessment_attempts where taken_by = ?");
        // 감사 행은 외래키 없이 id로 행위자를 가리켜서, 행위자를 지우면 아무도 가리키지 않는 행이 남는다. 그래서
        // 로그인했거나 행동한 사람은 지우지 않고 비활성화한다. 사용자를 대상으로만 가리키는 행은 막지 않는다.
        counts.put("audit_events", "select count(*) from audit_events where actor_id = ?");
        List<ReferenceConflictProblem.BlockingReference> references = new ArrayList<>();
        for (Map.Entry<String, String> entry : counts.entrySet()) {
            int placeholders = (int) entry.getValue().chars().filter(value -> value == '?').count();
            Object[] values = java.util.stream.LongStream.range(0, placeholders).map(index -> userId).boxed().toArray();
            long count = jdbcTemplate.queryForObject(entry.getValue(), Long.class, values);
            if (count > 0) {
                references.add(new ReferenceConflictProblem.BlockingReference(entry.getKey(), Math.toIntExact(count), DEACTIVATE_HINT));
            }
        }
        return references;
    }

    private CreateCommand createCommand(JsonNode body) {
        requireOnly(body, List.of("organizationId", "employeeNo", "name", "title", "email", "systemRole"));
        return new CreateCommand(positiveLong(body, "organizationId"), requiredText(body, "employeeNo", 32),
                requiredText(body, "name", 60), requiredText(body, "title", 40), requiredEmail(body),
                optionalRole(body, "systemRole", "USER"));
    }

    private UpdateCommand updateCommand(JsonNode body) {
        requireOnly(body, List.of("name", "title", "organizationId", "systemRole", "isActive"));
        if (body.isEmpty()) {
            throw new InvalidUserInputException();
        }
        return new UpdateCommand(optionalText(body, "name", 60), optionalText(body, "title", 40),
                optionalLong(body, "organizationId"), optionalRole(body, "systemRole", null), optionalBoolean(body, "isActive"));
    }

    private void requireOnly(JsonNode body, List<String> allowed) {
        if (!body.isObject() || body.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) {
            throw new InvalidUserInputException();
        }
    }

    private String requiredText(JsonNode body, String field, int maxLength) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            throw new InvalidUserInputException();
        }
        return value.asText();
    }

    private String optionalText(JsonNode body, String field, int maxLength) {
        if (!body.has(field)) {
            return null;
        }
        return requiredText(body, field, maxLength);
    }

    private long positiveLong(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (!JsonIds.isId(value)) {
            throw new InvalidUserInputException();
        }
        return value.longValue();
    }

    private Long optionalLong(JsonNode body, String field) {
        return body.has(field) ? positiveLong(body, field) : null;
    }

    private String requiredEmail(JsonNode body) {
        String email = requiredText(body, "email", 160);
        if (!email.contains("@")) {
            throw new InvalidUserInputException();
        }
        return email;
    }

    private String optionalRole(JsonNode body, String field, String defaultValue) {
        if (!body.has(field)) {
            return defaultValue;
        }
        JsonNode value = body.get(field);
        if (!value.isTextual() || !("USER".equals(value.asText()) || "SYSTEM_ADMIN".equals(value.asText()))) {
            throw new InvalidUserInputException();
        }
        return value.asText();
    }

    private Boolean optionalBoolean(JsonNode body, String field) {
        if (!body.has(field)) {
            return null;
        }
        if (!body.get(field).isBoolean()) {
            throw new InvalidUserInputException();
        }
        return body.get(field).booleanValue();
    }

    public record UserView(long id, long organizationId, String organizationName, String employeeNo, String name,
                           String title, String email, String systemRole, boolean isActive, String authProvider,
                           boolean loginEnabled, long version) {
    }

    public record UserList(List<UserView> items, PageMeta page) {
    }

    public record PageMeta(long total, int limit, int offset) {
    }

    private record CreateCommand(long organizationId, String employeeNo, String name, String title, String email,
                                 String systemRole) {
    }

    private record UpdateCommand(String name, String title, Long organizationId, String systemRole, Boolean isActive) {
    }

    public static class AdminAccessDeniedException extends RuntimeException { }
    public static class UserNotFoundException extends RuntimeException { }
    public static class InvalidUserInputException extends RuntimeException { }
    public static class UserConflictException extends RuntimeException {
        public UserConflictException(String message) { super(message); }
    }
    public static class StaleUserVersionException extends RuntimeException { }
}
