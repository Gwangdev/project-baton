package com.projectbaton.config;

import com.projectbaton.audit.AuditService;
import com.projectbaton.identity.CredentialService;
import com.projectbaton.jobs.JobRepository;
import com.projectbaton.jobs.DatabaseLane;
import com.projectbaton.shared.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보존 기간이 지난 운영 행을 지운다(README §8, C01). 재실행용 멱등성 결과는 계약상 24시간 뒤 만료되므로 늘 지운다.
 * 작업 메타데이터는 성공 뒤 7일, 실패 뒤 30일 남기는데, 운영 보존 정책이 아직 확정되지 않았고 README §8이 확정 전
 * 자동 삭제를 허용하지 않아서 {@code baton.retention.jobs.enabled}가 켜졌을 때만 지운다. 이 스위치는 합성 데이터
 * 스택만 켠다. 끄면 수집 일정만으로도 하루에 작업 행이 약 345,000개 쌓인다.
 *
 * <p>로그인 감사 행에는 요청이 온 주소가 남는다. 주소는 개인정보(A-02)이고 로그인에는 끝을 정할 프로젝트가 없으므로,
 * {@code baton.retention.audit.enabled}가 켜져 있으면 {@code baton.retention.audit.sign-in-address-months}(기본 24,
 * 윤년이 껴도 달력 2년)보다 오래된 로그인의 주소를 비운다. 누가 언제 로그인했는지는 나중에 요구될 수 있고 지운 행을
 * 되살릴 백업도 아직 없어서(A-07) 행은 지우지 않는다. 같은 이유로 이 스위치도 기본은 꺼져 있다.
 *
 * <p>단계마다 따로 돌아서, 실패한 단계는 그 이름으로 로그를 남기고 다음 단계는 그대로 돈다. 계속 실패하는 테이블
 * 하나가 다른 테이블의 정리를 막지 못한다.
 */
@Component
public class RetentionSchedule {
    private static final Logger log = LoggerFactory.getLogger(RetentionSchedule.class);

    private final JobRepository jobs;
    private final IdempotencyService idempotency;
    private final CredentialService credentials;
    private final AuditService audit;
    private final boolean jobsEnabled;
    private final boolean auditEnabled;
    private final int signInAddressMonths;

    public RetentionSchedule(JobRepository jobs, IdempotencyService idempotency, CredentialService credentials, AuditService audit,
                             @Value("${baton.retention.jobs.enabled:false}") boolean jobsEnabled,
                             @Value("${baton.retention.audit.enabled:false}") boolean auditEnabled,
                             @Value("${baton.retention.audit.sign-in-address-months:24}") int signInAddressMonths) {
        if (auditEnabled && signInAddressMonths < 1) {
            // 기간이 0 이하이면 모든 주소를 한꺼번에 비우게 되므로 막는다.
            throw new IllegalArgumentException("baton.retention.audit.sign-in-address-months must be at least 1 when audit retention is on.");
        }
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.credentials = credentials;
        this.audit = audit;
        this.jobsEnabled = jobsEnabled;
        this.auditEnabled = auditEnabled;
        this.signInAddressMonths = signInAddressMonths;
    }

    @Scheduled(fixedDelayString = "${baton.retention.interval:600000}", initialDelayString = "${baton.retention.interval:600000}")
    public void run() {
        DatabaseLane.CONTROL.run(() -> {
            step("idempotency results", idempotency::deleteExpired);
            step("setup codes", credentials::deleteExpired);
            if (jobsEnabled) step("job metadata", jobs::deleteFinished);
            if (auditEnabled) step("sign-in addresses", () -> audit.clearSignInAddresses(signInAddressMonths));
        });
    }

    private static void step(String name, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            log.error("Retention step '{}' failed; the other steps still run and this one is tried again next time.", name, failure);
        }
    }
}
