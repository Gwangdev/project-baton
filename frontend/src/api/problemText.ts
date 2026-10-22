import { ApiProblem } from './http'
import { ContractViolation } from './ids'
import { GenerationPollTimeout } from './handoverPolling'

/**
 * 서버 응답의 한국어 안내문.
 *
 * 판단은 서버가 하고, 이 파일은 그 판단을 설명만 한다. 문구는 안정적인 Problem `code`(C01)로 고르고 없으면 HTTP
 * 상태로 대신한다. 사용자가 운영 담당자에게 전할 수 있도록 `traceId`는 항상 남긴다. 서버의 `detail`은 개발자용
 * 영문이라 사람이 알아야 할 code를 담은 경우(409 상태 충돌이 어긴 규칙을 나열할 때)에만 쓴다.
 */

export interface ErrorText {
  message: string
  traceId?: string
}

const BY_CODE: Record<string, string> = {
  UNAUTHENTICATED: '로그인이 만료되었습니다. 다시 로그인하십시오.',
  INVALID_CREDENTIALS: '사내 계정 또는 비밀번호가 올바르지 않습니다.',
  ACCOUNT_INACTIVE: '비활성화된 계정입니다. 시스템 관리자에게 문의하십시오.',
  ACCESS_DENIED: '이 작업을 수행할 권한이 없습니다.',
  CSRF_REQUIRED: '보안 토큰이 만료되었습니다. 화면을 새로 고친 뒤 다시 시도하십시오.',
  STALE_VERSION: '다른 사용자가 먼저 변경했습니다. 최신 내용을 다시 불러온 뒤 확인하고 다시 시도하십시오.',
  IF_MATCH_REQUIRED: '버전 정보 없이 변경을 요청했습니다. 화면을 새로 고친 뒤 다시 시도하십시오.',
  IDEMPOTENCY_KEY_REUSED: '같은 요청 키가 다른 내용으로 사용되었습니다. 다시 시도하십시오.',
  REQUEST_IN_PROGRESS: '이전 요청이 아직 처리 중입니다. 잠시 후 결과를 확인하십시오.',
  INVALID_INPUT: '입력값을 확인하십시오.',
  INVALID_CREDENTIAL_TOKEN: '설정 코드가 올바르지 않거나 만료되었습니다. 관리자에게 새 코드를 요청하십시오.',
  SCOPE_TOO_LARGE: '선택한 범위의 확정 기록이 5,000건을 넘어 패키지를 만들 수 없습니다. 기간이나 기록 유형을 줄여 범위를 좁혀 다시 요청하십시오.',
  PAYLOAD_TOO_LARGE: '요청 내용이 너무 큽니다(1 MiB 초과). 내용을 줄여 다시 시도하십시오.',
  LAST_SYSTEM_ADMIN: '마지막 활성 시스템 관리자는 비활성화·삭제·강등할 수 없습니다.',
  DUPLICATE_ORGANIZATION_NAME: '같은 이름의 조직이 이미 있습니다.',
  INTERNAL_ERROR: '서버 오류가 발생했습니다. 추적 ID를 운영 담당자에게 전달하십시오.',
}

const WITH_RETRY_AFTER: Record<string, string> = {
  AUTH_RATE_LIMITED: '로그인 시도가 너무 많습니다.',
  QA_RATE_LIMITED: '질문 한도(분당 6회, 동시 1회)를 넘었습니다.',
  QUEUE_FULL: '처리 대기열이 가득 차 요청을 받지 못했습니다. 요청은 저장되지 않았습니다.',
}

/** getHandover와 승인 명령이 함께 쓰는 승인 차단 사유 code (HandoverReadinessCheck). */
export const BLOCKER_TEXT: Record<string, string> = {
  HANDOVER_NOT_READY: '패키지가 검토 가능한 상태가 아닙니다',
  REQUIRED_ITEMS_UNACKNOWLEDGED: '신규 담당자가 확인하지 않은 필수 항목이 있습니다',
  CONFLICTS_UNRESOLVED: '최종 근거를 고르지 않은 상충 기록이 있습니다',
  RELATIONS_WITHDRAWN: '철회된 기록 관계가 있어 패키지를 다시 만들어야 합니다',
  FOLLOW_UP_UNASSIGNED: '담당자나 기한이 없는 후속 작업이 있습니다',
  ASSESSMENT_NOT_SUBMITTED: '업무 준비도 점검이 제출되지 않았습니다',
  ASSESSMENT_NOT_PASSED: '업무 준비도 최신 결과가 통과 기준에 못 미칩니다',
  EFFECTIVE_DATE_NOT_REACHED: '교체 적용일이 아직 오지 않았습니다',
  INCOMING_OWNER_INACTIVE: '신규 담당자의 프로젝트 배정이 유효하지 않습니다',
}

