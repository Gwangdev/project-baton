/**
 * API DTO 타입.
 *
 * 이름과 필드는 `ProjectBaton-API.yml`의 components를 따른다. 엔티티 ID는 ID 매퍼가 양의 안전정수인지 확인한
 * 뒤에만 `number`로 쓴다. C01이 int64 ID를 2^53-1 이하로 제한하기 때문에 브라우저 숫자로 정확히 담을 수 있다.
 * `REC-101` 같은 화면 code는 문자열로 두고 경로 ID로 쓰지 않는다.
 */

export type RecordType =
  | 'SCHEDULE'
  | 'CHANGE_DECISION'
  | 'COMMITMENT'
  | 'BLOCKER'
  | 'SCOPE_EXCEPTION'
  | 'OPEN_ISSUE'
export type RecordTrustStatus = 'AUTO_DRAFT' | 'CONFIRMED' | 'CORRECTED' | 'DISMISSED'
export type RecordRelationType = 'SUPERSEDES' | 'REFERENCES' | 'CONFLICTS_WITH'
export type SourceChannel = 'EMAIL' | 'APPROVAL' | 'MESSENGER' | 'MEETING_MINUTES' | 'CLOUD_PROJECT_FOLDER'
export type ConnectionStatus = 'ACTIVE' | 'PAUSED' | 'DISCONNECTED'
export type SourceHealth = 'NORMAL' | 'DELAYED' | 'FAILED'
export type IngestionStatus = 'SUCCEEDED' | 'DELAYED' | 'FAILED'
export type SystemRole = 'USER' | 'SYSTEM_ADMIN'
export type ProjectRole = 'OUTGOING_OWNER' | 'INCOMING_OWNER' | 'PROJECT_MANAGER' | 'MEMBER'
export type ProjectStatus = 'PLANNED' | 'ON_TRACK' | 'AT_RISK' | 'CLOSED'
export type HandoverStatus = 'REQUESTED' | 'GENERATING' | 'READY' | 'REVIEW_REQUESTED' | 'COMPLETED' | 'FAILED'
export type HandoverItemKind =
  | 'OVERVIEW'
  | 'KEY_DECISION'
  | 'CHANGE'
  | 'COMMITMENT'
  | 'OPEN_RISK'
  | 'SCHEDULE'
  | 'FOLLOW_UP'
export type QaAnswerStatus = 'ANSWERED' | 'NO_EVIDENCE' | 'FAILED'
export type AssessmentStatus = 'NOT_STARTED' | 'SUBMITTED'
export type QuestionType = 'MULTIPLE_CHOICE' | 'SHORT_ANSWER'

export const RECORD_TYPES: readonly RecordType[] = [
  'SCHEDULE',
  'CHANGE_DECISION',
  'COMMITMENT',
  'BLOCKER',
  'SCOPE_EXCEPTION',
  'OPEN_ISSUE',
]

export interface PageMeta {
  total: number
  limit: number
  offset: number
  nextCursor?: string | null
}

export interface Page<T> {
  items: T[]
  page: PageMeta
}

export interface User {
  id: number
  version: number
  employeeNo: string
  name: string
  title: string
  email: string
  systemRole: SystemRole
  isActive: boolean
  organizationId: number
  organizationName?: string
  /** 계약상 필수이고 백엔드는 항상 채운다. 화면이 읽지 않아 타입은 선택으로 두고, 이 값을 생략하는 테스트 고정값을 허용한다. */
  authProvider?: 'LOCAL' | 'OIDC'
  loginEnabled: boolean
}

export interface ProjectMembership {
  projectId: number
  projectCode: string
  projectName: string
  projectRole: ProjectRole
}

export type AccountActivityAction = 'SIGN_IN' | 'ISSUE_CREDENTIAL_TOKEN' | 'SET_CREDENTIAL' | 'RESET_SYSTEM_ADMIN_CREDENTIAL'

/** 본인 계정에 일어난 보안 관련 일 한 건. 주소가 기록되지 않은 행은 null이다. */
export interface AccountActivity {
  action: AccountActivityAction
  occurredAt: string
  remoteAddress?: string | null
}

export interface CurrentUser {
  user: User
  memberships: ProjectMembership[]
  /** 최근 5건(최신순). 계약상 선택이라 생략될 수 있다. */
  accountActivity?: AccountActivity[]
}

