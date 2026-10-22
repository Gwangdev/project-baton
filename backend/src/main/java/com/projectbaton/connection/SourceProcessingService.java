package com.projectbaton.connection;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.projectbaton.audit.AuditService;
import com.projectbaton.jobs.JobRepository;
import com.projectbaton.project.ProjectAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceProcessingService {
    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;
    private final JobRepository jobs;
    private final AuditService audit;

    public SourceProcessingService(JdbcTemplate jdbc, ProjectAccessPolicy projects, JobRepository jobs, AuditService audit) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.jobs = jobs;
        this.audit = audit;
    }

    public void authorize(long actorId, long connectionId, long sourceId) {
        allow(actorId, find(connectionId, sourceId, false));
    }

    @Transactional
    public ProcessingAttempt retry(long actorId, long connectionId, long sourceId) {
        Source initial = find(connectionId, sourceId, false);
        lockProject(initial.projectId());
        Source source = find(connectionId, sourceId, true);
        allow(actorId, source);
        if (!"FAILED".equals(source.processingStatus())) {
            throw new SourceProcessingConflictException("Only failed sources can be retried.");
        }
        jdbc.update("""
                update record_sources set processing_status='PENDING', failure_code=null where id=?
                """, source.id());
        jobs.enqueue("STRUCTURE", source.id(), null, "structure-retry-source:" + source.id() + ":" + UUID.randomUUID(),
                OffsetDateTime.now().plusMinutes(30));
        audit.record(actorId, "RETRY_SOURCE_PROCESSING", "record_source", Long.toString(source.id()), "SUCCESS", null);
        return new ProcessingAttempt(source.id(), "PENDING");
    }

    private Source find(long connectionId, long sourceId, boolean lock) {
        List<Source> sources = jdbc.query("""
                select rs.id, rs.project_id, rs.processing_status, sc.owner_user_id
                from record_sources rs join source_connections sc on sc.id=rs.source_connection_id
                where rs.id=? and rs.source_connection_id=?%s
                """.formatted(lock ? " for update of rs" : ""),
                (rs, row) -> new Source(rs.getLong("id"), rs.getLong("project_id"), rs.getString("processing_status"),
                        rs.getObject("owner_user_id", Long.class)), sourceId, connectionId);
        if (sources.isEmpty()) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
        return sources.getFirst();
    }

    private void allow(long actorId, Source source) {
        projects.requireMember(actorId, source.projectId());
        if (source.ownerUserId() != null) {
            if (source.ownerUserId() != actorId) {
                throw new ProjectAccessPolicy.ProjectNotFoundException();
            }
            return;
        }
        Boolean reviewer = jdbc.queryForObject("""
                select exists(select 1 from project_members where project_id=? and user_id=?
                    and project_role in ('OUTGOING_OWNER','PROJECT_MANAGER') and joined_on<=current_date
                    and (left_on is null or current_date<left_on))
                """, Boolean.class, source.projectId(), actorId);
        if (!Boolean.TRUE.equals(reviewer)) {
            throw new SourceProcessingAccessDeniedException();
        }
    }

    private void lockProject(long projectId) {
        Long found = jdbc.query("select id from projects where id=? for update", rs -> rs.next() ? rs.getLong(1) : null,
                projectId);
        if (found == null) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
    }

    public record ProcessingAttempt(long sourceId, String status) {
    }

    private record Source(long id, long projectId, String processingStatus, Long ownerUserId) {
    }

    public static class SourceProcessingAccessDeniedException extends RuntimeException {
    }

    public static class SourceProcessingConflictException extends RuntimeException {
        public SourceProcessingConflictException(String message) {
            super(message);
        }
    }
}
