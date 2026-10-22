package com.projectbaton.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.projectbaton.qa.EmbeddingClient;
import com.projectbaton.qa.SpringAiEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * record_embeddings 열은 256차원을 담고, 쓰는 모델(OpenAI, Google, embeddinggemma)은 앞부분만으로도 쓸 수 있는
 * 임베딩이 되도록 학습된 더 긴 벡터를 돌려준다. client는 256으로 자르고 단위 길이로 다시 맞춘다. 만들 때 모델에
 * 아무것도 묻지 않아서 서비스가 키나 네트워크 없이 뜬다.
 */
class SpringAiEmbeddingClientTest {
    private static final class Fake implements EmbeddingModel {
        final AtomicInteger calls = new AtomicInteger();
        final int size;
        final boolean fail;

        Fake(int size, boolean fail) {
            this.size = size;
            this.fail = fail;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("provider down");
            List<Embedding> results = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                float[] vector = new float[size];
                for (int d = 0; d < size; d++) vector[d] = d + 1;
                results.add(new Embedding(vector, i));
            }
            return new EmbeddingResponse(results);
        }

        @Override public float[] embed(Document document) { return new float[size]; }
        @Override public int dimensions() { calls.incrementAndGet(); return size; }
    }

    @Test
    void buildingTheClientDoesNotCallTheModel() {
        Fake model = new Fake(768, false);

        new SpringAiEmbeddingClient(model, "embed-x");

        assertEquals(0, model.calls.get());
    }

    @Test
    void aLongerVectorIsCutToTheColumnWidthAndRescaled() {
        EmbeddingClient client = new SpringAiEmbeddingClient(new Fake(768, false), "embed-x");

        float[] vector = client.embed(List.of("text")).getFirst();

        assertEquals(EmbeddingClient.DIMENSIONS, vector.length);
        double norm = 0;
        for (float value : vector) norm += value * value;
        assertEquals(1.0, Math.sqrt(norm), 1e-5);
        assertTrue(vector[1] / vector[0] > 1.99 && vector[1] / vector[0] < 2.01, "the leading components keep their proportions");
    }

    @Test
    void aVectorOfExactlyTheColumnWidthIsLeftAlone() {
        float[] expected = new float[256];
        for (int d = 0; d < 256; d++) expected[d] = d + 1;

        assertArrayEquals(expected, new SpringAiEmbeddingClient(new Fake(256, false), "embed-x").embed(List.of("text")).getFirst());
    }

    @Test
    void aVectorShorterThanTheColumnIsRefused() {
        EmbeddingClient client = new SpringAiEmbeddingClient(new Fake(128, false), "embed-x");

        assertThrows(EmbeddingClient.EmbeddingUnavailableException.class, () -> client.embed(List.of("text")));
    }

    @Test
    void aProviderFailureIsReportedAsUnavailable() {
        EmbeddingClient client = new SpringAiEmbeddingClient(new Fake(768, true), "embed-x");

        assertThrows(EmbeddingClient.EmbeddingUnavailableException.class, () -> client.embed(List.of("text")));
    }
}
