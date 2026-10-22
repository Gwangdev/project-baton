package com.projectbaton.ai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;

/**
 * 모델 bean을 만들기 전에, 고른 AI 프로필에 필요한 값이 다 있는지 보고 빠진 것을 환경변수 이름으로 알린다. 키가
 * 없으면 Spring AI도 실패하지만 자기 문구로, 그것도 먼저 닿은 공급자 하나만 알리므로 여기서 빠진 값을 한 번에 모두
 * 말한다. 메시지에는 키의 값을 넣지 않는다.
 *
 * <p>프로필({@code baton.ai.profile}): {@code stub}은 오프라인으로 돌아 필요한 값이 없다. 다만 공급자 이름이 주어지면
 * Spring AI가 그 공급자의 모델을 어차피 만들어서 그 키가 필요하다. {@code local}과 {@code provider}는 Spring AI
 * 모델을 쓴다. 채팅과 임베딩 모델은 따로 고르고({@code BATON_AI_CHAT_PROVIDER}, {@code BATON_AI_EMBEDDING_PROVIDER},
 * 둘을 한 번에는 {@code BATON_AI_PROVIDER}), 값은 {@code openai}, {@code google-genai}, {@code ollama} 가운데
 * 하나라서 배포마다 섞어 쓸 수 있다. OpenAI와 Google은 배포 밖에 있고, 그곳으로 데이터를 보내는 것은 누군가 이름을
 * 밝혀 승인하기 전까지 금지된 자동 행위이므로 {@code BATON_AI_EXTERNAL_TRANSMISSION=approved}도 있어야 한다. 기준은
 * 공급자 이름이 아니라 데이터가 가는 곳이어서, {@code BATON_OLLAMA_BASE_URL}이 배포 네트워크 밖의 호스트를 가리키는
 * Ollama도 같은 승인이 필요하다.
 */
public final class AiSettings {
    static final Set<String> PROVIDERS = Set.of("openai", "google-genai", "ollama");
    /** 배포 밖의 공급자. 보낸 내용이 배포 밖으로 나간다. */
    private static final Set<String> EXTERNAL = Set.of("openai", "google-genai");
    /**
     * 글자로 시작하는 한 단어는 Compose 서비스다. 숫자만 있거나(134744072) 0x…로 시작하면 resolver가 주소로 읽는다.
     */
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private AiSettings() {
    }

    public static void validate(Environment environment) {
        String profile = environment.getProperty("baton.ai.profile", "stub");
        List<String> problems = new ArrayList<>();
        String chat = provider(environment, "spring.ai.model.chat", "BATON_AI_CHAT_PROVIDER", problems);
        String embedding = provider(environment, "spring.ai.model.embedding", "BATON_AI_EMBEDDING_PROVIDER", problems);
        for (String selected : new String[] {chat, embedding}) {
            if ("openai".equals(selected) && blank(environment, "spring.ai.openai.api-key")) {
                problem(problems, "OPENAI_API_KEY is not set");
            }
            if ("google-genai".equals(selected) && blank(environment, "spring.ai.google.genai.api-key")) {
                problem(problems, "GOOGLE_API_KEY is not set");
            }
        }
        // stub은 모델을 쓰지 않지만, 공급자 이름이 주어지면 Spring AI가 그 모델을 만들고 그 모델은 키 없이는
        // 만들어지지 않는다. 그래서 stub 실행은 키만 확인한다. 밖으로 보내는 것도 없고 고정할 모델도 없다.
        if ("stub".equals(profile)) {
            if (!problems.isEmpty()) {
                throw new IllegalStateException("baton.ai.profile=stub still builds the named provider's model: " + String.join("; ", problems)
                        + " (or leave BATON_AI_PROVIDER, BATON_AI_CHAT_PROVIDER and BATON_AI_EMBEDDING_PROVIDER unset)");
            }
            return;
        }
        List<String> external = new ArrayList<>();
        for (String selected : new String[] {chat, embedding}) {
            if (EXTERNAL.contains(selected) && !external.contains(selected)) {
                external.add(selected);
            }
        }
        if (chat.equals("ollama") || embedding.equals("ollama")) {
            String outside = outsideHost(environment.getProperty("spring.ai.ollama.base-url"));
            if (outside != null) {
                external.add("ollama at " + outside + " (BATON_OLLAMA_BASE_URL is outside the deployment's network)");
            }
        }
        if (!external.isEmpty() && !"approved".equals(environment.getProperty("baton.ai.external-transmission"))) {
            problem(problems, "BATON_AI_EXTERNAL_TRANSMISSION=approved is required to send data to " + String.join(" and ", external)
                    + " (design assumption A-04: only synthetic data, until the customer's terms are confirmed)");
        }
        if (blank(environment, "baton.ai.chat-model-id")) {
            problem(problems, "BATON_AI_CHAT_MODEL_ID is not set");
        }
        if (blank(environment, "baton.ai.embedding-model-id")) {
            problem(problems, "BATON_AI_EMBEDDING_MODEL_ID is not set");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("baton.ai.profile=" + profile + " is not usable: " + String.join("; ", problems));
        }
    }

