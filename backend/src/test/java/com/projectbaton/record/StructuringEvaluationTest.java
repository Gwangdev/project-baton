package com.projectbaton.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 합성 평가 집합을 검증기로 재생해 거절률과 파싱 시간을 알린다. 오프라인 경로만 잰다. 실제 모델의 지연과 실패율은
 * 모델 프로필을 설정해야 잴 수 있다.
 */
class StructuringEvaluationTest {
    private final ObjectMapper json = JsonMapper.builder().build();
    private final DraftRecordParser parser = new DraftRecordParser(json);

    @Test
    void validatorReachesTheRecordedVerdictForEveryCase() throws Exception {
        JsonNode dataset;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("ai-evaluation/structuring-cases.json")) {
            dataset = json.readTree(input);
        }
        List<RecordStructurer.Member> members = new ArrayList<>();
        dataset.get("members").forEach(member -> members.add(new RecordStructurer.Member(member.get("userId").longValue(), member.get("name").stringValue())));
        int rejected = 0;
        long nanos = 0;
        List<String> mismatches = new ArrayList<>();
        for (JsonNode testCase : dataset.get("cases")) {
            String raw = testCase.has("raw") ? testCase.get("raw").stringValue() : testCase.get("output").toString();
            RecordStructurer.SourceDocument document = new RecordStructurer.SourceDocument(testCase.get("body").stringValue(), "Synthetic", null, members);
            long started = System.nanoTime();
            String verdict;
            int records = -1;
            try {
                records = parser.parse(raw, document).size();
                verdict = "valid";
            } catch (RecordStructurer.StructuringException exception) {
                verdict = "invalid";
                rejected++;
            }
            nanos += System.nanoTime() - started;
            String expected = testCase.get("expect").stringValue();
            if (!expected.equals(verdict) || ("valid".equals(expected) && testCase.get("records").intValue() != records)) {
                mismatches.add(testCase.get("name").stringValue() + " -> " + verdict + "/" + records);
            }
        }
        int total = dataset.get("cases").size();
        System.out.printf("structuring evaluation: cases=%d rejected=%d rejectionRate=%.2f avgParseMicros=%.1f%n",
                total, rejected, rejected / (double) total, nanos / 1000.0 / total);
        if (!mismatches.isEmpty()) fail("Unexpected verdicts: " + mismatches);
        assertEquals(8, rejected);
    }
}
