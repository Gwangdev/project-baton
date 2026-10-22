package com.projectbaton.connection;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import com.projectbaton.audit.AuditService;
import com.projectbaton.project.ProjectAccessPolicy;
import com.projectbaton.record.RecordQueryService;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.JsonIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * AI 구조화가 만들지 못한 수집 파일의 초안을 사람이 쓴다(FR-05 복구). 이 초안도 AUTO_DRAFT이고 다른 초안처럼 검토를
 * 거친다.
 */
@Service
public class ManualDraftService {
    private static final Set<String> TYPES = Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "SCOPE_EXCEPTION", "OPEN_ISSUE");
    private static final Set<String> FIELDS = Set.of("recordType", "title", "summary", "originalExcerpt", "occurredOn", "ownerUserId", "dueOn");

    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;
    private final RecordQueryService records;
    private final AuditService audit;

    public ManualDraftService(JdbcTemplate jdbc, ProjectAccessPolicy projects, RecordQueryService records, AuditService audit) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.records = records;
        this.audit = audit;
    }

    public void authorize(long actor, long connection, long source) {
        allow(actor, find(connection, source, false));
    }

    @Transactional
    public RecordQueryService.RecordDetail create(long actor, long connection, long source, JsonNode body) {
        Draft draft = draft(body);
        Source initial = find(connection, source, false);
        lockProject(initial.projectId());
        Source locked = find(connection, source, true);
        allow(actor, locked);
        if (!"FAILED".equals(locked.processingStatus())) {
            throw new ManualDraftConflictException("Manual drafts are only available for failed sources.");
        }
        // 초안 작성자는 이 프로젝트를 볼 수 있으므로, 현재 구성원이 아닌 담당자는 요청의 잘못이다.
        if (draft.ownerUserId() != null && !projects.isMember(draft.ownerUserId(), locked.projectId())) {
            throw InvalidInputException.notAMember("ownerUserId");
        }
        long id = jdbc.queryForObject("""
                insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,
                                         author_name,owner_user_id,due_on,trust_status,source_version,extraction_index,search_text)
                values('REC-MANUAL-'||nextval('work_records_id_seq'),?,?,cast(? as record_type),?,?,?,?, 'Manual recovery', ?, ?,
                       'AUTO_DRAFT', ?, (select coalesce(max(extraction_index),0)+1 from work_records where record_source_id=?), ?)
                returning id
                """, Long.class, locked.projectId(), source, draft.type(), draft.title(), draft.summary(), draft.excerpt(),
                draft.occurredOn(), draft.ownerUserId(), draft.dueOn(), locked.externalVersion(), source, draft.summary());
        audit.record(actor, "CREATE_MANUAL_DRAFT", "work_record", Long.toString(id), "SUCCESS", null);
        return records.detail(actor, id);
    }

    private Source find(long connection, long source, boolean lock) {
        List<Source> rows = jdbc.query("""
                select rs.id, rs.project_id, rs.processing_status, rs.external_version, sc.owner_user_id
                from record_sources rs join source_connections sc on sc.id = rs.source_connection_id
                where rs.id = ? and rs.source_connection_id = ?
                """ + (lock ? " for update of rs" : ""),
                (rs, n) -> new Source(rs.getLong("id"), rs.getLong("project_id"), rs.getString("processing_status"),
                        rs.getString("external_version"), rs.getObject("owner_user_id", Long.class)), source, connection);
        if (rows.isEmpty()) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
        return rows.getFirst();
    }

    /** README §4.2: 개인 폴더의 파일은 소유자만, 공유 연동의 파일은 기존 담당자와 PM이 본다. */
    private void allow(long actor, Source source) {
        projects.requireMember(actor, source.projectId());
        if (source.ownerUserId() != null) {
            if (source.ownerUserId() != actor) {
                throw new ProjectAccessPolicy.ProjectNotFoundException();
            }
            return;
        }
        Boolean permitted = jdbc.queryForObject("""
                select exists(select 1 from project_members
                              where project_id = ? and user_id = ? and project_role in ('OUTGOING_OWNER','PROJECT_MANAGER')
                                and joined_on <= current_date and (left_on is null or current_date < left_on))
                """, Boolean.class, source.projectId(), actor);
        if (!Boolean.TRUE.equals(permitted)) {
            throw new ManualDraftAccessDeniedException();
        }
    }

    private void lockProject(long projectId) {
        Long found = jdbc.query("select id from projects where id = ? for update", rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (found == null) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
    }

    private Draft draft(JsonNode body) {
        if (body == null || !body.isObject() || body.properties().stream().anyMatch(field -> !FIELDS.contains(field.getKey()))) {
            throw new InvalidInputException();
        }
        String type = text(body, "recordType", 30, true);
        if (!TYPES.contains(type)) {
            throw new InvalidInputException();
        }
        LocalDate occurredOn = parse(text(body, "occurredOn", 10, true));
        return new Draft(type, text(body, "title", 200, true), text(body, "summary", 4000, true),
                text(body, "originalExcerpt", 10000, true), occurredOn, id(body, "ownerUserId"), date(body, "dueOn"));
    }

    private String text(JsonNode body, String field, int max, boolean required) {
        JsonNode value = body.get(field);
        if (value == null) {
            if (required) {
                throw new InvalidInputException();
            }
            return null;
        }
        if (!value.isTextual() || value.stringValue().isBlank() || value.stringValue().length() > max) {
            throw new InvalidInputException();
        }
        return value.stringValue();
    }

    private Long id(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!JsonIds.isId(value)) {
            throw new InvalidInputException();
        }
        return value.longValue();
    }

    private LocalDate date(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidInputException();
        }
        return parse(value.stringValue());
    }

    private LocalDate parse(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new InvalidInputException();
        }
    }

    private record Source(long id, long projectId, String processingStatus, String externalVersion, Long ownerUserId) { }

    private record Draft(String type, String title, String summary, String excerpt, LocalDate occurredOn, Long ownerUserId,
                         LocalDate dueOn) { }

    public static class ManualDraftConflictException extends RuntimeException {
        public ManualDraftConflictException(String message) {
            super(message);
        }
    }

    public static class ManualDraftAccessDeniedException extends RuntimeException { }
}
