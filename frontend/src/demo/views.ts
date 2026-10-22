import type {
  PersonalNote,
  Project,
  ProjectDetail,
  RecordDetail,
  SourceConnection,
  SourceConnectionDetail,
  WorkRecord,
} from '@/api/types'
import { isAdmin, requireUser, roleOf } from './access'
import { DEMO_PROJECT_ID } from './scenario'
import { clone, fail, type DemoRecord, type DemoState } from './state'

/**
 * 메모리 상태를 사용자에게 보이는 DTO로 바꾼다. 서버의 조회 서비스처럼 보는 사람에 따라 보이는 범위가 다르다.
 * 다른 사람 개인 폴더의 초안과 원문, 다른 사람 메모의 code는 여기서 걸러진다 (D9·README §4.2).
 */

/** 다른 사람 개인 폴더의 초안은 폴더 소유자 말고는 아무에게도 보이지 않는다 (D9). */
export function visibleRecords(state: DemoState, userId: number): DemoRecord[] {
  const foreignFolders = new Set(state.connections.filter((c) => c.ownerUserId !== null && c.ownerUserId !== userId).map((c) => c.id))
  return state.records.filter((r) => !(r.trustStatus === 'AUTO_DRAFT' && r.connectionId !== null && foreignFolders.has(r.connectionId)))
}

export function projectDetail(state: DemoState, project: Project, userId: number): ProjectDetail {
  const visible = visibleRecords(state, userId)
  return {
    ...clone(project),
    myRole: roleOf(state, userId, project.id)!,
    pendingRecordCount: visible.filter((r) => r.trustStatus === 'AUTO_DRAFT').length,
    openRiskCount: visible.filter((r) => r.recordType === 'BLOCKER' || r.recordType === 'OPEN_ISSUE').length,
  }
}

export function visibleConnections(state: DemoState, userId: number): SourceConnectionDetail[] {
  return state.connections.filter((c) => isAdmin(state, userId) || c.ownerUserId === null || c.ownerUserId === userId)
}

export function visibleConnection(state: DemoState, connectionId: number): SourceConnectionDetail {
  const userId = requireUser(state)
  return visibleConnections(state, userId).find((c) => c.id === connectionId) ?? fail(404, 'CONNECTION_NOT_FOUND')
}

export function editableConnection(state: DemoState, connectionId: number): SourceConnectionDetail {
  const userId = requireUser(state)
  const connection = visibleConnection(state, connectionId)
  if (connection.ownerUserId !== userId && !isAdmin(state, userId)) fail(403, 'ACCESS_DENIED')
  return connection
}

export function connectionSummary(connection: SourceConnectionDetail): SourceConnection {
  const { lastStatus: _s, lastCollectedCount: _n, lastFailedCount: _f, lastMessage: _m, collectedFileCount: _c, draftCandidateCount: _d, ...summary } = connection
  return clone(summary)
}

export function sourceName(state: DemoState, record: DemoRecord): string {
  if (record.noteCode) return '공유 메모'
  return state.connections.find((c) => c.id === record.connectionId)?.displayName ?? '출처 없음'
}

export function workRecord(state: DemoState, record: DemoRecord, userId: number): WorkRecord {
  const connection = state.connections.find((c) => c.id === record.connectionId)
  const author = state.notes.find((n) => n.id === record.noteId)?.authorId
  const supersededBy = state.relations.find((rel) => rel.relationType === 'SUPERSEDES' && rel.toRecordId === record.id && !rel.withdrawnAt)
  return {
    id: record.id, version: record.version, code: `REC-${record.id}`, projectId: DEMO_PROJECT_ID,
    recordType: record.recordType, title: record.title, summary: record.summary, occurredOn: record.occurredOn,
    trustStatus: record.trustStatus, authorName: record.authorName, ownerName: record.ownerName, dueOn: record.dueOn,
    source: connection ? { id: connection.id, displayName: connection.displayName, channel: connection.channel } : null,
    // 공유 요약의 메모 code는 작성자에게만 보인다 (README §4.2).
    sourceNoteCode: author === userId ? record.noteCode : null,
    supersededByCode: supersededBy ? `REC-${supersededBy.fromRecordId}` : null,
  }
}

export function recordDetail(state: DemoState, record: DemoRecord, userId: number): RecordDetail {
  const connection = state.connections.find((c) => c.id === record.connectionId)
  const privateOriginal = connection?.ownerUserId != null && connection.ownerUserId !== userId
  return {
    ...workRecord(state, record, userId),
    originalExcerpt: privateOriginal ? '' : record.originalExcerpt,
    confirmedByName: record.confirmedByName,
    confirmedAt: record.confirmedAt,
    revisions: clone(record.revisions),
    relations: state.relations.filter((rel) => (rel.fromRecordId === record.id || rel.toRecordId === record.id) && !rel.withdrawnAt).map((rel) => {
      const relatedId = rel.fromRecordId === record.id ? rel.toRecordId : rel.fromRecordId
      const related = state.records.find((r) => r.id === relatedId)
      return { ...clone(rel), relatedRecordId: relatedId, relatedRecordCode: `REC-${relatedId}`, relatedRecordTitle: related?.title ?? '' }
    }),
  }
}

export function publicNote(note: PersonalNote & { authorId: number }): PersonalNote {
  const { authorId: _a, ...rest } = note
  return clone(rest)
}
