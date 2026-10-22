package com.projectbaton.handover;

import java.net.URI;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.shared.IdempotencyService;
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
import tools.jackson.databind.node.ArrayNode;

/** 인수인계 요청, 패키지 재시도와 재생성, 인수인계 조회. */
@RestController
public class HandoverController {
    private final HandoverRequestService requests;
    private final HandoverQueryService queries;
    private final IdempotencyService idempotency;
    private final HandoverJson json;
    private final ObjectMapper mapper;

    public HandoverController(HandoverRequestService requests, HandoverQueryService queries, IdempotencyService idempotency,
                              HandoverJson json, ObjectMapper mapper) {
        this.requests = requests;
        this.queries = queries;
        this.idempotency = idempotency;
        this.json = json;
        this.mapper = mapper;
    }

    @PostMapping("/api/v1/projects/{projectId}/handovers")
    public ResponseEntity<JsonNode> request(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            @RequestBody JsonNode body) {
        HandoverRequestService.Command command = HandoverRequests.command(body, true);
        return accepted(idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST",
                        "/api/v1/projects/" + projectId + "/handovers", HandoverRequests.idempotencyKey(key), body.toString(),
                        () -> requests.authorizeRequest(actor.userId(), projectId)),
                () -> accepted(requests.request(actor.userId(), projectId, command))));
    }

    @PostMapping("/api/v1/handovers/{handoverId}/retry")
    public ResponseEntity<JsonNode> retry(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        long version = HandoverRequests.ifMatch(ifMatch);
        return accepted(idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST",
                        "/api/v1/handovers/" + handoverId + "/retry", HandoverRequests.idempotencyKey(key), "{}",
                        () -> requests.authorize(actor.userId(), handoverId)),
                () -> accepted(requests.retry(actor.userId(), handoverId, version))));
    }

    @PostMapping("/api/v1/handovers/{handoverId}/generations")
    public ResponseEntity<JsonNode> regenerate(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                               @RequestBody JsonNode body) {
        long version = HandoverRequests.ifMatch(ifMatch);
        HandoverRequestService.Command command = HandoverRequests.command(body, false);
        return accepted(idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST",
                        "/api/v1/handovers/" + handoverId + "/generations", HandoverRequests.idempotencyKey(key), body.toString(),
                        () -> requests.authorize(actor.userId(), handoverId)),
                () -> accepted(requests.regenerate(actor.userId(), handoverId, version, command))));
    }

    @GetMapping("/api/v1/projects/{projectId}/handovers")
    public ResponseEntity<JsonNode> list(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                         @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
        PageRequest page = PageRequest.of(limit, offset);
        HandoverQueryService.SummaryPage handovers = queries.list(actor.userId(), projectId, page);
        ArrayNode items = json.array();
        handovers.items().forEach(handover -> items.add(json.summary(handover)));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.page(items, page.meta(handovers.total())));
    }

    /** C01: GET 상세는 버전을 ETag로 보낸다. 본문에는 늘 있었는데 헤더가 빠져 있었다. */
    @GetMapping("/api/v1/handovers/{handoverId}")
    public ResponseEntity<JsonNode> detail(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId) {
        HandoverQueryService.Detail detail = queries.detail(actor.userId(), handoverId);
        return ResponseEntity.ok().eTag("\"" + detail.summary().version() + "\"").contentType(MediaType.APPLICATION_JSON)
                .body(json.detail(detail));
    }

    /**
     * 202 본문은 Handover 자원 전체다(OAS). 예전에는 id, status, generation, version만 담아서 필수인 code, project,
     * 담당자 이름, 적용일이 빠졌다.
     */
    private IdempotencyService.Response accepted(HandoverRequestService.Handover handover) {
        return new IdempotencyService.Response(202, json.summary(queries.summary(handover.id())).toString(), handover.id(), false);
    }

    private ResponseEntity<JsonNode> accepted(IdempotencyService.Response response) {
        try {
            JsonNode body = mapper.readTree(response.responseJson());
            return ResponseEntity.status(response.status())
                    .location(URI.create("/api/v1/handovers/" + body.get("id").longValue()))
                    .eTag("\"" + body.get("version").longValue() + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
