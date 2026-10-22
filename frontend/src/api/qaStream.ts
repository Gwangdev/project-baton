import { ContractViolation, mapResponse } from './ids'
import { readServerSentEvents } from './sse'
import type { QaAnswer, QaCitation } from './types'

/**
 * C07 답변 스트림 규약: `meta → evidence → delta 0..N → done | error`.
 *
 * `delta`는 임시 초안으로만 보여준다. 서버는 생성과 인용 검증을 마친 뒤 보내지만, 대화 기록이 되는 것은 저장된
 * `QaAnswer`를 담은 `done`이 도착했을 때다. 화면은 초안을 그 답변으로 바꾸고, `error`면 초안을 버려야 한다.
 * 같은 Idempotency-Key의 재전송은 `done` 하나로 오므로 `done`은 어느 시점에 와도 받는다. 그 밖에 순서가 어긋난
 * 이벤트는 규약 위반이므로 화면에 보이지 않는다.
 */

export interface QaStreamMeta {
  questionId: number
  generation: number
  traceId: string
}

export interface QaStreamHandlers {
  onMeta?: (meta: QaStreamMeta) => void
  onEvidence?: (citations: QaCitation[]) => void
  onDelta?: (text: string) => void
}

export type QaStreamResult =
  | { kind: 'done'; answer: QaAnswer }
  | { kind: 'error'; code: string; retryable: boolean; traceId: string }

type Stage = 'start' | 'meta' | 'evidence'

export async function consumeQaStream(body: ReadableStream<Uint8Array>, handlers: QaStreamHandlers): Promise<QaStreamResult> {
  let stage: Stage = 'start'
  let traceId = ''
  const violation = (): QaStreamResult => ({ kind: 'error', code: 'STREAM_PROTOCOL', retryable: false, traceId })

  try {
    for await (const { event, data } of readServerSentEvents(body)) {
      const payload = mapResponse<Record<string, unknown>>(JSON.parse(data))
      if (event === 'done') {
        return { kind: 'done', answer: payload.answer as QaAnswer }
      }
      if (event === 'error') {
        return {
          kind: 'error',
          code: String(payload.code),
          retryable: payload.retryable === true,
          traceId: typeof payload.traceId === 'string' ? payload.traceId : traceId,
        }
      }
      if (event === 'meta' && stage === 'start') {
        const meta = payload as unknown as QaStreamMeta
        traceId = meta.traceId
        stage = 'meta'
        handlers.onMeta?.(meta)
      } else if (event === 'evidence' && stage === 'meta') {
        stage = 'evidence'
        handlers.onEvidence?.((payload.citations as QaCitation[]) ?? [])
      } else if (event === 'delta' && stage === 'evidence') {
        handlers.onDelta?.(String(payload.text))
      } else {
        return violation()
      }
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    if (error instanceof SyntaxError || error instanceof ContractViolation) {
      return violation()
    }
    // 스트림 도중 연결이 끊겼다. 서버에서는 질문이 끝날 수 있으므로 결과는 질문 이력에서 확인한다.
    return { kind: 'error', code: 'STREAM_INTERRUPTED', retryable: true, traceId }
  }
  return { kind: 'error', code: 'STREAM_INTERRUPTED', retryable: true, traceId }
}
