package com.projectbaton.qa;

import java.util.List;

/**
 * 오프라인 프로필. 글을 만들지 않고 가장 높은 순위의 근거를 인용해서 모든 답변이 구조적으로 근거를 가진다. 해소되지
 * 않은 충돌이면 README §7의 요구대로 두 기록을 모두 밝히고 판단을 관리자에게 넘긴다.
 */
public class StubAnswerGenerator implements GroundedAnswerGenerator {
    public static final String MODEL_ID = "stub-extractive-v1";

    @Override public String modelId() { return MODEL_ID; }
    @Override public String promptVersion() { return "stub-extractive-v1"; }

    @Override
    public Generated generate(String question, List<Evidence> evidence, List<Conflict> conflicts) {
        Evidence top = evidence.getFirst();
        for (Conflict conflict : conflicts) {
            if (conflict.firstCode().equals(top.recordCode()) || conflict.secondCode().equals(top.recordCode())) {
                List<String> labels = evidence.stream()
                        .filter(block -> block.recordCode().equals(conflict.firstCode()) || block.recordCode().equals(conflict.secondCode()))
                        .map(Evidence::label).distinct().toList();
                return new Generated("Records " + conflict.firstCode() + " and " + conflict.secondCode()
                        + " disagree and the conflict is not resolved. Confirm with the project manager.", labels);
            }
        }
        String text = top.text().replaceAll("\\s+", " ").strip();
        return new Generated(top.recordCode() + ": " + (text.length() > 600 ? text.substring(0, 600) : text), List.of(top.label()));
    }
}
