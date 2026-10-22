package com.projectbaton.ai.evaluation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.projectbaton.qa.EmbeddingClient;
import com.projectbaton.qa.GroundedAnswerGenerator;
import com.projectbaton.qa.SpringAiAnswerGenerator;
import com.projectbaton.qa.SpringAiEmbeddingClient;
import com.projectbaton.record.DraftRecordParser;
import com.projectbaton.record.RecordStructurer;
import com.projectbaton.record.SpringAiRecordStructurer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import tools.jackson.databind.ObjectMapper;

/**
 * 합성 평가 집합을 앱 자신의 어댑터(구조화기, 답변 생성기, 임베딩 client)로 돌려서, 같은 모델로 운영이 할 일을 잰다.
 * 모델을 감싸 토큰을 예산에 대어 세고, 예산을 넘게 되면 실행을 멈추고 그렇다고 알린다. 검색은 pgvector가 아니라
 * 메모리에서(정답 기록 요약에 대한 코사인) 재서 DB가 필요 없다.
 */
public final class EvaluationRunner {
    private static final int EMBEDDING_BATCH = 16;

    private final MeteredModels.MeteredChat chat;
    private final EmbeddingModel embedding;
    private final DraftRecordParser parser;
    private final ObjectMapper json;
    private final String chatModelId;
    private final String embeddingModelId;
    private final TokenBudget budget;

    public EvaluationRunner(ChatModel chat, EmbeddingModel embedding, DraftRecordParser parser, ObjectMapper json,
                            String chatModelId, String embeddingModelId, TokenBudget budget) {
        this.chat = MeteredModels.chat(chat, budget);
        this.embedding = MeteredModels.embedding(embedding, budget);
        this.parser = parser;
        this.json = json;
        this.chatModelId = chatModelId;
        this.embeddingModelId = embeddingModelId;
        this.budget = budget;
    }

    public EvaluationReport run(EvaluationSet set, String provider) {
        // 첫 호출 전에 확인한다. 합성으로 표시되지 않은 집합은 어디로도 보내지 않는다.
        set.requireSynthetic();
        Run run = new Run(set);
        try {
            run.structuring();
            run.questions();
            run.retrieval();
        } catch (TokenBudget.BudgetExceededException stop) {
            run.stoppedBecause = stop.getMessage();
        }
        return run.report(provider);
    }

    private final class Run {
        private final EvaluationSet set;
        String stoppedBecause;

        int attempted;
        int succeeded;
        final Map<String, Integer> structuringFailures = new LinkedHashMap<>();
        int expected;
        int found;
        int fromNoise;
        final List<Long> structuringMillis = new ArrayList<>();

        int asked;
        int correct;
        final Map<String, Integer> questionFailures = new LinkedHashMap<>();
        final Map<String, int[]> byKind = new LinkedHashMap<>();
        int invalidCitations;
        final List<Long> answerMillis = new ArrayList<>();

        int retrievalQuestions;
        int retrievalHits;
        final List<String> samples = new ArrayList<>();

        Run(EvaluationSet set) {
            this.set = set;
        }

        void structuring() {
            RecordStructurer structurer = new SpringAiRecordStructurer(chat, parser);
            List<RecordStructurer.Member> members = set.members().stream().map(m -> new RecordStructurer.Member(m.userId(), m.name())).toList();
            for (EvaluationSet.Document document : set.documents()) {
                long started = System.nanoTime();
                try {
                    List<RecordStructurer.DraftRecord> drafts = structurer.extract(new RecordStructurer.SourceDocument(
                            document.body(), document.author(), LocalDate.parse(document.occurredOn()), members));
                    structuringMillis.add((System.nanoTime() - started) / 1_000_000);
                    attempted++;
                    succeeded++;
                    expected += document.expected().size();
                    for (EvaluationSet.Expected want : document.expected()) {
                        if (drafts.stream().anyMatch(draft -> draft.recordType().equals(want.recordType())
                                && (draft.title() + " " + draft.summary() + " " + draft.originalExcerpt()).contains(want.mustContain()))) {
                            found++;
                        }
                    }
                    if ("NOISE".equals(document.kind())) {
                        fromNoise += drafts.size();
                    }
                } catch (RecordStructurer.StructuringException failure) {
                    stopIfBudgetRefused();
                    attempted++;
                    sample("structuring " + document.id() + " " + failure.code());
                    structuringMillis.add((System.nanoTime() - started) / 1_000_000);
                    structuringFailures.merge(failure.code(), 1, Integer::sum);
                    expected += document.expected().size();
                }
            }
        }

