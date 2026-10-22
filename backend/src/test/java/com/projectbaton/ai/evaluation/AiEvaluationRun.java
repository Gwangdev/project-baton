package com.projectbaton.ai.evaluation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import com.projectbaton.BatonApplication;
import com.projectbaton.record.DraftRecordParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * 환경이 고른 실제 모델로 합성 평가를 돌린다. 공급자를 부르므로 {@code test}, {@code integrationTest},
 * {@code check}에는 들어가지 않고 요청할 때만 돈다.
 * <pre>
 *   BATON_AI_PROVIDER=ollama BATON_AI_CHAT_MODEL_ID=qwen3:4b BATON_AI_EMBEDDING_MODEL_ID=embeddinggemma ./gradlew aiEvaluation
 * </pre>
 * (채팅과 임베딩을 따로 고르려면 BATON_AI_CHAT_PROVIDER와 BATON_AI_EMBEDDING_PROVIDER, 키는 환경의 OPENAI_API_KEY와
 * GOOGLE_API_KEY). 평가가 운영자 대신 전송을 승인하지 않으므로, OpenAI나 Google을 고르면 환경에
 * {@code BATON_AI_EXTERNAL_TRANSMISSION=approved}도 있어야 한다. 토큰 상한은 {@code -Dbaton.eval.chat-tokens}와
 * {@code -Dbaton.eval.embedding-tokens}(기본 각 1,000,000)이고, {@code -Dbaton.eval.documents}와
 * {@code -Dbaton.eval.questions}로 실행을 줄인다. 앱을 운영과 같은 구성으로 띄워서 모델은 Spring AI가 같은 설정으로
 * 만드는 그 모델이다. 보고서는 출력하고 build/ai-evaluation에 쓴다.
 */
@Tag("ai-evaluation")
@Testcontainers
class AiEvaluationRun {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void evaluateTheChosenModels() throws Exception {
        String shared = setting("BATON_AI_PROVIDER", "none");
        String chatProvider = setting("BATON_AI_CHAT_PROVIDER", shared);
        String embeddingProvider = setting("BATON_AI_EMBEDDING_PROVIDER", shared);
        assumeTrue(!"none".equals(chatProvider) && !"none".equals(embeddingProvider),
                "Choose the models first: BATON_AI_PROVIDER (or BATON_AI_CHAT_PROVIDER and BATON_AI_EMBEDDING_PROVIDER) = openai, google-genai or ollama");
        String profile = "ollama".equals(chatProvider) && "ollama".equals(embeddingProvider) ? "local" : "provider";
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", POSTGRES.getUsername());
        properties.put("spring.datasource.password", POSTGRES.getPassword());
        properties.put("server.port", "0");
        properties.put("management.server.port", "0");
        properties.put("baton.jobs.dispatch-delay", "3600000");
        properties.put("baton.ai.profile", profile);
        // 승인은 운영자의 몫이라 키처럼 환경에서 읽는다. 그래서 BATON_AI_EXTERNAL_TRANSMISSION=approved 없이 OpenAI나
        // Google을 고르면 배포에서와 똑같이 여기서 앱이 멈춘다. 보내는 것은 합성
        // 집합뿐이다(EvaluationSet.requireSynthetic).
        properties.put("baton.ai.external-transmission", setting("BATON_AI_EXTERNAL_TRANSMISSION", ""));
        String[] arguments = properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BatonApplication.class).run(arguments)) {
            ChatModel chat = context.getBean(ChatModel.class);
            EmbeddingModel embedding = context.getBean(EmbeddingModel.class);
            TokenBudget budget = new TokenBudget(Long.getLong("baton.eval.chat-tokens", 1_000_000), Long.getLong("baton.eval.embedding-tokens", 1_000_000));
            EvaluationReport report = new EvaluationRunner(chat, embedding, context.getBean(DraftRecordParser.class), context.getBean(ObjectMapper.class),
                    setting("BATON_AI_CHAT_MODEL_ID", "unset"), setting("BATON_AI_EMBEDDING_MODEL_ID", "unset"), budget)
                    .run(EvaluationSet.load().limited(Integer.getInteger("baton.eval.documents", 50), Integer.getInteger("baton.eval.questions", 30)), chatProvider.equals(embeddingProvider) ? chatProvider : chatProvider + " + " + embeddingProvider);
            assertNotNull(report);
            String markdown = report.toMarkdown();
            System.out.println(markdown);
            Path directory = Path.of("build", "ai-evaluation");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
                    + chatProvider + ".md"), markdown);
        }
    }
}
