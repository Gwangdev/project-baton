package com.projectbaton.shared;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

// OAS가 nullable로 선언하지 않았으므로 Problem의 선택 항목(detail, instance, errors)은 비어 있으면 빼고 보낸다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        List<FieldError> errors,
        String code,
        String traceId
) {

    public record FieldError(String field, String message) {
    }
}
