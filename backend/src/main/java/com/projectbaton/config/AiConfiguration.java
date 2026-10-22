package com.projectbaton.config;

import com.projectbaton.ai.AiSettings;
import com.projectbaton.record.DraftRecordParser;
import com.projectbaton.record.RecordStructurer;
import com.projectbaton.record.SpringAiRecordStructurer;
import com.projectbaton.record.StubRecordStructurer;
import com.projectbaton.qa.EmbeddingClient;
import com.projectbaton.qa.GroundedAnswerGenerator;
import com.projectbaton.qa.SpringAiAnswerGenerator;
import com.projectbaton.qa.StubAnswerGenerator;
import com.projectbaton.qa.SpringAiEmbeddingClient;
import com.projectbaton.qa.StubEmbeddingClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code baton.ai.profile}(README §7)로 AI 구현을 고른다. {@code stub}은 오프라인 계약 실행용이고, {@code local}과
 * {@code provider}는 배포가 연결한 Spring AI 모델을 쓴다. 모델 bean이 없는 모델 프로필은 조용히 stub으로 돌아가지
 * 않고 기동에서 실패한다.
 */
@Configuration
public class AiConfiguration {

    /**
     * 어떤 bean보다 먼저 돌아서, 동작할 수 없는 프로필은 먼저 닿은 공급자의 오류가 아니라 빠진 설정을 모두 밝히며
     * 기동을 멈춘다.
     */
    @Bean
    static BeanFactoryPostProcessor aiSettingsCheck(Environment environment) {
        return beanFactory -> AiSettings.validate(environment);
    }

    @Bean
    RecordStructurer recordStructurer(@Value("${baton.ai.profile:stub}") String profile, DraftRecordParser parser,
                                      ObjectMapper json, ObjectProvider<ChatModel> chatModel) {
        return switch (profile) {
            case "stub" -> new StubRecordStructurer(parser, json);
            case "local", "provider" -> new SpringAiRecordStructurer(required(chatModel, profile, "ChatModel"), parser);
            default -> throw new IllegalStateException("Unknown baton.ai.profile: " + profile);
        };
    }

    @Bean
    EmbeddingClient embeddingClient(@Value("${baton.ai.profile:stub}") String profile,
                                    @Value("${baton.ai.embedding-model-id:}") String modelId,
                                    ObjectProvider<EmbeddingModel> embeddingModel) {
        return switch (profile) {
            case "stub" -> new StubEmbeddingClient();
            case "local", "provider" -> new SpringAiEmbeddingClient(required(embeddingModel, profile, "EmbeddingModel"), modelId);
            default -> throw new IllegalStateException("Unknown baton.ai.profile: " + profile);
        };
    }

    @Bean
    GroundedAnswerGenerator answerGenerator(@Value("${baton.ai.profile:stub}") String profile,
                                            @Value("${baton.ai.chat-model-id:}") String chatModelId,
                                            ObjectProvider<ChatModel> chatModel, ObjectMapper json) {
        return switch (profile) {
            case "stub" -> new StubAnswerGenerator();
            case "local", "provider" -> new SpringAiAnswerGenerator(required(chatModel, profile, "ChatModel"), chatModelId, json);
            default -> throw new IllegalStateException("Unknown baton.ai.profile: " + profile);
        };
    }

    static <T> T required(ObjectProvider<T> provider, String profile, String type) {
        T bean = provider.getIfAvailable();
        if (bean == null) {
            throw new IllegalStateException("baton.ai.profile=" + profile + " requires a " + type + " bean: choose a provider with "
                    + "BATON_AI_PROVIDER (or BATON_AI_CHAT_PROVIDER / BATON_AI_EMBEDDING_PROVIDER) = openai, google-genai or ollama");
        }
        return bean;
    }
}
