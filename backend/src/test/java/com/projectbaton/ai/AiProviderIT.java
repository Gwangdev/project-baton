package com.projectbaton.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.projectbaton.BatonApplication;
import com.projectbaton.qa.EmbeddingClient;
import com.projectbaton.qa.GroundedAnswerGenerator;
import com.projectbaton.qa.SpringAiAnswerGenerator;
import com.projectbaton.record.RecordStructurer;
import com.projectbaton.record.SpringAiRecordStructurer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 배포는 채팅과 임베딩 모델을 속성으로 고른다. OpenAI, Google GenAI, 로컬 Ollama 가운데 하나만 쓰거나 섞어 쓴다. 고를
 * 수 있는 경우마다 앱을 띄워서, application.yml의 환경변수가 알맞은 Spring AI 자동 구성에 닿는지, 아무것도 고르지
 * 않으면 모델을 만들지 않는지, 기동 중에 공급자를 부르지 않는지(여기 키는 지어낸 값이고 닿을 네트워크도 없다) 보인다.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class AiProviderIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    /**
     * 명령줄 인자가 환경보다 우선하므로, 시험이 의존하는 설정을 모두 여기서 주면 빌드를 돌리는 셸의 변수(실제 키,
     * 고른 공급자, 승인)가 시험이 보는 것을 바꾸지 못한다. 시험의 설정은 이름으로 이 값을 대신한다. 같은 이름을 두 번
     * 넘기면 두 값이 하나로 합쳐진다.
     */
    private ConfigurableApplicationContext start(String... settings) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("server.port", "0");
        properties.put("management.server.port", "0");
        properties.put("baton.jobs.dispatch-delay", "3600000");
        properties.put("baton.ai.profile", "stub");
        properties.put("BATON_AI_PROVIDER", "none");
        properties.put("BATON_AI_CHAT_PROVIDER", "${BATON_AI_PROVIDER:none}");
        properties.put("BATON_AI_EMBEDDING_PROVIDER", "${BATON_AI_PROVIDER:none}");
        properties.put("BATON_AI_CHAT_MODEL_ID", "chat-model-x");
        properties.put("BATON_AI_EMBEDDING_MODEL_ID", "embed-model-x");
        // 앱은 이 셋을 아래 속성으로 읽고 같은 이름의 환경변수는 그 속성에 바로 묶이므로 속성도 고정한다. 위의 변수
        // 이름은 placeholder에만 들어간다.
        properties.put("baton.ai.chat-model-id", "chat-model-x");
        properties.put("baton.ai.embedding-model-id", "embed-model-x");
        properties.put("baton.ai.external-transmission", "approved");
        properties.put("BATON_OLLAMA_BASE_URL", "http://localhost:11434");
        properties.put("OPENAI_API_KEY", "");
        // SPRING_AI_OPENAI_API_KEY 같은 Spring식 변수는 속성에 바로 묶이고 yaml보다 우선해서, 앱이 읽는 속성도
        // application.yml과 같은 placeholder로 여기서 고정한다.
        properties.put("spring.ai.model.chat", "${BATON_AI_CHAT_PROVIDER:${BATON_AI_PROVIDER:none}}");
        properties.put("spring.ai.model.embedding", "${BATON_AI_EMBEDDING_PROVIDER:${BATON_AI_PROVIDER:none}}");
        properties.put("spring.ai.model.embedding.text", "${BATON_AI_EMBEDDING_PROVIDER:${BATON_AI_PROVIDER:none}}");
        properties.put("spring.ai.openai.api-key", "${OPENAI_API_KEY:}");
        properties.put("spring.ai.ollama.base-url", "${BATON_OLLAMA_BASE_URL:http://localhost:11434}");
        properties.put("GOOGLE_API_KEY", "");
        for (String setting : settings) {
            int split = setting.indexOf('=');
            properties.put(setting.substring(0, split), setting.substring(split + 1));
        }
        // 명령줄 인자는 프로필을 stub으로 고정하는 application-test.yml보다 우선한다.
        return new SpringApplicationBuilder(BatonApplication.class)
                .run(properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    @Test
    void withoutAProviderNoModelIsBuiltAndTheStubRuns() {
        try (ConfigurableApplicationContext context = start()) {
            assertNull(context.getBeanProvider(ChatModel.class).getIfAvailable());
            assertNull(context.getBeanProvider(EmbeddingModel.class).getIfAvailable());
            assertEquals("stub-hashed-bigram-256-v1", context.getBean(EmbeddingClient.class).modelId());
        }
    }

    @Test
    void anEmptyGoogleKeyAsComposePassesItStillStartsTheStub() {
        // compose.yml은 선택 변수를 모두 ${NAME:-}로 넘겨서, Google 키가 없는 스택은 GOOGLE_API_KEY를 없는 변수가
        // 아니라 빈 문자열로 받는다. Spring은 빈 값을 설정된 값으로 본다.
        try (ConfigurableApplicationContext context = start("GOOGLE_API_KEY=")) {
            assertNull(context.getBeanProvider(EmbeddingModel.class).getIfAvailable());
            assertEquals("stub-hashed-bigram-256-v1", context.getBean(EmbeddingClient.class).modelId());
        }
    }

    @Test
    void openAiServesChatAndEmbeddings() {
        try (ConfigurableApplicationContext context = start("baton.ai.profile=provider", "BATON_AI_PROVIDER=openai", "OPENAI_API_KEY=" + key())) {
            assertTrue(context.getBean(ChatModel.class).getClass().getSimpleName().contains("OpenAi"));
            assertTrue(context.getBean(EmbeddingModel.class).getClass().getSimpleName().contains("OpenAi"));
            assertInstanceOf(SpringAiRecordStructurer.class, context.getBean(RecordStructurer.class));
            assertInstanceOf(SpringAiAnswerGenerator.class, context.getBean(GroundedAnswerGenerator.class));
            assertEquals("embed-model-x", context.getBean(EmbeddingClient.class).modelId());
        }
    }

    @Test
    void aKeyValueIsNeverWrittenToTheStartUpLog(CapturedOutput output) {
        String openAiKey = key();
        String googleKey = key();

        try (ConfigurableApplicationContext context = start("baton.ai.profile=provider", "BATON_AI_CHAT_PROVIDER=openai",
                "BATON_AI_EMBEDDING_PROVIDER=google-genai", "OPENAI_API_KEY=" + openAiKey, "GOOGLE_API_KEY=" + googleKey)) {
            assertTrue(context.getBean(ChatModel.class).getClass().getSimpleName().contains("OpenAi"));
        }

        assertTrue(!output.getAll().contains(openAiKey) && !output.getAll().contains(googleKey), "a key value reached the log");
    }

    @Test
    void googleGenAiServesChatAndEmbeddings() {
        try (ConfigurableApplicationContext context = start("baton.ai.profile=provider", "BATON_AI_PROVIDER=google-genai", "GOOGLE_API_KEY=" + key())) {
            assertTrue(context.getBean(ChatModel.class).getClass().getSimpleName().contains("GoogleGenAi"));
            assertTrue(context.getBean(EmbeddingModel.class).getClass().getSimpleName().contains("GoogleGenAi"));
        }
    }

    @Test
    void ollamaNeedsNoKey() {
        try (ConfigurableApplicationContext context = start("baton.ai.profile=local", "BATON_AI_PROVIDER=ollama")) {
            assertTrue(context.getBean(ChatModel.class).getClass().getSimpleName().contains("Ollama"));
            assertTrue(context.getBean(EmbeddingModel.class).getClass().getSimpleName().contains("Ollama"));
        }
    }

    @Test
    void chatAndEmbeddingsCanComeFromDifferentProviders() {
        try (ConfigurableApplicationContext context = start("baton.ai.profile=provider", "BATON_AI_CHAT_PROVIDER=openai",
                "BATON_AI_EMBEDDING_PROVIDER=ollama", "OPENAI_API_KEY=" + key())) {
            assertTrue(context.getBean(ChatModel.class).getClass().getSimpleName().contains("OpenAi"));
            assertTrue(context.getBean(EmbeddingModel.class).getClass().getSimpleName().contains("Ollama"));
        }
    }

    @Test
    void aModelProfileWithoutAnyProviderStopsStartUpAndNamesTheVariables() {
        Throwable failure = assertThrows(Throwable.class, () -> start("baton.ai.profile=provider").close());

        StringBuilder chain = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) chain.append(cause.getMessage()).append('\n');
        assertTrue(chain.toString().contains("BATON_AI_PROVIDER"), chain.toString());
    }

    @Test
    void anExternalProviderIsNotStartedWithoutTheApproval() {
        Throwable failure = assertThrows(Throwable.class, () -> start("baton.ai.profile=provider", "BATON_AI_PROVIDER=openai",
                "OPENAI_API_KEY=" + key(), "baton.ai.external-transmission=").close());

        StringBuilder chain = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) chain.append(cause.getMessage()).append('\n');
        assertTrue(chain.toString().contains("BATON_AI_EXTERNAL_TRANSMISSION"), chain.toString());
    }

    @Test
    void aSelectedProviderWithoutItsKeyStopsStartUpAndNamesTheVariable() {
        Throwable failure = assertThrows(Throwable.class, () -> start("baton.ai.profile=provider", "BATON_AI_PROVIDER=openai").close());

        StringBuilder chain = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) chain.append(cause.getMessage()).append('\n');
        assertTrue(chain.toString().contains("OPENAI_API_KEY"), chain.toString());
    }
}
