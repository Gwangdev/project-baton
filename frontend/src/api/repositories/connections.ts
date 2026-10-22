import type { HttpClient } from '../http'
import type {
  CollectedFile,
  CreateManualDraftInput,
  CreateSourceConnectionInput,
  Page,
  RecordDetail,
  SourceConnection,
  SourceConnectionDetail,
  UpdateSourceConnectionInput,
} from '../types'
import { readAll } from './listLimit'

/** 연동 설정·수집 파일·수동 재처리·수동 초안 (FR-05·19·21·22). */
export interface ConnectionRepository {
  listSourceConnections(projectId: number): Promise<Page<SourceConnection>>
  getSourceConnection(connectionId: number): Promise<SourceConnectionDetail>
  createSourceConnection(projectId: number, input: CreateSourceConnectionInput): Promise<SourceConnection>
  updateSourceConnection(connectionId: number, version: number, input: UpdateSourceConnectionInput): Promise<SourceConnection>
  deleteSourceConnection(connectionId: number, version: number): Promise<void>
  reconnectSourceConnection(connectionId: number): Promise<SourceConnection>
  listCollectedFiles(connectionId: number): Promise<Page<CollectedFile>>
  retrySourceProcessing(connectionId: number, sourceId: number): Promise<void>
  createManualDraft(connectionId: number, sourceId: number, input: CreateManualDraftInput): Promise<RecordDetail>
}

export function apiConnectionRepository(http: HttpClient): ConnectionRepository {
  return {
    listSourceConnections: (projectId) =>
      readAll((query) => http.get<Page<SourceConnection>>(`/projects/${projectId}/connections`, { ...query })),
    getSourceConnection: (connectionId) => http.get<SourceConnectionDetail>(`/connections/${connectionId}`),
    createSourceConnection: (projectId, input) =>
      http.command<SourceConnection>('POST', `/projects/${projectId}/connections`, { body: input }),
    updateSourceConnection: (connectionId, version, input) =>
      http.command<SourceConnection>('PATCH', `/connections/${connectionId}`, { body: input, version }),
    deleteSourceConnection: (connectionId, version) =>
      http.command<void>('DELETE', `/connections/${connectionId}`, { version }),
    reconnectSourceConnection: (connectionId) =>
      http.command<SourceConnection>('POST', `/connections/${connectionId}/reconnect`),
    listCollectedFiles: (connectionId) =>
      readAll((query) => http.get<Page<CollectedFile>>(`/connections/${connectionId}/files`, { ...query })),
    retrySourceProcessing: async (connectionId, sourceId) => {
      await http.command('POST', `/connections/${connectionId}/files/${sourceId}/processing-attempts`)
    },
    createManualDraft: (connectionId, sourceId, input) =>
      http.command<RecordDetail>('POST', `/connections/${connectionId}/files/${sourceId}/records`, { body: input }),
  }
}
