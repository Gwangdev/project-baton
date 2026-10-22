package com.projectbaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.projectbaton.handover.HandoverRequestService;
import com.projectbaton.config.JobHousekeeping;
import com.projectbaton.jobs.JobLeaseService;
import com.projectbaton.jobs.JobSlots;
import com.projectbaton.jobs.LeaseHeartbeat;
import com.projectbaton.jobs.DatabaseLane;
import com.projectbaton.qa.StubEmbeddingClient;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NFR-08(README §8): 모델이 느리거나 닿지 않을 때, 벡터 검색이 멈췄을 때도 일반 작업은 지연을 유지하고 서비스는 준비
 * 상태로 남는다. 서버, worker, 작업 slot, HTTP client는 진짜이고, 모델만 호출을 붙잡거나 실패시키거나 답하게 할 수
 * 있는 시험용 대역 뒤에 있다. 부하는 경우마다 15초 동안 초당 30건의 일반 API 요청(조회, 타임라인 한 페이지, 메모
 * 쓰기)이다.
 *
 * <p>모델 호출을 붙잡아 30초 지연을 흉내 낸다. 호출은 경우가 끝나거나 30초가 지나야 돌아와서, 측정 내내 모든 AI
 * slot(생성 6, 임베딩 1, 채팅 3)이 바쁘다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "baton.ai.profile=local", "baton.ai.embedding-model-id=test-hashed-256", "baton.ai.chat-model-id=faulty-chat",
        "baton.qa.model-timeout=40s", "baton.jobs.dispatch-delay=200", "baton.jobs.heartbeat-interval=1000",
        "baton.session.cookie-secure=false", "management.server.port=0"})
@Testcontainers
@Import(FailureIsolationIT.Models.class)
class FailureIsolationIT {
    // 시험이 이 값으로 계정을 만들고 로그인해서, 실행마다 만들고 자격 증명 문자열을 소스에 두지 않는다.
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Set<String> TYPES = Set.of("SCHEDULE", "CHANGE_DECISION", "COMMITMENT", "BLOCKER", "OPEN_ISSUE", "SCOPE_EXCEPTION");
    private static final int RPS = 30;
    private static final Duration WINDOW = Duration.ofSeconds(15);

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired LeaseHeartbeat heartbeat;
    @Autowired JobHousekeeping housekeeping;
    @Autowired ObjectMapper json;
    @Autowired HandoverRequestService handovers;
    @Autowired FaultyChatModel chat;
    @Autowired FaultyEmbeddingModel embedding;
    @Autowired @Qualifier("generationSlots") JobSlots generationSlots;
    @Autowired @Qualifier("embeddingSlots") JobSlots embeddingSlots;

    private final List<Fixture> fixtures = new ArrayList<>();
    private Client reader;

    @BeforeEach
    void setUp() throws Exception {
        chat.normal();
        embedding.normal();
        jdbc.update("delete from jobs");
        jdbc.execute("truncate table organizations, projects restart identity cascade");
        jdbc.update("insert into organizations(name) values('Failure Isolation Organization')");
        long manager = user("fi-manager");
        long readerId = user("fi-reader");
        fixtures.clear();
        for (int i = 1; i <= 3; i++) fixtures.add(project(i, manager, readerId));
        for (Fixture fixture : fixtures) waitUntil("package " + fixture.handoverId() + " READY", Duration.ofSeconds(30),
                () -> "READY".equals(jdbc.queryForObject("select status::text from handovers where id=?", String.class, fixture.handoverId())));
        reader = Client.signIn(this, "fi-reader");
    }

    @AfterEach
    void releaseModels() {
        chat.normal();
        embedding.normal();
    }

    @Test
    void generalApiKeepsItsLatencyWhileEveryAiSlotIsHeldBySlowModelCalls() throws Exception {
        chat.hold();
        embedding.holdIndexing();
        Fixture first = fixtures.getFirst();
        for (int i = 0; i < 8; i++) pendingSource(first, "slow-" + i);
        jdbc.queryForList("select id from work_records where project_id=? order by id limit 3", Long.class, first.projectId())
                .forEach(id -> jdbc.update("insert into jobs(id,kind,target_id,dedup_key,status,available_at,deadline_at) values(gen_random_uuid(),'EMBED',?,?,'PENDING',now(),now()+interval '10 minutes')",
                        id, "embed:" + id + ":0"));
        List<CompletableFuture<HttpResponse<String>>> questions = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            questions.add(Client.signIn(this, fixture.incomingNo()).askAsync(fixture.handoverId(), "question: 파일럿 일정은?"));
        }
        waitUntil("all ten AI slots busy", Duration.ofSeconds(20), () -> generationSlots.availableSlots() == 0
                && embeddingSlots.availableSlots() == 0 && chat.inFlight() == 9 && embedding.heldInFlight() == 1);
        UUID ingestJob = ingestJob(first);

