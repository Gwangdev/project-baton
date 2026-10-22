import type { RecordRepository } from '@/api/repositories/records'
import { requireMember, requireUser, roleOf, userName } from '../access'
import { recordDetail, visibleRecords, workRecord } from '../views'
import { fail, type DemoState } from '../state'

/**
 * 데모 기록 목록·상세·확인·관계 등록. cursor는 다음 위치를 담은 불투명 문자열이다. 관계는 서버처럼 PM만, 확정
 * 기록끼리만 등록하고, 이미 다른 기록이 대체한 기록을 다시 대체하면 409로 막는다(README §5.1).
 */
export function demoRecordRepository(state: DemoState): RecordRepository {
  return {
    listRecords: async (projectId, query = {}) => {
      await state.pause()
      const userId = requireMember(state, projectId)
      const all = visibleRecords(state, userId)
        .filter((r) => !query.recordType?.length || query.recordType.includes(r.recordType))
        .filter((r) => !query.trustStatus?.length || query.trustStatus.includes(r.trustStatus))
        .filter((r) => !query.occurredFrom || r.occurredOn >= query.occurredFrom)
        .filter((r) => !query.occurredTo || r.occurredOn <= query.occurredTo)
        .sort((a, b) => b.occurredOn.localeCompare(a.occurredOn) || b.id - a.id)
      const offset = query.cursor ? Number(query.cursor) : 0
      const limit = query.limit ?? 20
      const items = all.slice(offset, offset + limit).map((r) => workRecord(state, r, userId))
      const next = offset + limit < all.length ? String(offset + limit) : null
      return { items, page: { total: all.length, limit, offset: 0, nextCursor: next } }
    },
    getRecord: async (recordId) => {
      await state.pause()
      const userId = requireUser(state)
      const record = visibleRecords(state, userId).find((r) => r.id === recordId) ?? fail(404, 'PROJECT_NOT_FOUND')
      return recordDetail(state, record, userId)
    },
    reviewRecord: async (recordId, version, input) => {
      await state.pause()
      const userId = requireUser(state)
      const record = visibleRecords(state, userId).find((r) => r.id === recordId) ?? fail(404, 'PROJECT_NOT_FOUND')
      if (record.version !== version) fail(412, 'STALE_VERSION')
      if (record.trustStatus !== 'AUTO_DRAFT') fail(409, 'INVALID_STATE', 'Record is already reviewed')
      const actor = userName(state, userId)
      if (input.action === 'CORRECT') {
        if (!input.changeReason?.trim()) fail(400, 'INVALID_INPUT')
        record.revisions.push({
          revisionNo: record.revisions.length + 1, changedByName: actor, changeReason: input.changeReason!,
          beforeSummary: record.summary, afterSummary: input.summary ?? record.summary, changedAt: new Date().toISOString(),
        })
        record.title = input.title ?? record.title
        record.summary = input.summary ?? record.summary
        record.recordType = input.recordType ?? record.recordType
        if (input.dueOn !== undefined) record.dueOn = input.dueOn
      }
      record.trustStatus = input.action === 'CONFIRM' ? 'CONFIRMED' : input.action === 'CORRECT' ? 'CORRECTED' : 'DISMISSED'
      if (record.trustStatus !== 'DISMISSED') {
        record.confirmedByName = actor
        record.confirmedAt = new Date().toISOString()
      }
      record.version += 1
      return recordDetail(state, record, userId)
    },
    createRecordRelation: async (projectId, input) => {
      await state.pause()
      const userId = requireMember(state, projectId)
      if (roleOf(state, userId, projectId) !== 'PROJECT_MANAGER') fail(403, 'ACCESS_DENIED')
      const visible = visibleRecords(state, userId)
      const from = visible.find((r) => r.id === input.fromRecordId) ?? fail(404, 'PROJECT_NOT_FOUND')
      const to = visible.find((r) => r.id === input.toRecordId) ?? fail(404, 'PROJECT_NOT_FOUND')
      const confirmed = (status: string) => status === 'CONFIRMED' || status === 'CORRECTED'
      if (from.id === to.id || !confirmed(from.trustStatus) || !confirmed(to.trustStatus)) fail(409, 'INVALID_STATE')
      const [low, high] = input.relationType === 'CONFLICTS_WITH' ? [Math.min(from.id, to.id), Math.max(from.id, to.id)] : [from.id, to.id]
      // 서버와 같이, 철회된 관계는 중복·두 번째 대체 검사에서 없는 것으로 본다.
      const active = state.relations.filter((rel) => !rel.withdrawnAt)
      if (active.some((rel) => rel.fromRecordId === low && rel.toRecordId === high && rel.relationType === input.relationType)) fail(409, 'INVALID_STATE')
      // 서버의 순환 검사: 대상에서 대체 관계를 따라가 출처에 닿으면 순환이다.
      if (input.relationType === 'SUPERSEDES') {
        const reached = new Set<number>()
        const walk = (id: number) => active.filter((rel) => rel.relationType === 'SUPERSEDES' && rel.fromRecordId === id)
          .forEach((rel) => { if (!reached.has(rel.toRecordId)) { reached.add(rel.toRecordId); walk(rel.toRecordId) } })
        reached.add(high)
        walk(high)
        if (reached.has(low)) fail(409, 'INVALID_STATE', 'A supersession relation cannot create a cycle.')
      }
      // 서버의 V20 고유 인덱스처럼, 정렬한 낮은 기록 하나에는 철회되지 않은 상충이 하나뿐이다.
      if (input.relationType === 'CONFLICTS_WITH' && active.some((rel) => rel.relationType === 'CONFLICTS_WITH' && rel.fromRecordId === low)) {
        fail(409, 'INVALID_STATE', 'The record relation already exists or is invalid.')
      }
      if (input.relationType === 'SUPERSEDES' && active.some((rel) => rel.relationType === 'SUPERSEDES' && rel.toRecordId === high)) {
        fail(409, 'INVALID_STATE', 'The record is already superseded by another record.')
      }
      // 서버와 같이, 한 쌍에는 대체(결론이 났다)와 상충(아직 결론이 없다) 중 하나만 건다.
      const settling = (type: string) => type === 'SUPERSEDES' || type === 'CONFLICTS_WITH'
      if (settling(input.relationType) && active.some((rel) => settling(rel.relationType) && rel.relationType !== input.relationType
          && Math.min(rel.fromRecordId, rel.toRecordId) === Math.min(low, high) && Math.max(rel.fromRecordId, rel.toRecordId) === Math.max(low, high))) {
        fail(409, 'INVALID_STATE', 'The two records already have a superseding or conflicting relation.')
      }
      const relation = { id: state.nextId(), version: 0, projectId, fromRecordId: low, toRecordId: high, relationType: input.relationType, note: input.note ?? null }
      state.relations.push(relation)
      return { ...relation }
    },
    withdrawRecordRelation: async (relationId, version, reason) => {
      await state.pause()
      const relation = state.relations.find((rel) => rel.id === relationId) ?? fail(404, 'PROJECT_NOT_FOUND')
      const userId = requireMember(state, relation.projectId)
      if (roleOf(state, userId, relation.projectId) !== 'PROJECT_MANAGER') fail(403, 'ACCESS_DENIED')
      if (!reason.trim() || reason.length > 300) fail(400, 'INVALID_INPUT')
      if (relation.version !== version) fail(412, 'STALE_VERSION')
      if (relation.withdrawnAt) fail(409, 'INVALID_STATE', 'The record relation is already withdrawn.')
      Object.assign(relation, { withdrawnAt: new Date().toISOString(), withdrawalReason: reason, version: relation.version + 1 })
      // 서버는 게시된 세대의 충돌 목록을 관계 행에서 읽으므로 철회가 그 목록에도 보인다.
      for (const listed of state.handover?.detail.conflicts ?? []) {
        if (listed.id === relationId) Object.assign(listed, { withdrawnAt: relation.withdrawnAt, withdrawalReason: reason, version: relation.version })
      }
      return { ...relation }
    },
  }
}
