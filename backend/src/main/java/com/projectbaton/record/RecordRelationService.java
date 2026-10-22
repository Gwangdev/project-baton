package com.projectbaton.record;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.projectbaton.audit.AuditService;
import com.projectbaton.project.ProjectAccessPolicy;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.JsonIds;
import com.projectbaton.shared.StaleVersionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class RecordRelationService {

    private static final Set<String> TYPES = Set.of("SUPERSEDES", "REFERENCES", "CONFLICTS_WITH");
    private static final Set<String> CONFIRMED = Set.of("CONFIRMED", "CORRECTED");
    private final JdbcTemplate jdbc;
    private final ProjectAccessPolicy projects;
    private final RecordRepository records;
    private final AuditService audit;

    public RecordRelationService(JdbcTemplate jdbc, ProjectAccessPolicy projects, RecordRepository records, AuditService audit) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.records = records;
        this.audit = audit;
    }

    public void authorize(long actorId, long projectId) {
        projects.requireManager(actorId, projectId);
    }

    /** 철회의 PM 확인으로, 멱등 재실행보다 먼저 돈다. 누가 할 수 있는지는 관계의 프로젝트가 정한다. */
    public void authorizeWithdrawal(long actorId, long relationId) {
        projects.requireManager(actorId, projectOf(relationId));
    }

    /**
     * 잘못 등록한 관계를 철회한다. 행은 시각, 사람, 사유와 함께 남는다. 관계를 읽는 모든 확인(새 관계의 순환과 두
     * 번째 대체 확인, 기록 상세, 인수인계 패키지)은 철회되지 않은 관계만 읽는다. 이전 패키지가 이 관계로 만든 충돌
     * 항목은 그 고정된 회차에 남아 해소할 수 없고, 새 패키지를 요구한다(HandoverReadinessCheck).
     */
    @Transactional
    public RecordRelation withdraw(long actorId, long relationId, long expectedVersion, JsonNode body) {
        String reason = reason(body);
        long projectId = projectOf(relationId);
        lockProject(projectId);
        projects.requireManager(actorId, projectId);
        List<long[]> rows = jdbc.query("select version, case when withdrawn_at is null then 0 else 1 end from record_relations where id = ? for update",
                (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)}, relationId);
        if (rows.isEmpty()) throw new ProjectAccessPolicy.ProjectNotFoundException();
        if (rows.getFirst()[0] != expectedVersion) throw new StaleVersionException();
        if (rows.getFirst()[1] == 1) throw new RelationConflictException("The record relation is already withdrawn.");
        jdbc.update("""
                update record_relations set withdrawn_at = now(), withdrawn_by = ?, withdrawal_reason = ?, version = version + 1
                where id = ?
                """, actorId, reason, relationId);
        audit.record(actorId, "WITHDRAW_RECORD_RELATION", "record_relation", Long.toString(relationId), "SUCCESS", null);
        return find(relationId);
    }

    private long projectOf(long relationId) {
        Long projectId = jdbc.query("select project_id from record_relations where id = ?", rs -> rs.next() ? rs.getLong(1) : null, relationId);
        if (projectId == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
        return projectId;
    }

    private String reason(JsonNode body) {
        if (body == null || !body.isObject() || body.properties().stream().anyMatch(entry -> !"reason".equals(entry.getKey()))) {
            throw new InvalidInputException();
        }
        String reason = text(body, "reason", 300, true);
        if (reason.isBlank()) throw new InvalidInputException();
        return reason;
    }

    @Transactional
    public RecordRelation create(long actorId, long projectId, JsonNode body) {
        Command command = command(body);
        lockProject(projectId);
        projects.requireManager(actorId, projectId);
        Command normalized = normalize(command);
        lockRecords(actorId, projectId, normalized.fromRecordId(), normalized.toRecordId());
        if ("SUPERSEDES".equals(normalized.relationType()) && createsCycle(projectId, normalized.fromRecordId(), normalized.toRecordId())) {
            throw new RelationConflictException("A supersession relation cannot create a cycle.");
        }
        if ("SUPERSEDES".equals(normalized.relationType()) && alreadySuperseded(projectId, normalized.fromRecordId(), normalized.toRecordId())) {
            throw new RelationConflictException("The record is already superseded by another record.");
        }
        if (!"REFERENCES".equals(normalized.relationType())
                && pairAlreadySettledOtherwise(projectId, normalized.fromRecordId(), normalized.toRecordId(), normalized.relationType())) {
            throw new RelationConflictException("The two records already have a superseding or conflicting relation.");
        }
        try {
            long id = jdbc.queryForObject("""
                    insert into record_relations (project_id, from_record_id, to_record_id, relation_type, note)
                    values (?, ?, ?, cast(? as record_relation_type), ?) returning id
                    """, Long.class, projectId, normalized.fromRecordId(), normalized.toRecordId(), normalized.relationType(), normalized.note());
            audit.record(actorId, "CREATE_RECORD_RELATION", "record_relation", Long.toString(id), "SUCCESS", null);
            return find(id);
        } catch (DataIntegrityViolationException exception) {
            throw new RelationConflictException("The record relation already exists or is invalid.");
        }
    }

    private Command normalize(Command command) {
        if (!"CONFLICTS_WITH".equals(command.relationType()) || command.fromRecordId() < command.toRecordId()) return command;
        return new Command(command.toRecordId(), command.fromRecordId(), command.relationType(), command.note());
    }

    private boolean createsCycle(long projectId, long from, long to) {
        Boolean cycle = jdbc.queryForObject("""
                with recursive reachable(id) as (
                    select to_record_id from record_relations
                    where project_id = ? and relation_type = 'SUPERSEDES' and from_record_id = ? and withdrawn_at is null
                    union
                    select relation.to_record_id from record_relations relation
                    join reachable path on relation.from_record_id = path.id
                    where relation.project_id = ? and relation.relation_type = 'SUPERSEDES' and relation.withdrawn_at is null
                ) select exists(select 1 from reachable where id = ?)
                """, Boolean.class, projectId, to, projectId, from);
        return Boolean.TRUE.equals(cycle);
    }

    private void lockProject(long projectId) {
        Long locked = jdbc.query("select id from projects where id = ? for update", rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (locked == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
    }

    /**
     * README §5.1: 관계는 사람이 확인한 기록끼리 잇는다. 예전 잠금은 두 기록이 있는지만 확인해서, PM이 검토하지 않은
     * AI 초안이나 제외된 기록을 충돌이나 대체로 엮을 수 있었고 그 관계가 인수인계 패키지를 바꿨다. PM이 볼 수 없는
     * 기록(다른 구성원의 비공개 초안)은 신뢰 상태를 보기 전에 없는 것으로 알려서, 409가 그런 기록의 존재를 확인해
     * 주지 않는다(README §4.2).
     */
    private void lockRecords(long actorId, long projectId, long from, long to) {
        List<String> statuses = jdbc.query("""
                select trust_status::text from work_records where project_id = ? and id in (?, ?) order by id for update
                """, (rs, row) -> rs.getString(1), projectId, from, to);
        if (statuses.size() != 2 || records.findVisibleById(actorId, from) == null || records.findVisibleById(actorId, to) == null) {
            throw new ProjectAccessPolicy.ProjectNotFoundException();
        }
        if (!CONFIRMED.containsAll(statuses)) throw new RelationConflictException("Relations join confirmed records only.");
    }

    /**
     * README §5.1: 이전 기록 하나를 현재 기록 둘이 대체하면 모호하므로 거절한다. 고유 키는 같은 쌍을 두 번 막을
     * 뿐이어서, 이 확인은 대상을 이미 대체하는 다른 기록이 있는지 찾는다. 그 앞에서 잡은 프로젝트 행 잠금이 동시
     * 등록을 차례대로 세운다.
     */
    private boolean alreadySuperseded(long projectId, long from, long to) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from record_relations
                              where project_id = ? and relation_type = 'SUPERSEDES' and to_record_id = ? and from_record_id <> ?
                                and withdrawn_at is null)
                """, Boolean.class, projectId, to, from));
    }

    /**
     * 대체는 두 기록 가운데 어느 쪽이 유효한지 말하고, 충돌은 아직 정해지지 않았다고 말하므로 한 쌍이 둘 다 가질 수는
     * 없다. 둘 다 있으면 한 패키지에 후보가 같은 충돌 항목이 두 개 생겼고, 서로 반대 기록으로 해소할 수 있었다. 고유
     * 키는 같은 유형을 두 번 막을 뿐이어서, 이 확인은 다른 유형을 양방향으로 찾는다. 잘못된 대체를 철회하고 충돌을
     * 등록하는 것이 PM이 판단을 바꾸는 방법이라서, 철회된 관계는 세지 않는다.
     */
    private boolean pairAlreadySettledOtherwise(long projectId, long from, long to, String type) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from record_relations
                              where project_id = ? and relation_type in ('SUPERSEDES', 'CONFLICTS_WITH')
                                and relation_type <> cast(? as record_relation_type) and withdrawn_at is null
                                and least(from_record_id, to_record_id) = least(?, ?) and greatest(from_record_id, to_record_id) = greatest(?, ?))
                """, Boolean.class, projectId, type, from, to, from, to));
    }

    private RecordRelation find(long id) {
        return jdbc.queryForObject("""
                select id, project_id, from_record_id, to_record_id, relation_type::text, note, version, withdrawn_at, withdrawal_reason
                from record_relations where id = ?
                """, (rs, row) -> new RecordRelation(rs.getLong("id"), rs.getLong("project_id"), rs.getLong("from_record_id"),
                rs.getLong("to_record_id"), rs.getString("relation_type"), rs.getString("note"), rs.getLong("version"),
                rs.getObject("withdrawn_at", OffsetDateTime.class), rs.getString("withdrawal_reason")), id);
    }

    private Command command(JsonNode body) {
        if (body == null || !body.isObject() || body.properties().stream().anyMatch(entry -> !Set.of(
                "fromRecordId", "toRecordId", "relationType", "note").contains(entry.getKey()))) throw new InvalidInputException();
        long from = id(body, "fromRecordId");
        long to = id(body, "toRecordId");
        if (from == to) throw new InvalidInputException();
        String type = text(body, "relationType", 30, true);
        if (!TYPES.contains(type)) throw new InvalidInputException();
        String note = body.has("note") ? text(body, "note", 300, false) : null;
        return new Command(from, to, type, note);
    }

    private long id(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (!JsonIds.isId(value)) throw new InvalidInputException();
        return value.longValue();
    }

    private String text(JsonNode body, String field, int max, boolean required) {
        JsonNode value = body.get(field);
        if (value == null) { if (required) throw new InvalidInputException(); return null; }
        if (!value.isTextual() || value.stringValue().length() > max) throw new InvalidInputException();
        return value.stringValue();
    }

    public record RecordRelation(long id, long projectId, long fromRecordId, long toRecordId, String relationType,
                                 String note, long version, OffsetDateTime withdrawnAt, String withdrawalReason) { }
    private record Command(long fromRecordId, long toRecordId, String relationType, String note) { }
    public static class RelationConflictException extends RuntimeException { public RelationConflictException(String message) { super(message); } }
}
