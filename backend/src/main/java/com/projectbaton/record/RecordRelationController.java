package com.projectbaton.record;

import java.util.UUID;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.shared.IfMatchRequiredException;
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
public class RecordRelationController {
    private final RecordRelationService relations;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public RecordRelationController(RecordRelationService relations, IdempotencyService idempotency, ObjectMapper json) {
        this.relations = relations;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/api/v1/projects/{projectId}/record-relations")
    public ResponseEntity<JsonNode> create(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody JsonNode body) {
        String path = "/api/v1/projects/" + projectId + "/record-relations";
        IdempotencyService.Response response = idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST", path,
                uuid(key), body.toString(), () -> relations.authorize(actor.userId(), projectId)),
                () -> response(relations.create(actor.userId(), projectId, body)));
        try {
            return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(json.readTree(response.responseJson()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored record relation response could not be parsed", exception);
        }
    }

    @PostMapping("/api/v1/record-relations/{relationId}/withdrawal")
    public ResponseEntity<JsonNode> withdraw(@AuthenticationPrincipal CurrentActor actor, @PathVariable long relationId,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                             @RequestBody JsonNode body) {
        long expectedVersion = version(ifMatch);
        String path = "/api/v1/record-relations/" + relationId + "/withdrawal";
        IdempotencyService.Response response = idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST", path,
                uuid(key), body.toString(), () -> relations.authorizeWithdrawal(actor.userId(), relationId)),
                () -> {
                    RecordRelationService.RecordRelation relation = relations.withdraw(actor.userId(), relationId, expectedVersion, body);
                    return new IdempotencyService.Response(200, json.valueToTree(relation).toString(), relation.id(), false);
                });
        try {
            JsonNode stored = json.readTree(response.responseJson());
            return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON)
                    .eTag("\"" + stored.get("version").longValue() + "\"").body(stored);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored record relation response could not be parsed", exception);
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

    private IdempotencyService.Response response(RecordRelationService.RecordRelation relation) {
        JsonNode body = json.valueToTree(relation);
        return new IdempotencyService.Response(201, body.toString(), relation.id(), false);
    }
    private UUID uuid(String value) {
        if (value == null) throw new InvalidInputException();
        try { return UUID.fromString(value); } catch (IllegalArgumentException exception) { throw new InvalidInputException(); }
    }
}
