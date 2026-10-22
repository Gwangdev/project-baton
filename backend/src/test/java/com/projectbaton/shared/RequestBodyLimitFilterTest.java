package com.projectbaton.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Content-Length가 없는 본문(chunked 전송)은 읽으면서 세다가 한도에서 멈춘다. */
class RequestBodyLimitFilterTest {
    @Test
    void aChunkedBodyFailsOnceItPassesTheCap() {
        MockHttpServletRequest request = chunked((int) RequestBodyLimitFilter.MAX_BODY_BYTES + 1);

        assertThrows(RequestBodyLimitFilter.PayloadTooLargeException.class, () -> new RequestBodyLimitFilter().doFilter(
                request, new MockHttpServletResponse(), (filtered, response) -> ((HttpServletRequest) filtered).getInputStream().readAllBytes()));
    }

    @Test
    void aChunkedBodyAtTheCapIsReadWhole() throws Exception {
        MockHttpServletRequest request = chunked((int) RequestBodyLimitFilter.MAX_BODY_BYTES);
        int[] read = new int[1];

        new RequestBodyLimitFilter().doFilter(request, new MockHttpServletResponse(),
                (filtered, response) -> read[0] = ((HttpServletRequest) filtered).getInputStream().readAllBytes().length);

        assertEquals(RequestBodyLimitFilter.MAX_BODY_BYTES, read[0]);
    }

    @Test
    void aChunkedBodyReadAsTextIsCappedToo() {
        MockHttpServletRequest request = chunked((int) RequestBodyLimitFilter.MAX_BODY_BYTES + 1);

        assertThrows(java.io.IOException.class, () -> new RequestBodyLimitFilter().doFilter(request, new MockHttpServletResponse(),
                (filtered, response) -> ((HttpServletRequest) filtered).getReader().transferTo(java.io.Writer.nullWriter())));
    }

    private static MockHttpServletRequest chunked(int size) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects/1/notes") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[size]);
        return request;
    }
}
