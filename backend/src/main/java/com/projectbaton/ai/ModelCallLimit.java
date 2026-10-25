package com.projectbaton.ai;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 모델 호출 하나에 거는 시간 상한({@code baton.ai.call-timeout}). 공급자 SDK마다 시간 설정이 다르고 Ollama 클라이언트는
 * 읽기 시간 상한이 없어서, 답하지 않는 공급자가 작업 slot을 끝없이 쥘 수 있었다. 그래서 호출을 가상 스레드에서 돌리고
 * 상한이 지나면 interrupt한 뒤 호출한 쪽에 실패를 돌려준다. 동시 호출 수는 부르는 쪽(작업 slot, Q&A 스레드)이 이미
 * 제한한다.
 */
public final class ModelCallLimit implements AutoCloseable {
    private final Duration limit;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    public ModelCallLimit(Duration limit) {
        if (limit == null || limit.isNegative() || limit.isZero()) {
            throw new IllegalStateException("baton.ai.call-timeout must be a positive duration");
        }
        this.limit = limit;
    }

    public Duration limit() {
        return limit;
    }

    /** 상한 안에 끝난 호출의 결과를 돌려주고, 넘었거나 실패했으면 {@link CallFailedException}을 던진다. */
    public <T> T call(Callable<T> call) {
        Future<T> running = calls.submit(call);
        try {
            return running.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            running.cancel(true);
            throw new CallFailedException("the model call did not finish within " + limit, timeout);
        } catch (ExecutionException failure) {
            throw new CallFailedException("the model call failed", failure.getCause());
        } catch (InterruptedException interrupted) {
            running.cancel(true);
            Thread.currentThread().interrupt();
            throw new CallFailedException("interrupted while waiting for the model", interrupted);
        }
    }

    @Override
    public void close() {
        calls.shutdownNow();
    }

    public static final class CallFailedException extends RuntimeException {
        public CallFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
