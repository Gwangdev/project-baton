import type {
  CollectedFile,
  PersonalNote,
  Project,
  RecordRelation,
  RecordTrustStatus,
  RecordType,
  SourceConnectionDetail,
  User,
} from '@/api/types'

/**
 * 1단계 발표용 가상 데이터. VITE_DATA_MODE=demo일 때만 불러온다.
 *
 * 값은 `DEMO_SCENARIO.md`에서 왔고 모두 가상이다. 데모와 실제 백엔드가 같은 화면을 쓰도록 API DTO 형태로 적었다.
 * 숫자 ID는 임의지만 고정이고, 화면 code는 시나리오 문서(REC-101…, NOTE-001)와 같다.
 */

export const DEMO_PROJECT_ID = 14

export const PROJECT: Project = {
  id: DEMO_PROJECT_ID,
  version: 0,
  code: 'PJT-2026-014',
  name: 'B리테일 옴니채널 고객지원 플랫폼 구축',
  deliveringCompany: 'A테크서비스',
  clientCompany: 'B리테일',
  startedOn: '2026-06-01',
  endsOn: '2026-11-30',
  phase: '통합 테스트 준비',
  status: 'AT_RISK',
  isActive: true,
}

export interface DemoAccount {
  user: User
  projectRole: 'OUTGOING_OWNER' | 'INCOMING_OWNER' | 'PROJECT_MANAGER' | 'MEMBER'
  /** 로그인 화면 데모 계정 안내에 보이는 역할 이름. */
  roleLabel: string
}

const account = (
  id: number, employeeNo: string, name: string, title: string, organizationId: number, organizationName: string,
  systemRole: User['systemRole'], projectRole: DemoAccount['projectRole'], roleLabel: string,
): DemoAccount => ({
  user: {
    id, version: 0, employeeNo, name, title, email: `${employeeNo.toLowerCase()}@example.test`, systemRole,
    isActive: true, organizationId, organizationName, authProvider: 'LOCAL', loginEnabled: true,
  },
  projectRole,
  roleLabel,
})

/** 발표 동선은 기존 담당자 → 관리자 → 신규 담당자 → 관리자 순서다. 비밀번호는 demoCredentials.ts에만 있다. */
export const ACCOUNTS: DemoAccount[] = [
  account(1, 'A-10421', '김현우', '책임', 1, 'A테크서비스 고객지원사업부', 'USER', 'OUTGOING_OWNER', '기존 담당자'),
  account(2, 'A-10188', '박지민', 'PL', 1, 'A테크서비스 고객지원사업부', 'USER', 'PROJECT_MANAGER', '프로젝트 관리자'),
  account(3, 'A-10733', '이서연', '매니저', 1, 'A테크서비스 고객지원사업부', 'USER', 'INCOMING_OWNER', '신규 담당자'),
  account(4, 'A-10002', '최도윤', '매니저', 2, 'A테크서비스 정보시스템실', 'SYSTEM_ADMIN', 'MEMBER', '시스템 관리자'),
]

export const ORGANIZATIONS = [
  { id: 1, version: 0, name: 'A테크서비스 고객지원사업부', isActive: true },
  { id: 2, version: 0, name: 'A테크서비스 정보시스템실', isActive: true },
  // 오타로 만들어 아무도 참조하지 않는 조직. 이런 행은 지울 수 있다는 것을 관리 화면에서 보여주기 위해 둔다.
  { id: 3, version: 0, name: 'A테크서비스 고객지원사어부', isActive: true },
]

const connection = (
  id: number, channel: SourceConnectionDetail['channel'], displayName: string, externalRef: string,
  ownerUserId: number | null, extra: Partial<SourceConnectionDetail> = {},
): SourceConnectionDetail => ({
  id, version: 0, projectId: DEMO_PROJECT_ID, ownerUserId, channel, displayName, externalRef, status: 'ACTIVE',
  health: 'NORMAL', lastCollectedAt: '2026-09-11T08:40:00+09:00', lastStatus: 'SUCCEEDED', lastCollectedCount: 4,
  lastFailedCount: 0, lastMessage: null, collectedFileCount: 0, draftCandidateCount: 0, ...extra,
})

