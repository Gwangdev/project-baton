package com.projectbaton.project;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.projectbaton.audit.AuditService;
import com.projectbaton.identity.SystemAdminAccessPolicy;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.PageMeta;
import com.projectbaton.shared.ReferenceConflictException;
import com.projectbaton.shared.ReferenceConflictProblem;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ProjectAdminService {

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final SystemAdminAccessPolicy accessPolicy;

    public ProjectAdminService(JdbcTemplate jdbcTemplate, AuditService auditService, SystemAdminAccessPolicy accessPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.accessPolicy = accessPolicy;
    }

    public void requireSysadmin(long actorId) {
        accessPolicy.requireSystemAdmin(actorId);
    }

    public ProjectList list(int limit, int offset) {
        long total = jdbcTemplate.queryForObject("select count(*) from projects", Long.class);
        List<ProjectView> projects = jdbcTemplate.query("""
                select id, code, name, delivering_company, client_company, started_on, ends_on, phase, status, is_active, version
                from projects order by created_at desc, id desc limit ? offset ?
                """, (rs, row) -> project(rs), limit, offset);
        return new ProjectList(projects, new PageMeta(total, limit, offset, null));
    }

    @Transactional
    public ProjectView create(long actorId, JsonNode body) {
        CreateCommand command = createCommand(body);
        try {
            long id = jdbcTemplate.queryForObject("""
                    insert into projects (code, name, delivering_company, client_company, started_on, ends_on, phase, status)
                    values (?, ?, ?, ?, ?, ?, ?, cast(? as project_status)) returning id
                    """, Long.class, command.code(), command.name(), command.deliveringCompany(), command.clientCompany(),
                    command.startedOn(), command.endsOn(), command.phase(), command.status());
            ProjectView created = find(id);
            auditService.record(actorId, "CREATE_PROJECT", "project", Long.toString(id), "SUCCESS", null);
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw new ProjectConflictException("A project with the same code already exists.");
        }
    }

    @Transactional
    public ProjectView update(long actorId, long projectId, long expectedVersion, JsonNode body) {
        UpdateCommand command = updateCommand(body);
        ProjectView current = findForUpdate(projectId);
        if (current.version() != expectedVersion) {
            throw new StaleProjectVersionException();
        }
        if (Boolean.FALSE.equals(command.isActive()) && current.isActive() && hasActiveHandover(projectId)) {
            throw new ProjectConflictException("A project with an active handover cannot be deactivated.");
        }
        int updated = jdbcTemplate.update("""
                update projects set name = coalesce(?, name), phase = coalesce(?, phase),
                    status = cast(coalesce(?, status::text) as project_status), is_active = coalesce(?, is_active),
                    version = version + 1 where id = ? and version = ?
                """, command.name(), command.phase(), command.status(), command.isActive(), projectId, expectedVersion);
        if (updated == 0) {
            throw new StaleProjectVersionException();
        }
        ProjectView result = find(projectId);
        auditService.record(actorId, "UPDATE_PROJECT", "project", Long.toString(projectId), "SUCCESS", null);
        return result;
    }

    @Transactional
    public void delete(long actorId, long projectId, long expectedVersion) {
        ProjectView current = findForUpdate(projectId);
        if (current.version() != expectedVersion) {
            throw new StaleProjectVersionException();
        }
        List<ReferenceConflictProblem.BlockingReference> references = references(projectId);
        if (!references.isEmpty()) {
            throw new ReferenceConflictException(references);
        }
        try {
            jdbcTemplate.update("delete from projects where id = ? and version = ?", projectId, expectedVersion);
        } catch (DataIntegrityViolationException exception) {
            throw new ReferenceConflictException(List.of(new ReferenceConflictProblem.BlockingReference(
                    "business_history", 1, "비활성화로 전환하면 업무 이력을 유지할 수 있습니다.")));
        }
        auditService.record(actorId, "DELETE_PROJECT", "project", Long.toString(projectId), "SUCCESS", null);
    }

    private ProjectView find(long projectId) {
        List<ProjectView> projects = jdbcTemplate.query("""
                select id, code, name, delivering_company, client_company, started_on, ends_on, phase, status, is_active, version
                from projects where id = ?
                """, (rs, row) -> project(rs), projectId);
        if (projects.isEmpty()) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
        return projects.getFirst();
    }

    private ProjectView findForUpdate(long projectId) {
        List<ProjectView> projects = jdbcTemplate.query("""
                select id, code, name, delivering_company, client_company, started_on, ends_on, phase, status, is_active, version
                from projects where id = ? for update
                """, (rs, row) -> project(rs), projectId);
        if (projects.isEmpty()) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
        return projects.getFirst();
    }

    private ProjectView project(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProjectView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("delivering_company"), rs.getString("client_company"),
                rs.getObject("started_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class),
                rs.getString("phase"), rs.getString("status"), rs.getBoolean("is_active"), rs.getLong("version"));
    }

    private boolean hasActiveHandover(long projectId) {
        Boolean active = jdbcTemplate.queryForObject("select exists(select 1 from handovers where project_id = ? and status <> 'COMPLETED')",
                Boolean.class, projectId);
        return Boolean.TRUE.equals(active);
    }

    private List<ReferenceConflictProblem.BlockingReference> references(long projectId) {
        Map<String, String> counts = new LinkedHashMap<>();
        counts.put("project_members", "select count(*) from project_members where project_id = ?");
        counts.put("source_connections", "select count(*) from source_connections where project_id = ?");
        counts.put("record_sources", "select count(*) from record_sources where project_id = ?");
        counts.put("personal_notes", "select count(*) from personal_notes where project_id = ?");
        counts.put("work_records", "select count(*) from work_records where project_id = ?");
        counts.put("record_relations", "select count(*) from record_relations where project_id = ?");
        counts.put("handovers", "select count(*) from handovers where project_id = ?");
        List<ReferenceConflictProblem.BlockingReference> references = new ArrayList<>();
        for (Map.Entry<String, String> entry : counts.entrySet()) {
            long count = jdbcTemplate.queryForObject(entry.getValue(), Long.class, projectId);
            if (count > 0) {
                references.add(new ReferenceConflictProblem.BlockingReference(entry.getKey(), Math.toIntExact(count),
                        "비활성화로 전환하면 업무 이력을 유지할 수 있습니다."));
            }
        }
        return references;
    }

    private CreateCommand createCommand(JsonNode body) {
        requireOnly(body, List.of("code", "name", "deliveringCompany", "clientCompany", "startedOn", "endsOn", "phase", "status"));
        LocalDate startedOn = requiredDate(body, "startedOn");
        LocalDate endsOn = requiredDate(body, "endsOn");
        if (startedOn.isAfter(endsOn)) {
            throw new InvalidInputException();
        }
        return new CreateCommand(requiredText(body, "code", 32), requiredText(body, "name", 200),
                requiredText(body, "deliveringCompany", 120), requiredText(body, "clientCompany", 120),
                startedOn, endsOn, requiredText(body, "phase", 60), requiredStatus(body, "status"));
    }

    private UpdateCommand updateCommand(JsonNode body) {
        requireOnly(body, List.of("name", "phase", "status", "isActive"));
        if (body.isEmpty()) {
            throw new InvalidInputException();
        }
        return new UpdateCommand(optionalText(body, "name", 200), optionalText(body, "phase", 60),
                optionalStatus(body, "status"), optionalBoolean(body, "isActive"));
    }

    private void requireOnly(JsonNode body, List<String> allowed) {
        if (!body.isObject() || body.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey()))) {
            throw new InvalidInputException();
        }
    }

    private String requiredText(JsonNode body, String field, int maxLength) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            throw new InvalidInputException();
        }
        return value.asText();
    }

    private String optionalText(JsonNode body, String field, int maxLength) {
        return body.has(field) ? requiredText(body, field, maxLength) : null;
    }

    private LocalDate requiredDate(JsonNode body, String field) {
        String value = requiredText(body, field, 10);
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new InvalidInputException();
        }
    }

    private String requiredStatus(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || !validStatus(value.asText())) {
            throw new InvalidInputException();
        }
        return value.asText();
    }

    private String optionalStatus(JsonNode body, String field) {
        return body.has(field) ? requiredStatus(body, field) : null;
    }

    private boolean validStatus(String value) {
        return "PLANNED".equals(value) || "ON_TRACK".equals(value) || "AT_RISK".equals(value) || "CLOSED".equals(value);
    }

    private Boolean optionalBoolean(JsonNode body, String field) {
        if (!body.has(field) || !body.get(field).isBoolean()) {
            if (!body.has(field)) {
                return null;
            }
            throw new InvalidInputException();
        }
        return body.get(field).booleanValue();
    }

    public record ProjectView(long id, String code, String name, String deliveringCompany, String clientCompany,
                              LocalDate startedOn, LocalDate endsOn, String phase, String status, boolean isActive,
                              long version) { }
    public record ProjectList(List<ProjectView> items, PageMeta page) { }
    private record CreateCommand(String code, String name, String deliveringCompany, String clientCompany,
                                 LocalDate startedOn, LocalDate endsOn, String phase, String status) { }
    private record UpdateCommand(String name, String phase, String status, Boolean isActive) { }
    public static class ProjectConflictException extends RuntimeException { public ProjectConflictException(String message) { super(message); } }
    public static class StaleProjectVersionException extends RuntimeException { }
}
