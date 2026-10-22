package com.projectbaton.shared;

/**
 * contracts.md C01의 offset 페이지 범위: limit 1..100(기본 20), offset 0..10,000. 범위를 벗어나면 조용히 맞추지 않고
 * 400으로 답해서, 클라이언트가 목록을 다 읽지 않았는데 다 읽었다고 믿는 일이 없게 한다.
 */
public record PageRequest(int limit, int offset) {
    public static final int DEFAULT_LIMIT = 20;

    public PageRequest {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10_000) {
            throw new InvalidInputException();
        }
    }

    public static PageRequest of(Integer limit, Integer offset) {
        return new PageRequest(limit == null ? DEFAULT_LIMIT : limit, offset == null ? 0 : offset);
    }

    public PageMeta meta(long total) {
        return new PageMeta(total, limit, offset, null);
    }
}
