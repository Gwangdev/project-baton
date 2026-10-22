package com.projectbaton.identity;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.projectbaton.audit.AuditService;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.IfMatchRequiredException;
import com.projectbaton.shared.PageMeta;
import com.projectbaton.shared.ReferenceConflictException;
import com.projectbaton.shared.ReferenceConflictProblem;
import com.projectbaton.shared.StaleVersionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 조직 관리 명령(contracts.md C02). 상태를 바꾸는 명령은 모두 {@link IdempotencyService}로 감싸서 재실행 전에도 권한을
 * 다시 확인하고, If-Match, 참조, 마지막 관리자 확인은 재실행이 아닌 첫 실제 시도에서만 돈다(contracts.md C01: "replay는
 * ... version 검사보다 먼저").
 */
@Service
public class OrganizationService {

    private static final Set<String> UPDATABLE_FIELDS = Set.of("name", "isActive");
    private static final int NAME_MAX_LENGTH = 120;

    private final OrganizationRepository repository;
    private final SystemAdminAccessPolicy accessPolicy;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public OrganizationService(OrganizationRepository repository, SystemAdminAccessPolicy accessPolicy,
                               IdempotencyService idempotencyService, AuditService auditService,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public OrganizationListResponse list(long actorId, int limit, int offset) {
        accessPolicy.requireSystemAdmin(actorId);
        List<OrganizationView> items = repository.list(limit, offset);
        long total = repository.count();
        return new OrganizationListResponse(items, new PageMeta(total, limit, offset, null));
    }

    public IdempotencyService.Response create(long actorId, UUID idempotencyKey, JsonNode body) {
        accessPolicy.requireSystemAdmin(actorId);
        String trimmedName = parseCreateName(body);
        IdempotencyService.Request request = new IdempotencyService.Request(actorId, "POST",
                "/api/v1/admin/organizations", idempotencyKey, body.toString(),
                () -> accessPolicy.requireSystemAdmin(actorId));
        return idempotencyService.execute(request, () -> {
            OrganizationView created;
            try {
                created = repository.insert(trimmedName);
            } catch (DataIntegrityViolationException exception) {
                throw new DuplicateOrganizationNameException();
            }
            auditService.record(actorId, "CREATE_ORGANIZATION", "organization", String.valueOf(created.id()),
                    "SUCCESS", null);
            return new IdempotencyService.Response(201, writeJson(created), created.id(), false);
        });
    }

    public UpdateResult update(long actorId, long organizationId, UUID idempotencyKey, String ifMatchHeader,
                               JsonNode body) {
        accessPolicy.requireSystemAdmin(actorId);
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            throw new IfMatchRequiredException();
        }
        long requestedVersion = parseVersion(ifMatchHeader);
        OrganizationPatch patch = parsePatch(body);
        IdempotencyService.Request request = new IdempotencyService.Request(actorId, "PATCH",
                "/api/v1/admin/organizations/" + organizationId, idempotencyKey, body.toString(),
                () -> accessPolicy.requireSystemAdmin(actorId));
        IdempotencyService.Response response = idempotencyService.execute(request,
                () -> applyUpdate(actorId, organizationId, requestedVersion, patch));
        return new UpdateResult(response, parseView(response.responseJson()).version());
    }

    public void delete(long actorId, long organizationId, UUID idempotencyKey, String ifMatchHeader) {
        accessPolicy.requireSystemAdmin(actorId);
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            throw new IfMatchRequiredException();
        }
        long requestedVersion = parseVersion(ifMatchHeader);
        IdempotencyService.Request request = new IdempotencyService.Request(actorId, "DELETE",
                "/api/v1/admin/organizations/" + organizationId, idempotencyKey, "{}",
                () -> accessPolicy.requireSystemAdmin(actorId));
        idempotencyService.execute(request, () -> applyDelete(actorId, organizationId, requestedVersion));
    }

    public OrganizationView parseView(String json) {
        return objectMapper.readValue(json, OrganizationView.class);
    }

    private IdempotencyService.Response applyUpdate(long actorId, long organizationId, long requestedVersion,
                                                     OrganizationPatch patch) {
        OrganizationRow row = repository.lockForUpdate(organizationId).orElseThrow(OrganizationNotFoundException::new);
        if (row.version() != requestedVersion) {
            throw new StaleVersionException();
        }
        boolean deactivating = patch.isActive().isPresent() && !patch.isActive().get() && row.isActive();
        if (deactivating && repository.countActiveSystemAdminsOutsideOrganization(organizationId) == 0) {
            throw new LastActiveSystemAdminException();
        }
        OrganizationView updated;
        try {
            updated = repository.update(organizationId, patch.name().orElse(null), patch.isActive().orElse(null));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateOrganizationNameException();
        }
        auditService.record(actorId, "UPDATE_ORGANIZATION", "organization", String.valueOf(organizationId),
                "SUCCESS", null);
        return new IdempotencyService.Response(200, writeJson(updated), organizationId, false);
    }

    private IdempotencyService.Response applyDelete(long actorId, long organizationId, long requestedVersion) {
        OrganizationRow row = repository.lockForUpdate(organizationId).orElseThrow(OrganizationNotFoundException::new);
        if (row.version() != requestedVersion) {
            throw new StaleVersionException();
        }
        long userCount = repository.countUsers(organizationId);
        if (userCount > 0) {
            throw new ReferenceConflictException(List.of(new ReferenceConflictProblem.BlockingReference(
                    "users", (int) userCount, "비활성화로 전환하면 소속 사용자 접근을 감춘 채 이력을 유지한다.")));
        }
        try {
            repository.delete(organizationId);
        } catch (DataIntegrityViolationException exception) {
            throw new ReferenceConflictException(List.of());
        }
        auditService.record(actorId, "DELETE_ORGANIZATION", "organization", String.valueOf(organizationId),
                "SUCCESS", null);
        return new IdempotencyService.Response(204, "{}", organizationId, false);
    }

    private String parseCreateName(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw new InvalidPatchException();
        }
        for (var entry : body.properties()) {
            if (!"name".equals(entry.getKey())) {
                throw new InvalidPatchException();
            }
        }
        if (!body.has("name")) {
            throw new InvalidPatchException();
        }
        JsonNode nameNode = body.get("name");
        if (nameNode.isNull() || !nameNode.isTextual()) {
            throw new InvalidPatchException();
        }
        String value = nameNode.asText().trim();
        if (value.isBlank() || value.length() > NAME_MAX_LENGTH) {
            throw new InvalidPatchException();
        }
        return value;
    }

    private OrganizationPatch parsePatch(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw new InvalidPatchException();
        }
        for (var entry : body.properties()) {
            if (!UPDATABLE_FIELDS.contains(entry.getKey())) {
                throw new InvalidPatchException();
            }
        }
        Optional<String> name = Optional.empty();
        if (body.has("name")) {
            JsonNode nameNode = body.get("name");
            if (nameNode.isNull() || !nameNode.isTextual()) {
                throw new InvalidPatchException();
            }
            String value = nameNode.asText().trim();
            if (value.isBlank() || value.length() > NAME_MAX_LENGTH) {
                throw new InvalidPatchException();
            }
            name = Optional.of(value);
        }
        Optional<Boolean> isActive = Optional.empty();
        if (body.has("isActive")) {
            JsonNode activeNode = body.get("isActive");
            if (activeNode.isNull() || !activeNode.isBoolean()) {
                throw new InvalidPatchException();
            }
            isActive = Optional.of(activeNode.asBoolean());
        }
        return new OrganizationPatch(name, isActive);
    }

    private long parseVersion(String ifMatchHeader) {
        if (!ifMatchHeader.matches("^\"[0-9]+\"$")) {
            throw new InvalidPatchException();
        }
        try {
            return Long.parseLong(ifMatchHeader.substring(1, ifMatchHeader.length() - 1));
        } catch (NumberFormatException tooLong) {
            // 숫자만 있지만 Long.MAX_VALUE를 넘는 경우. 예전에는 IllegalArgumentException을 통째로 바꾸는 처리로
            // 드러났는데, 그 처리가 없어진 지금은 여기서 바꾸지 않으면 500이 된다.
            throw new InvalidPatchException();
        }
    }

    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    public record OrganizationListResponse(List<OrganizationView> items, PageMeta page) {
    }

    public record OrganizationView(long id, String name, boolean isActive, long userCount, long version) {
    }

    record OrganizationRow(long id, boolean isActive, long version) {
    }

    private record OrganizationPatch(Optional<String> name, Optional<Boolean> isActive) {
    }

    public record UpdateResult(IdempotencyService.Response response, long version) {
    }

    public static class OrganizationNotFoundException extends RuntimeException {
    }

    public static class DuplicateOrganizationNameException extends RuntimeException {
    }

    public static class LastActiveSystemAdminException extends RuntimeException {
    }

    public static class InvalidPatchException extends RuntimeException {
    }
}
