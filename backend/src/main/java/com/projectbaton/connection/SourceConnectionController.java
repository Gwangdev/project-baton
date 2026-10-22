package com.projectbaton.connection;

import java.util.UUID;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.IfMatchRequiredException;
import com.projectbaton.shared.InvalidInputException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class SourceConnectionController {

    private final SourceConnectionService connections;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public SourceConnectionController(SourceConnectionService connections, IdempotencyService idempotency, ObjectMapper json) {
        this.connections = connections;
        this.idempotency = idempotency;
        this.json = json;
    }

    @GetMapping("/api/v1/projects/{projectId}/connections")
    public SourceConnectionService.ConnectionList list(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                                        @RequestParam(required = false) String channel,
                                                        @RequestParam(defaultValue = "20") int limit,
                                                        @RequestParam(defaultValue = "0") int offset) {
        page(limit, offset);
        return connections.list(actor.userId(), projectId, channel, limit, offset);
    }

    @PostMapping("/api/v1/projects/{projectId}/connections")
    public ResponseEntity<JsonNode> create(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody JsonNode body) {
        String path = "/api/v1/projects/" + projectId + "/connections";
        return response(idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST", path, uuid(key), body.toString(),
                () -> connections.authorizeCreate(actor.userId(), projectId, body)),
                () -> body(201, connections.create(actor.userId(), projectId, body))), false);
    }

    @GetMapping("/api/v1/connections/{connectionId}")
    public ResponseEntity<SourceConnectionService.SourceConnectionDetail> detail(@AuthenticationPrincipal CurrentActor actor,
                                                                                  @PathVariable long connectionId) {
        SourceConnectionService.SourceConnectionDetail detail = connections.detail(actor.userId(), connectionId);
        return ResponseEntity.ok().eTag("\"" + detail.version() + "\"").body(detail);
    }

    @PatchMapping("/api/v1/connections/{connectionId}")
    public ResponseEntity<JsonNode> update(@AuthenticationPrincipal CurrentActor actor, @PathVariable long connectionId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @RequestBody JsonNode body) {
        long version = version(ifMatch);
        String path = "/api/v1/connections/" + connectionId;
        return response(idempotency.execute(new IdempotencyService.Request(actor.userId(), "PATCH", path, uuid(key), body.toString(),
                () -> connections.authorizeManagement(actor.userId(), connectionId)),
                () -> body(200, connections.update(actor.userId(), connectionId, version, body))), true);
    }

    @DeleteMapping("/api/v1/connections/{connectionId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentActor actor, @PathVariable long connectionId,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        long version = version(ifMatch);
        String path = "/api/v1/connections/" + connectionId;
        idempotency.execute(new IdempotencyService.Request(actor.userId(), "DELETE", path, uuid(key), "{}",
                () -> connections.authorizeManagement(actor.userId(), connectionId)), () -> {
            connections.delete(actor.userId(), connectionId, version);
            return new IdempotencyService.Response(204, "{}", connectionId, false);
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/connections/{connectionId}/reconnect")
    public ResponseEntity<JsonNode> reconnect(@AuthenticationPrincipal CurrentActor actor, @PathVariable long connectionId,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        String path = "/api/v1/connections/" + connectionId + "/reconnect";
        return response(idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST", path, uuid(key), "{}",
                () -> connections.authorizeManagement(actor.userId(), connectionId)),
                () -> body(200, connections.reconnect(actor.userId(), connectionId))), false);
    }

    @GetMapping("/api/v1/connections/{connectionId}/files")
    public SourceConnectionService.CollectedFileList files(@AuthenticationPrincipal CurrentActor actor, @PathVariable long connectionId,
                                                           @RequestParam(defaultValue = "20") int limit,
                                                           @RequestParam(defaultValue = "0") int offset) {
        page(limit, offset);
        return connections.files(actor.userId(), connectionId, limit, offset);
    }

    private IdempotencyService.Response body(int status, Object value) {
        JsonNode node = json.valueToTree(value);
        return new IdempotencyService.Response(status, node.toString(), node.get("id").longValue(), false);
    }

    private ResponseEntity<JsonNode> response(IdempotencyService.Response value, boolean etag) {
        try {
            ResponseEntity.BodyBuilder response = ResponseEntity.status(value.status()).contentType(MediaType.APPLICATION_JSON);
            JsonNode body = json.readTree(value.responseJson());
            if (etag) response.eTag("\"" + body.get("version").longValue() + "\"");
            return response.body(body);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored source connection response could not be parsed", exception);
        }
    }

    private void page(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) throw new InvalidInputException();
    }

    private long version(String value) {
        if (value == null) throw new IfMatchRequiredException();
        if (!value.matches("\\\"[0-9]+\\\"")) throw new InvalidInputException();
        try { return Long.parseLong(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException exception) { throw new InvalidInputException(); }
    }

    private UUID uuid(String value) {
        if (value == null) throw new InvalidInputException();
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException exception) { throw new InvalidInputException(); }
    }
}
