package com.projectbaton.record;

import com.projectbaton.shared.JsonIds;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 모델 출력을 구조화 스키마로 검증하고 그 밖의 것은 거절한다. README §7이 스키마 위반을 저장값으로 고쳐 쓰는 것을
 * 금지하므로, 모르는 필드나 enum, 담당자, 날짜가 있으면 그 항목만 빼거나 추측하지 않고 출력 전체를 실패로 한다. bean
 * converter의 관대한 기본값(모르는 속성 허용, 숫자를 문자열로 변환)이 바로 막아야 할 것이어서 Jackson을 converter가
 * 아니라 엄격한 parser로 쓴다.
 */
@Component
public class DraftRecordParser {
    static final int MAX_RECORDS = 20;
    private static final Set<String> RECORD_TYPES = Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "SCOPE_EXCEPTION", "OPEN_ISSUE");
    private static final Set<String> FIELDS = Set.of("recordType", "title", "summary", "originalExcerpt", "occurredOn", "ownerUserId", "dueOn");

    private final ObjectMapper json;

    public DraftRecordParser(ObjectMapper json) {
        this.json = json;
    }

    public List<RecordStructurer.DraftRecord> parse(String raw, RecordStructurer.SourceDocument document) {
        JsonNode root;
        try {
            root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .readTree(raw == null ? "" : raw);
        } catch (JacksonException exception) {
            throw invalid();
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.has("records") || !root.get("records").isArray()
                || root.get("records").size() > MAX_RECORDS) {
            throw invalid();
        }
        Set<Long> members = new HashSet<>();
        document.members().forEach(member -> members.add(member.userId()));
        String body = collapse(document.body());
        List<RecordStructurer.DraftRecord> drafts = new ArrayList<>();
        for (JsonNode node : root.get("records")) drafts.add(draft(node, members, body));
        return drafts;
    }

    private RecordStructurer.DraftRecord draft(JsonNode node, Set<Long> members, String body) {
        if (!node.isObject() || node.properties().stream().map(Map.Entry::getKey).anyMatch(field -> !FIELDS.contains(field))) {
            throw invalid();
        }
        String recordType = text(node, "recordType", 40);
        if (!RECORD_TYPES.contains(recordType)) throw invalid();
        String excerpt = text(node, "originalExcerpt", 10_000);
        // 바꿔 말하거나 지어낸 문장은 모델의 글을 근거로 내세우게 되므로, 발췌는 원문을 그대로 인용해야 한다.
        if (!body.contains(collapse(excerpt))) throw invalid();
        Long owner = null;
        JsonNode ownerNode = node.get("ownerUserId");
        if (ownerNode != null && !ownerNode.isNull()) {
            if (!JsonIds.isId(ownerNode) || !members.contains(ownerNode.longValue())) throw invalid();
            owner = ownerNode.longValue();
        }
        return new RecordStructurer.DraftRecord(recordType, text(node, "title", 200), text(node, "summary", 4_000), excerpt,
                date(node.get("occurredOn"), false), owner, date(node.get("dueOn"), true));
    }

    private String text(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank() || value.stringValue().length() > maxLength) throw invalid();
        return value.stringValue().strip();
    }

    private LocalDate date(JsonNode value, boolean optional) {
        if (value == null || value.isNull()) {
            if (optional) return null;
            throw invalid();
        }
        if (!value.isString()) throw invalid();
        try {
            return LocalDate.parse(value.stringValue());
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static String collapse(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private static RecordStructurer.StructuringException invalid() {
        return new RecordStructurer.StructuringException("MODEL_OUTPUT_INVALID", false);
    }
}
