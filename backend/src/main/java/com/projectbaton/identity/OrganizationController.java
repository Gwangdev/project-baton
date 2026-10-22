package com.projectbaton.identity;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.projectbaton.shared.IdempotencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * FR-18 조직 관리 표면: listOrganizations, createOrganization, updateOrganization, deleteOrganization(contracts.md
 * C02). 멱등 재실행이 contracts.md C01과 어긋나지 않도록, GET이 아닌 메서드는 요청 모양만 검사하고 권한, 버전 확인,
 * 명령 처리는 {@link OrganizationService}에 둔다.
 */
@RestController
@RequestMapping("/api/v1/admin/organizations")
@Validated
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @GetMapping
    public OrganizationService.OrganizationListResponse list(
            @AuthenticationPrincipal CurrentActor actor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return organizationService.list(actor.userId(), limit, offset);
    }

    @PostMapping
    public ResponseEntity<OrganizationService.OrganizationView> create(
            @AuthenticationPrincipal CurrentActor actor,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody JsonNode body) {
        IdempotencyService.Response response = organizationService.create(actor.userId(), idempotencyKey, body);
        return ResponseEntity.status(response.status()).body(organizationService.parseView(response.responseJson()));
    }

    @PatchMapping("/{organizationId}")
    public ResponseEntity<OrganizationService.OrganizationView> update(
            @AuthenticationPrincipal CurrentActor actor,
            @PathVariable long organizationId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody JsonNode body) {
        OrganizationService.UpdateResult result = organizationService.update(actor.userId(), organizationId,
                idempotencyKey, ifMatch, body);
        return ResponseEntity.status(result.response().status())
                .eTag("\"" + result.version() + "\"")
                .body(organizationService.parseView(result.response().responseJson()));
    }

    @DeleteMapping("/{organizationId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CurrentActor actor,
            @PathVariable long organizationId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        organizationService.delete(actor.userId(), organizationId, idempotencyKey, ifMatch);
        return ResponseEntity.noContent().build();
    }
}
