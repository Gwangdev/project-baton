package com.projectbaton.shared;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 다른 행이 아직 대상을 참조해서 거절된 영구 삭제의 Problem 본문(contracts.md C02: FK restrict와 명시적 참조 수,
 * 목록에 비공개 내용 없음).
 */
// ApiProblem과 같은 규칙. 없는 선택 항목은 null로 보내지 않고 뺀다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceConflictProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        List<ApiProblem.FieldError> errors,
        String code,
        String traceId,
        List<BlockingReference> references
) {

    public record BlockingReference(String resource, int count, String hint) {
    }
}
