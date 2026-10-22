/**
 * fetch 응답 본문을 읽는 최소한의 `text/event-stream` 판독기.
 *
 * `EventSource`는 C07이 요구하는 POST 본문과 CSRF 헤더를 보낼 수 없어서 `fetch`로 직접 읽는다. 서버가 보내는
 * 필드(`event`, `data`)만 해석하고, 주석과 모르는 필드는 SSE 형식이 정한 대로 무시한다. 청크 경계는 UTF-8 문자
 * 중간을 포함해 어디에나 올 수 있으므로 스트리밍 디코딩을 하고, 이벤트는 빈 줄에서만 자른다.
 */

export interface ServerSentEvent {
  event: string
  data: string
}

export async function* readServerSentEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<ServerSentEvent> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let event = 'message'
  let data: string[] = []
  let hasField = false

  const takeLine = (line: string): ServerSentEvent | null => {
    if (line === '') {
      const complete = hasField ? { event, data: data.join('\n') } : null
      event = 'message'
      data = []
      hasField = false
      return complete
    }
    if (line.startsWith(':')) return null
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    let value = colon < 0 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') {
      event = value
      hasField = true
    } else if (field === 'data') {
      data.push(value)
      hasField = true
    }
    return null
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true })
      let newline = buffer.search(/\r\n|\r|\n/)
      while (newline >= 0) {
        const line = buffer.slice(0, newline)
        const width = buffer.startsWith('\r\n', newline) ? 2 : 1
        buffer = buffer.slice(newline + width)
        const complete = takeLine(line)
        if (complete) yield complete
        newline = buffer.search(/\r\n|\r|\n/)
      }
      if (done) break
    }
    if (buffer !== '') takeLine(buffer)
    const last = takeLine('')
    if (last) yield last
  } finally {
    reader.releaseLock()
  }
}