export interface Project {
  id: number
  version: number
  code: string
  name: string
  deliveringCompany: string
  clientCompany: string
  startedOn: string
  endsOn: string
  phase: string
  status: ProjectStatus
  isActive: boolean
}

export interface ProjectDetail extends Project {
  myRole: ProjectRole
  pendingRecordCount: number
  openRiskCount: number
}

export interface SourceConnection {
  id: number
  version: number
  projectId: number
  ownerUserId?: number | null
  channel: SourceChannel
  displayName: string
  externalRef?: string | null
  status: ConnectionStatus
  health: SourceHealth
  lastCollectedAt?: string | null
}

export interface SourceConnectionDetail extends SourceConnection {
  lastStatus: IngestionStatus | null
  lastCollectedCount: number
  lastFailedCount: number
  lastMessage: string | null
  collectedFileCount: number
  draftCandidateCount: number
}

export interface DashboardSummary {
  projectCount: number
  pendingRecordCount: number
  activeHandoverCount: number
  failedJobCount: number
  projects: ProjectDetail[]
  connectionHealth?: SourceConnection[]
}

export interface CollectedFile {
  id: number
  displayName: string
  externalRef?: string | null
  collectedAt: string
  derivedRecordCodes?: string[]
  processingStatus: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED'
  failureCode?: string | null
}

export interface PersonalNote {
  id: number
  code: string
  projectId: number
  body: string
  createdAt: string
  sharedRecordCodes?: string[]
}

export interface RecordSourceRef {
  id: number
  displayName: string
  channel: SourceChannel
  externalRef?: string | null
}

export interface WorkRecord {
  id: number
  version: number
  code: string
  projectId: number
  recordType: RecordType
  title: string
  summary: string
  occurredOn: string
  trustStatus: RecordTrustStatus
  authorName: string
  ownerName?: string | null
  dueOn?: string | null
  source?: RecordSourceRef | null
  sourceNoteCode?: string | null
  supersededByCode?: string | null
}

export interface RecordRevision {
  revisionNo: number
  changedByName: string
  changeReason: string
  beforeSummary: string
  afterSummary: string
  changedAt: string
}

export interface RecordRelation {
  id: number
  version: number
  projectId: number
  fromRecordId: number
  toRecordId: number
  relationType: RecordRelationType
  relatedRecordId?: number
  relatedRecordCode?: string
  relatedRecordTitle?: string
  note?: string | null
  /** 잘못 등록해 철회한 관계. 철회 뒤에도 이미 만든 패키지 세대의 충돌 목록에는 남는다. */
  withdrawnAt?: string | null
  withdrawalReason?: string | null
}

export interface RecordDetail extends WorkRecord {
  originalExcerpt: string
  confirmedByName: string | null
  confirmedAt: string | null
  revisions: RecordRevision[]
  relations: RecordRelation[]
}

export interface Handover {
  id: number
  version: number
  code: string
  projectId: number
  status: HandoverStatus
  outgoingOwnerName: string
  incomingOwnerName: string
  effectiveOn: string
  progressPercent?: number
  requestedAt?: string
  generation: number
}

/**
 * requestHandover·retry·regenerate의 202 본문에서 클라이언트가 기대는 필드.
 *
 * 202 본문은 Handover 전체이지만(계약과 백엔드가 같다) 화면은 이 네 필드만 읽는다. 코드·담당자 이름 같은 나머지는
 * 생성이 끝날 때까지 getHandover 폴링으로 읽는다.
 */
export interface HandoverAccepted {
  id: number
  version: number
  status: HandoverStatus
  generation: number
}

export interface HandoverDetail extends Handover {
  scopeFrom: string
  scopeTo: string
  scopeRecordTypes: RecordType[]
  includeNoteDerivedRecords: boolean
  includedCount: number
  /** 가장 최근 보완 요청 사유. 보완 요청이 없었으면 null */
  revisionComment: string | null
  failureReason: string | null
  conflicts: RecordRelation[]
  unconfirmedRecords: WorkRecord[]
  approvalBlockers: string[]
  readyAt: string | null
  completedAt: string | null
}

export interface HandoverItem {
  id: number
  version: number
  itemKind: HandoverItemKind
  title: string
  body: string
  displayOrder: number
  isRequired: boolean
  evidence?: WorkRecord | null
  resolvedByRecordCode?: string | null
  resolutionNote?: string | null
  assigneeName?: string | null
  dueOn?: string | null
  generation: number
  acknowledged: boolean
  conflictId?: number | null
  candidateRecordIds?: number[]
}

