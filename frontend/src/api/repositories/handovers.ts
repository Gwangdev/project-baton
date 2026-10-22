import type { HttpClient } from '../http'
import type {
  CreateHandoverInput,
  Handover,
  HandoverAccepted,
  HandoverDetail,
  HandoverItem,
  HandoverItemAcknowledgement,
  Page,
  RegenerateHandoverInput,
} from '../types'
import { readAll } from './listLimit'

/** 담당자 교체 요청·패키지·충돌 해소·명시 확인·완료 결정 (FR-10~14, C05·C06). */
export interface HandoverRepository {
  listHandovers(projectId: number): Promise<Page<Handover>>
  requestHandover(projectId: number, input: CreateHandoverInput): Promise<HandoverAccepted>
  getHandover(handoverId: number): Promise<HandoverDetail>
  retryHandoverGeneration(handoverId: number, version: number): Promise<HandoverAccepted>
  regenerateHandover(handoverId: number, version: number, input: RegenerateHandoverInput): Promise<HandoverAccepted>
  listHandoverItems(handoverId: number): Promise<Page<HandoverItem>>
  resolveHandoverItem(handoverId: number, itemId: number, version: number, resolvedByRecordId: number, resolutionNote: string): Promise<HandoverItem>
  acknowledgeHandoverItem(handoverId: number, itemId: number, version: number): Promise<HandoverItemAcknowledgement>
  decideHandoverCompletion(handoverId: number, version: number, decision: 'APPROVE' | 'REQUEST_REVISION', comment?: string): Promise<HandoverDetail>
}

export function apiHandoverRepository(http: HttpClient): HandoverRepository {
  return {
    listHandovers: (projectId) => readAll((query) => http.get<Page<Handover>>(`/projects/${projectId}/handovers`, { ...query })),
    requestHandover: (projectId, input) =>
      http.command<HandoverAccepted>('POST', `/projects/${projectId}/handovers`, { body: input }),
    getHandover: (handoverId) => http.get<HandoverDetail>(`/handovers/${handoverId}`),
    retryHandoverGeneration: (handoverId, version) =>
      http.command<HandoverAccepted>('POST', `/handovers/${handoverId}/retry`, { version }),
    regenerateHandover: (handoverId, version, input) =>
      http.command<HandoverAccepted>('POST', `/handovers/${handoverId}/generations`, { body: input, version }),
    listHandoverItems: (handoverId) => readAll((query) => http.get<Page<HandoverItem>>(`/handovers/${handoverId}/items`, { ...query })),
    resolveHandoverItem: (handoverId, itemId, version, resolvedByRecordId, resolutionNote) =>
      http.command<HandoverItem>('PATCH', `/handovers/${handoverId}/items/${itemId}`, {
        body: { resolvedByRecordId, resolutionNote },
        version,
      }),
    acknowledgeHandoverItem: (handoverId, itemId, version) =>
      http.command<HandoverItemAcknowledgement>('POST', `/handovers/${handoverId}/items/${itemId}/acknowledgements`, { version }),
    decideHandoverCompletion: (handoverId, version, decision, comment) =>
      http.command<HandoverDetail>('POST', `/handovers/${handoverId}/approval`, {
        body: comment === undefined ? { decision } : { decision, comment },
        version,
      }),
  }
}
