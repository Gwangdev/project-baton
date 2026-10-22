package com.projectbaton.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import com.projectbaton.audit.AuditService;
import com.projectbaton.shared.InvalidInputException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 계정의 일회용 설정 코드(README §4.1). 관리자가 코드를 발급하고 사용자가 그 코드로 비밀번호를 정하며, 잊은
 * 비밀번호도 같은 경로로 재설정한다. 코드는 임의의 256비트라서 빠른 해시로 충분하고 비밀번호 해시는 비용만 늘리므로,
 * 코드는 해시로만 저장한다.
 *
 * <p>관리자는 비밀번호를 고르지도 보지도 않지만, 건네주기 전까지 코드를 쥐고 있고 코드를 쥔 사람은 누구나 쓸 수 있다.
 * 그래서 관리자가 코드를 발급한 계정의 비밀번호를 정하고 그 사용자로 로그인할 수 있다. 일반 계정에서는 이를 막지
 * 않는다. 두 요청과 모든 로그인이 들어온 주소와 함께 기록되어 나중에 셋을 비교할 수 있고, 설정 자체는 계정 주인의
 * 이름으로 남는다. 다만 사무실이 함께 쓰는 주소는 알려 주는 것이 적고, 감사 기록은 DB만 읽을 수 있어서 비교에는
 * 한계가 있다.
 *
 * <p>시스템 관리자의 비밀번호는 코드로 정하지 않는다. 시스템 관리자에게는 코드를 발급하지 않고, 이전에 발급한 코드도
 * 계정이 관리자가 되는 순간 틀린 코드와 같은 답으로 거절한다. 관리자가 다른 관리자를 먼저 강등하고 코드를 발급해
 * 비밀번호를 정한 뒤 다시 승격하는 것은 막지 못한다. 관리자 권한 안의 일이라 나중에 추적만 할 수 있고(강등, 발급,
 * 로그인이 감사된다), 비밀번호가 바뀌어 로그인할 수 없게 된 주인은 그 사실을 볼 수 없다. 관리자 비밀번호 재설정은
 * 운영자가 맡는다(AdminBootstrap).
 */
