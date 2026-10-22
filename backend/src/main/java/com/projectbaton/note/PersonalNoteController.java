package com.projectbaton.note;

import java.util.UUID;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.record.RecordQueryService;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
public class PersonalNoteController {

    private final PersonalNoteService notes;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public PersonalNoteController(PersonalNoteService notes, IdempotencyService idempotency, ObjectMapper objectMapper) {
        this.notes = notes;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/v1/projects/{projectId}/notes")
    public PersonalNoteService.NoteList list(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                             @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false) Integer offset) {
        return notes.list(actor.userId(), projectId, PageRequest.of(limit, offset));
    }

    @PostMapping("/api/v1/projects/{projectId}/notes")
    public ResponseEntity<JsonNode> create(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody JsonNode body) {
        String path = "/api/v1/projects/" + projectId + "/notes";
        return response(idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST", path, uuid(key), body.toString(),
                () -> notes.authorizeProjectMember(actor.userId(), projectId)),
                () -> body(201, notes.create(actor.userId(), projectId, body))));
    }

    @PostMapping("/api/v1/notes/{noteId}/share")
    public ResponseEntity<JsonNode> share(@AuthenticationPrincipal CurrentActor actor, @PathVariable long noteId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @RequestBody JsonNode body) {
        String path = "/api/v1/notes/" + noteId + "/share";
        return response(idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST", path, uuid(key), body.toString(),
                () -> notes.authorizeAuthor(actor.userId(), noteId)),
                () -> body(201, notes.share(actor.userId(), noteId, body))));
    }

    private IdempotencyService.Response body(int status, Object value) {
        JsonNode node = objectMapper.valueToTree(value);
        return new IdempotencyService.Response(status, node.toString(), node.get("id").longValue(), false);
    }

    private ResponseEntity<JsonNode> response(IdempotencyService.Response value) {
        try {
            return ResponseEntity.status(value.status()).contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.readTree(value.responseJson()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored personal note response could not be parsed", exception);
        }
    }

    private UUID uuid(String value) {
        if (value == null) throw new InvalidInputException();
        try { return UUID.fromString(value); } catch (IllegalArgumentException exception) { throw new InvalidInputException(); }
    }
}
