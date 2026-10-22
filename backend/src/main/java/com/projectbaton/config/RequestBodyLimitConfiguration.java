package com.projectbaton.config;

import com.projectbaton.shared.RequestBodyLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 본문 크기 제한은 trace id를 붙인 직후(RequestTraceConfiguration), 보안과 세션 처리보다 먼저 돌아서 너무 큰 본문을
 * 읽지 않고 거절하고, 그 거절에도 trace id가 붙는다.
 */
@Configuration
public class RequestBodyLimitConfiguration {
    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter() {
        FilterRegistrationBean<RequestBodyLimitFilter> registration = new FilterRegistrationBean<>(new RequestBodyLimitFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
