package com.projectbaton.record;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.projectbaton.project.ProjectAccessPolicy;
import com.projectbaton.project.ProjectAdminService;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.JsonIds;
import com.projectbaton.shared.PageMeta;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class RecordQueryService {

    private static final Set<String> RECORD_TYPES = Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "SCOPE_EXCEPTION", "OPEN_ISSUE");
    private static final Set<String> TRUST_STATUSES = Set.of("AUTO_DRAFT", "CONFIRMED", "CORRECTED", "DISMISSED");

    private final RecordRepository records;
    private final ProjectAccessPolicy projectAccess;
    private final ObjectMapper objectMapper;

    public RecordQueryService(RecordRepository records, ProjectAccessPolicy projectAccess, ObjectMapper objectMapper) {
        this.records = records;
        this.projectAccess = projectAccess;
        this.objectMapper = objectMapper;
    }

    public RecordList list(long actorId, long projectId, List<String> recordTypes, List<String> trustStatuses,
                           LocalDate occurredFrom, LocalDate occurredTo, int limit, int offset, String encodedCursor) {
        projectAccess.requireMember(actorId, projectId);
        Filters filters = filters(recordTypes, trustStatuses, occurredFrom, occurredTo);
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000 || (encodedCursor != null && offset != 0)) {
            throw new InvalidInputException();
        }
        Cursor cursor = cursor(encodedCursor);
        List<RecordView> items = records.findVisible(actorId, projectId, filters, cursor, limit, offset).stream()
                .map(row -> view(row, actorId)).toList();
        String nextCursor = items.size() == limit ? cursor(items.getLast().occurredOn(), items.getLast().id()) : null;
        return new RecordList(items, new PageMeta(records.countVisible(actorId, projectId, filters), limit, offset, nextCursor));
    }

    public RecordDetail detail(long actorId, long recordId) {
        RecordRepository.RecordRow row = records.findVisibleById(actorId, recordId);
        if (row == null) throw new ProjectAccessPolicy.ProjectNotFoundException();
        RecordView view = view(row, actorId);
        boolean privateSourceNotOwned = row.sourceOwnerUserId() != null && row.sourceOwnerUserId() != actorId;
        String excerpt = privateSourceNotOwned ? null : row.originalExcerpt();
        return new RecordDetail(view.id(), view.code(), view.projectId(), view.recordType(), view.title(), view.summary(), view.occurredOn(),
                view.trustStatus(), view.authorName(), view.ownerName(), view.dueOn(), view.source(), view.sourceNoteCode(),
                view.supersededByCode(), view.version(),
                excerpt, row.confirmedByName(), row.confirmedAt(), records.revisions(recordId), records.relations(actorId, recordId));
    }

    private Filters filters(List<String> recordTypes, List<String> trustStatuses, LocalDate occurredFrom, LocalDate occurredTo) {
        List<String> types = recordTypes == null ? List.of() : recordTypes;
        List<String> statuses = trustStatuses == null ? List.of() : trustStatuses;
        if (!RECORD_TYPES.containsAll(types) || !TRUST_STATUSES.containsAll(statuses)
                || (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo))) {
            throw new InvalidInputException();
        }
        return new Filters(types, statuses, occurredFrom, occurredTo);
    }

    private RecordView view(RecordRepository.RecordRow row, long actorId) {
        boolean privateSourceNotOwned = row.sourceOwnerUserId() != null && row.sourceOwnerUserId() != actorId;
        SourceRef source = row.sourceId() == null || privateSourceNotOwned ? null
                : new SourceRef(row.sourceId(), row.sourceDisplayName(), row.sourceChannel(), row.sourceExternalRef());
        String sourceNoteCode = row.sourceNoteAuthorId() != null && row.sourceNoteAuthorId() == actorId ? row.sourceNoteCode() : null;
        return new RecordView(row.id(), row.code(), row.projectId(), row.recordType(), row.title(), row.summary(), row.occurredOn(),
                row.trustStatus(), row.authorName(), row.ownerName(), row.dueOn(), source, sourceNoteCode, row.supersededByCode(),
                row.version());
    }

    private Cursor cursor(String encoded) {
        if (encoded == null) return null;
        try {
            JsonNode value = objectMapper.readTree(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
            // 클라이언트는 아무 cursor나 보낼 수 있다. 해석할 수 없는 날짜(DateTimeParseException은
            // IllegalArgumentException이 아니다), 문자열이 아닌 날짜, long 범위를 벗어난 id는 500으로 끝났고, 타입이
            // 틀린 id는 0으로 읽혔다. 모두 호출자의 실수다.
            if (!value.isObject() || !value.path("occurredOn").isTextual() || !JsonIds.isId(value.get("id"))) {
                throw new InvalidInputException();
            }
            return new Cursor(LocalDate.parse(value.get("occurredOn").stringValue()), value.get("id").longValue());
        } catch (IllegalArgumentException | DateTimeParseException | JacksonException exception) {
            throw new InvalidInputException();
        }
    }

    private String cursor(LocalDate occurredOn, long id) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.createObjectNode()
                    .put("occurredOn", occurredOn.toString()).put("id", id).toString().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Record cursor could not be encoded", exception);
        }
    }

    public record Filters(List<String> recordTypes, List<String> trustStatuses, LocalDate occurredFrom, LocalDate occurredTo) { }
    public record Cursor(LocalDate occurredOn, long id) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceRef(long id, String displayName, String channel, String externalRef) { }
    /**
     * supersededByCode는 이 기록을 대체하는 확정 기록(SUPERSEDES 관계)이다. OAS에는 선언됐지만 채우는 곳이 없어서,
     * 대체된 결정이 타임라인에서 현재 결정처럼 보였다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordView(long id, String code, long projectId, String recordType, String title, String summary,
                             LocalDate occurredOn, String trustStatus, String authorName, String ownerName, LocalDate dueOn,
                             SourceRef source, String sourceNoteCode, String supersededByCode, long version) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordDetail(long id, String code, long projectId, String recordType, String title, String summary,
                               LocalDate occurredOn, String trustStatus, String authorName, String ownerName, LocalDate dueOn,
                               SourceRef source, String sourceNoteCode, String supersededByCode, long version, String originalExcerpt,
                               String confirmedByName, OffsetDateTime confirmedAt, List<RecordRepository.RecordRevision> revisions,
                               List<RecordRepository.RecordRelation> relations) { }
    public record RecordList(List<RecordView> items, PageMeta page) { }
}
