package com.projectbaton.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.projectbaton.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영 스택의 첫 SYSTEM_ADMIN을 만든다. 공개 가입이 없고 관리자가 만든 계정은 발급 전까지 자격 증명이 없어서(README
 * §4.1), 새 DB에는 들어갈 길이 아예 없었다. BATON_BOOTSTRAP_ADMIN_* 변수가 모두 있고 활성 시스템 관리자가 없으면,
 * 지정한 조직에 해시한 비밀번호로 한 명을 만든다. 관리자가 생긴 뒤에는 변수를 무시하고 값도 검사하지 않아서, 예전에
 * 남겨 둔 비밀번호가 재기동을 막지 못하고 변수를 그대로 둬도 계정을 다시 만들지 않는다. 아래 재설정 플래그를 함께
 * 켜지 않으면 계정을 재설정하지도 않는다. 일부만 설정하면 관리자 없이 이유도 모른 채 뜬 스택이 되므로 기동을 멈춘다.
 * 값은 로그에 남기지 않는다.
 *
 * <p>시스템 관리자에게는 설정 코드를 발급하지 않고 이전에 발급한 코드도 그 계정이 관리자가 되면
 * 거절해서(CredentialService), 비밀번호를 잃은 관리자는 API로 돌아올 길이 없다. 운영자에게는 길이 있다.
 * BATON_BOOTSTRAP_ADMIN_RESET=true와 같은 변수로 기동하면 그 사번의 활성 로컬 관리자 비밀번호를 바꾸고, 관리자의
 * 세션을 끝내고, 재설정을 감사에 남긴다. 배포 변수를 바꿀 수 있는 사람만 할 수 있고, 플래그가 켜져 있는 동안 기동할
 * 때마다 경고해서 실수로 켜 둔 채 남지 않게 한다.
 */
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String TITLE = "System administrator";
    /** 같은 DB를 쓰는 여러 인스턴스의 기동을 차례대로 세운다. */
    private static final long BOOTSTRAP_LOCK = 0x41444d494eL;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final AuditService audit;
    private final Settings settings;
    private final int minPasswordLength;

    public AdminBootstrap(JdbcTemplate jdbc, PasswordEncoder passwords, TransactionTemplate transactions, AuditService audit,
                          Settings settings, int minPasswordLength) {
        this.minPasswordLength = minPasswordLength;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.transactions = transactions;
        this.audit = audit;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        run();
    }

    public void run() {
        List<String> missing = settings.missing();
        if (missing.size() == Settings.VARIABLES.size()) {
            if (settings.reset()) {
                // 재설정할 대상이 없으면 아무것도 하지 않고 아무 말도 없이 끝나므로 막는다.
                throw new IllegalStateException("BATON_BOOTSTRAP_ADMIN_RESET is set without the other BATON_BOOTSTRAP_ADMIN_* variables");
            }
            return;
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Bootstrap administrator settings are incomplete; missing " + String.join(", ", missing));
        }
        if (settings.reset()) {
            requireAcceptablePassword();
            reset();
            return;
        }
        transactions.executeWithoutResult(status -> {
            // 잠금 키는 상수라 예전의 문자열 이어 붙이기도 주입될 수는 없었지만, 글자를 이어 붙여 만든 SQL은 나중에
            // 누군가 변수로 바꿔 베끼는 패턴이어서 다른 문장 파라미터처럼 bind한다.
            jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, BOOTSTRAP_LOCK);
            Boolean exists = jdbc.queryForObject("""
                    select exists(select 1 from users u join organizations o on o.id = u.organization_id
                                  where u.system_role = 'SYSTEM_ADMIN' and u.is_active and o.is_active)
                    """, Boolean.class);
            if (Boolean.TRUE.equals(exists)) {
                return;
            }
            // 관리자가 생기면 변수를 무시하므로, 실제로 만들 계정만 규칙을 지키게 한다. 예전에 남겨 둔 값은 검사하지
            // 않아서 기동을 막지 못한다.
            requireAcceptablePassword();
            // 비활성 조직의 관리자는 로그인할 수 없고, 이미 다른 사람의 사번이나 이메일이면 고유 키에서 아무 설명
            // 없이 실패한다. 그래서 둘 다 이유를 밝히며 기동을 멈춘다.
            Boolean organizationActive = jdbc.query("select is_active from organizations where name = ?",
                    rs -> rs.next() ? rs.getBoolean(1) : null, settings.organization());
            if (Boolean.FALSE.equals(organizationActive)) {
                throw new IllegalStateException("BATON_BOOTSTRAP_ADMIN_ORGANIZATION names an inactive organization");
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from users where employee_no = ? or email = ?)",
                    Boolean.class, settings.employeeNo(), settings.email()))) {
                throw new IllegalStateException("BATON_BOOTSTRAP_ADMIN_EMPLOYEE_NO or _EMAIL is already used by another user");
            }
            jdbc.update("insert into organizations(name) values(?) on conflict (name) do nothing", settings.organization());
            long userId = jdbc.queryForObject("""
                    insert into users(organization_id,employee_no,name,title,email,system_role,credential_hash)
                    values((select id from organizations where name = ?),?,?,?,?,'SYSTEM_ADMIN',?) returning id
                    """, Long.class, settings.organization(), settings.employeeNo(), settings.name(), TITLE, settings.email(),
                    passwords.encode(settings.password()));
            audit.record(null, "BOOTSTRAP_SYSTEM_ADMIN", "user", Long.toString(userId), "SUCCESS", null);
            log.info("Created the bootstrap system administrator (user {}).", userId);
        });
    }

    /**
     * 관리자는 권한이 가장 큰 계정이라서 비밀번호도 설정 코드 비밀번호와 같은 규칙(PasswordRule)을 따른다. 메시지에는
     * 값을 넣지 않는다.
     */
    private void requireAcceptablePassword() {
        if (!PasswordRule.accepts(minPasswordLength, settings.employeeNo(), settings.password())) {
            throw new IllegalStateException("BATON_BOOTSTRAP_ADMIN_PASSWORD must be " + minPasswordLength + " to " + PasswordRule.MAX_LENGTH
                    + " characters and differ from the employee number");
        }
    }

    private void reset() {
        transactions.executeWithoutResult(status -> {
            jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, BOOTSTRAP_LOCK);
            List<Long> found = jdbc.queryForList("""
                    select u.id from users u join organizations o on o.id = u.organization_id
                    where u.employee_no = ? and u.system_role = 'SYSTEM_ADMIN' and u.auth_provider = 'LOCAL'
                      and u.is_active and o.is_active
                    """, Long.class, settings.employeeNo());
            if (found.isEmpty()) {
                throw new IllegalStateException("BATON_BOOTSTRAP_ADMIN_RESET is set but no active local system administrator has the "
                        + "employee number in BATON_BOOTSTRAP_ADMIN_EMPLOYEE_NO");
            }
            long userId = found.getFirst();
            jdbc.update("update users set credential_hash = ?, version = version + 1 where id = ?", passwords.encode(settings.password()), userId);
            // 옛 비밀번호를 가진 사람은 세션도 가지고 있을 수 있으므로, 재설정과 함께 이 관리자의 세션을 끝낸다.
            jdbc.update("delete from spring_session where principal_name = ?", settings.employeeNo());
            audit.record(null, "RESET_SYSTEM_ADMIN_CREDENTIAL", "user", Long.toString(userId), "SUCCESS", null);
            log.warn("BATON_BOOTSTRAP_ADMIN_RESET is set: the password of system administrator {} was reset and the sessions ended. "
                    + "Remove the variable once the administrator has signed in.", userId);
        });
    }

    public record Settings(String employeeNo, String password, String name, String email, String organization, boolean reset) {
        static final List<String> VARIABLES = List.of("BATON_BOOTSTRAP_ADMIN_EMPLOYEE_NO", "BATON_BOOTSTRAP_ADMIN_PASSWORD",
                "BATON_BOOTSTRAP_ADMIN_NAME", "BATON_BOOTSTRAP_ADMIN_EMAIL", "BATON_BOOTSTRAP_ADMIN_ORGANIZATION");

        List<String> missing() {
            Map<String, String> values = Map.of(VARIABLES.get(0), blankIfNull(employeeNo), VARIABLES.get(1), blankIfNull(password),
                    VARIABLES.get(2), blankIfNull(name), VARIABLES.get(3), blankIfNull(email), VARIABLES.get(4), blankIfNull(organization));
            List<String> missing = new ArrayList<>();
            for (String variable : VARIABLES) {
                if (values.get(variable).isBlank()) {
                    missing.add(variable);
                }
            }
            return missing;
        }

        private static String blankIfNull(String value) {
            return value == null ? "" : value;
        }
    }
}
