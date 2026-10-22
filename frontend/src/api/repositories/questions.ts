import type { HttpClient } from '../http'
import { consumeQaStream, type QaStreamHandlers, type QaStreamResult } from '../qaStream'
import type { Page, QaAnswer } from '../types'
import { LIST_LIMIT } from './listLimit'

/** 근거 기반 Q&A와 그 이력 (FR-15·16, C07 스트림). */
export interface QuestionRepository {
  listQuestions(handoverId: number, cursor?: string): Promise<Page<QaAnswer>>
  askQuestion(handoverId: number, question: string, handlers: QaStreamHandlers, signal?: AbortSignal): Promise<QaStreamResult>
}

export function apiQuestionRepository(http: HttpClient): QuestionRepository {
  return {
    listQuestions: (handoverId, cursor) =>
      http.get<Page<QaAnswer>>(`/handovers/${handoverId}/questions`, { limit: LIST_LIMIT, cursor }),
    askQuestion: async (handoverId, question, handlers, signal) => {
      const response = await http.openStream(`/handovers/${handoverId}/questions`, { question }, signal)
      if (!response.body) return { kind: 'error', code: 'STREAM_INTERRUPTED', retryable: true, traceId: '' }
      return consumeQaStream(response.body, handlers)
    },
  }
}
