package com.projectbaton.qa;

import java.util.List;

/** 고정한 모델 하나로 글을 임베딩한다. 저장하는 모든 벡터에 모델 id를 남겨서 두 모델의 벡터가 섞이지 않는다. */
public interface EmbeddingClient {
    /** V9 열이 받는 유일한 크기. */
    int DIMENSIONS = 256;

    String modelId();

    /**
     * 입력마다 길이 {@link #DIMENSIONS}인 벡터를 순서대로 돌려주거나 {@link EmbeddingUnavailableException}을 던진다.
     */
    List<float[]> embed(List<String> texts);

    /** pgvector의 텍스트 형식. {@code cast(? as vector)}로 bind 파라미터에 쓴다. */
    static String literal(float[] vector) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) text.append(',');
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }

    class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(Throwable cause) { super("Embedding model unavailable", cause); }
    }
}
