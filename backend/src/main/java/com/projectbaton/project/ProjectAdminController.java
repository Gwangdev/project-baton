package com.projectbaton.project;

import java.util.UUID;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.identity.UserManagementService.InvalidUserInputException;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.IfMatchRequiredException;
import com.projectbaton.shared.InvalidInputException;
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
@RequestMapping("/api/v1/admin/projects")
public class ProjectAdminController {

    private final ProjectAdminService projects;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public ProjectAdminController(ProjectAdminService projects, IdempotencyService idempotency, ObjectMapper objectMapper) {
        this.projects = projects;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ProjectAdminService.ProjectList list(@AuthenticationPrincipal CurrentActor actor,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(defaultValue = "0") int offset) {
        projects.requireSysadmin(actor.userId());
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) {
            throw new InvalidInputException();
        }
        return projects.list(limit, offset);
    }

    @PostMapping
    public ResponseEntity<JsonNode> create(@AuthenticationPrincipal CurrentActor actor, @RequestHeader("Idempotency-Key") String key,
                                            @RequestBody JsonNode body, HttpServletRequest request) {
        return json(idempotency.execute(idempotencyRequest(actor, "POST", request.getRequestURI(), key, body),
                () -> response(201, projects.create(actor.userId(), body))));
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<JsonNode> update(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                            @RequestHeader("Idempotency-Key") String key,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                            @RequestBody JsonNode body, HttpServletRequest request) {
        long version = requiredVersion(ifMatch);
        IdempotencyService.Response response = idempotency.execute(idempotencyRequest(actor, "PATCH", request.getRequestURI(), key, body),
                () -> response(200, projects.update(actor.userId(), projectId, version, body)));
        try {
            return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON)
                    .eTag("\"" + objectMapper.readTree(response.responseJson()).get("version").longValue() + "\"")
                    .body(objectMapper.readTree(response.responseJson()));
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Stored response JSON is invalid", exception);
        }
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                       @RequestHeader("Idempotency-Key") String key,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                       HttpServletRequest request) {
        long version = requiredVersion(ifMatch);
        idempotency.execute(idempotencyRequest(actor, "DELETE", request.getRequestURI(), key, objectMapper.createObjectNode()), () -> {
            projects.delete(actor.userId(), projectId, version);
            return new IdempotencyService.Response(204, "{}", projectId, false);
        });
        return ResponseEntity.noContent().build();
    }

    private IdempotencyService.Request idempotencyRequest(CurrentActor actor, String method, String path, String key, JsonNode body) {
        try {
            return new IdempotencyService.Request(actor.userId(), method, path, UUID.fromString(key), body.toString(),
                    () -> projects.requireSysadmin(actor.userId()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidUserInputException();
        }
    }

    private IdempotencyService.Response response(int status, Object value) {
        JsonNode json = objectMapper.valueToTree(value);
        return new IdempotencyService.Response(status, json.toString(), json.get("id").longValue(), false);
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
            throw new InvalidUserInputException();
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new InvalidUserInputException();
        }
    }
}
