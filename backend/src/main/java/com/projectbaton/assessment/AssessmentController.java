package com.projectbaton.assessment;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.shared.IdempotencyService;
import com.projectbaton.shared.InvalidInputException;
import com.projectbaton.shared.JsonIds;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class AssessmentController {
    private final AssessmentService assessments;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public AssessmentController(AssessmentService assessments, IdempotencyService idempotency, ObjectMapper json) {
        this.assessments = assessments;
        this.idempotency = idempotency;
        this.json = json;
    }

    @GetMapping("/api/v1/handovers/{handoverId}/assessment")
    public AssessmentService.Detail detail(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId) {
        return assessments.detail(actor.userId(), handoverId);
    }

    @PostMapping("/api/v1/handovers/{handoverId}/assessment/attempts")
    public ResponseEntity<JsonNode> submit(@AuthenticationPrincipal CurrentActor actor, @PathVariable long handoverId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody JsonNode body) {
        String path = "/api/v1/handovers/" + handoverId + "/assessment/attempts";
        IdempotencyService.Response response = idempotency.execute(new IdempotencyService.Request(actor.userId(), "POST", path,
                        key(key), body.toString(), () -> assessments.authorizeSubmission(actor.userId(), handoverId)),
                () -> result(assessments.submit(actor.userId(), handoverId, answers(body)), handoverId));
        try {
            return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(json.readTree(response.responseJson()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored assessment response is invalid.", exception);
        }
    }

    private IdempotencyService.Response result(AssessmentService.Attempt attempt, long handoverId) {
        return new IdempotencyService.Response(201, json.valueToTree(attempt).toString(), handoverId, false);
    }

    private List<AssessmentService.SubmittedAnswer> answers(JsonNode body) {
        if (!body.isObject() || body.size() != 1 || !body.has("answers") || !body.get("answers").isArray()) throw new AssessmentService.InvalidAssessmentInputException();
        return java.util.stream.StreamSupport.stream(body.get("answers").spliterator(), false).map(answer -> {
            if (!answer.isObject() || answer.size() != 2 || !answer.has("questionId") || !answer.has("answerText")
                    || !JsonIds.isId(answer.get("questionId")) || !answer.get("answerText").isTextual()) {
                throw new AssessmentService.InvalidAssessmentInputException();
            }
            return new AssessmentService.SubmittedAnswer(answer.get("questionId").longValue(), answer.get("answerText").asText());
        }).toList();
    }

    private UUID key(String value) {
        if (value == null) throw new InvalidInputException();
        try { return UUID.fromString(value); } catch (IllegalArgumentException exception) { throw new InvalidInputException(); }
    }
}
