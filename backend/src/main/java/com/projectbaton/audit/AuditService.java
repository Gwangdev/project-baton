package com.projectbaton.audit;

import com.projectbaton.shared.RequestTrace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    /**
     * 지정한 개월 수보다 오래된 로그인의 주소를 오래된 것부터 한 번에 한 묶음씩 비운다. 통합 시험이 DB에 실행 계획을
     * 물어볼 수 있게 public으로 둔다.
     */
    public static final String CLEAR_SIGN_IN_ADDRESSES = """
            update audit_events set remote_address = null where id in (
                select id from audit_events
                where action = 'SIGN_IN' and remote_address is not null and occurred_at < now() - make_interval(months => ?)
                order by occurred_at limit ?)
            """;
    private static final int BATCH = 5_000;

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(Long actorId, String action, String targetType, String targetId, String result, String traceId) {
        record(actorId, action, targetType, targetId, result, traceId, null);
    }

    /**
     * 설정 코드 행위(코드를 발급한 관리자가 직접 쓸 수도 있다)와 로그인(본인이 자기 주소와 비교해 본다)은 나중에
     * 판단할 때 요청 주소가 필요해서 이 둘에만 주소를 남기고 나머지는 비워 둔다. 로그인 주소는 나중에
     * {@link #clearSignInAddresses}가 비운다.
     */
    public void record(Long actorId, String action, String targetType, String targetId, String result, String traceId, String remoteAddress) {
        jdbcTemplate.update("""
                insert into audit_events (actor_id, action, target_type, target_id, result, trace_id, remote_address)
                values (?, ?, ?, ?, ?, ?, ?)
                """, actorId, action, targetType, targetId, result, traceId != null ? traceId : RequestTrace.currentOrNew(), remoteAddress);
    }

    /**
     * {@code months} 개월보다 오래된 로그인의 요청 주소를 묶음 단위로 비우고 비운 수를 돌려준다. 묶음으로 나누어 한
     * 문장이 테이블의 큰 부분을 건드리지 않게 한다. 주소는 개인정보(A-02)이고 끝을 정할 프로젝트도 없지만, 누가 언제
     * 로그인했는지는 나중에 요구될 수 있는 감사 증거라서 행은 남긴다. 따라잡은 뒤에는 다시 불러도 바뀌는 것이 없다.
     */
    public int clearSignInAddresses(int months) {
        int total = 0;
        int cleared;
        do {
            cleared = jdbcTemplate.update(CLEAR_SIGN_IN_ADDRESSES, months, BATCH);
            total += cleared;
        } while (cleared == BATCH);
        return total;
    }
}
