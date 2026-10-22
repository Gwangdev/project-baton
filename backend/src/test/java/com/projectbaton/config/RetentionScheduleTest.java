package com.projectbaton.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.projectbaton.audit.AuditService;
import com.projectbaton.identity.CredentialService;
import com.projectbaton.jobs.JobRepository;
import com.projectbaton.shared.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 보존 단계는 서로 독립이다. 계속 실패하는 단계가 뒤의 단계를 굶겨서는 안 되고, 실패가 조용히 지나가지 않도록 단계
 * 이름으로 로그를 남겨야 한다.
 */
class RetentionScheduleTest {
    private final JobRepository jobs = mock(JobRepository.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AuditService audit = mock(AuditService.class);

    @Test
    void aStepThatFailsDoesNotStopTheStepsAfterItAndIsLoggedByName() {
        doThrow(new IllegalStateException("idempotency table unavailable")).when(idempotency).deleteExpired();
        Logger logger = (Logger) LoggerFactory.getLogger(RetentionSchedule.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new RetentionSchedule(jobs, idempotency, credentials, audit, true, true, 24).run();
        } finally {
            logger.detachAppender(appender);
        }

        verify(credentials).deleteExpired();
        verify(jobs).deleteFinished();
        verify(audit).clearSignInAddresses(24);
        assertEquals(1, appender.list.stream().filter(event -> event.getLevel() == Level.ERROR
                && event.getFormattedMessage().contains("idempotency")).count());
    }

    @Test
    void aFailureInTheLastStepStillRunsEveryEarlierOne() {
        doThrow(new IllegalStateException("audit table unavailable")).when(audit).clearSignInAddresses(24);

        new RetentionSchedule(jobs, idempotency, credentials, audit, true, true, 24).run();

        verify(idempotency).deleteExpired();
        verify(credentials).deleteExpired();
        verify(jobs).deleteFinished();
    }

    /** 기간이 0 이하이면 모든 주소를 한꺼번에 비우게 되므로, 그런 값은 대신 기동을 멈춘다. */
    @Test
    void anAuditWindowBelowOneMonthStopsTheStartWhereTheSwitchIsOn() {
        assertThrows(IllegalArgumentException.class, () -> new RetentionSchedule(jobs, idempotency, credentials, audit, false, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetentionSchedule(jobs, idempotency, credentials, audit, false, true, -5));
        new RetentionSchedule(jobs, idempotency, credentials, audit, false, false, 0);
    }
}
