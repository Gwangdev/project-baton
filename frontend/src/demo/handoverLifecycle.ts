import type { Handover, HandoverAccepted, HandoverDetail, HandoverItem, HandoverItemKind } from '@/api/types'
import { relationOfItem } from '@/api/conflicts'
import { requireMember, roleOf } from './access'
import { visibleRecords, workRecord } from './views'
import { ASSESSMENT, DEMO_PROJECT_ID, PROJECT } from './scenario'
import { clone, fail, type DemoHandover, type DemoRecord, type DemoState } from './state'

/**
 * 데모 인수인계의 생성 수명주기와 참여자 확인.
 *
 * 서버의 BUILD_PACKAGE 워커와 HandoverReadinessCheck 역할이다. 생성은 요청 뒤 경과 시간으로만 진행하므로 몇 번을
 * 읽어도 결과가 달라지지 않는다. 서버처럼 snapshot 안의 철회되지 않은 대체·상충 관계마다 관리자가 해소할 충돌 항목을
 * 만들고, 그 항목은 관계의 출처 기록(대체는 새 기록, 상충은 정렬한 낮은 기록)의 것이다. 해소는 항목 하나에 충돌
 * 하나라서, 출처가 같은 관계가 여럿이면 첫 관계는 그 기록의 항목에, 나머지는 목록 끝에 붙인 그 항목의 사본에 둔다.
 */

const KIND_OF: Record<string, HandoverItemKind> = {
  CHANGE_DECISION: 'KEY_DECISION',
  SCOPE_EXCEPTION: 'CHANGE',
  COMMITMENT: 'COMMITMENT',
  SCHEDULE: 'SCHEDULE',
  BLOCKER: 'OPEN_RISK',
  OPEN_ISSUE: 'OPEN_RISK',
}

export function isParticipant(state: DemoState, userId: number): boolean {
  const h = state.handover
  return !!h && (userId === h.outgoingId || userId === h.incomingId || roleOf(state, userId, DEMO_PROJECT_ID) === 'PROJECT_MANAGER')
}

export function requireHandoverReader(state: DemoState, handoverId: number): number {
  const userId = requireMember(state, DEMO_PROJECT_ID)
  if (!state.handover || handoverId !== state.handover.detail.id) fail(404, 'HANDOVER_NOT_FOUND')
  if (!isParticipant(state, userId)) fail(403, 'ACCESS_DENIED')
  return userId
}

export function requireManager(state: DemoState, handoverId: number, version?: number): DemoHandover {
  const userId = requireHandoverReader(state, handoverId)
  if (roleOf(state, userId, DEMO_PROJECT_ID) !== 'PROJECT_MANAGER') fail(403, 'ACCESS_DENIED')
  const h = advance(state)
  if (version !== undefined && h.detail.version !== version) fail(412, 'STALE_VERSION')
  return h
}

export function requireIncoming(state: DemoState, handoverId: number): DemoHandover {
  const userId = requireHandoverReader(state, handoverId)
  const h = advance(state)
  if (userId !== h.incomingId) fail(403, 'ACCESS_DENIED')
  return h
}

/** 생성은 경과 시간으로만 진행한다. 몇 번을 읽어도 결과가 달라지지 않는다. */
export function advance(state: DemoState): DemoHandover {
  const h = state.handover!
  if (h.published || h.detail.status === 'FAILED') return h
  const elapsed = state.options.now() - h.requestedAtMs
  if (elapsed >= state.options.generationMs) {
    publish(state, h)
  } else if (elapsed >= state.options.generationMs / 3) {
    h.detail.status = 'GENERATING'
    h.detail.progressPercent = 45
  }
  return h
}

