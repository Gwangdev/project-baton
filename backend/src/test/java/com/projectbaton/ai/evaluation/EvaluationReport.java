package com.projectbaton.ai.evaluation;

import java.util.List;
import java.util.Map;

/** 평가 한 번이 잰 것. 숫자와, evidence/model-profile.md에 들어가는 markdown 표로 담는다. */
public record EvaluationReport(String provider, String chatModelId, String embeddingModelId,
                               int structuringAttempted, int structuringSucceeded, Map<String, Integer> structuringFailures,
                               int expectedRecords, int foundRecords, int recordsFromNoise, List<Long> structuringMillis,
                               int questionsAsked, int questionsCorrect, Map<String, Integer> questionFailures,
                               Map<String, int[]> correctByKind, int invalidCitations, List<Long> answerMillis,
                               int retrievalQuestions, double recallAt3, long chatTokens, long embeddingTokens,
                               String stoppedBecause, List<String> failureSamples) {

    static long percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(fraction * sorted.size()) - 1));
    }

    private static String percent(int part, int whole) {
        return whole == 0 ? "n/a" : String.format("%.0f%% (%d/%d)", 100.0 * part / whole, part, whole);
    }

    private static String latency(List<Long> values) {
        return values.isEmpty() ? "n/a" : String.format("avg %d ms, p95 %d ms, max %d ms",
                (long) values.stream().mapToLong(Long::longValue).average().orElse(0), percentile(values, 0.95), percentile(values, 1.0));
    }

    public String toMarkdown() {
        StringBuilder text = new StringBuilder();
        text.append("### ").append(provider).append(" — chat `").append(chatModelId).append("`, embeddings `")
                .append(embeddingModelId).append("`\n\n| Measure | Result |\n|---|---|\n");
        text.append("| Structuring: documents parsed | ").append(percent(structuringSucceeded, structuringAttempted)).append(" |\n");
        text.append("| Structuring: failures by code | ").append(structuringFailures.isEmpty() ? "none" : structuringFailures).append(" |\n");
        text.append("| Structuring: expected records found | ").append(percent(foundRecords, expectedRecords)).append(" |\n");
        text.append("| Structuring: records invented from noise documents | ").append(recordsFromNoise).append(" |\n");
        text.append("| Structuring latency | ").append(latency(structuringMillis)).append(" |\n");
        text.append("| Q&A: correct | ").append(percent(questionsCorrect, questionsAsked)).append(" |\n");
        correctByKind.forEach((kind, counts) -> text.append("| Q&A ").append(kind).append(" | ").append(percent(counts[0], counts[1])).append(" |\n"));
        text.append("| Q&A: failures by code | ").append(questionFailures.isEmpty() ? "none" : questionFailures).append(" |\n");
        text.append("| Q&A: citations naming no evidence | ").append(invalidCitations).append(" |\n");
        text.append("| Q&A latency | ").append(latency(answerMillis)).append(" |\n");
        text.append("| Retrieval recall@3 | ").append(retrievalQuestions == 0 ? "n/a" : String.format("%.0f%% of %d questions", 100 * recallAt3, retrievalQuestions)).append(" |\n");
        text.append("| Tokens used (reported or estimated) | chat ").append(chatTokens).append(", embeddings ").append(embeddingTokens).append(" |\n");
        if (stoppedBecause != null) {
            text.append("| Stopped early | ").append(stoppedBecause).append(" |\n");
        }
        if (!failureSamples.isEmpty()) {
            text.append("\nFirst failing outputs:\n\n");
            failureSamples.forEach(sample -> text.append("- ").append(sample.replace("|", "\\|")).append('\n'));
        }
        return text.toString();
    }
}