    /**
     * Ollama 주소가 배포 밖이면 그 호스트를, 안이면 null을 돌려준다. 안은 이 기계, Docker 호스트, 사설 네트워크 주소,
     * 글자로 시작하는 한 단어 이름(Compose 서비스), .internal·.local·.localhost 아래의 이름이다. 기동 중에는
     * 네트워크를 쓰면 안 되므로 이름을 풀어 보지 않고, 알아보지 못한 이름이나 읽을 수 없는 주소는 밖으로 본다. 앞에
     * 0이 붙은 IPv4 주소(010.0.0.1, 192.168.01.1)도 밖이다. Java는 10진수로 읽지만 resolver는 8진수로 읽어 다른
     * 기계를 가리킬 수 있다. 주소에 자격 증명이 들어 있을 수 있어서 호스트만 돌려준다.
     */
    static String outsideHost(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String host;
        try {
            host = new URI(baseUrl.trim()).getHost();
        } catch (URISyntaxException unreadable) {
            return "an address that is not a valid URL";
        }
        if (host == null) {
            return "an address that is not a valid URL";
        }
        String name = host.toLowerCase(Locale.ROOT);
        if (name.equals("localhost") || name.equals("host.docker.internal") || name.endsWith(".localhost")
                || name.endsWith(".internal") || name.endsWith(".local") || SERVICE_NAME.matcher(name).matches()) {
            return null;
        }
        if (name.startsWith("[")) {
            return name.equals("[::1]") || name.startsWith("[fc") || name.startsWith("[fd") || name.startsWith("[fe80") ? null : host;
        }
        Matcher address = IPV4.matcher(name);
        if (address.matches()) {
            // 010.0.0.1은 Java에게는 10.0.0.1이지만 앞의 0을 8진수로 읽는 resolver에게는 8.0.0.1이라서 믿지 않는다.
            for (int group = 1; group <= 4; group++) {
                if (address.group(group).length() > 1 && address.group(group).startsWith("0")) {
                    return host;
                }
            }
            int first = Integer.parseInt(address.group(1));
            int second = Integer.parseInt(address.group(2));
            boolean inside = first == 127 || first == 10 || first == 192 && second == 168 || first == 172 && second >= 16 && second <= 31
                    || first == 169 && second == 254;
            return inside ? null : host;
        }
        return host;
    }

    /**
     * 여기서 "none"은 오류가 아니다. 배포가 모델 bean을 직접 넣을 수 있고(통합 시험이 그렇다), 모델 bean이 아예 없는
     * 모델 프로필은 AiConfiguration이 공급자 변수를 밝히며 멈춘다.
     */
    private static String provider(Environment environment, String property, String variable, List<String> problems) {
        String value = environment.getProperty(property, "none");
        if (!"none".equals(value) && !value.isBlank() && !PROVIDERS.contains(value)) {
            problem(problems, variable + " is '" + value + "' but must be one of " + String.join(", ", orderedProviders()));
        }
        return value;
    }

    private static List<String> orderedProviders() {
        return List.of("openai", "google-genai", "ollama");
    }

    private static boolean blank(Environment environment, String property) {
        String value = environment.getProperty(property);
        return value == null || value.isBlank();
    }

    private static void problem(List<String> problems, String text) {
        if (!problems.contains(text)) {
            problems.add(text);
        }
    }
}
