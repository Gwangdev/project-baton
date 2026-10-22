package com.projectbaton.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.projectbaton.shared.BusinessCalendar;
import org.junit.jupiter.api.Test;

class DatabasePoolsTest {
    /**
     * 새 연결마다 도는 문장은 실행 중에 시간대로 조립하지 않은 상수다. 그래서 상수와 업무 달력이 어긋나지 않는다.
     */
    @Test
    void theConnectionInitStatementNamesTheBusinessZone() {
        assertEquals("SET TIME ZONE '" + BusinessCalendar.ZONE.getId() + "'", DatabasePools.CONNECTION_INIT_SQL);
    }
}
