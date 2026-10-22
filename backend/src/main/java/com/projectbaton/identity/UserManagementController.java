package com.projectbaton.identity;

import java.util.UUID;

import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.IfMatchRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/users")
public class UserManagementController {

    private final UserManagementService users;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public UserManagementController(UserManagementService users, IdempotencyService idempotency, ObjectMapper objectMapper) {
        this.users = users;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public UserManagementService.UserList list(@AuthenticationPrincipal CurrentActor actor,
                                                @RequestParam(required = false) Long organizationId,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(defaultValue = "0") int offset) {
        users.requireSysadmin(actor.userId());
        if (organizationId != null && organizationId < 1 || limit < 1 || limit > 100 || offset < 0 || offset > 10000) {
            throw new UserManagementService.InvalidUserInputException();
        }
        return users.list(organizationId, limit, offset);
    }

    @PostMapping
    public ResponseEntity<JsonNode> create(@AuthenticationPrincipal CurrentActor actor, @RequestHeader("Idempotency-Key") String key,
                                            @RequestBody JsonNode body, HttpServletRequest request) {
        IdempotencyService.Response response = idempotency.execute(request(actor, "POST", request.getRequestURI(), key, body),
                () -> response(201, users.create(actor.userId(), body)));
        return json(response);
    }

    @PatchMapping("/{userId}")
    public ResponseEntity<JsonNode> update(@AuthenticationPrincipal CurrentActor actor, @PathVariable long userId,
                                            @RequestHeader("Idempotency-Key") String key,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                            @RequestBody JsonNode body, HttpServletRequest request) {
        long version = requiredVersion(ifMatch);
        IdempotencyService.Response response = idempotency.execute(request(actor, "PATCH", request.getRequestURI(), key, body),
                () -> response(200, users.update(actor.userId(), userId, version, body)));
        try {
            return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON)
                    .eTag("\"" + jsonVersion(response.responseJson()) + "\"")
                    .body(objectMapper.readTree(response.responseJson()));
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Stored response JSON is invalid", exception);
        }
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentActor actor, @PathVariable long userId,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                       HttpServletRequest request) {
        long version = requiredVersion(ifMatch);
        idempotency.execute(request(actor, "DELETE", request.getRequestURI(), key, objectMapper.createObjectNode()), () -> {
            users.delete(actor.userId(), userId, version);
            return new IdempotencyService.Response(204, "{}", userId, false);
        });
        return ResponseEntity.noContent().build();
    }

    private IdempotencyService.Request request(CurrentActor actor, String method, String path, String key, JsonNode body) {
        UUID uuid;
        try {
            uuid = UUID.fromString(key);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new UserManagementService.InvalidUserInputException();
        }
        return new IdempotencyService.Request(actor.userId(), method, path, uuid, body.toString(), () -> users.requireSysadmin(actor.userId()));
    }

    private IdempotencyService.Response response(int status, Object value) {
        try {
            JsonNode json = objectMapper.valueToTree(value);
            return new IdempotencyService.Response(status, json.toString(), json.get("id") == null ? null : json.get("id").longValue(), false);
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    private ResponseEntity<JsonNode> json(IdempotencyService.Response response) {
        try {
            return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.readTree(response.responseJson()));
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Stored response JSON is invalid", exception);
        }
    }

    private long requiredVersion(String ifMatch) {
        if (ifMatch == null) {
            throw new IfMatchRequiredException();
        }
        if (ifMatch.length() < 3 || !ifMatch.startsWith("\"") || !ifMatch.endsWith("\"")) {
            throw new UserManagementService.InvalidUserInputException();
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new UserManagementService.InvalidUserInputException();
        }
    }

    private long jsonVersion(String json) {
        try {
            return objectMapper.readTree(json).get("version").longValue();
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Stored response JSON is invalid", exception);
        }
    }

}
