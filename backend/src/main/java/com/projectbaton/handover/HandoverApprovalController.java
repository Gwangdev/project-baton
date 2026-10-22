package com.projectbaton.handover;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.InvalidInputException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PM의 완료 결정. 승인하거나, 의견과 함께 보완을 요청한다. */
@RestController
public class HandoverApprovalController {
    private static final int MAX_COMMENT_LENGTH = 500;
    private final HandoverApprovalService approvals;
    private final IdempotencyService idempotency;
    private final HandoverJson json;
    private final ObjectMapper mapper;

    public HandoverApprovalController(HandoverApprovalService approvals, IdempotencyService idempotency, HandoverJson json,
                                      ObjectMapper mapper) {
        this.approvals = approvals;
        this.idempotency = idempotency;
        this.json = json;
        this.mapper = mapper;
    }

    @PostMapping("/api/v1/handovers/{handoverId}/approval")
    public ResponseEntity<JsonNode> decide(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @RequestBody JsonNode body) {
        long version = HandoverRequests.ifMatch(ifMatch);
        HandoverApprovalService.Decision decision = decision(body);
        String comment = comment(body, decision);
        IdempotencyService.Response response = idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST",
                        "/api/v1/handovers/" + handoverId + "/approval", HandoverRequests.idempotencyKey(key), body.toString(),
                        () -> approvals.authorize(actor.userId(), handoverId)),
                () -> {
                    HandoverQueryService.Detail detail = approvals.decide(actor.userId(), handoverId, version, decision, comment);
                    return new IdempotencyService.Response(200, json.detail(detail).toString(), detail.summary().id(), false);
                });
        try {
            JsonNode decided = mapper.readTree(response.responseJson());
            return ResponseEntity.status(response.status()).eTag("\"" + decided.get("version").longValue() + "\"")
                    .contentType(MediaType.APPLICATION_JSON).body(decided);
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private HandoverApprovalService.Decision decision(JsonNode body) {
        if (!body.isObject() || body.properties().stream().anyMatch(field -> !field.getKey().equals("decision") && !field.getKey().equals("comment"))) {
            throw new InvalidInputException();
        }
        JsonNode decision = body.get("decision");
        if (decision == null || !decision.isTextual()) {
            throw new InvalidInputException();
        }
        try {
            return HandoverApprovalService.Decision.valueOf(decision.stringValue());
        } catch (IllegalArgumentException unknown) {
            throw new InvalidInputException();
        }
    }

    private String comment(JsonNode body, HandoverApprovalService.Decision decision) {
        JsonNode comment = body.get("comment");
        if (comment == null) {
            if (decision == HandoverApprovalService.Decision.REQUEST_REVISION) {
                throw new InvalidInputException();
            }
            return null;
        }
        // 의견은 보완 요청의 사유다(OAS). 예전에는 승인도 의견을 받아 길이만 검사하고 버려서 보낸 사람은 남은 줄
        // 알았다. 이제 해당하지 않는 다른 필드처럼 거절한다.
        if (decision == HandoverApprovalService.Decision.APPROVE) {
            throw new InvalidInputException();
        }
        if (!comment.isTextual() || comment.stringValue().length() > MAX_COMMENT_LENGTH
                || comment.stringValue().isBlank()) {
            throw new InvalidInputException();
        }
        return comment.stringValue();
    }
}
