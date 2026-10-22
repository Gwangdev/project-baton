import { describe, expect, it, vi } from 'vitest'
import { ApiProblem, HttpClient } from '@/api/http'
import { ContractViolation } from '@/api/ids'

/**
 * C01 전송 규칙을 대본대로 답하는 fetch로 검사한다.
 *
 * 대본의 응답은 순서대로 소비되고 모든 요청은 기록된다. 그래서 서버가 받을 헤더를 그대로 단언할 수 있다.
 * 세션 쿠키 모드, CSRF 토큰, If-Match, 재사용된 Idempotency-Key.
 */

type Scripted = Response | Error | DOMException

interface Recorded {
  url: string
  method: string
  headers: Record<string, string>
  body: string | undefined
}

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })

const problem = (status: number, code: string, headers: Record<string, string> = {}, extra: object = {}) =>
  new Response(
    JSON.stringify({ type: 'about:blank', title: 'x', status, code, traceId: `trace-${code}`, detail: `d-${code}`, ...extra }),
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  )

const csrf = (token: string) => json(200, { token, headerName: 'X-CSRF-TOKEN' })

function client(script: Scripted[], options: { onUnauthenticated?: () => void } = {}) {
  const calls: Recorded[] = []
  const sleeps: number[] = []
  let keySeq = 0
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const headers: Record<string, string> = {}
    new Headers(init?.headers).forEach((v, k) => { headers[k] = v })
    calls.push({ url: String(input), method: init?.method ?? 'GET', headers, body: init?.body as string | undefined })
    expect(init?.credentials).toBe('same-origin')
    const next = script.shift()
    if (!next) throw new Error(`unexpected request ${init?.method} ${String(input)}`)
    if (!(next instanceof Response)) throw next
    return next
  })
  const http = new HttpClient({
    fetch: fetchImpl as unknown as typeof fetch,
    newKey: () => `00000000-0000-4000-8000-${String(++keySeq).padStart(12, '0')}`,
    sleep: async (ms) => { sleeps.push(ms) },
    onUnauthenticated: options.onUnauthenticated,
  })
  return { http, calls, sleeps, remaining: script }
}

describe('HttpClient 키 없는 명령', () => {
  it('idempotent:false 명령은 CSRF 토큰만 싣고 Idempotency-Key는 싣지 않는다', async () => {
    const { http, calls } = client([csrf('a'), json(201, { token: 't'.repeat(43), expiresAt: '2026-10-06T00:00:00Z' })])
    await http.command('POST', '/admin/users/7/credential-tokens', { idempotent: false })
    expect(calls[1].headers['x-csrf-token']).toBe('a')
    expect(calls[1].headers['idempotency-key']).toBeUndefined()
  })

  it('기본 명령은 여전히 Idempotency-Key를 싣는다', async () => {
    const { http, calls } = client([csrf('a'), json(200, {})])
    await http.command('POST', '/x', { body: {} })
    expect(calls[1].headers['idempotency-key']).toBeDefined()
  })
})

