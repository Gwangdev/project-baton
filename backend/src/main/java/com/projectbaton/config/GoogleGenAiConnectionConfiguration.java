package com.projectbaton.config;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.time.Duration;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Google 공급자의 연결. Google을 채팅이나 임베딩 공급자로 고를 때만 만든다.
 *
 * <p>Spring AI 자동 구성이 만드는 Google 클라이언트는 SDK 재시도가 켜져 있어서, 무료 한도에 걸렸을 때 서버가 준
 * retryDelay(약 6시간)만큼 요청 스레드를 재웠다. 설계는 SDK 재시도를 한 번으로 고정하고 작업 계층만 재시도하므로,
 * 시도 1회와 {@code baton.ai.call-timeout}을 건 클라이언트를 여기서 만들어 채팅(자동 구성이 이 bean을 쓴다)과
 * 임베딩 연결에 함께 준다.
 *
 * <p>임베딩 연결의 자동 구성은 어떤 공급자를 고르든 연결을 만들었고 그 연결은 키 없이는 만들어지지 않았다. Compose는
 * 설정하지 않은 {@code GOOGLE_API_KEY}를 빈 문자열로 넘기고 Spring은 이를 설정된 값으로 봐서 stub으로도 스택이
 * 기동에서 멈췄다. 그래서 application.yml이 그 자동 구성을 빼고 여기서 Google 임베딩을 고를 때만 연결을 만든다.
 * Google을 고르고 키가 없는 배포는 {@link com.projectbaton.ai.AiSettings}가 변수 이름을 밝히며 멈춘다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${spring.ai.model.chat:}' == 'google-genai' or '${spring.ai.model.embedding.text:}' == 'google-genai'")
public class GoogleGenAiConnectionConfiguration {

    @Bean
    Client googleGenAiClient(@Value("${spring.ai.google.genai.api-key:}") String apiKey,
                             @Value("${baton.ai.call-timeout:60s}") Duration timeout,
                             @Value("${baton.ai.google-base-url:}") String baseUrl) {
        HttpOptions.Builder http = HttpOptions.builder()
                .timeout((int) timeout.toMillis())
                .retryOptions(HttpRetryOptions.builder().attempts(1));
        if (!baseUrl.isBlank()) http.baseUrl(baseUrl);
        return Client.builder().apiKey(apiKey).httpOptions(http.build()).build();
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.embedding.text", havingValue = "google-genai")
    GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(Client client,
                                                                                @Value("${spring.ai.google.genai.api-key:}") String apiKey) {
        return GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).genAiClient(client).build();
    }
}
