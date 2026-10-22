import type { HttpClient } from '../http'
import type { CreateRecordRelationInput, Page, RecordDetail, RecordQuery, RecordRelation, ReviewRecordInput, WorkRecord } from '../types'

/** 기록 목록·상세·확인·관계 등록과 철회 (FR-02·03·06·12). 목록은 기록 cursor로 이어 읽는다. */
export interface RecordRepository {
  listRecords(projectId: number, query?: RecordQuery): Promise<Page<WorkRecord>>
  getRecord(recordId: number): Promise<RecordDetail>
  reviewRecord(recordId: number, version: number, input: ReviewRecordInput): Promise<RecordDetail>
  createRecordRelation(projectId: number, input: CreateRecordRelationInput): Promise<RecordRelation>
  withdrawRecordRelation(relationId: number, version: number, reason: string): Promise<RecordRelation>
}

export function apiRecordRepository(http: HttpClient): RecordRepository {
  return {
    listRecords: (projectId, query = {}) => http.get<Page<WorkRecord>>(`/projects/${projectId}/records`, {
      recordType: query.recordType,
      trustStatus: query.trustStatus,
      occurredFrom: query.occurredFrom,
      occurredTo: query.occurredTo,
      limit: query.limit,
      cursor: query.cursor,
    }),
    getRecord: (recordId) => http.get<RecordDetail>(`/records/${recordId}`),
    reviewRecord: (recordId, version, input) =>
      http.command<RecordDetail>('POST', `/records/${recordId}/review`, { body: input, version }),
    createRecordRelation: (projectId, input) =>
      http.command<RecordRelation>('POST', `/projects/${projectId}/record-relations`, { body: input }),
    withdrawRecordRelation: (relationId, version, reason) =>
      http.command<RecordRelation>('POST', `/record-relations/${relationId}/withdrawal`, { body: { reason }, version }),
  }
}
