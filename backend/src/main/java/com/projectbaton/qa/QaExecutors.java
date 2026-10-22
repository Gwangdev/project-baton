package com.projectbaton.qa;

import java.util.concurrent.Executor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Q&A 작업용 스레드. {@link Executor} bean으로 내보내면 Spring Boot의 기본 앱 작업 executor가 꺼져서 여기에 쥐고
 * 있는다. README §6은 동시 채팅 호출을 세 개로 제한한다.
 */
@Component
public class QaExecutors implements DisposableBean {
    private final ThreadPoolTaskExecutor model = pool("qa-model-", 3, 3, 50);
    private final ThreadPoolTaskExecutor stream = pool("qa-stream-", 4, 16, 100);

    /** 시간이 초과된 호출을 interrupt로 취소할 수 있게 {@link AsyncTaskExecutor}로 돌려준다. */
    public AsyncTaskExecutor model() { return model; }
    public Executor stream() { return stream; }

    @Override
    public void destroy() {
        model.shutdown();
        stream.shutdown();
    }

    private static ThreadPoolTaskExecutor pool(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.initialize();
        return executor;
    }
}
