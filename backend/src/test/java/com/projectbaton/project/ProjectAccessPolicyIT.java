package com.projectbaton.project;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class ProjectAccessPolicyIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ProjectAccessPolicy projectAccessPolicy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from project_members");
        jdbcTemplate.update("delete from projects");
        jdbcTemplate.update("delete from users");
        jdbcTemplate.update("delete from organizations");
        jdbcTemplate.update("insert into organizations (name) values ('Project Access Organization')");
    }

    @Test
    void allowsOnlyAnActiveMemberOfTheRequestedProject() {
        long actorId = user("member");
        long projectId = project("ACCESS-1");
        jdbcTemplate.update("""
                insert into project_members (project_id, user_id, project_role, joined_on)
                values (?, ?, 'MEMBER', current_date)
                """, projectId, actorId);

        assertThatCode(() -> projectAccessPolicy.requireMember(actorId, projectId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> projectAccessPolicy.requireMember(actorId, project("ACCESS-2")))
                .isInstanceOf(ProjectAccessPolicy.ProjectNotFoundException.class);
    }

    @Test
    void doesNotGrantContentAccessToASystemAdminWithoutMembership() {
        long systemAdminId = user("system-admin");
        jdbcTemplate.update("update users set system_role = 'SYSTEM_ADMIN' where id = ?", systemAdminId);

        assertThatThrownBy(() -> projectAccessPolicy.requireMember(systemAdminId, project("ACCESS-3")))
                .isInstanceOf(ProjectAccessPolicy.ProjectNotFoundException.class);
    }

    @Test
    void requiresTheProjectManagerRoleAndRejectsEndedMembership() {
        long actorId = user("former-member");
        long projectId = project("ACCESS-4");
        jdbcTemplate.update("""
                insert into project_members (project_id, user_id, project_role, joined_on, left_on)
                values (?, ?, 'PROJECT_MANAGER', current_date - 2, current_date - 1)
                """, projectId, actorId);

        assertThatThrownBy(() -> projectAccessPolicy.requireManager(actorId, projectId))
                .isInstanceOf(ProjectAccessPolicy.ProjectNotFoundException.class);
    }

    @Test
    void doesNotTreatAnOrdinaryMemberAsAManager() {
        long actorId = user("ordinary-member");
        long projectId = project("ACCESS-5");
        jdbcTemplate.update("""
                insert into project_members (project_id, user_id, project_role, joined_on)
                values (?, ?, 'MEMBER', current_date)
                """, projectId, actorId);

        // README §4.2: 구성원은 프로젝트를 볼 수 있으므로, 관리자 역할이 없는 것은 숨기는 404가 아니라 403이다.

        assertThatThrownBy(() -> projectAccessPolicy.requireManager(actorId, projectId))
                .isInstanceOf(ProjectAccessPolicy.ManagerRequiredException.class);
    }

    private long user(String employeeNo) {
        return jdbcTemplate.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email)
                values ((select id from organizations where name = 'Project Access Organization'), ?, ?, 'Engineer', ?)
                returning id
                """, Long.class, employeeNo, employeeNo, employeeNo + "@example.test");
    }

    private long project(String code) {
        return jdbcTemplate.queryForObject("""
                insert into projects (code, name, delivering_company, client_company, started_on, ends_on, phase, status)
                values (?, ?, 'Project Baton', 'Client', current_date - 1, current_date + 1, 'Delivery', 'ON_TRACK')
                returning id
                """, Long.class, code, code + " project");
    }
}
