package com.projectbaton.record;

import java.time.LocalDate;
import java.util.List;

/**
 * 수집한 원문 문서 하나를 기록 초안 후보로 바꾼다. 프로젝트, 원문, 신뢰 상태는 모델이 정하지 않는다. 호출자가 원문
 * 행에서 묶어 넣고, 모든 초안은 사람이 검토하도록 AUTO_DRAFT로 저장한다.
 */
public interface RecordStructurer {

    List<DraftRecord> extract(SourceDocument document);

    /** 구조화기가 보는 사실은 원문과 담당자로 지정할 수 있는 구성원뿐이다. */
    record SourceDocument(String body, String authorName, LocalDate occurredOn, List<Member> members) { }

    record Member(long userId, String name) { }

    record DraftRecord(String recordType, String title, String summary, String originalExcerpt, LocalDate occurredOn,
                       Long ownerUserId, LocalDate dueOn) { }

    /** {@code retryable}은 공급자 장애와, 매번 같은 방식으로 실패할 출력을 가른다. */
    class StructuringException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public StructuringException(String code, boolean retryable) {
            super(code);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }
}
