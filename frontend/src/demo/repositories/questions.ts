import type { QuestionRepository } from '@/api/repositories/questions'
import type { QaAnswer, QaCitation } from '@/api/types'
import { requireIncoming } from '../handoverLifecycle'
import { sourceName } from '../views'
import { NO_EVIDENCE_CONTACTS, QA_SCRIPT } from '../scenario'
import { clone, fail, page, type DemoState } from '../state'

/**
 * 데모 Q&A. 핵심어로 고른 답변을 C07과 같은 순서(meta → evidence → delta → done)로 흘린다.
 * 맞는 핵심어가 없으면 답을 만들지 않고 NO_EVIDENCE와 확인할 대상을 돌려준다.
 */
export function demoQuestionRepository(state: DemoState): QuestionRepository {
  return {
    listQuestions: async (handoverId) => {
      await state.pause()
      requireIncoming(state, handoverId)
      return page(clone(state.qa))
    },
    askQuestion: async (handoverId, question, handlers) => {
      await state.pause()
      const h = requireIncoming(state, handoverId)
      if (!question.trim() || question.length > 2000) fail(400, 'INVALID_INPUT')
      const questionId = state.nextId()
      state.qa.push({ id: questionId, role: 'USER', content: question, createdAt: new Date().toISOString() })
      handlers.onMeta?.({ questionId, generation: h.detail.generation, traceId: 'demo' })
      const hit = QA_SCRIPT.find((s) => s.keywords.some((k) => question.includes(k)))
      const citations: QaCitation[] = (hit?.citations ?? []).map((c) => {
        const record = state.records.find((r) => r.id === c.recordId)!
        return {
          recordId: record.id, recordCode: `REC-${record.id}`, recordTitle: record.title,
          sourceName: sourceName(state, record), occurredOn: record.occurredOn,
          trustStatusAtAnswer: record.trustStatus, isPrimary: c.isPrimary,
        }
      })
      handlers.onEvidence?.(citations)
      for (const sentence of hit?.sentences ?? []) {
        await state.pause()
        handlers.onDelta?.(sentence)
      }
      const answer: QaAnswer = hit
        ? { id: state.nextId(), role: 'ASSISTANT', content: hit.sentences.join(' '), createdAt: new Date().toISOString(),
            answerStatus: 'ANSWERED', citations, suggestedContacts: null, suggestedDocuments: null }
        : { id: state.nextId(), role: 'ASSISTANT', content: '', createdAt: new Date().toISOString(),
            answerStatus: 'NO_EVIDENCE', citations: [], ...NO_EVIDENCE_CONTACTS }
      state.qa.push(answer)
      return { kind: 'done', answer: clone(answer) }
    },
  }
}
