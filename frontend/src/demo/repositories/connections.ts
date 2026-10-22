import { ApiProblem } from '@/api/http'
import type { ConnectionRepository } from '@/api/repositories/connections'
import type { SourceConnectionDetail } from '@/api/types'
import { isAdmin, requireMember, requireUser } from '../access'
import { connectionSummary, editableConnection, recordDetail, visibleConnection, visibleConnections } from '../views'
import { clone, fail, page, type DemoState } from '../state'

/** 데모 연동 설정과 수집 파일. 수집한 문서가 있는 연동은 지우지 않는다. */
export function demoConnectionRepository(state: DemoState): ConnectionRepository {
  return {
    listSourceConnections: async (projectId) => {
      await state.pause()
      const userId = requireMember(state, projectId)
      return page(visibleConnections(state, userId).map(connectionSummary))
    },
    getSourceConnection: async (connectionId) => {
      await state.pause()
      return clone(visibleConnection(state, connectionId))
    },
    createSourceConnection: async (projectId, input) => {
      await state.pause()
      const userId = requireMember(state, projectId)
      const personal = input.channel === 'CLOUD_PROJECT_FOLDER'
      if (!personal && !isAdmin(state, userId)) fail(403, 'ACCESS_DENIED')
      const created: SourceConnectionDetail = {
        id: state.nextId(), version: 0, projectId, ownerUserId: personal ? userId : null, channel: input.channel,
        displayName: input.displayName, externalRef: input.externalRef, status: 'ACTIVE', health: 'NORMAL',
        lastCollectedAt: null, lastStatus: null, lastCollectedCount: 0, lastFailedCount: 0, lastMessage: null,
        collectedFileCount: 0, draftCandidateCount: 0,
      }
      state.connections.push(created)
      return connectionSummary(created)
    },
    updateSourceConnection: async (connectionId, version, input) => {
      await state.pause()
      const connection = editableConnection(state, connectionId)
      if (connection.version !== version) fail(412, 'STALE_VERSION')
      Object.assign(connection, input)
      connection.version += 1
      return connectionSummary(connection)
    },
    deleteSourceConnection: async (connectionId, version) => {
      await state.pause()
      const connection = editableConnection(state, connectionId)
      if (connection.version !== version) fail(412, 'STALE_VERSION')
      const collected = state.files.filter((f) => f.connectionId === connectionId).length + connection.lastCollectedCount
      if (collected > 0) {
        throw new ApiProblem({ status: 409, code: 'REFERENCE_CONFLICT', traceId: 'demo', references: [{ resource: 'record_sources', count: collected }] })
      }
      state.connections = state.connections.filter((c) => c.id !== connectionId)
    },
    reconnectSourceConnection: async (connectionId) => {
      await state.pause()
      const connection = editableConnection(state, connectionId)
      connection.status = 'ACTIVE'
      connection.health = 'NORMAL'
      connection.version += 1
      return connectionSummary(connection)
    },
    listCollectedFiles: async (connectionId) => {
      await state.pause()
      const connection = visibleConnection(state, connectionId)
      const userId = requireUser(state)
      // 개인 폴더 원본은 소유자의 것이다. 다른 사람은 목록도 볼 수 없다 (D9).
      if (connection.ownerUserId !== null && connection.ownerUserId !== userId) fail(404, 'CONNECTION_NOT_FOUND')
      return page(state.files.filter((f) => f.connectionId === connectionId).map(({ connectionId: _c, ...file }) => clone(file)))
    },
    retrySourceProcessing: async (connectionId, sourceId) => {
      await state.pause()
      const file = state.files.find((f) => f.id === sourceId && f.connectionId === connectionId) ?? fail(404, 'SOURCE_NOT_FOUND')
      if (file.processingStatus !== 'FAILED') fail(409, 'INVALID_STATE')
      file.processingStatus = 'PENDING'
      file.failureCode = null
    },
    // 서버처럼 처리에 실패한 파일에서만, 그 파일을 볼 수 있는 사람만 수동 초안을 만든다. 초안은 확인 대기로 남는다.
    createManualDraft: async (connectionId, sourceId, input) => {
      await state.pause()
      const userId = requireUser(state)
      const connection = visibleConnection(state, connectionId)
      if (connection.ownerUserId !== null && connection.ownerUserId !== userId) fail(404, 'SOURCE_NOT_FOUND')
      const file = state.files.find((f) => f.id === sourceId && f.connectionId === connectionId) ?? fail(404, 'SOURCE_NOT_FOUND')
      if (file.processingStatus !== 'FAILED') fail(409, 'INVALID_STATE')
      const id = state.nextId()
      const record = {
        id, recordType: input.recordType, title: input.title, summary: input.summary, originalExcerpt: input.originalExcerpt,
        occurredOn: input.occurredOn, authorName: 'Manual recovery', ownerName: null, dueOn: input.dueOn ?? null,
        trustStatus: 'AUTO_DRAFT' as const, connectionId, confirmedByName: null, confirmedAt: null,
        version: 0, noteId: null, noteCode: null, revisions: [],
      }
      state.records.push(record)
      file.derivedRecordCodes = [...(file.derivedRecordCodes ?? []), `REC-${id}`]
      return recordDetail(state, record, userId)
    },
  }
}
