package com.projectbaton.qa;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.shared.ApiProblemWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * askQuestion은 Accept로 응답 형식을 정한다(C07). {@code text/event-stream}이면 200 stream, JSON과 맞는 형식이거나
 * Accept가 없으면 201 JSON 답변이고, 다른 형식은 질문을 받기 전에 406이다.
 */
@RestController
public class QuestionController {
    static final long STREAM_TIMEOUT_MILLIS = 60_000;
    private static final Logger log = LoggerFactory.getLogger(QuestionController.class);

    private final QuestionService questions;
    private final QaExecutors executors;
    private final ObjectMapper json;

    public QuestionController(QuestionService questions, QaExecutors executors, ObjectMapper json) {
        this.questions = questions;
        this.executors = executors;
        this.json = json;
    }

    @GetMapping("/api/v1/handovers/{handoverId}/questions")
    public ResponseEntity<JsonNode> list(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId,
                                         @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(required = false) String cursor) {
        QuestionService.History history = questions.history(actor.userId(), handoverId, limit, offset, cursor);
        ObjectNode body = json.createObjectNode();
        history.items().forEach(body.putArray("items")::add);
        ObjectNode page = body.putObject("page");
        page.put("total", history.total());
        page.put("limit", history.limit());
        page.put("offset", history.offset());
        if (history.nextCursor() == null) page.putNull("nextCursor"); else page.put("nextCursor", history.nextCursor());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @PostMapping("/api/v1/handovers/{handoverId}/questions")
    public ResponseEntity<JsonNode> ask(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @RequestHeader(value = "Accept", required = false) String accept,
                                        @RequestBody JsonNode body) throws HttpMediaTypeNotAcceptableException {
        requireJsonAcceptable(accept);
        QuestionService.Accepted accepted = questions.accept(actor.userId(), handoverId, uuid(key), question(body));
        JsonNode answer = switch (accepted) {
            case QuestionService.Accepted.Replay replay -> replay.answer();
            case QuestionService.Accepted.Fresh fresh -> {
                JsonNode committed = questions.answer(fresh, (event, data) -> { });
                yield committed != null ? committed : questions.storedAnswer(fresh.context());
            }
        };
        return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON).body(answer);
    }

    @PostMapping(value = "/api/v1/handovers/{handoverId}/questions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId,
                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                @RequestBody JsonNode body) throws IOException {
        // stream을 열기 전에 질문을 받아서, 접근, 입력, 재실행 충돌은 여전히 Problem으로 답한다.
        QuestionService.Accepted accepted = questions.accept(actor.userId(), handoverId, uuid(key), question(body));
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        if (accepted instanceof QuestionService.Accepted.Replay replay) {
            emitter.send(SseEmitter.event().name("done").data(json.createObjectNode().set("answer", replay.answer()).toString()));
            emitter.complete();
            return emitter;
        }
        QuestionService.Accepted.Fresh fresh = (QuestionService.Accepted.Fresh) accepted;
        AtomicBoolean gone = new AtomicBoolean();
        emitter.onError(error -> gone.set(true));
        emitter.onTimeout(() -> gone.set(true));
        AnswerSink sink = (event, data) -> {
            if (gone.get()) throw new IOException("client disconnected");
            emitter.send(SseEmitter.event().name(event).data(data.toString()));
        };
        try {
            executors.stream().execute(() -> {
                try {
                    questions.answer(fresh, sink);
                } catch (RuntimeException failure) {
                    // answer()는 예상한 모든 경로에서 마무리하므로 여기 닿았다면 서버 오류다. 클라이언트가 본 trace
                    // id와 함께 로그를 남기고 stream을 닫는다. lease가 끝나면 복구가 질문을 실패로 처리한다.
                    log.error("Unhandled {} in Q&A stream traceId={}", ApiProblemWriter.origin(failure), fresh.context().traceId());
                } finally {
                    emitter.complete();
                }
            });
        } catch (RejectedExecutionException saturated) {
            // 질문은 받은 상태로 남는다. lease가 끝나면 복구가 FAILED로 닫고, 재실행은 그 결과를 돌려준다.
            emitter.send(SseEmitter.event().name("error").data(json.createObjectNode().put("code", "QA_BUSY").put("retryable", true)
                    .put("traceId", fresh.context().traceId()).toString()));
            emitter.complete();
        }
        return emitter;
    }

    private void requireJsonAcceptable(String accept) throws HttpMediaTypeNotAcceptableException {
        if (accept == null || accept.isBlank()) return;
        List<MediaType> accepted = MediaType.parseMediaTypes(accept);
        if (accepted.stream().noneMatch(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON))) {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        }
    }

    private String question(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("question") || !body.get("question").isString()) {
            throw new QuestionService.InvalidQuestionException();
        }
        return body.get("question").stringValue();
    }

    private UUID uuid(String key) {
        try {
            return UUID.fromString(key);
        } catch (RuntimeException invalid) {
            throw new QuestionService.InvalidQuestionException();
        }
    }
}
