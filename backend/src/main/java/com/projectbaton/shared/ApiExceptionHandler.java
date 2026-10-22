package com.projectbaton.shared;

import java.util.List;

import com.projectbaton.assessment.AssessmentService.AssessmentAccessDeniedException;
import com.projectbaton.assessment.AssessmentService.InvalidAssessmentInputException;
import com.projectbaton.connection.ManualDraftService.ManualDraftAccessDeniedException;
import com.projectbaton.connection.ManualDraftService.ManualDraftConflictException;
import com.projectbaton.connection.SourceConnectionService.ConnectionAccessDeniedException;
import com.projectbaton.connection.SourceProcessingService.SourceProcessingAccessDeniedException;
import com.projectbaton.connection.SourceProcessingService.SourceProcessingConflictException;
import com.projectbaton.handover.HandoverAcknowledgementService.AccessDeniedException;
import com.projectbaton.handover.HandoverConflictService.InvalidResolutionException;
import com.projectbaton.handover.HandoverItemQueryService.InvalidItemKindException;
import com.projectbaton.handover.HandoverQueryService.HandoverAccessDeniedException;
import com.projectbaton.handover.HandoverRequestService.HandoverConflictException;
import com.projectbaton.handover.HandoverRequestService.HandoverStaleException;
import com.projectbaton.handover.HandoverRequestService;
import com.projectbaton.identity.AuthenticationAttemptLimiter.AuthenticationRateLimitedException;
import com.projectbaton.identity.CredentialService;
import com.projectbaton.identity.LocalAuthenticationService.AccountInactiveException;
import com.projectbaton.identity.OrganizationService;
import com.projectbaton.identity.SystemAdminAccessPolicy;
import com.projectbaton.identity.UserManagementService.AdminAccessDeniedException;
import com.projectbaton.identity.UserManagementService.InvalidUserInputException;
import com.projectbaton.identity.UserManagementService.StaleUserVersionException;
import com.projectbaton.identity.UserManagementService.UserConflictException;
import com.projectbaton.identity.UserManagementService.UserNotFoundException;
import com.projectbaton.jobs.JobQueueSaturatedException;
import com.projectbaton.project.ProjectAccessPolicy.ProjectNotFoundException;
import com.projectbaton.project.ProjectAccessPolicy;
import com.projectbaton.project.ProjectAdminService.ProjectConflictException;
import com.projectbaton.project.ProjectAdminService.StaleProjectVersionException;
import com.projectbaton.project.ProjectAdminService;
import com.projectbaton.project.ProjectMemberService.InvalidMemberInputException;
import com.projectbaton.project.ProjectMemberService.MemberConflictException;
import com.projectbaton.project.ProjectMemberService.MemberNotFoundException;
import com.projectbaton.qa.QuestionService;
import com.projectbaton.record.RecordRelationService.RelationConflictException;
import com.projectbaton.record.RecordReviewService.InvalidReviewInputException;
import com.projectbaton.record.RecordReviewService.ReviewAccessDeniedException;
import com.projectbaton.record.RecordReviewService.ReviewConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    /** README §6에 맞춘다. 큐가 포화되면 밀린 작업이 빠질 시간이 지난 뒤 다시 오라고 알린다. */
    private static final String QUEUE_RETRY_AFTER_SECONDS = "30";

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiProblem> invalidCredentials(HttpServletRequest request) {
        return problem(request, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials.", null);
    }

    @ExceptionHandler(AccountInactiveException.class)
    ResponseEntity<ApiProblem> inactiveAccount(HttpServletRequest request) {
        return problem(request, HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "The account is inactive.", null);
    }

    /** 설정 코드가 틀린 모든 경우에 같은 답을 줘서, 어떤 계정이 있는지 응답으로 알 수 없게 한다. */
    @ExceptionHandler(CredentialService.InvalidCredentialTokenException.class)
    ResponseEntity<ApiProblem> invalidCredentialToken(HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "INVALID_CREDENTIAL_TOKEN", "The setup code is not valid.", null);
    }

    @ExceptionHandler(AuthenticationRateLimitedException.class)
    ResponseEntity<ApiProblem> rateLimited(HttpServletRequest request) {
        return problem(request, HttpStatus.TOO_MANY_REQUESTS, "AUTH_RATE_LIMITED",
                "Too many failed authentication attempts.", null);
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    ResponseEntity<ApiProblem> projectNotFound(HttpServletRequest request) {
        return problem(request, HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The requested project was not found.", null);
    }

    @ExceptionHandler(MemberNotFoundException.class) ResponseEntity<ApiProblem> memberNotFound(HttpServletRequest r){return problem(r,HttpStatus.NOT_FOUND,"PROJECT_MEMBER_NOT_FOUND","The requested project member was not found.",null);}
    @ExceptionHandler(InvalidMemberInputException.class) ResponseEntity<ApiProblem> invalidMember(HttpServletRequest r){return problem(r,HttpStatus.BAD_REQUEST,"INVALID_INPUT","Request validation failed.",null);}
    @ExceptionHandler(MemberConflictException.class) ResponseEntity<ApiProblem> memberConflict(MemberConflictException e,HttpServletRequest r){return problem(r,HttpStatus.CONFLICT,"INVALID_STATE",e.getMessage(),null);}

    @ExceptionHandler(AdminAccessDeniedException.class)
    ResponseEntity<ApiProblem> adminAccessDenied(HttpServletRequest request) {
        return problem(request, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.", null);
    }

    // 예전에는 IllegalArgumentException도 여기로 와서 내부 프로그래밍 오류가 호출자 탓인 400 응답이 됐다. 이제
    // controller가 잘못된 입력을 직접 InvalidInputException으로 바꾼다.
    @ExceptionHandler(InvalidUserInputException.class)
    ResponseEntity<ApiProblem> invalidUserInput(HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request validation failed.", null);
    }

    @ExceptionHandler(InvalidInputException.class)
    ResponseEntity<ApiProblem> invalidInput(InvalidInputException exception, HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request validation failed.",
                exception.errors().isEmpty() ? null : exception.errors());
    }

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<ApiProblem> userNotFound(HttpServletRequest request) {
        return problem(request, HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The requested user was not found.", null);
    }

    @ExceptionHandler(UserConflictException.class)
    ResponseEntity<ApiProblem> userConflict(UserConflictException exception, HttpServletRequest request) {
        return problem(request, HttpStatus.CONFLICT, "INVALID_STATE", exception.getMessage(), null);
    }

    @ExceptionHandler(StaleUserVersionException.class)
    ResponseEntity<ApiProblem> staleVersion(HttpServletRequest request) {
        return problem(request, HttpStatus.PRECONDITION_FAILED, "STALE_VERSION", "The user has changed.", null);
    }


    @ExceptionHandler(ProjectConflictException.class)
    ResponseEntity<ApiProblem> projectConflict(ProjectConflictException exception, HttpServletRequest request) {
        return problem(request, HttpStatus.CONFLICT, "INVALID_STATE", exception.getMessage(), null);
    }

    @ExceptionHandler(InvalidReviewInputException.class)
    ResponseEntity<ApiProblem> invalidReviewInput(HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request validation failed.", null);
    }

    @ExceptionHandler(ReviewAccessDeniedException.class)
    ResponseEntity<ApiProblem> reviewAccessDenied(HttpServletRequest request) {
        return problem(request, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.", null);
    }

    @ExceptionHandler(ConnectionAccessDeniedException.class)
    ResponseEntity<ApiProblem> connectionAccessDenied(HttpServletRequest request) {
        return problem(request, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.", null);
    }
    @ExceptionHandler(ManualDraftAccessDeniedException.class) ResponseEntity<ApiProblem> manualDraftAccess(HttpServletRequest r){return problem(r,HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access is denied.",null);}
    @ExceptionHandler(ManualDraftConflictException.class) ResponseEntity<ApiProblem> manualDraftConflict(ManualDraftConflictException e,HttpServletRequest r){return problem(r,HttpStatus.CONFLICT,"INVALID_STATE",e.getMessage(),null);}
    @ExceptionHandler(SourceProcessingAccessDeniedException.class) ResponseEntity<ApiProblem> sourceProcessingAccess(HttpServletRequest r){return problem(r,HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access is denied.",null);}
    @ExceptionHandler(SourceProcessingConflictException.class) ResponseEntity<ApiProblem> sourceProcessingConflict(SourceProcessingConflictException e,HttpServletRequest r){return problem(r,HttpStatus.CONFLICT,"INVALID_STATE",e.getMessage(),null);}
    @ExceptionHandler(HandoverConflictException.class) ResponseEntity<ApiProblem> handoverConflict(HandoverConflictException e,HttpServletRequest r){return problem(r,HttpStatus.CONFLICT,"INVALID_STATE",e.getMessage(),null);}
    @ExceptionHandler(HandoverRequestService.ScopeTooLargeException.class)
    ResponseEntity<ApiProblem> handoverScopeTooLarge(HttpServletRequest request) {
        return problem(request, HttpStatus.CONFLICT, "SCOPE_TOO_LARGE",
                "The scope holds more than 5,000 confirmed records. Narrow the period or the record types.", null);
    }

    @ExceptionHandler(HandoverStaleException.class) ResponseEntity<ApiProblem> handoverStale(HttpServletRequest r){return problem(r,HttpStatus.PRECONDITION_FAILED,"STALE_VERSION","The handover has changed.",null);}
    @ExceptionHandler(AccessDeniedException.class) ResponseEntity<ApiProblem> handoverAcknowledgementAccess(HttpServletRequest r){return problem(r,HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access is denied.",null);}
    @ExceptionHandler(ProjectAccessPolicy.ManagerRequiredException.class)
    ResponseEntity<ApiProblem> managerRequired(HttpServletRequest request) {
        return problem(request, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "The project manager role is required.", null);
    }
    @ExceptionHandler(HandoverAccessDeniedException.class) ResponseEntity<ApiProblem> handoverReadAccess(HttpServletRequest r){return problem(r,HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access is denied.",null);}
    @ExceptionHandler(InvalidResolutionException.class) ResponseEntity<ApiProblem> invalidResolution(HttpServletRequest r){return problem(r,HttpStatus.BAD_REQUEST,"INVALID_INPUT","Request validation failed.",null);}
    @ExceptionHandler(InvalidItemKindException.class) ResponseEntity<ApiProblem> invalidItemKind(HttpServletRequest r){return problem(r,HttpStatus.BAD_REQUEST,"INVALID_INPUT","Request validation failed.",null);}
    @ExceptionHandler(InvalidAssessmentInputException.class) ResponseEntity<ApiProblem> invalidAssessmentInput(HttpServletRequest r){return problem(r,HttpStatus.BAD_REQUEST,"INVALID_INPUT","Request validation failed.",null);}
    @ExceptionHandler(AssessmentAccessDeniedException.class) ResponseEntity<ApiProblem> assessmentAccessDenied(HttpServletRequest r){return problem(r,HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access is denied.",null);}

    @ExceptionHandler(ReviewConflictException.class)
    ResponseEntity<ApiProblem> reviewConflict(ReviewConflictException exception, HttpServletRequest request) {
        return problem(request, HttpStatus.CONFLICT, "INVALID_STATE", exception.getMessage(), null);
    }

    @ExceptionHandler(RelationConflictException.class)
    ResponseEntity<ApiProblem> relationConflict(RelationConflictException exception, HttpServletRequest request) {
        return problem(request, HttpStatus.CONFLICT, "INVALID_STATE", exception.getMessage(), null);
    }

    @ExceptionHandler(StaleProjectVersionException.class)
    ResponseEntity<ApiProblem> staleProjectVersion(HttpServletRequest request) {
        return problem(request, HttpStatus.PRECONDITION_FAILED, "STALE_VERSION", "The project has changed.", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalidInput(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiProblem.FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiProblem.FieldError(error.getField(), "Invalid value."))
                .toList();
        return problem(request, HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request validation failed.", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiProblem> unreadableInput(HttpMessageNotReadableException exception, HttpServletRequest request) {
        // 본문 converter가 읽기 실패를 감싸므로 1 MiB 한도에서 잘린 본문은 원인(cause)으로 여기 도착한다.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof RequestBodyLimitFilter.PayloadTooLargeException) {
                return payloadTooLarge(request);
            }
        }
        return problem(request, HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request content is invalid.", null);
    }

    @ExceptionHandler(RequestBodyLimitFilter.PayloadTooLargeException.class)
    ResponseEntity<ApiProblem> payloadTooLarge(HttpServletRequest request) {
        return problem(request, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "The request body exceeds 1 MiB.", null);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class})
    ResponseEntity<ApiProblem> invalidRequestParameters(HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request parameters are invalid.", null);
    }

    @ExceptionHandler(SystemAdminAccessPolicy.SystemAdminRequiredException.class)
    ResponseEntity<ApiProblem> systemAdminRequired(HttpServletRequest request) {
        return problem(request, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "System administrator access is required.", null);
    }

    @ExceptionHandler(OrganizationService.OrganizationNotFoundException.class)
    ResponseEntity<ApiProblem> organizationNotFound(HttpServletRequest request) {
        return problem(request, HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The requested organization was not found.",
                null);
    }

    @ExceptionHandler(OrganizationService.DuplicateOrganizationNameException.class)
    ResponseEntity<ApiProblem> duplicateOrganizationName(HttpServletRequest request) {
        return problem(request, HttpStatus.CONFLICT, "DUPLICATE_ORGANIZATION_NAME",
                "An organization with this name already exists.", null);
    }

    @ExceptionHandler(OrganizationService.LastActiveSystemAdminException.class)
    ResponseEntity<ApiProblem> lastActiveSystemAdmin(HttpServletRequest request) {
        return problem(request, HttpStatus.CONFLICT, "LAST_SYSTEM_ADMIN",
                "The last active system administrator cannot be removed this way.", null);
    }

    @ExceptionHandler(OrganizationService.InvalidPatchException.class)
    ResponseEntity<ApiProblem> invalidPatch(HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request validation failed.", null);
    }

    @ExceptionHandler(IfMatchRequiredException.class)
    ResponseEntity<ApiProblem> ifMatchRequired(HttpServletRequest request) {
        return problem(request, HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED", "An If-Match header is required.",
                null);
    }

    @ExceptionHandler(StaleVersionException.class)
    ResponseEntity<ApiProblem> staleResourceVersion(HttpServletRequest request) {
        return problem(request, HttpStatus.PRECONDITION_FAILED, "STALE_VERSION",
                "The resource has changed since it was last read.", null);
    }

    @ExceptionHandler(ReferenceConflictException.class)
    ResponseEntity<ReferenceConflictProblem> referenceConflict(ReferenceConflictException exception,
                                                                HttpServletRequest request) {
        ApiProblem base = ApiProblemWriter.problem(request, HttpStatus.CONFLICT.value(), "REFERENCE_CONFLICT",
                "Other data still references this resource.", null);
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new ReferenceConflictProblem(base.type(), base.title(), base.status(), base.detail(),
                        base.instance(), base.errors(), base.code(), base.traceId(), exception.references()));
    }

    @ExceptionHandler(IdempotencyService.IdempotencyConflictException.class)
    ResponseEntity<ApiProblem> idempotencyConflict(HttpServletRequest request) {
        return problem(request, HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "The idempotency key was already used with a different request body.", null);
    }

    @ExceptionHandler(IdempotencyService.RequestInProgressException.class)
    ResponseEntity<ApiProblem> requestInProgress(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).header("Retry-After", "1")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblemWriter.problem(request, HttpStatus.CONFLICT.value(), "REQUEST_IN_PROGRESS",
                        "The original request with this idempotency key is still being processed.", null));
    }

    @ExceptionHandler(IdempotencyService.InvalidIdempotencyBodyException.class)
    ResponseEntity<ApiProblem> invalidIdempotencyBody(HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request body could not be parsed.", null);
    }

    @ExceptionHandler(QuestionService.InvalidQuestionException.class)
    ResponseEntity<ApiProblem> invalidQuestion(HttpServletRequest request) {
        return problem(request, HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request validation failed.", null);
    }

    @ExceptionHandler(QuestionService.QaAccessDeniedException.class)
    ResponseEntity<ApiProblem> questionAccessDenied(HttpServletRequest request) {
        return problem(request, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied.", null);
    }

    @ExceptionHandler(QuestionService.QaRateLimitedException.class)
    ResponseEntity<ApiProblem> questionRateLimited(QuestionService.QaRateLimitedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", Integer.toString(exception.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblemWriter.problem(request, HttpStatus.TOO_MANY_REQUESTS.value(), "QA_RATE_LIMITED", exception.getMessage(), null));
    }

    @ExceptionHandler(JobQueueSaturatedException.class)
    ResponseEntity<ApiProblem> queueFull(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", QUEUE_RETRY_AFTER_SECONDS)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblemWriter.problem(request, HttpStatus.SERVICE_UNAVAILABLE.value(), "QUEUE_FULL",
                        "The work queue is full. Retry later.", null));
    }

    /**
     * 어떤 처리기도 맡지 않은 예외의 마지막 처리. 404, 405, 415 같은 프레임워크 거절은 이미 상태를 가지고 있어서
     * 그대로 두고 Problem 본문만 붙인다. 나머지는 예상하지 못한 오류라 일반 500으로 바꾸고, 로그에는 실패 위치와
     * 클라이언트가 받은 traceId만 남긴다.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse rejection) {
            int status = rejection.getStatusCode().value();
            return ResponseEntity.status(status).headers(rejection.getHeaders()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ApiProblemWriter.problem(request, status, ApiProblemWriter.codeFor(status), "The request could not be completed.", null));
        }
        ApiProblem problem = ApiProblemWriter.problem(request, HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR",
                "The request could not be completed.", null);
        log.error("Unhandled {} traceId={}", ApiProblemWriter.origin(exception), problem.traceId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private ResponseEntity<ApiProblem> problem(HttpServletRequest request, HttpStatus status, String code, String detail,
                                               List<ApiProblem.FieldError> errors) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblemWriter.problem(request, status.value(), code, detail, errors));
    }
}
