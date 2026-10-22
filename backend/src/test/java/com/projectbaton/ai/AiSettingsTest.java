package com.projectbaton.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 동작할 수 없는 모델 프로필은 기동을 멈추고 빠진 설정을 이름으로 알려야 한다. 이 메시지는 로그에 남으므로 키의 값은
 * 넣지 않는다.
 */
class AiSettingsTest {
    private static MockEnvironment environment(String profile, String chat, String embedding) {
        return new MockEnvironment().withProperty("baton.ai.profile", profile)
                .withProperty("spring.ai.model.chat", chat).withProperty("spring.ai.model.embedding", embedding)
                .withProperty("baton.ai.chat-model-id", "chat-x").withProperty("baton.ai.embedding-model-id", "embed-x")
                .withProperty("baton.ai.external-transmission", "approved");
    }

    private static String message(MockEnvironment environment) {
        return assertThrows(IllegalStateException.class, () -> AiSettings.validate(environment)).getMessage();
    }

    @Test
    void theStubProfileNeedsNothing() {
        assertDoesNotThrow(() -> AiSettings.validate(new MockEnvironment()));
        assertDoesNotThrow(() -> AiSettings.validate(environment("stub", "none", "none")));
    }

    /**
     * Spring AI는 프로필과 상관없이 고른 공급자의 모델을 만들어서, 공급자 이름을 준 stub 실행도 그 공급자의 키가
     * 필요하다. 이 확인이 없을 때는 공급자 라이브러리 자체의 메시지(Google은 Vertex 프로젝트 id)로 기동이 멈췄고, 그
     * 메시지는 이 배포의 변수를 하나도 밝히지 않았다.
     */
    @Test
    void aStubRunThatNamesAProviderStillNeedsItsKeyAndNothingElse() {
        assertTrue(message(environment("stub", "none", "google-genai")).contains("GOOGLE_API_KEY"));
        assertTrue(message(environment("stub", "openai", "none")).contains("OPENAI_API_KEY"));
        MockEnvironment withKey = environment("stub", "openai", "none").withProperty("spring.ai.openai.api-key", "present");
        withKey.withProperty("baton.ai.external-transmission", "").withProperty("baton.ai.chat-model-id", "");
        assertDoesNotThrow(() -> AiSettings.validate(withKey));
    }

    @Test
    void anUnknownProviderNamesTheAllowedOnes() {
        String text = message(environment("provider", "mystery", "ollama"));
        assertTrue(text.contains("BATON_AI_CHAT_PROVIDER") && text.contains("openai, google-genai, ollama"), text);
        assertTrue(message(environment("provider", "ollama", "mystery")).contains("BATON_AI_EMBEDDING_PROVIDER"));
    }

    @Test
    void noProviderIsLeftToTheBeanCheckBecauseADeploymentMaySupplyItsOwnModel() {
        assertDoesNotThrow(() -> AiSettings.validate(environment("provider", "none", "none")));
    }

    @Test
    void aSelectedProviderNeedsItsKeyAndTheMessageNamesTheVariable() {
        assertTrue(message(environment("provider", "openai", "openai")).contains("OPENAI_API_KEY"));
        assertTrue(message(environment("provider", "google-genai", "google-genai")).contains("GOOGLE_API_KEY"));
        assertDoesNotThrow(() -> AiSettings.validate(environment("local", "ollama", "ollama")));
    }

    /** 배포 밖의 공급자에게 데이터를 보내는 것은 누군가 이름을 밝혀 승인하기 전까지 금지된 자동 행위다. */
    @Test
    void anExternalProviderNeedsAnExplicitApprovalToReceiveData() {
        MockEnvironment environment = environment("provider", "openai", "ollama").withProperty("spring.ai.openai.api-key", "present");
        environment.withProperty("baton.ai.external-transmission", "");

        String text = message(environment);

        assertTrue(text.contains("BATON_AI_EXTERNAL_TRANSMISSION=approved") && text.contains("openai"), text);
        assertDoesNotThrow(() -> AiSettings.validate(environment("local", "ollama", "ollama").withProperty("baton.ai.external-transmission", "")));
    }

