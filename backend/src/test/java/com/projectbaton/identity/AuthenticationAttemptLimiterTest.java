package com.projectbaton.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

/**
 * limiter는 (사번, 주소)마다 실패를 5분 동안 센다. 호출자는 사번을 마음대로 지어낼 수 있어서, 다시 묻지 않는 항목이
 * 쌓이면 안 된다.
 */
class AuthenticationAttemptLimiterTest {
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-05T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void expiredEntriesOfKeysNobodyAsksAboutAgainAreDropped() {
        MovableClock clock = new MovableClock();
        AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock);
        for (int i = 0; i < AuthenticationAttemptLimiter.SWEEP_THRESHOLD + 1; i++) {
            limiter.recordFailure("invented-" + i, "192.0.2.1");
        }
        assertEquals(AuthenticationAttemptLimiter.SWEEP_THRESHOLD + 1, limiter.trackedKeys());

        clock.advance(Duration.ofMinutes(6));
        limiter.recordFailure("one-more", "192.0.2.1");

        assertEquals(1, limiter.trackedKeys());
    }

    /**
     * 예전에는 기준을 넘은 뒤 실패할 때마다 모든 키를 훑어서, 살아 있는 키가 몰려들면 실패 한 번의 비용이 테이블
     * 전체만큼 들었다. 이제 훑기는 간격마다 최대 한 번이다.
     */
    @Test
    void theTableIsWalkedAtMostOncePerIntervalWhileKeysStayLive() {
        MovableClock clock = new MovableClock();
        AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock);
        for (int i = 0; i < AuthenticationAttemptLimiter.SWEEP_THRESHOLD + 1; i++) {
            limiter.recordFailure("live-" + i, "192.0.2.1");
        }
        limiter.recordFailure("first-past-the-threshold", "192.0.2.1");
        int afterTheFlood = limiter.sweepCount();
        assertEquals(1, afterTheFlood, "the first failure past the threshold walks the table once");
        for (int i = 0; i < 500; i++) {
            limiter.recordFailure("more-" + i, "192.0.2.1");
        }

        assertEquals(afterTheFlood, limiter.sweepCount(), "no further walk within the interval");

        clock.advance(AuthenticationAttemptLimiter.SWEEP_INTERVAL.plusSeconds(1));
        limiter.recordFailure("later", "192.0.2.1");

        assertEquals(afterTheFlood + 1, limiter.sweepCount(), "one walk once the interval has passed");
    }

    /**
     * 항목을 빈 채로 만들고 두 번째 단계에서 채워서, 그 사이에 훑기가 돌면 항목이 지워지고 실패가 세어지지 않았다.
     * 여러 스레드에서 같은 키의 실패를 세어도 언제나 한도에 닿아야 한다.
     */
    @Test
    void failuresOnOneKeyAreAllCountedWhileTheTableIsBeingCleaned() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(5);
        try {
            for (int round = 0; round < 100; round++) {
                MovableClock clock = new MovableClock();
                AuthenticationAttemptLimiter limiter = new AuthenticationAttemptLimiter(clock);
                for (int i = 0; i < AuthenticationAttemptLimiter.SWEEP_THRESHOLD + 1; i++) {
                    limiter.recordFailure("expired-" + i, "192.0.2.1");
                }
                clock.advance(Duration.ofMinutes(6));
                CountDownLatch together = new CountDownLatch(5);
                java.util.List<Future<?>> done = new java.util.ArrayList<>();
                for (int thread = 0; thread < 5; thread++) {
                    done.add(threads.submit(() -> {
                        together.countDown();
                        together.await();
                        limiter.recordFailure("racing", "192.0.2.2");
                        return null;
                    }));
                }
                for (Future<?> future : done) {
                    future.get();
                }

                assertThrows(AuthenticationAttemptLimiter.AuthenticationRateLimitedException.class,
                        () -> limiter.requireAllowed("racing", "192.0.2.2"), "round " + round + " lost a failure");
            }
        } finally {
            threads.shutdownNow();
        }
    }
}
