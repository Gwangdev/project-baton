import type { HandoverDetail, HandoverStatus } from './types'

/**
 * 인수인계를 다시 읽어 패키지 생성을 기다린다.
 *
 * requestHandover·retry·regenerate는 작업을 적재하면 202로 답하고(C01), 워커가 나중에 READY나 FAILED로 바꾼다.
 * 조회는 서버 상태를 바꾸지 않으므로(조회가 진행률을 올리면 결함이다) 반복 폴링이 안전하다. 간격은 워커
 * 주기 1초보다 충분히 길게 두고, 기본 한도는 README §8의 패키지 p95 2분에 여유를 더한 값이다. 한도가 지나면 끝없이
 * 기다리지 않고 마지막 상태를 보여준 뒤 사용자가 다시 확인하게 한다.
 */

const PENDING: ReadonlySet<HandoverStatus> = new Set(['REQUESTED', 'GENERATING'])

export class GenerationPollTimeout extends Error {
  readonly last: HandoverDetail
  constructor(last: HandoverDetail) {
    super('Package generation did not finish within the polling budget')
    this.last = last
  }
}

export interface PollOptions {
  intervalMs?: number
  timeoutMs?: number
  signal?: AbortSignal
  onUpdate?: (handover: HandoverDetail) => void
  sleep?: (ms: number) => Promise<void>
  now?: () => number
}

const abortError = () => new DOMException('Polling stopped', 'AbortError')

export function isGenerationPending(status: HandoverStatus): boolean {
  return PENDING.has(status)
}

export async function pollHandover(load: () => Promise<HandoverDetail>, options: PollOptions = {}): Promise<HandoverDetail> {
  const interval = options.intervalMs ?? 1500
  const budget = options.timeoutMs ?? 180_000
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)))
  const now = options.now ?? (() => Date.now())
  const started = now()
  for (;;) {
    if (options.signal?.aborted) throw abortError()
    const handover = await load()
    options.onUpdate?.(handover)
    if (!isGenerationPending(handover.status)) return handover
    if (now() - started >= budget) throw new GenerationPollTimeout(handover)
    await sleep(interval)
    if (options.signal?.aborted) throw abortError()
  }
}
