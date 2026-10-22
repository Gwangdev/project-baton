package com.projectbaton.connection;

import java.net.URI;
import java.util.UUID;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.InvalidInputException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class SourceProcessingController {
    private final SourceProcessingService processing;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public SourceProcessingController(SourceProcessingService processing, IdempotencyService idempotency, ObjectMapper json) {
        this.processing = processing;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/api/v1/connections/{connectionId}/files/{sourceId}/processing-attempts")
    public ResponseEntity<JsonNode> retry(@AuthenticationPrincipal CurrentActor actor, @PathVariable long connectionId,
                                          @PathVariable long sourceId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        String path = "/api/v1/connections/" + connectionId + "/files/" + sourceId + "/processing-attempts";
        IdempotencyService.Response response = idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST", path,
                uuid(key), "{}", () -> processing.authorize(actor.userId(), connectionId, sourceId)),
                () -> response(processing.retry(actor.userId(), connectionId, sourceId)));
        try {
            return ResponseEntity.status(response.status())
                    .location(URI.create("/api/v1/connections/" + connectionId + "/files"))
                    .contentType(MediaType.APPLICATION_JSON).body(json.readTree(response.responseJson()));
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private IdempotencyService.Response response(SourceProcessingService.ProcessingAttempt attempt) {
        JsonNode body = json.valueToTree(attempt);
        return new IdempotencyService.Response(202, body.toString(), attempt.sourceId(), false);
    }

    private UUID uuid(String key) {
        if (key == null) {
            throw new InvalidInputException();
        }
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException exception) {
            throw new InvalidInputException();
        }
    }
}
