package com.projectbaton.ai.evaluation;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** 토큰을 {@link TokenBudget}에 대어 세고, 예산이 감당하지 못할 호출은 거절하는 채팅·임베딩 모델. */
final class MeteredModels {
    private static final long OUTPUT_RESERVE = 1_500;

    private MeteredModels() {
    }

    /** 토큰을 세고 마지막 답을 기억해서, 실패를 그 출력과 함께 보일 수 있는 채팅 모델. */
    static final class MeteredChat implements ChatModel {
        private final ChatModel delegate;
        private final TokenBudget budget;
        private volatile String lastOutput = "";

        MeteredChat(ChatModel delegate, TokenBudget budget) {
            this.delegate = delegate;
            this.budget = budget;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = invoke(delegate, budget, prompt);
            lastOutput = response == null || response.getResult() == null ? "" : String.valueOf(response.getResult().getOutput().getText());
            return response;
        }

        String lastOutput() {
            return lastOutput;
        }
    }

    static MeteredChat chat(ChatModel delegate, TokenBudget budget) {
        return new MeteredChat(delegate, budget);
    }

    private static ChatResponse invoke(ChatModel delegate, TokenBudget budget, Prompt prompt) {
        long input = TokenBudget.estimate(prompt.getContents());
        budget.reserveChat(input + OUTPUT_RESERVE);
        ChatResponse response = delegate.call(prompt);
        long reported = 0;
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Integer total = response.getMetadata().getUsage().getTotalTokens();
            reported = total == null ? 0 : total;
        }
        String answer = response == null || response.getResult() == null ? "" : response.getResult().getOutput().getText();
        budget.addChat(reported > 0 ? reported : input + TokenBudget.estimate(answer == null ? "" : answer));
        return response;
    }

    static EmbeddingModel embedding(EmbeddingModel delegate, TokenBudget budget) {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                long estimate = request.getInstructions().stream().mapToLong(TokenBudget::estimate).sum();
                budget.reserveEmbedding(estimate);
                EmbeddingResponse response = delegate.call(request);
                long reported = 0;
                if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                    Integer total = response.getMetadata().getUsage().getTotalTokens();
                    reported = total == null ? 0 : total;
                }
                budget.addEmbedding(reported > 0 ? reported : estimate);
                return response;
            }

            @Override
            public float[] embed(Document document) {
                return call(new EmbeddingRequest(java.util.List.of(document.getText()), null)).getResult().getOutput();
            }

            @Override
            public int dimensions() {
                return delegate.dimensions();
            }
        };
    }
}
