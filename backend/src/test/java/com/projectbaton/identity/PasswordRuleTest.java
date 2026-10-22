package com.projectbaton.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 모든 로컬 비밀번호가 따르는 규칙. 설정한 최소 길이부터 128자까지이고, 사번과 같으면 안 된다. */
class PasswordRuleTest {
    @Test
    void acceptsEveryLengthFromTheMinimumToTheMaximum() {
        assertEquals(true, PasswordRule.accepts(12, "emp-1", "a".repeat(12)));
        assertEquals(true, PasswordRule.accepts(12, "emp-1", "a".repeat(PasswordRule.MAX_LENGTH)));
    }

    @Test
    void refusesWhatIsShorterThanTheMinimumLongerThanTheMaximumOrTheEmployeeNumber() {
        assertEquals(false, PasswordRule.accepts(12, "emp-1", "a".repeat(11)));
        assertEquals(false, PasswordRule.accepts(12, "emp-1", "a".repeat(PasswordRule.MAX_LENGTH + 1)));
        assertEquals(false, PasswordRule.accepts(12, "employee-0001", "employee-0001"));
    }

    @Test
    void theMinimumFollowsTheConfiguredValue() {
        assertEquals(true, PasswordRule.accepts(8, "emp-1", "a".repeat(8)));
        assertEquals(false, PasswordRule.accepts(16, "emp-1", "a".repeat(15)));
    }
}