describe('HttpClient 조회', () => {
  it('세션 쿠키를 싣고, 반복 쿼리 파라미터를 만들고, ID를 검사한다', async () => {
    const { http, calls } = client([json(200, { items: [{ id: 101, code: 'REC-101', version: 0 }], page: { total: 1 } })])
    const body = await http.get<{ items: { id: number; code: string }[] }>('/projects/1/records', {
      trustStatus: ['CONFIRMED', 'CORRECTED'],
      cursor: undefined,
      limit: 20,
    })
    expect(body.items[0]).toEqual({ id: 101, code: 'REC-101', version: 0 })
    expect(calls[0].url).toBe('/api/v1/projects/1/records?trustStatus=CONFIRMED&trustStatus=CORRECTED&limit=20')
  })

  it('JavaScript 안전 범위를 넘는 ID는 반올림된 값을 쓰지 않고 거부한다', async () => {
    const { http } = client([
      new Response('{"id": 9007199254740993, "code": "REC-X"}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    ])
    await expect(http.get('/records/1')).rejects.toBeInstanceOf(ContractViolation)
  })

  it('숫자로 온 화면 code를 거부한다', async () => {
    const { http } = client([json(200, { id: 1, code: 101 })])
    await expect(http.get('/records/1')).rejects.toBeInstanceOf(ContractViolation)
  })

  it('401을 세션에 알리고 Problem도 던진다', async () => {
    const onUnauthenticated = vi.fn()
    const { http } = client([problem(401, 'UNAUTHENTICATED')], { onUnauthenticated })
    await expect(http.get('/me')).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED' })
    expect(onUnauthenticated).toHaveBeenCalledOnce()
  })
})

describe('HttpClient 명령', () => {
  it('CSRF 토큰을 먼저 받아 Idempotency-Key·If-Match와 함께 보낸다', async () => {
    const { http, calls } = client([csrf('t1'), json(200, { id: 7, version: 4 })])
    const result = await http.command('POST', '/records/7/review', { body: { action: 'CONFIRM' }, version: 3 })
    expect(result).toEqual({ id: 7, version: 4 })
    expect(calls[0].url).toBe('/api/v1/auth/csrf')
    const sent = calls[1]
    expect(sent.method).toBe('POST')
    expect(sent.headers['x-csrf-token']).toBe('t1')
    expect(sent.headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/)
    expect(sent.headers['if-match']).toBe('"3"')
    expect(sent.headers['content-type']).toBe('application/json')
    expect(sent.body).toBe('{"action":"CONFIRM"}')
  })

  it('토큰은 명령끼리 재사용하되 키는 명령마다 새로 만든다', async () => {
    const { http, calls } = client([csrf('t1'), json(201, { id: 1 }), json(201, { id: 2 })])
    await http.command('POST', '/projects/1/notes', { body: { body: 'a' } })
    await http.command('POST', '/projects/1/notes', { body: { body: 'b' } })
    expect(calls.filter((c) => c.url.endsWith('/auth/csrf'))).toHaveLength(1)
    expect(calls[1].headers['idempotency-key']).not.toBe(calls[2].headers['idempotency-key'])
  })

  it('CSRF 403이면 토큰을 한 번 다시 받아 같은 키로 재시도한다', async () => {
    const { http, calls } = client([csrf('old'), problem(403, 'CSRF_REQUIRED'), csrf('new'), json(201, { id: 3 })])
    await http.command('POST', '/projects/1/notes', { body: { body: 'a' } })
    const attempts = calls.filter((c) => c.method === 'POST')
    expect(attempts.map((c) => c.headers['x-csrf-token'])).toEqual(['old', 'new'])
    expect(attempts[0].headers['idempotency-key']).toBe(attempts[1].headers['idempotency-key'])
  })

  it('새로 받은 CSRF 토큰도 거절되면 반복하지 않는다', async () => {
    const { http } = client([csrf('a'), problem(403, 'CSRF_REQUIRED'), csrf('b'), problem(403, 'CSRF_REQUIRED')])
    await expect(http.command('POST', '/x', { body: {} })).rejects.toMatchObject({ status: 403, code: 'CSRF_REQUIRED' })
  })

  it('일반 권한 거부 403은 재시도하지 않는다', async () => {
    const { http, remaining } = client([csrf('a'), problem(403, 'ACCESS_DENIED'), csrf('unused')])
    await expect(http.command('POST', '/x', { body: {} })).rejects.toMatchObject({ code: 'ACCESS_DENIED' })
    expect(remaining).toHaveLength(1)
  })

  it('첫 요청이 처리 중이면 Retry-After만큼 기다렸다가 같은 키로 재전송한다', async () => {
    const { http, calls, sleeps } = client([
      csrf('a'),
      problem(409, 'REQUEST_IN_PROGRESS', { 'Retry-After': '1' }),
      json(202, { id: 9, version: 0, status: 'REQUESTED', generation: 1 }),
    ])
    const accepted = await http.command('POST', '/projects/1/handovers', { body: { x: 1 } })
    expect(accepted).toMatchObject({ id: 9 })
    expect(sleeps).toEqual([1000])
    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts[0].headers['idempotency-key']).toBe(posts[1].headers['idempotency-key'])
  })

  it('서버가 이미 적용했을 수 있으므로 네트워크 실패는 같은 키로 재시도한다', async () => {
    const { http, calls } = client([csrf('a'), new TypeError('network down'), json(201, { id: 4 })])
    await http.command('POST', '/projects/1/notes', { body: { body: 'a' } })
    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[0].headers['idempotency-key']).toBe(posts[1].headers['idempotency-key'])
  })

  it('응답을 받지 못한 CSRF 토큰 요청도 재시도한다', async () => {
    const { http, calls } = client([new TypeError('reset'), csrf('a'), json(201, { id: 5 })])
    await http.command('POST', '/projects/1/notes', { body: { body: 'a' } })
    expect(calls.map((c) => c.url)).toEqual(['/api/v1/auth/csrf', '/api/v1/auth/csrf', '/api/v1/projects/1/notes'])
  })

  it('취소된 요청은 재시도하지 않는다', async () => {
    const aborted = new DOMException('aborted', 'AbortError')
    const { http, remaining } = client([csrf('a'), aborted, json(201, {})])
    await expect(http.command('POST', '/x', { body: {} })).rejects.toBe(aborted)
    expect(remaining).toHaveLength(1)
  })

  it.each([
    [412, 'STALE_VERSION', {}],
    [428, 'IF_MATCH_REQUIRED', {}],
    [409, 'INVALID_STATE', {}],
  ])('%i %s는 재시도하지 않고 Problem으로 넘긴다', async (status, code) => {
    const { http, remaining } = client([csrf('a'), problem(status, code), json(200, {})])
    const error = await http.command('POST', '/x', { body: {}, version: 1 }).catch((e) => e)
    expect(error).toBeInstanceOf(ApiProblem)
    expect(error).toMatchObject({ status, code, traceId: `trace-${code}`, detail: `d-${code}` })
    expect(remaining).toHaveLength(1)
  })

  it.each([
    [429, 'QA_RATE_LIMITED', '12'],
    [503, 'QUEUE_FULL', '30'],
  ])('%i %s는 재시도하지 않고 Retry-After를 화면에 넘긴다', async (status, code, after) => {
    const { http, remaining } = client([csrf('a'), problem(status, code, { 'Retry-After': after }), json(200, {})])
    const error = await http.command('POST', '/x', { body: {} }).catch((e) => e)
    expect(error).toMatchObject({ status, code, retryAfterSeconds: Number(after) })
    expect(remaining).toHaveLength(1)
  })

  it('참조 충돌의 막는 참조 목록을 담는다', async () => {
    const { http } = client([
      csrf('a'),
      problem(409, 'REFERENCE_CONFLICT', {}, { references: [{ resource: 'work_records', count: 2 }] }),
    ])
    const error = (await http.command('DELETE', '/admin/users/3', { version: 0 }).catch((e) => e)) as ApiProblem
    expect(error.references).toEqual([{ resource: 'work_records', count: 2 }])
  })

  it('Problem이 아닌 실패도 상태 코드를 가진 Problem으로 바꾼다', async () => {
    const { http } = client([csrf('a'), new Response('<html>bad gateway</html>', { status: 502 })])
    await expect(http.command('POST', '/x', { body: {} })).rejects.toMatchObject({ status: 502, code: 'HTTP_502' })
  })

  it('204에는 undefined를 돌려준다', async () => {
    const { http } = client([csrf('a'), new Response(null, { status: 204 })])
    await expect(http.command('DELETE', '/admin/organizations/1', { version: 2 })).resolves.toBeUndefined()
  })
})

describe('HttpClient 로그인·로그아웃', () => {
  it('로그인은 Idempotency-Key 없이 보내고 끝나면 CSRF 토큰을 새로 받는다', async () => {
    const { http, calls } = client([
      csrf('before'),
      json(200, { user: { id: 2 }, memberships: [] }),
      csrf('after'),
      json(201, { id: 1 }),
    ])
    await http.signIn({ employeeNo: 'A-1', password: 'secret' })
    await http.command('POST', '/projects/1/notes', { body: { body: 'x' } })
    const signIn = calls[1]
    expect(signIn.url).toBe('/api/v1/auth/sign-in')
    expect(signIn.headers['x-csrf-token']).toBe('before')
    expect(signIn.headers['idempotency-key']).toBeUndefined()
    expect(calls[3].headers['x-csrf-token']).toBe('after')
  })

  it('로그아웃은 CSRF를 싣고 보낸 뒤 토큰을 버린다', async () => {
    const { http, calls } = client([csrf('a'), new Response(null, { status: 204 }), csrf('b'), json(200, {})])
    await http.signOut()
    await http.command('POST', '/x', { body: {} })
    expect(calls[1].headers['x-csrf-token']).toBe('a')
    expect(calls[3].headers['x-csrf-token']).toBe('b')
  })
})

describe('HttpClient 스트림', () => {
  it('CSRF·키·SSE Accept 헤더로 이벤트 스트림을 연다', async () => {
    const stream = new Response('event:meta\ndata:{}\n\n', { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
    const { http, calls } = client([csrf('a'), stream])
    const response = await http.openStream('/handovers/1/questions', { question: 'q' })
    expect(response.status).toBe(200)
    expect(calls[1].headers.accept).toBe('text/event-stream')
    expect(calls[1].headers['idempotency-key']).toBeDefined()
  })

  it('스트림 시작 전 거절은 Problem으로 바꾼다', async () => {
    const { http } = client([csrf('a'), problem(429, 'QA_RATE_LIMITED', { 'Retry-After': '5' })])
    await expect(http.openStream('/handovers/1/questions', { question: 'q' })).rejects.toMatchObject({
      status: 429,
      retryAfterSeconds: 5,
    })
  })
})
