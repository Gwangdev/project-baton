package com.projectbaton.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobSlotsTest {
    private static final Duration DRAIN = Duration.ofSeconds(5);
    private JobSlots slots;

    @AfterEach
    void stop() {
        if (slots != null) slots.destroy();
    }

    @Test
    void runsAtMostTheConfiguredNumberOfJobsAtOnce() throws Exception {
        AtomicInteger queued = new AtomicInteger(10);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        BooleanSupplier step = () -> {
            if (queued.getAndDecrement() <= 0) return false;
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            await(release);
            running.decrementAndGet();
            done.incrementAndGet();
            return true;
        };
        slots = new JobSlots("test", 6, List.of(step), DRAIN);

        slots.fill();
        waitUntil(() -> running.get() == 6);
        slots.fill();
        slots.fill();
        assertEquals(6, running.get());
        assertEquals(0, slots.availableSlots());

        release.countDown();
        waitUntil(() -> done.get() == 10 && slots.availableSlots() == 6);
        assertEquals(6, peak.get());
    }

    @Test
    void sharedSlotsAlternateBetweenKindsSoABacklogCannotStarveTheOther() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger structuring = new AtomicInteger(50);
        AtomicInteger packages = new AtomicInteger(2);
        BooleanSupplier structure = () -> structuring.getAndDecrement() > 0 && order.add("S");
        BooleanSupplier buildPackage = () -> packages.getAndDecrement() > 0 && order.add("P");
        slots = new JobSlots("test", 1, List.of(structure, buildPackage), DRAIN);

        slots.fill();
        waitUntil(() -> order.size() == 52 && slots.availableSlots() == 1);
        assertEquals(List.of("P", "P"), order.subList(0, 4).stream().filter("P"::equals).toList(),
                "both package jobs run within the first four jobs: " + order);
    }

    @Test
    void aFailingJobReleasesItsSlot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BooleanSupplier step = () -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("handler bug");
            return false;
        };
        slots = new JobSlots("test", 1, List.of(step), DRAIN);

        slots.fill();
        waitUntil(() -> calls.get() == 1 && slots.availableSlots() == 1);
        slots.fill();
        waitUntil(() -> calls.get() == 2 && slots.availableSlots() == 1);
    }

    @Test
    void anIdleQueueIsPolledByOneRunnerInsteadOfEverySlot() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        slots = new JobSlots("test", 6, List.of(() -> {
            polls.incrementAndGet();
            return false;
        }), DRAIN);

        slots.fill();
        waitUntil(() -> polls.get() >= 1 && slots.availableSlots() == 6);
        int afterFirstTick = polls.get();
        assertTrue(afterFirstTick <= 6);

        slots.fill();
        waitUntil(() -> polls.get() == afterFirstTick + 1 && slots.availableSlots() == 6);
        Thread.sleep(100);
        assertEquals(afterFirstTick + 1, polls.get());
    }

    @Test
    void shutdownLetsARunningJobFinishAndStartsNoOther() throws Exception {
        // 재시작할 때마다 작업을 interrupt하면 lease 만료를 기다리게 되고 세 번의 시도 가운데 하나를 썼다.
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        slots = new JobSlots("test", 1, List.of(() -> {
            calls.incrementAndGet();
            started.countDown();
            await(release);
            finished.incrementAndGet();
            return true;
        }), DRAIN);
        slots.fill();
        assertTrue(started.await(10, TimeUnit.SECONDS));

        Thread stopping = new Thread(slots::destroy);
        stopping.start();
        Thread.sleep(200);
        assertTrue(stopping.isAlive(), "shutdown returned while a job was still running");
        slots.fill();
        release.countDown();
        stopping.join(10_000);

        assertFalse(stopping.isAlive());
        assertEquals(1, finished.get(), "the running job completed instead of being interrupted");
        assertEquals(1, calls.get(), "no job was claimed after shutdown began");
    }

    @Test
    void shutdownInterruptsAJobThatOutlastsTheDrain() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        slots = new JobSlots("test", 1, List.of(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException expected) {
                interrupted.incrementAndGet();
            }
            return false;
        }), Duration.ofMillis(300));
        slots.fill();
        assertTrue(started.await(10, TimeUnit.SECONDS));

        long begun = System.nanoTime();
        slots.destroy();

        assertTrue(Duration.ofNanos(System.nanoTime() - begun).compareTo(Duration.ofSeconds(5)) < 0, "shutdown waited past its drain");
        waitUntil(() -> interrupted.get() == 1);
    }

    @Test
    void aDispatchRacingShutdownNeitherFailsNorKeepsASlot() throws Exception {
        // destroy()가 runner를 끌 때 scheduler가 fill() 안에 있을 수 있다. 멈춘 executor에 넘긴 작업이 예외와 함께
        // 거절됐고, 그 작업 몫으로 잡은 slot은 돌아오지 않았다.
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (int round = 0; round < 300; round++) {
            JobSlots racing = new JobSlots("race", 16, List.of(() -> false), Duration.ofSeconds(1));
            CountDownLatch dispatching = new CountDownLatch(1);
            Thread dispatcher = new Thread(() -> {
                dispatching.countDown();
                try {
                    for (int i = 0; i < 2_000; i++) racing.fill();
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
            dispatcher.start();
            await(dispatching);
            racing.destroy();
            dispatcher.join(10_000);
            assertEquals(16, racing.availableSlots(), "slots after shutdown in round " + round);
        }
        assertTrue(failures.isEmpty(), "dispatch failed during shutdown: " + failures.stream().limit(3).toList());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not reached in 10 seconds");
            Thread.sleep(10);
        }
    }
}
