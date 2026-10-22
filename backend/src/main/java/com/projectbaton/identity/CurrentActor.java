package com.projectbaton.identity;

import java.io.Serializable;
import java.security.Principal;

/**
 * 로그인한 사용자. 사번을 이름으로 가진 {@link Principal}이라서 Spring Session이 세션을 그 이름으로 색인하고, 그래서
 * 자격 증명이 바뀔 때 한 사용자의 세션을 찾아 끝낼 수 있다.
 */
public record CurrentActor(long userId, String employeeNo) implements Serializable, Principal {
    @Override
    public String getName() {
        return employeeNo;
    }
}
