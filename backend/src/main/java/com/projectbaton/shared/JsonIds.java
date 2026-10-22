package com.projectbaton.shared;

import tools.jackson.databind.JsonNode;

/**
 * 요청 본문이 id로 담을 수 있는 값. 본문 id 파서마다 {@code isIntegralNumber() && longValue() >= 1}을 써서, long
 * 범위를 넘는 수가 잘린 값으로 통과하거나 500으로 끝났고 사용자 관리의 자체 검사는 소수까지 허용했다. JSON
 * 클라이언트가 자릿수를 잃지 않고 담을 수 있는 가장 큰 정수가 2^53-1이어서 id는 1부터 2^53-1까지의 정수이고, 나머지는
 * 호출자의 실수(400)다.
 */
public final class JsonIds {
    private static final long MAX_ID = 9_007_199_254_740_991L;

    private JsonIds() {
    }

    /** {@code value}가 long에 들어가고 1부터 2^53-1 사이인 정수 node이면 true. */
    public static boolean isId(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= 1 && value.longValue() <= MAX_ID;
    }
}