    @Test
    void theModelIdsMustBePinned() {
        MockEnvironment environment = new MockEnvironment().withProperty("baton.ai.profile", "local")
                .withProperty("spring.ai.model.chat", "ollama").withProperty("spring.ai.model.embedding", "ollama");

        String text = message(environment);

        assertTrue(text.contains("BATON_AI_CHAT_MODEL_ID") && text.contains("BATON_AI_EMBEDDING_MODEL_ID"), text);
    }

    @Test
    void keysAreCheckedPerSelectedProviderOnly() {
        MockEnvironment hybrid = environment("provider", "openai", "ollama").withProperty("spring.ai.openai.api-key", "present");

        assertDoesNotThrow(() -> AiSettings.validate(hybrid));
    }

    @Test
    void aKeyValueNeverAppearsInAMessage() {
        String secret = "secret-" + java.util.UUID.randomUUID();
        MockEnvironment environment = environment("provider", "openai", "mystery").withProperty("spring.ai.openai.api-key", secret);

        assertFalse(message(environment).contains(secret));
    }

    private static MockEnvironment ollamaAt(String baseUrl) {
        return environment("local", "ollama", "ollama").withProperty("baton.ai.external-transmission", "")
                .withProperty("spring.ai.ollama.base-url", baseUrl);
    }

    /** 배포 네트워크 안이나 호스트의 모델 서버는 밖으로 나가는 데이터를 받지 않는다. */
    @Test
    void anOllamaInsideTheDeploymentNeedsNoApproval() {
        for (String inside : new String[] {"http://localhost:11434", "http://127.0.0.1:11434", "http://[::1]:11434",
                "http://host.docker.internal:11434", "http://ollama:11434", "http://10.1.2.3:11434", "http://172.16.0.1:11434",
                "http://172.31.255.254", "http://192.168.0.5:11434", "http://gpu.corp.internal:11434", "http://box.local:11434",
                "http://llm.localhost:11434"}) {
            assertDoesNotThrow(() -> AiSettings.validate(ollamaAt(inside)), inside);
        }
    }

    /** 데이터가 나가는지를 정하는 것은 공급자 이름이 아니다. 원격 호스트의 Ollama는 OpenAI만큼 외부다. */
    @Test
    void anOllamaOnARemoteHostNeedsTheSameApprovalAsAnExternalProvider() {
        for (String outside : new String[] {"https://llm.example.com", "http://203.0.113.9:11434", "http://172.32.0.1:11434",
                "http://11.0.0.1:11434", "http://not a url", "http://134744072:11434", "http://0x08080808", "http://010.0.0.1:11434", "http://10.00.0.1"}) {
            String text = message(ollamaAt(outside));
            assertTrue(text.contains("BATON_AI_EXTERNAL_TRANSMISSION=approved") && text.contains("BATON_OLLAMA_BASE_URL"), outside + ": " + text);
        }
        assertDoesNotThrow(() -> AiSettings.validate(ollamaAt("https://llm.example.com").withProperty("baton.ai.external-transmission", "approved")));
    }

    /** 숫자로만 된 이름은 resolver에게 한 단어 서비스 이름이 아니라 주소다(134744072는 8.8.8.8). */
    @Test
    void aNumberWithoutDotsIsAnAddressNotAServiceName() {
        assertTrue(message(ollamaAt("http://134744072:11434")).contains("BATON_OLLAMA_BASE_URL"));
        assertDoesNotThrow(() -> AiSettings.validate(ollamaAt("http://ollama-gpu-1:11434")));
    }

    /** Java의 URI는 밑줄이 있는 호스트를 읽지 못해서, 그런 주소는 읽을 수 없는 주소로 보고 밖으로 센다. */
    @Test
    void anAddressTheUriParserCannotReadNeedsTheApproval() {
        assertTrue(message(ollamaAt("http://ollama_gpu:11434")).contains("BATON_OLLAMA_BASE_URL"));
    }

    @Test
    void aBaseUrlCredentialIsNeverPartOfTheMessage() {
        String text = message(ollamaAt("https://someone:secret-pw@llm.example.com"));

        assertTrue(text.contains("llm.example.com"));
        assertFalse(text.contains("secret-pw") || text.contains("someone"), text);
    }

    @Test
    void anOllamaThatIsNotSelectedIsNotLookedAt() {
        assertDoesNotThrow(() -> AiSettings.validate(environment("provider", "none", "none").withProperty("spring.ai.ollama.base-url", "https://llm.example.com")
                .withProperty("baton.ai.external-transmission", "")));
    }
}
