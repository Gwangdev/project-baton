package com.projectbaton.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 로컬 로그인. Spring Security의 DaoAuthenticationProvider는 비밀번호보다 계정 비활성 여부를 먼저 확인해서 틀린
 * 비밀번호로도 비활성 계정이 있다는 것이 드러나고, 비활성 조직이라는 개념도 없어서 쓰지 않는다. 여기서는 비밀번호를
 * 먼저 확인하고 비활성 여부는 그 뒤에 본다.
 */
@Service
public class LocalAuthenticationService {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String unknownAccountHash;

    public LocalAuthenticationService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.unknownAccountHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public CurrentActor authenticate(String employeeNo, String password) {
        Optional<AccountRow> account = jdbcTemplate.query("""
                select u.id, u.employee_no, u.credential_hash, u.is_active and o.is_active as active
                from users u join organizations o on o.id = u.organization_id
                where u.employee_no = ? and u.auth_provider = 'LOCAL'
                """, (resultSet, rowNum) -> new AccountRow(
                resultSet.getLong("id"), resultSet.getString("employee_no"),
                resultSet.getString("credential_hash"), resultSet.getBoolean("active")), employeeNo).stream().findFirst();

        // 비교를 건너뛰면 그 답이 눈에 띄게 빨라져서 어떤 사번이 있는지 드러났다. 그래서 없는 계정이나 자격 증명이
        // 없는 계정도 같은 알고리즘의 버림용 해시와 비교한다.
        String hash = account.map(AccountRow::credentialHash).orElse(null);
        boolean matches = passwordEncoder.matches(password, hash == null ? unknownAccountHash : hash);
        if (hash == null || !matches) {
            throw new BadCredentialsException("Invalid credentials");
        }
        if (!account.get().active()) {
            throw new AccountInactiveException();
        }
        jdbcTemplate.update("update users set last_signed_in_at = now() where id = ?", account.get().id());
        return new CurrentActor(account.get().id(), account.get().employeeNo());
    }

    public CurrentUserResponse currentUser(CurrentActor actor) {
        UserView user = jdbcTemplate.queryForObject("""
                select u.id, u.organization_id, o.name as organization_name, u.employee_no, u.name, u.title, u.email,
                       u.system_role, u.is_active, u.auth_provider, u.version,
                       (u.credential_hash is not null) as login_enabled
                from users u join organizations o on o.id = u.organization_id
                where u.id = ? and u.is_active and o.is_active
                """, (resultSet, rowNum) -> new UserView(
                resultSet.getLong("id"), resultSet.getLong("organization_id"), resultSet.getString("organization_name"),
                resultSet.getString("employee_no"), resultSet.getString("name"), resultSet.getString("title"),
                resultSet.getString("email"), resultSet.getString("system_role"), resultSet.getBoolean("is_active"),
                resultSet.getString("auth_provider"), resultSet.getLong("version"), resultSet.getBoolean("login_enabled")),
                actor.userId());
        List<MembershipView> memberships = jdbcTemplate.query("""
                select p.id, p.code, p.name, pm.project_role
                from project_members pm join projects p on p.id = pm.project_id
                where pm.user_id = ? and p.is_active and pm.joined_on <= current_date
                  and (pm.left_on is null or current_date < pm.left_on)
                order by p.created_at desc, p.id desc
                """, (resultSet, rowNum) -> new MembershipView(resultSet.getLong("id"), resultSet.getString("code"),
                resultSet.getString("name"), resultSet.getString("project_role")), actor.userId());
        return new CurrentUserResponse(user, memberships, accountActivity(actor.userId()));
    }

    /**
     * 이 계정에 일어난 최근 다섯 가지 일(본인의 로그인, 이 계정을 가리키는 설정 코드 행위, 운영자의 비밀번호
     * 재설정)을 최신순으로. 계정 본인의 행만 읽어서 다른 사람의 활동은 보이지 않고, 부분 인덱스 두 개(V18)가 있어서
     * 감사 테이블이 커져도 짧은 인덱스 스캔으로 끝난다. 통합 시험이 바로 이 문장의 실행 계획을 DB에 물어볼 수 있게
     * public으로 둔다.
     */
    public static final String ACCOUNT_ACTIVITY = """
            select action, occurred_at, remote_address from (
                (select id, action, occurred_at, remote_address from audit_events
                  where action = 'SIGN_IN' and actor_id = ? order by occurred_at desc, id desc limit 5)
                union all
                (select id, action, occurred_at, remote_address from audit_events
                  where action in ('ISSUE_CREDENTIAL_TOKEN', 'SET_CREDENTIAL', 'RESET_SYSTEM_ADMIN_CREDENTIAL') and target_id = ? order by occurred_at desc, id desc limit 5)
            ) recent order by occurred_at desc, id desc limit 5
            """;

    private List<ActivityView> accountActivity(long userId) {
        return jdbcTemplate.query(ACCOUNT_ACTIVITY, (resultSet, rowNum) -> new ActivityView(resultSet.getString("action"),
                resultSet.getObject("occurred_at", java.time.OffsetDateTime.class), resultSet.getString("remote_address")),
                userId, Long.toString(userId));
    }

    public boolean isActive(CurrentActor actor) {
        Boolean active = jdbcTemplate.queryForObject("""
                select u.is_active and o.is_active
                from users u join organizations o on o.id = u.organization_id
                where u.id = ?
                """, Boolean.class, actor.userId());
        return Boolean.TRUE.equals(active);
    }

    public static class AccountInactiveException extends RuntimeException {
    }

    public record CurrentUserResponse(UserView user, List<MembershipView> memberships, List<ActivityView> accountActivity) {
    }

    public record ActivityView(String action, java.time.OffsetDateTime occurredAt, String remoteAddress) {
    }

    /**
     * OAS User: authProvider는 필수이고 organizationName은 선언되어 있다. 선언되지 않은 createdAt은 돌려주지 않는다.
     */
    public record UserView(long id, long organizationId, String organizationName, String employeeNo, String name, String title,
                           String email, String systemRole, boolean isActive, String authProvider, long version,
                           boolean loginEnabled) {
    }

    public record MembershipView(long projectId, String projectCode, String projectName, String projectRole) {
    }

    private record AccountRow(long id, String employeeNo, String credentialHash, boolean active) {
    }
}
