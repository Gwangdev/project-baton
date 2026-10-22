package com.projectbaton.shared;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * servlet 컨테이너의 오류 dispatch를 Problem으로 그린다. servlet filter에서 난 실패, 보안 firewall의 거절, 컨테이너
 * 수준의 오류는 {@link ApiExceptionHandler}에 닿지 않고, Spring Boot의 기본 controller는 자기 JSON 모양으로 답해서
 * C01 오류 계약을 깬다. Boot의 error attributes로는 media type을 바꿀 수 없어서 기본 controller를 고치지 않고
 * 대신한다.
 */
@RestController
public class ProblemErrorController implements ErrorController {
    private static final Logger log = LoggerFactory.getLogger(ProblemErrorController.class);

    // 오류 dispatch는 실패한 요청의 메서드를 그대로 가지고 오므로 API가 받는 모든 메서드가 이 처리기에 닿아야 한다.
    // 이 처리기는 그리고 로그를 남길 뿐 상태를 바꾸지 않아서 POST/PUT/PATCH/DELETE를 받아도 CSRF 영향이 없다.
    @RequestMapping(value = "/error", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<ApiProblem> error(HttpServletRequest request) {
        // 상태 값은 컨테이너의 오류 dispatch에만 있다. 클라이언트가 /error를 직접 요청하면 읽을 것이 없다.
        if (!(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status) || status < 400) {
            return respond(ApiProblemWriter.problem(request, 404, ApiProblemWriter.codeFor(404), "The request could not be completed.", null));
        }
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        ApiProblem problem = ApiProblemWriter.problem(uri instanceof String original ? original : request.getRequestURI(), RequestTrace.of(request), status,
                ApiProblemWriter.codeFor(status), "The request could not be completed.", null);
        if (status >= 500 && request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure) {
            log.error("Unhandled {} traceId={}", ApiProblemWriter.origin(failure), problem.traceId());
        }
        return respond(problem);
    }

    private ResponseEntity<ApiProblem> respond(ApiProblem problem) {
        return ResponseEntity.status(problem.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
