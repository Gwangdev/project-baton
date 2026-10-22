package com.projectbaton.config;

import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Google 임베딩 연결로, Google이 임베딩 공급자일 때만 만든다.
 *
 * <p>Spring AI의 자동 구성은 어떤 공급자를 고르든 이 연결을 만들었고, 이 연결은 키 없이는 만들어지지 않는다.
 * Gradle에서처럼 변수가 없을 때는 application.yml의 임시 키로 넘어갔지만, Compose는 설정하지 않은
 * {@code GOOGLE_API_KEY}를 빈 문자열로 넘기고 Spring은 이를 설정된 값으로 봐서 stub으로도 Compose 스택이 기동에서
 * 멈췄다. 이제 application.yml이 그 자동 구성을 빼고 이 클래스가 Google 텍스트 임베딩 모델을 만드는 스위치 아래에서
 * 다시 넣으므로, Google을 고르지 않으면 키도 임시 키도 필요 없다. Google을 고르고 키가 없는 배포는
 * {@link com.projectbaton.ai.AiSettings}가 변수 이름을 밝히며 멈춘다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.model.embedding.text", havingValue = "google-genai", matchIfMissing = true)
@Import(GoogleGenAiEmbeddingConnectionAutoConfiguration.class)
public class GoogleEmbeddingConnectionConfiguration {
}
