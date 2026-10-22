package com.projectbaton.jobs;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.projectbaton.shared.ApiProblemWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 작업 한 부류의 고정된 실행 slot 수(README §6: 구조화와 패키지가 6개를 함께 쓰고 임베딩은 1개). lane은 호출한
 * 스레드에서 자기 종류의 작업 하나를 가져와 실행하고 찾았는지 알린다. lane이 가져오기 전에 slot을 먼저 잡으므로 바로
 * 시작할 수 없는 작업은 가져오지 않는다.
 *
 * <p>예전에는 worker가 평범한 {@code @Scheduled} 메서드였고 Spring의 scheduler는 기본 스레드가 하나라서, 프로세스의
 * 모든 worker가 한 번에 작업 하나만 돌렸고 느린 모델 호출 하나가 임베딩, 수집, Q&A 복구를 함께 멈췄다. slot은 작업
 * 부류마다 따로 스레드를 준다.
 *
 * <p>runner는 작업이 있는 동안 계속 가져가되 회차마다 다음 lane부터 시작해서, 구조화 작업이 밀려도 공유 slot의 패키지
 * 생성이 굶지 않는다. 큐가 비어 있으면 tick마다 runner 하나만 확인한다.
 */
public class JobSlots implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(JobSlots.class);

    private final String name;
    private final List<BooleanSupplier> lanes;
    private final Semaphore free;
    private final ThreadPoolTaskExecutor runners;
    private final AtomicInteger nextLane = new AtomicInteger();
    private final AtomicBoolean idle = new AtomicBoolean();
    private final Duration drain;
    // fill()과 destroy()의 시작이 이 잠금을 쥐어서, dispatch는 종료 전에 runner를 executor에 다 넘기거나 멈춤을 보고
    // 하나도 넘기지 않는다. 잠금이 없을 때는 멈춤 확인을 이미 지난 dispatch가 멈춘 executor에 runner를 넘겼고,
    // executor가 거절한 뒤에도 그 runner 몫의 slot이 잡힌 채 남았다.
    private final Object lifecycle = new Object();
    private volatile boolean stopping;

    public JobSlots(String name, int slots, List<BooleanSupplier> lanes, Duration drain) {
        this.name = name;
        this.drain = drain;
        this.lanes = List.copyOf(lanes);
        this.free = new Semaphore(slots);
        this.runners = new ThreadPoolTaskExecutor();
        runners.setThreadNamePrefix("jobs-" + name + "-");
        runners.setCorePoolSize(slots);
        runners.setMaxPoolSize(slots);
        runners.setQueueCapacity(slots);
        runners.initialize();
    }

    // 첫 dispatch도 한 번 기다린다. 기다리지 않으면 context가 뜨자마자 모든 pool이 runner를 띄워 그 순간 앱(또는 시험
    // 준비)이 하는 일과 경쟁한다. 시험은 지연을 한 시간으로 두고 dispatch를 직접 부른다.
    @Scheduled(fixedDelayString = "${baton.jobs.dispatch-delay:1000}", initialDelayString = "${baton.jobs.dispatch-delay:1000}")
    public void fill() {
        synchronized (lifecycle) {
            if (stopping) return;
            int started = 0;
            while ((!idle.get() || started == 0) && free.tryAcquire()) {
                runners.execute(this::run);
                started++;
            }
        }
    }

    /**
     * 지금 runner가 쥐지 않은 slot 수. lane이 가져오기 전에 slot을 잡으므로 이 값으로 분기하는 코드는 없다. README
     * §8이 말하는 포화 신호인 {@code baton.job.slots.available} gauge(JobSlotConfiguration)의 값이고, pool이 포화되는
     * 것을 봐야 하는 시험도 읽는다(FailureIsolationIT는 AI slot을 모두 쥐고, IngestionWorkerIT와 JobSlotsTest는
     * pool이 비기를 기다린다).
     */
    public int availableSlots() {
        return free.availablePermits();
    }

    private void run() {
        try {
            DatabaseLane.WORKER.run(() -> {
                while (step()) idle.set(false);
                idle.set(true);
            });
        } catch (RuntimeException failure) {
            // heartbeat 없이 작업이 RUNNING으로 남으므로 lease 복구가 다음 시도에 넘긴다.
            log.error("Unhandled {} in {} job slot", ApiProblemWriter.origin(failure), name);
        } finally {
            free.release();
        }
    }

    private boolean step() {
        if (stopping) return false;
        int start = Math.floorMod(nextLane.getAndIncrement(), lanes.size());
        for (int offset = 0; offset < lanes.size(); offset++) {
            if (lanes.get((start + offset) % lanes.size()).getAsBoolean()) return true;
        }
        return false;
    }

    /**
     * 작업을 더 가져가지 않고, 실행 중인 작업에는 drain 기간만큼 마칠 시간을 준다. executor의 기본 종료처럼 바로
     * interrupt하면 작업이 lease 만료까지 RUNNING으로 남고, 재시작할 때마다 세 번의 시도 가운데 하나를 썼다. drain
     * 뒤에도 돌고 있는 작업은 전처럼 interrupt하고 lease로 복구한다(README §6).
     */
    @Override
    public void destroy() {
        ThreadPoolExecutor executor = runners.getThreadPoolExecutor();
        synchronized (lifecycle) {
            stopping = true;
            executor.shutdown();
        }
        try {
            if (!executor.awaitTermination(drain.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("{} job slots still busy after {}; interrupting", name, drain);
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