@Service
public class CredentialService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final FindByIndexNameSessionRepository<?> sessions;
    private final Duration tokenLifetime;
    private final int minPasswordLength;

    public CredentialService(JdbcTemplate jdbc, PasswordEncoder passwords, AuditService audit,
                             FindByIndexNameSessionRepository<?> sessions,
                             @Value("${baton.auth.credential-token-ttl:PT24H}") Duration tokenLifetime,
                             @Value("${baton.auth.password-min-length:12}") int minPasswordLength) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
        this.sessions = sessions;
        this.tokenLifetime = tokenLifetime;
        this.minPasswordLength = minPasswordLength;
    }

    /**
     * 사용자의 쓰지 않은 코드를 새 코드로 바꾼다. 평문 코드는 여기서만 돌려준다. 요청 주소는 관리자의 것이고 감사
     * 행에 남는다.
     */
    @Transactional
    public IssuedToken issue(long actorId, long userId, String remoteAddress) {
        List<Target> eligible = jdbc.query("""
                select u.is_active and o.is_active and u.auth_provider = 'LOCAL', u.system_role::text
                from users u join organizations o on o.id = u.organization_id
                where u.id = ? for no key update of u
                """, (rs, row) -> new Target(rs.getBoolean(1), rs.getString(2)), userId);
        if (eligible.isEmpty()) {
            throw new UserManagementService.UserNotFoundException();
        }
        if (!eligible.getFirst().usable()) {
            throw new UserManagementService.UserConflictException("Only an active local account can receive a setup code.");
        }
        // 코드를 쥔 사람은 누구나 비밀번호를 정할 수 있어서 관리자가 다른 관리자의 계정을 가져갈 수 있다. 그래서
        // 시스템 관리자의 비밀번호는 API가 아니라 운영자가 재설정한다(AdminBootstrap).
        if ("SYSTEM_ADMIN".equals(eligible.getFirst().systemRole())) {
            throw new UserManagementService.UserConflictException("A system administrator's password is reset by the operator, not with a setup code.");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(tokenLifetime);
        jdbc.update("delete from credential_tokens where user_id = ? and used_at is null", userId);
        jdbc.update("insert into credential_tokens(user_id, token_hash, expires_at, created_by) values (?, ?, ?, ?)",
                userId, sha256(token), expiresAt, actorId);
        audit.record(actorId, "ISSUE_CREDENTIAL_TOKEN", "user", Long.toString(userId), "SUCCESS", null, remoteAddress);
        return new IssuedToken(token, expiresAt);
    }

    /**
     * 코드가 사용자 본인의 것이고 쓰지 않았고 만료되지 않았으면 비밀번호를 정한다. 코드를 찾기 전에 비밀번호를
     * 해시해서, 없는 사번과 틀린 코드와 맞는 코드가 모두 같은 비용이 든다. 답도 같아서 호출자는 어떤 사번이 있는지 알
     * 수 없다.
     */
    @Transactional
    public void setCredential(String employeeNo, String token, String newPassword, String remoteAddress) {
        checkPassword(employeeNo, newPassword);
        String hash = passwords.encode(newPassword);
        // 발급은 사용자를 잠그고 코드를 지운다. 예전에는 여기서 코드를 잠그고 사용자를 바꿔서 둘이 서로를 기다릴 수
        // 있었고, PostgreSQL이 그 가운데 하나를 교착 오류로 끝냈다. 그래서 여기서도 사용자를 먼저 잠근다. 없는
        // 사번에도 조회가 돌아서 작업량이 계정의 존재 여부에 따라 달라지지 않는다. 일반 계정에 발급한 코드가, 쓰기
        // 전에 계정이 승격됐다고 관리자 비밀번호를 정해서는 안 되므로, 코드를 찾는 쿼리에서 시스템 관리자의 코드를
        // 거절해 틀린 코드와 같은 답과 비용으로 끝낸다. 이 경로는 키 열을 바꾸지 않아서 no-key-update로 충분하고,
        // 평범한 for update와 달리 사용자를 참조하는 동시 insert를 붙잡지 않는다.
        jdbc.query("select id from users where employee_no = ? for no key update", (rs, row) -> rs.getLong(1), employeeNo);
        List<long[]> found = jdbc.query("""
                select t.id, u.id
                from credential_tokens t
                join users u on u.id = t.user_id
                join organizations o on o.id = u.organization_id
                where t.token_hash = ? and u.employee_no = ? and t.used_at is null and t.expires_at > now()
                  and u.system_role <> 'SYSTEM_ADMIN'
                  and u.is_active and o.is_active and u.auth_provider = 'LOCAL'
                for update of t
                """, (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)}, sha256(token), employeeNo);
        if (found.isEmpty()) {
            throw new InvalidCredentialTokenException();
        }
        long tokenId = found.getFirst()[0];
        long userId = found.getFirst()[1];
        jdbc.update("update credential_tokens set used_at = now() where id = ?", tokenId);
        jdbc.update("update users set credential_hash = ?, version = version + 1 where id = ?", hash, userId);
        audit.record(userId, "SET_CREDENTIAL", "user", Long.toString(userId), "SUCCESS", null, remoteAddress);
        // 재설정은 잊었거나 노출된 비밀번호를 위한 것이므로, 옛 비밀번호로 로그인한 세션은 모두 끝낸다.
        sessions.findByPrincipalName(employeeNo).keySet().forEach(sessions::deleteById);
    }

    /** 쓴 코드와 오래전에 만료된 코드에는 감사 기록에 없는 정보가 없어서 다른 운영 행과 함께 지운다. */
    public void deleteExpired() {
        jdbc.update("delete from credential_tokens where used_at is not null or expires_at < now() - interval '1 day'");
    }

    private void checkPassword(String employeeNo, String password) {
        if (!PasswordRule.accepts(minPasswordLength, employeeNo, password)) {
            throw new InvalidInputException("newPassword", "Must be " + minPasswordLength + " to " + PasswordRule.MAX_LENGTH
                    + " characters and not the employee number.");
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedToken(String token, OffsetDateTime expiresAt) { }

    private record Target(boolean usable, String systemRole) { }

    /** 없는 계정, 틀린 코드, 만료된 코드, 쓴 코드, 남의 코드가 모두 같은 실패라서 답이 그 이상을 알려 주지 않는다. */
    public static class InvalidCredentialTokenException extends RuntimeException { }
}
