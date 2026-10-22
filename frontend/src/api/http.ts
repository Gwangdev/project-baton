import { mapResponse } from './ids'
import type { BlockingReference } from './types'

/**
 * C01 HTTP 전송 계층.
 *
 * 브라우저 세션은 `BATON_SESSION` 쿠키다. 그래서 모든 요청은 same-origin 자격증명으로 보내고, 세션에 관한 값을
 * 스크립트가 읽을 수 있는 저장소에 두지 않는다. 상태를 바꾸는 요청은 `GET /auth/csrf`에서 받은 토큰을
 * `X-CSRF-TOKEN`에 싣는다. 토큰은 필요할 때 받아 오고, 로그인·로그아웃 뒤에 새로 받으며, 서버가
 * `403 CSRF_REQUIRED`로 답하면 한 번 다시 받는다.
 *
 * 로그인·로그아웃을 뺀 모든 명령은 `Idempotency-Key`를 싣는다. 키 하나는 사용자 행동 하나에 속한다. 네트워크가
 * 끊기거나 서버가 같은 요청이 아직 처리 중이라고 답하면(`409 REQUEST_IN_PROGRESS` + `Retry-After`) 같은 키로 다시
 * 보낸다. 그러면 서버가 명령을 두 번 적용하지 않고 저장된 결과를 재전송한다. 그 밖의 응답(412 버전 불일치, 428,
 * 409 업무 충돌, 429, 503)은 `code`·`traceId`·`Retry-After`를 담은 `ApiProblem`으로 화면에 넘긴다. 다음에 무엇을
 * 할지는 사용자만 정할 수 있기 때문이다.
 */

export type QueryValue = string | number | boolean | readonly string[] | undefined | null
export type CommandMethod = 'POST' | 'PATCH' | 'DELETE'

export interface HttpClientOptions {
  baseUrl?: string
  fetch?: typeof fetch
  newKey?: () => string
  sleep?: (ms: number) => Promise<void>
  onUnauthenticated?: () => void
}

export interface CommandOptions {
  body?: unknown
  /** If-Match에 쓸 행 version. 계약이 버전을 요구하지 않는 명령에서만 생략한다. */
  version?: number
  signal?: AbortSignal
  /**
   * false이면 Idempotency-Key를 싣지 않는다. 계약이 키를 받지 않는 두 명령(설정 코드 발급과 설정)만 쓴다. 발급 응답이
   * 평문 코드라서 서버가 그 응답을 저장해 재전송할 수 없기 때문이다.
   */
  idempotent?: boolean
}

interface FieldError {
  field: string
  message: string
}

export class ApiProblem extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string
  readonly detail: string | null
  readonly errors: FieldError[]
  readonly references: BlockingReference[]
  readonly retryAfterSeconds: number | null

  constructor(init: {
    status: number
    code: string
    traceId?: string
    detail?: string | null
    errors?: FieldError[]
    references?: BlockingReference[]
    retryAfterSeconds?: number | null
  }) {
    super(`${init.status} ${init.code}`)
    this.status = init.status
    this.code = init.code
    this.traceId = init.traceId ?? ''
    this.detail = init.detail ?? null
    this.errors = init.errors ?? []
    this.references = init.references ?? []
    this.retryAfterSeconds = init.retryAfterSeconds ?? null
  }
}

/** 네트워크 실패는 같은 키로 이 횟수만큼 더 보낸다. */
const NETWORK_RETRIES = 2
/** 처리 중인 명령의 재전송은 서버의 Retry-After만큼 기다렸다가 이 횟수까지 다시 보낸다. */
const IN_PROGRESS_RETRIES = 3

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

function parseRetryAfter(value: string | null): number | null {
  if (!value) return null
  const seconds = Number(value)
  if (Number.isFinite(seconds) && seconds >= 0) return seconds
  const date = Date.parse(value)
  return Number.isNaN(date) ? null : Math.max(0, Math.ceil((date - Date.now()) / 1000))
}

async function readProblem(response: Response): Promise<ApiProblem> {
  const retryAfterSeconds = parseRetryAfter(response.headers.get('Retry-After'))
  const type = response.headers.get('Content-Type') ?? ''
  if (type.includes('json')) {
    try {
      const body = (await response.json()) as Record<string, unknown>
      return new ApiProblem({
        status: response.status,
        code: typeof body.code === 'string' ? body.code : `HTTP_${response.status}`,
        traceId: typeof body.traceId === 'string' ? body.traceId : '',
        detail: typeof body.detail === 'string' ? body.detail : null,
        errors: Array.isArray(body.errors) ? (body.errors as FieldError[]) : [],
        references: Array.isArray(body.references) ? (body.references as BlockingReference[]) : [],
        retryAfterSeconds,
      })
    } catch {
      // 프록시나 컨테이너가 잘린 본문으로 답할 수 있다. 그래도 상태 코드만으로 의미가 있다.
    }
  }
  return new ApiProblem({ status: response.status, code: `HTTP_${response.status}`, retryAfterSeconds })
}

export class HttpClient {
  private readonly baseUrl: string
  private readonly fetchImpl: typeof fetch
  private readonly newKey: () => string
  private readonly sleep: (ms: number) => Promise<void>
  private readonly onUnauthenticated?: () => void
  private csrf: { token: string; headerName: string } | null = null

