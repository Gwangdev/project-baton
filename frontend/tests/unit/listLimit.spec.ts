import { describe, expect, it, vi } from 'vitest'
import { readAll } from '@/api/repositories/listLimit'

describe('목록 끝까지 읽기', () => {
  it('total에 닿을 때까지 100건씩 이어 읽는다', async () => {
    const rows = Array.from({ length: 250 }, (_, i) => i)
    const fetch = vi.fn(async ({ limit, offset }: { limit: number; offset: number }) => ({
      items: rows.slice(offset, offset + limit),
      page: { total: rows.length, limit, offset, nextCursor: null },
    }))

    const all = await readAll(fetch)

    expect(all.items).toEqual(rows)
    expect(all.page.total).toBe(250)
    expect(fetch.mock.calls.map(([query]) => query.offset)).toEqual([0, 100, 200])
  })

  it('빈 페이지가 오면 멈춘다', async () => {
    const fetch = vi.fn(async () => ({ items: [], page: { total: 5, limit: 100, offset: 0, nextCursor: null } }))
    expect((await readAll(fetch)).items).toEqual([])
    expect(fetch).toHaveBeenCalledOnce()
  })
})
