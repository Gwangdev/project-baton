import type { AssessmentRepository } from '@/api/repositories/assessments'
import type { AssessmentAttempt } from '@/api/types'
import { advance, requireHandoverReader, requireIncoming } from '../handoverLifecycle'
import { ASSESSMENT } from '../scenario'
import { clone, fail, type DemoState } from '../state'

/**
 * 데모 업무 준비도. 서버(C08)처럼 부분 문자열이 아니라 정규화한 뒤 완전 일치로 채점한다.
 * 보완 요청 상태에서 다시 제출하면 READY로 돌아간다.
 */
const normalize = (text: string) => text.normalize('NFKC').trim().replace(/\s+/g, ' ').toLowerCase()

export function demoAssessmentRepository(state: DemoState): AssessmentRepository {
  return {
    getAssessment: async (handoverId) => {
      await state.pause()
      const userId = requireHandoverReader(state, handoverId)
      const h = advance(state)
      if (userId === h.outgoingId) fail(403, 'ACCESS_DENIED')
      if (!h.published) fail(409, 'INVALID_STATE')
      return {
        generation: h.detail.generation,
        status: h.attempts.length ? 'SUBMITTED' : 'NOT_STARTED',
        passThreshold: ASSESSMENT.length,
        questions: ASSESSMENT.map((q) => ({
          id: q.questionNo, questionNo: q.questionNo, questionType: 'SHORT_ANSWER', prompt: q.prompt,
          linkedRecordCode: `REC-${q.linkedRecordId}`, choices: null,
        })),
        attempts: clone(h.attempts),
      }
    },
    submitAssessmentAttempt: async (handoverId, answers) => {
      await state.pause()
      const h = requireIncoming(state, handoverId)
      const ids = answers.map((a) => a.questionId).sort()
      if (ids.length !== ASSESSMENT.length || ids.some((id, i) => id !== i + 1) || answers.some((a) => !a.answerText.trim())) {
        fail(400, 'INVALID_INPUT')
      }
      const graded = ASSESSMENT.map((q) => {
        const given = answers.find((a) => a.questionId === q.questionNo)!.answerText
        const isCorrect = q.acceptedAnswers.some((accepted) => normalize(accepted) === normalize(given))
        return { questionId: q.questionNo, questionNo: q.questionNo, answerText: given, isCorrect,
          linkedRecordCode: isCorrect ? null : `REC-${q.linkedRecordId}` }
      })
      const attempt: AssessmentAttempt = {
        attemptNo: h.attempts.length + 1, score: graded.filter((g) => g.isCorrect).length, total: graded.length,
        submittedAt: new Date().toISOString(), answers: graded,
      }
      h.attempts.push(attempt)
      if (h.detail.status === 'REVIEW_REQUESTED') {
        h.detail.status = 'READY'
        h.detail.version += 1
      }
      return clone(attempt)
    },
  }
}
