package com.projectbaton.project;

import java.util.UUID;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.identity.UserManagementService.InvalidUserInputException;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.IfMatchRequiredException;
import com.projectbaton.shared.PageRequest;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/projects/{projectId}/members")
public class ProjectMemberController {
    private final ProjectMemberService members;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public ProjectMemberController(ProjectMemberService members, IdempotencyService idempotency, ObjectMapper json) {
        this.members = members;
        this.idempotency = idempotency;
        this.json = json;
    }

    @GetMapping
    public ProjectMemberService.MemberList list(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                                @RequestParam(required = false) Integer limit,
                                                @RequestParam(required = false) Integer offset) {
        return members.list(actor.userId(), projectId, PageRequest.of(limit, offset));
    }

    @PostMapping
    public ResponseEntity<JsonNode> create(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                           @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body,
                                           HttpServletRequest request) {
        IdempotencyService.Response response = idempotency.execute(idempotent(actor, "POST", request.getRequestURI(), key, body),
                () -> stored(201, members.create(actor.userId(), projectId, body)));
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(read(response));
    }

    @PatchMapping("/{memberId}")
    public ResponseEntity<JsonNode> update(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                           @PathVariable long memberId, @RequestHeader("Idempotency-Key") String key,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                           @RequestBody JsonNode body, HttpServletRequest request) {
        IdempotencyService.Response response = idempotency.execute(idempotent(actor, "PATCH", request.getRequestURI(), key, body),
                () -> stored(200, members.update(actor.userId(), projectId, memberId, version(ifMatch), body)));
        JsonNode updated = read(response);
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON)
                .eTag("\"" + updated.get("version").longValue() + "\"").body(updated);
    }

    @DeleteMapping("/{memberId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                       @PathVariable long memberId, @RequestHeader("Idempotency-Key") String key,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                       HttpServletRequest request) {
        idempotency.execute(idempotent(actor, "DELETE", request.getRequestURI(), key, json.createObjectNode()), () -> {
            members.delete(actor.userId(), projectId, memberId, version(ifMatch));
            return new IdempotencyService.Response(204, "{}", memberId, false);
        });
        return ResponseEntity.noContent().build();
    }

    private IdempotencyService.Request idempotent(CurrentActor actor, String method, String path, String key, JsonNode body) {
        try {
            return new IdempotencyService.Request(actor.userId(), method, path, UUID.fromString(key), body.toString(),
                    () -> members.requireAdmin(actor.userId()));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidUserInputException();
        }
    }

    private IdempotencyService.Response stored(int status, ProjectMemberService.MemberView member) {
        JsonNode body = json.valueToTree(member);
        return new IdempotencyService.Response(status, body.toString(), member.id(), false);
    }

    private JsonNode read(IdempotencyService.Response response) {
        try {
            return json.readTree(response.responseJson());
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long version(String ifMatch) {
        if (ifMatch == null) {
            throw new IfMatchRequiredException();
        }
        if (!ifMatch.matches("\"[0-9]+\"")) {
            throw new InvalidUserInputException();
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException invalid) {
            throw new InvalidUserInputException();
        }
    }
}
