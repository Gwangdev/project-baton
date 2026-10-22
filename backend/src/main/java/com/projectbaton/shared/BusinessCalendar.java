package com.projectbaton.shared;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 업무일(README §4.2). 배포는 고객사마다 단일 테넌트이고 해외에서 일해도 바뀌는 것은 네트워크이지 달력이 아니어서,
 * 배정 날짜와 적용일, 규칙이 그 날짜와 비교하는 모든 「오늘」은 한 시간대(Asia/Seoul)의 달력 날짜다. 시각은 UTC로
 * 둔다. DB 연결도 같은 시간대로 시작하므로({@code config/DatabasePools}) SQL의 {@code current_date}와 이 클래스가
 * 같은 날을 가리킨다. 예전에는 각자 JVM이나 DB 서버가 돌던 시간대를 따랐다.
 */
public final class BusinessCalendar {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private BusinessCalendar() {
    }

    public static LocalDate today() {
        return today(Clock.systemUTC());
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
