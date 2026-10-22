package com.projectbaton.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** trace id는 서버가 요청마다 만들고, 요청이 도는 동안 보이고, 요청보다 오래 남지 않는다. */
class RequestTraceFilterTest {
    private final RequestTraceFilter filter = new RequestTraceFilter();

    @Test
    void theIdIsOnTheRequestAndInTheLoggingContextWhileItRunsAndGoneAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        String[] seen = new String[1];

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                seen[0] = RequestTrace.current();
            }
        }));

        assertNotNull(seen[0]);
        assertEquals(seen[0], RequestTrace.of(request));
        assertNull(RequestTrace.current());
    }

    @Test
    void aClientSuppliedIdIsNotUsedAndTwoRequestsGetTwoIds() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/me");
        first.addHeader("X-Trace-Id", "chosen-by-the-client");
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/me");

        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(second, new MockHttpServletResponse(), new MockFilterChain());

        assertNotEquals("chosen-by-the-client", RequestTrace.of(first));
        assertNotEquals(RequestTrace.of(first), RequestTrace.of(second));
    }

    @Test
    void theLoggingContextIsClearedWhenTheRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException("boom");
        }));

        assertNull(RequestTrace.current());
    }
}
