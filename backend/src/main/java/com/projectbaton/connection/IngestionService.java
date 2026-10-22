package com.projectbaton.connection;

import java.time.OffsetDateTime;

import com.projectbaton.jobs.JobRepository;
import com.projectbaton.project.ProjectAccessPolicy.ProjectNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionService {
    private final JdbcTemplate jdbc;
    private final SourceConnector fixtureConnector;
    private final JobRepository jobs;

    public IngestionService(JdbcTemplate jdbc, FixtureSourceConnector fixtureConnector, JobRepository jobs) {
        this.jdbc = jdbc;
        this.fixtureConnector = fixtureConnector;
        this.jobs = jobs;
    }

    @Transactional
    public IngestionResult ingest(long connectionId, int batchSize) {
        ConnectionRow connection = jdbc.query("""
                select id, project_id, status::text, connector_kind, checkpoint
                from source_connections where id=? for update
                """, rs -> rs.next()
                ? new ConnectionRow(rs.getLong("id"), rs.getLong("project_id"), rs.getString("status"),
                        rs.getString("connector_kind"), rs.getString("checkpoint"))
                : null, connectionId);
        if (connection == null) {
            throw new ProjectNotFoundException();
        }
        if (!"ACTIVE".equals(connection.status())) {
            throw new InactiveSourceConnectionException();
        }
        if (!"FIXTURE".equals(connection.connectorKind())) {
            throw new UnsupportedConnectorException(connection.connectorKind());
        }

        SourceConnector.SourceBatch batch = fixtureConnector.fetch(connectionId, connection.checkpoint(), batchSize);
        int inserted = 0;
        for (SourceConnector.SourceDocument document : batch.documents()) {
            String existingHash = jdbc.query("""
                    select content_hash from record_sources
                    where source_connection_id=? and external_document_id=? and external_version=?
                    for update
                    """, rs -> rs.next() ? rs.getString(1) : null,
                    connection.id(), document.externalId(), document.version());
            if (existingHash != null) {
                if (!existingHash.equals(document.contentHash())) {
                    throw new SourceVersionConflictException();
                }
                continue;
            }
            Long sourceId = jdbc.query("""
                    insert into record_sources(
                        source_connection_id, display_name, external_ref, collected_at, project_id,
                        external_document_id, external_version, content_hash, source_body,
                        processing_status, source_author_name, source_occurred_on)
                    values (?, ?, ?, now(), ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    returning id
                    """, rs -> rs.next() ? rs.getLong(1) : null,
                    connection.id(), document.externalId(), "fixture://" + document.externalId(), connection.projectId(),
                    document.externalId(), document.version(), document.contentHash(), document.body(), document.authorName(),
                    document.occurredOn());
            jobs.enqueue("STRUCTURE", sourceId, null, "structure-source:" + sourceId,
                    OffsetDateTime.now().plusMinutes(30));
            inserted++;
        }
        jdbc.update("""
                update source_connections
                set checkpoint=?, last_collected_at=now(), last_status='SUCCEEDED', last_collected_count=?,
                    last_failed_count=0, last_message=null, version=version+1
                where id=?
                """, batch.nextCheckpoint(), inserted, connectionId);
        return new IngestionResult(inserted, batch.nextCheckpoint());
    }

    public record IngestionResult(int insertedSources, String nextCheckpoint) {
    }

    private record ConnectionRow(long id, long projectId, String status, String connectorKind, String checkpoint) {
    }

    public static class InactiveSourceConnectionException extends RuntimeException {
    }

    public static class UnsupportedConnectorException extends RuntimeException {
        public UnsupportedConnectorException(String connectorKind) {
            super("Unsupported connector: " + connectorKind);
        }
    }

    public static class SourceVersionConflictException extends RuntimeException {
    }
}