/** 메신저 지연은 의도한 상태다. 수집에 문제가 생겨도 기록 조회는 계속된다는 것을 보여준다 (FR-20). */
export const CONNECTIONS: SourceConnectionDetail[] = [
  connection(11, 'EMAIL', '사내 메일 — 고객지원사업부', 'mail://teams/cs-delivery', null, { lastCollectedCount: 12 }),
  connection(12, 'APPROVAL', '전자결재 — 변경관리', 'approval://change-mgmt', null, { lastCollectedCount: 3 }),
  connection(13, 'MESSENGER', '프로젝트 메신저 — PJT-2026-014', 'chat://rooms/pjt-2026-014', null, {
    health: 'DELAYED', lastStatus: 'DELAYED', lastCollectedCount: 7, lastFailedCount: 1,
    lastCollectedAt: '2026-09-11T06:10:00+09:00',
    lastMessage: '메시지 1건의 첨부 수집이 지연되고 있음. 기존 기록 조회는 영향 없음',
  }),
  connection(14, 'MEETING_MINUTES', '회의록 저장소 — 주간·리스크', 'docs://minutes/pjt-2026-014', null),
  connection(15, 'CLOUD_PROJECT_FOLDER', '내 업무 폴더 — B리테일 통합테스트', 'drive://approved/kim/pjt-2026-014', 1, {
    lastCollectedAt: '2026-09-11T09:05:00+09:00', lastCollectedCount: 3, collectedFileCount: 3,
  }),
  connection(16, 'MEETING_MINUTES', '회의록 — 대상 경로 오입력', 'drive://approved/pjt-2026-14', null, {
    status: 'DISCONNECTED', lastCollectedAt: null, lastStatus: null, lastCollectedCount: 0,
    lastMessage: '경로를 찾을 수 없어 한 번도 수집하지 않았다.',
  }),
]

export const COLLECTED_FILES: (CollectedFile & { connectionId: number })[] = [
  { id: 201, connectionId: 15, displayName: '통합테스트_시나리오_v3.xlsx', collectedAt: '2026-09-11T09:05:00+09:00',
    derivedRecordCodes: [], processingStatus: 'SUCCEEDED' },
  { id: 202, connectionId: 15, displayName: 'CRM연동_이슈정리.docx', collectedAt: '2026-09-11T09:05:00+09:00',
    derivedRecordCodes: ['REC-106'], processingStatus: 'SUCCEEDED' },
  { id: 203, connectionId: 15, displayName: '고객보고_9월2주.pptx', collectedAt: '2026-09-10T18:22:00+09:00',
    derivedRecordCodes: [], processingStatus: 'FAILED', failureCode: 'MODEL_UNAVAILABLE' },
]

/** 원본은 요약을 공유한 뒤에도 다른 사람에게 보이지 않는다 (D10). */
export const NOTES: (PersonalNote & { authorId: number })[] = [
  {
    id: 1,
    code: 'NOTE-001',
    projectId: DEMO_PROJECT_ID,
    authorId: 1,
    body: [
      '9/10 15:20 B리테일 정과장 통화.',
      '대량 상담이력 다운로드는 1차 오픈에서 빼기로 구두 합의. 성능 부담 우려.',
      '2차 개선으로 넘기되 시기는 아직 미정. 정과장이 내부 보고 후 다시 알려주기로.',
      '(개인 메모) 이 건은 회의록에 안 남았음. 내가 기억하고 있어야 함.',
    ].join('\n'),
    createdAt: '2026-09-10T15:40:00+09:00',
    sharedRecordCodes: [],
  },
]

export interface DemoRecordSeed {
  id: number
  recordType: RecordType
  title: string
  summary: string
  originalExcerpt: string
  occurredOn: string
  authorName: string
  ownerName: string | null
  dueOn: string | null
  trustStatus: RecordTrustStatus
  connectionId: number | null
  confirmedByName: string | null
  confirmedAt: string | null
}

