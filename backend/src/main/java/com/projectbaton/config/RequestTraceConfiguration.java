package com.projectbaton.config;

import com.projectbaton.shared.RequestTraceFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** trace id를 무엇보다 먼저 붙여서 거절된 요청에도 trace id가 있다. */
@Configuration
public class RequestTraceConfiguration {
    @Bean
    FilterRegistrationBean<RequestTraceFilter> requestTraceFilter() {
        FilterRegistrationBean<RequestTraceFilter> registration = new FilterRegistrationBean<>(new RequestTraceFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
