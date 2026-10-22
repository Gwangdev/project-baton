package com.projectbaton.project;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projectbaton.shared.AuditRows;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 프로젝트 구성원이 읽는 구성원 목록. 목록이 관리자용 하나뿐이어서, 시스템 관리자가 아닌 PM은 인수인계를 요청하려면
 * 사용자 id를 직접 입력해야 했다. 구성원은 프로젝트의 현재 구성원(접근 정책과 같은 조건: 시작했고, 끝나지 않았고,
 * 사용자와 조직이 활성)을 보고, 프로젝트 밖의 사람은 목록을 아예 보지 못한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProjectRosterIT {
    private static final PasswordEncoder PASSWORDS = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private static final String PASSWORD = UUID.randomUUID().toString();

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    private long project;
    private long manager;
    private long member;

    @BeforeEach
    void reset() {
        AuditRows.clear(jdbc);
        jdbc.execute("truncate table organizations, projects restart identity cascade");
        jdbc.update("insert into organizations(name) values('Roster Organization')");
        project = jdbc.queryForObject("""
                insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status)
                values('ROSTER','Roster','D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id
                """, Long.class);
        manager = user("roster-manager", true);
        member = user("roster-member", true);
        long ended = user("roster-ended", true);
        long inactive = user("roster-inactive", false);
        long future = user("roster-future", true);
        user("roster-outsider", true);
        assign(manager, "PROJECT_MANAGER", -10, null);
        assign(member, "MEMBER", -10, null);
        assign(ended, "MEMBER", -10, 0);
        assign(inactive, "MEMBER", -10, null);
        assign(future, "MEMBER", 5, null);
    }

    @Test
    void aMemberSeesTheProjectsCurrentMembersOnly() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/members", project).cookie(signIn("roster-member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].userId", contains((int) manager, (int) member)))
                .andExpect(jsonPath("$.items[0].projectRole", is("PROJECT_MANAGER")))
                .andExpect(jsonPath("$.items[0].email").doesNotExist())
                .andExpect(jsonPath("$.page.total", is(2)));
    }

    @Test
    void someoneOutsideTheProjectDoesNotSeeIt() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/members", project).cookie(signIn("roster-outsider")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/projects/{projectId}/members", project).cookie(signIn("roster-ended")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theListIsPagedWithinTheContractBounds() throws Exception {
        Cookie session = signIn("roster-manager");
        mockMvc.perform(get("/api/v1/projects/{projectId}/members?limit=1&offset=1", project).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].userId", contains((int) member)))
                .andExpect(jsonPath("$.page.total", is(2)));
        mockMvc.perform(get("/api/v1/projects/{projectId}/members?limit=0", project).cookie(session))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/projects/{projectId}/members?limit=101", project).cookie(session))
                .andExpect(status().isBadRequest());
    }

    private Cookie signIn(String employeeNo) throws Exception {
        return new Cookie("BATON_SESSION", mockMvc.perform(post("/api/v1/auth/sign-in").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("BATON_SESSION").getValue());
    }

    private long user(String employeeNo, boolean active) {
        return jdbc.queryForObject("""
                insert into users(organization_id,employee_no,name,title,email,system_role,credential_hash,is_active)
                values((select id from organizations),?,?,'Engineer',?,'USER',?,?) returning id
                """, Long.class, employeeNo, employeeNo, employeeNo + "@example.test", PASSWORDS.encode(PASSWORD), active);
    }

    /** 날짜는 오늘로부터의 일수다. 배정은 joinedInDays에 시작하고, leftInDays가 주어지면 그때 끝난다. */
    private void assign(long userId, String role, int joinedInDays, Integer leftInDays) {
        jdbc.update("""
                insert into project_members(project_id,user_id,project_role,joined_on,left_on)
                values(?,?,cast(? as project_role),current_date + ?, current_date + cast(? as integer))
                """, project, userId, role, joinedInDays, leftInDays);
    }
}
