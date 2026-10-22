package com.projectbaton.note;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.projectbaton.audit.AuditService;
import com.projectbaton.jobs.EmbeddingQueue;
import com.projectbaton.project.ProjectAccessPolicy;
import com.projectbaton.record.RecordQueryService;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.JsonIds;
import com.projectbaton.shared.PageMeta;
import com.projectbaton.shared.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class PersonalNoteService {

    private static final Set<String> RECORD_TYPES = Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "SCOPE_EXCEPTION", "OPEN_ISSUE");

    private final JdbcTemplate jdbcTemplate;
    private final ProjectAccessPolicy projectAccess;
    private final RecordQueryService records;
    private final AuditService audit;
    private final EmbeddingQueue embeddings;

    public PersonalNoteService(JdbcTemplate jdbcTemplate, ProjectAccessPolicy projectAccess, RecordQueryService records,
                               AuditService audit, EmbeddingQueue embeddings) {
        this.embeddings = embeddings;
        this.jdbcTemplate = jdbcTemplate;
        this.projectAccess = projectAccess;
        this.records = records;
        this.audit = audit;
    }

    public void authorizeProjectMember(long actorId, long projectId) {
        projectAccess.requireMember(actorId, projectId);
    }

    public void authorizeAuthor(long actorId, long noteId) {
        Long projectId = jdbcTemplate.query("select project_id from personal_notes where id = ? and author_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, noteId, actorId);
        if (projectId == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
        projectAccess.requireMember(actorId, projectId);
    }

    /**
     * 모든 목록처럼 페이지로 나눈다(contracts.md C01). 예전에는 페이지 한도를 20이라고 알리면서 메모를 전부 돌려줬다.
     */
    public NoteList list(long actorId, long projectId, PageRequest page) {
        projectAccess.requireMember(actorId, projectId);
        long total = jdbcTemplate.queryForObject("select count(*) from personal_notes where project_id = ? and author_id = ?",
                Long.class, projectId, actorId);
        List<PersonalNote> items = jdbcTemplate.query("""
                select pn.id, pn.code, pn.project_id, pn.body, pn.created_at,
                       coalesce(array_agg(wr.code order by wr.id) filter (where wr.id is not null), array[]::varchar[]) as shared_record_codes
                from personal_notes pn
                left join work_records wr on wr.source_note_id = pn.id
                where pn.project_id = ? and pn.author_id = ?
                group by pn.id
                order by pn.created_at desc, pn.id desc
                limit ? offset ?
                """, (rs, row) -> new PersonalNote(rs.getLong("id"), rs.getString("code"), rs.getLong("project_id"),
                rs.getString("body"), rs.getObject("created_at", OffsetDateTime.class),
                List.of((String[]) rs.getArray("shared_record_codes").getArray())), projectId, actorId, page.limit(), page.offset());
        return new NoteList(items, page.meta(total));
    }

    @Transactional
    public PersonalNote create(long actorId, long projectId, JsonNode body) {
        lockProject(projectId);
        projectAccess.requireMember(actorId, projectId);
        String text = noteBody(body);
        long id = jdbcTemplate.queryForObject("""
                insert into personal_notes (code, project_id, author_id, body)
                values ('NOTE-' || nextval('personal_notes_id_seq'), ?, ?, ?)
                returning id
                """, Long.class, projectId, actorId, text);
        audit.record(actorId, "CREATE_PERSONAL_NOTE", "personal_note", String.valueOf(id), "SUCCESS", null);
        return note(id, actorId);
    }

    @Transactional
    public RecordQueryService.RecordDetail share(long actorId, long noteId, JsonNode body) {
        NoteLock note = lockNote(noteId, actorId);
        if (note == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
        lockProject(note.projectId());
        projectAccess.requireMember(actorId, note.projectId());
        ShareRequest request = shareRequest(body);
        if (request.ownerUserId() != null && !projectAccess.isMember(request.ownerUserId(), note.projectId())) {
            throw InvalidInputException.notAMember("ownerUserId");
        }
        long recordId = jdbcTemplate.queryForObject("""
                insert into work_records (code, project_id, source_note_id, record_type, title, summary, original_excerpt,
                                          occurred_on, author_id, author_name, owner_user_id, due_on, trust_status,
                                          confirmed_by, confirmed_at, search_text)
                select 'REC-NOTE-' || nextval('work_records_id_seq'), ?, ?, cast(? as record_type), ?, ?, ?, ?,
                       u.id, u.name, ?, ?, 'CONFIRMED', u.id, now(), ?
                from users u where u.id = ?
                returning id
                """, Long.class, note.projectId(), noteId, request.recordType(), request.title(), request.summary(), request.summary(),
                request.occurredOn(), request.ownerUserId(), request.dueOn(), request.summary(), actorId);
        embeddings.enqueue(recordId, 0);
        audit.record(actorId, "SHARE_PERSONAL_NOTE", "personal_note", String.valueOf(noteId), "SUCCESS", null);
        return records.detail(actorId, recordId);
    }

    private PersonalNote note(long noteId, long actorId) {
        return jdbcTemplate.queryForObject("""
                select pn.id, pn.code, pn.project_id, pn.body, pn.created_at,
                       coalesce(array_agg(wr.code order by wr.id) filter (where wr.id is not null), array[]::varchar[]) as shared_record_codes
                from personal_notes pn left join work_records wr on wr.source_note_id = pn.id
                where pn.id = ? and pn.author_id = ? group by pn.id
                """, (rs, row) -> new PersonalNote(rs.getLong("id"), rs.getString("code"), rs.getLong("project_id"),
                rs.getString("body"), rs.getObject("created_at", OffsetDateTime.class),
                List.of((String[]) rs.getArray("shared_record_codes").getArray())), noteId, actorId);
    }

    private void lockProject(long projectId) {
        Integer locked = jdbcTemplate.query("select id from projects where id = ? for update", rs -> rs.next() ? 1 : null, projectId);
        if (locked == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
    }

    private NoteLock lockNote(long noteId, long actorId) {
        return jdbcTemplate.query("select id, project_id from personal_notes where id = ? and author_id = ? for update",
                rs -> rs.next() ? new NoteLock(rs.getLong("id"), rs.getLong("project_id")) : null, noteId, actorId);
    }

    private String noteBody(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("body") || !body.get("body").isTextual()
                || body.get("body").stringValue().isBlank() || body.get("body").stringValue().length() > 10000) {
            throw new InvalidInputException();
        }
        return body.get("body").stringValue();
    }

    private ShareRequest shareRequest(JsonNode body) {
        if (body == null || !body.isObject() || body.properties().stream().anyMatch(entry -> !Set.of(
                "recordType", "title", "summary", "occurredOn", "ownerUserId", "dueOn").contains(entry.getKey()))) {
            throw new InvalidInputException();
        }
        String recordType = text(body, "recordType", 30, true);
        String title = text(body, "title", 200, true);
        String summary = text(body, "summary", 4000, true);
        String occurredOn = text(body, "occurredOn", 10, true);
        if (!RECORD_TYPES.contains(recordType) || title == null || summary == null || title.isBlank() || summary.isBlank()) {
            throw new InvalidInputException();
        }
        LocalDate occurred;
        try { occurred = LocalDate.parse(occurredOn); } catch (RuntimeException exception) { throw new InvalidInputException(); }
        return new ShareRequest(recordType, title, summary, occurred, nullableId(body, "ownerUserId"), nullableDate(body, "dueOn"));
    }

    private String text(JsonNode body, String field, int maximum, boolean required) {
        JsonNode value = body.get(field);
        if (value == null) { if (required) throw new InvalidInputException(); return null; }
        if (!value.isTextual() || value.stringValue().length() > maximum) throw new InvalidInputException();
        return value.stringValue();
    }

    private Long nullableId(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!JsonIds.isId(value)) throw new InvalidInputException();
        return value.longValue();
    }

    private LocalDate nullableDate(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new InvalidInputException();
        try { return LocalDate.parse(value.stringValue()); } catch (RuntimeException exception) { throw new InvalidInputException(); }
    }

    public record PersonalNote(long id, String code, long projectId, String body, OffsetDateTime createdAt,
                               List<String> sharedRecordCodes) { }
    public record NoteList(List<PersonalNote> items, PageMeta page) { }
    private record NoteLock(long id, long projectId) { }
    private record ShareRequest(String recordType, String title, String summary, LocalDate occurredOn, Long ownerUserId,
                                LocalDate dueOn) { }
}
