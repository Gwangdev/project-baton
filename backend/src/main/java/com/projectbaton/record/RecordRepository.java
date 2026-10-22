package com.projectbaton.record;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.projectbaton.project.RecordVisibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecordRepository {
    // 표시용 열만 있다. 공개 범위 조건 밖에 두고, 합계는 이 열을 join하지 않는다.
    private static final String DISPLAY_JOINS = """

            left join record_sources rs on rs.id = wr.record_source_id
            left join source_connections sc on sc.id = rs.source_connection_id
            left join personal_notes pn on pn.id = wr.source_note_id
            left join users owner on owner.id = wr.owner_user_id
            left join users confirmer on confirmer.id = wr.confirmed_by
            """;

    private final JdbcTemplate jdbcTemplate;

    public RecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countVisible(long actorId, long projectId, RecordQueryService.Filters filters) {
        Query query = query(actorId, projectId, filters, null);
        return jdbcTemplate.queryForObject("select count(*) from work_records wr cross join " + RecordVisibility.BOUND_SCOPE
                + " scope where " + query.where(), Long.class, query.arguments().toArray());
    }

    public List<RecordRow> findVisible(long actorId, long projectId, RecordQueryService.Filters filters,
                                       RecordQueryService.Cursor cursor, int limit, int offset) {
        Query query = query(actorId, projectId, filters, cursor);
        List<Object> arguments = new ArrayList<>(query.arguments());
        arguments.add(limit);
        arguments.add(offset);
        return jdbcTemplate.query(select() + "from work_records wr cross join " + RecordVisibility.BOUND_SCOPE + " scope"
                        + DISPLAY_JOINS + "where " + query.where() + " order by wr.occurred_on desc, wr.id desc limit ? offset ?",
                (rs, row) -> row(rs), arguments.toArray());
    }

    public RecordRow findVisibleById(long actorId, long recordId) {
        List<RecordRow> records = jdbcTemplate.query(select() + """
                from work_records wr
                cross join (select cast(? as bigint) as actor_id,
                                   (select project_id from work_records where id = ?) as project_id) scope
                """ + DISPLAY_JOINS + "where wr.id = ? and " + RecordVisibility.visible("wr", "scope"),
                (rs, row) -> row(rs), actorId, recordId, recordId);
        return records.isEmpty() ? null : records.getFirst();
    }

    public List<RecordRevision> revisions(long recordId) {
        return jdbcTemplate.query("""
                select revision_no, changed_by.name as changed_by_name, change_reason, before_summary, after_summary, changed_at
                from record_revisions rr join users changed_by on changed_by.id = rr.changed_by
                where rr.work_record_id = ? order by rr.revision_no
                """, (rs, row) -> new RecordRevision(rs.getInt("revision_no"), rs.getString("changed_by_name"),
                rs.getString("change_reason"), rs.getString("before_summary"), rs.getString("after_summary"),
                rs.getObject("changed_at", OffsetDateTime.class)), recordId);
    }

    public List<RecordRelation> relations(long actorId, long recordId) {
        return jdbcTemplate.query("""
                select rr.id, rr.project_id, rr.from_record_id, rr.to_record_id, rr.relation_type, rr.note,
                       related.id as related_record_id, related.code as related_record_code, related.title as related_record_title
                from record_relations rr
                join work_records related on related.id = case when rr.from_record_id = ? then rr.to_record_id else rr.from_record_id end
                left join record_sources related_source on related_source.id = related.record_source_id
                left join source_connections related_connection on related_connection.id = related_source.source_connection_id
                where (rr.from_record_id = ? or rr.to_record_id = ?) and rr.withdrawn_at is null
                  and (related.trust_status in ('CONFIRMED', 'CORRECTED')
                       or (related_connection.owner_user_id is not null and related_connection.owner_user_id = ?))
                order by rr.id
                """, (rs, row) -> new RecordRelation(rs.getLong("id"), 0, rs.getLong("project_id"), rs.getLong("from_record_id"),
                rs.getLong("to_record_id"), rs.getString("relation_type"), rs.getLong("related_record_id"),
                rs.getString("related_record_code"), rs.getString("related_record_title"), rs.getString("note")),
                recordId, recordId, recordId, actorId);
    }

    private Query query(long actorId, long projectId, RecordQueryService.Filters filters, RecordQueryService.Cursor cursor) {
        StringBuilder where = new StringBuilder(RecordVisibility.visible("wr", "scope"));
        List<Object> arguments = new ArrayList<>(List.of(actorId, projectId));
        in(where, arguments, "wr.record_type", "record_type", filters.recordTypes());
        in(where, arguments, "wr.trust_status", "record_trust_status", filters.trustStatuses());
        if (filters.occurredFrom() != null) { where.append(" and wr.occurred_on >= ?"); arguments.add(filters.occurredFrom()); }
        if (filters.occurredTo() != null) { where.append(" and wr.occurred_on <= ?"); arguments.add(filters.occurredTo()); }
        if (cursor != null) { where.append(" and (wr.occurred_on, wr.id) < (?, ?)"); arguments.add(cursor.occurredOn()); arguments.add(cursor.id()); }
        return new Query(where.toString(), arguments);
    }

    private void in(StringBuilder where, List<Object> arguments, String column, String enumType, List<String> values) {
        if (values.isEmpty()) return;
        where.append(" and ").append(column).append(" in (").append(("?::" + enumType + ",").repeat(values.size()));
        where.setLength(where.length() - 1);
        where.append(")");
        arguments.addAll(values);
    }

    private String select() {
        return """
                select wr.id, wr.code, wr.project_id, wr.record_type, wr.title, wr.summary, wr.original_excerpt,
                       wr.occurred_on, wr.trust_status, wr.author_name, owner.name as owner_name, wr.due_on,
                       wr.version, wr.source_note_id, pn.code as source_note_code, pn.author_id as source_note_author_id,
                       sc.id as source_connection_id, sc.owner_user_id as source_owner_user_id, sc.channel as source_channel,
                       rs.id as source_id, rs.display_name as source_display_name, rs.external_ref as source_external_ref,
                       confirmer.name as confirmed_by_name, wr.confirmed_at,
                       (select successor.code from record_relations supersession
                        join work_records successor on successor.id = supersession.from_record_id
                        where supersession.relation_type = 'SUPERSEDES' and supersession.to_record_id = wr.id
                          and supersession.withdrawn_at is null
                          and successor.trust_status in ('CONFIRMED', 'CORRECTED')
                        order by supersession.id limit 1) as superseded_by_code
                """;
    }

    private RecordRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RecordRow(rs.getLong("id"), rs.getString("code"), rs.getLong("project_id"), rs.getString("record_type"),
                rs.getString("title"), rs.getString("summary"), rs.getString("original_excerpt"),
                rs.getObject("occurred_on", LocalDate.class), rs.getString("trust_status"), rs.getString("author_name"),
                rs.getString("owner_name"), rs.getObject("due_on", LocalDate.class), rs.getLong("version"),
                nullableLong(rs, "source_note_id"), rs.getString("source_note_code"), nullableLong(rs, "source_note_author_id"),
                nullableLong(rs, "source_connection_id"), nullableLong(rs, "source_owner_user_id"), rs.getString("source_channel"),
                nullableLong(rs, "source_id"), rs.getString("source_display_name"), rs.getString("source_external_ref"),
                rs.getString("confirmed_by_name"), rs.getObject("confirmed_at", OffsetDateTime.class),
                rs.getString("superseded_by_code"));
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record Query(String where, List<Object> arguments) { }

    public record RecordRow(long id, String code, long projectId, String recordType, String title, String summary,
                            String originalExcerpt, LocalDate occurredOn, String trustStatus, String authorName,
                            String ownerName, LocalDate dueOn, long version, Long sourceNoteId, String sourceNoteCode,
                            Long sourceNoteAuthorId, Long sourceConnectionId, Long sourceOwnerUserId, String sourceChannel,
                            Long sourceId, String sourceDisplayName, String sourceExternalRef, String confirmedByName,
                            OffsetDateTime confirmedAt, String supersededByCode) { }
    public record RecordRevision(int revisionNo, String changedByName, String changeReason, String beforeSummary,
                                 String afterSummary, OffsetDateTime changedAt) { }
    public record RecordRelation(long id, long version, long projectId, long fromRecordId, long toRecordId, String relationType,
                                 long relatedRecordId, String relatedRecordCode, String relatedRecordTitle, String note) { }
}