export const RECORDS: DemoRecordSeed[] = [
  {
    id: 101, recordType: 'SCHEDULE', title: '1차 오픈 목표일 10월 15일', summary: '1차 오픈 목표일은 10월 15일이다.',
    originalExcerpt: '[고객 이메일 2026-08-28] ... 협의드린 대로 1차 오픈 목표일은 10월 15일로 잡고 진행 부탁드립니다.',
    occurredOn: '2026-08-28', authorName: 'B리테일 정과장', ownerName: null, dueOn: '2026-10-15',
    trustStatus: 'CONFIRMED', connectionId: 11, confirmedByName: '김현우', confirmedAt: '2026-08-29T10:12:00+09:00',
  },
  {
    id: 102, recordType: 'CHANGE_DECISION', title: '최종 오픈일 10월 22일로 변경',
    summary: '보안성 검토 기간을 반영해 최종 오픈일을 10월 22일로 변경한다.',
    originalExcerpt: '[변경승인 결재문서 2026-09-05] 변경 사유: 보안성 검토 기간 2주 반영. 변경 후 오픈일: 2026-10-22. 승인: 박지민 PL',
    occurredOn: '2026-09-05', authorName: '박지민', ownerName: '박지민', dueOn: '2026-10-22',
    trustStatus: 'CONFIRMED', connectionId: 12, confirmedByName: '김현우', confirmedAt: '2026-09-05T17:30:00+09:00',
  },
  {
    id: 103, recordType: 'COMMITMENT', title: '통합 시연 버전 9월 18일 제공',
    summary: '통합 시연 버전을 9월 18일까지 고객에게 제공한다.',
    originalExcerpt: '[주간회의록 2026-09-08] 논의 3. 고객사에서 통합 시연 버전 요청. 9월 18일까지 제공하기로 함. 담당 협의 필요.',
    occurredOn: '2026-09-08', authorName: '김현우', ownerName: null, dueOn: null,
    trustStatus: 'AUTO_DRAFT', connectionId: 14, confirmedByName: null, confirmedAt: null,
  },
  {
    id: 104, recordType: 'BLOCKER', title: 'CRM 테스트 계정 발급 지연',
    summary: 'B리테일 CRM 테스트 계정 발급이 지연 중이며 고객 인프라팀 확인이 필요하다.',
    originalExcerpt: '[프로젝트 메신저 2026-09-09] 김현우: CRM 테스트 계정 아직입니다. 인프라팀 회신 없음. 통합테스트 일정에 영향 있습니다.',
    occurredOn: '2026-09-09', authorName: '김현우', ownerName: '김현우', dueOn: null,
    trustStatus: 'CONFIRMED', connectionId: 13, confirmedByName: '김현우', confirmedAt: '2026-09-09T14:05:00+09:00',
  },
  {
    id: 106, recordType: 'OPEN_ISSUE', title: '대체 테스트 데이터 사용 여부 결정 필요',
    summary: 'CRM 테스트 계정이 9월 16일까지 발급되지 않으면 대체 테스트 데이터를 사용할지 결정해야 한다.',
    originalExcerpt: '[리스크 회의록 2026-09-11] 리스크 R-03. 계정 미발급 지속 시 대체 데이터 사용 검토. 판단 기한 9/16.',
    occurredOn: '2026-09-11', authorName: '박지민', ownerName: null, dueOn: '2026-09-16',
    trustStatus: 'AUTO_DRAFT', connectionId: 14, confirmedByName: null, confirmedAt: null,
  },
]

/** REC-105는 시작 상태에 없다. 장면 2에서 NOTE-001의 요약을 공유하면 CONFIRMED로 생긴다 (D4·D12). */

/**
 * SUPERSEDES는 「무엇이 현재 유효한가」를, REFERENCES는 「이 판단이 무엇에 기대는가」를 말한다.
 * 데모는 1단계 발표처럼 SUPERSEDES 쌍을 관리자가 해소할 충돌 항목으로 만든다.
 */
export const RELATIONS: RecordRelation[] = [
  { id: 1, version: 0, projectId: DEMO_PROJECT_ID, fromRecordId: 102, toRecordId: 101, relationType: 'SUPERSEDES',
    note: '보안성 검토 기간 반영으로 오픈일이 10월 15일에서 10월 22일로 변경됨' },
  { id: 2, version: 0, projectId: DEMO_PROJECT_ID, fromRecordId: 106, toRecordId: 104, relationType: 'REFERENCES',
    note: '대체 테스트 데이터 사용 여부는 CRM 계정 발급 지연을 전제로 하는 결정임' },
]

export const ASSESSMENT = [
  { questionNo: 1, prompt: '최종 오픈일은 언제인가?', linkedRecordId: 102,
    acceptedAnswers: ['10월 22일', '2026-10-22', '10/22'] },
  { questionNo: 2, prompt: '가장 가까운 고객 약속은 무엇인가?', linkedRecordId: 103,
    acceptedAnswers: ['9월 18일 통합 시연 버전 제공', '통합 시연 버전 제공'] },
  { questionNo: 3, prompt: '현재 통합 테스트의 장애요인은 무엇인가?', linkedRecordId: 104,
    acceptedAnswers: ['CRM 테스트 계정 발급 지연', 'CRM 테스트 계정 발급'] },
  { questionNo: 4, prompt: '1차 오픈에서 제외된 기능은 무엇인가?', linkedRecordId: 105,
    acceptedAnswers: ['대량 상담이력 다운로드'] },
  { questionNo: 5, prompt: '9월 16일까지 추가 결정할 사항은 무엇인가?', linkedRecordId: 106,
    acceptedAnswers: ['대체 테스트 데이터 사용 여부', '대체 테스트 데이터 사용'] },
]

/** 핵심어로 고른 답변. 맞는 핵심어가 없으면 NO_EVIDENCE와 확인할 대상을 돌려준다. */
export const QA_SCRIPT = [
  {
    keywords: ['오픈일', '오픈 일정', '최종 오픈'],
    sentences: ['최종 오픈일은 2026년 10월 22일입니다.', '보안성 검토 기간을 반영하여 기존 10월 15일에서 변경됐습니다.'],
    citations: [{ recordId: 102, isPrimary: true }, { recordId: 101, isPrimary: false }],
  },
]

export const NO_EVIDENCE_CONTACTS = { suggestedContacts: '박지민 PL', suggestedDocuments: '프로젝트 회의록' }
