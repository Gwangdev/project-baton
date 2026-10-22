package com.projectbaton.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projectbaton.project.ProjectAccessPolicy.ProjectNotFoundException;
import com.projectbaton.audit.AuditService;
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
class IdempotencyIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from idempotency_requests");
        AuditRows.clear(jdbcTemplate);
        jdbcTemplate.update("delete from users");
        jdbcTemplate.update("delete from organizations");
        jdbcTemplate.update("insert into organizations (name) values ('Idempotency Actor Organization')");
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneBusinessRowAndReplayOneResult() throws Exception {
        long actorId = actor();
        UUID key = UUID.randomUUID();
        IdempotencyService.Request request = request(actorId, key, "{\"name\":\"Created once\"}", () -> { });

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<IdempotencyService.Response> command = () -> idempotencyService.execute(request,
                    () -> createOrganization("Created once"));
            List<Future<IdempotencyService.Response>> results = executor.invokeAll(List.of(command, command));

            assertThat(results.get(0).get().responseJson()).isEqualTo(results.get(1).get().responseJson());
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from organizations where name = 'Created once'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsTheSameKeyWhenTheCanonicalRequestBodyDiffers() {
        long actorId = actor();
        UUID key = UUID.randomUUID();
        idempotencyService.execute(request(actorId, key, "{\"name\":\"first\"}", () -> { }),
                () -> createOrganization("first"));

        assertThatThrownBy(() -> idempotencyService.execute(request(actorId, key, "{\"name\":\"second\"}", () -> { }),
                () -> createOrganization("second")))
                .isInstanceOf(IdempotencyService.IdempotencyConflictException.class);
    }

    @Test
    void checksCurrentAuthorizationBeforeReplayingAStoredResponse() {
        long actorId = actor();
        UUID key = UUID.randomUUID();
        AtomicBoolean permitted = new AtomicBoolean(true);
        idempotencyService.execute(request(actorId, key, "{\"name\":\"protected\"}", () -> {
            if (!permitted.get()) {
                throw new ProjectNotFoundException();
            }
        }), () -> createOrganization("protected"));
        permitted.set(false);

        assertThatThrownBy(() -> idempotencyService.execute(request(actorId, key, "{\"name\":\"protected\"}", () -> {
            if (!permitted.get()) {
                throw new ProjectNotFoundException();
            }
        }), () -> createOrganization("must not run")))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void persistsAuditMetadataWithoutStoringRequestContent() {
        long actorId = actor();

        auditService.record(actorId, "CREATE_ORGANIZATION", "organization", "42", "SUCCESS", "trace-42");

        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_events where actor_id = ? and action = ? "
                        + "and target_type = ? and target_id = ? and result = ? and trace_id = ?", Integer.class,
                actorId, "CREATE_ORGANIZATION", "organization", "42", "SUCCESS", "trace-42"))
                .isEqualTo(1);
    }

    private IdempotencyService.Request request(long actorId, UUID key, String body, Runnable authorize) {
        return new IdempotencyService.Request(actorId, "POST", "/api/v1/organizations", key, body, authorize);
    }

    private IdempotencyService.Response createOrganization(String name) {
        long id = jdbcTemplate.queryForObject("insert into organizations (name) values (?) returning id", Long.class, name);
        return new IdempotencyService.Response(201, "{\"id\":" + id + "}", id, false);
    }

    private long actor() {
        return jdbcTemplate.queryForObject("""
                insert into users (organization_id, employee_no, name, title, email)
                values ((select id from organizations where name = 'Idempotency Actor Organization'), 'idempotency-actor',
                        'Actor', 'Engineer', 'idempotency-actor@example.test')
                returning id
                """, Long.class);
    }
}
