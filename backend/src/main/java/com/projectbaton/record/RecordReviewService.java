package com.projectbaton.record;

import java.time.LocalDate;
import java.util.Set;

import com.projectbaton.audit.AuditService;
import com.projectbaton.jobs.EmbeddingQueue;
import com.projectbaton.project.ProjectAccessPolicy;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.JsonIds;
import com.projectbaton.shared.StaleVersionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RecordReviewService {

    private static final Set<String> ACTIONS = Set.of("CONFIRM", "CORRECT", "DISMISS");
    private static final Set<String> RECORD_TYPES = Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "SCOPE_EXCEPTION", "OPEN_ISSUE");
    private static final Set<String> FIELDS = Set.of("action", "title", "summary", "recordType", "ownerUserId", "dueOn", "changeReason");

    private final JdbcTemplate jdbcTemplate;
    private final ProjectAccessPolicy projectAccess;
    private final RecordQueryService queries;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final EmbeddingQueue embeddings;

    public RecordReviewService(JdbcTemplate jdbcTemplate, ProjectAccessPolicy projectAccess, RecordQueryService queries,
                               AuditService audit, ObjectMapper objectMapper, EmbeddingQueue embeddings) {
        this.embeddings = embeddings;
        this.jdbcTemplate = jdbcTemplate;
        this.projectAccess = projectAccess;
        this.queries = queries;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public void authorize(long actorId, long recordId) {
        if (queries.detail(actorId, recordId) == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
    }

    @Transactional
    public RecordQueryService.RecordDetail review(long actorId, long recordId, long expectedVersion, JsonNode body) {
        LockRow row = lock(recordId);
        projectAccess.requireMember(actorId, row.projectId());
        requireReviewer(actorId, row);
        if (row.version() != expectedVersion) throw new StaleVersionException();
        if (!"AUTO_DRAFT".equals(row.trustStatus())) throw new ReviewConflictException("The record has already been reviewed.");
        Review request = request(body, row);

        if ("CORRECT".equals(request.action()) && request.ownerUserId() != null
                && !projectAccess.isMember(request.ownerUserId(), row.projectId())) {
            throw InvalidInputException.notAMember("ownerUserId");
        }
        String trustStatus = switch (request.action()) {
            case "CONFIRM" -> "CONFIRMED";
            case "CORRECT" -> "CORRECTED";
            default -> "DISMISSED";
        };
        int updated = jdbcTemplate.update("""
                update work_records
                set trust_status = cast(? as record_trust_status), title = ?, summary = ?, record_type = cast(? as record_type),
                    owner_user_id = ?, due_on = ?, confirmed_by = ?, confirmed_at = case when ? then now() else null end,
                    version = version + 1, updated_at = now()
                where id = ? and version = ?
                """, trustStatus, request.title(), request.summary(), request.recordType(), request.ownerUserId(), request.dueOn(),
                "DISMISSED".equals(trustStatus) ? null : actorId, !"DISMISSED".equals(trustStatus), recordId, expectedVersion);
        if (updated != 1) throw new StaleVersionException();
        if ("CORRECT".equals(request.action())) revision(actorId, row, request);
        if (!"DISMISSED".equals(trustStatus)) embeddings.enqueue(recordId, expectedVersion + 1);
        audit.record(actorId, "REVIEW_RECORD", "work_record", String.valueOf(recordId), "SUCCESS", null);
        return queries.detail(actorId, recordId);
    }

    private LockRow lock(long recordId) {
        Long projectId = jdbcTemplate.query("select project_id from work_records where id = ?", rs -> rs.next() ? rs.getLong(1) : null, recordId);
        if (projectId == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
        jdbcTemplate.query("select id from projects where id = ? for update", rs -> null, projectId);
        return jdbcTemplate.query("""
                select wr.id, wr.project_id, wr.trust_status, wr.version, wr.title, wr.summary, wr.record_type,
                       wr.owner_user_id, wr.due_on, sc.owner_user_id as source_owner_user_id
                from work_records wr
                left join record_sources rs on rs.id = wr.record_source_id
                left join source_connections sc on sc.id = rs.source_connection_id
                where wr.id = ? for update of wr
                """, rs -> rs.next() ? new LockRow(rs.getLong("id"), rs.getLong("project_id"), rs.getString("trust_status"),
                rs.getLong("version"), rs.getString("title"), rs.getString("summary"), rs.getString("record_type"),
                nullableLong(rs, "owner_user_id"), rs.getObject("due_on", LocalDate.class), nullableLong(rs, "source_owner_user_id")) : null, recordId);
    }

    private void requireReviewer(long actorId, LockRow row) {
        if (row.sourceOwnerUserId() != null) {
            if (row.sourceOwnerUserId() != actorId) throw new ProjectAccessPolicy.ProjectNotFoundException();
            return;
        }
        Boolean allowed = jdbcTemplate.queryForObject("""
                select exists (select 1 from project_members where project_id = ? and user_id = ?
                    and project_role in ('OUTGOING_OWNER', 'PROJECT_MANAGER') and joined_on <= current_date
                    and (left_on is null or current_date < left_on))
                """, Boolean.class, row.projectId(), actorId);
        if (!Boolean.TRUE.equals(allowed)) throw new ReviewAccessDeniedException();
    }

    private Review request(JsonNode body, LockRow current) {
        if (body == null || !body.isObject() || body.properties().stream().anyMatch(entry -> !FIELDS.contains(entry.getKey()))) {
            throw new InvalidReviewInputException();
        }
        JsonNode actionNode = body.get("action");
        if (actionNode == null || !actionNode.isTextual() || !ACTIONS.contains(actionNode.stringValue())) throw new InvalidReviewInputException();
        String action = actionNode.stringValue();
        if (!"CORRECT".equals(action)) {
            if (body.size() != 1) throw new InvalidReviewInputException();
            return new Review(action, current.title(), current.summary(), current.recordType(), current.ownerUserId(), current.dueOn(), null);
        }
        String reason = text(body, "changeReason", 300, false);
        if (reason == null || reason.isBlank()) throw new InvalidReviewInputException();
        String title = text(body, "title", 200, true);
        String summary = text(body, "summary", 4000, true);
        String recordType = enumValue(body, "recordType", RECORD_TYPES, current.recordType());
        Long owner = nullableId(body, "ownerUserId", current.ownerUserId());
        LocalDate dueOn = date(body, "dueOn", current.dueOn());
        if (title == null) title = current.title();
        if (summary == null) summary = current.summary();
        Review review = new Review(action, title, summary, recordType, owner, dueOn, reason);
        if (review.sameAs(current)) throw new InvalidReviewInputException();
        return review;
    }

    private String text(JsonNode body, String field, int maxLength, boolean optional) {
        JsonNode value = body.get(field);
        if (value == null) return null;
        if (!value.isTextual() || value.stringValue().length() > maxLength || (!optional && value.stringValue().isBlank())) throw new InvalidReviewInputException();
        return value.stringValue();
    }

    private String enumValue(JsonNode body, String field, Set<String> values, String fallback) {
        JsonNode value = body.get(field);
        if (value == null) return fallback;
        if (!value.isTextual() || !values.contains(value.stringValue())) throw new InvalidReviewInputException();
        return value.stringValue();
    }

    private Long nullableId(JsonNode body, String field, Long fallback) {
        JsonNode value = body.get(field);
        if (value == null) return fallback;
        if (value.isNull()) return null;
        if (!JsonIds.isId(value)) throw new InvalidReviewInputException();
        return value.longValue();
    }

    private LocalDate date(JsonNode body, String field, LocalDate fallback) {
        JsonNode value = body.get(field);
        if (value == null) return fallback;
        if (value.isNull()) return null;
        if (!value.isTextual()) throw new InvalidReviewInputException();
        try { return LocalDate.parse(value.stringValue()); } catch (RuntimeException exception) { throw new InvalidReviewInputException(); }
    }

    private void revision(long actorId, LockRow before, Review after) {
        ObjectNode beforeData = data(before.title(), before.summary(), before.recordType(), before.ownerUserId(), before.dueOn());
        ObjectNode afterData = data(after.title(), after.summary(), after.recordType(), after.ownerUserId(), after.dueOn());
        jdbcTemplate.update("""
                insert into record_revisions (work_record_id, revision_no, changed_by, change_reason, before_summary, after_summary, before_data, after_data)
                values (?, (select coalesce(max(revision_no), 0) + 1 from record_revisions where work_record_id = ?), ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, before.id(), before.id(), actorId, after.changeReason(), before.summary(), after.summary(), beforeData.toString(), afterData.toString());
    }

    private ObjectNode data(String title, String summary, String recordType, Long ownerUserId, LocalDate dueOn) {
        ObjectNode data = objectMapper.createObjectNode().put("title", title).put("summary", summary).put("recordType", recordType);
        if (ownerUserId == null) data.putNull("ownerUserId"); else data.put("ownerUserId", ownerUserId);
        if (dueOn == null) data.putNull("dueOn"); else data.put("dueOn", dueOn.toString());
        return data;
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record LockRow(long id, long projectId, String trustStatus, long version, String title, String summary,
                           String recordType, Long ownerUserId, LocalDate dueOn, Long sourceOwnerUserId) { }
    private record Review(String action, String title, String summary, String recordType, Long ownerUserId, LocalDate dueOn,
                          String changeReason) {
        boolean sameAs(LockRow row) {
            return title.equals(row.title()) && summary.equals(row.summary()) && recordType.equals(row.recordType())
                    && java.util.Objects.equals(ownerUserId, row.ownerUserId()) && java.util.Objects.equals(dueOn, row.dueOn());
        }
    }
    public static class InvalidReviewInputException extends RuntimeException { }
    public static class ReviewConflictException extends RuntimeException { public ReviewConflictException(String message) { super(message); } }
    public static class ReviewAccessDeniedException extends RuntimeException { }
}