function publish(state: DemoState, h: DemoHandover): void {
  const d = h.detail
  const inScope = (r: DemoRecord) => d.scopeRecordTypes.includes(r.recordType) && r.occurredOn >= d.scopeFrom && r.occurredOn <= d.scopeTo
  const snapshot = state.records
    .filter((r) => (r.trustStatus === 'CONFIRMED' || r.trustStatus === 'CORRECTED') && inScope(r))
    .filter((r) => d.includeNoteDerivedRecords || r.noteId === null)
    .sort((a, b) => a.occurredOn.localeCompare(b.occurredOn) || a.id - b.id)
  const ids = new Set(snapshot.map((r) => r.id))
  const conflicts = state.relations.filter((rel) => (rel.relationType === 'SUPERSEDES' || rel.relationType === 'CONFLICTS_WITH')
    && !rel.withdrawnAt && ids.has(rel.fromRecordId) && ids.has(rel.toRecordId))
  let nextId = 1
  const item = (itemKind: HandoverItemKind, title: string, body: string, isRequired: boolean, extra: Partial<HandoverItem> = {}): HandoverItem => ({
    id: nextId, version: 0, itemKind, title, body, displayOrder: nextId++, isRequired, generation: d.generation,
    acknowledged: false, resolvedByRecordCode: null, resolutionNote: null, assigneeName: null, dueOn: null,
    conflictId: null, ...extra,
  })
  const items = [item('OVERVIEW', '프로젝트 개요',
    `${PROJECT.name}. 현재 단계는 ${PROJECT.phase}이며 일정 주의 상태다. 확정 기록 ${snapshot.length}건으로 구성했다.`, true)]
  // 서버의 conflictId는 충돌 행의 ID라 관계 ID와 다른 번호다. 데모도 따로 매겨 화면이 두 값을 섞지 않는지 시험된다.
  const conflictOf = (rel: (typeof conflicts)[number]): Partial<HandoverItem> =>
    ({ conflictId: state.nextId(), candidateRecordIds: [rel.toRecordId, rel.fromRecordId].sort((a, b) => a - b) })
  const copies: { kind: HandoverItemKind; record: DemoRecord; rel: (typeof conflicts)[number] }[] = []
  for (const r of snapshot) {
    const kind = KIND_OF[r.recordType]
    const [pair, ...more] = conflicts.filter((rel) => rel.fromRecordId === r.id).sort((a, b) => a.id - b.id)
    items.push(item(kind, r.title, r.summary, kind !== 'SCHEDULE', pair ? conflictOf(pair) : {}))
    more.forEach((rel) => copies.push({ kind, record: r, rel }))
    if (kind === 'OPEN_RISK') {
      items.push(item('FOLLOW_UP', r.title, `후속 작업: ${r.summary}`, true, { assigneeName: d.incomingOwnerName, dueOn: r.dueOn ?? d.effectiveOn }))
    }
  }
  // 서버처럼 출처 기록 ID, 관계 ID 순으로 목록 끝에 둔다.
  copies.sort((a, b) => a.record.id - b.record.id || a.rel.id - b.rel.id)
  for (const { kind, record, rel } of copies) items.push(item(kind, record.title, record.summary, kind !== 'SCHEDULE', conflictOf(rel)))
  h.items = items
  h.published = true
  d.status = 'READY'
  d.progressPercent = 100
  d.version += 1
  d.includedCount = snapshot.length
  d.conflicts = conflicts.map((rel) => clone(rel))
  d.readyAt = new Date().toISOString()
}

export function blockers(h: DemoHandover): string[] {
  if (!['READY', 'REVIEW_REQUESTED'].includes(h.detail.status)) return h.detail.status === 'COMPLETED' ? [] : ['HANDOVER_NOT_READY']
  const list: string[] = []
  if (h.detail.status !== 'READY') list.push('HANDOVER_NOT_READY')
  if (h.items.some((i) => i.isRequired && !h.acknowledged.has(i.id))) list.push('REQUIRED_ITEMS_UNACKNOWLEDGED')
  // 서버와 같이, 철회된 관계의 항목은 해소할 수 없고 패키지를 다시 만들어야 한다.
  const withdrawn = (i: HandoverItem) => Boolean(relationOfItem(h.detail.conflicts, i)?.withdrawnAt)
  if (h.items.some((i) => i.conflictId && !i.resolvedByRecordCode && !withdrawn(i))) list.push('CONFLICTS_UNRESOLVED')
  if (h.items.some((i) => i.conflictId && withdrawn(i))) list.push('RELATIONS_WITHDRAWN')
  const latest = h.attempts.at(-1)
  if (!latest) list.push('ASSESSMENT_NOT_SUBMITTED')
  else if (latest.score < ASSESSMENT.length) list.push('ASSESSMENT_NOT_PASSED')
  return list
}

export function handoverDetail(state: DemoState, userId: number): HandoverDetail {
  const h = advance(state)
  const d = h.detail
  const inScope = (r: DemoRecord) => d.scopeRecordTypes.includes(r.recordType) && r.occurredOn >= d.scopeFrom && r.occurredOn <= d.scopeTo
  return {
    ...clone(d),
    unconfirmedRecords: visibleRecords(state, userId).filter((r) => r.trustStatus === 'AUTO_DRAFT' && inScope(r)).map((r) => workRecord(state, r, userId)),
    approvalBlockers: blockers(h),
  }
}

export function handoverSummary(h: DemoHandover): Handover {
  const { id, version, code, projectId, status, outgoingOwnerName, incomingOwnerName, effectiveOn, progressPercent, requestedAt, generation } = h.detail
  return { id, version, code, projectId, status, outgoingOwnerName, incomingOwnerName, effectiveOn, progressPercent, requestedAt, generation }
}

export function accepted(h: DemoHandover): HandoverAccepted {
  const { id, version, status, generation } = h.detail
  return { id, version, status, generation }
}

export function restartGeneration(state: DemoState, nextGeneration: boolean): void {
  const h = state.handover!
  h.detail.status = 'REQUESTED'
  h.detail.progressPercent = 0
  h.detail.version += 1
  if (nextGeneration) h.detail.generation += 1
  h.requestedAtMs = state.options.now()
  h.items = []
  h.acknowledged = new Set()
  h.attempts = []
  h.published = false
}
