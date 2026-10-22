package com.projectbaton.shared;

/**
 * 모든 관리·조회 endpoint가 함께 쓰는 목록 응답의 페이지 정보(contracts.md C01). {@code total}은 원래 행 수가 아니라
 * 권한으로 거른 뒤의 수다.
 */
public record PageMeta(long total, int limit, int offset, String nextCursor) {
}
