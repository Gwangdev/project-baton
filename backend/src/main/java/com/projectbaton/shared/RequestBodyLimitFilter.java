package com.projectbaton.shared;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * contracts.md C01은 JSON 요청 본문을 1 MiB로 제한한다. 이를 강제하는 곳이 없어서 아무 클라이언트나 API가 임의로 큰
 * 본문을 메모리에 담게 할 수 있었다. 선언된 Content-Length가 한도를 넘으면 아무것도 읽지 않고 거절하고, 길이가 없는
 * 본문(chunked)은 읽으면서 세다가 한도를 넘는 순간 실패한다. 둘 다 413 PAYLOAD_TOO_LARGE로 답한다. 프록시도 API
 * 앞에서 같은 한도를 둔다.
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    public static final long MAX_BODY_BYTES = 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            ApiProblemWriter.write(request, response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "PAYLOAD_TOO_LARGE");
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    /** 한도를 넘은 본문을 읽는 중에 던진다. ApiExceptionHandler가 413으로 그린다. */
    public static class PayloadTooLargeException extends IOException {
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private ServletInputStream limited;
        private BufferedReader reader;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitedInputStream(super.getInputStream());
            }
            return limited;
        }

        /** 문자로 읽는 경로도 같은 계수 stream을 거쳐서, 문자로 읽는 본문에도 한도가 걸린다. */
        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                reader = new BufferedReader(new InputStreamReader(getInputStream(),
                        encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
            }
            return reader;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private long read;

        LimitedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(long bytes) throws PayloadTooLargeException {
            read += bytes;
            if (read > MAX_BODY_BYTES) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