  constructor(options: HttpClientOptions = {}) {
    this.baseUrl = options.baseUrl ?? '/api/v1'
    this.fetchImpl = options.fetch ?? ((input, init) => globalThis.fetch(input, init))
    this.newKey = options.newKey ?? (() => crypto.randomUUID())
    this.sleep = options.sleep ?? defaultSleep
    this.onUnauthenticated = options.onUnauthenticated
  }

  async get<T>(path: string, query: Record<string, QueryValue> = {}, signal?: AbortSignal): Promise<T> {
    const response = await this.exchange(() => ({ method: 'GET', headers: { Accept: 'application/json' }, signal }), `${path}${toQuery(query)}`)
    return this.read<T>(response)
  }

  async command<T>(method: CommandMethod, path: string, options: CommandOptions = {}): Promise<T> {
    const idempotent = options.idempotent !== false
    const key = idempotent ? this.newKey() : null
    const response = await this.exchange(
      () => this.commandInit(method, key, options, 'application/json'),
      path,
      { csrf: true, replay: idempotent },
    )
    return this.read<T>(response)
  }

  /** C07 이벤트 스트림을 연다. 서버가 질문을 접수하기 전의 거절은 여기서 Problem으로 던진다. */
  async openStream(path: string, body: unknown, signal?: AbortSignal): Promise<Response> {
    const key = this.newKey()
    return this.exchange(() => this.commandInit('POST', key, { body, signal }, 'text/event-stream'), path, {
      csrf: true,
      replay: true,
    })
  }

  async signIn<T>(credentials: { employeeNo: string; password: string }): Promise<T> {
    const response = await this.exchange(
      () => this.authInit(JSON.stringify(credentials)),
      '/auth/sign-in',
      { csrf: true, replay: false },
    )
    const user = await this.read<T>(response)
    // 세션이 바뀌었다. README §4.1에 따라 새 세션의 토큰을 다시 받는다.
    this.csrf = null
    return user
  }

  async signOut(): Promise<void> {
    try {
      await this.exchange(() => this.authInit(undefined), '/auth/sign-out', { csrf: true, replay: false })
    } finally {
      this.csrf = null
    }
  }

  private commandInit(method: CommandMethod, key: string | null, options: CommandOptions, accept: string): RequestInit {
    const headers: Record<string, string> = { Accept: accept }
    if (key !== null) headers['Idempotency-Key'] = key
    if (options.version !== undefined) headers['If-Match'] = `"${options.version}"`
    if (options.body !== undefined) headers['Content-Type'] = 'application/json'
    return {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    }
  }

  private authInit(body: string | undefined): RequestInit {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    return { method: 'POST', headers, body }
  }

  private async ensureCsrf(): Promise<{ token: string; headerName: string }> {
    if (this.csrf) return this.csrf
    const response = await this.fetchImpl(`${this.baseUrl}/auth/csrf`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
    })
    if (!response.ok) throw await readProblem(response)
    const body = (await response.json()) as { token: string; headerName: string }
    this.csrf = { token: body.token, headerName: body.headerName }
    return this.csrf
  }

  /**
   * 논리적 요청 하나를 보낸다. 본문 스트림은 두 번 보낼 수 없으므로 `init`은 시도마다 새로 만들지만, 호출자가
   * 정한 Idempotency-Key는 그대로 둔다.
   */
  private async exchange(
    init: () => RequestInit,
    path: string,
    mode: { csrf?: boolean; replay?: boolean } = {},
  ): Promise<Response> {
    let csrfRenewed = false
    let networkRetries = 0
    let inProgressRetries = 0
    for (;;) {
      const request = init()
      const headers = new Headers(request.headers)
      let response: Response
      try {
        if (mode.csrf) {
          const csrf = await this.ensureCsrf()
          headers.set(csrf.headerName, csrf.token)
        }
        response = await this.fetchImpl(`${this.baseUrl}${path}`, { ...request, headers, credentials: 'same-origin' })
      } catch (error) {
        // fetch는 HTTP 응답을 전혀 받지 못했을 때만 TypeError로 실패한다. CSRF 토큰 요청도 마찬가지다.
        if (!(error instanceof TypeError) || networkRetries >= NETWORK_RETRIES) throw error
        networkRetries += 1
        await this.sleep(500 * networkRetries)
        continue
      }
      if (response.ok) return response

      const problem = await readProblem(response)
      if (problem.status === 403 && problem.code === 'CSRF_REQUIRED' && mode.csrf && !csrfRenewed) {
        csrfRenewed = true
        this.csrf = null
        continue
      }
      if (problem.status === 409 && problem.code === 'REQUEST_IN_PROGRESS' && mode.replay
        && inProgressRetries < IN_PROGRESS_RETRIES) {
        inProgressRetries += 1
        await this.sleep((problem.retryAfterSeconds ?? 1) * 1000)
        continue
      }
      if (problem.status === 401) this.onUnauthenticated?.()
      throw problem
    }
  }

  private async read<T>(response: Response): Promise<T> {
    if (response.status === 204) return undefined as T
    const text = await response.text()
    return mapResponse<T>(text === '' ? undefined : JSON.parse(text))
  }
}

function toQuery(query: Record<string, QueryValue>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    if (Array.isArray(value)) value.forEach((v) => params.append(key, v))
    else params.append(key, String(value))
  }
  const text = params.toString()
  return text ? `?${text}` : ''
}
