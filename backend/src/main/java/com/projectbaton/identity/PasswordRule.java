package com.projectbaton.identity;

/**
 * 로컬 비밀번호가 무엇일 수 있는지 정하는 유일한 곳. 설정 코드 비밀번호와 부트스트랩 관리자 비밀번호가 함께 따른다.
 */
final class PasswordRule {
    static final int MAX_LENGTH = 128;

    private PasswordRule() {
    }

    static boolean accepts(int minLength, String employeeNo, String password) {
        return password.length() >= minLength && password.length() <= MAX_LENGTH && !password.equals(employeeNo);
    }
}