/** Q&A 스트림 실패 code(C07 error 이벤트)와 FAILED 답변에 저장되는 실패 code. */
export const QA_FAILURE_TEXT: Record<string, string> = {
  MODEL_TIMEOUT: 'AI 응답 시간이 초과되었습니다',
  MODEL_UNAVAILABLE: 'AI 모델에 연결하지 못했습니다',
  MODEL_BUSY: 'AI 모델이 다른 질문을 처리 중입니다',
  QA_BUSY: '질의응답 처리량이 가득 찼습니다',
  CITATION_INVALID: '검증되지 않은 근거가 포함되어 답변을 게시하지 않았습니다',
  ACCESS_REVOKED: '답변 중 접근 권한이 철회되었습니다',
  CLIENT_DISCONNECTED: '연결이 끊겨 답변을 완료하지 못했습니다',
  ANSWER_DISCARDED: '답변 저장에 실패해 게시하지 않았습니다',
  DEADLINE_EXCEEDED: '처리 기한이 지나 답변을 종료했습니다',
  STREAM_INTERRUPTED: '응답 스트림이 중간에 끊겼습니다. 질문 이력에서 결과를 확인하십시오',
  STREAM_PROTOCOL: '서버 응답 형식이 계약과 다릅니다',
}

/** handovers.failureReason에 서버가 남기는 code. 생성 작업이 실패한 이유를 사용자의 말로 옮긴다. */
export const GENERATION_FAILURE_TEXT: Record<string, string> = {
  MAX_ATTEMPTS_EXCEEDED: '패키지 생성을 세 번 시도했지만 끝내지 못했습니다. 잠시 후 다시 시도하십시오.',
  DEADLINE_EXCEEDED: '패키지 생성이 정해진 시간(2시간) 안에 끝나지 않았습니다. 다시 시도하십시오.',
  GENERATION_ABANDONED: '패키지 생성 작업이 실패 사유를 남기지 못하고 중단되었습니다. 다시 시도하십시오.',
  INSUFFICIENT_EVIDENCE: '확정된 근거가 부족해 준비도 문항 5개를 만들 수 없습니다. 기록을 더 확인한 뒤 재생성하십시오.',
}

export function describeBlocker(code: string): string {
  return BLOCKER_TEXT[code] ?? code
}

export function describeProblem(problem: ApiProblem): ErrorText {
  const traceId = problem.traceId || undefined
  const timed = WITH_RETRY_AFTER[problem.code]
  if (timed) {
    const wait = problem.retryAfterSeconds === null ? '' : ` ${problem.retryAfterSeconds}초 후 다시 시도하십시오.`
    return { message: `${timed}${wait}`, traceId }
  }
  if (problem.code === 'REFERENCE_CONFLICT') {
    const refs = problem.references.map((r) => `${r.resource} ${r.count}건`).join(', ')
    return { message: `다른 데이터(${refs})가 참조하고 있어 삭제할 수 없습니다. 비활성화하십시오.`, traceId }
  }
  if (problem.code === 'INVALID_STATE') {
    const blockers = (problem.detail ?? '').match(/[A-Z][A-Z_]{3,}/g)?.filter((c) => BLOCKER_TEXT[c]) ?? []
    const reason = blockers.length > 0 ? ` (${blockers.map(describeBlocker).join(' · ')})` : ''
    return { message: `현재 상태에서는 처리할 수 없습니다${reason}.`, traceId }
  }
  if (problem.code === 'INVALID_INPUT' && problem.errors.some((e) => e.field === 'newPassword')) {
    return { message: '비밀번호는 12자 이상 128자 이하이고 사내 계정과 달라야 합니다.', traceId }
  }
  if (problem.code === 'INVALID_INPUT' && problem.errors.length > 0) {
    return { message: `입력값을 확인하십시오: ${problem.errors.map((e) => e.field).join(', ')}`, traceId }
  }
  const known = BY_CODE[problem.code]
  if (known) return { message: known, traceId }
  if (problem.status === 404) return { message: '대상을 찾을 수 없거나 접근 권한이 없습니다.', traceId }
  if (problem.status >= 500) return { message: `서버가 요청을 처리하지 못했습니다 (HTTP ${problem.status}).`, traceId }
  return { message: `요청을 처리하지 못했습니다 (HTTP ${problem.status}).`, traceId }
}

export function describeError(error: unknown): ErrorText {
  if (error instanceof ApiProblem) return describeProblem(error)
  if (error instanceof ContractViolation) return { message: '서버 응답이 계약과 다릅니다. 운영 담당자에게 알려 주십시오.' }
  if (error instanceof GenerationPollTimeout) {
    return { message: '패키지 생성이 예상보다 오래 걸립니다. 잠시 후 새로 고쳐 상태를 확인하십시오.' }
  }
  if (error instanceof TypeError) return { message: '서버에 연결할 수 없습니다. 네트워크 상태를 확인하십시오.' }
  return { message: error instanceof Error ? error.message : String(error) }
}

/** 오류 문단 하나만 보여주는 화면용 한 줄 문구. */
export function errorLine(error: unknown): string {
  const text = describeError(error)
  return text.traceId ? `${text.message} (추적 ID ${text.traceId})` : text.message
}
