package com.projectbaton.record;

import java.util.UUID;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.IfMatchRequiredException;
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

@RestController
public class RecordReviewController {

    private final RecordReviewService reviews;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public RecordReviewController(RecordReviewService reviews, IdempotencyService idempotency, ObjectMapper objectMapper) {
        this.reviews = reviews;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/v1/records/{recordId}/review")
    public ResponseEntity<JsonNode> review(@AuthenticationPrincipal CurrentActor actor, @PathVariable long recordId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @RequestBody JsonNode body) {
        long expectedVersion = version(ifMatch);
        UUID key = key(idempotencyKey);
        String path = "/api/v1/records/" + recordId + "/review";
        IdempotencyService.Response response = idempotency.execute(
                new IdempotencyService.Request(actor.userId(), "POST", path, key, body.toString(),
                        () -> reviews.authorize(actor.userId(), recordId)),
                () -> response(reviews.review(actor.userId(), recordId, expectedVersion, body)));
        return response(response);
    }

    private IdempotencyService.Response response(RecordQueryService.RecordDetail detail) {
        JsonNode response = objectMapper.valueToTree(detail);
        return new IdempotencyService.Response(200, response.toString(), detail.id(), false);
    }

    private ResponseEntity<JsonNode> response(IdempotencyService.Response response) {
        try {
            JsonNode body = objectMapper.readTree(response.responseJson());
            return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON)
                    .eTag("\"" + body.get("version").longValue() + "\"").body(body);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored review response could not be parsed", exception);
        }
    }

    private long version(String ifMatch) {
        if (ifMatch == null) throw new IfMatchRequiredException();
        if (!ifMatch.matches("\\\"[0-9]+\\\"")) throw new InvalidInputException();
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new InvalidInputException();
        }
    }

    private UUID key(String value) {
        if (value == null) throw new InvalidInputException();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidInputException();
        }
    }
}
