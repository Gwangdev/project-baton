package com.projectbaton.connection;

import java.util.UUID;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.record.RecordQueryService;
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

@RestController
public class ManualDraftController {
    private final ManualDraftService drafts;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public ManualDraftController(ManualDraftService drafts, IdempotencyService idempotency, ObjectMapper json) {
        this.drafts = drafts;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/api/v1/connections/{connectionId}/files/{sourceId}/records")
    public ResponseEntity<JsonNode> create(@AuthenticationPrincipal CurrentActor actor, @PathVariable long connectionId,
                                           @PathVariable long sourceId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody JsonNode body) {
        String path = "/api/v1/connections/" + connectionId + "/files/" + sourceId + "/records";
        IdempotencyService.Response response = idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST", path,
                        key(key), body.toString(), () -> drafts.authorize(actor.userId(), connectionId, sourceId)),
                () -> created(drafts.create(actor.userId(), connectionId, sourceId, body)));
        try {
            return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON)
                    .body(json.readTree(response.responseJson()));
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private IdempotencyService.Response created(RecordQueryService.RecordDetail record) {
        JsonNode body = json.valueToTree(record);
        return new IdempotencyService.Response(201, body.toString(), record.id(), false);
    }

    private UUID key(String key) {
        if (key == null) {
            throw new InvalidInputException();
        }
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidInputException();
        }
    }
}
