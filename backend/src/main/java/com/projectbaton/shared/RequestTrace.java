package com.projectbaton.shared;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

/**
 * 요청 하나의 trace id. {@link RequestTraceFilter}가 만들어 요청과 로깅 context에 두므로, 오류 응답과 로그 줄과 감사
 * 행의 id가 같은 값이라 셋을 서로 따라갈 수 있다. 클라이언트가 고르지 않는다. 요청 밖에서 도는 코드(기동 작업)에는
 * 없다.
 */
public final class RequestTrace {
    static final String ATTRIBUTE = "baton.traceId";
    static final String MDC_KEY = "traceId";

    private RequestTrace() {
    }

    static String begin(HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, traceId);
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    static void end() {
        MDC.remove(MDC_KEY);
    }

    /**
     * 이 요청의 id. 컨테이너의 오류 dispatch는 filter가 끝나 로깅 context를 비운 뒤에 돌지만 요청 속성은 남아 있어서
     * id는 요청에서 찾는다. filter를 거치지 않은 요청(/api 밖)은 새 id를 받는다.
     */
    public static String of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String traceId ? traceId : UUID.randomUUID().toString();
    }

    /** 이 스레드에서 처리 중인 요청의 id. 요청 밖이면 null. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 이 스레드에서 처리 중인 요청의 id. 요청 밖에서 도는 작업이면 새 id. */
    public static String currentOrNew() {
        String current = current();
        return current != null ? current : UUID.randomUUID().toString();
    }
}
