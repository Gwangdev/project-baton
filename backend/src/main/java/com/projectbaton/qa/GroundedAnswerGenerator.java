package com.projectbaton.qa;

import java.util.List;

/** 서버가 고른 근거만으로 답을 쓴다. 인용은 근거 라벨이고, 모델이 지어낸 URL이나 id가 아니다. */
public interface GroundedAnswerGenerator {

    String modelId();

    String promptVersion();

    Generated generate(String question, List<Evidence> evidence, List<Conflict> conflicts);

    /** 모델이 인용할 수 있는 유일한 손잡이({@code label}, 예: E1). */
    record Evidence(String label, String recordCode, String text) { }

    /** 관리자가 아직 충돌을 해소하지 않은 인용 기록 두 개. 답변은 한쪽을 골라서는 안 된다. */
    record Conflict(String firstCode, String secondCode) { }

    /** 답변도 인용도 비어 있으면 근거가 질문에 답하지 않는다는 뜻이다. */
    record Generated(String answer, List<String> citations) { }

    class GenerationException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public GenerationException(String code, boolean retryable) {
            super(code);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }
}
