package com.projectbaton.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 통합 시험의 모든 MockMvc 교환(메서드, 경로, 상태, content type, 본문)을 기록해서 ResponseContractCheck가 실행 뒤
 * OAS와 비교할 수 있게 한다. META-INF/spring으로 모든 {@code @AutoConfigureMockMvc} context에 들어가서 시험이 따로 켤
 * 필요가 없다. {@code baton.response-observations} 시스템 속성(integrationTest 작업이 설정한다)이 없으면 아무것도
 * 기록하지 않는다.
 */
@AutoConfiguration
public class ResponseRecordingConfiguration {
    private static final String DIRECTORY = System.getProperty("baton.response-observations");
    private static final Path FILE = DIRECTORY == null ? null : Path.of(DIRECTORY, UUID.randomUUID() + ".jsonl");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    MockMvcBuilderCustomizer responseRecording() {
        return builder -> builder.alwaysDo(ResponseRecordingConfiguration::record);
    }

    static void record(MvcResult result) throws Exception {
        if (FILE == null || result.getRequest().isAsyncStarted()) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("method", result.getRequest().getMethod());
        line.put("path", result.getRequest().getRequestURI());
        line.put("status", result.getResponse().getStatus());
        line.put("contentType", result.getResponse().getContentType());
        line.put("body", result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        synchronized (ResponseRecordingConfiguration.class) {
            try {
                Files.createDirectories(FILE.getParent());
                Files.writeString(FILE, JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
