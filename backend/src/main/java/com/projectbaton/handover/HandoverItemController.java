package com.projectbaton.handover;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import tools.jackson.databind.node.ArrayNode;

/** 패키지 항목: 항목 목록, PM의 충돌 해소, 신규 담당자의 확인. */
@RestController
public class HandoverItemController {
    private final HandoverItemQueryService items;
    private final HandoverConflictService conflicts;
    private final HandoverAcknowledgementService acknowledgements;
    private final IdempotencyService idempotency;
    private final HandoverJson json;
    private final ObjectMapper mapper;

    public HandoverItemController(HandoverItemQueryService items, HandoverConflictService conflicts,
                                  HandoverAcknowledgementService acknowledgements, IdempotencyService idempotency,
                                  HandoverJson json, ObjectMapper mapper) {
        this.items = items;
        this.conflicts = conflicts;
        this.acknowledgements = acknowledgements;
        this.idempotency = idempotency;
        this.json = json;
        this.mapper = mapper;
    }

    @GetMapping("/api/v1/handovers/{handoverId}/items")
    public ResponseEntity<JsonNode> list(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId,
                                         @RequestParam(required = false) String itemKind,
                                         @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
        PageRequest page = PageRequest.of(limit, offset);
        HandoverItemQueryService.ItemPage listed = items.list(actor.userId(), handoverId, itemKind, page);
        ArrayNode values = json.array();
        listed.items().forEach(item -> values.add(json.item(item)));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.page(values, page.meta(listed.total())));
    }

    @PatchMapping("/api/v1/handovers/{handoverId}/items/{itemId}")
    public ResponseEntity<JsonNode> resolve(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId,
                                            @PathVariable long itemId,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                            @RequestBody JsonNode body) {
        long version = HandoverRequests.ifMatch(ifMatch);
        long selected = HandoverRequests.id(body, "resolvedByRecordId");
        String note = resolutionNote(body);
        IdempotencyService.Response response = idempotency.execute(new IdempotencyService.Request(actor.userId(), "PATCH",
                        "/api/v1/handovers/" + handoverId + "/items/" + itemId, HandoverRequests.idempotencyKey(key), body.toString(),
                        () -> conflicts.authorize(actor.userId(), handoverId)),
                () -> {
                    HandoverConflictService.ResolvedItem item = conflicts.resolve(actor.userId(), handoverId, itemId, version, selected, note);
                    return new IdempotencyService.Response(200, json.resolvedItem(item).toString(), item.id(), false);
                });
        JsonNode resolved = read(response);
        return ResponseEntity.status(response.status()).eTag("\"" + resolved.get("version").longValue() + "\"")
                .contentType(MediaType.APPLICATION_JSON).body(resolved);
    }

    @PostMapping("/api/v1/handovers/{handoverId}/items/{itemId}/acknowledgements")
    public ResponseEntity<JsonNode> acknowledge(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId,
                                                @PathVariable long itemId,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        long version = HandoverRequests.ifMatch(ifMatch);
        IdempotencyService.Response response = idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST",
                        "/api/v1/handovers/" + handoverId + "/items/" + itemId + "/acknowledgements",
                        HandoverRequests.idempotencyKey(key), "{}", () -> acknowledgements.authorize(actor.userId(), handoverId)),
                () -> {
                    HandoverAcknowledgementService.Acknowledgement acknowledgement =
                            acknowledgements.acknowledge(actor.userId(), handoverId, itemId, version);
                    return new IdempotencyService.Response(acknowledgement.created() ? 201 : 200,
                            json.acknowledgement(acknowledgement).toString(), itemId, false);
                });
        return ResponseEntity.status(response.status()).eTag("\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON).body(read(response));
    }

    private String resolutionNote(JsonNode body) {
        if (!body.isObject() || body.size() != 2 || !body.has("resolvedByRecordId")) {
            throw new InvalidInputException();
        }
        JsonNode note = body.get("resolutionNote");
        if (note == null || !note.isTextual() || note.stringValue().isBlank() || note.stringValue().length() > HandoverConflictService.MAX_RESOLUTION_NOTE) {
            throw new InvalidInputException();
        }
        return note.stringValue();
    }

    private JsonNode read(IdempotencyService.Response response) {
        try {
            return mapper.readTree(response.responseJson());
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
