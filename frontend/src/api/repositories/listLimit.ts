import type { Page } from '../types'

/** 목록은 요청당 100건, offset은 10,000이 상한이다(C01). */
export const LIST_LIMIT = 100
const MAX_OFFSET = 10_000

export interface PageQuery {
  limit: number
  offset: number
}

/**
 * offset 목록을 끝까지 읽어 한 페이지로 합친다. 선택 목록처럼 모든 행이 있어야 하는 화면이 쓴다. 예전에는 첫 페이지만
 * 받아 101번째 사용자·프로젝트에 닿을 수 없었다. 서버의 offset 상한을 넘으면 거기서 멈춘다.
 */
export async function readAll<T>(fetch: (query: PageQuery) => Promise<Page<T>>): Promise<Page<T>> {
  const items: T[] = []
  let total = 0
  for (let offset = 0; offset <= MAX_OFFSET; ) {
    const page = await fetch({ limit: LIST_LIMIT, offset })
    items.push(...page.items)
    total = page.page.total
    offset += page.items.length
    if (page.items.length === 0 || offset >= total) break
  }
  return { items, page: { total, limit: items.length, offset: 0, nextCursor: null } }
}
