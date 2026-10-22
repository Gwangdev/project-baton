import type {
  AccountActivityAction,
  ConnectionStatus,
  HandoverItemKind,
  HandoverStatus,
  ProjectRole,
  ProjectStatus,
  QaAnswerStatus,
  RecordTrustStatus,
  RecordType,
  SourceChannel,
  SourceHealth,
} from '@/api/types'

/**
 * 계약 enum의 한국어 표시 이름.
 *
 * 한곳에 모아 두어 같은 값이 화면마다 다르게 읽히지 않게 한다. 이름은 설명만 하고 동작을 막는 데 쓰지 않는다.
 */

export const RECORD_TYPE_LABEL: Record<RecordType, string> = {
  SCHEDULE: '일정',
  CHANGE_DECISION: '변경 결정',
  COMMITMENT: '고객 약속',
  BLOCKER: '장애요인',
  SCOPE_EXCEPTION: '범위 예외',
  OPEN_ISSUE: '미해결 결정',
}

export const TRUST_STATUS_LABEL: Record<RecordTrustStatus, string> = {
  AUTO_DRAFT: '확인 대기',
  CONFIRMED: '확인됨',
  CORRECTED: '수정 확정',
  DISMISSED: '제외됨',
}

/** 승인 뒤 후임자는 OUTGOING_OWNER가 되므로 현 담당자로 표시한다 (README §5.2). */
export const PROJECT_ROLE_LABEL: Record<ProjectRole, string> = {
  OUTGOING_OWNER: '담당자',
  INCOMING_OWNER: '신규 담당자',
  PROJECT_MANAGER: '프로젝트 관리자',
  MEMBER: '구성원',
}

export const PROJECT_STATUS_LABEL: Record<ProjectStatus, string> = {
  PLANNED: '계획',
  ON_TRACK: '정상',
  AT_RISK: '일정 주의',
  CLOSED: '종료',
}

export const HANDOVER_STATUS_LABEL: Record<HandoverStatus, string> = {
  REQUESTED: '요청 접수',
  GENERATING: '패키지 생성 중',
  READY: '열람 가능',
  REVIEW_REQUESTED: '보완 요청됨',
  COMPLETED: '완료',
  FAILED: '생성 실패',
}

export const ITEM_KIND_LABEL: Record<HandoverItemKind, string> = {
  OVERVIEW: '프로젝트 개요',
  KEY_DECISION: '핵심 결정',
  CHANGE: '변경 이력',
  COMMITMENT: '고객 약속',
  OPEN_RISK: '미해결 위험',
  SCHEDULE: '향후 일정',
  FOLLOW_UP: '후속 작업',
}

export const ITEM_KIND_ORDER: HandoverItemKind[] = [
  'OVERVIEW', 'KEY_DECISION', 'CHANGE', 'COMMITMENT', 'OPEN_RISK', 'SCHEDULE', 'FOLLOW_UP',
]

export const QA_STATUS_LABEL: Record<QaAnswerStatus, string> = {
  ANSWERED: '근거 있음',
  NO_EVIDENCE: '확인 가능한 기록 없음',
  FAILED: 'AI 응답 실패',
}

export const CONNECTION_STATUS_LABEL: Record<ConnectionStatus, string> = {
  ACTIVE: '연결됨',
  PAUSED: '일시 중지',
  DISCONNECTED: '연결 해제됨',
}

export const HEALTH_LABEL: Record<SourceHealth, string> = { NORMAL: '정상', DELAYED: '지연', FAILED: '실패' }

export const CHANNEL_LABEL: Record<SourceChannel, string> = {
  EMAIL: '메일',
  APPROVAL: '전자결재',
  MESSENGER: '메신저',
  MEETING_MINUTES: '회의록',
  CLOUD_PROJECT_FOLDER: '개인 업무 폴더',
}

export const formatTimestamp = (value: string | null | undefined): string =>
  value ? value.replace('T', ' ').slice(0, 16) : '--'

/** 업무일은 Asia/Seoul 기준 날짜다(README §4.2). 입력 기본값으로만 쓴다. */
export const seoulToday = (): string =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date())

/** 본인 계정 활동의 표시 이름. 발급·설정은 누가 했는지가 아니라 어떤 일이 있었는지만 말한다. */
export const ACCOUNT_ACTIVITY_LABEL: Record<AccountActivityAction, string> = {
  SIGN_IN: '로그인',
  ISSUE_CREDENTIAL_TOKEN: '설정 코드 발급(관리자)',
  SET_CREDENTIAL: '비밀번호 설정',
  RESET_SYSTEM_ADMIN_CREDENTIAL: '운영자가 비밀번호 재설정',
}