        void questions() {
            GroundedAnswerGenerator generator = new SpringAiAnswerGenerator(chat, chatModelId, json);
            Map<String, EvaluationSet.GoldRecord> records = set.records().stream()
                    .collect(Collectors.toMap(EvaluationSet.GoldRecord::code, r -> r));
            for (EvaluationSet.Question question : set.questions()) {
                List<GroundedAnswerGenerator.Evidence> evidence = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                for (int i = 0; i < question.evidence().size(); i++) {
                    EvaluationSet.GoldRecord record = records.get(question.evidence().get(i));
                    String label = "E" + (i + 1);
                    labels.add(label);
                    String text = question.overrides() != null && question.overrides().containsKey(record.code())
                            ? question.overrides().get(record.code()) : record.summary();
                    evidence.add(new GroundedAnswerGenerator.Evidence(label, record.code(), record.title() + ": " + text));
                }
                List<GroundedAnswerGenerator.Conflict> conflicts = question.conflict() == null ? List.of()
                        : List.of(new GroundedAnswerGenerator.Conflict(question.conflict().get(0), question.conflict().get(1)));
                int[] counts = byKind.computeIfAbsent(question.kind(), kind -> new int[2]);
                long started = System.nanoTime();
                try {
                    GroundedAnswerGenerator.Generated generated = generator.generate(question.text(), evidence, conflicts);
                    answerMillis.add((System.nanoTime() - started) / 1_000_000);
                    asked++;
                    counts[1]++;
                    for (String citation : generated.citations()) {
                        if (!labels.contains(citation)) {
                            invalidCitations++;
                        }
                    }
                    if (isCorrect(question, generated)) {
                        correct++;
                        counts[0]++;
                    }
                } catch (GroundedAnswerGenerator.GenerationException failure) {
                    stopIfBudgetRefused();
                    asked++;
                    counts[1]++;
                    sample("question " + question.id() + " " + failure.code());
                    answerMillis.add((System.nanoTime() - started) / 1_000_000);
                    questionFailures.merge(failure.code(), 1, Integer::sum);
                }
            }
        }

        /** 보고서가 모델이 실제로 한 말을 보일 수 있게 실패한 출력 몇 개를 짧게 잘라 남긴다. */
        private void sample(String what) {
            if (samples.size() < 3) {
                String output = chat.lastOutput().replaceAll("\\s+", " ");
                samples.add(what + ": " + (output.length() > 300 ? output.substring(0, 300) + "…" : output));
            }
        }

        /** 거절된 호출은 모델에 닿지 않았으므로 시도로도 실패로도 세지 않는다. */
        private void stopIfBudgetRefused() {
            if (budget.refusal() != null) {
                throw new TokenBudget.BudgetExceededException(budget.refusal());
            }
        }

        private boolean isCorrect(EvaluationSet.Question question, GroundedAnswerGenerator.Generated generated) {
            String answer = generated.answer();
            if ("NO_EVIDENCE".equals(question.kind())) {
                return answer.isBlank() && generated.citations().isEmpty();
            }
            if (answer.isBlank() || generated.citations().isEmpty()) {
                return false;
            }
            return question.mustMention().stream().allMatch(answer::contains)
                    && question.mustNotMention().stream().noneMatch(answer::contains);
        }

        void retrieval() {
            EmbeddingClient client = new SpringAiEmbeddingClient(embedding, embeddingModelId);
            List<EvaluationSet.GoldRecord> corpus = set.records().stream().filter(r -> !Boolean.TRUE.equals(r.conflictClone())).toList();
            List<float[]> vectors = embedAll(client, corpus.stream().map(r -> r.title() + "\n" + r.summary()).toList());
            List<EvaluationSet.Question> asked = set.questions().stream().filter(q -> q.goldRecord() != null && "ANSWERABLE".equals(q.kind())).toList();
            List<float[]> questions = embedAll(client, asked.stream().map(EvaluationSet.Question::text).toList());
            for (int q = 0; q < asked.size(); q++) {
                final float[] query = questions.get(q);
                List<String> top = java.util.stream.IntStream.range(0, corpus.size()).boxed()
                        .sorted(Comparator.comparingDouble((Integer i) -> -cosine(query, vectors.get(i))))
                        .limit(3).map(i -> corpus.get(i).code()).toList();
                retrievalQuestions++;
                if (top.contains(asked.get(q).goldRecord())) {
                    retrievalHits++;
                }
            }
        }

        private List<float[]> embedAll(EmbeddingClient client, List<String> texts) {
            List<float[]> out = new ArrayList<>();
            for (int start = 0; start < texts.size(); start += EMBEDDING_BATCH) {
                out.addAll(client.embed(texts.subList(start, Math.min(texts.size(), start + EMBEDDING_BATCH))));
            }
            return out;
        }

        private double cosine(float[] a, float[] b) {
            double dot = 0, na = 0, nb = 0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                na += a[i] * a[i];
                nb += b[i] * b[i];
            }
            return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
        }

        EvaluationReport report(String provider) {
            return new EvaluationReport(provider, chatModelId, embeddingModelId, attempted, succeeded, structuringFailures, expected, found,
                    fromNoise, structuringMillis, asked, correct, questionFailures, byKind, invalidCitations, answerMillis,
                    retrievalQuestions, retrievalQuestions == 0 ? 0 : (double) retrievalHits / retrievalQuestions,
                    budget.chatUsed(), budget.embeddingUsed(), stoppedBecause, samples);
        }
    }

}
