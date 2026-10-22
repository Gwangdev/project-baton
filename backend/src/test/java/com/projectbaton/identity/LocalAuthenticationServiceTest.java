package com.projectbaton.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 없는 사번도 틀린 비밀번호와 같은 비밀번호 확인 비용이 들어야 한다. 예전에는 맞는 계정이 없으면 바로 돌아와서 응답
 * 시간으로 어떤 사번이 있는지 드러났다.
 */
class LocalAuthenticationServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void anUnknownAccountStillRunsOnePasswordCheck() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("nobody"))).thenReturn(List.of());
        AtomicInteger checks = new AtomicInteger();
        PasswordEncoder encoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence raw) {
                return "{noop}" + raw;
            }

            @Override
            public boolean matches(CharSequence raw, String encoded) {
                checks.incrementAndGet();
                return false;
            }
        };
        LocalAuthenticationService service = new LocalAuthenticationService(jdbc, encoder);

        assertThrows(BadCredentialsException.class, () -> service.authenticate("nobody", "guess"));
        assertEquals(1, checks.get());
    }
}
