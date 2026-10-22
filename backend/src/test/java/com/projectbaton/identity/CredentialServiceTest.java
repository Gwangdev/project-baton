package com.projectbaton.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.projectbaton.audit.AuditService;
import com.projectbaton.shared.InvalidInputException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * 응답 시간이 해시를 건너뛰면 공격자가 어떤 사번이 있는지 알게 되므로, 자격 증명 설정은 로그인처럼 계정과 코드가 있든
 * 없든 같은 비용이 들어야 한다. 그래서 비밀번호를 먼저 해시한다.
 */
class CredentialServiceTest {
    private final AtomicInteger encodes = new AtomicInteger();
    private final PasswordEncoder encoder = new PasswordEncoder() {
        @Override public String encode(CharSequence raw) { encodes.incrementAndGet(); return "{noop}" + raw; }
        @Override public boolean matches(CharSequence raw, String encoded) { return false; }
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CredentialService service(JdbcTemplate jdbc) {
        return new CredentialService(jdbc, encoder, mock(AuditService.class), mock(FindByIndexNameSessionRepository.class),
                Duration.ofHours(24), 12);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnknownAccountStillHashesThePasswordOnce() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        assertThrows(CredentialService.InvalidCredentialTokenException.class,
                () -> service(jdbc).setCredential("nobody", "x".repeat(43), "a-long-enough-password", "192.0.2.1"));

        assertEquals(1, encodes.get());
    }

    @Test
    void aWeakPasswordIsRefusedBeforeAnythingIsLookedUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThrows(InvalidInputException.class, () -> service(jdbc).setCredential("someone", "x".repeat(43), "short", "192.0.2.1"));
        assertThrows(InvalidInputException.class, () -> service(jdbc).setCredential("someone", "x".repeat(43), "someone", "192.0.2.1"));
        assertThrows(InvalidInputException.class, () -> service(jdbc).setCredential("someone", "x".repeat(43), "p".repeat(129), "192.0.2.1"));

        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
        assertEquals(0, encodes.get());
    }
}