        Measurement measured = measure(reader, first);

        assertEquals(0, generationSlots.availableSlots(), "generation slots were still saturated at the end");
        assertEquals(0, embeddingSlots.availableSlots(), "embedding slot was still saturated at the end");
        assertEquals(9, chat.inFlight(), "six structuring and three chat calls were still held");
        assertEquals("SUCCEEDED", jdbc.queryForObject("select status from jobs where id=?", String.class, ingestJob),
                "ingestion is not AI work and must not wait for the AI slots");
        measured.assertWithinNfr();
        assertEquals(200, readiness());

        chat.normal();
        embedding.normal();
        for (CompletableFuture<HttpResponse<String>> question : questions) question.get(40, TimeUnit.SECONDS);
    }

    @Test
    void anUnreachableModelFailsAiWorkButNotGeneralWork() throws Exception {
        chat.fail();
        embedding.fail();
        Fixture first = fixtures.getFirst();
        for (int i = 0; i < 8; i++) pendingSource(first, "outage-" + i);

        HttpResponse<String> answer = Client.signIn(this, first.incomingNo()).askAsync(first.handoverId(), "파일럿 일정은?").get(30, TimeUnit.SECONDS);
        Measurement measured = measure(reader, first);

        assertEquals(201, answer.statusCode(), answer.body());
        assertEquals("FAILED", json.readTree(answer.body()).path("answerStatus").asString());
        assertTrue(jdbc.queryForObject("select count(*) from jobs where kind='STRUCTURE' and attempts>=1", Integer.class) >= 6,
                "structuring kept trying against the failing model");
        assertEquals(0, jdbc.queryForObject("select count(*) from work_records where trust_status='AUTO_DRAFT'", Integer.class),
                "a failed model call publishes no draft");
        measured.assertWithinNfr();
        assertEquals(200, readiness(), "the model is not a readiness condition");
    }

    @Test
    void aVectorOutageFallsBackToKeywordEvidenceAndLeavesGeneralWorkAlone() throws Exception {
        embedding.fail();
        Fixture first = fixtures.getFirst();

        HttpResponse<String> answer = Client.signIn(this, first.incomingNo()).askAsync(first.handoverId(), "파일럿 출시 일정").get(30, TimeUnit.SECONDS);
        Measurement measured = measure(reader, first);

        assertEquals(201, answer.statusCode(), answer.body());
        JsonNode body = json.readTree(answer.body());
        assertEquals("ANSWERED", body.path("answerStatus").asString(), answer.body());
        assertFalse(body.path("citations").isEmpty(), "keyword evidence still grounds the answer");
        measured.assertWithinNfr();
        assertEquals(200, readiness());
    }

    @Test
    void generalApiKeepsItsConnectionsWhileWorkerQueriesHoldAllTheyCanGet() throws Exception {
        // 첫 부하 시험에서는 DB가 포화된 동안 근거 검색이 연결을 몇 초씩 쥐었고, API 요청이 같은 pool을 기다리다 시간
        // 초과됐다. 여기서는 worker 쪽 작업이 쥘 수 있는 연결을 모두 쥐고 놓지 않는데, API는 자기 pool로 목표 안에
        // 답해야 한다.
        List<java.sql.Connection> held = new ArrayList<>();
        ExecutorService hoarder = Executors.newSingleThreadExecutor();
        try {
            int taken = hoarder.submit(() -> DatabaseLane.WORKER.call(() -> {
                while (true) {
                    try {
                        held.add(dataSource.getConnection());
                    } catch (java.sql.SQLException exhausted) {
                        return held.size();
                    }
                }
            })).get(60, TimeUnit.SECONDS);

            Measurement measured = measure(reader, fixtures.getFirst());

            measured.assertWithinNfr();
            assertTrue(taken <= 8, "worker work took " + taken + " connections; README §6 sizes the worker pool at 8");
            assertEquals(200, readiness(), "readiness checks the database through the API pool");
        } finally {
            for (java.sql.Connection connection : held) connection.close();
            hoarder.shutdownNow();
        }
    }

    @Test
    void leasesAreRenewedAndSweptWhileJobWorkHoldsEveryWorkerConnection() throws Exception {
        // heartbeat와 sweep이 worker 연결 여덟 개를 작업 runner 여덟, 근거 검색과 함께 썼다. 그 연결이 모두 잡히자
        // heartbeat가 5초 동안 연결을 기다리다 실패했고, heartbeat를 세 번 놓치자 lease가 만료되어 sweep이 실행 중인
        // 작업을 다른 runner에 넘겼다.
        UUID job = ingestJob(fixtures.getFirst());
        UUID token = UUID.randomUUID();
        jdbc.update("update jobs set status='RUNNING', lease_token=?, lease_until=now()+interval '5 seconds', attempts=1 where id=?", token, job);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService runner = Executors.newSingleThreadExecutor();
        List<java.sql.Connection> held = new ArrayList<>();
        ExecutorService hoarder = Executors.newSingleThreadExecutor();
        try {
            runner.submit(() -> heartbeat.hold(new JobLeaseService.JobContext(job, 0, null, token, OffsetDateTime.now().plusMinutes(10)),
                    () -> await(release)));
            hoarder.submit(() -> DatabaseLane.WORKER.call(() -> {
                while (true) {
                    try {
                        held.add(dataSource.getConnection());
                    } catch (java.sql.SQLException exhausted) {
                        return held.size();
                    }
                }
            })).get(60, TimeUnit.SECONDS);

            long started = System.nanoTime();
            heartbeat.extend();
            housekeeping.sweep();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertTrue(elapsedMs < 2_000, "heartbeat and sweep waited " + elapsedMs + " ms for a connection");
            assertTrue(jdbc.queryForObject("select lease_until > now() + interval '20 seconds' from jobs where id=?", Boolean.class, job),
                    "the lease was not renewed");
            assertEquals("RUNNING", jdbc.queryForObject("select status::text from jobs where id=?", String.class, job));
        } finally {
            for (java.sql.Connection connection : held) connection.close();
            hoarder.shutdownNow();
            release.countDown();
            runner.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 로그인한 구성원 한 명이 일정한 속도로 일반 API 요청을 보내고 지연을 하나씩 기록한다. */
    private Measurement measure(Client client, Fixture fixture) throws Exception {
        List<Long> records = jdbc.queryForList("select id from work_records where project_id=? order by id limit 50", Long.class, fixture.projectId());
        IntFunction<HttpRequest> request = n -> switch (n % 10) {
            case 0, 1, 2, 3, 4 -> client.get("/api/v1/projects/" + fixture.projectId() + "/records?limit=20&trustStatus=CONFIRMED");
            case 5, 6 -> client.get("/api/v1/dashboard");
            case 7, 8 -> client.get("/api/v1/records/" + records.get(n % records.size()));
            default -> client.post("/api/v1/projects/" + fixture.projectId() + "/notes", "{\"body\":\"synthetic load note " + n + "\"}");
        };
        ConcurrentLinkedQueue<long[]> results = new ConcurrentLinkedQueue<>();
        List<CompletableFuture<Void>> inFlight = new ArrayList<>();
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger sent = new AtomicInteger();
        int total = (int) (WINDOW.toSeconds() * RPS);
        CountDownLatch scheduled = new CountDownLatch(total);
        ticker.scheduleAtFixedRate(() -> {
            int n = sent.getAndIncrement();
            if (n >= total) return;
            long started = System.nanoTime();
            synchronized (inFlight) {
                inFlight.add(client.http.sendAsync(request.apply(n), HttpResponse.BodyHandlers.ofString())
                        .handle((response, failure) -> {
                            results.add(new long[] {failure == null ? response.statusCode() : -1, System.nanoTime() - started});
                            return null;
                        }));
            }
            scheduled.countDown();
        }, 0, 1_000_000 / RPS, TimeUnit.MICROSECONDS);
        assertTrue(scheduled.await(WINDOW.toSeconds() + 10, TimeUnit.SECONDS), "load generator fell behind");
        ticker.shutdownNow();
        synchronized (inFlight) {
            CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        }
        return new Measurement(new ArrayList<>(results));
    }

    record Measurement(List<long[]> results) {
        void assertWithinNfr() {
            long errors = results.stream().filter(r -> r[0] < 200 || r[0] >= 300).count();
            long[] sorted = results.stream().mapToLong(r -> r[1]).sorted().toArray();
            double p95 = sorted[(int) Math.ceil(sorted.length * 0.95) - 1] / 1_000_000.0;
            System.out.printf("FailureIsolationIT requests=%d errors=%d p95=%.1fms max=%.1fms%n",
                    sorted.length, errors, p95, sorted[sorted.length - 1] / 1_000_000.0);
            assertEquals(WINDOW.toSeconds() * RPS, sorted.length);
            assertEquals(0, errors, "general API errors under AI failure");
            assertTrue(p95 < 500, "general API p95 " + p95 + "ms exceeds 500ms");
        }
    }

    private int readiness() throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health/readiness"))
                .GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private UUID ingestJob(Fixture fixture) {
        long connection = jdbc.queryForObject("""
                insert into source_connections(project_id,channel,display_name,external_ref,status,health,connector_kind)
                values(?,'MESSENGER','Isolation fixture','fixture://isolation','ACTIVE','NORMAL','FIXTURE') returning id
                """, Long.class, fixture.projectId());
        UUID id = UUID.randomUUID();
        jdbc.update("insert into jobs(id,kind,target_id,dedup_key,status,available_at,deadline_at) values(?,'INGEST',?,?,'PENDING',now(),now()+interval '10 minutes')",
                id, connection, "ingest:isolation:" + id);
        return id;
    }

    private void pendingSource(Fixture fixture, String document) {
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body)
                values(?,?,now(),?,?,'v1',repeat('f',64),'Synthetic document the model has to structure.') returning id
                """, Long.class, fixture.connectionId(), document, fixture.projectId(), document);
        jdbc.update("insert into jobs(id,kind,target_id,dedup_key,status,available_at,deadline_at) values(gen_random_uuid(),'STRUCTURE',?,?,'PENDING',now(),now()+interval '10 minutes')",
                source, "structure:" + source);
    }

    record Fixture(long projectId, long connectionId, long handoverId, String incomingNo) { }

    private Fixture project(int index, long manager, long readerId) {
        long outgoing = user("fi-outgoing-" + index);
        long incoming = user("fi-incoming-" + index);
        long project = jdbc.queryForObject("insert into projects(code,name,delivering_company,client_company,started_on,ends_on,phase,status) values(?,?,'D','C','2026-01-01','2026-12-31','Build','ON_TRACK') returning id",
                Long.class, "FI-" + index, "Failure isolation " + index);
        member(project, manager, "PROJECT_MANAGER");
        member(project, outgoing, "OUTGOING_OWNER");
        member(project, incoming, "INCOMING_OWNER");
        member(project, readerId, "MEMBER");
        long connection = jdbc.queryForObject("insert into source_connections(project_id,channel,display_name) values(?,'EMAIL','Isolation mail') returning id", Long.class, project);
        long source = jdbc.queryForObject("""
                insert into record_sources(source_connection_id,display_name,collected_at,project_id,external_document_id,external_version,content_hash,source_body,processing_status)
                values(?,'isolation.eml',now(),?,?,'v1',repeat('c',64),'synthetic body','SUCCEEDED') returning id
                """, Long.class, connection, project, "isolation-" + index);
        List<String> types = List.copyOf(new java.util.TreeSet<>(TYPES));
        for (int n = 0; n < 300; n++) {
            String type = types.get(n % types.size());
            jdbc.update("""
                    insert into work_records(code,project_id,record_source_id,record_type,title,summary,original_excerpt,occurred_on,author_name,due_on,trust_status,confirmed_by,confirmed_at,source_version,extraction_index,search_text)
                    values(?,?,?,cast(? as record_type),?,?,'synthetic excerpt',?,'Synthetic author',?,'CONFIRMED',?,now(),'v1',?,?)
                    """, "REC-FI-" + index + "-" + n, project, source, type, "파일럿 기록 " + n, "파일럿 출시 일정 합성 요약 " + n,
                    LocalDate.of(2026, 8, 1).plusDays(n % 40), type.equals("SCHEDULE") ? LocalDate.of(2026, 10, 1) : null, manager, n,
                    "파일럿 출시 일정 합성 요약 " + n);
        }
        long handover = handovers.request(manager, project, new HandoverRequestService.Command(outgoing, incoming, LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14), TYPES, true)).id();
        return new Fixture(project, connection, handover, "fi-incoming-" + index);
    }

    private long user(String employeeNo) {
        return jdbc.queryForObject("insert into users(organization_id,employee_no,name,title,email,credential_hash) values((select id from organizations),?,?,'Engineer',?,?) returning id",
                Long.class, employeeNo, employeeNo, employeeNo + "@test", PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
    }

    private void member(long project, long user, String role) {
        jdbc.update("insert into project_members(project_id,user_id,project_role,joined_on) values(?,?,cast(? as project_role),date '2026-01-01')", project, user, role);
    }

    private static void waitUntil(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(50);
        }
    }

    /** 로그인한 브라우저 같은 client. 쿠키 세션, 쓰기에는 CSRF 헤더, 명령에는 Idempotency-Key. */
    static final class Client {
        final HttpClient http;
        private final String base;
        private final String csrf;

        private Client(HttpClient http, String base, String csrf) {
            this.http = http;
            this.base = base;
            this.csrf = csrf;
        }

        static Client signIn(FailureIsolationIT test, String employeeNo) throws Exception {
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://127.0.0.1:" + test.port;
            String token = test.token(http, base);
            HttpResponse<String> signIn = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/sign-in"))
                    .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, signIn.statusCode(), signIn.body());
            return new Client(http, base, test.token(http, base));
        }

        HttpRequest get(String path) {
            return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).GET().build();
        }

        HttpRequest post(String path, String body) {
            return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        }

        CompletableFuture<HttpResponse<String>> askAsync(long handoverId, String question) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/handovers/" + handoverId + "/questions"))
                    .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", csrf).header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question + "\"}")).build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private String token(HttpClient http, String base) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body()).path("token").asString();
    }

    @TestConfiguration
    static class Models {
        @Bean FaultyChatModel chatModel() { return new FaultyChatModel(); }
        @Bean FaultyEmbeddingModel embeddingModel() { return new FaultyEmbeddingModel(); }
    }

    /** 호출마다 답하거나, 풀어 줄 때까지(느린 모델의 경우로 최대 30초) 붙잡거나, 장애처럼 실패한다. */
    static class FaultyChatModel implements ChatModel {
        private volatile CountDownLatch held = new CountDownLatch(0);
        private volatile boolean failing;
        private final AtomicInteger inFlight = new AtomicInteger();

        void normal() { failing = false; held.countDown(); held = new CountDownLatch(0); }
        void hold() { failing = false; held = new CountDownLatch(1); }
        void fail() { normal(); failing = true; }
        int inFlight() { return inFlight.get(); }

        @Override
        public ChatResponse call(Prompt prompt) {
            inFlight.incrementAndGet();
            try {
                if (failing) throw new IllegalStateException("synthetic provider outage");
                if (!held.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic slow model gave up");
                if (failing) throw new IllegalStateException("synthetic provider outage");
                // 근거 있는 모델처럼 첫 근거 블록을 인용한다. 구조화는 이 모양을 거절한다.
                return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"answer\":\"파일럿 일정은 기록을 확인하세요.\",\"citations\":[\"E1\"]}"))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    /**
     * 오프라인 stub 같은 해시 벡터. {@link #holdIndexing()}은 EMBED 작업의 호출만 붙잡아서, 임베딩 slot이 바쁜
     * 동안에도 질문 자신의 쿼리 임베딩(글이 "question:"으로 시작)은 지나가고 질문은 채팅 slot에 닿는다.
     */
    static class FaultyEmbeddingModel implements EmbeddingModel {
        private volatile CountDownLatch held = new CountDownLatch(0);
        private volatile boolean failing;
        private final AtomicInteger heldInFlight = new AtomicInteger();

        void normal() { failing = false; held.countDown(); held = new CountDownLatch(0); }
        void holdIndexing() { failing = false; held = new CountDownLatch(1); }
        void fail() { normal(); failing = true; }
        int heldInFlight() { return heldInFlight.get(); }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (failing) throw new IllegalStateException("synthetic vector outage");
            boolean indexing = request.getInstructions().stream().noneMatch(text -> text.startsWith("question:"));
            if (indexing && held.getCount() > 0) {
                heldInFlight.incrementAndGet();
                try {
                    held.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    heldInFlight.decrementAndGet();
                }
            }
            List<Embedding> results = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                results.add(new Embedding(StubEmbeddingClient.vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(results);
        }

        @Override public float[] embed(Document document) { return call(new EmbeddingRequest(List.of(document.getText()), null)).getResult().getOutput(); }
        @Override public int dimensions() { return StubEmbeddingClient.DIMENSIONS; }
    }
}
