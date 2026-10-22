package com.projectbaton.ai.evaluation;

/**
 * 평가 한 번이 쓸 수 있는 토큰 상한으로, 호출마다 먼저 확인한다. 이미 쓴 토큰과 이번 호출의 추정치를 더해 한도를
 * 넘으면 호출을 거절해서, 실행이 한도를 넘기 전에 멈춘다. 실제 사용량은 공급자가 응답 메타데이터에 알려 주면 그 값을,
 * 알려 주지 않으면 추정치를 쓴다.
 */
public final class TokenBudget {
    private final long chatLimit;
    private final long embeddingLimit;
    private long chatUsed;
    private long embeddingUsed;
    private String refusal;

    public TokenBudget(long chatLimit, long embeddingLimit) {
        this.chatLimit = chatLimit;
        this.embeddingLimit = embeddingLimit;
    }

    /** ASCII 밖의 글자(한국어)는 대략 글자당 한 토큰, ASCII는 4분의 1 토큰으로 친다. 정확한 수가 아니라 상한이다. */
    public static long estimate(String text) {
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            tokens += text.charAt(i) < 128 ? 0.25 : 1.0;
        }
        return (long) Math.ceil(tokens);
    }

    public void reserveChat(long estimate) {
        if (chatUsed + estimate > chatLimit) {
            throw refuse("chat token budget of " + chatLimit + " reached after " + chatUsed + " tokens");
        }
    }

    public void reserveEmbedding(long estimate) {
        if (embeddingUsed + estimate > embeddingLimit) {
            throw refuse("embedding token budget of " + embeddingLimit + " reached after " + embeddingUsed + " tokens");
        }
    }

    /**
     * 어댑터는 모델의 어떤 예외든 자기 "모델 사용 불가"로 바꿔서 거절이 공급자 실패 하나로 숨겨진다. 그래서 runner는
     * 실패할 때마다 예산 때문인지 여기에 묻는다.
     */
    private BudgetExceededException refuse(String message) {
        refusal = message;
        return new BudgetExceededException(message);
    }

    /** 예산이 호출을 거절한 이유. 거절하지 않았으면 null. */
    public String refusal() {
        return refusal;
    }

    public void addChat(long tokens) {
        chatUsed += tokens;
    }

    public void addEmbedding(long tokens) {
        embeddingUsed += tokens;
    }

    public long chatUsed() {
        return chatUsed;
    }

    public long embeddingUsed() {
        return embeddingUsed;
    }

    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }
}
