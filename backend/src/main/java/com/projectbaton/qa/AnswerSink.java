package com.projectbaton.qa;

import java.io.IOException;
import tools.jackson.databind.JsonNode;

/**
 * 답변 하나의 C07 이벤트(meta, evidence, delta, done, error)를 받는다. JSON endpoint는 무시하고 SSE endpoint는
 * 클라이언트에 쓴다. {@link IOException}은 클라이언트가 떠났다는 뜻이라 답변을 멈춰야 한다.
 */
@FunctionalInterface
public interface AnswerSink {
    void send(String event, JsonNode data) throws IOException;
}
