package com.projectbaton.identity;

import com.projectbaton.shared.InvalidInputException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 로컬 계정의 설정 코드. 관리자가 발급하고 사용자가 쓴다. 재실행하면 응답이 저장되는데 첫 호출의 응답은 평문 코드여서
 * 두 호출 모두 Idempotency-Key를 받지 않고, 두 번째 호출은 코드 자체가 한 번만 쓰이게 한다.
 */
@RestController
public class CredentialController {
    private final CredentialService credentials;
    private final UserManagementService users;
    private final AuthenticationAttemptLimiter limiter;

    public CredentialController(CredentialService credentials, UserManagementService users, AuthenticationAttemptLimiter limiter) {
        this.credentials = credentials;
        this.users = users;
        this.limiter = limiter;
    }

    @PostMapping("/api/v1/admin/users/{userId}/credential-tokens")
    public ResponseEntity<CredentialService.IssuedToken> issue(@AuthenticationPrincipal CurrentActor actor, @PathVariable long userId,
                                                               HttpServletRequest request) {
        users.requireSysadmin(actor.userId());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(credentials.issue(actor.userId(), userId, request.getRemoteAddr()));
    }

    @PostMapping("/api/v1/auth/credentials")
    public ResponseEntity<Void> set(@RequestBody JsonNode body, HttpServletRequest request) {
        String employeeNo = text(body, "employeeNo", 64);
        String token = text(body, "token", 128);
        String newPassword = text(body, "newPassword", 1_024);
        if (body.size() != 3) {
            throw new InvalidInputException();
        }
        // 코드를 추측하는 것도 비밀번호를 추측하는 것만큼 공격이므로 로그인과 같은 limiter를 쓴다.
        String address = request.getRemoteAddr();
        limiter.requireAllowed(employeeNo, address);
        try {
            credentials.setCredential(employeeNo, token, newPassword, address);
        } catch (CredentialService.InvalidCredentialTokenException failure) {
            limiter.recordFailure(employeeNo, address);
            throw failure;
        }
        limiter.recordSuccess(employeeNo, address);
        return ResponseEntity.noContent().build();
    }

    private static String text(JsonNode body, String field, int maxLength) {
        JsonNode value = body.get(field);
        if (!body.isObject() || value == null || !value.isTextual() || value.stringValue().isBlank() || value.stringValue().length() > maxLength) {
            throw new InvalidInputException();
        }
        return value.stringValue();
    }
}
