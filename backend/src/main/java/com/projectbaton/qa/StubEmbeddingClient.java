package com.projectbaton.qa;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 오프라인 임베딩. 정규화한 단어와 그 글자 bigram에 대한 부호 있는 feature hashing 벡터다. 모델이 필요 없고 매번 같은
 * 값을 내면서도, 단어를 공유하는 글끼리는 실제 코사인 유사도가 생겨서 검색 계약을 시험할 수 있다.
 */
public class StubEmbeddingClient implements EmbeddingClient {
    public static final String MODEL_ID = "stub-hashed-bigram-256-v1";

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        texts.forEach(text -> vectors.add(vector(text)));
        return vectors;
    }

    public static float[] vector(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String word : Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) continue;
            add(vector, "w:" + word);
            // 라틴 글자 단어에서는 같은 글자 쌍이 무관한 영어 글을 비슷해 보이게 만들어서, bigram은 한글 단어에만 쓴다.
            // 한글에서는 일정은 안의 일정을 맞춰 준다.
            if (word.codePoints().anyMatch(point -> Character.UnicodeScript.of(point) == Character.UnicodeScript.HANGUL)) {
                for (int i = 0; i + 1 < word.length(); i++) add(vector, "b:" + word.substring(i, i + 2));
            }
        }
        double norm = 0;
        for (float value : vector) norm += value * value;
        if (norm > 0) {
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) vector[i] *= scale;
        }
        return vector;
    }

    private static void add(float[] vector, String feature) {
        int hash = feature.hashCode();
        vector[Math.floorMod(hash, DIMENSIONS)] += ((hash >>> 16) & 1) == 0 ? 1f : -1f;
    }
}
