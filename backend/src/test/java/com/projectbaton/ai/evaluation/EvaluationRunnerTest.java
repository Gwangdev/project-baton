package com.projectbaton.ai.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.projectbaton.qa.StubEmbeddingClient;
import com.projectbaton.record.DraftRecordParser;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 각본대로 답하는 모델로 평가 장치를 오프라인에서 돌린다. 바르게 답하는 모델은 만점이어야 하고, 출력 형식을 깨는
 * 모델은 코드별 실패로 보여야 하고, 예산은 넘기 전에 실행을 멈춰야 하고, 합성으로 표시되지 않은 집합은 모델에 닿으면
 * 안 된다.
 */
class EvaluationRunnerTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final EvaluationSet SET = EvaluationSet.load();

    /** 좋은 모델처럼 답한다. 문서에는 정답 기록, 질문에는 핵심 사실로. */
    private static final class GoodChat implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            calls.incrementAndGet();
            String text = prompt.getContents();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text.contains("Question:") ? answer(text) : records(text)))));
        }

        private String records(String text) {
            EvaluationSet.Document document = SET.documents().stream().filter(d -> text.contains(d.body())).findFirst().orElseThrow();
            List<String> out = new ArrayList<>();
            for (EvaluationSet.Expected expected : document.expected()) {
                String excerpt = document.body().substring(0, 12);
                out.add("{\"recordType\":\"" + expected.recordType() + "\",\"title\":\"t\",\"summary\":\"" + expected.mustContain()
                        + "\",\"originalExcerpt\":\"" + excerpt + "\",\"occurredOn\":\"" + document.occurredOn()
                        + "\",\"ownerUserId\":null,\"dueOn\":null}");
            }
            return "{\"records\":[" + String.join(",", out) + "]}";
        }

        private String answer(String text) {
            EvaluationSet.Question question = SET.questions().stream().filter(q -> text.contains("Question: " + q.text())).findFirst().orElseThrow();
            if (question.mustMention().isEmpty()) {
                return "{\"answer\":\"\",\"citations\":[]}";
            }
            return "{\"answer\":\"" + String.join(" ", question.mustMention()) + "\",\"citations\":[\"E1\"]}";
        }
    }

    private static final class BrokenChat implements ChatModel {
        @Override
        public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("```json\n{}\n```"))));
        }
    }

    private static final class HashedEmbeddings implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> out = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                out.add(new Embedding(StubEmbeddingClient.vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(out);
        }

        @Override public float[] embed(Document document) { return StubEmbeddingClient.vector(document.getText()); }
        @Override public int dimensions() { return 256; }
    }

    private static EvaluationRunner runner(ChatModel chat, TokenBudget budget) {
        return new EvaluationRunner(chat, new HashedEmbeddings(), new DraftRecordParser(JSON), JSON, "chat-x", "embed-x", budget);
    }

    @Test
    void theSetHasFiftyDocumentsAndThirtyQuestionsOfFourKinds() {
        assertEquals(50, SET.documents().size());
        assertEquals(30, SET.questions().size());
        assertEquals(List.of("ANSWERABLE", "CONFLICT", "INJECTION", "NO_EVIDENCE"),
                SET.questions().stream().map(EvaluationSet.Question::kind).distinct().sorted().toList());
        SET.requireSynthetic();
    }

    @Test
    void aModelThatAnswersCorrectlyScoresFully() {
        EvaluationReport report = runner(new GoodChat(), new TokenBudget(10_000_000, 10_000_000)).run(SET, "offline");

        assertEquals(50, report.structuringAttempted());
        assertEquals(50, report.structuringSucceeded());
        assertEquals(report.expectedRecords(), report.foundRecords());
        assertEquals(0, report.recordsFromNoise());
        assertEquals(30, report.questionsAsked());
        assertEquals(30, report.questionsCorrect());
        assertEquals(0, report.invalidCitations());
        assertTrue(report.recallAt3() >= 0.8, "retrieval recall@3 was " + report.recallAt3());
        assertEquals(null, report.stoppedBecause());
        String markdown = report.toMarkdown();
        assertTrue(markdown.contains("offline") && markdown.contains("chat-x") && markdown.contains("embed-x"), markdown);
    }

    @Test
    void aModelThatBreaksTheOutputFormatShowsAsFailuresByCode() {
        EvaluationReport report = runner(new BrokenChat(), new TokenBudget(10_000_000, 10_000_000)).run(SET, "offline");

        assertEquals(0, report.structuringSucceeded());
        assertEquals(50, report.structuringFailures().get("MODEL_OUTPUT_INVALID"));
        assertEquals(0, report.questionsCorrect());
        assertEquals(30, report.questionFailures().get("ANSWER_INVALID"));
    }

    @Test
    void theBudgetStopsTheRunBeforeItIsPassed() {
        TokenBudget budget = new TokenBudget(4_000, 10_000_000);

        EvaluationReport report = runner(new GoodChat(), budget).run(SET, "offline");

        assertTrue(report.stoppedBecause() != null && report.stoppedBecause().contains("budget"), String.valueOf(report.stoppedBecause()));
        assertTrue(budget.chatUsed() <= 4_000, "used " + budget.chatUsed());
        assertTrue(report.structuringAttempted() < 50);
    }

    @Test
    void aSetNotMarkedSyntheticNeverReachesAModel() {
        EvaluationSet unmarked = new EvaluationSet(1, false, SET.members(), SET.documents(), SET.records(), SET.questions());
        GoodChat chat = new GoodChat();

        assertThrows(IllegalStateException.class, () -> runner(chat, new TokenBudget(1_000_000, 1_000_000)).run(unmarked, "openai"));
        assertEquals(0, chat.calls.get());
    }

    @Test
    void theEstimateChargesKoreanCharactersMoreThanAscii() {
        assertEquals(1, TokenBudget.estimate("abcd"));
        assertEquals(3, TokenBudget.estimate("한글입"));
    }
}
