package com.projectbaton.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class IdempotencyService {

    private static final int RETENTION_BATCH = 5_000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Response execute(Request request, Supplier<Response> command) {
        request.authorize().run();
        String requestHash = requestHash(request.canonicalJsonBody());
        int inserted;
        try {
            inserted = jdbcTemplate.update("""
                    insert into idempotency_requests (actor_id, method, path, key, request_hash, state, expires_at)
                    values (?, ?, ?, ?, ?, 'IN_PROGRESS', ?)
                    on conflict (actor_id, method, path, key) do nothing
                    """, request.actorId(), request.method(), request.path(), request.key(), requestHash,
                    OffsetDateTime.now().plus(Duration.ofHours(24)));
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException("Unable to reserve idempotency key", exception);
        }
        if (inserted == 0) {
            StoredRequest stored = jdbcTemplate.queryForObject("""
                    select request_hash, state, target_id, http_status, response_json::text
                    from idempotency_requests
                    where actor_id = ? and method = ? and path = ? and key = ?
                    """, (resultSet, rowNum) -> new StoredRequest(
                    resultSet.getString("request_hash"), resultSet.getString("state"),
                    resultSet.getObject("target_id", Long.class), resultSet.getObject("http_status", Integer.class),
                    resultSet.getString("response_json")), request.actorId(), request.method(), request.path(), request.key());
            if (!stored.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException();
            }
            if (!"COMPLETED".equals(stored.state())) {
                throw new RequestInProgressException();
            }
            return new Response(stored.httpStatus(), canonicalJson(stored.responseJson()), stored.targetId(), true);
        }

        Response response = command.get();
        Response canonicalResponse = new Response(response.status(), canonicalJson(response.responseJson()),
                response.targetId(), false);
        jdbcTemplate.update("""
                update idempotency_requests
                set state = 'COMPLETED', target_id = ?, http_status = ?, response_json = cast(? as jsonb)
                where actor_id = ? and method = ? and path = ? and key = ?
                """, canonicalResponse.targetId(), canonicalResponse.status(), canonicalResponse.responseJson(), request.actorId(), request.method(),
                request.path(), request.key());
        return canonicalResponse;
    }

    /**
     * C01이 Q&A에 정한 두 단계 흐름의 앞 단계. 호출자의 접수 트랜잭션 안에서 키를 잡고, 끝난 재실행에는 저장된 결과를
     * 돌려준다. 호출자는 업무 행과 IN_PROGRESS 행을 함께 커밋하고 나중에 {@link #completeTarget}으로 끝낸다. 그 커밋
     * 전에 실패하면 키는 쓰이지 않은 채 남는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation reserve(Request request) {
        request.authorize().run();
        String requestHash = requestHash(request.canonicalJsonBody());
        int inserted = jdbcTemplate.update("""
                insert into idempotency_requests (actor_id, method, path, key, request_hash, state, expires_at)
                values (?, ?, ?, ?, ?, 'IN_PROGRESS', ?)
                on conflict (actor_id, method, path, key) do nothing
                """, request.actorId(), request.method(), request.path(), request.key(), requestHash,
                OffsetDateTime.now().plus(Duration.ofHours(24)));
        if (inserted == 1) return new Reservation(null);
        StoredRequest stored = jdbcTemplate.queryForObject("""
                select request_hash, state, target_id, http_status, response_json::text
                from idempotency_requests
                where actor_id = ? and method = ? and path = ? and key = ?
                """, (resultSet, rowNum) -> new StoredRequest(
                resultSet.getString("request_hash"), resultSet.getString("state"),
                resultSet.getObject("target_id", Long.class), resultSet.getObject("http_status", Integer.class),
                resultSet.getString("response_json")), request.actorId(), request.method(), request.path(), request.key());
        if (!stored.requestHash().equals(requestHash)) throw new IdempotencyConflictException();
        if (!"COMPLETED".equals(stored.state())) throw new RequestInProgressException();
        return new Reservation(new Response(stored.httpStatus(), canonicalJson(stored.responseJson()), stored.targetId(), true));
    }

    /** 잡아 둔 키가 만든 업무 행을, 그 행을 만든 트랜잭션 안에서 기록한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachTarget(long actorId, String method, String path, UUID key, long targetId) {
        jdbcTemplate.update("""
                update idempotency_requests set target_id = ?
                where actor_id = ? and method = ? and path = ? and key = ? and state = 'IN_PROGRESS'
                """, targetId, actorId, method, path, key);
    }

    /** 뒤 단계로, 최종 결과를 한 번 저장한다. 그 대상의 키가 더는 IN_PROGRESS가 아니면 false를 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completeTarget(long actorId, String method, String path, long targetId, int status, String responseJson) {
        return jdbcTemplate.update("""
                update idempotency_requests set state = 'COMPLETED', http_status = ?, response_json = cast(? as jsonb)
                where actor_id = ? and method = ? and path = ? and target_id = ? and state = 'IN_PROGRESS'
                """, status, canonicalJson(responseJson), actorId, method, path, targetId) == 1;
    }

    private String requestHash(String json) {
        try {
            String canonicalJson = canonicalJson(json);
            return hex(MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String canonicalJson(String json) {
        try {
            return canonicalize(objectMapper.readTree(json)).toString();
        } catch (JacksonException exception) {
            throw new InvalidIdempotencyBodyException();
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            node.properties().stream().sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .forEach(entry -> canonical.set(entry.getKey(), canonicalize(entry.getValue())));
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            node.forEach(value -> canonical.add(canonicalize(value)));
            return canonical;
        }
        return node;
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    public record Request(long actorId, String method, String path, UUID key, String canonicalJsonBody, Runnable authorize) {
    }

    public record Response(int status, String responseJson, Long targetId, boolean replayed) {
    }

    /** 새로 잡은 키면 {@code replay}는 null이다. */
    public record Reservation(Response replay) {
    }

    private record StoredRequest(String requestHash, String state, Long targetId, Integer httpStatus,
                                 String responseJson) {
    }

    public static class IdempotencyConflictException extends RuntimeException {
    }

    public static class RequestInProgressException extends RuntimeException {
    }

    public static class InvalidIdempotencyBodyException extends RuntimeException {
    }

    /**
     * 24시간 보존(C01)이 지난 완료 결과를 묶음 단위로 지운다. 진행 중인 행은 아직 끝나지 않은 요청이나 Q&A 복구의
     * 것이라 오래돼도 남긴다.
     */
    public void deleteExpired() {
        int deleted;
        do {
            deleted = jdbcTemplate.update("""
                    delete from idempotency_requests where id in (
                        select id from idempotency_requests where state = 'COMPLETED' and expires_at < now() limit ?)
                    """, RETENTION_BATCH);
        } while (deleted == RETENTION_BATCH);
    }
}
