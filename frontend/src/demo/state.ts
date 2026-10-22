import { ApiProblem } from '@/api/http'
import type {
  AssessmentAttempt,
  CollectedFile,
  HandoverDetail,
  HandoverItem,
  Organization,
  Page,
  PersonalNote,
  Project,
  ProjectMember,
  QaAnswer,
  RecordRelation,
  RecordRevision,
  SourceConnectionDetail,
  User,
} from '@/api/types'
import {
  ACCOUNTS,
  COLLECTED_FILES,
  CONNECTIONS,
  NOTES,
  ORGANIZATIONS,
  PROJECT,
  RECORDS,
  RELATIONS,
  type DemoRecordSeed,
} from './scenario'

/**
 * 데모 저장소가 공유하는 메모리 상태.
 *
 * 기능별 데모 구현(`repositories/*`)은 이 상태 하나를 함께 읽고 바꾼다. 서버의 DB 역할이며, 시작할 때
 * 시나리오(scenario.ts)를 복사해 채운다. 화면이 받는 값은 항상 복사본이라 화면이 상태를 직접 바꾸지 못한다.
 */

export interface DemoRecord extends DemoRecordSeed {
  version: number
  noteId: number | null
  noteCode: string | null
  revisions: RecordRevision[]
}

export interface DemoHandover {
  detail: HandoverDetail
  outgoingId: number
  incomingId: number
  requestedAtMs: number
  items: HandoverItem[]
  acknowledged: Set<number>
  attempts: AssessmentAttempt[]
  published: boolean
}

export interface DemoOptions {
  /** 발표에서 처리 중 상태가 보이도록 호출마다 쉬는 시간. 단위 테스트에서는 0이다. */
  delayMs?: number
  /** 요청부터 READY까지 걸리는 시간. */
  generationMs?: number
  now?: () => number
}

export const clone = <T>(value: T): T => structuredClone(value)

export const fail = (status: number, code: string, detail?: string): never => {
  throw new ApiProblem({ status, code, detail, traceId: 'demo' })
}

/** 서버처럼 offset 한 페이지를 잘라 준다. 범위를 주지 않으면 전체를 한 페이지로 준다. */
export const page = <T>(items: T[], range?: { limit: number; offset: number }): Page<T> =>
  range
    ? { items: items.slice(range.offset, range.offset + range.limit), page: { total: items.length, limit: range.limit, offset: range.offset, nextCursor: null } }
    : { items, page: { total: items.length, limit: items.length, offset: 0, nextCursor: null } }

export class DemoState {
  readonly options: Required<DemoOptions>
  signedIn: number | null = null
  users: User[]
  organizations: Organization[]
  projects: Project[]
  members: ProjectMember[]
  connections: SourceConnectionDetail[]
  files: (CollectedFile & { connectionId: number })[]
  notes: (PersonalNote & { authorId: number })[]
  records: DemoRecord[]
  relations: RecordRelation[]
  handover: DemoHandover | null = null
  qa: QaAnswer[] = []
  /** 발급된 설정 코드(사용자 ID별 하나). 서버의 credential_tokens에 해당한다. */
  setupCodes = new Map<number, string>()
  /** 설정 코드로 정한 비밀번호. 없으면 demoCredentials의 데모 비밀번호를 쓴다. */
  passwords: Record<string, string> = {}
  /** 로그인한 적이 있는 사용자. 서버에서는 감사 행(audit_events)의 행위자라, 삭제는 거절되고 비활성화가 안내된다. */
  actors = new Set<number>()
  private seq = 1000

  constructor(options: DemoOptions = {}) {
    this.options = { delayMs: options.delayMs ?? 250, generationMs: options.generationMs ?? 2500, now: options.now ?? Date.now }
    this.users = [...ACCOUNTS.map((a) => clone(a.user)), {
      id: 5, version: 0, employeeNo: 'A-99999', name: '테스트 계정', title: '임시', email: 'a-99999@example.test',
      systemRole: 'USER', isActive: true, organizationId: 1, organizationName: ORGANIZATIONS[0].name,
      authProvider: 'LOCAL', loginEnabled: false,
    }]
    this.organizations = clone(ORGANIZATIONS)
    this.projects = [clone(PROJECT)]
    this.members = ACCOUNTS.map((a, i) => ({
      id: i + 1, version: 0, userId: a.user.id, userName: a.user.name, userTitle: a.user.title,
      projectRole: a.projectRole, joinedOn: '2026-06-01', leftOn: null,
    }))
    this.connections = clone(CONNECTIONS)
    this.files = clone(COLLECTED_FILES)
    this.notes = clone(NOTES)
    this.records = RECORDS.map((r) => ({ ...clone(r), version: 0, noteId: null, noteCode: null, revisions: [] }))
    this.relations = clone(RELATIONS)
  }

  nextId(): number {
    this.seq += 1
    return this.seq
  }

  pause(): Promise<void> {
    return this.options.delayMs > 0 ? new Promise((r) => setTimeout(r, this.options.delayMs)) : Promise.resolve()
  }
}
