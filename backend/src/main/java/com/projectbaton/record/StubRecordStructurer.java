package com.projectbaton.record;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 오프라인 프로필. 문서 본문의 SHA-256으로 기록해 둔 모델 출력을 재생하고, 그 밖의 문서에는 기록을 돌려주지 않는다.
 * 글을 직접 분류하지 않아서 이것이 만든 결과를 모델의 판단으로 오해할 일이 없고, 재생한 출력도 실제 모델 출력과 같은
 * 엄격한 parser를 거친다.
 */
public class StubRecordStructurer implements RecordStructurer {
    static final String RESOURCE = "ai-stub/structuring.json";
    private static final String EMPTY = "{\"records\":[]}";

    private final DraftRecordParser parser;
    private final Map<String, String> recorded;

    public StubRecordStructurer(DraftRecordParser parser, ObjectMapper json) {
        this.parser = parser;
        this.recorded = load(json);
    }

    @Override
    public List<DraftRecord> extract(SourceDocument document) {
        return parser.parse(recorded.getOrDefault(sha256(document.body()), EMPTY), document);
    }

    private static Map<String, String> load(ObjectMapper json) {
        try (InputStream input = StubRecordStructurer.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing " + RESOURCE);
            Map<String, String> outputs = new HashMap<>();
            JsonNode root = json.readTree(input);
            root.properties().forEach(entry -> outputs.put(entry.getKey(), entry.getValue().toString()));
            return outputs;
        } catch (IOException exception) {
            throw new IllegalStateException("Unreadable " + RESOURCE, exception);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
