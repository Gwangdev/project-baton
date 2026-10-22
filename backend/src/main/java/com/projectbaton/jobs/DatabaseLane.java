package com.projectbaton.jobs;

import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 현재 스레드의 DB 작업이 쓰는 연결 pool(README §6, config/DatabasePools).
 * <ul>
 * <li>{@code API}: 요청 처리. 기본값이다.</li>
 * <li>{@code WORKER}: AI와 작업 처리(작업 slot의 runner, 근거 검색). 첫 부하 시험에서는 이 작업이 API pool을 함께
 * 써서, DB가 느려지자 연결을 5~9초씩 쥐었고 그 연결을 기다리던 API 요청이 실패했다.</li>
 * <li>{@code CONTROL}: 작업을 살려 두고 정리하는 예약 작업(lease heartbeat, sweep, Q&A 복구, 보존, 수집 일정). runner
 * 여덟과 worker pool을 함께 쓰던 때에는 작업이 worker 연결을 모두 쥐면 heartbeat가 연결 대기 시간을 넘겼고,
 * heartbeat를 놓친 실행 중 작업의 lease가 만료되어 sweep이 그 작업을 다른 runner에 넘겼다.</li>
 * </ul>
 *
 * <p>연결은 트랜잭션이나 단일 문장이 시작될 때 정해지므로 lane은 그보다 먼저 정해야 한다. 열린 트랜잭션 안에서 lane을
 * 바꾸면 그 트랜잭션이 쥔 연결을 조용히 계속 쓰게 되어서, 그 경우는 실패로 처리한다.
 */
public enum DatabaseLane {
    API, WORKER, CONTROL;

    private static final ThreadLocal<DatabaseLane> CURRENT = ThreadLocal.withInitial(() -> API);

    public static DatabaseLane current() {
        return CURRENT.get();
    }

    public void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    public <T> T call(Supplier<T> work) {
        DatabaseLane previous = CURRENT.get();
        if (previous == this) return work.get();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(this + " database work must start outside a transaction");
        }
        CURRENT.set(this);
        try {
            return work.get();
        } finally {
            if (previous == API) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
