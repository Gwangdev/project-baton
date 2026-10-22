package com.projectbaton.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

@Component
public class AuthenticationAttemptLimiter {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    /**
     * 키는 다시 물어볼 때만 정리되고, 호출자는 인증 없는 로그인과 설정 경로에서 사번을 끝없이 지어낼 수 있다. 그래서
     * 키가 이만큼 쌓이면 만료된 키를 쓸어 낸다.
     */
    static final int SWEEP_THRESHOLD = 10_000;
    /**
     * sweep은 모든 키를 훑는다. 예전에는 기준을 넘은 뒤 실패할 때마다 sweep을 시작해서, 살아 있는 키가 몰려들면 실패
     * 한 번의 비용이 테이블 전체만큼 들었다. 이제는 간격마다 최대 한 번만 돌고 간격이 window보다 훨씬 짧아서, 만료된
     * 키는 만료 뒤 한 간격 안에 사라진다.
     */
    static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);

    private final ConcurrentHashMap<AttemptKey, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;
    private final AtomicReference<Instant> lastSweep = new AtomicReference<>(Instant.MIN);
    private final AtomicInteger sweeps = new AtomicInteger();

    public AuthenticationAttemptLimiter() {
        this(Clock.systemUTC());
    }

    AuthenticationAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 테이블을 훑은 횟수. 훑기가 간격마다 한 번으로 제한되는지 보이려고 시험만 읽는다. */
    int sweepCount() {
        return sweeps.get();
    }

    /** 쥐고 있는 키 수. 만료된 키가 빠지는지 보이려고 시험만 읽는다. */
    int trackedKeys() {
        return failures.size();
    }

    /**
     * 확인과 뒤이은 {@link #recordFailure}가 따로 도는 연산이라서, 함께 도착한 요청이 각자 확인을 통과할 수 있다.
     * 몰려든 요청은 다섯 번이 아니라 동시에 처리 중인 요청 수만큼(웹 서버 스레드 한도까지, 기본 200) 시도할 수 있다.
     * 몰린 시도가 기록되면 키가 5분 잠기고, 시도마다 비밀번호 해시 비용이 들고, 비밀번호는 12자 이상이고, 한도는
     * 지속적인 추측만 막으면 되므로 한 window 동안은 이를 받아들인다. 확인할 때 시도를 예약하고 성공하면 돌려주면 이
     * 틈을 닫을 수 있다.
     */
    void requireAllowed(String employeeNo, String remoteAddress) {
        AttemptKey key = new AttemptKey(employeeNo, remoteAddress);
        boolean[] limited = {false};
        // 키마다 읽기와 변경을 map 연산 하나 안에서 해서, 동시에 도는 실패 기록이나 sweep이 반쯤 바뀐 키를 보지
        // 않는다.
        failures.computeIfPresent(key, (ignored, attempts) -> {
            discardExpired(attempts, clock.instant());
            limited[0] = attempts.size() >= MAX_FAILURES;
            return attempts.isEmpty() ? null : attempts;
        });
        if (limited[0]) {
            throw new AuthenticationRateLimitedException();
        }
    }

    void recordFailure(String employeeNo, String remoteAddress) {
        Instant now = clock.instant();
        sweepIfDue(now);
        // 예전에는 항목을 빈 채로 만든 뒤 두 번째 단계에서 채워서, 그 사이에 sweep이 돌면 항목이 지워지고 실패가
        // 세어지지 않았다. 한 연산에서 만들고 채우면 그런 틈이 없다.
        failures.compute(new AttemptKey(employeeNo, remoteAddress), (ignored, attempts) -> {
            Deque<Instant> kept = attempts == null ? new ArrayDeque<>() : attempts;
            discardExpired(kept, now);
            kept.addLast(now);
            return kept;
        });
    }

    void recordSuccess(String employeeNo, String remoteAddress) {
        failures.remove(new AttemptKey(employeeNo, remoteAddress));
    }

    private void sweepIfDue(Instant now) {
        if (failures.size() <= SWEEP_THRESHOLD) {
            return;
        }
        Instant previous = lastSweep.get();
        if (previous.plus(SWEEP_INTERVAL).isAfter(now) || !lastSweep.compareAndSet(previous, now)) {
            return;
        }
        sweeps.incrementAndGet();
        Instant threshold = now.minus(WINDOW);
        for (AttemptKey key : failures.keySet()) {
            failures.computeIfPresent(key, (ignored, attempts) -> attempts.isEmpty() || !attempts.peekLast().isAfter(threshold) ? null : attempts);
        }
    }

    private void discardExpired(Deque<Instant> attempts, Instant now) {
        Instant threshold = now.minus(WINDOW);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(threshold)) {
            attempts.removeFirst();
        }
    }

    private record AttemptKey(String employeeNo, String remoteAddress) {
    }

    public static class AuthenticationRateLimitedException extends RuntimeException {
    }
}
