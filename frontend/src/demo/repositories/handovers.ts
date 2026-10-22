import type { HandoverRepository } from '@/api/repositories/handovers'
import { relationOfItem } from '@/api/conflicts'
import { requireMember, requireUser, roleOf, userName } from '../access'
import {
  accepted,
  advance,
  blockers,
  handoverDetail,
  handoverSummary,
  isParticipant,
  requireHandoverReader,
  requireManager,
  restartGeneration,
} from '../handoverLifecycle'
import { clone, fail, page, type DemoState } from '../state'

/** 데모 담당자 교체 요청·패키지·충돌 해소·명시 확인·완료 결정. */
export function demoHandoverRepository(state: DemoState): HandoverRepository {
  return {
    listHandovers: async (projectId) => {
      await state.pause()
      const userId = requireMember(state, projectId)
      if (!state.handover || !isParticipant(state, userId)) return page([])
      return page([handoverSummary(advance(state))])
    },
    requestHandover: async (projectId, input) => {
      await state.pause()
      const userId = requireMember(state, projectId)
      if (roleOf(state, userId, projectId) !== 'PROJECT_MANAGER') fail(403, 'ACCESS_DENIED')
      if (input.scopeRecordTypes.length === 0 || input.outgoingOwnerId === input.incomingOwnerId) fail(400, 'INVALID_INPUT')
      if (!roleOf(state, input.incomingOwnerId, projectId) || !roleOf(state, input.outgoingOwnerId, projectId)) fail(400, 'INVALID_INPUT')
      if (state.handover && state.handover.detail.status !== 'COMPLETED') fail(409, 'INVALID_STATE', 'A handover is already in progress')
      state.handover = {
        outgoingId: input.outgoingOwnerId,
        incomingId: input.incomingOwnerId,
        requestedAtMs: state.options.now(),
        items: [],
        acknowledged: new Set(),
        attempts: [],
        published: false,
        detail: {
          id: 1, version: 0, code: 'H-001', projectId, status: 'REQUESTED',
          outgoingOwnerName: userName(state, input.outgoingOwnerId), incomingOwnerName: userName(state, input.incomingOwnerId),
          effectiveOn: input.effectiveOn, progressPercent: 0, requestedAt: new Date().toISOString(), generation: 1,
          scopeFrom: input.scopeFrom, scopeTo: input.scopeTo, scopeRecordTypes: [...input.scopeRecordTypes],
          includeNoteDerivedRecords: input.includeNoteDerivedRecords, includedCount: 0, revisionComment: null,
          failureReason: null, conflicts: [], unconfirmedRecords: [], approvalBlockers: [], readyAt: null, completedAt: null,
        },
      }
      return accepted(state.handover)
    },
    getHandover: async (handoverId) => {
      await state.pause()
      const userId = requireHandoverReader(state, handoverId)
      return handoverDetail(state, userId)
    },
    retryHandoverGeneration: async (handoverId, version) => {
      await state.pause()
      const h = requireManager(state, handoverId, version)
      if (h.detail.status !== 'FAILED') fail(409, 'INVALID_STATE')
      restartGeneration(state, false)
      return accepted(h)
    },
    regenerateHandover: async (handoverId, version, input) => {
      await state.pause()
      const h = requireManager(state, handoverId, version)
      if (!['READY', 'REVIEW_REQUESTED', 'FAILED'].includes(h.detail.status)) fail(409, 'INVALID_STATE')
      Object.assign(h.detail, input)
      restartGeneration(state, true)
      return accepted(h)
    },
    listHandoverItems: async (handoverId) => {
      await state.pause()
      const userId = requireHandoverReader(state, handoverId)
      const h = advance(state)
      return page(h.items.map((item) => ({ ...clone(item), acknowledged: userId === h.incomingId && h.acknowledged.has(item.id) })))
    },
    resolveHandoverItem: async (handoverId, itemId, version, resolvedByRecordId, resolutionNote) => {
      await state.pause()
      const h = requireManager(state, handoverId)
      const item = h.items.find((i) => i.id === itemId) ?? fail(404, 'ITEM_NOT_FOUND')
      if (item.version !== version) fail(412, 'STALE_VERSION')
      // 서버와 같은 규칙: 후보가 아닌 기록은 409이고, 대체 쌍은 대체한 기록(관계의 출발 기록)으로만 해소한다.
      if (!item.candidateRecordIds?.includes(resolvedByRecordId)) fail(409, 'INVALID_STATE')
      const relation = relationOfItem(h.detail.conflicts, item)
      if (relation?.withdrawnAt) fail(409, 'INVALID_STATE', 'The relation of this conflict was withdrawn; rebuild the package.')
      if (relation?.relationType === 'SUPERSEDES' && resolvedByRecordId !== relation.fromRecordId) fail(409, 'INVALID_STATE')
      item.resolvedByRecordCode = `REC-${resolvedByRecordId}`
      item.resolutionNote = resolutionNote
      item.version += 1
      return clone(item)
    },
    acknowledgeHandoverItem: async (handoverId, itemId, version) => {
      await state.pause()
      const userId = requireHandoverReader(state, handoverId)
      const h = advance(state)
      if (userId !== h.incomingId) fail(403, 'ACCESS_DENIED')
      if (!['READY', 'REVIEW_REQUESTED'].includes(h.detail.status)) fail(409, 'INVALID_STATE')
      const item = h.items.find((i) => i.id === itemId) ?? fail(404, 'ITEM_NOT_FOUND')
      if (item.version !== version) fail(412, 'STALE_VERSION')
      h.acknowledged.add(itemId)
      return { itemId, userId, generation: h.detail.generation, acknowledgedAt: new Date().toISOString() }
    },
    decideHandoverCompletion: async (handoverId, version, decision, comment) => {
      await state.pause()
      const h = requireManager(state, handoverId, version)
      const userId = requireUser(state)
      // 서버와 같은 입력 규칙: 의견은 보완 요청의 사유이고 필수이며, 승인에는 붙이지 않는다(400).
      if (decision === 'APPROVE' ? comment !== undefined : !comment?.trim()) fail(400, 'INVALID_INPUT')
      if (h.detail.status === 'COMPLETED') fail(409, 'INVALID_STATE')
      if (decision === 'REQUEST_REVISION') {
        if (h.detail.status !== 'READY') fail(409, 'INVALID_STATE')
        h.detail.status = 'REVIEW_REQUESTED'
        h.detail.revisionComment = comment?.trim() ?? null
      } else {
        const remaining = blockers(h)
        if (remaining.length > 0) fail(409, 'INVALID_STATE', remaining.join(','))
        h.detail.status = 'COMPLETED'
        h.detail.completedAt = new Date().toISOString()
      }
      h.detail.version += 1
      return handoverDetail(state, userId)
    },
  }
}
