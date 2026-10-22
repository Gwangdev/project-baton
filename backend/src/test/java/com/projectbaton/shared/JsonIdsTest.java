package com.projectbaton.shared;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 요청 본문에서 id로 치는 것: 1부터 2^53-1까지의 정수. 정수로 바뀔 뿐인 값은 아니다. */
class JsonIdsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static boolean valid(String json) {
        return JsonIds.isId(JSON.readTree(json));
    }

    @Test
    void acceptsWholeNumbersInsideTheSafeRange() {
        assertTrue(valid("1"));
        assertTrue(valid("2147483648"));
        assertTrue(valid("9007199254740991"));
    }

    @Test
    void rejectsZeroNegativeAndOutOfRangeNumbers() {
        assertFalse(valid("0"));
        assertFalse(valid("-5"));
        assertFalse(valid("9007199254740992"));
        assertFalse(valid("99999999999999999999"));
        assertFalse(valid("-99999999999999999999"));
    }

    @Test
    void rejectsFractionsTextNullAndAMissingValue() {
        assertFalse(valid("1.5"));
        assertFalse(valid("1.0"));
        assertFalse(valid("\"7\""));
        assertFalse(valid("null"));
        assertFalse(valid("true"));
        assertFalse(valid("[1]"));
        assertFalse(JsonIds.isId(null));
    }
}
