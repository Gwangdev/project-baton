package com.projectbaton.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

import com.projectbaton.shared.ApiProblemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * README §4.1은 운영에서 Secure=true로 고정한다. 앱은 TLS를 끝내는 프록시 뒤에서 평문 HTTP를 보므로, Secure를
     * {@code request.isSecure()}에 맡기면 오류 없이 빠진다. 그래서 플래그를 명시하고, 평문 HTTP 로컬 스택(브라우저
     * E2E, compose.dev.yml)이 끌 때만 꺼진다.
     */
    @Bean
    CookieSerializer cookieSerializer(@Value("${baton.session.cookie-secure:true}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("BATON_SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secure);
        return serializer;
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(@Value("${baton.session.cookie-secure:true}") boolean secure) {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setHeaderName("X-CSRF-TOKEN");
        csrf.setCookieCustomizer(cookie -> cookie.secure(secure));
        return csrf;
    }

    /**
     * 관리 포트가 이 보안 구성을 함께 써서 probe와 metric에 따로 chain을 준다. 이 둘은 그 포트에서만
     * 나가고(application.yml) 프록시는 /api만 넘기므로 클라이언트에게 노출되지 않는다.
     */
    @Bean
    @Order(1)
    SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository securityContextRepository,
                                            CsrfTokenRepository csrf, LocalAuthenticationService authenticationService)
            throws Exception {
        http.securityContext(context -> context.securityContextRepository(securityContextRepository))
                .csrf(configurer -> configurer.csrfTokenRepository(csrf))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        // 컨테이너의 오류 dispatch는 원래 실패를 그려야 하는데, 다시 인가하면 익명 요청의 400이나
                        // 500이 401로 바뀐다. 클라이언트가 /error를 직접 요청한 것은 ERROR dispatch가 아니다.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/sign-in", "/api/v1/auth/credentials").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                ApiProblemWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHENTICATED"))
                        .accessDeniedHandler((request, response, exception) -> ApiProblemWriter.write(request, response,
                                HttpServletResponse.SC_FORBIDDEN,
                                exception instanceof CsrfException ? "CSRF_REQUIRED" : "ACCESS_DENIED")))
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .addFilterAfter(new ActiveActorFilter(authenticationService), SecurityContextHolderFilter.class);
        return http.build();
    }

}
