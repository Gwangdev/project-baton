import { describe, expect, it } from 'vitest'
import { readServerSentEvents } from '@/api/sse'
import { consumeQaStream } from '@/api/qaStream'

/**
 * 브라우저가 받는 그대로의 C07 스트림. 청크 경계는 아무 데나 오고, 순서는 meta → evidence → delta* → done|error다.
 * delta는 임시 문장이고, 저장된 답변은 `done`에만 있다.
 */

function body(...chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream({
    start(controller) {
      chunks.forEach((c) => controller.enqueue(encoder.encode(c)))
      controller.close()
    },
  })
}

const answer = {
  id: 12,
  role: 'ASSISTANT',
  content: 'REC-104: 계정 발급 지연.',
  createdAt: '2026-09-27T00:00:00Z',
  answerStatus: 'ANSWERED',
  citations: [{ recordId: 104, recordCode: 'REC-104', recordTitle: 't', sourceName: 's', trustStatusAtAnswer: 'CONFIRMED', isPrimary: true }],
}

describe('readServerSentEvents', () => {
  it('청크로 나뉜 이벤트를 다시 잇고 주석은 무시한다', async () => {
    const events = []
    for await (const e of readServerSentEvents(body('event:me', 'ta\ndata:{"questionId":1}\n', '\n: keep-alive\n\nevent: delta\r\ndata: {"text":"a"}\r\n\r\n'))) {
      events.push(e)
    }
    expect(events).toEqual([
      { event: 'meta', data: '{"questionId":1}' },
      { event: 'delta', data: '{"text":"a"}' },
    ])
  })

  it('여러 줄 data는 줄바꿈으로 잇고, 빈 줄 없는 마지막 이벤트도 내보낸다', async () => {
    const events = []
    for await (const e of readServerSentEvents(body('data:line1\ndata:line2'))) events.push(e)
    expect(events).toEqual([{ event: 'message', data: 'line1\nline2' }])
  })
})

describe('consumeQaStream', () => {
  const stream = (...events: [string, unknown][]) =>
    body(...events.map(([name, data]) => `event:${name}\ndata:${JSON.stringify(data)}\n\n`))

  it('meta·evidence·임시 delta를 알린 뒤 저장된 답변으로 끝난다', async () => {
    const seen: string[] = []
    const result = await consumeQaStream(
      stream(
        ['meta', { questionId: 11, generation: 1, traceId: 'tr' }],
        ['evidence', { citations: answer.citations }],
        ['delta', { text: '첫 문장.' }],
        ['delta', { text: '둘째 문장.' }],
        ['done', { answer }],
      ),
      {
        onMeta: (m) => seen.push(`meta:${m.questionId}`),
        onEvidence: (c) => seen.push(`evidence:${c.length}`),
        onDelta: (t) => seen.push(`delta:${t}`),
      },
    )
    expect(seen).toEqual(['meta:11', 'evidence:1', 'delta:첫 문장.', 'delta:둘째 문장.'])
    expect(result).toEqual({ kind: 'done', answer })
  })

  it('NO_EVIDENCE는 delta 없이 끝난다', async () => {
    const noEvidence = { ...answer, answerStatus: 'NO_EVIDENCE', citations: [], content: '' }
    const result = await consumeQaStream(
      stream(['meta', { questionId: 1, generation: 1, traceId: 't' }], ['evidence', { citations: [] }], ['done', { answer: noEvidence }]),
      {},
    )
    expect(result).toMatchObject({ kind: 'done', answer: { answerStatus: 'NO_EVIDENCE' } })
  })

  it('done 하나뿐인 재전송도 완료된 답변으로 받는다', async () => {
    const result = await consumeQaStream(stream(['done', { answer }]), {})
    expect(result).toEqual({ kind: 'done', answer })
  })

  it('error 이벤트와 재시도 여부로 끝나며, 앞선 delta는 임시로 남는다', async () => {
    const deltas: string[] = []
    const result = await consumeQaStream(
      stream(
        ['meta', { questionId: 1, generation: 1, traceId: 'tr-9' }],
        ['evidence', { citations: [] }],
        ['delta', { text: '임시' }],
        ['error', { code: 'MODEL_TIMEOUT', retryable: true, traceId: 'tr-9' }],
      ),
      { onDelta: (t) => deltas.push(t) },
    )
    expect(deltas).toEqual(['임시'])
    expect(result).toEqual({ kind: 'error', code: 'MODEL_TIMEOUT', retryable: true, traceId: 'tr-9' })
  })

  it('done이나 error 없이 닫힌 스트림은 중단으로 알린다', async () => {
    const result = await consumeQaStream(stream(['meta', { questionId: 1, generation: 1, traceId: 'tr-2' }]), {})
    expect(result).toEqual({ kind: 'error', code: 'STREAM_INTERRUPTED', retryable: true, traceId: 'tr-2' })
  })

  it('계약 순서를 어긴 이벤트는 보여주지 않고 거부한다', async () => {
    const result = await consumeQaStream(
      stream(['delta', { text: 'no meta yet' }], ['done', { answer }]),
      { onDelta: () => { throw new Error('must not be called') } },
    )
    expect(result).toMatchObject({ kind: 'error', code: 'STREAM_PROTOCOL', retryable: false })
  })

  it('최종 답변 안의 ID도 검사한다', async () => {
    const result = await consumeQaStream(stream(['done', { answer: { ...answer, id: 2 ** 53 + 2 } }]), {})
    expect(result).toMatchObject({ kind: 'error', code: 'STREAM_PROTOCOL' })
  })
})
