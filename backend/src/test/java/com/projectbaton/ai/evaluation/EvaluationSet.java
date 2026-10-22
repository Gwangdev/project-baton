package com.projectbaton.ai.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/**
 * 합성 평가 집합(tools/make_ai_eval_set.py): 좋은 구조화가 찾아낼 기록을 담은 문서와, 그 기록에 대한 질문. 집합과
 * 모든 문서의 {@code synthetic}은 true여야 하고, runner는 그 밖의 것을 외부 공급자에 보내지 않는다.
 */
public record EvaluationSet(int version, boolean synthetic, List<Member> members, List<Document> documents,
                            List<GoldRecord> records, List<Question> questions) {

    public record Member(long userId, String name) { }

    public record Expected(String recordType, String mustContain) { }

    public record Document(String id, boolean synthetic, String kind, String author, String occurredOn, String body, List<Expected> expected) { }

    public record GoldRecord(String code, String docId, String recordType, String title, String summary, String key, Boolean conflictClone) { }

    public record Question(String id, String kind, String text, List<String> evidence, List<String> conflict, List<String> mustMention,
                           List<String> mustNotMention, String goldRecord, java.util.Map<String, String> overrides) { }

    public static EvaluationSet load() {
        try (InputStream stream = EvaluationSet.class.getClassLoader().getResourceAsStream("ai-evaluation/synthetic-set.json")) {
            if (stream == null) {
                throw new IllegalStateException("ai-evaluation/synthetic-set.json is missing from the test resources");
            }
            return JsonMapper.builder().build().readValue(stream, EvaluationSet.class);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 빠르게 볼 때 쓰는 작은 실행. 처음 {@code documents}개 문서와, 모든 종류가 들어가도록 종류를 번갈아 고른
     * {@code questions}개 질문. 질문이 정답 기록을 인용하므로 정답 기록은 모두 남긴다.
     */
    public EvaluationSet limited(int documents, int questions) {
        java.util.Map<String, java.util.ArrayDeque<Question>> byKind = new java.util.TreeMap<>();
        this.questions.forEach(question -> byKind.computeIfAbsent(question.kind(), kind -> new java.util.ArrayDeque<>()).add(question));
        List<Question> chosen = new java.util.ArrayList<>();
        while (chosen.size() < questions && byKind.values().stream().anyMatch(queue -> !queue.isEmpty())) {
            for (java.util.ArrayDeque<Question> queue : byKind.values()) {
                if (!queue.isEmpty() && chosen.size() < questions) {
                    chosen.add(queue.poll());
                }
            }
        }
        return new EvaluationSet(version, synthetic, members, this.documents.stream().limit(documents).toList(), records, chosen);
    }

    /** 이 집합 전체가 합성으로 표시되지 않았으면 예외를 던진다. */
    public void requireSynthetic() {
        if (!synthetic || documents.stream().anyMatch(document -> !document.synthetic())) {
            throw new IllegalStateException("The evaluation set is not marked synthetic; nothing is sent to a provider");
        }
    }
}
