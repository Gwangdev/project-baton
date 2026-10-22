package com.projectbaton.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code organizations} 테이블에 JDBC로 직접 접근한다. 호출자에게 ORM entity를 내보내지 않고, 모든 읽기가
 * controller가 직렬화하는 DTO를 그대로 돌려준다(README.md §3: repository는 DB 접근만 하고 entity는 서비스 계층 밖으로
 * 나가지 않는다).
 */
@Repository
class OrganizationRepository {

    private final JdbcTemplate jdbcTemplate;

    OrganizationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<OrganizationService.OrganizationView> list(int limit, int offset) {
        return jdbcTemplate.query("""
                select o.id, o.name, o.is_active, o.version,
                       (select count(*) from users u where u.organization_id = o.id) as user_count
                from organizations o
                order by o.created_at desc, o.id desc
                limit ? offset ?
                """, this::mapView, limit, offset);
    }

    long count() {
        return jdbcTemplate.queryForObject("select count(*) from organizations", Long.class);
    }

    OrganizationService.OrganizationView insert(String name) {
        long id = jdbcTemplate.queryForObject(
                "insert into organizations (name) values (?) returning id", Long.class, name);
        return findView(id).orElseThrow();
    }

    Optional<OrganizationService.OrganizationRow> lockForUpdate(long organizationId) {
        return jdbcTemplate.query("""
                select id, is_active, version from organizations where id = ? for update
                """, (ResultSet resultSet, int rowNum) -> new OrganizationService.OrganizationRow(
                resultSet.getLong("id"), resultSet.getBoolean("is_active"), resultSet.getLong("version")),
                organizationId).stream().findFirst();
    }

    OrganizationService.OrganizationView update(long organizationId, String name, Boolean isActive) {
        jdbcTemplate.update("""
                update organizations
                set name = coalesce(?, name), is_active = coalesce(?, is_active), version = version + 1
                where id = ?
                """, name, isActive, organizationId);
        return findView(organizationId).orElseThrow();
    }

    long countUsers(long organizationId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from users where organization_id = ?", Long.class, organizationId);
    }

    long countActiveSystemAdminsOutsideOrganization(long organizationId) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from users u
                join organizations o on o.id = u.organization_id
                where u.system_role = 'SYSTEM_ADMIN' and u.is_active and o.is_active and o.id <> ?
                """, Long.class, organizationId);
    }

    void delete(long organizationId) {
        jdbcTemplate.update("delete from organizations where id = ?", organizationId);
    }

    private Optional<OrganizationService.OrganizationView> findView(long organizationId) {
        return jdbcTemplate.query("""
                select o.id, o.name, o.is_active, o.version,
                       (select count(*) from users u where u.organization_id = o.id) as user_count
                from organizations o
                where o.id = ?
                """, this::mapView, organizationId).stream().findFirst();
    }

    private OrganizationService.OrganizationView mapView(ResultSet resultSet, int rowNum) throws SQLException {
        return new OrganizationService.OrganizationView(resultSet.getLong("id"), resultSet.getString("name"),
                resultSet.getBoolean("is_active"), resultSet.getLong("user_count"), resultSet.getLong("version"));
    }
}
