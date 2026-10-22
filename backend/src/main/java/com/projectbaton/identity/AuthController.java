package com.projectbaton.identity;

import com.projectbaton.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final LocalAuthenticationService authenticationService;
    private final AuthenticationAttemptLimiter authenticationAttemptLimiter;
    private final SecurityContextRepository securityContextRepository;
    private final AuditService audit;

    public AuthController(LocalAuthenticationService authenticationService,
                          AuthenticationAttemptLimiter authenticationAttemptLimiter,
                          SecurityContextRepository securityContextRepository, AuditService audit) {
        this.audit = audit;
        this.authenticationService = authenticationService;
        this.authenticationAttemptLimiter = authenticationAttemptLimiter;
        this.securityContextRepository = securityContextRepository;
    }

    @GetMapping("/auth/csrf")
    public ResponseEntity<CsrfTokenResponse> csrf(HttpServletRequest request) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse(csrfToken.getToken(), csrfToken.getHeaderName()));
    }

    @PostMapping("/auth/sign-in")
    public LocalAuthenticationService.CurrentUserResponse signIn(@Valid @RequestBody SignInRequest request,
                                                                  HttpServletRequest servletRequest,
                                                                  HttpServletResponse servletResponse) {
        String remoteAddress = servletRequest.getRemoteAddr();
        authenticationAttemptLimiter.requireAllowed(request.employeeNo(), remoteAddress);
        CurrentActor actor;
        try {
            actor = authenticationService.authenticate(request.employeeNo(), request.password());
        } catch (BadCredentialsException exception) {
            authenticationAttemptLimiter.recordFailure(request.employeeNo(), remoteAddress);
            throw exception;
        }
        authenticationAttemptLimiter.recordSuccess(request.employeeNo(), remoteAddress);
        // 인증되지 않은 호출자의 실패를 남기면 테이블이 끝없이 찰 수 있고 실패는 이미 limiter가 세므로, 성공만 감사에
        // 남긴다. 주소가 있어야 계정 주인이 자기 것이 아닌 로그인을 알아볼 수 있다.
        audit.record(actor.userId(), "SIGN_IN", "user", Long.toString(actor.userId()), "SUCCESS", null, remoteAddress);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        servletRequest.getSession();
        servletRequest.changeSessionId();
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        return authenticationService.currentUser(actor);
    }

    /** Spring Session은 쿠키를 발급한 CookieSerializer로 만료시키므로 Secure 플래그도 그대로 따라간다. */
    @PostMapping("/auth/sign-out")
    public ResponseEntity<Void> signOut(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public LocalAuthenticationService.CurrentUserResponse me(
            @org.springframework.security.core.annotation.AuthenticationPrincipal CurrentActor actor) {
        return authenticationService.currentUser(actor);
    }

    public record SignInRequest(@NotBlank String employeeNo, @NotBlank String password) {
    }

    public record CsrfTokenResponse(String token, String headerName) {
    }

}
