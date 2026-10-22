package com.projectbaton.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code ProjectAccessPolicy}와 따로 두는 전역 관리 권한. SYSTEM_ADMIN은 조직, 사용자, 프로젝트, 배정을 관리할 권한을
 * 주지만 프로젝트 업무 내용에 대한 접근을 자동으로 주지는 않는다(README.md §4.2).
 */
@Component
public class SystemAdminAccessPolicy {

    private final JdbcTemplate jdbcTemplate;

    public SystemAdminAccessPolicy(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void requireSystemAdmin(long actorId) {
        if (!isSystemAdmin(actorId)) {
            throw new SystemAdminRequiredException();
        }
    }

    public boolean isSystemAdmin(long actorId) {
        Boolean permitted = jdbcTemplate.queryForObject("""
                select exists (
                    select 1
                    from users u
                    join organizations o on o.id = u.organization_id
                    where u.id = ?
                      and u.is_active
                      and o.is_active
                      and u.system_role = 'SYSTEM_ADMIN'
                )
                """, Boolean.class, actorId);
        return Boolean.TRUE.equals(permitted);
    }

    public static class SystemAdminRequiredException extends RuntimeException {
    }
}
