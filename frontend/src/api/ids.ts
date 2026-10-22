/**
 * 응답 ID 매퍼.
 *
 * C01은 int64 엔티티 ID를 1..2^53-1로 제한한다. 브라우저 `number`가 정확히 담을 수 있는 범위이기 때문이다.
 * `JSON.parse`는 더 큰 값에서 실패하지 않고 조용히 반올림하며, 반올림된 ID는 다른 행을 가리킨다. 그래서 모든
 * 응답은 화면에 닿기 전에 이 검사를 거친다. `id`이거나 `Id`/`Ids`로 끝나는 필드는 양의 안전정수, `version`은 0 이상의
 * 안전정수, 화면 code(`code`, `…Code`, `…Codes`)는 문자열이어야 한다. 어긋나면 계약 오류이므로 값을 고쳐 쓰지 않는다.
 *
 * 규칙에 맞지 않는 이름 두 개는 따로 둔다. `traceId`는 불투명 문자열이고, 평가의 `choices[].id`는
 * `TYPE_schedule` 같은 보기 키다.
 */

export class ContractViolation extends Error {
  readonly path: string
  constructor(path: string, message: string) {
    super(`API contract violation at ${path}: ${message}`)
    this.path = path
  }
}

const OPAQUE_STRING_KEYS = new Set(['traceId'])
const UNCHECKED_SUBTREES = new Set(['choices'])

function isEntityId(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function checkField(key: string, value: unknown, path: string): void {
  if (value === null || value === undefined) return
  if (OPAQUE_STRING_KEYS.has(key)) {
    if (typeof value !== 'string') throw new ContractViolation(path, 'expected an opaque string')
    return
  }
  if (key === 'version') {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
      throw new ContractViolation(path, 'version must be a non-negative safe integer')
    }
    return
  }
  if (key === 'id' || key.endsWith('Id')) {
    if (!isEntityId(value)) throw new ContractViolation(path, 'ID must be a positive safe integer')
    return
  }
  if (key.endsWith('Ids')) {
    if (!Array.isArray(value) || !value.every(isEntityId)) {
      throw new ContractViolation(path, 'IDs must be positive safe integers')
    }
    return
  }
  if (key === 'code' || key.endsWith('Code')) {
    if (typeof value !== 'string') throw new ContractViolation(path, 'display code must be a string')
    return
  }
  if (key.endsWith('Codes')) {
    if (!Array.isArray(value) || !value.every((v) => typeof v === 'string')) {
      throw new ContractViolation(path, 'display codes must be strings')
    }
  }
}

function walk(value: unknown, path: string): void {
  if (Array.isArray(value)) {
    value.forEach((item, index) => walk(item, `${path}[${index}]`))
    return
  }
  if (value === null || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    const childPath = `${path}.${key}`
    if (UNCHECKED_SUBTREES.has(key)) continue
    checkField(key, child, childPath)
    walk(child, childPath)
  }
}

/** 파싱한 응답 본문의 ID·code 필드를 검사하고 타입을 붙여 돌려준다. */
export function mapResponse<T>(body: unknown): T {
  walk(body, '$')
  return body as T
}

/** 경로 파라미터에서 온 ID를 요청 경로에 넣기 전에 검사한다. */
export function parseRouteId(raw: unknown): number | null {
  if (typeof raw !== 'string' || !/^[1-9][0-9]*$/.test(raw)) return null
  const value = Number(raw)
  return Number.isSafeInteger(value) ? value : null
}
