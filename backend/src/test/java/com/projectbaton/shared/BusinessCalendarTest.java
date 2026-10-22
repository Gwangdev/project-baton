package com.projectbaton.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** JVM이나 DB가 어떤 시간대로 돌든 업무일은 서울 달력 날짜다. */
class BusinessCalendarTest {
    @Test
    void anInstantLateInTheUtcDayIsAlreadyTheNextBusinessDay() {
        Clock lateUtc = Clock.fixed(Instant.parse("2026-10-05T16:00:00Z"), ZoneOffset.UTC);

        assertEquals(LocalDate.of(2026, 10, 6), BusinessCalendar.today(lateUtc));
    }

    @Test
    void theJvmDefaultZoneDoesNotMoveTheBusinessDay() {
        ZoneId before = ZoneId.systemDefault();
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"));
        try {
            assertEquals(LocalDate.now(ZoneId.of("Asia/Seoul")), BusinessCalendar.today());
        } finally {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(before));
        }
    }
}
