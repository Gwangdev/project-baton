package com.projectbaton.connection;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.projectbaton.audit.AuditService;
import com.projectbaton.identity.SystemAdminAccessPolicy;
import com.projectbaton.project.ProjectAccessPolicy;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.PageMeta;
import com.projectbaton.shared.ReferenceConflictException;
import com.projectbaton.shared.ReferenceConflictProblem;
import com.projectbaton.shared.StaleVersionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class SourceConnectionService {

    private static final Set<String> CHANNELS = Set.of("EMAIL", "APPROVAL", "MESSENGER", "MEETING_MINUTES", "CLOUD_PROJECT_FOLDER");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "PAUSED", "DISCONNECTED");

    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;
    private final SystemAdminAccessPolicy admins;
    private final AuditService audit;

    public SourceConnectionService(JdbcTemplate jdbc, ProjectAccessPolicy projects, SystemAdminAccessPolicy admins,
                                   AuditService audit) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.admins = admins;
        this.audit = audit;
    }

    public void authorizeCreate(long actorId, long projectId, JsonNode body) {
        CreateCommand command = createCommand(body);
        if (command.personal()) projects.requireMember(actorId, projectId);
        else admins.requireSystemAdmin(actorId);
    }

    public ConnectionList list(long actorId, long projectId, String channel, int limit, int offset) {
        projects.requireMember(actorId, projectId);
        if (channel != null && !CHANNELS.contains(channel)) throw new InvalidInputException();
        String filter = channel == null ? null : channel;
        long total = jdbc.queryForObject("""
                select count(*) from source_connections
                where project_id = ? and (owner_user_id is null or owner_user_id = ?)
                  and (cast(? as source_channel) is null or channel = cast(? as source_channel))
                """, Long.class, projectId, actorId, filter, filter);
        List<SourceConnection> items = jdbc.query("""
                select id, project_id, owner_user_id, channel::text, display_name, external_ref, status::text, health::text,
                       last_collected_at, version
                from source_connections
                where project_id = ? and (owner_user_id is null or owner_user_id = ?)
                  and (cast(? as source_channel) is null or channel = cast(? as source_channel))
                order by created_at desc, id desc limit ? offset ?
                """, (rs, row) -> connection(rs), projectId, actorId, filter, filter, limit, offset);
        return new ConnectionList(items, new PageMeta(total, limit, offset, null));
    }

    @Transactional
    public SourceConnection create(long actorId, long projectId, JsonNode body) {
        CreateCommand command = createCommand(body);
        lockProject(projectId);
        if (command.personal()) projects.requireMember(actorId, projectId);
        else admins.requireSystemAdmin(actorId);
        long id = jdbc.queryForObject("""
                insert into source_connections (project_id, owner_user_id, channel, display_name, external_ref, connector_kind)
                values (?, ?, cast(? as source_channel), ?, ?, 'FIXTURE') returning id
                """, Long.class, projectId, command.personal() ? actorId : null, command.channel(), command.displayName(),
                command.externalRef());
        audit.record(actorId, "CREATE_SOURCE_CONNECTION", "source_connection", Long.toString(id), "SUCCESS", null);
        return find(id);
    }

    public SourceConnectionDetail detail(long actorId, long connectionId) {
        ConnectionRow row = findRow(connectionId);
        authorizeDetail(actorId, row);
        return detail(row);
    }

    public void authorizeManagement(long actorId, long connectionId) {
        authorizeWrite(actorId, findRow(connectionId));
    }

    @Transactional
    public SourceConnection update(long actorId, long connectionId, long expectedVersion, JsonNode body) {
        UpdateCommand command = updateCommand(body);
        ConnectionRow existing = findRow(connectionId);
        lockProject(existing.projectId());
        ConnectionRow current = lock(connectionId);
        authorizeWrite(actorId, current);
        if (current.version() != expectedVersion) throw new StaleVersionException();
        int changed = jdbc.update("""
                update source_connections set display_name = coalesce(?, display_name), external_ref = coalesce(?, external_ref),
                    status = cast(coalesce(?, status::text) as connection_status), version = version + 1
                where id = ? and version = ?
                """, command.displayName(), command.externalRef(), command.status(), connectionId, expectedVersion);
        if (changed == 0) throw new StaleVersionException();
        audit.record(actorId, "UPDATE_SOURCE_CONNECTION", "source_connection", Long.toString(connectionId), "SUCCESS", null);
        return find(connectionId);
    }

    @Transactional
    public void delete(long actorId, long connectionId, long expectedVersion) {
        ConnectionRow existing = findRow(connectionId);
        lockProject(existing.projectId());
        ConnectionRow current = lock(connectionId);
        authorizeWrite(actorId, current);
        if (current.version() != expectedVersion) throw new StaleVersionException();
        long sources = jdbc.queryForObject("select count(*) from record_sources where source_connection_id = ?", Long.class, connectionId);
        if (sources > 0) throw new ReferenceConflictException(List.of(new ReferenceConflictProblem.BlockingReference(
                "record_sources", Math.toIntExact(sources), "수집된 문서가 있는 연결은 해제 상태로 유지해야 합니다.")));
        int deleted = jdbc.update("delete from source_connections where id = ? and version = ?", connectionId, expectedVersion);
        if (deleted == 0) throw new StaleVersionException();
        audit.record(actorId, "DELETE_SOURCE_CONNECTION", "source_connection", Long.toString(connectionId), "SUCCESS", null);
    }

    @Transactional
    public SourceConnection reconnect(long actorId, long connectionId) {
        ConnectionRow existing = findRow(connectionId);
        lockProject(existing.projectId());
        ConnectionRow current = lock(connectionId);
        authorizeWrite(actorId, current);
        jdbc.update("update source_connections set status = 'ACTIVE', health = 'NORMAL', version = version + 1 where id = ?", connectionId);
        audit.record(actorId, "RECONNECT_SOURCE_CONNECTION", "source_connection", Long.toString(connectionId), "SUCCESS", null);
        return find(connectionId);
    }

    public CollectedFileList files(long actorId, long connectionId, int limit, int offset) {
        ConnectionRow connection = findRow(connectionId);
        authorizeFiles(actorId, connection);
        long total = jdbc.queryForObject("select count(*) from record_sources where source_connection_id = ?", Long.class, connectionId);
        List<CollectedFile> items = jdbc.query("""
                select rs.id, rs.display_name, rs.external_ref, rs.collected_at, rs.processing_status, rs.failure_code,
                    coalesce(array_agg(wr.code order by wr.id) filter (where wr.id is not null), array[]::varchar[]) codes
                from record_sources rs left join work_records wr on wr.record_source_id = rs.id
                where rs.source_connection_id = ?
                group by rs.id order by rs.collected_at desc, rs.id desc limit ? offset ?
                """, (rs, row) -> new CollectedFile(rs.getLong("id"), rs.getString("display_name"), rs.getString("external_ref"),
                rs.getObject("collected_at", OffsetDateTime.class), List.of((String[]) rs.getArray("codes").getArray()),
                rs.getString("processing_status"), rs.getString("failure_code")), connectionId, limit, offset);
        return new CollectedFileList(items, new PageMeta(total, limit, offset, null));
    }

    private void authorizeDetail(long actorId, ConnectionRow row) {
        if (row.ownerUserId() != null) {
            if (row.ownerUserId() != actorId) throw new ProjectAccessPolicy.ProjectNotFoundException();
            projects.requireMember(actorId, row.projectId());
            return;
        }
        admins.requireSystemAdmin(actorId);
    }

    private void authorizeFiles(long actorId, ConnectionRow row) {
        if (row.ownerUserId() != null) {
            if (row.ownerUserId() != actorId) throw new ProjectAccessPolicy.ProjectNotFoundException();
            projects.requireMember(actorId, row.projectId());
            return;
        }
        projects.requireMember(actorId, row.projectId());
        Boolean reviewer = jdbc.queryForObject("""
                select exists (select 1 from project_members where project_id = ? and user_id = ?
                    and project_role in ('OUTGOING_OWNER', 'PROJECT_MANAGER') and joined_on <= current_date
                    and (left_on is null or current_date < left_on))
                """, Boolean.class, row.projectId(), actorId);
        if (!Boolean.TRUE.equals(reviewer)) throw new ConnectionAccessDeniedException();
    }

    private void authorizeWrite(long actorId, ConnectionRow row) {
        if (row.ownerUserId() == null) {
            admins.requireSystemAdmin(actorId);
            return;
        }
        if (row.ownerUserId() != actorId) throw new ProjectAccessPolicy.ProjectNotFoundException();
        projects.requireMember(actorId, row.projectId());
    }

    private SourceConnectionDetail detail(ConnectionRow row) {
        long files = jdbc.queryForObject("select count(*) from record_sources where source_connection_id = ?", Long.class, row.id());
        long drafts = jdbc.queryForObject("""
                select count(*) from work_records wr join record_sources rs on rs.id = wr.record_source_id
                where rs.source_connection_id = ? and wr.trust_status = 'AUTO_DRAFT'
                """, Long.class, row.id());
        return new SourceConnectionDetail(row.id(), row.projectId(), row.ownerUserId(), row.channel(), row.displayName(), row.externalRef(),
                row.status(), row.health(), row.lastCollectedAt(), row.version(), row.lastStatus(), row.lastCollectedCount(),
                row.lastFailedCount(), row.lastMessage(), files, drafts);
    }

    private SourceConnection find(long id) {
        ConnectionRow row = findRow(id);
        return new SourceConnection(row.id(), row.projectId(), row.ownerUserId(), row.channel(), row.displayName(), row.externalRef(),
                row.status(), row.health(), row.lastCollectedAt(), row.version());
    }

    private SourceConnection connection(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SourceConnection(rs.getLong("id"), rs.getLong("project_id"), rs.getObject("owner_user_id", Long.class),
                rs.getString("channel"), rs.getString("display_name"), rs.getString("external_ref"), rs.getString("status"),
                rs.getString("health"), rs.getObject("last_collected_at", OffsetDateTime.class), rs.getLong("version"));
    }

    private ConnectionRow findRow(long id) {
        List<ConnectionRow> rows = jdbc.query("""
                select id, project_id, owner_user_id, channel::text, display_name, external_ref, status::text, health::text,
                       last_collected_at, version, last_status::text, last_collected_count, last_failed_count, last_message
                from source_connections where id = ?
                """, (rs, row) -> row(rs), id);
        if (rows.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        return rows.getFirst();
    }

    private ConnectionRow lock(long id) {
        List<ConnectionRow> rows = jdbc.query("""
                select id, project_id, owner_user_id, channel::text, display_name, external_ref, status::text, health::text,
                       last_collected_at, version, last_status::text, last_collected_count, last_failed_count, last_message
                from source_connections where id = ? for update
                """, (rs, row) -> row(rs), id);
        if (rows.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        return rows.getFirst();
    }

    private ConnectionRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConnectionRow(rs.getLong("id"), rs.getLong("project_id"), rs.getObject("owner_user_id", Long.class),
                rs.getString("channel"), rs.getString("display_name"), rs.getString("external_ref"), rs.getString("status"),
                rs.getString("health"), rs.getObject("last_collected_at", OffsetDateTime.class), rs.getLong("version"),
                rs.getString("last_status"), rs.getInt("last_collected_count"), rs.getInt("last_failed_count"), rs.getString("last_message"));
    }

    private void lockProject(long projectId) {
        Long id = jdbc.query("select id from projects where id = ? for update", rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (id == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
    }

    private CreateCommand createCommand(JsonNode body) {
        only(body, Set.of("channel", "displayName", "externalRef"));
        String channel = text(body, "channel", 30, true);
        if (!CHANNELS.contains(channel)) throw new InvalidInputException();
        return new CreateCommand(channel, text(body, "displayName", 200, true), text(body, "externalRef", 300, true));
    }

    private UpdateCommand updateCommand(JsonNode body) {
        only(body, Set.of("displayName", "externalRef", "status"));
        if (body.size() == 0) throw new InvalidInputException();
        String status = body.has("status") ? text(body, "status", 20, true) : null;
        if (status != null && !STATUSES.contains(status)) throw new InvalidInputException();
        return new UpdateCommand(body.has("displayName") ? text(body, "displayName", 200, true) : null,
                body.has("externalRef") ? text(body, "externalRef", 300, true) : null, status);
    }

    private void only(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject() || body.properties().stream().anyMatch(entry -> !fields.contains(entry.getKey())))
            throw new InvalidInputException();
    }

    private String text(JsonNode body, String field, int max, boolean required) {
        JsonNode value = body.get(field);
        if (value == null) { if (required) throw new InvalidInputException(); return null; }
        if (!value.isTextual() || value.stringValue().isBlank() || value.stringValue().length() > max) throw new InvalidInputException();
        return value.stringValue();
    }

    public record SourceConnection(long id, long projectId, Long ownerUserId, String channel, String displayName,
                                   String externalRef, String status, String health, OffsetDateTime lastCollectedAt, long version) { }
    public record ConnectionList(List<SourceConnection> items, PageMeta page) { }
    public record SourceConnectionDetail(long id, long projectId, Long ownerUserId, String channel, String displayName,
                                         String externalRef, String status, String health, OffsetDateTime lastCollectedAt, long version,
                                         String lastStatus, int lastCollectedCount, int lastFailedCount, String lastMessage,
                                         long collectedFileCount, long draftCandidateCount) { }
    public record CollectedFile(long id, String displayName, String externalRef, OffsetDateTime collectedAt,
                                List<String> derivedRecordCodes, String processingStatus, String failureCode) { }
    public record CollectedFileList(List<CollectedFile> items, PageMeta page) { }
    private record CreateCommand(String channel, String displayName, String externalRef) { boolean personal() { return "CLOUD_PROJECT_FOLDER".equals(channel); } }
    private record UpdateCommand(String displayName, String externalRef, String status) { }
    private record ConnectionRow(long id, long projectId, Long ownerUserId, String channel, String displayName, String externalRef,
                                 String status, String health, OffsetDateTime lastCollectedAt, long version, String lastStatus,
                                 int lastCollectedCount, int lastFailedCount, String lastMessage) { }
    public static class ConnectionAccessDeniedException extends RuntimeException { }
}
