package com.projectbaton.shared;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

public final class ApiProblemWriter {

    private ApiProblemWriter() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title(status)
                + "\",\"status\":" + status + ",\"detail\":\"" + detail(code)
                + "\",\"instance\":\"" + jsonEscape(request.getRequestURI()) + "\",\"code\":\""
                + jsonEscape(code) + "\",\"traceId\":\"" + RequestTrace.of(request) + "\"}");
    }

    public static ApiProblem problem(HttpServletRequest request, int status, String code, String detail,
                                     java.util.List<ApiProblem.FieldError> errors) {
        return problem(request.getRequestURI(), RequestTrace.of(request), status, code, detail, errors);
    }

    /** 컨테이너의 오류 dispatch는 /error 아래에서 돌아서 원래 요청 URI를 직접 넘긴다. */
    public static ApiProblem problem(String instance, String traceId, int status, String code, String detail,
                                     java.util.List<ApiProblem.FieldError> errors) {
        return new ApiProblem("about:blank", title(status), status, detail, instance, errors, code, traceId);
    }

    /** 업무 규칙이 이름 붙이지 않은 상태(프레임워크나 컨테이너의 거절 등)에 쓰는 고정 Problem 코드. */
    public static String codeFor(int status) {
        return switch (status) {
            case 400 -> "INVALID_INPUT";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
    }

    /**
     * 서버 로그에 남길, 예상하지 못한 실패가 난 위치. 예외 메시지에는 SQL, bind 값, 원문이 들어갈 수 있어서 타입과 첫
     * 앱 frame만 남긴다. 프레임워크 frame만으로는 오류 위치를 찾기 어렵다.
     */
    public static String origin(Throwable failure) {
        StackTraceElement[] frames = failure.getStackTrace();
        for (StackTraceElement frame : frames) {
            if (frame.getClassName().startsWith("com.projectbaton.")) return failure.getClass().getName() + " at " + frame;
        }
        return failure.getClass().getName() + " at " + (frames.length == 0 ? "unknown" : frames[0].toString());
    }

    private static String title(int status) {
        try {
            return HttpStatus.valueOf(status).getReasonPhrase();
        } catch (IllegalArgumentException ignored) {
            return "Error";
        }
    }

    private static String detail(String code) {
        return switch (code) {
            case "UNAUTHENTICATED" -> "Authentication is required.";
            case "CSRF_REQUIRED" -> "A valid CSRF token is required.";
            case "ACCESS_DENIED" -> "Access is denied.";
            case "ACCOUNT_INACTIVE" -> "The account is inactive.";
            default -> "The request could not be completed.";
        };
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
