package com.projectbaton.qa;

import java.util.ArrayList;
import java.util.List;
import com.projectbaton.ai.ModelCallLimit;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * {@code local}이나 {@code provider} 프로필의 Spring AI {@link EmbeddingModel}로 임베딩한다.
 *
 * <p>record_embeddings 열은 숫자 {@link #DIMENSIONS}개를 담는다. 쓰는 모델(OpenAI, Google, embeddinggemma)은
 * 앞부분만으로도 임베딩이 되도록 학습된 더 긴 벡터를 돌려줘서, 긴 벡터는 공급자와 상관없이 같은 방식으로 열 폭에 맞게
 * 자르고 단위 길이로 다시 맞춘다. 더 짧은 벡터는 정직하게 채울 방법이 없어서 거절한다. 예전에는 기동 때 폭을 물어봐서
 * 첫 요청 전에 공급자를 호출했고, 키나 네트워크가 없으면 서비스가 뜨지 못했다. 그래서 client를 만들 때 모델에
 * 아무것도 묻지 않는다.
 */
public class SpringAiEmbeddingClient implements EmbeddingClient {
    private final EmbeddingModel model;
    private final String modelId;
    private final ModelCallLimit limit;

    public SpringAiEmbeddingClient(EmbeddingModel model, String modelId, ModelCallLimit limit) {
        if (modelId == null || modelId.isBlank() || modelId.length() > 120) {
            throw new IllegalStateException("baton.ai.embedding-model-id must name the pinned embedding model (1..120 chars)");
        }
        this.model = model;
        this.modelId = modelId;
        this.limit = limit;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> raw;
        try {
            raw = limit.call(() -> model.embed(texts));
        } catch (RuntimeException exception) {
            throw new EmbeddingUnavailableException(exception);
        }
        List<float[]> vectors = new ArrayList<>(raw.size());
        for (float[] vector : raw) {
            vectors.add(fit(vector));
        }
        return vectors;
    }

    private float[] fit(float[] vector) {
        if (vector.length == DIMENSIONS) {
            return vector;
        }
        if (vector.length < DIMENSIONS) {
            throw new EmbeddingUnavailableException(new IllegalStateException("Embedding model " + modelId + " returned "
                    + vector.length + " dimensions; the record_embeddings column holds " + DIMENSIONS));
        }
        float[] cut = new float[DIMENSIONS];
        double norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            cut[i] = vector[i];
            norm += (double) vector[i] * vector[i];
        }
        if (norm == 0) {
            return cut;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < DIMENSIONS; i++) {
            cut[i] *= scale;
        }
        return cut;
    }
}
