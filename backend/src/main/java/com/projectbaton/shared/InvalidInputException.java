package com.projectbaton.shared;

import java.util.List;

/**
 * 본문이나 파라미터가 계약을 어긴 요청(C01: 400 INVALID_INPUT). 예전에는 모든 도메인이 ProjectAdminService의 예외를
 * 빌려 써서, 프로젝트 관리의 무관한 변경이 기록, 메모, 연동, 인수인계까지 번질 수 있었다.
 *
 * <p>잘못이 특정 필드에 있으면 Problem의 {@code errors}가 그 필드를 밝힌다. 호출자가 담당자로 지정한 사용자가
 * 프로젝트 구성원이 아닌 경우가 그렇다. 호출자는 그 프로젝트를 볼 수 있으므로 프로젝트를 숨기는 404가 아니라 그
 * 필드에 대한 400으로 답한다.
 */
public class InvalidInputException extends RuntimeException {
    private final List<ApiProblem.FieldError> errors;

    public InvalidInputException() {
        this.errors = List.of();
    }

    public InvalidInputException(String field, String message) {
        this.errors = List.of(new ApiProblem.FieldError(field, message));
    }

    /** 요청이 지정한 사용자(담당자)가 해당 프로젝트의 현재 구성원이 아닌 경우. */
    public static InvalidInputException notAMember(String field) {
        return new InvalidInputException(field, "Must name a current member of the project.");
    }

    public List<ApiProblem.FieldError> errors() {
        return errors;
    }
}