export interface HandoverItemAcknowledgement {
  itemId: number
  userId: number
  generation: number
  acknowledgedAt: string
}

export interface QaCitation {
  recordId?: number
  recordCode: string
  recordTitle: string
  sourceName: string
  occurredOn?: string
  trustStatusAtAnswer: RecordTrustStatus
  isPrimary: boolean
}

export interface QaAnswer {
  id: number
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
  answerStatus?: QaAnswerStatus
  citations?: QaCitation[]
  suggestedContacts?: string | null
  suggestedDocuments?: string | null
}

export interface AssessmentChoice {
  id: string
  text: string
}

export interface AssessmentQuestion {
  id: number
  questionNo: number
  questionType: QuestionType
  prompt: string
  linkedRecordCode: string
  choices: AssessmentChoice[] | null
}

export interface AssessmentAnswer {
  questionId: number
  questionNo: number
  answerText: string
  isCorrect: boolean
  linkedRecordCode?: string | null
}

export interface AssessmentAttempt {
  attemptNo: number
  score: number
  total: number
  submittedAt?: string | null
  answers: AssessmentAnswer[]
}

export interface AssessmentDetail {
  generation: number
  status: AssessmentStatus
  passThreshold: number
  questions: AssessmentQuestion[]
  attempts: AssessmentAttempt[]
}

export interface Organization {
  id: number
  version: number
  name: string
  isActive: boolean
  userCount?: number
}

export interface ProjectMember {
  id: number
  version: number
  userId: number
  userName: string
  userTitle: string
  projectRole: ProjectRole
  joinedOn: string
  leftOn?: string | null
}

export interface BlockingReference {
  resource: string
  count: number
  hint?: string
}

// ── 요청 본문 ───────────────────────────────────────────────

export interface RecordQuery {
  recordType?: RecordType[]
  trustStatus?: RecordTrustStatus[]
  occurredFrom?: string
  occurredTo?: string
  limit?: number
  cursor?: string
}

/** PM이 확정 기록 둘을 잇는다(README §5.1). SUPERSEDES는 새 기록 → 이전 기록이다. */
export interface CreateRecordRelationInput {
  fromRecordId: number
  toRecordId: number
  relationType: RecordRelationType
  note?: string
}

/** AI 구조화에 실패한 수집 파일에서 사람이 직접 쓰는 초안. 다른 초안처럼 확인을 거친다. */
export interface CreateManualDraftInput {
  recordType: RecordType
  title: string
  summary: string
  originalExcerpt: string
  occurredOn: string
  dueOn?: string
}

export interface ReviewRecordInput {
  action: 'CONFIRM' | 'CORRECT' | 'DISMISS'
  title?: string
  summary?: string
  recordType?: RecordType
  ownerUserId?: number | null
  dueOn?: string | null
  changeReason?: string
}

export interface ShareNoteSummaryInput {
  recordType: RecordType
  title: string
  summary: string
  occurredOn: string
  dueOn?: string | null
}

export interface CreateSourceConnectionInput {
  channel: SourceChannel
  displayName: string
  externalRef: string
}

export interface UpdateSourceConnectionInput {
  displayName?: string
  externalRef?: string
  status?: ConnectionStatus
}

export interface CreateHandoverInput {
  outgoingOwnerId: number
  incomingOwnerId: number
  effectiveOn: string
  scopeFrom: string
  scopeTo: string
  scopeRecordTypes: RecordType[]
  includeNoteDerivedRecords: boolean
}

export interface RegenerateHandoverInput {
  scopeFrom: string
  scopeTo: string
  scopeRecordTypes: RecordType[]
  includeNoteDerivedRecords: boolean
}

export interface SubmittedAnswer {
  questionId: number
  answerText: string
}

/** 관리자가 발급한 일회용 설정 코드. 발급 응답에서만 평문으로 볼 수 있다. */
export interface CredentialToken {
  token: string
  expiresAt: string
}

export interface SetCredentialInput {
  employeeNo: string
  token: string
  newPassword: string
}

export interface UserCreateInput {
  organizationId: number
  employeeNo: string
  name: string
  title: string
  email: string
  systemRole?: SystemRole
}

export interface ProjectCreateInput {
  code: string
  name: string
  deliveringCompany: string
  clientCompany: string
  startedOn: string
  endsOn: string
  phase: string
  status: ProjectStatus
}

export interface ProjectMemberInput {
  userId: number
  projectRole: ProjectRole
  joinedOn: string
}
