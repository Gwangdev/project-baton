import { describe, expect, it } from 'vitest'
import { GenerationPollTimeout, pollHandover } from '@/api/handoverPolling'
import type { HandoverDetail, HandoverStatus } from '@/api/types'

/**
 * 패키지 생성은 202로 접수되고 워커가 끝낸다. 화면은 상태가 REQUESTED/GENERATING을 벗어날 때까지 getHandover를
 * 다시 읽을 뿐이고, 상태를 스스로 진행시키지 않는다.
 */

const detail = (status: HandoverStatus, progressPercent = 0) => ({ id: 1, status, progressPercent }) as HandoverDetail

function scripted(...statuses: HandoverStatus[]) {
  const reads: HandoverStatus[] = []
  return {
    reads,
    load: async () => {
      const next = statuses.length > 1 ? statuses.shift()! : statuses[0]
      reads.push(next)
      return detail(next)
    },
  }
}

describe('pollHandover', () => {
  it('워커가 게시하거나 실패할 때까지 읽고, 읽을 때마다 알린다', async () => {
    const { load, reads } = scripted('REQUESTED', 'GENERATING', 'READY')
    const seen: HandoverStatus[] = []
    const waits: number[] = []
    const result = await pollHandover(load, {
      intervalMs: 1500,
      sleep: async (ms) => { waits.push(ms) },
      onUpdate: (h) => seen.push(h.status),
    })
    expect(result.status).toBe('READY')
    expect(reads).toEqual(['REQUESTED', 'GENERATING', 'READY'])
    expect(seen).toEqual(['REQUESTED', 'GENERATING', 'READY'])
    expect(waits).toEqual([1500, 1500])
  })

  it.each<HandoverStatus>(['FAILED', 'REVIEW_REQUESTED', 'COMPLETED'])('%s면 기다리지 않고 멈춘다', async (status) => {
    const { load } = scripted(status)
    const waits: number[] = []
    await expect(pollHandover(load, { sleep: async (ms) => { waits.push(ms) } })).resolves.toMatchObject({ status })
    expect(waits).toEqual([])
  })

  it('한도가 지나면 끝없이 폴링하지 않고 마지막 상태와 함께 멈춘다', async () => {
    const { load } = scripted('GENERATING')
    let now = 0
    const error = await pollHandover(load, {
      intervalMs: 1000,
      timeoutMs: 3000,
      now: () => now,
      sleep: async (ms) => { now += ms },
    }).catch((e) => e)
    expect(error).toBeInstanceOf(GenerationPollTimeout)
    expect(error.last.status).toBe('GENERATING')
  })

  it('화면을 떠나면 멈춘다', async () => {
    const { load, reads } = scripted('REQUESTED')
    const controller = new AbortController()
    const promise = pollHandover(load, {
      signal: controller.signal,
      sleep: async () => { controller.abort() },
    })
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
    expect(reads).toHaveLength(1)
  })
})
