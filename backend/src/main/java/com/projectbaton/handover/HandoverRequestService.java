package com.projectbaton.handover;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.projectbaton.audit.AuditService;
import com.projectbaton.jobs.JobRepository;
import com.projectbaton.project.ProjectAccessPolicy;
import com.projectbaton.shared.InvalidInputException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 패키지 생성을 시작하는 PM 명령: 새 요청, 실패한 생성의 재시도, 재생성. */
@Service
public class HandoverRequestService {
    /** contracts.md C05: 패키지 하나가 snapshot으로 담는 확정 기록의 최대 수. */
    static final int MAX_SCOPE_RECORDS = 5_000;
    private static final Set<String> RECORD_TYPES =
            Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "SCOPE_EXCEPTION", "OPEN_ISSUE");
    private static final Set<String> REGENERABLE_STATUSES = Set.of("READY", "REVIEW_REQUESTED", "FAILED");

    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;
    private final JobRepository jobs;
    private final AuditService audit;

    public HandoverRequestService(JdbcTemplate jdbc, ProjectAccessPolicy projects, JobRepository jobs, AuditService audit) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.jobs = jobs;
        this.audit = audit;
    }

    /**
     * 멱등 재실행이 저장된 답을 돌려주기 전에 하는 확인. 예전에는 세 명령이 이 자리를 비워 둬서, 첫 호출 뒤 역할(이나
     * 프로젝트)을 잃은 관리자도 저장된 202를 받았다. 해소, 확인, 승인, 관계 명령은 이미 여기서 확인했다.
     */
    @Transactional(readOnly = true)
    public void authorizeRequest(long actor, long projectId) {
        projects.requireManager(actor, projectId);
    }

    /** 프로젝트가 아니라 인수인계를 지정하는 재시도와 재생성에 쓰는 같은 확인. */
    @Transactional(readOnly = true)
    public void authorize(long actor, long handoverId) {
        projects.requireManager(actor, row(handoverId, false).projectId());
    }

    @Transactional
    public Handover request(long actor, long projectId, Command command) {
        lockProject(projectId);
        projects.requireManager(actor, projectId);
        validateScope(command);
        // 두 담당자가 같은 사람이면 모르는 담당자처럼 요청 자체가 모순이다. 저장된 상태와의 충돌이 아니므로 409가
        // 아니라 다른 잘못된 필드처럼 400으로 답한다.
        if (command.outgoingOwnerId() == command.incomingOwnerId()) {
            throw new InvalidInputException("incomingOwnerId", "Must differ from outgoingOwnerId.");
        }
        // 요청자는 이 프로젝트를 볼 수 있으므로, 프로젝트에 속하지 않은 지정 담당자는 요청의 잘못이다(필드를 밝힌
        // 400). 예전에는 프로젝트를 숨기는 404로 드러나서 관리자에게 아무것도 알려 주지 못했다.
        if (!projects.isMember(command.outgoingOwnerId(), projectId)) {
            throw InvalidInputException.notAMember("outgoingOwnerId");
        }
        if (!projects.isMember(command.incomingOwnerId(), projectId)) {
            throw InvalidInputException.notAMember("incomingOwnerId");
        }
        Boolean ongoing = jdbc.queryForObject("""
                select exists(select 1 from handovers where project_id=? and status in ('REQUESTED','GENERATING','READY','REVIEW_REQUESTED','FAILED'))
                """, Boolean.class, projectId);
        if (Boolean.TRUE.equals(ongoing)) {
            throw new HandoverConflictException("An active handover already exists.");
        }
        long id = jdbc.queryForObject("""
                insert into handovers(code,project_id,requested_by,outgoing_owner_id,incoming_owner_id,effective_on,
                                      scope_from,scope_to,include_note_derived_records,status)
                values('HO-'||nextval('handovers_id_seq'),?,?,?,?,?,?,?,?,'REQUESTED') returning id
                """, Long.class, projectId, actor, command.outgoingOwnerId(), command.incomingOwnerId(), command.effectiveOn(),
                command.scopeFrom(), command.scopeTo(), command.includeNotes());
        replaceScopeTypes(id, command.types());
        snapshotAndEnqueue(id, 1, projectId, actor, command, "build-package:" + id + ":1");
        audit.record(actor, "REQUEST_HANDOVER", "handover", Long.toString(id), "SUCCESS", null);
        return view(id);
    }

    @Transactional
    public Handover retry(long actor, long handoverId, long expectedVersion) {
        Row row = lockHandover(handoverId);
        projects.requireManager(actor, row.projectId());
        checkVersion(row, expectedVersion);
        if (!"FAILED".equals(row.status())) {
            throw new HandoverConflictException("Only failed handovers can be retried.");
        }
        jdbc.update("update handovers set status='REQUESTED',failure_reason=null,version=version+1 where id=?", handoverId);
        jdbc.update("update handover_generations set status='REQUESTED' where handover_id=? and generation=?", handoverId, row.generation());
        jobs.enqueue("BUILD_PACKAGE", handoverId, row.generation(),
                "build-package:" + handoverId + ":" + row.generation() + ":retry:" + (row.version() + 1), deadline());
        audit.record(actor, "RETRY_HANDOVER", "handover", Long.toString(handoverId), "SUCCESS", null);
        return view(handoverId);
    }

    @Transactional
    public Handover regenerate(long actor, long handoverId, long expectedVersion, Command command) {
        Row row = lockHandover(handoverId);
        projects.requireManager(actor, row.projectId());
        validateScope(command);
        checkVersion(row, expectedVersion);
        if (!REGENERABLE_STATUSES.contains(row.status())) {
            throw new HandoverConflictException("Handover cannot be regenerated in its current state.");
        }
        int next = row.generation() + 1;
        jdbc.update("""
                update handovers
                set generation=?,scope_from=?,scope_to=?,include_note_derived_records=?,status='REQUESTED',failure_reason=null,
                    version=version+1
                where id=?
                """, next, command.scopeFrom(), command.scopeTo(), command.includeNotes(), handoverId);
        replaceScopeTypes(handoverId, command.types());
        snapshotAndEnqueue(handoverId, next, row.projectId(), actor, command, "build-package:" + handoverId + ":" + next);
        audit.record(actor, "REGENERATE_HANDOVER", "handover", Long.toString(handoverId), "SUCCESS", null);
        return view(handoverId);
    }

    /**
     * 범위 오류는 호출자의 잘못이다. 뒤집힌 기간, 빈 유형 목록, 모르는 유형이 그렇다. 예전에는 DB까지 가서 CHECK
     * 위반이나 enum 변환 실패로 500이 되어 돌아왔다.
     */
    private void validateScope(Command command) {
        if (command.scopeFrom() == null || command.scopeTo() == null || command.scopeFrom().isAfter(command.scopeTo())
                || command.types() == null || command.types().isEmpty() || !RECORD_TYPES.containsAll(command.types())) {
            throw new InvalidInputException();
        }
    }

    private void replaceScopeTypes(long handoverId, Set<String> types) {
        jdbc.update("delete from handover_scope_types where handover_id=?", handoverId);
        for (String type : types) {
            jdbc.update("insert into handover_scope_types(handover_id,record_type) values(?,cast(? as record_type))", handoverId, type);
        }
    }

    /**
     * contracts.md C05는 패키지를 기록 5,000건으로 제한하고 PM에게 범위를 좁히라고 한다. snapshot을 복사하기 전에
     * 개수를 세어서, 너무 넓은 범위는 큰 insert 뒤 rollback이 아니라 인덱스 count 한 번의 비용만 든다. 예외가 같은
     * 트랜잭션에서 먼저 쓴 인수인계 행을 rollback한다.
     */
    private void snapshotAndEnqueue(long handoverId, int generation, long projectId, long actor, Command command, String dedupKey) {
        Integer inScope = jdbc.queryForObject("select count(*) from work_records wr " + SCOPE_FILTER, Integer.class,
                handoverId, projectId, command.scopeFrom(), command.scopeTo(), command.includeNotes());
        if (inScope != null && inScope > MAX_SCOPE_RECORDS) {
            throw new ScopeTooLargeException();
        }
        long generationId = jdbc.queryForObject("""
                insert into handover_generations(handover_id,generation,project_id,created_by,status)
                values(?,?,?,?,'REQUESTED') returning id
                """, Long.class, handoverId, generation, projectId, actor);
        int included = jdbc.update("""
                insert into handover_snapshot_records(generation_id,project_id,work_record_id,record_version,content_json,trust_status)
                select ?,wr.project_id,wr.id,wr.version,
                       jsonb_build_object('id',wr.id,'title',wr.title,'summary',wr.summary,'recordType',wr.record_type,
                                          'occurredOn',wr.occurred_on,'dueOn',wr.due_on),
                       wr.trust_status
                from work_records wr
                """ + SCOPE_FILTER, generationId, handoverId, projectId, command.scopeFrom(), command.scopeTo(), command.includeNotes());
        jdbc.update("update handovers set included_count=? where id=?", included, handoverId);
        jobs.enqueue("BUILD_PACKAGE", handoverId, generation, dedupKey, deadline());
    }

    private static final String SCOPE_FILTER = """
            join handover_scope_types st on st.handover_id=? and st.record_type=wr.record_type
            where wr.project_id=? and wr.trust_status in ('CONFIRMED','CORRECTED') and wr.occurred_on between ? and ?
              and (? or wr.source_note_id is null)
            """;

    /** README §6: 패키지 생성은 두 시간이 지나면 포기한다. */
    private OffsetDateTime deadline() {
        return OffsetDateTime.now().plusHours(2);
    }

    private Row lockHandover(long handoverId) {
        Row row = row(handoverId, false);
        lockProject(row.projectId());
        return row(handoverId, true);
    }

    private Row row(long id, boolean lock) {
        List<Row> rows = jdbc.query("select id,project_id,status::text,generation,version from handovers where id=?" + (lock ? " for update" : ""),
                (rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getLong(5)), id);
        if (rows.isEmpty()) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
        return rows.getFirst();
    }

    private void checkVersion(Row row, long expected) {
        if (row.version() != expected) {
            throw new HandoverStaleException();
        }
    }

    private Handover view(long id) {
        Row row = row(id, false);
        return new Handover(row.id(), row.status(), row.generation(), row.version());
    }

    private void lockProject(long projectId) {
        Long locked = jdbc.query("select id from projects where id=? for update", rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (locked == null) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
    }

    private record Row(long id, long projectId, String status, int generation, long version) { }

    public record Command(long outgoingOwnerId, long incomingOwnerId, LocalDate effectiveOn, LocalDate scopeFrom, LocalDate scopeTo,
                          Set<String> types, boolean includeNotes) { }

    public record Handover(long id, String status, int generation, long version) { }

    public static class HandoverConflictException extends RuntimeException {
        public HandoverConflictException(String message) {
            super(message);
        }
    }

    public static class HandoverStaleException extends RuntimeException { }

    public static class ScopeTooLargeException extends RuntimeException { }
}
