import { describe, expect, it } from 'vitest'
import { resolveDataMode } from '@/api/dataMode'
import { ContractViolation, mapResponse, parseRouteId } from '@/api/ids'
import { ApiProblem } from '@/api/http'
import { GENERATION_FAILURE_TEXT, describeBlocker, describeProblem, errorLine } from '@/api/problemText'

describe('데이터 모드', () => {
  it('기본값은 API이고 demo는 명시했을 때만 받는다', () => {
    expect(resolveDataMode(undefined)).toBe('api')
    expect(resolveDataMode('')).toBe('api')
    expect(resolveDataMode('api')).toBe('api')
    expect(resolveDataMode('demo')).toBe('demo')
  })

  it('모르는 모드는 추측하지 않고 거부한다', () => {
    expect(() => resolveDataMode('Demo')).toThrow('Unsupported VITE_DATA_MODE')
  })
})

describe('ID 매퍼', () => {
  it('숫자 ID와 문자열 code는 그대로 둔다', () => {
    const body = { items: [{ id: 101, code: 'REC-101', projectId: 1, candidateRecordIds: [101, 102], sourceNoteCode: null }] }
    expect(mapResponse(body)).toBe(body)
  })

  it('불투명 trace ID와 평가 보기 키는 엔티티 ID로 보지 않는다', () => {
    expect(() => mapResponse({ traceId: 'a-b', questions: [{ id: 1, choices: [{ id: 'TYPE_schedule', text: 'Schedule' }] }] })).not.toThrow()
  })

  it.each([
    [{ id: '101' }, '$.id'],
    [{ id: 0 }, '$.id'],
    [{ items: [{ projectId: 2 ** 53 }] }, '$.items[0].projectId'],
    [{ candidateRecordIds: [1, -2] }, '$.candidateRecordIds'],
    [{ version: -1 }, '$.version'],
    [{ recordCode: 104 }, '$.recordCode'],
    [{ derivedRecordCodes: [1] }, '$.derivedRecordCodes'],
  ])('%j를 %s에서 거부한다', (body, path) => {
    const error = (() => { try { mapResponse(body) } catch (e) { return e } })() as ContractViolation
    expect(error).toBeInstanceOf(ContractViolation)
    expect(error.path).toBe(path)
  })

  it('주소창에서는 정규형 양의 정수만 받는다', () => {
    expect(parseRouteId('14')).toBe(14)
    for (const raw of ['REC-101', '0', '01', '1.5', '-3', '9007199254740993', undefined, ['1']]) {
      expect(parseRouteId(raw)).toBeNull()
    }
  })
})

describe('Problem 안내문', () => {
  const problem = (init: ConstructorParameters<typeof ApiProblem>[0]) => new ApiProblem(init)

  it('모든 안내문에 추적 ID를 남긴다', () => {
    expect(errorLine(problem({ status: 500, code: 'INTERNAL_ERROR', traceId: 't-1' }))).toContain('추적 ID t-1')
  })

  it('요청 한도와 대기열 포화에는 언제 다시 할지 알린다', () => {
    expect(describeProblem(problem({ status: 429, code: 'QA_RATE_LIMITED', retryAfterSeconds: 12 })).message).toContain('12초 후')
    expect(describeProblem(problem({ status: 503, code: 'QUEUE_FULL', retryAfterSeconds: 30 })).message).toContain('저장되지 않았습니다')
  })

  it('상태 충돌이 알린 규칙을 이름으로 보여준다', () => {
    const text = describeProblem(problem({ status: 409, code: 'INVALID_STATE', detail: 'REQUIRED_ITEMS_UNACKNOWLEDGED,CONFLICTS_UNRESOLVED' }))
    expect(text.message).toContain(describeBlocker('REQUIRED_ITEMS_UNACKNOWLEDGED'))
    expect(text.message).toContain(describeBlocker('CONFLICTS_UNRESOLVED'))
  })

  it('삭제를 막는 참조를 나열한다', () => {
    const text = describeProblem(problem({ status: 409, code: 'REFERENCE_CONFLICT', references: [{ resource: 'users', count: 3 }] }))
    expect(text.message).toContain('users 3건')
  })

  it('없는 자원인지 금지된 자원인지 드러내지 않는다', () => {
    expect(describeProblem(problem({ status: 404, code: 'PROJECT_NOT_FOUND' })).message).toContain('찾을 수 없거나 접근 권한이 없습니다')
  })

  it('범위가 너무 크거나 본문이 너무 큰 요청은 줄여서 다시 하라고 안내한다', () => {
    expect(describeProblem(problem({ status: 409, code: 'SCOPE_TOO_LARGE' })).message).toContain('범위를 좁혀')
    expect(describeProblem(problem({ status: 413, code: 'PAYLOAD_TOO_LARGE' })).message).toContain('너무 큽니다')
  })

  it('설정 코드가 틀리면 계정이 있는지 알리지 않고 새 코드를 요청하라고 안내한다', () => {
    const text = describeProblem(problem({ status: 400, code: 'INVALID_CREDENTIAL_TOKEN' })).message
    expect(text).toContain('설정 코드')
    expect(text).toContain('관리자')
    expect(text).not.toContain('계정이 없')
  })

  it('비밀번호 규칙을 어긴 입력은 규칙을 말해 준다', () => {
    const text = describeProblem(problem({ status: 400, code: 'INVALID_INPUT', errors: [{ field: 'newPassword', message: 'x' }] })).message
    expect(text).toContain('12자')
  })

  it('버전 정보가 없는 변경 요청은 서버가 보내는 IF_MATCH_REQUIRED로 안내한다', () => {
    expect(describeProblem(problem({ status: 428, code: 'IF_MATCH_REQUIRED' })).message).toContain('버전 정보 없이')
  })

  it('패키지 생성이 포기된 이유를 사용자의 말로 알린다', () => {
    expect(GENERATION_FAILURE_TEXT.MAX_ATTEMPTS_EXCEEDED).toContain('다시 시도')
    expect(GENERATION_FAILURE_TEXT.DEADLINE_EXCEEDED).toContain('시간')
    expect(GENERATION_FAILURE_TEXT.GENERATION_ABANDONED).toContain('중단')
  })

  it('버전 불일치를 다른 사람이 먼저 바꾼 것으로 설명한다', () => {
    expect(describeProblem(problem({ status: 412, code: 'STALE_VERSION' })).message).toContain('다른 사용자가 먼저 변경')
  })
})
