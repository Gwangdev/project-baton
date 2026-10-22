package com.projectbaton.qa;

import java.util.List;

/**
 * 인수인계 질문의 근거를 찾는다. 서버가 로그인한 행위자와 현재 인수인계 회차로 쿼리를 만들어서(README §7), 질문 글의
 * 어떤 내용도 검색하는 프로젝트나 회차를 넓힐 수 없다.
 */
public interface EvidenceRetriever {
    /** README §7: 답변에 닿는 근거 조각은 최대 여덟 개다. */
    int MAX_EVIDENCE = 8;

    List<EvidenceChunk> retrieve(EvidenceQuery query);

    record EvidenceQuery(long actorId, long projectId, long handoverId, int generation, String question, int limit) { }

    /** {@code chunkId}는 벡터 검색 결과면 임베딩 행 id이고, 키워드 검색 결과면 {@code snapshot:<id>}다. */
    record EvidenceChunk(long snapshotRecordId, String chunkId, String text, String sourceLabel) { }

    class EvidenceAccessDeniedException extends RuntimeException { }
}
