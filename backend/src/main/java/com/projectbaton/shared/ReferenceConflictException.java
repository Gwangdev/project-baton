package com.projectbaton.shared;

import java.util.List;

/**
 * 다른 행이 아직 대상을 참조해서 영구 삭제가 거절될 때 던진다. 자원 이름과 개수만 담고 ID나 비공개 내용은 담지
 * 않는다(contracts.md C02).
 */
public class ReferenceConflictException extends RuntimeException {

    private final List<ReferenceConflictProblem.BlockingReference> references;

    public ReferenceConflictException(List<ReferenceConflictProblem.BlockingReference> references) {
        this.references = references;
    }

    public List<ReferenceConflictProblem.BlockingReference> references() {
        return references;
    }
}
