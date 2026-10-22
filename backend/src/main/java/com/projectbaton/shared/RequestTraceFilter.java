package com.projectbaton.shared;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/** 다른 무엇이 실패하기 전에 모든 API 요청에 trace id를 주고, 끝나면 로깅 context에서 뺀다. */
public class RequestTraceFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestTrace.begin(request);
        try {
            chain.doFilter(request, response);
        } finally {
            RequestTrace.end();
        }
    }
}
